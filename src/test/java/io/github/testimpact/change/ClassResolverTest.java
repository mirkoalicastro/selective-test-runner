package io.github.testimpact.change;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassResolverTest {

  @Test
  void expandsInnerClassesFromOutputDir(@TempDir Path tmp) throws IOException {
    Path classes = tmp.resolve("classes");
    Path pkg = classes.resolve("com/acme");
    Files.createDirectories(pkg);
    Files.createFile(pkg.resolve("OrderService.class"));
    Files.createFile(pkg.resolve("OrderService$Builder.class"));
    Files.createFile(pkg.resolve("OrderService$1.class"));
    Files.createFile(pkg.resolve("OtherClass.class"));

    ClassResolver r = new ClassResolver(classes, null);
    Set<String> resolved = r.resolve(Arrays.asList("src/main/java/com/acme/OrderService.java"));

    assertEquals(3, resolved.size());
    assertTrue(resolved.contains("com/acme/OrderService"));
    assertTrue(resolved.contains("com/acme/OrderService$Builder"));
    assertTrue(resolved.contains("com/acme/OrderService$1"));
  }

  @Test
  void fallsBackToSourceNameWhenOutputDirMissing(@TempDir Path tmp) {
    ClassResolver r = new ClassResolver(tmp.resolve("nonexistent"), null);
    Set<String> resolved = r.resolve(Arrays.asList("src/main/java/com/acme/Foo.java"));
    assertEquals(1, resolved.size());
    assertTrue(resolved.contains("com/acme/Foo"));
  }

  @Test
  void multiModuleResolvesInnerClassesFromCorrectSiblingModule(@TempDir Path tmp)
      throws IOException {
    Path moduleAClasses = tmp.resolve("module-a/target/classes");
    Path moduleBClasses = tmp.resolve("module-b/target/classes");
    Files.createDirectories(moduleAClasses.resolve("com/acme"));
    Files.createDirectories(moduleBClasses.resolve("com/acme"));

    // Foo only exists in module-b; resolver must find it even though we pass both dirs.
    Files.createFile(moduleBClasses.resolve("com/acme/Foo.class"));
    Files.createFile(moduleBClasses.resolve("com/acme/Foo$Inner.class"));

    ClassResolver r = new ClassResolver(java.util.Arrays.asList(moduleAClasses, moduleBClasses));
    Set<String> resolved = r.resolve(Arrays.asList("module-b/src/main/java/com/acme/Foo.java"));

    assertEquals(2, resolved.size());
    assertTrue(resolved.contains("com/acme/Foo"));
    assertTrue(resolved.contains("com/acme/Foo$Inner"));
  }

  @Test
  void multiModuleStripsLeadingModuleDirFromSourcePath(@TempDir Path tmp) throws IOException {
    Path classes = tmp.resolve("module-a/target/classes/com/acme");
    Files.createDirectories(classes);
    Files.createFile(classes.resolve("Bar.class"));

    ClassResolver r =
        new ClassResolver(java.util.Arrays.asList(tmp.resolve("module-a/target/classes")));
    Set<String> resolved = r.resolve(Arrays.asList("module-a/src/main/java/com/acme/Bar.java"));

    assertEquals(1, resolved.size());
    assertTrue(resolved.contains("com/acme/Bar"));
  }
}
