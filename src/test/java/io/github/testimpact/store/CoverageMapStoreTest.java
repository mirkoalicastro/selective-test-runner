package io.github.testimpact.store;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoverageMapStoreTest {

  @Test
  void roundTrip(@TempDir Path tmp) throws IOException {
    CoverageMap m = new CoverageMap();
    m.setBuildHash("a3f91cc");
    Set<String> classes = new HashSet<>();
    classes.add("com/acme/OrderService");
    classes.add("com/acme/PriceCalculator");
    m.replace("com.acme.OrderTest#computesTotal", classes);
    m.replace("com.acme.OrderTest#applyDiscount", new HashSet<>(List.of("com/acme/Discount")));

    Path file = tmp.resolve("coverage.json");
    CoverageMapStore.save(file, m);

    CoverageMap loaded = CoverageMapStore.load(file);
    assertNotNull(loaded);
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
    m.replace("T1", new HashSet<>(List.of("a/A", "a/B")));
    m.replace("T2", new HashSet<>(List.of("a/C")));
    m.replace("T3", new HashSet<>(List.of("a/B", "a/D")));

    Set<String> changed = new HashSet<>(List.of("a/B"));
    Set<String> hit = m.testsTouching(changed);
    assertEquals(new HashSet<>(List.of("T1", "T3")), hit);
  }

  @Test
  void mergeAndSaveCreatesFreshMapWhenMissing(@TempDir Path tmp) throws IOException {
    Path file = tmp.resolve("coverage.json");
    Map<String, Set<String>> entries = new HashMap<>();
    entries.put("com.acme.T1#a", new HashSet<>(List.of("a/A")));
    CoverageMapStore.mergeAndSave(file, entries, "abc123");

    CoverageMap loaded = CoverageMapStore.load(file);
    assertNotNull(loaded);
    assertEquals(1, loaded.size());
    assertEquals("abc123", loaded.buildHash());
  }

  @Test
  void mergeAndSavePreservesPriorEntries(@TempDir Path tmp) throws IOException {
    Path file = tmp.resolve("coverage.json");
    CoverageMap initial = new CoverageMap();
    initial.replace("T_old", new HashSet<>(List.of("a/Old")));
    CoverageMapStore.save(file, initial);

    Map<String, Set<String>> incoming = new HashMap<>();
    incoming.put("T_new", new HashSet<>(List.of("a/New")));
    CoverageMapStore.mergeAndSave(file, incoming, "h");

    CoverageMap loaded = CoverageMapStore.load(file);
    assertNotNull(loaded);
    assertEquals(2, loaded.size());
    assertEquals(new HashSet<>(List.of("a/Old")), loaded.entries().get("T_old"));
    assertEquals(new HashSet<>(List.of("a/New")), loaded.entries().get("T_new"));
  }

  @Test
  void mergeAndSaveReplacesEntryForRerunTest(@TempDir Path tmp) throws IOException {
    Path file = tmp.resolve("coverage.json");
    CoverageMap initial = new CoverageMap();
    initial.replace("T1", new HashSet<>(List.of("a/Old1", "a/Old2")));
    CoverageMapStore.save(file, initial);

    Map<String, Set<String>> incoming = new HashMap<>();
    incoming.put("T1", new HashSet<>(List.of("a/Fresh")));
    CoverageMapStore.mergeAndSave(file, incoming, "h");

    CoverageMap loaded = CoverageMapStore.load(file);
    assertNotNull(loaded);
    assertEquals(1, loaded.size());
    assertEquals(new HashSet<>(List.of("a/Fresh")), loaded.entries().get("T1"));
  }

  @Test
  void mergeAndSaveIsConcurrencySafe(@TempDir Path tmp) throws Exception {
    // Simulates several modules under `mvn -T` writing to the shared map at once.
    Path file = tmp.resolve("coverage.json");
    int threads = 16;
    int entriesPerThread = 25;

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    List<Future<?>> futures = new ArrayList<>();

    for (int t = 0; t < threads; t++) {
      final int tid = t;
      futures.add(
          pool.submit(
              () -> {
                start.await();
                Map<String, Set<String>> entries = new HashMap<>();
                for (int i = 0; i < entriesPerThread; i++) {
                  String testId = "module" + tid + ".T" + i + "#go";
                  entries.put(testId, new HashSet<>(List.of("c/C" + tid + "_" + i)));
                }
                CoverageMapStore.mergeAndSave(file, entries, "h" + tid);
                return null;
              }));
    }

    start.countDown();
    for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);

    CoverageMap loaded = CoverageMapStore.load(file);
    assertNotNull(loaded);
    assertEquals(threads * entriesPerThread, loaded.size());
  }
}
