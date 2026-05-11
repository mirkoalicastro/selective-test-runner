package io.github.mirkoalicastro.gradle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mirkoalicastro.change.ChangeDetector;
import io.github.mirkoalicastro.common.DumpReader;
import io.github.mirkoalicastro.common.PluginPaths;
import io.github.mirkoalicastro.report.ImpactReport;
import io.github.mirkoalicastro.store.CoverageMap;
import io.github.mirkoalicastro.store.CoverageMapStore;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskAction;

/**
 * Merges this module's agent dump into the shared coverage map, writes a JSON report, updates the
 * build counter, and prints a summary.
 */
public class TestImpactReportTask extends DefaultTask {

  private TestImpactExtension ext;
  private Project targetProject;

  void configure(TestImpactExtension ext, Project project) {
    this.ext = ext;
    this.targetProject = project;
    setDescription("Merges coverage dump into the shared map and generates a report");
    setGroup("verification");
  }

  /** Executes the report task. */
  @TaskAction
  public void report() throws IOException {
    String buildDir = targetProject.getLayout().getBuildDirectory().getAsFile().get().toString();
    Path dump = PluginPaths.dump(buildDir);
    Path selRec = PluginPaths.selectionRecord(buildDir);

    File rootDir = targetProject.getRootProject().getProjectDir();
    ChangeDetector cd = new ChangeDetector(rootDir);
    GradleProjectScope scope = new GradleProjectScope(targetProject, cd.repoRoot());

    Path mapPath;
    if (ext.getCoverageMapPath().isPresent()) {
      mapPath = ext.getCoverageMapPath().getAsFile().get().toPath();
    } else {
      mapPath = PluginPaths.coverageMap(scope.reactorRootBuildDir());
    }

    // 1. Read this module's dump.
    Map<String, Set<String>> dumpEntries = Collections.emptyMap();
    try {
      if (Files.exists(dump)) dumpEntries = DumpReader.read(dump);
    } catch (IOException e) {
      getLogger().warn("test-impact: failed to read dump file: " + e.getMessage());
    }

    // 2. Merge into the shared map under a file lock.
    String buildHash = cd.headCommit();
    CoverageMapStore.mergeAndSave(mapPath, dumpEntries, buildHash);

    // 3. Read the post-merge map for reporting.
    CoverageMap map;
    try {
      map = CoverageMapStore.load(mapPath);
      if (map == null) map = new CoverageMap();
    } catch (IOException e) {
      getLogger().warn("test-impact: post-merge map unreadable: " + e.getMessage());
      map = new CoverageMap();
    }

    // 4. Update this module's build counter.
    boolean wasFullRun = wasFullRun(selRec);
    int newCounter;
    try {
      int current = readBuildCounter(buildDir);
      newCounter = wasFullRun ? 0 : current + 1;
      Path counter = PluginPaths.buildCounter(buildDir);
      if (counter.getParent() != null) Files.createDirectories(counter.getParent());
      Files.write(counter, Integer.toString(newCounter).getBytes());
    } catch (IOException e) {
      getLogger().debug("test-impact: failed to update build counter: " + e.getMessage());
      newCounter = -1;
    }

    // 5. Build & write JSON report + console summary.
    ImpactReport rpt = buildReport(selRec, map, dumpEntries.size(), newCounter, wasFullRun);
    rpt.writeTo(PluginPaths.report(buildDir));
    printConsoleSummary(rpt, wasFullRun);
  }

  private boolean wasFullRun(Path selRec) {
    if (!Files.exists(selRec)) return true;
    try {
      JsonNode n = new ObjectMapper().readTree(selRec.toFile());
      return "FULL_RUN".equals(n.path("mode").asText());
    } catch (IOException e) {
      return true;
    }
  }

  private int readBuildCounter(String buildDir) {
    Path p = PluginPaths.buildCounter(buildDir);
    if (!Files.exists(p)) return 0;
    try {
      return Integer.parseInt(new String(Files.readAllBytes(p)).trim());
    } catch (Exception e) {
      return 0;
    }
  }

  private ImpactReport buildReport(
      Path selRec, CoverageMap map, int dumpSize, int newCounter, boolean wasFullRun) {
    ImpactReport r = new ImpactReport();
    r.buildHash = map.buildHash();
    r.baseline = "lastCommit";
    r.changedClasses = new ArrayList<>();
    r.fallbackMode = wasFullRun;
    r.mapAge = Math.max(newCounter, 0);
    r.mapEntries = map.size();
    r.durationMs = 0L;

    Set<String> selectedIds = new HashSet<>();
    if (Files.exists(selRec)) {
      try {
        JsonNode n = new ObjectMapper().readTree(selRec.toFile());
        r.baseline = n.path("baseline").asText("lastCommit");
        JsonNode cc = n.path("changedClasses");
        if (cc.isArray()) {
          Iterator<JsonNode> it = cc.elements();
          while (it.hasNext()) r.changedClasses.add(it.next().asText());
        }
        JsonNode st = n.path("selectedTests");
        if (st.isArray()) {
          Iterator<JsonNode> it = st.elements();
          while (it.hasNext()) selectedIds.add(it.next().asText());
        }
        r.durationMs =
            Math.max(
                0L,
                System.currentTimeMillis()
                    - n.path("startedMillis").asLong(System.currentTimeMillis()));
      } catch (IOException ignored) {
      }
    }

    int selected = wasFullRun ? dumpSize : selectedIds.size();
    int total = wasFullRun ? dumpSize : Math.max(map.size(), dumpSize);
    r.selectedTests = selected;
    r.totalTests = total;
    r.reductionPct = total == 0 ? 0.0 : (1.0 - ((double) selected / (double) total)) * 100.0;
    return r;
  }

  private void printConsoleSummary(ImpactReport r, boolean wasFullRun) {
    getLogger().lifecycle("test-impact-gradle-plugin");
    getLogger().lifecycle("  Changed classes : " + r.changedClasses.size());
    for (String c : new TreeSet<>(r.changedClasses)) getLogger().lifecycle("    └─ " + c);
    getLogger()
        .lifecycle("  Coverage map   : " + r.mapEntries + " entries, " + r.mapAge + " builds old");
    if (wasFullRun) {
      getLogger().lifecycle("  Selected tests : full run");
    } else {
      int skipped = Math.max(0, r.totalTests - r.selectedTests);
      getLogger()
          .lifecycle(
              "  Selected tests : "
                  + r.selectedTests
                  + " / "
                  + r.totalTests
                  + " ("
                  + String.format("%.1f", 100.0 - r.reductionPct)
                  + "%)");
      getLogger().lifecycle("  Skipped tests  : " + skipped);
    }
    getLogger().lifecycle("  Fallback mode  : " + r.fallbackMode);
  }
}
