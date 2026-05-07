package io.github.mirkoalicastro.mojo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mirkoalicastro.change.ChangeDetector;
import io.github.mirkoalicastro.common.DumpReader;
import io.github.mirkoalicastro.common.PluginPaths;
import io.github.mirkoalicastro.common.ReactorScope;
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
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Merges this module's per-build agent dump into the reactor-shared coverage map, writes the
 * per-module JSON report, updates the per-module build counter, and prints a human-readable
 * summary. Bound to {@code verify}.
 *
 * <p>Concurrency: {@link CoverageMapStore#mergeAndSave} serialises read-modify-write under an
 * OS-level file lock so concurrent module reports under {@code mvn -T} are safe.
 */
@Mojo(name = "report", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public class ReportMojo extends AbstractMojo {

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Parameter(defaultValue = "${session}", readonly = true, required = true)
  private MavenSession session;

  /** Path to the persistent coverage map. Defaults to the reactor root's build dir. */
  @Parameter(property = "testimpact.coverageMapPath")
  private File coverageMapPath;

  @Parameter(property = "testimpact.fullRunInterval", defaultValue = "50")
  private int fullRunInterval;

  @Override
  public void execute() throws MojoExecutionException {
    String buildDir = project.getBuild().getDirectory();
    Path dump = PluginPaths.dump(buildDir);
    Path selRec = PluginPaths.selectionRecord(buildDir);

    File topBasedir =
        session.getTopLevelProject() == null
            ? project.getBasedir()
            : session.getTopLevelProject().getBasedir();
    ChangeDetector cd = new ChangeDetector(topBasedir);
    ReactorScope scope = new ReactorScope(session, project, cd.repoRoot());
    Path mapPath =
        coverageMapPath != null
            ? coverageMapPath.toPath()
            : PluginPaths.coverageMap(scope.reactorRootBuildDir());

    // 1. Read this module's dump (if it exists).
    Map<String, Set<String>> dumpEntries = Collections.emptyMap();
    try {
      if (Files.exists(dump)) dumpEntries = DumpReader.read(dump);
    } catch (IOException e) {
      getLog().warn("test-impact: failed to read dump file: " + e.getMessage());
    }

    // 2. Merge into the shared map under a file lock.
    try {
      String buildHash = cd.headCommit();
      CoverageMapStore.mergeAndSave(mapPath, dumpEntries, buildHash);
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to update shared coverage map", e);
    }

    // 3. Read the post-merge map for reporting (snapshot may include other modules' updates).
    CoverageMap map;
    try {
      map = CoverageMapStore.load(mapPath);
      if (map == null) map = new CoverageMap();
    } catch (IOException e) {
      getLog().warn("test-impact: post-merge map unreadable: " + e.getMessage());
      map = new CoverageMap();
    }

    // 4. Update this module's build counter.
    boolean wasFullRun = wasFullRun(selRec);
    int newCounter;
    try {
      int current = readBuildCounter();
      newCounter = wasFullRun ? 0 : current + 1;
      Path counter = PluginPaths.buildCounter(buildDir);
      if (counter.getParent() != null) Files.createDirectories(counter.getParent());
      Files.write(counter, Integer.toString(newCounter).getBytes());
    } catch (IOException e) {
      getLog().debug("test-impact: failed to update build counter: " + e.getMessage());
      newCounter = -1;
    }

    // 5. Build & write JSON report + console summary.
    ImpactReport report = buildReport(selRec, map, dumpEntries.size(), newCounter, wasFullRun);
    try {
      report.writeTo(PluginPaths.report(buildDir));
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to write JSON report", e);
    }
    printConsoleSummary(report, wasFullRun);
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

  private int readBuildCounter() {
    Path p = PluginPaths.buildCounter(project.getBuild().getDirectory());
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

    // For full runs, "selected" = total tests run (== dump size if collection happened).
    int selected = wasFullRun ? dumpSize : selectedIds.size();
    int total = wasFullRun ? dumpSize : Math.max(map.size(), dumpSize);
    r.selectedTests = selected;
    r.totalTests = total;
    r.reductionPct = total == 0 ? 0.0 : (1.0 - ((double) selected / (double) total)) * 100.0;
    return r;
  }

  private void printConsoleSummary(ImpactReport r, boolean wasFullRun) {
    getLog().info("test-impact-maven-plugin");
    getLog().info("  Changed classes : " + r.changedClasses.size());
    for (String c : new TreeSet<>(r.changedClasses)) getLog().info("    └─ " + c);
    getLog().info("  Coverage map   : " + r.mapEntries + " entries, " + r.mapAge + " builds old");
    if (wasFullRun) {
      getLog().info("  Selected tests : full run");
    } else {
      int skipped = Math.max(0, r.totalTests - r.selectedTests);
      getLog()
          .info(
              "  Selected tests : "
                  + r.selectedTests
                  + " / "
                  + r.totalTests
                  + " ("
                  + String.format("%.1f", 100.0 - r.reductionPct)
                  + "%)");
      getLog().info("  Skipped tests  : " + skipped);
    }
    getLog().info("  Fallback mode  : " + r.fallbackMode);
  }
}
