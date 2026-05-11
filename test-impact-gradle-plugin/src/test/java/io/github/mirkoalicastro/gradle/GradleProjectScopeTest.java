package io.github.mirkoalicastro.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link GradleProjectScope}. Uses a single shared {@link ProjectBuilder} instance to
 * avoid Gradle's internal class-decoration failures when creating multiple projects in the same
 * JVM.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GradleProjectScopeTest {

  private Project rootProject;
  private File canonicalDir;

  @BeforeAll
  void setUp(@TempDir File tempDir) throws IOException {
    canonicalDir = tempDir.getCanonicalFile();
    rootProject = ProjectBuilder.builder().withProjectDir(canonicalDir).build();
  }

  @Test
  void ownerOfReturnsCatchAllForSingleProject() {
    GradleProjectScope scope = new GradleProjectScope(rootProject, canonicalDir);
    assertNotNull(scope.ownerOf("unknown/src/Foo.java"));
    assertEquals(rootProject, scope.ownerOf("unknown/src/Foo.java"));
  }

  @Test
  void filterToReachableKeepsChangesForSingleProject() {
    GradleProjectScope scope = new GradleProjectScope(rootProject, canonicalDir);
    Set<String> changed = new HashSet<>();
    changed.add("src/main/java/A.java");
    changed.add("README.md");
    Set<String> filtered = scope.filterToReachable(changed);
    assertEquals(changed, filtered);
  }

  @Test
  void filterToReachableHandlesNullInput() {
    GradleProjectScope scope = new GradleProjectScope(rootProject, canonicalDir);
    assertNull(scope.filterToReachable(null));
  }

  @Test
  void allOutputDirsEmptyWithoutJavaPlugin() {
    GradleProjectScope scope = new GradleProjectScope(rootProject, canonicalDir);
    List<Path> dirs = scope.allOutputDirs();
    assertTrue(dirs.isEmpty());
  }

  @Test
  void isTestInCurrentModuleReturnsFalseWithoutJavaPlugin() {
    GradleProjectScope scope = new GradleProjectScope(rootProject, canonicalDir);
    assertFalse(scope.isTestInCurrentModule("com.acme.MyTest"));
  }

  @Test
  void reactorRootBuildDirReturnsRootBuildDir() {
    GradleProjectScope scope = new GradleProjectScope(rootProject, canonicalDir);
    String buildDir = scope.reactorRootBuildDir();
    assertNotNull(buildDir);
    assertTrue(buildDir.contains("build"));
  }

  @Test
  void scopeWithNullRepoRootHandlesGracefully() {
    GradleProjectScope scope = new GradleProjectScope(rootProject, null);
    assertNull(scope.repoRoot());
    assertNull(scope.ownerOf("some/path"));
    Set<String> changed = new HashSet<>();
    changed.add("foo.java");
    assertEquals(changed, scope.filterToReachable(changed));
  }
}
