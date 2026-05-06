package io.github.testimpact.mojo;

import io.github.testimpact.common.PluginPaths;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/**
 * Activates coverage collection by attaching the agent JAR to Surefire's argLine. Bound to {@code
 * process-test-classes} per spec §5.2.
 *
 * <p>Effect on subsequent {@code surefire:test}: {@code -javaagent:<plugin.jar>
 * -Dtestimpact.dump=<dump.bin>} is prepended to {@code argLine}, instructing the forked test JVM to
 * instrument production classes and emit a per-test class-touch dump.
 */
@Mojo(
    name = "collect",
    defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES,
    requiresDependencyResolution = ResolutionScope.TEST,
    threadSafe = true)
public class CollectMojo extends AbstractMojo {

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Parameter(defaultValue = "${plugin.artifactMap}", readonly = true)
  private java.util.Map<String, Artifact> pluginArtifacts;

  /** Optional include packages (comma-separated, e.g. {@code com.acme,io.acme}). */
  @Parameter(property = "testimpact.includes")
  private String includes;

  /** Optional exclude packages (comma-separated). */
  @Parameter(property = "testimpact.excludes")
  private String excludes;

  @Override
  public void execute() throws MojoExecutionException {
    File agentJar = locateAgentJar();
    if (agentJar == null) {
      getLog()
          .warn(
              "test-impact: could not locate plugin agent JAR — collection skipped, full suite will run");
      return;
    }

    Path dump = PluginPaths.dump(project.getBuild().getDirectory());
    try {
      Files.createDirectories(dump.getParent());
    } catch (Exception e) {
      throw new MojoExecutionException("Failed to create dump directory", e);
    }

    if (includes == null || includes.isEmpty()) {
      includes = detectProjectPackages();
      if (includes != null) {
        getLog().info("test-impact: auto-detected includes: " + includes);
      }
    }

    StringBuilder argLine = new StringBuilder();
    argLine.append("-javaagent:").append(quote(agentJar.getAbsolutePath()));
    argLine.append(" -Dtestimpact.dump=").append(quote(dump.toString()));
    if (includes != null && !includes.isEmpty()) {
      argLine.append(" -Dtestimpact.includes=").append(quote(includes));
    }
    if (excludes != null && !excludes.isEmpty()) {
      argLine.append(" -Dtestimpact.excludes=").append(quote(excludes));
    }
    String existing = project.getProperties().getProperty("argLine");
    String combined =
        existing == null || existing.isEmpty() ? argLine.toString() : argLine + " " + existing;
    project.getProperties().setProperty("argLine", combined);

    getLog().info("test-impact:collect attached agent: " + agentJar.getName());
    getLog().debug("test-impact argLine: " + combined);
  }

  private File locateAgentJar() {
    // 1. Plugin's own dependency map (rare path — only set if user declared the agent classifier as
    // a dependency).
    if (pluginArtifacts != null) {
      for (Artifact a : pluginArtifacts.values()) {
        if (a == null || a.getFile() == null) continue;
        if ("agent".equals(a.getClassifier())) return a.getFile();
      }
    }
    // 2. Sibling -agent.jar next to the plugin JAR in ~/.m2 — the normal install layout.
    try {
      java.net.URL src = getClass().getProtectionDomain().getCodeSource().getLocation();
      if (src != null) {
        File pluginJar = new File(src.toURI());
        if (pluginJar.isFile()) {
          String name = pluginJar.getName();
          if (name.endsWith(".jar")) {
            String base = name.substring(0, name.length() - 4);
            File sibling = new File(pluginJar.getParentFile(), base + "-agent.jar");
            if (sibling.isFile()) return sibling;
          }
          // 3. Fallback to the plugin JAR itself. Only works if it bundles ASM (it doesn't by
          // default,
          //    so this path will silently fail with NoClassDefFoundError inside the agent — warn
          // loudly.)
          getLog()
              .warn(
                  "test-impact: shaded agent JAR not found next to "
                      + pluginJar.getName()
                      + " — falling back to the plain plugin JAR. The agent will fail to load (ASM missing). "
                      + "Reinstall the plugin so the '-agent' classifier JAR is present in your local repo.");
          return pluginJar;
        }
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  /**
   * Scans compiled output directories to discover the project's own package prefixes. Returns a
   * comma-separated, dot-notation list (e.g. {@code "com.example.demo"}) or {@code null} if no
   * classes are found.
   */
  private String detectProjectPackages() {
    TreeSet<String> packages = new TreeSet<>();
    scanPackages(new File(project.getBuild().getOutputDirectory()), packages);
    scanPackages(new File(project.getBuild().getTestOutputDirectory()), packages);
    if (packages.isEmpty()) return null;

    // Reduce to prefix-minimal set: since the TreeSet is sorted, each entry
    // is kept only if it isn't already covered by the previous prefix.
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
