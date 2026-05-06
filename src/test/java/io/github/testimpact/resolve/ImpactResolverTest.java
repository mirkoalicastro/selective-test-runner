package io.github.testimpact.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.testimpact.store.CoverageMap;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ImpactResolverTest {

  @Test
  void coldMapForcesFullRun() {
    Selection s = new ImpactResolver(50, false).resolve(null, Collections.singleton("a/A"), 0);
    assertEquals(Selection.Mode.FULL_RUN, s.mode());
    assertTrue(s.reason().contains("absent"));
  }

  @Test
  void emptyChangesForceFullRun() {
    CoverageMap m = new CoverageMap();
    Selection s = new ImpactResolver(50, false).resolve(m, Collections.emptySet(), 0);
    assertEquals(Selection.Mode.FULL_RUN, s.mode());
  }

  @Test
  void zeroIntersectionForcesFullRunByDefault() {
    CoverageMap m = new CoverageMap();
    m.replace("T1", new HashSet<>(List.of("a/A")));
    Selection s = new ImpactResolver(50, false).resolve(m, Collections.singleton("z/Z"), 0);
    assertEquals(Selection.Mode.FULL_RUN, s.mode());
  }

  @Test
  void zeroIntersectionThrowsWhenStrict() {
    CoverageMap m = new CoverageMap();
    m.replace("T1", new HashSet<>(List.of("a/A")));
    assertThrows(
        IllegalStateException.class,
        () -> new ImpactResolver(50, true).resolve(m, Collections.singleton("z/Z"), 0));
  }

  @Test
  void selectsTestsTouchingChangedClasses() {
    CoverageMap m = new CoverageMap();
    m.replace("com.acme.T1#a", new HashSet<>(java.util.Arrays.asList("a/A", "a/B")));
    m.replace("com.acme.T2#b", new HashSet<>(List.of("a/C")));

    Set<String> changed = new HashSet<>(List.of("a/A"));
    Selection s = new ImpactResolver(50, false).resolve(m, changed, 0);
    assertEquals(Selection.Mode.SELECTED, s.mode());
    assertEquals(Collections.singleton("com.acme.T1#a"), s.selectedTestIds());
    assertEquals(Collections.singleton("com.acme.T1"), s.selectedTestClasses());
  }

  @Test
  void exceedingFullRunIntervalForcesFullRun() {
    CoverageMap m = new CoverageMap();
    m.replace("T1", new HashSet<>(List.of("a/A")));
    Selection s = new ImpactResolver(5, false).resolve(m, Collections.singleton("a/A"), 5);
    assertEquals(Selection.Mode.FULL_RUN, s.mode());
    assertTrue(s.reason().contains("fullRunInterval"));
  }
}
