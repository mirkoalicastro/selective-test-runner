package io.github.testimpact.mojo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.testimpact.change.ChangeDetector;
import io.github.testimpact.common.DumpReader;
import io.github.testimpact.common.PluginPaths;
import io.github.testimpact.report.ImpactReport;
import io.github.testimpact.store.CoverageMap;
import io.github.testimpact.store.CoverageMapStore;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Merges the per-build agent dump into the persistent coverage map, writes the JSON report,
 * updates the build counter, and prints a human-readable summary. Bound to {@code verify}.
 */
@Mojo(name = "report",
        defaultPhase = LifecyclePhase.VERIFY,
        threadSafe = true)
public class ReportMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "testimpact.coverageMapPath",
            defaultValue = "${project.build.directory}/.test-impact/coverage.db")
    private File coverageMapPath;

    @Parameter(property = "testimpact.fullRunInterval", defaultValue = "50")
    private int fullRunInterval;

    @Override
    public void execute() throws MojoExecutionException {
        String buildDir = project.getBuild().getDirectory();
        Path dump = PluginPaths.dump(buildDir);
        Path mapPath = coverageMapPath.toPath();
        Path selRec = PluginPaths.selectionRecord(buildDir);

        // 1. Load existing map (or create new).
        CoverageMap map = null;
        try {
            map = CoverageMapStore.load(mapPath);
        } catch (IOException e) {
            getLog().warn("test-impact: existing map unreadable, will rebuild — " + e.getMessage());
        }
        boolean isFresh = map == null || map.version() != CoverageMap.FORMAT_VERSION;
        if (isFresh) map = new CoverageMap();

        // 2. If dump exists, merge it.
        Map<String, Set<String>> dumpEntries = Collections.emptyMap();
        try {
            if (Files.exists(dump)) {
                dumpEntries = DumpReader.read(dump);
                for (Map.Entry<String, Set<String>> e : dumpEntries.entrySet()) {
                    // For full runs, replace the entry; for selections, merge.
                    map.replace(e.getKey(), e.getValue());
                }
                if (!dumpEntries.isEmpty()) {
                    map.setBuildHash(new ChangeDetector(project.getBasedir()).headCommit());
                    map.setTimestamp(System.currentTimeMillis());
                }
            }
        } catch (IOException e) {
            getLog().warn("test-impact: failed to read dump file: " + e.getMessage());
        }

        // 3. Persist map.
        try {
            CoverageMapStore.save(mapPath, map);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write coverage map", e);
        }

        // 4. Update build counter.
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
        if (!Files.exists(selRec)) return true; // no record == no select ran == treat as full
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

    private ImpactReport buildReport(Path selRec, CoverageMap map, int dumpSize, int newCounter, boolean wasFullRun) {
        ImpactReport r = new ImpactReport();
        r.buildHash = map.buildHash();
        r.baseline = "lastCommit";
        r.changedClasses = new ArrayList<>();
        r.fallbackMode = wasFullRun;
        r.mapAge = newCounter < 0 ? 0 : newCounter;
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
                r.durationMs = Math.max(0L,
                        System.currentTimeMillis() - n.path("startedMillis").asLong(System.currentTimeMillis()));
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
        getLog().info("maven-test-impact-plugin");
        getLog().info("  Changed classes : " + r.changedClasses.size());
        for (String c : new TreeSet<>(r.changedClasses)) getLog().info("    └─ " + c);
        getLog().info("  Coverage map   : " + r.mapEntries + " entries, " + r.mapAge + " builds old");
        if (wasFullRun) {
            getLog().info("  Selected tests : full run");
        } else {
            int skipped = Math.max(0, r.totalTests - r.selectedTests);
            getLog().info("  Selected tests : " + r.selectedTests + " / " + r.totalTests
                    + " (" + String.format("%.1f", 100.0 - r.reductionPct) + "%)");
            getLog().info("  Skipped tests  : " + skipped);
        }
        getLog().info("  Fallback mode  : " + r.fallbackMode);
    }

    /** Hand for tests. */
    static List<String> placeholder() { return Collections.emptyList(); }
}
