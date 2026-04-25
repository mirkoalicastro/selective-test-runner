package io.github.testimpact.change;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
