package io.github.testimpact.store;

import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * MessagePack-backed persistence for {@link CoverageMap}.
 *
 * Layout (top-level map of 4 keys, fixed names):
 *   "v"  -> uint8   format version
 *   "b"  -> string  build_hash
 *   "t"  -> uint64  timestamp
 *   "e"  -> map&lt;string, array&lt;string&gt;&gt;  entries (forward index)
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
        Files.createDirectories(file.getParent());
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

    public static void delete(Path file) throws IOException {
        Files.deleteIfExists(file);
    }
}
