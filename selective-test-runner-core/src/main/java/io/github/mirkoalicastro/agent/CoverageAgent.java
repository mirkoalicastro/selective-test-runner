package io.github.mirkoalicastro.agent;

import java.lang.instrument.Instrumentation;

/** Java agent entry point. Installs the bytecode transformer for coverage collection. */
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
    // canRetransform=true so our instrumentation survives retransformClasses() calls
    // from other agents (e.g. Mockito 5's inline mock maker).
    inst.addTransformer(
        new CoverageTransformer(
            System.getProperty("testimpact.includes", ""),
            System.getProperty("testimpact.excludes", "")),
        true);
  }
}
