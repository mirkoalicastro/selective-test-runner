package io.github.testimpact.store;

import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * MessagePack-backed persistence for {@link CoverageMap}.
 *
 * Layout (top-level map of 4 keys, fixed names):
 *   "v"  -> uint8   format version
 *   "b"  -> string  build_hash
 *   "t"  -> uint64  timestamp
 *   "e"  -> map&lt;string, array&lt;string&gt;&gt;  entries (forward index)
 *
 * Concurrency: {@link #mergeAndSave} uses an OS-level {@link FileLock} on a sibling
 * {@code .lock} file so concurrent module reports under {@code mvn -T} serialise
 * read-modify-write. The data file itself is replaced atomically via temp+rename.
 */
public final class CoverageMapStore {

    private CoverageMapStore() {}

    public static CoverageMap load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        byte[] bytes = Files.readAllBytes(file);
        try (MessageUnpacker u = MessagePack.newDefaultUnpacker(bytes)) {
            int top = u.unpackMapHeader();
            int version = CoverageMap.FORMAT_VERSION;
            String buildHash = "";
            long ts = 0L;
            Map<String, Set<String>> entries = new HashMap<>();
            for (int i = 0; i < top; i++) {
                String key = u.unpackString();
                switch (key) {
                    case "v": version = u.unpackInt(); break;
                    case "b": buildHash = u.unpackString(); break;
                    case "t": ts = u.unpackLong(); break;
                    case "e": {
                        int n = u.unpackMapHeader();
                        for (int j = 0; j < n; j++) {
                            String testId = u.unpackString();
                            int m = u.unpackArrayHeader();
                            Set<String> classes = new HashSet<>(Math.max(8, m));
                            for (int k = 0; k < m; k++) classes.add(u.unpackString());
                            entries.put(testId, classes);
                        }
                        break;
                    }
                    default: u.skipValue();
                }
            }
            return new CoverageMap(version, buildHash, ts, entries);
        }
    }

    public static void save(Path file, CoverageMap map) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        try (MessageBufferPacker p = MessagePack.newDefaultBufferPacker()) {
            p.packMapHeader(4);
            p.packString("v"); p.packInt(map.version());
            p.packString("b"); p.packString(map.buildHash() == null ? "" : map.buildHash());
            p.packString("t"); p.packLong(map.timestamp());
            p.packString("e");
            Map<String, Set<String>> entries = map.entries();
            p.packMapHeader(entries.size());
            for (Map.Entry<String, Set<String>> e : entries.entrySet()) {
                p.packString(e.getKey());
                Set<String> classes = e.getValue();
                p.packArrayHeader(classes.size());
                for (String c : classes) p.packString(c);
            }
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            Files.write(tmp, p.toByteArray());
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
    }

    /**
     * Atomically read-merge-write the coverage map at {@code file}. Replaces every
     * (testId → classes) pair in {@code newEntries} into the existing map, then writes
     * the merged map back. If the existing map is missing or has the wrong format
     * version, a fresh map is started. Tests not in {@code newEntries} are preserved.
     *
     * Coordinated via an OS-level lock on {@code file.lock} so concurrent invocations
     * (multiple modules under {@code mvn -T}, or different JVMs) serialise correctly.
     *
     * @param buildHash recorded as the map's new buildHash if {@code newEntries} is non-empty
     */
    /**
     * Per-path JVM monitor. {@link FileLock} is process-wide but not thread-wide — two
     * threads in the same JVM trying to lock the same file get
     * {@link java.nio.channels.OverlappingFileLockException}. We synchronise on a
     * per-path object first, then take the OS lock for cross-process safety.
     */
    private static final ConcurrentMap<String, Object> JVM_LOCKS = new ConcurrentHashMap<>();

    public static void mergeAndSave(Path file, Map<String, Set<String>> newEntries, String buildHash) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Path lockFile = file.resolveSibling(file.getFileName().toString() + ".lock");
        String jvmLockKey = file.toAbsolutePath().normalize().toString();
        Object jvmLock = JVM_LOCKS.computeIfAbsent(jvmLockKey, k -> new Object());
        synchronized (jvmLock) {
            try (FileChannel ch = FileChannel.open(lockFile,
                         StandardOpenOption.CREATE, StandardOpenOption.WRITE);
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
        }
    }

    public static void delete(Path file) throws IOException {
        Files.deleteIfExists(file);
        Files.deleteIfExists(file.resolveSibling(file.getFileName().toString() + ".lock"));
    }
}
