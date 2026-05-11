package io.github.mirkoalicastro.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class PrefixIndexTest {

  private static TreeMap<String, String> index(String... pairs) {
    TreeMap<String, String> m = new TreeMap<>(PrefixIndex.descendingByLength());
    for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
    return m;
  }

  @Test
  void longestPrefixMatchPicksLongestModule() {
    TreeMap<String, String> idx =
        index(
            "", "parent",
            "module-a", "module-a",
            "module-b", "module-b",
            "module-a/sub", "module-a-sub");
    assertEquals(
        "module-a-sub", PrefixIndex.longestPrefixMatch(idx, "module-a/sub/src/main/java/X.java"));
    assertEquals("module-a", PrefixIndex.longestPrefixMatch(idx, "module-a/src/main/java/X.java"));
    assertEquals("module-b", PrefixIndex.longestPrefixMatch(idx, "module-b/src/main/java/Y.java"));
  }

  @Test
  void emptyPrefixIsCatchAll() {
    TreeMap<String, String> idx =
        index(
            "", "parent",
            "module-a", "module-a");
    assertEquals("parent", PrefixIndex.longestPrefixMatch(idx, "tools/scripts/build.sh"));
    assertEquals("module-a", PrefixIndex.longestPrefixMatch(idx, "module-a/foo"));
  }

  @Test
  void noMatchReturnsNullWhenNoCatchAll() {
    TreeMap<String, String> idx = index("module-a", "module-a");
    assertNull(PrefixIndex.longestPrefixMatch(idx, "module-b/src/main/java/X.java"));
    assertNull(PrefixIndex.longestPrefixMatch(idx, "tools/x"));
  }

  @Test
  void prefixMustBeFollowedBySlash() {
    TreeMap<String, String> idx = index("module-a", "module-a");
    assertNull(PrefixIndex.longestPrefixMatch(idx, "module-a-extra/src/X.java"));
  }

  @Test
  void nullPathReturnsNull() {
    TreeMap<String, String> idx = index("module-a", "module-a");
    assertNull(PrefixIndex.longestPrefixMatch(idx, null));
  }
}
