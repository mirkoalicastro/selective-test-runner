package io.github.mirkoalicastro.gradle;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

/**
 * DSL extension for configuring the test-impact plugin.
 *
 * <pre>
 * testImpact {
 *     baseline = 'lastCommit'
 *     fullRunInterval = 50
 *     includes = 'com.mycompany.myapp'
 *     excludes = 'com.mycompany.myapp.generated'
 *     failOnEmptySelection = false
 * }
 * </pre>
 */
public abstract class TestImpactExtension {

  /**
   * Git baseline for change detection: {@code lastCommit}, {@code lastTag}, or {@code lastFullRun}.
   * Default: {@code lastCommit}.
   */
  public abstract Property<String> getBaseline();

  /**
   * Force a full run every N incremental builds. Set to {@code 0} to disable. Default: {@code 50}.
   */
  public abstract Property<Integer> getFullRunInterval();

  /**
   * Comma-separated package prefixes to instrument (dot notation). Auto-detected from compiled
   * output if empty.
   */
  public abstract Property<String> getIncludes();

  /** Comma-separated package prefixes to exclude from instrumentation. */
  public abstract Property<String> getExcludes();

  /**
   * If {@code true}, fail the build when no tests match the changed classes instead of falling back
   * to a full run. Default: {@code false}.
   */
  public abstract Property<Boolean> getFailOnEmptySelection();

  /**
   * When {@code true}, suppress the full-run fallback on a cache miss and emit a warning instead.
   * Default: {@code false}.
   */
  public abstract Property<Boolean> getSkipOnCacheMiss();

  /**
   * Override the coverage map location. Defaults to {@code
   * <rootProject.buildDir>/.test-impact/coverage.json}.
   */
  public abstract RegularFileProperty getCoverageMapPath();
}
