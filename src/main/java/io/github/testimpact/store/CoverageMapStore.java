package io.github.testimpact.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * JSON-backed persistence for {@link CoverageMap}.
 *
 * <p>Layout (top-level JSON object):
 *
 * <pre>
 * {
 *   "version":   int,
 *   "buildHash": "string",
 *   "timestamp": long,
 *   "entries":   { "testId": ["classRef", ...] }
 * }
 * </pre>
 *
 * Concurrency: {@link #mergeAndSave} uses an OS-level {@link FileLock} on a sibling {@code .lock}
 * file so concurrent module reports under {@code mvn -T} serialise read-modify-write. The data file
 * itself is replaced atomically via temp+rename.
 */
public final class CoverageMapStore {

  private CoverageMapStore() {}

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public static CoverageMap load(Path file) throws IOException {
    if (!Files.exists(file)) {
      return null;
    }
    Map<String, Object> raw = MAPPER.readValue(file.toFile(), new TypeReference<>() {});
    int version =
        raw.containsKey("version")
            ? ((Number) raw.get("version")).intValue()
            : CoverageMap.FORMAT_VERSION;
    String buildHash = raw.containsKey("buildHash") ? (String) raw.get("buildHash") : "";
    long ts = raw.containsKey("timestamp") ? ((Number) raw.get("timestamp")).longValue() : 0L;
    Map<String, Set<String>> entries = new HashMap<>();
    if (raw.containsKey("entries")) {
      @SuppressWarnings("unchecked")
      Map<String, java.util.List<String>> e =
          (Map<String, java.util.List<String>>) raw.get("entries");
      for (Map.Entry<String, java.util.List<String>> entry : e.entrySet()) {
        entries.put(entry.getKey(), new HashSet<>(entry.getValue()));
      }
    }
    return new CoverageMap(version, buildHash, ts, entries);
  }

  public static void save(Path file, CoverageMap map) throws IOException {
    if (file.getParent() != null) Files.createDirectories(file.getParent());
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("version", map.version());
    raw.put("buildHash", map.buildHash() == null ? "" : map.buildHash());
    raw.put("timestamp", map.timestamp());
    Map<String, Set<String>> entries = map.entries();
    Map<String, Set<String>> sorted = new LinkedHashMap<>();
    for (Map.Entry<String, Set<String>> e : entries.entrySet()) {
      sorted.put(e.getKey(), new TreeSet<>(e.getValue()));
    }
    raw.put("entries", sorted);
    Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), raw);
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  /**
   * Per-path JVM monitor. {@link FileLock} is process-wide but not thread-wide — two threads in the
   * same JVM trying to lock the same file get {@link
   * java.nio.channels.OverlappingFileLockException}. We synchronise on a per-path object first,
   * then take the OS lock for cross-process safety.
   */
  private static final ConcurrentMap<String, Object> JVM_LOCKS = new ConcurrentHashMap<>();

  public static void mergeAndSave(Path file, Map<String, Set<String>> newEntries, String buildHash)
      throws IOException {
    if (file.getParent() != null) Files.createDirectories(file.getParent());
    Path lockFile = file.resolveSibling(file.getFileName().toString() + ".lock");
    String jvmLockKey = file.toAbsolutePath().normalize().toString();
    Object jvmLock = JVM_LOCKS.computeIfAbsent(jvmLockKey, k -> new Object());
    synchronized (jvmLock) {
      try (FileChannel ch =
              FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
          FileLock ignored = ch.lock()) {
        CoverageMap map = load(file);
        if (map == null || map.version() != CoverageMap.FORMAT_VERSION) {
          map = new CoverageMap();
        }
        if (newEntries != null) {
          for (Map.Entry<String, Set<String>> e : newEntries.entrySet()) {
            map.replace(e.getKey(), e.getValue());
          }
          if (!newEntries.isEmpty()) {
            map.setBuildHash(buildHash == null ? "" : buildHash);
            map.setTimestamp(System.currentTimeMillis());
          }
        }
        save(file, map);
      }
      Files.deleteIfExists(lockFile);
    }
  }

  public static void delete(Path file) throws IOException {
    Files.deleteIfExists(file);
    Files.deleteIfExists(file.resolveSibling(file.getFileName().toString() + ".lock"));
  }
}
