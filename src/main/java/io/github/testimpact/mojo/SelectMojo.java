package io.github.testimpact.mojo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.testimpact.change.Baseline;
import io.github.testimpact.change.ChangeDetector;
import io.github.testimpact.change.ClassResolver;
import io.github.testimpact.common.PluginPaths;
import io.github.testimpact.resolve.ImpactResolver;
import io.github.testimpact.resolve.Selection;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Computes the impacted test set and feeds it into Surefire via the {@code test} property.
 * Bound to {@code test} (before {@code surefire:test}) per spec §5.2.
 *
 * If the resolver returns a full-run, this mojo leaves Surefire's includes untouched.
 */
@Mojo(name = "select",
        defaultPhase = LifecyclePhase.TEST,
        threadSafe = true)
public class SelectMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** Baseline strategy: lastCommit, lastTag, or lastFullRun. */
    @Parameter(property = "testimpact.baseline", defaultValue = "lastCommit")
    private String baseline;

    /** Force a full run every N builds. 0 disables the timer. */
    @Parameter(property = "testimpact.fullRunInterval", defaultValue = "50")
    private int fullRunInterval;

    /** Path to the persistent coverage map. */
    @Parameter(property = "testimpact.coverageMapPath",
            defaultValue = "${project.build.directory}/.test-impact/coverage.db")
    private File coverageMapPath;

    /** Treat an empty intersection as a fatal error rather than triggering a full run. */
    @Parameter(property = "testimpact.failOnEmptySelection", defaultValue = "false")
    private boolean failOnEmptySelection;

    /** When true, suppress the full-run fallback on a cache miss and emit a warning instead. */
    @Parameter(property = "testimpact.skipOnCacheMiss", defaultValue = "false")
    private boolean skipOnCacheMiss;

    @Override
    public void execute() throws MojoExecutionException {
        long start = System.currentTimeMillis();
        Path mapPath = coverageMapPath.toPath();
        CoverageMap map;
        try {
            map = CoverageMapStore.load(mapPath);
        } catch (IOException e) {
            getLog().warn("test-impact: failed to load coverage map (" + e.getMessage() + ") — full run");
            map = null;
        }

        Baseline base;
        try {
            base = Baseline.parse(baseline);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        ChangeDetector cd = new ChangeDetector(project.getBasedir());
        Set<String> changedSources = cd.changedSources(base, map == null ? null : map.buildHash());
        Set<String> changedClasses = changedSources == null
                ? null
                : ClassResolver.of(project.getBuild().getOutputDirectory(),
                                   project.getBuild().getTestOutputDirectory()).resolve(changedSources);

        int buildsSinceFull = readBuildCounter();
        ImpactResolver resolver = new ImpactResolver(fullRunInterval, failOnEmptySelection);
        Selection sel = resolver.resolve(map, changedClasses, buildsSinceFull);

        if (sel.mode() == Selection.Mode.SELECTED) {
            String csv = String.join(",", sel.selectedTestClasses());
            project.getProperties().setProperty("test", csv);
            // Surefire 3.x respects -Dtest; also set the modern surefire.test property as a belt-and-braces.
            project.getProperties().setProperty("surefire.test", csv);
            getLog().info("test-impact: selected " + sel.selectedTestIds().size() + " tests across "
                    + sel.selectedTestClasses().size() + " classes");
        } else {
            getLog().info("test-impact: full run (" + sel.reason() + ")");
            if (skipOnCacheMiss && map == null) {
                getLog().warn("test-impact: skipOnCacheMiss=true — leaving Surefire to run normally");
            }
            // Mark next build as a coverage-refresh build so collect runs.
            // (No-op here: collect already runs in process-test-classes; we record state for report.)
        }

        writeSelectionRecord(sel, base, map, start);
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

    private void writeSelectionRecord(Selection sel, Baseline base, CoverageMap map, long startMillis) {
        Path p = PluginPaths.selectionRecord(project.getBuild().getDirectory());
        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("mode", sel.mode().name());
            record.put("reason", sel.reason());
            record.put("baseline", base.name());
            record.put("changedClasses", new TreeSet<>(sel.changedClasses()));
            record.put("selectedTests", new TreeSet<>(sel.selectedTestIds()));
            record.put("mapEntries", map == null ? 0 : map.size());
            record.put("mapBuildHash", map == null ? "" : map.buildHash());
            record.put("startedMillis", startMillis);
            new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValue(p.toFile(), record);
        } catch (IOException e) {
            getLog().debug("test-impact: failed to write selection record: " + e.getMessage());
        }
    }
}
