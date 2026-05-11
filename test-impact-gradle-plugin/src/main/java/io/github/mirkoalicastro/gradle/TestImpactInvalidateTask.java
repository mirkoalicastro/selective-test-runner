package io.github.mirkoalicastro.gradle;

import io.github.mirkoalicastro.change.ChangeDetector;
import io.github.mirkoalicastro.common.PluginPaths;
import io.github.mirkoalicastro.store.CoverageMapStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskAction;

/**
 * Removes the shared coverage map and every project's per-build state (dump, selection record,
 * build counter), forcing a cold rebuild.
 */
public class TestImpactInvalidateTask extends DefaultTask {

  private TestImpactExtension ext;
  private Project targetProject;

  void configure(TestImpactExtension ext, Project project) {
    this.ext = ext;
    this.targetProject = project;
    setDescription("Clears the coverage map and all per-project state");
    setGroup("verification");
  }

  /** Executes the invalidate task. */
  @TaskAction
  public void invalidate() throws IOException {
    ChangeDetector cd = new ChangeDetector(targetProject.getRootProject().getProjectDir());
    GradleProjectScope scope = new GradleProjectScope(targetProject, cd.repoRoot());

    Path mapPath;
    if (ext.getCoverageMapPath().isPresent()) {
      mapPath = ext.getCoverageMapPath().getAsFile().get().toPath();
    } else {
      mapPath = PluginPaths.coverageMap(scope.reactorRootBuildDir());
    }

    CoverageMapStore.delete(mapPath);

    int count = 0;
    for (Project p : targetProject.getRootProject().getAllprojects()) {
      String dir = p.getLayout().getBuildDirectory().getAsFile().get().toString();
      deleteIfExists(PluginPaths.dump(dir));
      deleteIfExists(PluginPaths.selectionRecord(dir));
      deleteIfExists(PluginPaths.buildCounter(dir));
      count++;
    }
    getLogger()
        .lifecycle(
            "test-impact: shared coverage map and per-project state cleared ("
                + count
                + " projects)");
  }

  private static void deleteIfExists(Path p) throws IOException {
    if (Files.exists(p)) Files.delete(p);
  }
}
