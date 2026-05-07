package io.github.mirkoalicastro.resolve;

import io.github.mirkoalicastro.store.CoverageMap;
import java.util.Set;

/** Intersects a change set with a coverage map and produces a {@link Selection}. */
public final class ImpactResolver {

  private final int fullRunInterval;
  private final boolean failOnEmptySelection;

  public ImpactResolver(int fullRunInterval, boolean failOnEmptySelection) {
    this.fullRunInterval = fullRunInterval;
    this.failOnEmptySelection = failOnEmptySelection;
  }

  public Selection resolve(CoverageMap map, Set<String> changedClasses, int buildsSinceFullRun) {
    if (map == null) {
      return Selection.fullRun("coverage map absent", changedClasses);
    }
    if (map.version() != CoverageMap.FORMAT_VERSION) {
      return Selection.fullRun(
          "coverage map version mismatch (have "
              + map.version()
              + ", expected "
              + CoverageMap.FORMAT_VERSION
              + ")",
          changedClasses);
    }
    if (fullRunInterval > 0 && buildsSinceFullRun >= fullRunInterval) {
      return Selection.fullRun(
          "coverage map older than fullRunInterval (" + fullRunInterval + ")", changedClasses);
    }
    if (changedClasses == null) {
      return Selection.fullRun("change set unavailable", null);
    }
    if (changedClasses.isEmpty()) {
      // No changes detected — run full suite.
      return Selection.fullRun("no changed classes detected", changedClasses);
    }
    Set<String> tests = map.testsTouching(changedClasses);
    if (tests.isEmpty()) {
      String msg = "impact resolution returned 0 tests";
      if (failOnEmptySelection) {
        throw new IllegalStateException(msg + " and failOnEmptySelection=true");
      }
      return Selection.fullRun(msg, changedClasses);
    }
    return Selection.selected(tests, changedClasses);
  }
}
