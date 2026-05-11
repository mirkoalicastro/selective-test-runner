package io.github.mirkoalicastro.gradle;

import io.github.mirkoalicastro.common.PrefixIndex;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;

/**
 * Gradle equivalent of the Maven plugin's {@code ReactorScope}. Provides multi-project context for
 * change filtering, module ownership resolution, and output directory discovery.
 *
 * <p>Construct once per task execution.
 */
public final class GradleProjectScope {

  private final Project project;
  private final File repoRoot;
  private final TreeMap<String, Project> projectsByBasedir;
  private final Set<Project> reachable;

  /**
   * Creates a new scope for the given project.
   *
   * @param project the current Gradle project
   * @param repoRoot the Git repository root directory, or {@code null} for single-project fallback
   */
  public GradleProjectScope(Project project, File repoRoot) {
    this.project = project;
    this.repoRoot = canonicalize(repoRoot);
    this.projectsByBasedir = buildBasedirIndex(project, this.repoRoot);
    this.reachable = computeReachable(project);
  }

  /** Returns the root project's build directory path (shared coverage map location). */
  public String reactorRootBuildDir() {
    return project.getRootProject().getLayout().getBuildDirectory().getAsFile().get().toString();
  }

  /** Returns the Git repository root directory. */
  public File repoRoot() {
    return repoRoot;
  }

  /** Returns the current project. */
  public Project currentProject() {
    return project;
  }

  /**
   * Returns the project whose base directory is the longest prefix of the given repo-root-relative
   * path, or {@code null} if none match.
   */
  public Project ownerOf(String repoRelativeSourcePath) {
    return PrefixIndex.longestPrefixMatch(projectsByBasedir, repoRelativeSourcePath);
  }

  /**
   * Restricts a set of changed source paths to those owned by modules reachable from the current
   * project. Sources outside any module are kept to avoid silently missing changes.
   */
  public Set<String> filterToReachable(Set<String> changedSources) {
    if (changedSources == null) return null;
    if (projectsByBasedir.isEmpty()) return changedSources;
    Set<String> out = new HashSet<>();
    for (String s : changedSources) {
      Project owner = ownerOf(s);
      if (owner == null || reachable.contains(owner)) {
        out.add(s);
      }
    }
    return out;
  }

  /**
   * Returns all compile and test output directories across the entire build, for cross-module
   * inner-class expansion.
   */
  public List<Path> allOutputDirs() {
    List<Path> dirs = new ArrayList<>();
    for (Project p : project.getRootProject().getAllprojects()) {
      JavaPluginExtension java = p.getExtensions().findByType(JavaPluginExtension.class);
      if (java == null) continue;
      for (SourceSet ss : java.getSourceSets()) {
        for (File dir : ss.getOutput().getClassesDirs()) {
          dirs.add(dir.toPath());
        }
      }
    }
    return dirs;
  }

  /**
   * Returns {@code true} if the given test class FQN has a compiled {@code .class} file under the
   * current project's test output directories.
   */
  public boolean isTestInCurrentModule(String testClassFqn) {
    if (testClassFqn == null || testClassFqn.isEmpty()) return false;
    JavaPluginExtension java = project.getExtensions().findByType(JavaPluginExtension.class);
    if (java == null) return false;
    String relativePath = testClassFqn.replace('.', '/') + ".class";
    for (File dir :
        java.getSourceSets()
            .getByName(SourceSet.TEST_SOURCE_SET_NAME)
            .getOutput()
            .getClassesDirs()) {
      if (Files.exists(Paths.get(dir.getAbsolutePath(), relativePath))) return true;
    }
    return false;
  }

  private static Set<Project> computeReachable(Project current) {
    Set<Project> visited = new HashSet<>();
    Queue<Project> queue = new LinkedList<>();
    queue.add(current);
    while (!queue.isEmpty()) {
      Project p = queue.poll();
      if (!visited.add(p)) continue;
      p.getConfigurations().stream()
          .flatMap(cfg -> cfg.getDependencies().withType(ProjectDependency.class).stream())
          .map(ProjectDependency::getDependencyProject)
          .forEach(queue::add);
    }
    return visited;
  }

  private static File canonicalize(File f) {
    if (f == null) return null;
    try {
      return f.getCanonicalFile();
    } catch (IOException e) {
      return f.getAbsoluteFile();
    }
  }

  private static TreeMap<String, Project> buildBasedirIndex(Project project, File repoRoot) {
    TreeMap<String, Project> map = new TreeMap<>(PrefixIndex.descendingByLength());
    if (repoRoot == null) return map;
    Path root = repoRoot.toPath();
    for (Project p : project.getRootProject().getAllprojects()) {
      File basedir = canonicalize(p.getProjectDir());
      try {
        Path rel = root.relativize(basedir.toPath());
        String key = rel.toString().replace(File.separatorChar, '/');
        if (".".equals(key)) key = "";
        map.put(key, p);
      } catch (IllegalArgumentException ignored) {
      }
    }
    return map;
  }
}
