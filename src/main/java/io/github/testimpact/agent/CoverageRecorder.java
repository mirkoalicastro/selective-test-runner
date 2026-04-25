package io.github.testimpact.agent;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-test class-touch recorder. Loadable from the system class loader, thread-safe.
 *
 * Lifecycle:
 *   beginTest(id)  — pushes a new test context onto the calling thread; finalises any
 *                    previous unfinished context for that thread (covers thrown tests).
 *   touch(cls)     — records a class-ref into the calling thread's current context.
 *   endTest()      — finalises the calling thread's current context.
 *
 * Aggregated entries are written to the file at system property {@code testimpact.dump}
 * during JVM shutdown. Any contexts still live at shutdown are finalised first
 * (this catches the last test on a thread when it threw).
 *
 * Dump format (DataOutput, simple binary):
 *   int  numTests
 *   for each test:
 *     UTF testId
 *     int numClasses
 *     for each class: UTF classRef
 */
public final class CoverageRecorder {

    private CoverageRecorder() {}

    private static final ThreadLocal<TestContext> CURRENT = new ThreadLocal<>();

    /** Live contexts indexed by thread, used for shutdown finalisation. */
    private static final Map<Thread, TestContext> LIVE = new ConcurrentHashMap<>();

    /** Aggregated entries: testId -> set of touched class refs. */
    private static final Map<String, Set<String>> ENTRIES = new HashMap<>();

    private static volatile boolean enabled = false;

    static synchronized void enable() {
        if (enabled) return;
        enabled = true;
        Runtime.getRuntime().addShutdownHook(new Thread(CoverageRecorder::shutdown, "test-impact-flush"));
    }

    public static boolean isEnabled() { return enabled; }

    public static void beginTest(String testId) {
        if (!enabled) return;
        TestContext prev = CURRENT.get();
        if (prev != null) finalise(prev); // covers a previously-thrown test on this thread
        TestContext ctx = new TestContext(testId);
        CURRENT.set(ctx);
        LIVE.put(Thread.currentThread(), ctx);
    }

    public static void endTest() {
        if (!enabled) return;
        TestContext ctx = CURRENT.get();
        if (ctx == null) return;
        CURRENT.remove();
        LIVE.remove(Thread.currentThread());
        finalise(ctx);
    }

    public static void touch(String classRef) {
        if (!enabled) return;
        TestContext ctx = CURRENT.get();
        if (ctx != null) ctx.touched.add(classRef);
    }

    private static void finalise(TestContext ctx) {
        if (ctx.touched.isEmpty()) return;
        synchronized (ENTRIES) {
            ENTRIES.merge(ctx.testId, ctx.touched, (a, b) -> { a.addAll(b); return a; });
        }
    }

    private static void shutdown() {
        // Finalise any contexts still live (their tests threw and never called endTest).
        for (Map.Entry<Thread, TestContext> e : LIVE.entrySet()) finalise(e.getValue());
        LIVE.clear();
        flush();
    }

    /** Flush accumulated data to the configured dump file. Safe to call repeatedly. */
    public static synchronized void flush() {
        String dump = System.getProperty("testimpact.dump");
        if (dump == null || dump.isEmpty()) return;
        Map<String, Set<String>> snapshot;
        synchronized (ENTRIES) {
            if (ENTRIES.isEmpty()) return;
            snapshot = new HashMap<>(ENTRIES.size());
            for (Map.Entry<String, Set<String>> e : ENTRIES.entrySet()) {
                snapshot.put(e.getKey(), new HashSet<>(e.getValue()));
            }
        }
        Path path = Paths.get(dump);
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(path)))) {
                out.writeInt(snapshot.size());
                for (Map.Entry<String, Set<String>> e : snapshot.entrySet()) {
                    out.writeUTF(e.getKey());
                    Set<String> classes = e.getValue();
                    out.writeInt(classes.size());
                    for (String c : classes) out.writeUTF(c);
                }
            }
        } catch (IOException ignored) {
            // never throw from a shutdown hook
        }
    }

    private static final class TestContext {
        final String testId;
        final Set<String> touched = Collections.synchronizedSet(new HashSet<>());
        TestContext(String testId) { this.testId = testId; }
    }
}
