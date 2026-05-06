package io.github.testimpact.mojo;

import io.github.testimpact.change.ChangeDetector;
import io.github.testimpact.common.PluginPaths;
import io.github.testimpact.common.ReactorScope;
import io.github.testimpact.store.CoverageMapStore;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Removes the shared coverage map (at the reactor root) and every reactor module's per-build state
 * (dump, selection record, build counter), forcing a cold rebuild.
 */
@Mojo(name = "invalidate", threadSafe = true)
public class InvalidateMojo extends AbstractMojo {

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Parameter(defaultValue = "${session}", readonly = true, required = true)
  private MavenSession session;

  @Parameter(property = "testimpact.coverageMapPath")
  private File coverageMapPath;

  @Override
  public void execute() throws MojoExecutionException {
    File topBasedir =
        session.getTopLevelProject() == null
            ? project.getBasedir()
            : session.getTopLevelProject().getBasedir();
    ReactorScope scope =
        new ReactorScope(session, project, new ChangeDetector(topBasedir).repoRoot());
    Path mapPath =
        coverageMapPath != null
            ? coverageMapPath.toPath()
            : PluginPaths.coverageMap(scope.reactorRootBuildDir());

    try {
      CoverageMapStore.delete(mapPath);
      for (MavenProject p : session.getProjects()) {
        String dir = p.getBuild().getDirectory();
        deleteIfExists(PluginPaths.dump(dir));
        deleteIfExists(PluginPaths.selectionRecord(dir));
        deleteIfExists(PluginPaths.buildCounter(dir));
      }
      getLog()
          .info(
              "test-impact: shared coverage map and per-module state cleared ("
                  + session.getProjects().size()
                  + " modules)");
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to invalidate coverage map", e);
    }
  }

  private static void deleteIfExists(Path p) throws IOException {
    if (Files.exists(p)) Files.delete(p);
  }
}
