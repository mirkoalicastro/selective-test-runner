package io.github.mirkoalicastro.change;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Resolve changed Java source files to JVM-internal class names ({@code com/acme/Foo}).
 *
 * <p>For each changed source like {@code module-a/src/main/java/com/acme/Foo.java}, we: 1. derive
 * the package-relative path {@code com/acme/Foo}; 2. scan every configured output dir for {@code
 * Foo.class}, {@code Foo$*.class}, {@code Foo$1.class}, etc., and add each match.
 *
 * <p>Multi-module builds pass every reactor module's compile + test-compile output dirs so a change
 * in a sibling module's source still expands its inner classes correctly.
 *
 * <p>If no output dir contains the file (clean build, or no module owns the path), we fall back to
 * the source-path-derived name only — inner classes are missed but never silently misattributed.
 */
public final class ClassResolver {

  private final List<Path> searchDirs;

  public ClassResolver(Path outputDir, Path testOutputDir) {
    this.searchDirs = new ArrayList<>(2);
    if (outputDir != null) this.searchDirs.add(outputDir);
    if (testOutputDir != null) this.searchDirs.add(testOutputDir);
  }

  public ClassResolver(List<Path> searchDirs) {
    this.searchDirs = new ArrayList<>(searchDirs);
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
      for (Path dir : searchDirs) {
        foundInner |= addInnerClassFiles(dir, pkg, simple, classes);
      }
      if (!foundInner) classes.add(base);
    }
    return classes;
  }

  private static String sourceToInternalName(String src) {
    // Strip the longest known source-root prefix wherever it appears in the path,
    // so monorepo paths like "module-a/src/main/java/com/acme/Foo.java" still work.
    String[] roots = {"src/main/java/", "src/test/java/"};
    for (String r : roots) {
      int idx = src.indexOf(r);
      if (idx >= 0) {
        String rel = src.substring(idx + r.length());
        if (rel.endsWith(".java")) return rel.substring(0, rel.length() - 5);
      }
    }
    if (src.endsWith(".java")) return src.substring(0, src.length() - 5);
    return null;
  }

  private static boolean addInnerClassFiles(
      Path classesDir, String pkg, String simple, Set<String> out) {
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
}
