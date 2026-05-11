package io.github.mirkoalicastro.gradle;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mirkoalicastro.change.Baseline;
import io.github.mirkoalicastro.change.ChangeDetector;
import io.github.mirkoalicastro.change.ClassResolver;
import io.github.mirkoalicastro.common.PluginPaths;
import io.github.mirkoalicastro.resolve.ImpactResolver;
import io.github.mirkoalicastro.resolve.Selection;
import io.github.mirkoalicastro.store.CoverageMap;
import io.github.mirkoalicastro.store.CoverageMapStore;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.logging.Logger;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.testing.Test;

/**
 * Combined collect and select action registered as {@code doFirst} on every {@link Test} task.
 *
 * <p><b>Collect phase:</b> resolves the Java agent JAR and appends {@code -javaagent} to the test
 * JVM arguments for bytecode-level coverage collection.
 *
 * <p><b>Select phase:</b> detects changed source files via Git, resolves them to compiled classes,
 * computes the impacted test set, and configures the test task's filter.
 */
class TestImpactAction implements Action<Task> {

  private final TestImpactExtension ext;
  private final Configuration agentConfig;
  private final Project project;

  TestImpactAction(TestImpactExtension ext, Configuration agentConfig, Project project) {
    this.ext = ext;
    this.agentConfig = agentConfig;
    this.project = project;
  }

  @Override
  public void execute(Task task) {
    Test testTask = (Test) task;
    Logger log = project.getLogger();

    collect(testTask, log);
    select(testTask, log);
  }

  private void collect(Test testTask, Logger log) {
    File agentJar;
    try {
      agentJar = agentConfig.resolve().iterator().next();
    } catch (Exception e) {
      log.warn(
          "test-impact: could not resolve agent JAR — collection skipped, full suite will run");
      return;
    }

    String buildDir = project.getLayout().getBuildDirectory().getAsFile().get().toString();
    Path dump = PluginPaths.dump(buildDir);
    try {
      Files.createDirectories(dump.getParent());
    } catch (IOException e) {
      log.warn("test-impact: failed to create dump directory: " + e.getMessage());
      return;
    }

    String includes = ext.getIncludes().getOrElse("");
    if (includes.isEmpty()) {
      includes = detectProjectPackages();
      if (includes != null) {
        log.info("test-impact: auto-detected includes: " + includes);
      }
    }
    String excludes = ext.getExcludes().getOrElse("");

    List<String> args = new ArrayList<>();
    args.add("-javaagent:" + quote(agentJar.getAbsolutePath()));
    args.add("-Dtestimpact.dump=" + quote(dump.toString()));
    if (includes != null && !includes.isEmpty()) {
      args.add("-Dtestimpact.includes=" + quote(includes));
    }
    if (!excludes.isEmpty()) {
      args.add("-Dtestimpact.excludes=" + quote(excludes));
    }
    testTask.jvmArgs(args);

    log.info("test-impact: attached agent: " + agentJar.getName());
    log.debug("test-impact jvmArgs: " + args);
  }

  private void select(Test testTask, Logger log) {
    long start = System.currentTimeMillis();
    String buildDir = project.getLayout().getBuildDirectory().getAsFile().get().toString();
    File rootDir = project.getRootProject().getProjectDir();

    ChangeDetector cd = new ChangeDetector(rootDir);
    File repoRoot = cd.repoRoot();
    GradleProjectScope scope = new GradleProjectScope(project, repoRoot);

    Path mapPath = resolveMapPath(scope);
    CoverageMap map;
    try {
      map = CoverageMapStore.load(mapPath);
    } catch (IOException e) {
      log.warn("test-impact: failed to load coverage map (" + e.getMessage() + ") — full run");
      map = null;
    }

    Baseline base;
    try {
      base = Baseline.parse(ext.getBaseline().get());
    } catch (IllegalArgumentException e) {
      log.error("test-impact: invalid baseline: " + e.getMessage());
      return;
    }

    Set<String> changedSources = cd.changedSources(base, map == null ? null : map.buildHash());
    Set<String> filteredSources =
        changedSources == null ? null : scope.filterToReachable(changedSources);
    Set<String> changedClasses =
        filteredSources == null
            ? null
            : new ClassResolver(scope.allOutputDirs()).resolve(filteredSources);

    int buildsSinceFull = readBuildCounter(buildDir);
    ImpactResolver resolver =
        new ImpactResolver(ext.getFullRunInterval().get(), ext.getFailOnEmptySelection().get());
    Selection sel = resolver.resolve(map, changedClasses, buildsSinceFull);

    if (sel.mode() == Selection.Mode.SELECTED) {
      Set<String> moduleClasses = filterToCurrentModule(sel, scope);
      if (moduleClasses.isEmpty()) {
        testTask.getFilter().setIncludePatterns("io.github.mirkoalicastro.NoTestMatches");
        log.info("test-impact: no impacted tests in this module");
      } else {
        testTask.getFilter().setIncludePatterns(moduleClasses.toArray(new String[0]));
        log.info(
            "test-impact: selected "
                + sel.selectedTestIds().size()
                + " tests across "
                + moduleClasses.size()
                + " classes (this module)");
      }
    } else {
      log.info("test-impact: full run (" + sel.reason() + ")");
      if (ext.getSkipOnCacheMiss().get() && map == null) {
        log.warn("test-impact: skipOnCacheMiss=true — leaving test task to run normally");
      }
    }

    writeSelectionRecord(sel, base, map, start, buildDir, log);
  }

  private Path resolveMapPath(GradleProjectScope scope) {
    if (ext.getCoverageMapPath().isPresent()) {
      return ext.getCoverageMapPath().getAsFile().get().toPath();
    }
    return PluginPaths.coverageMap(scope.reactorRootBuildDir());
  }

  private Set<String> filterToCurrentModule(Selection sel, GradleProjectScope scope) {
    Set<String> classes = new LinkedHashSet<>();
    for (String testId : sel.selectedTestIds()) {
      int hash = testId.indexOf('#');
      String fqn = hash < 0 ? testId : testId.substring(0, hash);
      if (scope.isTestInCurrentModule(fqn)) classes.add(fqn);
    }
    return classes;
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

  private void writeSelectionRecord(
      Selection sel,
      Baseline base,
      CoverageMap map,
      long startMillis,
      String buildDir,
      Logger log) {
    Path p = PluginPaths.selectionRecord(buildDir);
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
      new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(p.toFile(), record);
    } catch (IOException e) {
      log.debug("test-impact: failed to write selection record: " + e.getMessage());
    }
  }

  /**
   * Scans compiled output directories to discover the project's own package prefixes. Returns a
   * comma-separated, dot-notation list or {@code null} if no classes are found.
   */
  private String detectProjectPackages() {
    TreeSet<String> packages = new TreeSet<>();
    JavaPluginExtension java = project.getExtensions().findByType(JavaPluginExtension.class);
    if (java == null) return null;
    for (SourceSet ss : java.getSourceSets()) {
      for (File dir : ss.getOutput().getClassesDirs()) {
        scanPackages(dir, packages);
      }
    }
    if (packages.isEmpty()) return null;

    List<String> minimal = new ArrayList<>();
    for (String pkg : packages) {
      if (minimal.isEmpty() || !pkg.startsWith(minimal.get(minimal.size() - 1) + "/")) {
        minimal.add(pkg);
      }
    }

    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < minimal.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append(minimal.get(i).replace('/', '.'));
    }
    return sb.toString();
  }

  private static void scanPackages(File root, Set<String> packages) {
    if (!root.isDirectory()) return;
    try (Stream<Path> walk = Files.walk(root.toPath())) {
      walk.filter(p -> p.toString().endsWith(".class"))
          .forEach(
              p -> {
                Path parent = root.toPath().relativize(p).getParent();
                if (parent != null) {
                  String pkg = parent.toString().replace(File.separatorChar, '/');
                  if (!pkg.isEmpty()) packages.add(pkg);
                }
              });
    } catch (IOException ignored) {
    }
  }

  private static String quote(String s) {
    return s.indexOf(' ') < 0 ? s : "\"" + s + "\"";
  }
}
