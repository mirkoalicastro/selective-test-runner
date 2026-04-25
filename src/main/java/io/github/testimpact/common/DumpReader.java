package io.github.testimpact.common;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Reader for the binary dump file produced by {@code CoverageRecorder.flush()}. */
public final class DumpReader {

    private DumpReader() {}

    public static Map<String, Set<String>> read(Path dumpFile) throws IOException {
        Map<String, Set<String>> out = new HashMap<>();
        if (!Files.exists(dumpFile) || Files.size(dumpFile) == 0) return out;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(dumpFile)))) {
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                String testId = in.readUTF();
                int m = in.readInt();
                Set<String> classes = new HashSet<>(Math.max(8, m));
                for (int j = 0; j < m; j++) classes.add(in.readUTF());
                out.put(testId, classes);
            }
        }
        return out;
    }
}
