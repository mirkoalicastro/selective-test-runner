package io.github.testimpact.resolve;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Result of an impact resolution. */
public final class Selection {

  /** Reason the resolver returned a particular outcome. */
  public enum Mode {
    /** Coverage map present, change set computed, intersection produced concrete tests. */
    SELECTED,
    /**
     * Force a full run: cold map, version mismatch, age limit reached, empty intersection, git
     * failure, or any other safety condition.
     */
    FULL_RUN
  }

  private final Mode mode;
  private final Set<String> selectedTestIds;
  private final Set<String> changedClasses;
  private final String reason;

  private Selection(
      Mode mode, Set<String> selectedTestIds, Set<String> changedClasses, String reason) {
    this.mode = mode;
    this.selectedTestIds = selectedTestIds;
    this.changedClasses = changedClasses;
    this.reason = reason;
  }

  public static Selection selected(Set<String> tests, Set<String> changedClasses) {
    return new Selection(Mode.SELECTED, tests, changedClasses, null);
  }

  public static Selection fullRun(String reason, Set<String> changedClasses) {
    return new Selection(
        Mode.FULL_RUN,
        Collections.emptySet(),
        changedClasses == null ? Collections.emptySet() : changedClasses,
        reason);
  }

  public Mode mode() {
    return mode;
  }

  public Set<String> selectedTestIds() {
    return selectedTestIds;
  }

  public Set<String> changedClasses() {
    return changedClasses;
  }

  public String reason() {
    return reason;
  }

  /**
   * The set of test class names (without #method), suitable for Surefire {@code <test>} include.
   */
  public Set<String> selectedTestClasses() {
    Set<String> classes = new LinkedHashSet<>();
    for (String id : selectedTestIds) {
      int hash = id.indexOf('#');
      classes.add(hash < 0 ? id : id.substring(0, hash));
    }
    return classes;
  }
}
