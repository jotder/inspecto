package com.gamma.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SE-1a — the Space lock ({@code docs/okf/product/module/config/spec-space-lock.md}): what it lists, how a file keeps
 * its uid, what it reports, that a bad lock is recomputed and never an error, the kill switch, the background
 * schedule, and — over every shipped Space — that computing it writes nothing under {@code config/}.
 */
class SpaceLockTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach
    void restore() {
        System.clearProperty(SpaceLock.PROPERTY);
    }

    /** A small Space: a Pipeline, an Enrichment and a Job naming it, a setting, a history copy and server state. */
    private static Path space(Path tmp) throws IOException {
        Path config = Files.createDirectories(tmp.resolve("alpha/config"));
        write(config.resolve("orders/orders_pipeline.toon"), "id: orders\nname: Orders\n");
        write(config.resolve("orders/orders_daily_enrich.toon"), "name: ORDERS_DAILY\ntriggers:\n  on_pipeline: orders\n");
        write(config.resolve("jobs/nightly_job.toon"), "job:\n  name: nightly\n  type: noop\n  on_pipeline: orders\n");
        write(config.resolve("roles.toon"), "roles[0]:\n");
        write(config.resolve("orders/.history/orders_pipeline.v1.toon"), "id: orders\n");
        write(config.resolve("pending-changes/x_job.toon"), "job:\n  name: held\n");
        return config;
    }

    private static void write(Path p, String text) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    private static JsonNode lock(Path config) throws IOException {
        return JSON.readTree(Files.readAllBytes(SpaceLock.file(config)));
    }

    private static JsonNode fileEntry(JsonNode lock, String path) {
        for (JsonNode f : lock.get("files")) if (f.get("path").asText().equals(path)) return f;
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(SpaceLock.Report r, String list) {
        return (List<Map<String, Object>>) ((Map<String, Object>) r.body().get(list)).get("items");
    }

    @Test
    void writesASignedLockOfEveryLiveConfigBesideConfig(@TempDir Path tmp) throws Exception {
        Path config = space(tmp);
        SpaceLock.Report r = SpaceLock.compute("alpha", config, List.of());
        assertEquals("ready", r.status());
        assertEquals(tmp.resolve("alpha/.inspecto/space.lock").toAbsolutePath().normalize(), SpaceLock.file(config));
        JsonNode lock = lock(config);
        assertEquals(1, lock.get("format").asInt());
        assertEquals("alpha", lock.get("space").asText());
        List<String> paths = new ArrayList<>();
        for (JsonNode f : lock.get("files")) paths.add(f.get("path").asText());
        assertEquals(List.of("jobs/nightly_job.toon", "orders/orders_daily_enrich.toon", "orders/orders_pipeline.toon",
                "roles.toon"), paths, "the live set: no .history copy, no server-written state");

        JsonNode p = fileEntry(lock, "orders/orders_pipeline.toon");
        assertEquals("pipeline", p.get("kind").asText());
        assertEquals("orders", p.get("id").asText());
        assertFalse(p.get("uid").asText().isBlank());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(config.resolve("orders/orders_pipeline.toon")))), p.get("hash").asText());
        assertEquals("nightly", fileEntry(lock, "jobs/nightly_job.toon").get("id").asText());
        assertEquals("job", fileEntry(lock, "jobs/nightly_job.toon").get("kind").asText());
        assertEquals("ORDERS_DAILY", fileEntry(lock, "orders/orders_daily_enrich.toon").get("id").asText());
        assertEquals("setting", fileEntry(lock, "roles.toon").get("kind").asText());

        boolean jobLink = false;
        for (JsonNode l : lock.get("links"))
            if (l.get("fromKind").asText().equals("job") && l.get("key").asText().equals("on_pipeline")) {
                jobLink = true;
                assertEquals("RESOLVED", l.get("status").asText());
                assertEquals("jobs/nightly_job.toon", l.get("from").asText());
            }
        assertTrue(jobLink, "the census links are recorded: " + lock.get("links"));

        String[] note = {null};
        assertNotNull(SpaceLock.readVerified(config.toAbsolutePath().normalize(), SpaceLock.file(config), note),
                "the lock verifies with the Space key: " + note[0]);
        assertTrue(Files.isRegularFile(tmp.resolve("alpha/config.secrets/" + SpaceLock.KEY_FILE)),
                "signed with the Space's Pending Change key");
    }

    @Test
    void computingWritesNothingUnderConfig(@TempDir Path tmp) throws Exception {
        Path config = space(tmp);
        List<String> before = tree(config);
        SpaceLock.compute("alpha", config, List.of());
        assertEquals(before, tree(config));
    }

    @Test
    void brokenLinksAndDuplicatesAreReported(@TempDir Path tmp) throws Exception {
        Path config = space(tmp);
        write(config.resolve("jobs/orphan_job.toon"), "job:\n  name: orphan\n  type: noop\n  on_pipeline: gone\n");
        write(config.resolve("jobs/again_job.toon"), "job:\n  name: nightly\n  type: noop\n");
        write(config.resolve("orders/lost_enrich.toon"), "name: LOST\ntriggers:\n  on_pipeline: nowhere\n");
        SpaceLock.Report r = SpaceLock.compute("alpha", config, List.of());

        List<String> broken = new ArrayList<>();
        for (Map<String, Object> b : items(r, "broken")) broken.add(b.get("fromKind") + ":" + b.get("key") + "=" + b.get("value"));
        assertTrue(broken.contains("job:on_pipeline=gone"), broken.toString());
        assertTrue(broken.contains("enrichment:triggers.on_pipeline=nowhere"), broken.toString());
        assertEquals(2, broken.size(), broken.toString());

        List<Map<String, Object>> dups = items(r, "duplicates");
        assertEquals(1, dups.size(), dups.toString());
        assertEquals("job:nightly", dups.get(0).get("claim"));
        assertEquals(List.of("jobs/again_job.toon", "jobs/nightly_job.toon"), dups.get(0).get("paths"));
    }

    @Test
    void aFileKeepsItsUidByPathAndWhenMovedUnchanged(@TempDir Path tmp) throws Exception {
        Path config = space(tmp);
        SpaceLock.compute("alpha", config, List.of());
        JsonNode first = lock(config);
        String pipelineUid = fileEntry(first, "orders/orders_pipeline.toon").get("uid").asText();
        String jobUid = fileEntry(first, "jobs/nightly_job.toon").get("uid").asText();

        write(config.resolve("orders/orders_pipeline.toon"), "id: orders\nname: Orders renamed\n");   // edited in place
        Files.createDirectories(config.resolve("ops"));
        Files.move(config.resolve("jobs/nightly_job.toon"), config.resolve("ops/nightly_job.toon"));   // moved, unchanged
        SpaceLock.compute("alpha", config, List.of());
        JsonNode second = lock(config);
        assertEquals(pipelineUid, fileEntry(second, "orders/orders_pipeline.toon").get("uid").asText(), "same path");
        assertEquals(jobUid, fileEntry(second, "ops/nightly_job.toon").get("uid").asText(), "same content hash");
    }

    @Test
    void editsOutsideTheServerAreReportedAndServerWritesAreNot(@TempDir Path tmp) throws Exception {
        Path config = space(tmp);
        SpaceLock.Report base = SpaceLock.compute("alpha", config, List.of());
        assertEquals("none", base.body().get("baseline"));
        assertEquals(0, items(base, "changedOutside").size(), "no baseline: nothing to compare");

        // by hand, while no server write was in progress
        write(config.resolve("orders/orders_pipeline.toon"), "id: orders\nname: Hand edited\n");
        write(config.resolve("jobs/new_job.toon"), "job:\n  name: fresh\n  type: noop\n");
        Files.delete(config.resolve("roles.toon"));
        SpaceLock.Report r = SpaceLock.compute("alpha", config, List.of());
        assertEquals("lock", r.body().get("baseline"));
        List<String> outside = new ArrayList<>();
        for (Map<String, Object> o : items(r, "changedOutside")) outside.add(o.get("change") + " " + o.get("path"));
        assertEquals(List.of("added jobs/new_job.toon", "modified orders/orders_pipeline.toon", "removed roles.toon"),
                outside);

        // the mark stays while the file is unchanged
        SpaceLock.Report again = SpaceLock.compute("alpha", config, List.of());
        assertEquals(3, items(again, "changedOutside").size(), items(again, "changedOutside").toString());

        // a server write: the file's modification time falls inside the request's window
        SpaceLock.Window w = new SpaceLock.Window();
        write(config.resolve("orders/orders_pipeline.toon"), "id: orders\nname: Saved by the server\n");
        w.end = System.currentTimeMillis();
        SpaceLock.Report saved = SpaceLock.compute("alpha", config, List.of(w));
        List<String> left = new ArrayList<>();
        for (Map<String, Object> o : items(saved, "changedOutside")) left.add(o.get("path").toString());
        assertFalse(left.contains("orders/orders_pipeline.toon"), "a server write clears the mark: " + left);

        // a hand edit whose time is outside the window is still outside, even with a window recorded
        write(config.resolve("jobs/nightly_job.toon"), "job:\n  name: nightly\n  type: noop\n  on_pipeline: orders\n  x: 1\n");
        Files.setLastModifiedTime(config.resolve("jobs/nightly_job.toon"),
                FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));
        SpaceLock.Window later = new SpaceLock.Window();
        later.end = System.currentTimeMillis();
        SpaceLock.Report mixed = SpaceLock.compute("alpha", config, List.of(later));
        boolean found = false;
        for (Map<String, Object> o : items(mixed, "changedOutside"))
            if (o.get("path").equals("jobs/nightly_job.toon")) found = "modified".equals(o.get("change"));
        assertTrue(found, items(mixed, "changedOutside").toString());
    }

    @Test
    void aTamperedOrUnreadableLockIsRecomputedNeverAnError(@TempDir Path tmp) throws Exception {
        Path config = space(tmp);
        SpaceLock.compute("alpha", config, List.of());
        Path lock = SpaceLock.file(config);

        Files.writeString(lock, Files.readString(lock).replace("\"roles.toon\"", "\"forged.toon\""));
        SpaceLock.Report r = SpaceLock.compute("alpha", config, List.of());
        assertEquals("ready", r.status());
        assertEquals("none", r.body().get("baseline"));
        assertEquals("the lock's signature does not verify", r.body().get("baselineNote"));
        String[] note = {null};
        assertNotNull(SpaceLock.readVerified(config.toAbsolutePath().normalize(), lock, note), "rewritten and signed");

        Files.writeString(lock, "{not json");
        SpaceLock.Report garbage = SpaceLock.compute("alpha", config, List.of());
        assertEquals("ready", garbage.status());
        assertEquals("the lock could not be read", garbage.body().get("baselineNote"));

        Files.delete(lock);
        assertEquals("no lock yet", SpaceLock.compute("alpha", config, List.of()).body().get("baselineNote"));
    }

    @Test
    void anUnchangedSpaceDoesNotRewriteTheLock(@TempDir Path tmp) throws Exception {
        Path config = space(tmp);
        SpaceLock.Report first = SpaceLock.compute("alpha", config, List.of());
        byte[] bytes = Files.readAllBytes(SpaceLock.file(config));
        Thread.sleep(5);
        SpaceLock.Report second = SpaceLock.compute("alpha", config, List.of());
        assertArrayEquals(bytes, Files.readAllBytes(SpaceLock.file(config)));
        assertEquals(first.body().get("lockWrittenAt"), second.body().get("lockWrittenAt"));
    }

    @Test
    void theKillSwitchWritesNothingAndReportsDisabled(@TempDir Path tmp) throws Exception {
        Path config = space(tmp);
        System.setProperty(SpaceLock.PROPERTY, "off");
        assertFalse(SpaceLock.enabled());
        SpaceLock.afterBoot("alpha", config);
        assertNull(SpaceLock.writeStarted("alpha", config));
        assertEquals("disabled", SpaceLock.report("alpha", config).status());
        Thread.sleep(SpaceLock.DEBOUNCE_MS + 500);
        assertFalse(Files.exists(SpaceLock.file(config)), "no lock is written while off");
        SpaceLock.forget(config);
    }

    @Test
    void afterBootComputesInTheBackgroundAndAWriteRecomputes(@TempDir Path tmp) throws Exception {
        Path config = space(tmp);
        try {
            SpaceLock.afterBoot("alpha", config);
            assertEquals("computing", SpaceLock.report("alpha", config).status(), "the boot path only schedules");
            SpaceLock.Report r = await(config, rep -> true);
            assertEquals(4, r.body().get("files"));
            assertTrue(Files.isRegularFile(SpaceLock.file(config)));

            SpaceLock.Window w = SpaceLock.writeStarted("alpha", config);
            write(config.resolve("jobs/server_job.toon"), "job:\n  name: server\n  type: noop\n");
            SpaceLock.writeEnded(config, w, true);
            SpaceLock.Report after = await(config, rep -> Integer.valueOf(5).equals(rep.body().get("files")));
            for (Map<String, Object> o : items(after, "changedOutside"))
                assertNotEquals("jobs/server_job.toon", o.get("path"), "written inside a server window");
        } finally {
            SpaceLock.forget(config);
        }
    }

    /** A server write the debounce has not yet recorded is flushed when the Space closes — dropped, the next boot
     *  would report the file as changed outside the server (verification finding 1). */
    @Test
    void closingFlushesAServerWriteNotYetInALock(@TempDir Path tmp) throws Exception {
        Path config = space(tmp);
        SpaceLock.compute("alpha", config, List.of());
        SpaceLock.Window w = SpaceLock.writeStarted("alpha", config);
        write(config.resolve("jobs/nightly_job.toon"), "job:\n  name: nightly\n  type: noop\n  x: 2\n");
        SpaceLock.writeEnded(config, w, true);
        SpaceLock.forget(config);   // inside the debounce: the pending run is cancelled
        SpaceLock.Report next = SpaceLock.compute("alpha", config, List.of());   // the next boot
        assertEquals(0, ((Map<?, ?>) next.body().get("changedOutside")).get("total"), next.body().toString());
    }

    /** SPACE-LOCK-CLOSE-RESIDUALS-1 (2): a write window opened after a Space was closed and deleted (a request
     *  already bound to it) makes no fresh state, and nothing recreates its folders. */
    @Test
    void aDeletedSpaceIsNeverRecreated(@TempDir Path tmp) throws Exception {
        Path config = space(tmp);
        SpaceLock.compute("alpha", config, List.of());
        SpaceLock.forget(config);
        deleteTree(tmp.resolve("alpha"));

        SpaceLock.Window w = SpaceLock.writeStarted("alpha", config);
        SpaceLock.writeEnded(config, w, true);
        assertEquals("closed", SpaceLock.report("alpha", config).status());
        Thread.sleep(SpaceLock.DEBOUNCE_MS + 1000);   // past the debounce a scheduled computation would have run
        assertFalse(Files.exists(tmp.resolve("alpha")), "a forgotten Space's folder was recreated");

        // a computation that reaches a deleted config root (already running when the Space went) writes nothing
        SpaceLock.compute("alpha", config, List.of());
        assertFalse(Files.exists(tmp.resolve("alpha")), "compute recreated a deleted Space's folders");
    }

    /** (2) A computation still running when the Space closes writes no lock and creates no folder. */
    @Test
    void aComputationThatSeesTheSpaceClosedWritesNothing(@TempDir Path tmp) throws Exception {
        Path config = space(tmp);
        SpaceLock.Report r = SpaceLock.compute("alpha", config, List.of(), () -> true);
        assertEquals("closed", r.status());
        assertFalse(Files.exists(tmp.resolve("alpha/" + SpaceLock.DIR)), "a closed Space got a lock folder");
        assertFalse(Files.exists(tmp.resolve("alpha/config.secrets")), "a closed Space got a key folder");
    }

    /** (1) and (3): a running computation holds closing for at most the flush budget — not the Space's whole close
     *  deadline — and the skipped flush is logged. */
    @Test
    void closingWaitsAtMostTheFlushBudgetAndLogsTheSkippedFlush(@TempDir Path tmp) throws Exception {
        Path config = space(tmp);
        SpaceLock.Window w = SpaceLock.writeStarted("alpha", config);
        SpaceLock.writeEnded(config, w, true);   // an unrecorded server write: closing would flush it
        java.util.concurrent.locks.ReentrantLock computing = SpaceLock.computingForTest(config);
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        Thread holder = new Thread(() -> {
            computing.lock();
            held.countDown();
            try {
                done.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                computing.unlock();
            }
        });
        holder.start();
        held.await();
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
                new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SpaceLock.class);
        logger.addAppender(logs);
        try {
            long t0 = System.currentTimeMillis();
            SpaceLock.forget(config);
            long took = System.currentTimeMillis() - t0;
            assertTrue(took < SpaceLock.FLUSH_BUDGET_MS + 1500, "closing waited " + took + " ms");
            assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("close-time flush was skipped")),
                    "the skipped flush was not logged");
        } finally {
            logger.detachAppender(logs);
            done.countDown();
            holder.join();
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    /** Writes closer together than the debounce still get a computation within the maximum wait (finding 3). */
    @Test
    void steadyWritesStillGetAComputation(@TempDir Path tmp) throws Exception {
        Path config = space(tmp);
        try {
            long start = System.currentTimeMillis();
            while (!Files.exists(SpaceLock.file(config))
                    && System.currentTimeMillis() - start < SpaceLock.MAX_WAIT_MS + 5_000) {
                SpaceLock.writeEnded(config, SpaceLock.writeStarted("alpha", config), true);
                Thread.sleep(SpaceLock.DEBOUNCE_MS / 3);
            }
            assertTrue(Files.exists(SpaceLock.file(config)), "no computation under steady writes");
        } finally {
            SpaceLock.forget(config);
        }
    }

    private static SpaceLock.Report await(Path config, java.util.function.Predicate<SpaceLock.Report> ok) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            SpaceLock.Report r = SpaceLock.report("alpha", config);
            if ("ready".equals(r.status()) && !Boolean.TRUE.equals(r.body().get("recomputing")) && ok.test(r)) return r;
            Thread.sleep(100);
        }
        throw new AssertionError("the lock was not computed in time: " + SpaceLock.report("alpha", config).body());
    }

    /**
     * Every shipped Space and Space Template: the lock computes, writes nothing under {@code config/}, and its
     * time is printed ({@code [SE-1a]}) — the measurement the plan asks for.
     */
    @Test
    void everyShippedSpaceComputesWithoutTouchingItsConfig(@TempDir Path tmp) throws Exception {
        for (SpaceCorpusEquivalenceTest.Entry e : SpaceCorpusEquivalenceTest.corpus()) {
            Path base = tmp.resolve(e.id());
            copy(e.dir().resolve("config"), base.resolve("config"));
            Path config = base.resolve("config");
            List<String> before = tree(config);
            long t0 = System.nanoTime();
            SpaceLock.Report cold = SpaceLock.compute(e.id(), config, List.of());
            long cold_ms = (System.nanoTime() - t0) / 1_000_000;
            t0 = System.nanoTime();
            SpaceLock.Report warm = SpaceLock.compute(e.id(), config, List.of());
            long warm_ms = (System.nanoTime() - t0) / 1_000_000;
            assertEquals("ready", cold.status(), e.label());
            assertEquals(before, tree(config), e.label() + ": the lock must not write under config/");
            assertEquals(0, ((Map<?, ?>) warm.body().get("changedOutside")).get("total"), e.label() + ": no false outside edit");
            System.out.println("[SE-1a] " + e.label() + " files=" + cold.body().get("files") + " links="
                    + cold.body().get("links") + " broken=" + ((Map<?, ?>) cold.body().get("broken")).get("total")
                    + " duplicates=" + ((Map<?, ?>) cold.body().get("duplicates")).get("total")
                    + " first=" + cold_ms + "ms again=" + warm_ms + "ms");
        }
    }

    /**
     * Boot is not slowed: the demo Space is discovered and started with the switch off and on (each on its own
     * copy), the times are printed ({@code [SE-1a] boot}), and right after {@code startAll} no lock exists yet —
     * the boot path only schedules.
     */
    @Test
    void bootOnlySchedulesTheLock(@TempDir Path tmp) throws Exception {
        Path demo = Path.of("..", "spaces", "demo").toAbsolutePath().normalize();
        long[] ms = new long[4];   // off, on, off, on: the second pair is past JIT warm-up
        for (int i = 0; i < 4; i++) {
            boolean on = i % 2 == 1;
            if (!on) System.setProperty(SpaceLock.PROPERTY, "off");
            else System.clearProperty(SpaceLock.PROPERTY);
            Path root = tmp.resolve("boot" + i);
            copy(demo.resolve("config"), root.resolve("demo/config"));
            Files.copy(demo.resolve("space.toon"), root.resolve("demo/space.toon"));
            // the lock's thread is held through startAll, so "no lock yet" proves boot only scheduled, at any boot speed
            Runnable release = SpaceLock.holdThreadForTest();
            long t0 = System.nanoTime();
            SpaceManager spaces = SpaceManager.discover(root);
            try {
                try {
                    spaces.startAll();
                    ms[i] = (System.nanoTime() - t0) / 1_000_000;
                    assertFalse(Files.exists(root.resolve("demo/.inspecto/space.lock")), "boot must not compute the lock");
                } finally {
                    release.run();
                }
                if (on) {
                    long deadline = System.currentTimeMillis() + 20_000;
                    while (!Files.exists(root.resolve("demo/.inspecto/space.lock")) && System.currentTimeMillis() < deadline)
                        Thread.sleep(100);
                    assertTrue(Files.exists(root.resolve("demo/.inspecto/space.lock")), "computed after boot, in the background");
                }
            } finally {
                spaces.close();
                com.gamma.config.safety.DiscoveredRoots.clear();
            }
        }
        System.out.println("[SE-1a] boot demo (off, on, off, on): " + java.util.Arrays.toString(ms) + " ms");
    }

    private static void copy(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            Files.createDirectories(to);
            return;
        }
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t);
            }
        }
    }

    private static List<String> tree(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.map(p -> {
                try {
                    return root.relativize(p) + "|" + (Files.isRegularFile(p)
                            ? HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))) : "d");
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            }).sorted().toList();
        }
    }
}
