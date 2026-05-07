package io.github.mirkoalicastro.change;

/** Baseline strategies for diffing the current working tree. */
public enum Baseline {
  /** HEAD vs HEAD~1. */
  LAST_COMMIT,
  /** HEAD vs latest tag. */
  LAST_TAG,
  /** Working tree vs the commit recorded in the coverage map. */
  LAST_FULL_RUN;

  public static Baseline parse(String s) {
    if (s == null) return LAST_COMMIT;
    switch (s.trim().toLowerCase()) {
      case "lastcommit":
        return LAST_COMMIT;
      case "lasttag":
        return LAST_TAG;
      case "lastfullrun":
        return LAST_FULL_RUN;
      default:
        throw new IllegalArgumentException("Unknown baseline: " + s);
    }
  }
}
