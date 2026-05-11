package io.github.mirkoalicastro.gradle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that exercise the plugin tasks at a higher level using {@link ProjectBuilder}. Full
 * Gradle-runner-based functional tests require a Gradle distribution matching the host JDK and are
 * better run from a Gradle-based build.
 */
class TestImpactFunctionalTest {

  @TempDir File projectDir;

  @Test
  void invalidateDeletesStateFiles() throws IOException, GitAPIException {
    File canonical = projectDir.getCanonicalFile();
    Git.init().setDirectory(canonical).call().close();
    Project project = ProjectBuilder.builder().withProjectDir(canonical).build();
    project.getPluginManager().apply("io.github.mirkoalicastro.test-impact");

    // Create fake state files
    Path buildDir = canonical.toPath().resolve("build");
    Path stateDir = buildDir.resolve(".test-impact");
    Files.createDirectories(stateDir);

    Path dumpFile = stateDir.resolve("dump.bin");
    Path selectionFile = stateDir.resolve("selection.json");
    Path counterFile = stateDir.resolve("builds-since-full.txt");
    Files.write(dumpFile, new byte[0]);
    Files.write(selectionFile, "{}".getBytes());
    Files.write(counterFile, "3".getBytes());

    assertTrue(Files.exists(dumpFile));
    assertTrue(Files.exists(selectionFile));
    assertTrue(Files.exists(counterFile));

    // Execute the invalidate task
    TestImpactInvalidateTask task =
        (TestImpactInvalidateTask) project.getTasks().getByName("testImpactInvalidate");
    task.invalidate();

    assertFalse(Files.exists(dumpFile));
    assertFalse(Files.exists(selectionFile));
    assertFalse(Files.exists(counterFile));
  }
}
