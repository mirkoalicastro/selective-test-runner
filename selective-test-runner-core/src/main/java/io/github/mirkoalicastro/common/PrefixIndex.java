package io.github.mirkoalicastro.common;

import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

/**
 * Utility for longest-prefix matching over a descending-length-ordered {@link TreeMap}. Used by
 * both the Maven and Gradle plugins to map repo-root-relative source paths to the owning build
 * module.
 */
public final class PrefixIndex {

  private PrefixIndex() {}

  /** Comparator ordering strings by descending length, then lexicographically. */
  public static Comparator<String> descendingByLength() {
    return (a, b) -> {
      int byLength = Integer.compare(b.length(), a.length());
      return byLength != 0 ? byLength : a.compareTo(b);
    };
  }

  /**
   * Walks a descending-length-ordered {@link TreeMap} and returns the value whose key is the
   * longest prefix of {@code path}. An empty-string key is treated as a wildcard fallback.
   *
   * @param index map ordered by {@link #descendingByLength()}
   * @param path the path to match against
   * @return the value for the longest matching prefix, or {@code null} if none match
   */
  public static <T> T longestPrefixMatch(TreeMap<String, T> index, String path) {
    if (path == null || index == null || index.isEmpty()) return null;
    for (Map.Entry<String, T> e : index.entrySet()) {
      String prefix = e.getKey();
      if (prefix.isEmpty()) return e.getValue();
      if (path.startsWith(prefix + "/")) return e.getValue();
    }
    return null;
  }
}
