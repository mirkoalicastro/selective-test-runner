package io.github.testimpact.common;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Centralised path resolution for plugin artifacts. */
public final class PluginPaths {

    private PluginPaths() {}

    /** Default coverage map location: {@code ${project.build.directory}/.test-impact/coverage.db}. */
    public static Path coverageMap(String projectBuildDir) {
        return Paths.get(projectBuildDir, ".test-impact", "coverage.db");
    }

    /** Per-build dump file written by the agent and consumed by the report goal. */
    public static Path dump(String projectBuildDir) {
        return Paths.get(projectBuildDir, ".test-impact", "dump.bin");
    }

    /** Selected-tests file written by select goal, read by report goal for stats. */
    public static Path selectionRecord(String projectBuildDir) {
        return Paths.get(projectBuildDir, ".test-impact", "selection.json");
    }

    /** Public JSON report file. */
    public static Path report(String projectBuildDir) {
        return Paths.get(projectBuildDir, "test-impact-report.json");
    }

    /** Persistent counter of builds since last full run. */
    public static Path buildCounter(String projectBuildDir) {
        return Paths.get(projectBuildDir, ".test-impact", "builds-since-full.txt");
    }
}
