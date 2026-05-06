package io.github.testimpact.common;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Centralised path resolution for plugin artifacts.
 *
 * Reactor-shared artifacts (coverage map, build counter) live under the reactor root's
 * build directory. Per-module artifacts (dump, selection record, JSON report) live
 * under each module's own build directory.
 */
public final class PluginPaths {

    private PluginPaths() {}

    /** Shared coverage map: {@code <reactorRootBuildDir>/.test-impact/coverage.json}. */
    public static Path coverageMap(String reactorRootBuildDir) {
        return Paths.get(reactorRootBuildDir, ".test-impact", "coverage.json");
    }

    /** Per-module agent dump: {@code <projectBuildDir>/.test-impact/dump.bin}. */
    public static Path dump(String projectBuildDir) {
        return Paths.get(projectBuildDir, ".test-impact", "dump.bin");
    }

    /**
     * Per-module build counter ({@code <projectBuildDir>/.test-impact/builds-since-full.txt}).
     * Each module independently tracks its own builds-since-last-full-run so the
     * fullRunInterval safety net fires per module rather than globally.
     */
    public static Path buildCounter(String projectBuildDir) {
        return Paths.get(projectBuildDir, ".test-impact", "builds-since-full.txt");
    }

    /** Per-module selection record (consumed by report goal for stats). */
    public static Path selectionRecord(String projectBuildDir) {
        return Paths.get(projectBuildDir, ".test-impact", "selection.json");
    }

    /** Per-module JSON report file. */
    public static Path report(String projectBuildDir) {
        return Paths.get(projectBuildDir, "test-impact-report.json");
    }
}
