package io.github.mirkoalicastro.mojo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mirkoalicastro.common.PluginPaths;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Properties;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SelectMojoTest {
  @TempDir Path tempDir;
  @Mock private MavenProject project;
  @Mock private MavenSession session;
  @Mock private Log log;
  private Path repoDir;
  private Path srcDir;
  private Path testClassesDir;
  private String buildDir;
  private Properties projectProperties;
  private Properties userProperties;
  private SelectMojo mojo;

  @BeforeEach
  void setUp() throws Exception {
    createDirectories();
    initProject();
    initGitRepo(repoDir);
    initSelectMojo();
  }

  private void createDirectories() throws IOException {
    repoDir = Files.createDirectory(tempDir.resolve("repo"));
    srcDir = Files.createDirectories(repoDir.resolve("src/main/java"));
    Path targetDir = Files.createDirectory(repoDir.resolve("target"));
    buildDir = targetDir.toString();
    testClassesDir = Files.createDirectory(targetDir.resolve("test-classes"));
  }

  private void initProject() {
    Build build = new Build();
    build.setDirectory(buildDir);
    build.setTestOutputDirectory(testClassesDir.toString());
    when(project.getBasedir()).thenReturn(repoDir.toFile());
    when(project.getBuild()).thenReturn(build);
    projectProperties = new Properties();
    userProperties = new Properties();
    lenient().when(project.getProperties()).thenReturn(projectProperties);
    lenient().when(session.getUserProperties()).thenReturn(userProperties);
    lenient().when(project.getCollectedProjects()).thenReturn(Collections.emptyList());
    when(session.getTopLevelProject()).thenReturn(project);
  }

  private void initGitRepo(Path dir) throws Exception {
    runCommand(dir, "git", "init");
    runCommand(dir, "git", "config", "user.email", "test@example.com");
    runCommand(dir, "git", "config", "user.name", "Test User");
    Files.writeString(dir.resolve("README.md"), "init\n", StandardCharsets.UTF_8);
    runCommand(dir, "git", "add", ".");
    runCommand(dir, "git", "commit", "-m", "initial commit");
  }

  private void initSelectMojo() {
    mojo = new SelectMojo();
    mojo.setLog(log);
    setField(mojo, "project", project);
    setField(mojo, "session", session);
    setField(mojo, "baseline", "lastCommit");
    setField(mojo, "fullRunInterval", 50);
    setField(mojo, "failOnEmptySelection", false);
    setField(mojo, "skipOnCacheMiss", false);
    setField(mojo, "testClassesToRunAlways", "");
  }

  @Test
  void execute_throwsMojoExecutionExceptionOnInvalidBaseline() {
    setField(mojo, "baseline", "notAValidBaseline");
    MojoExecutionException ex = assertThrows(MojoExecutionException.class, mojo::execute);
    assertNotNull(ex.getMessage());
  }

  @Test
  void execute_logsSkipOnCacheMissWarningWhenEnabledInFullRun() throws Exception {
    setField(mojo, "skipOnCacheMiss", true);
    mojo.execute();
    verify(log, atLeastOnce()).warn(org.mockito.ArgumentMatchers.contains("skipOnCacheMiss=true"));
  }

  @Test
  void execute_setsProperties() throws Exception {
    writeCoverageMap(buildCoverageMapJson());
    setField(mojo, "testClassesToRunAlways", "com.demo.RunAlwaysTest");
    addJavaFile();
    addTestClasses();
    mojo.execute();
    validateProperties("com.demo.RunAlwaysTest,com.demo.MainTest");
    verify(log, atLeastOnce()).info("test-impact: selected 2 tests across 2 classes (this module)");
    validateSelectionRecord();
  }

  @Test
  void execute_setsNoMatchPatternWhenSelectedModeHasNoImpactedTestsInModule() throws Exception {
    writeCoverageMap(buildCoverageMapJson());
    setField(mojo, "testClassesToRunAlways", "com.demo.RunAlwaysTest");
    addJavaFile();
    mojo.execute();
    validateProperties("io.github.mirkoalicastro.NoTestMatches#nothing");
    verify(log, atLeastOnce()).info("test-impact: no impacted tests in this module");
    validateSelectionRecord();
  }

  @Test
  void execute_fallsBackToFullRunAndLogsWarningWhenCoverageMapCorrupt() throws Exception {
    writeCoverageMap("{ invalid json");
    addJavaFile();
    addTestClasses();
    mojo.execute();
    validateProperties(null);
    verify(log, atLeastOnce()).warn(org.mockito.ArgumentMatchers.contains("failed to load coverage map"));
    JsonNode record = readSelectionRecord();
    assertEquals("FULL_RUN", record.get("mode").asText());
    assertEquals("coverage map absent", record.get("reason").asText());
    assertEquals("LAST_COMMIT", record.get("baseline").asText());
    assertEquals("[ \"com/demo/Main\" ]", record.get("changedClasses").toPrettyString());
    assertEquals("[ ]", record.get("selectedTests").toPrettyString());
    assertEquals(0, record.get("mapEntries").asInt());
    assertEquals("", record.get("mapBuildHash").asText());
    assertTrue(record.has("startedMillis"));
    assertTrue(record.get("startedMillis").asLong() > 0L);
  }

  private String buildCoverageMapJson() throws Exception {
    String headHash = gitRevParseHead(repoDir);
    return "{\"buildHash\":\"" + headHash + "\",\"entries\": {\"com.demo.MainTest#test\": [\"com/demo/Main\"]}}";
  }

  private void writeCoverageMap(String coverageJson) throws IOException {
    Path mapFile = PluginPaths.coverageMap(buildDir);
    Files.createDirectories(mapFile.getParent());
    Files.writeString(mapFile, coverageJson, StandardCharsets.UTF_8);
    setField(mojo, "coverageMapPath", mapFile.toFile());
  }

  private void validateProperties(String expected) {
    assertEquals(expected, projectProperties.getProperty("test"));
    assertEquals(expected, projectProperties.getProperty("surefire.test"));
    assertEquals(expected, userProperties.getProperty("test"));
    assertEquals(expected, userProperties.getProperty("surefire.test"));
  }

  private void validateSelectionRecord() throws Exception {
    JsonNode record = readSelectionRecord();
    assertEquals("SELECTED", record.get("mode").asText());
    assertEquals("null", record.get("reason").asText());
    assertEquals("LAST_COMMIT", record.get("baseline").asText());
    assertEquals("[ \"com/demo/Main\" ]", record.get("changedClasses").toPrettyString());
    assertEquals("[ \"com.demo.MainTest#test\", \"com.demo.RunAlwaysTest\" ]", record.get("selectedTests").toPrettyString());
    assertEquals(1, record.get("mapEntries").asInt());
    assertEquals(gitRevParseHead(repoDir), record.get("mapBuildHash").asText());
    assertTrue(record.has("startedMillis"));
    assertTrue(record.get("startedMillis").asLong() > 0L);
  }

  private JsonNode readSelectionRecord() throws IOException {
    Path recordFile = PluginPaths.selectionRecord(buildDir);
    assertTrue(Files.exists(recordFile), "Expected selection record at " + recordFile);
    return new ObjectMapper().readTree(recordFile.toFile());
  }

  private void setField(Object target, String fieldName, Object value) {
    try {
      Field field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("Failed to set field '" + fieldName + "'", e);
    }
  }

  private void addJavaFile() throws Exception {
    Path packageDir = Files.createDirectories(srcDir.resolve("com/demo"));
    Files.writeString(packageDir.resolve("Main.java"), "init\n", StandardCharsets.UTF_8);
  }

  private void addTestClasses() throws Exception {
    Path packageDir = Files.createDirectories(testClassesDir.resolve("com/demo"));
    Files.writeString(packageDir.resolve("MainTest.class"), "init\n", StandardCharsets.UTF_8);
    Files.writeString(packageDir.resolve("RunAlwaysTest.class"), "init\n", StandardCharsets.UTF_8);
  }

  private String gitRevParseHead(Path dir) throws Exception {
    return runCommand(dir, "git", "rev-parse", "HEAD").trim();
  }

  private String runCommand(Path dir, String... command) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.directory(dir.toFile());
    pb.redirectErrorStream(true);
    Process process = pb.start();
    String output;
    try (InputStream in = process.getInputStream()) {
      output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    int exitCode = process.waitFor();
    if (exitCode != 0) {
      throw new IllegalStateException(
          "Command failed (" + exitCode + "): " + String.join(" ", command) + "\n" + output);
    }
    return output;
  }
}
