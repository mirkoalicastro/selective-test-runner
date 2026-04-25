package io.github.testimpact.change;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Resolve changed Java source files to JVM-internal class names ({@code com/acme/Foo}).
 *
 * For each changed source like {@code src/main/java/com/acme/Foo.java}, we:
 *  1. derive the package-relative path {@code com/acme/Foo};
 *  2. scan the corresponding output classes directory for {@code Foo.class},
 *     {@code Foo$*.class}, {@code Foo$1.class}, etc., and add each match.
 *
 * If the output directory is not present (clean build), we fall back to the source-path-derived
 * name only — inner classes are missed but never silently misattributed.
 */
public final class ClassResolver {

    private final Path outputDir;
    private final Path testOutputDir;

    public ClassResolver(Path outputDir, Path testOutputDir) {
        this.outputDir = outputDir;
        this.testOutputDir = testOutputDir;
    }

    public Set<String> resolve(Collection<String> changedSources) {
        Set<String> classes = new HashSet<>();
        for (String src : changedSources) {
            String base = sourceToInternalName(src);
            if (base == null) continue;
            int slash = base.lastIndexOf('/');
            String pkg = slash < 0 ? "" : base.substring(0, slash);
            String simple = slash < 0 ? base : base.substring(slash + 1);

            boolean foundInner = false;
            foundInner |= addInnerClassFiles(outputDir, pkg, simple, classes);
            foundInner |= addInnerClassFiles(testOutputDir, pkg, simple, classes);
            if (!foundInner) classes.add(base); // fallback when classes dir is empty
        }
        return classes;
    }

    private static String sourceToInternalName(String src) {
        // Match common Maven source roots.
        String[] roots = {"src/main/java/", "src/test/java/"};
        for (String r : roots) {
            int idx = src.indexOf(r);
            if (idx >= 0) {
                String rel = src.substring(idx + r.length());
                if (rel.endsWith(".java")) return rel.substring(0, rel.length() - 5);
            }
        }
        // Fallback: drop extension.
        if (src.endsWith(".java")) return src.substring(0, src.length() - 5);
        return null;
    }

    private static boolean addInnerClassFiles(Path classesDir, String pkg, String simple, Set<String> out) {
        if (classesDir == null || !Files.isDirectory(classesDir)) return false;
        Path pkgDir = pkg.isEmpty() ? classesDir : classesDir.resolve(pkg);
        if (!Files.isDirectory(pkgDir)) return false;
        boolean found = false;
        try (Stream<Path> stream = Files.list(pkgDir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".class")) continue;
                String nameNoExt = name.substring(0, name.length() - 6);
                if (nameNoExt.equals(simple) || nameNoExt.startsWith(simple + "$")) {
                    String internal = pkg.isEmpty() ? nameNoExt : pkg + "/" + nameNoExt;
                    out.add(internal);
                    found = true;
                }
            }
        } catch (IOException ignored) {
        }
        return found;
    }

    public static ClassResolver of(String outputDir, String testOutputDir) {
        return new ClassResolver(
                outputDir == null ? null : Paths.get(outputDir),
                testOutputDir == null ? null : Paths.get(testOutputDir));
    }
}
