package io.github.testimpact.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Set;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * Bytecode transformer that: - skips system / framework / agent-internal classes; - in test classes
 * (have any test annotations), wraps test methods with begin/end; - in non-test classes, prepends a
 * {@link CoverageRecorder#touch(String)} call to every method.
 *
 * <p>Two-pass on each class: first pass detects which methods are tests, second pass transforms.
 */
final class CoverageTransformer implements ClassFileTransformer {

  private static final int ASM_API = Opcodes.ASM9;

  private static final String RECORDER = "io/github/testimpact/agent/CoverageRecorder";

  private static final Set<String> TEST_ANNOTATIONS = new HashSet<>();

  static {
    TEST_ANNOTATIONS.add("Lorg/junit/jupiter/api/Test;");
    TEST_ANNOTATIONS.add("Lorg/junit/jupiter/api/RepeatedTest;");
    TEST_ANNOTATIONS.add("Lorg/junit/jupiter/api/TestFactory;");
    TEST_ANNOTATIONS.add("Lorg/junit/jupiter/api/TestTemplate;");
    TEST_ANNOTATIONS.add("Lorg/junit/jupiter/params/ParameterizedTest;");
    TEST_ANNOTATIONS.add("Lorg/junit/Test;");
    TEST_ANNOTATIONS.add("Lorg/testng/annotations/Test;");
  }

  private static final String[] DEFAULT_EXCLUDES = {
    "io/github/testimpact/",
  };

  private final String[] includes;
  private final String[] excludes;

  CoverageTransformer(String includesCsv, String excludesCsv) {
    this.includes = splitCsvToInternal(includesCsv);
    this.excludes = mergeExcludes(splitCsvToInternal(excludesCsv));
  }

  @Override
  public byte[] transform(
      ClassLoader loader,
      String className,
      Class<?> classBeingRedefined,
      ProtectionDomain pd,
      byte[] classfileBuffer) {
    if (className == null) return null;
    // Skip bootstrap-loaded classes: the recorder lives on the system classloader and
    // isn't reachable from the bootstrap loader, so an instrumented bootstrap class
    // would throw NoClassDefFoundError on first invocation. java.xml's
    // org.xml.sax.* classes hit this path when test code goes through JAXP.
    if (loader == null) return null;
    if (isExcluded(className)) return null;
    if (!isIncluded(className)) return null;
    try {
      // Pass 1: find test methods.
      TestScanner scan = new TestScanner();
      new ClassReader(classfileBuffer)
          .accept(scan, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

      ClassReader cr = new ClassReader(classfileBuffer);
      // COMPUTE_MAXS only — COMPUTE_FRAMES would call ClassWriter#getCommonSuperClass which
      // uses Class.forName via the loader. That fails for test classes referencing types not
      // yet loadable in surefire's classloader (e.g. Maven-plugin types mocked under Mockito),
      // and the failure swallows the whole transform → no beginTest/endTest for that class.
      // Our injections (LDC + INVOKESTATIC at entry, INVOKESTATIC before RETURN) don't change
      // stack/locals at any existing frame point, so the original frames stay valid.
      ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
      // EXPAND_FRAMES is required by AdviceAdapter (LocalVariablesSorter) — without it,
      // any class compiled with -target 7+ that carries a StackMapTable (i.e. virtually
      // all modern classfiles) fails the transform with "LocalVariablesSorter only
      // accepts expanded frames", and the class runs uninstrumented.
      cr.accept(
          new InjectingVisitor(cw, className, scan.testMethods, scan.isTestClass),
          ClassReader.EXPAND_FRAMES);
      return cw.toByteArray();
    } catch (Throwable t) {
      if (Boolean.getBoolean("testimpact.debug")) {
        System.err.println("[testimpact] transform failed for " + className + ": " + t);
      }
      // Safety: never break the class load on a transform failure.
      return null;
    }
  }

  private boolean isExcluded(String internalName) {
    for (String p : excludes) if (internalName.startsWith(p)) return true;
    return false;
  }

  private boolean isIncluded(String internalName) {
    if (includes.length == 0) return true;
    for (String p : includes) if (internalName.startsWith(p)) return true;
    return false;
  }

  private static String[] splitCsvToInternal(String csv) {
    if (csv == null || csv.isEmpty()) return new String[0];
    String[] parts = csv.split(",");
    String[] out = new String[parts.length];
    for (int i = 0; i < parts.length; i++) out[i] = parts[i].trim().replace('.', '/');
    return out;
  }

  private static String[] mergeExcludes(String[] user) {
    String[] all = new String[DEFAULT_EXCLUDES.length + user.length];
    System.arraycopy(DEFAULT_EXCLUDES, 0, all, 0, DEFAULT_EXCLUDES.length);
    System.arraycopy(user, 0, all, DEFAULT_EXCLUDES.length, user.length);
    return all;
  }

  /** First pass: collect test method names + descriptors. */
  private static final class TestScanner extends ClassVisitor {
    boolean isTestClass = false;
    final Set<String> testMethods = new HashSet<>();

    TestScanner() {
      super(ASM_API);
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      return new MethodVisitor(ASM_API) {
        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
          if (TEST_ANNOTATIONS.contains(desc)) {
            testMethods.add(name + descriptor);
            isTestClass = true;
          }
          return null;
        }
      };
    }
  }

  /** Second pass: rewrite methods. */
  private static final class InjectingVisitor extends ClassVisitor {
    private final String internalName;
    private final Set<String> testMethods;
    private final boolean isTestClass;
    private String classNameForTouch;

    InjectingVisitor(
        ClassVisitor cv, String internalName, Set<String> testMethods, boolean isTestClass) {
      super(ASM_API, cv);
      this.internalName = internalName;
      this.testMethods = testMethods;
      this.isTestClass = isTestClass;
      this.classNameForTouch = internalName;
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
      if (mv == null) return null;
      if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return mv;

      boolean isTest = testMethods.contains(name + descriptor);
      String testId = isTest ? internalName.replace('/', '.') + "#" + name : null;
      // Skip touch injection for synthetic / bridge / <clinit> & <init> nano-methods? Keep simple:
      // instrument all.
      return new MethodAdvice(
          mv, access, name, descriptor, classNameForTouch, isTest, testId, !isTestClass);
    }
  }

  /** Advice: prepend touch (if production class), wrap with begin/end if test method. */
  private static final class MethodAdvice extends AdviceAdapter {
    private final String classRef;
    private final boolean isTest;
    private final String testId;
    private final boolean injectTouch;

    MethodAdvice(
        MethodVisitor mv,
        int access,
        String name,
        String desc,
        String classRef,
        boolean isTest,
        String testId,
        boolean injectTouch) {
      super(ASM_API, mv, access, name, desc);
      this.classRef = classRef;
      this.isTest = isTest;
      this.testId = testId;
      this.injectTouch = injectTouch;
    }

    @Override
    protected void onMethodEnter() {
      if (injectTouch) {
        visitLdcInsn(classRef);
        visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "touch", "(Ljava/lang/String;)V", false);
      }
      if (isTest) {
        visitLdcInsn(testId);
        visitMethodInsn(
            Opcodes.INVOKESTATIC, RECORDER, "beginTest", "(Ljava/lang/String;)V", false);
        // Touch the test class itself so map entries always include at least the test
        // class. This (a) keeps the test visible to impact analysis even if the body
        // only exercises mocks, and (b) makes test-only edits re-select that test.
        visitLdcInsn(classRef);
        visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "touch", "(Ljava/lang/String;)V", false);
      }
    }

    @Override
    protected void onMethodExit(int opcode) {
      // Normal returns only — AdviceAdapter doesn't fire onMethodExit for ATHROW.
      // Thrown tests are handled by CoverageRecorder.beginTest's auto-finalise of the
      // previous context, plus the shutdown-hook sweep over still-live contexts.
      if (isTest && opcode != Opcodes.ATHROW) {
        visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "endTest", "()V", false);
      }
    }
  }
}
