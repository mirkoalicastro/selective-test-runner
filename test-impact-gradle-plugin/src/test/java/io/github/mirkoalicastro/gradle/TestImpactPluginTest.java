package io.github.mirkoalicastro.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

class TestImpactPluginTest {

  @Test
  void pluginAppliesSuccessfully() {
    Project project = ProjectBuilder.builder().build();
    project.getPluginManager().apply("java");
    project.getPluginManager().apply("io.github.mirkoalicastro.test-impact");
    assertNotNull(project.getExtensions().findByName("testImpact"));
  }

  @Test
  void extensionDefaultsAreSet() {
    Project project = ProjectBuilder.builder().build();
    project.getPluginManager().apply("java");
    project.getPluginManager().apply("io.github.mirkoalicastro.test-impact");
    TestImpactExtension ext = project.getExtensions().getByType(TestImpactExtension.class);
    assertEquals("lastCommit", ext.getBaseline().get());
    assertEquals(50, ext.getFullRunInterval().get());
    assertEquals("", ext.getIncludes().get());
    assertEquals("", ext.getExcludes().get());
    assertEquals(false, ext.getFailOnEmptySelection().get());
    assertEquals(false, ext.getSkipOnCacheMiss().get());
  }

  @Test
  void reportAndInvalidateTasksRegistered() {
    Project project = ProjectBuilder.builder().build();
    project.getPluginManager().apply("java");
    project.getPluginManager().apply("io.github.mirkoalicastro.test-impact");
    assertNotNull(project.getTasks().findByName("testImpactReport"));
    assertNotNull(project.getTasks().findByName("testImpactInvalidate"));
  }

  @Test
  void pluginVersionIsResolved() {
    String version = TestImpactPlugin.resolvePluginVersion();
    assertNotNull(version);
    // Should not fall back to default if version.properties is filtered
    // In test context the unfiltered file contains ${project.version}, so fallback is expected
  }
}
