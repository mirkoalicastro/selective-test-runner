package io.github.testimpact.common;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

/**
 * View over the current Maven session that answers reactor-monorepo questions: - Where is the
 * reactor-shared coverage map? - Which reactor module owns a given (repo-root-relative) source
 * path? - Which modules are reachable from the current one via the reactor dependency graph? -
 * Where are all the compiled-class output dirs (for cross-module inner-class expansion)? - Does a
 * given test class live in the current module's test classpath?
 *
 * <p>Construct once per Mojo execution. All reads are best-effort: if Maven hands us a detached
 * project (no session, no graph), we fall back to single-module behaviour.
 */
public final class ReactorScope {

  private final MavenSession session;
  private final MavenProject currentProject;
  private final File repoRoot;

  /** Repo-root-relative basedir → owning project, ordered by descending key length. */
  private final TreeMap<String, MavenProject> projectsByBasedir;

  /** Self + transitive upstream projects in the reactor dependency graph. */
  private final Set<MavenProject> reachable;

  public ReactorScope(MavenSession session, MavenProject currentProject, File repoRoot) {
    this.session = session;
    this.currentProject = currentProject;
    this.repoRoot = repoRoot == null ? null : repoRoot.getAbsoluteFile();
    this.projectsByBasedir = buildBasedirIndex(session, this.repoRoot);
    this.reachable = computeReachable(session, currentProject);
  }

  /** {@code <reactor-root>/target} (or whatever the top-level project's build dir is). */
  public String reactorRootBuildDir() {
    MavenProject top = session == null ? currentProject : session.getTopLevelProject();
    if (top == null) top = currentProject;
    return top.getBuild().getDirectory();
  }

  public File repoRoot() {
    return repoRoot;
  }

  public MavenProject currentProject() {
    return currentProject;
  }

  /**
   * Project whose basedir is the longest prefix of the given repo-root-relative path; null if none
   * match.
   */
  public MavenProject ownerOf(String repoRelativeSourcePath) {
    return longestPrefixMatch(projectsByBasedir, repoRelativeSourcePath);
  }

  /**
   * Pure helper: walks {@code index} (descending-length-ordered) and returns the value whose key is
   * the longest prefix of {@code path}. An empty-string key is treated as a wildcard fallback.
   * Public so the prefix logic is unit-testable without Maven.
   */
  public static <T> T longestPrefixMatch(TreeMap<String, T> index, String path) {
    if (path == null || index == null || index.isEmpty()) return null;
    for (Map.Entry<String, T> e : index.entrySet()) {
      String prefix = e.getKey();
      if (prefix.isEmpty()) return e.getValue();
      if (path.startsWith(prefix + "/")) return e.getValue();
    }
    return null;
  }

  /** Comparator for the longest-prefix-first index — exposed for tests. */
  public static java.util.Comparator<String> descendingByLength() {
    return (a, b) -> {
      int byLength = Integer.compare(b.length(), a.length());
      return byLength != 0 ? byLength : a.compareTo(b);
    };
  }

  /**
   * Restrict the change set to sources owned by modules reachable from the current one. Sources
   * outside any reactor module (e.g. top-level config files) are kept — they may still be relevant
   * and we'd rather over-select than silently miss.
   */
  public Set<String> filterToReachable(Set<String> changedSources) {
    if (changedSources == null) return null;
    if (projectsByBasedir.isEmpty()) return changedSources;
    Set<String> out = new HashSet<>();
    for (String s : changedSources) {
      MavenProject owner = ownerOf(s);
      if (owner == null || reachable.contains(owner)) {
        out.add(s);
      }
    }
    return out;
  }

  /** All compile + test output dirs across the reactor — feeds the multi-module ClassResolver. */
  public List<Path> allOutputDirs() {
    List<Path> dirs = new ArrayList<>();
    Iterable<MavenProject> projects =
        session == null
            ? java.util.Collections.singletonList(currentProject)
            : session.getProjects();
    for (MavenProject p : projects) {
      String out = p.getBuild().getOutputDirectory();
      if (out != null) dirs.add(Paths.get(out));
      String tout = p.getBuild().getTestOutputDirectory();
      if (tout != null) dirs.add(Paths.get(tout));
    }
    return dirs;
  }

  /**
   * True iff the given test class FQN has a compiled .class file under the current module's test
   * output dir.
   */
  public boolean isTestInCurrentModule(String testClassFqn) {
    if (testClassFqn == null || testClassFqn.isEmpty()) return false;
    String testOut = currentProject.getBuild().getTestOutputDirectory();
    if (testOut == null) return false;
    Path candidate = Paths.get(testOut, testClassFqn.replace('.', '/') + ".class");
    return Files.exists(candidate);
  }

  private static Set<MavenProject> computeReachable(MavenSession session, MavenProject current) {
    Set<MavenProject> out = new HashSet<>();
    out.add(current);
    if (session == null) return out;
    try {
      if (session.getProjectDependencyGraph() != null) {
        out.addAll(session.getProjectDependencyGraph().getUpstreamProjects(current, true));
      }
    } catch (Exception ignored) {
      // No graph (single project mode, or detached session) — current only.
    }
    return out;
  }

  private static TreeMap<String, MavenProject> buildBasedirIndex(
      MavenSession session, File repoRoot) {
    TreeMap<String, MavenProject> map = new TreeMap<>(descendingByLength());
    if (session == null || repoRoot == null) return map;
    Path root = repoRoot.toPath();
    for (MavenProject p : session.getProjects()) {
      File basedir = p.getBasedir();
      if (basedir == null) continue;
      try {
        Path rel = root.relativize(basedir.getAbsoluteFile().toPath());
        String key = rel.toString().replace(File.separatorChar, '/');
        if (".".equals(key)) key = "";
        map.put(key, p);
      } catch (IllegalArgumentException ignored) {
        // basedir not under repo root; skip
      }
    }
    return map;
  }
}
