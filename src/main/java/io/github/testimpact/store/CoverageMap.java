package io.github.testimpact.store;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * In-memory representation of the on-disk coverage map.
 *
 * <p>Forward index: TestId -> Set&lt;ClassRef&gt; (persisted).
 *
 * <p>TestId = "fully.qualified.TestClass#methodName" ClassRef = "fully/qualified/ClassName" (JVM
 * internal name)
 */
public final class CoverageMap {

  public static final int FORMAT_VERSION = 1;

  private final int version;
  private String buildHash;
  private long timestamp;
  private final Map<String, Set<String>> entries;

  public CoverageMap() {
    this(FORMAT_VERSION, "", System.currentTimeMillis(), new HashMap<>());
  }

  public CoverageMap(
      int version, String buildHash, long timestamp, Map<String, Set<String>> entries) {
    this.version = version;
    this.buildHash = buildHash;
    this.timestamp = timestamp;
    this.entries = entries;
  }

  public int version() {
    return version;
  }

  public String buildHash() {
    return buildHash;
  }

  public long timestamp() {
    return timestamp;
  }

  public Map<String, Set<String>> entries() {
    return entries;
  }

  public void setBuildHash(String h) {
    this.buildHash = h;
  }

  public void setTimestamp(long t) {
    this.timestamp = t;
  }

  public void replace(String testId, Set<String> touched) {
    entries.put(testId, new HashSet<>(touched));
  }

  /** Resolve the set of tests that touch any of the given changed classes. */
  public Set<String> testsTouching(Set<String> changedClasses) {
    if (changedClasses == null || changedClasses.isEmpty()) return Collections.emptySet();
    Set<String> result = new HashSet<>();
    for (Map.Entry<String, Set<String>> e : entries.entrySet()) {
      for (String c : e.getValue()) {
        if (changedClasses.contains(c)) {
          result.add(e.getKey());
          break;
        }
      }
    }
    return result;
  }

  public int size() {
    return entries.size();
  }
}
