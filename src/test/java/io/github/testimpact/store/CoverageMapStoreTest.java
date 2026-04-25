package io.github.testimpact.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CoverageMapStoreTest {

    @Test
    void roundTrip(@TempDir Path tmp) throws IOException {
        CoverageMap m = new CoverageMap();
        m.setBuildHash("a3f91cc");
        Set<String> classes = new HashSet<>();
        classes.add("com/acme/OrderService");
        classes.add("com/acme/PriceCalculator");
        m.replace("com.acme.OrderTest#computesTotal", classes);
        m.replace("com.acme.OrderTest#applyDiscount", new HashSet<>(java.util.Arrays.asList("com/acme/Discount")));

        Path file = tmp.resolve("coverage.db");
        CoverageMapStore.save(file, m);

        CoverageMap loaded = CoverageMapStore.load(file);
        assertEquals(CoverageMap.FORMAT_VERSION, loaded.version());
        assertEquals("a3f91cc", loaded.buildHash());
        assertEquals(2, loaded.size());
        assertEquals(classes, loaded.entries().get("com.acme.OrderTest#computesTotal"));
    }

    @Test
    void missingFileLoadsAsNull(@TempDir Path tmp) throws IOException {
        assertNull(CoverageMapStore.load(tmp.resolve("nope.db")));
    }

    @Test
    void testsTouchingResolvesIntersection() {
        CoverageMap m = new CoverageMap();
        m.replace("T1", new HashSet<>(java.util.Arrays.asList("a/A", "a/B")));
        m.replace("T2", new HashSet<>(java.util.Arrays.asList("a/C")));
        m.replace("T3", new HashSet<>(java.util.Arrays.asList("a/B", "a/D")));

        Set<String> changed = new HashSet<>(java.util.Arrays.asList("a/B"));
        Set<String> hit = m.testsTouching(changed);
        assertEquals(new HashSet<>(java.util.Arrays.asList("T1", "T3")), hit);
    }
}
