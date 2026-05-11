package io.github.mirkoalicastro.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;

/**
 * Gradle plugin that performs bytecode-level test impact analysis to selectively run only the tests
 * affected by code changes.
 *
 * <p>Applies to a project by registering:
 *
 * <ul>
 *   <li>A {@code testImpact} extension for configuration
 *   <li>A {@code doFirst} action on every {@link Test} task (collect + select)
 *   <li>A {@code testImpactReport} task finalized by each {@link Test} task
 *   <li>A {@code testImpactInvalidate} task for manual cache clearing
 * </ul>
 */
public class TestImpactPlugin implements Plugin<Project> {

  @Override
  public void apply(Project project) {
    TestImpactExtension ext =
        project.getExtensions().create("testImpact", TestImpactExtension.class);

    ext.getBaseline().convention("lastCommit");
    ext.getFullRunInterval().convention(50);
    ext.getIncludes().convention("");
    ext.getExcludes().convention("");
    ext.getFailOnEmptySelection().convention(false);
    ext.getSkipOnCacheMiss().convention(false);

    Configuration agentConfig =
        project
            .getConfigurations()
            .detachedConfiguration(
                project
                    .getDependencies()
                    .create(
                        "io.github.mirkoalicastro:selective-test-runner-core:"
                            + resolvePluginVersion()
                            + ":agent"));
    agentConfig.setTransitive(false);

    project
        .getTasks()
        .withType(Test.class)
        .configureEach(
            testTask -> testTask.doFirst(new TestImpactAction(ext, agentConfig, project)));

    TaskProvider<TestImpactReportTask> reportTask =
        project
            .getTasks()
            .register(
                "testImpactReport",
                TestImpactReportTask.class,
                task -> task.configure(ext, project));

    project
        .getTasks()
        .withType(Test.class)
        .configureEach(testTask -> testTask.finalizedBy(reportTask));

    project
        .getTasks()
        .register(
            "testImpactInvalidate",
            TestImpactInvalidateTask.class,
            task -> task.configure(ext, project));
  }

  static String resolvePluginVersion() {
    Properties props = new Properties();
    try (InputStream in = TestImpactPlugin.class.getResourceAsStream("/version.properties")) {
      if (in != null) {
        props.load(in);
        return props.getProperty("version", "1.0.0");
      }
    } catch (IOException ignored) {
    }
    return "1.0.0";
  }
}
