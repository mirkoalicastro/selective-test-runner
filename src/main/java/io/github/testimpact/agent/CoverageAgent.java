package io.github.testimpact.agent;

import java.lang.instrument.Instrumentation;

/**
 * Java agent entry point. Installs a {@link CoverageTransformer} that:
 *   - injects {@link CoverageRecorder#touch(String)} at the entry of every method
 *     in production classes;
 *   - wraps test methods (JUnit 4/5, TestNG) with begin/end calls.
 *
 * System properties:
 *   testimpact.dump      = output file for collected entries (required for collection)
 *   testimpact.includes  = comma-separated package prefixes to include (default: all non-system)
 *   testimpact.excludes  = comma-separated package prefixes to exclude
 */
public final class CoverageAgent {

    private CoverageAgent() {}

    public static void premain(String args, Instrumentation inst) {
        install(inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        install(inst);
    }

    private static void install(Instrumentation inst) {
        CoverageRecorder.enable();
        inst.addTransformer(new CoverageTransformer(
                System.getProperty("testimpact.includes", ""),
                System.getProperty("testimpact.excludes", "")
        ), false);
    }
}
