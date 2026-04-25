package io.github.testimpact.mojo;

import io.github.testimpact.common.PluginPaths;
import io.github.testimpact.store.CoverageMapStore;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Removes the persisted coverage map and per-build state, forcing a cold rebuild. */
@Mojo(name = "invalidate", threadSafe = true)
public class InvalidateMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "testimpact.coverageMapPath",
            defaultValue = "${project.build.directory}/.test-impact/coverage.db")
    private File coverageMapPath;

    @Override
    public void execute() throws MojoExecutionException {
        String buildDir = project.getBuild().getDirectory();
        try {
            CoverageMapStore.delete(coverageMapPath.toPath());
            deleteIfExists(PluginPaths.dump(buildDir));
            deleteIfExists(PluginPaths.selectionRecord(buildDir));
            deleteIfExists(PluginPaths.buildCounter(buildDir));
            getLog().info("test-impact: coverage map and per-build state cleared");
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to invalidate coverage map", e);
        }
    }

    private static void deleteIfExists(Path p) throws IOException {
        if (Files.exists(p)) Files.delete(p);
    }
}
