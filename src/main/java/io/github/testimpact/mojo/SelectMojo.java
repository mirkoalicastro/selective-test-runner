package io.github.testimpact.mojo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.testimpact.change.Baseline;
import io.github.testimpact.change.ChangeDetector;
import io.github.testimpact.change.ClassResolver;
import io.github.testimpact.common.PluginPaths;
import io.github.testimpact.common.ReactorScope;
import io.github.testimpact.resolve.ImpactResolver;
import io.github.testimpact.resolve.Selection;
import io.github.testimpact.store.CoverageMap;
import io.github.testimpact.store.CoverageMapStore;
import org.apache.maven.execution.MavenSession;
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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Computes the impacted test set and feeds it into Surefire via the {@code test} property.
 * Bound to {@code test} (before {@code surefire:test}) per spec §5.2.
 *
 * Multi-module behaviour:
 *   - Reads the shared coverage map from the reactor root.
 *   - Filters changed sources to those owned by modules reachable upstream from this one
 *     via the reactor dependency graph.
 *   - Filters selected tests to those whose .class lives in this module's test output dir,
 *     so each module's Surefire only ever sees its own tests.
 *
 * If the resolver returns a full-run, this mojo leaves Surefire's includes untouched.
 */
@Mojo(name = "select",
        // PROCESS_TEST_CLASSES — must run before surefire:test. Binding to the `test`
        // phase races surefire: when the user's pom declares surefire before this plugin,
        // surefire's `test` goal fires first and the selection has no effect.
        defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES,
        threadSafe = true)
public class SelectMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    /** Baseline strategy: lastCommit, lastTag, or lastFullRun. */
    @Parameter(property = "testimpact.baseline", defaultValue = "lastCommit")
    private String baseline;

    /** Force a full run every N builds. 0 disables the timer. */
    @Parameter(property = "testimpact.fullRunInterval", defaultValue = "50")
    private int fullRunInterval;

    /**
     * Path to the persistent coverage map. Defaults to the reactor root's build dir so
     * every module shares one map. Override to pin to a per-module location.
     */
    @Parameter(property = "testimpact.coverageMapPath")
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

        File topBasedir = session.getTopLevelProject() == null
                ? project.getBasedir()
                : session.getTopLevelProject().getBasedir();
        ChangeDetector cd = new ChangeDetector(topBasedir);
        File repoRoot = cd.repoRoot();
        ReactorScope scope = new ReactorScope(session, project, repoRoot);

        Path mapPath = resolveMapPath(scope);
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

        Set<String> changedSources = cd.changedSources(base, map == null ? null : map.buildHash());
        Set<String> filteredSources = changedSources == null ? null : scope.filterToReachable(changedSources);
        Set<String> changedClasses = filteredSources == null
                ? null
                : new ClassResolver(scope.allOutputDirs()).resolve(filteredSources);

        int buildsSinceFull = readBuildCounter();
        ImpactResolver resolver = new ImpactResolver(fullRunInterval, failOnEmptySelection);
        Selection sel = resolver.resolve(map, changedClasses, buildsSinceFull);

        if (sel.mode() == Selection.Mode.SELECTED) {
            Set<String> moduleClasses = filterToCurrentModule(sel, scope);
            if (moduleClasses.isEmpty()) {
                // Nothing to run in this module — set surefire's test filter to a non-matching pattern
                // so surefire skips this module's tests instead of running everything.
                String empty = "io.github.testimpact.NoTestMatches#nothing";
                project.getProperties().setProperty("test", empty);
                project.getProperties().setProperty("surefire.test", empty);
                session.getUserProperties().setProperty("test", empty);
                session.getUserProperties().setProperty("surefire.test", empty);
                getLog().info("test-impact: no impacted tests in this module");
            } else {
                String csv = String.join(",", moduleClasses);
                project.getProperties().setProperty("test", csv);
                project.getProperties().setProperty("surefire.test", csv);
                // Surefire's `test` parameter resolves from user properties first; setting only
                // project properties leaves the filter unset in practice.
                session.getUserProperties().setProperty("test", csv);
                session.getUserProperties().setProperty("surefire.test", csv);
                getLog().info("test-impact: selected " + sel.selectedTestIds().size() + " tests across "
                        + moduleClasses.size() + " classes (this module)");
            }
        } else {
            getLog().info("test-impact: full run (" + sel.reason() + ")");
            if (skipOnCacheMiss && map == null) {
                getLog().warn("test-impact: skipOnCacheMiss=true — leaving Surefire to run normally");
            }
        }

        writeSelectionRecord(sel, base, map, start);
    }

    private Path resolveMapPath(ReactorScope scope) {
        if (coverageMapPath != null) return coverageMapPath.toPath();
        return PluginPaths.coverageMap(scope.reactorRootBuildDir());
    }

    /** Restrict the selection to tests whose class lives under this module's testOutputDirectory. */
    private Set<String> filterToCurrentModule(Selection sel, ReactorScope scope) {
        Set<String> classes = new LinkedHashSet<>();
        for (String testId : sel.selectedTestIds()) {
            int hash = testId.indexOf('#');
            String fqn = hash < 0 ? testId : testId.substring(0, hash);
            if (scope.isTestInCurrentModule(fqn)) classes.add(fqn);
        }
        return classes;
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
