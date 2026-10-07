package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.audit.EventLog;
import com.gamma.geolink.ScheduledLinkIndexBuilder;
import com.gamma.la.core.DatasetProviders;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.linkindex.LinkIndexAccess;
import com.gamma.linkindex.LinkIndexAccess.Outcome;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-DAILY-INGEST-1, T5 - "land a day, the index appends": the {@code link-index} bridge (the code {@code la.index.build}
 * calls) against a REAL control plane, the real {@code IndexBuilder} and real Parquet files, one day per file. Proves the
 * mode comes from the server's own {@code plan} (append, compact then append, never a forced full), a refusal is recorded
 * and not retried (except an in-flight build, which is waited on with a bounded backoff), and the delegated principal's authority is re-decided on every run.
 */
class ScheduledIndexBuildTest {

    private static final long DAY = 86_400_000L, T0 = 1_700_000_000_000L;

    @AfterEach
    void reset() {
        DatasetProviders.forTest(null);
        com.gamma.la.api.IndexRoutes.forTest(null);
        com.gamma.la.api.ScheduledIndexBuild.backoffForTest(0);
    }

    private record Ctx(CollectorService svc, ControlApi api, Path root, String store, Path dir) implements AutoCloseable {
        public void close() throws Exception {
            api.close();
            svc.close();
            if (Files.isDirectory(dir)) try (var w = Files.walk(dir)) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
        }
    }

    private Ctx open(Path cfg, Path root, Map<String, Object> dataset) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            String store = "sched_idx_" + System.nanoTime();
            Map<String, Object> content = new java.util.LinkedHashMap<>(dataset);
            content.put("physicalRef", store);
            new ComponentStore(root.resolve("registry")).write("dataset", "xdr_daily", content);
            return new Ctx(svc, api, root, store, Path.of("database").resolve(store));
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    /** One day lands: a 3-edge Parquet file with a pinned mtime (the clock never decides). */
    private static void land(Path file, int day) throws Exception {
        Files.createDirectories(file.getParent());
        DuckDbUtil.loadDriver();
        java.io.File db = DuckDbUtil.tempDbFile("sched_idx_");
        try (java.sql.Connection conn = DuckDbUtil.openConnection(db); java.sql.Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES ('a" + day + "','bob','call'),('bob','c" + day + "','sms'),('a" + day
                    + "','c" + day + "','call')) t(who,other,kind)) TO '" + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(T0 + day * DAY));
    }

    private static LinkIndexAccess.Request request(boolean allowFull, String owner) {
        return new LinkIndexAccess.Request("xdr_index", "xdr_daily", "who", "other", "kind", null, null, null, List.of(),
                owner, allowFull, 60_000L);
    }

    private Outcome run(Ctx c, boolean allowFull, String owner) {
        return new ScheduledLinkIndexBuilder().build(c.root, c.api.dataRoot(), request(allowFull, owner));
    }

    @Test
    void landingADayAppendsAndTheModeComesFromTheServersPlan(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, Map.of("owner", "analyst-1", "shares", List.of()))) {
            land(c.dir.resolve("day0.parquet"), 0);

            Outcome refused = run(c, false, "analyst-1");                       // no index yet: a first FULL build is never started unasked
            assertEquals("REFUSED", refused.result());
            assertEquals("FULL_NOT_ALLOWED", refused.code());

            Outcome first = run(c, true, "analyst-1");
            assertEquals("BUILT", first.result(), first.message());
            assertEquals("full", first.mode());
            assertEquals(3L, first.edges());

            assertEquals("UP_TO_DATE", run(c, false, "analyst-1").result(), "nothing landed: nothing to do");

            land(c.dir.resolve("day1.parquet"), 1);                              // the next day lands
            Outcome day1 = run(c, false, "analyst-1");                           // allowFull NOT needed for an append
            assertEquals("BUILT", day1.result(), day1.message());
            assertEquals("append", day1.mode());
            assertEquals(1, day1.deltas());
            assertEquals("UP_TO_DATE", run(c, false, "analyst-1").result());
        }
    }

    @Test
    void aRewrittenInputIsAFullBuildThatIsRefusedUnlessAllowedAndNothingIsForced(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, Map.of("owner", "analyst-1", "shares", List.of()))) {
            Path day0 = c.dir.resolve("day0.parquet");
            land(day0, 0);
            assertEquals("BUILT", run(c, true, "analyst-1").result());

            Files.setLastModifiedTime(day0, java.nio.file.attribute.FileTime.fromMillis(T0 + 99 * DAY));   // a re-delivered day
            Outcome o = run(c, false, "analyst-1");
            assertEquals("REFUSED", o.result());
            assertEquals("FULL_NOT_ALLOWED", o.code());
            assertEquals("full", o.mode());
            assertEquals("BUILT", run(c, true, "analyst-1").result(), "the operator allowed it, so the server's full runs");
        }
    }

    @Test
    void deltasNearTheCapAreCompactedThenTheLandedDayIsAppendedInTheSameRun(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, Map.of("owner", "analyst-1", "shares", List.of()))) {
            land(c.dir.resolve("day0.parquet"), 0);
            assertEquals("BUILT", run(c, true, "analyst-1").result());
            for (int d = 1; d <= 8; d++) {                                       // eight daily appends fill the delta cap
                land(c.dir.resolve("day" + d + ".parquet"), d);
                Outcome o = run(c, false, "analyst-1");
                assertEquals("BUILT", o.result(), "day " + d + ": " + o.message());
                assertEquals("append", o.mode());
                assertEquals(d, o.deltas());
            }
            land(c.dir.resolve("day9.parquet"), 9);                              // a ninth delta would be a 409
            Outcome o = run(c, false, "analyst-1");
            assertEquals("BUILT", o.result(), o.message());
            assertEquals("append", o.mode(), "the run ends on the append that ingests the new day");
            assertEquals(1, o.deltas(), "compacted first, so only the new day is a delta");
            assertEquals("UP_TO_DATE", run(c, false, "analyst-1").result());
        }
    }

    @Test
    void theOwnersAuthorityIsRedecidedEveryRunAndEveryRefusalIsAudited(@TempDir Path cfg, @TempDir Path root) throws Exception {
        List<com.gamma.audit.Event> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.function.Consumer<com.gamma.audit.Event> sub = seen::add;
        EventLog.current().addSubscriber(sub);
        try (Ctx c = open(cfg, root, Map.of("owner", "analyst-1", "shares", List.of(
                Map.of("subjectType", "role", "subjectId", "analysts", "access", "view"))))) {
            land(c.dir.resolve("day0.parquet"), 0);
            assertEquals("NO_OWNER", run(c, true, " ").code());
            assertEquals("ROLE_SHARE_ONLY", run(c, true, "analyst-9").code(), "a role share cannot be re-resolved off a request");
            assertEquals("BUILT", run(c, true, "analyst-1").result(), "the recorded owner may");

            new ComponentStore(root.resolve("registry")).write("dataset", "xdr_daily",
                    Map.of("owner", "analyst-2", "physicalRef", c.store, "shares", List.of()));   // ownership moved away
            Outcome lost = run(c, true, "analyst-1");
            assertEquals("REFUSED", lost.result());
            assertEquals("DATASET_NOT_SHARED", lost.code());
            assertFalse(lost.message().contains("who"), "a refusal names no column");

            new ComponentStore(root.resolve("registry")).delete("dataset", "xdr_daily");
            assertEquals("DATASET_GONE", run(c, true, "analyst-1").code());

            var audited = seen.stream()
                    .filter(e -> LinkEventTypes.LINK_INDEX_SCHEDULED_RUN.equals(e.type())).toList();
            assertTrue(audited.size() >= 5, "every attempt, built or refused, is audited: " + audited.size());
            assertTrue(audited.stream().allMatch(e -> "index-build:xdr_index".equals(String.valueOf(e.attributes().get(com.gamma.audit.AuditAttrs.ACTOR)))));
        } finally {
            EventLog.current().removeSubscriber(sub);
        }
    }

    @Test
    void aSecondRunWhileABuildIsLiveWaitsBoundedThenIsRefusedBuildInProgressAndASlowBuildFailsAsRunningAtTheTimeout(@TempDir Path cfg, @TempDir Path root) throws Exception {
        com.gamma.la.api.ScheduledIndexBuild.backoffForTest(20);                  // 20+40+...+640 ms: six waits inside the slow build
        java.util.concurrent.atomic.AtomicBoolean release = new java.util.concurrent.atomic.AtomicBoolean();
        com.gamma.la.api.IndexRoutes.forTest(req -> {
            try {
                while (!release.get()) {
                    if (req.options().cancel().isCancelled()) throw new com.gamma.la.storage.IndexBuilder.CancelledException();
                    Thread.sleep(5);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return new com.gamma.la.storage.IndexBuilder.Result(1, req.store().directory(), null, 3, 3, 0, 3, 16, Map.of(), 5);
        });
        try (Ctx c = open(cfg, root, Map.of("owner", "analyst-1", "shares", List.of()))) {
            land(c.dir.resolve("day0.parquet"), 0);
            var slow = new java.util.concurrent.atomic.AtomicReference<Outcome>();
            Thread t = new Thread(() -> slow.set(new ScheduledLinkIndexBuilder().build(c.root, c.api.dataRoot(),
                    new LinkIndexAccess.Request("xdr_index", "xdr_daily", "who", "other", "kind", null, null, null, List.of(),
                            "analyst-1", true, 1_500L))));
            t.start();
            Thread.sleep(500);                                                    // the first build is live (blocked in the builder)
            Outcome dup = run(c, true, "analyst-1");
            assertEquals("REFUSED", dup.result());
            assertEquals("BUILD_IN_PROGRESS", dup.code());
            assertTrue(dup.message().contains("after 6 waits"), dup.message());
            t.join(20_000);
            assertEquals("RUNNING", slow.get().result(), "still running when the wait ended: the Job fails the Run on this");
            assertFalse(slow.get().ok());
            release.set(true);
        } finally {
            release.set(true);
        }
    }

    /** Operator 2026-10-06: a run that meets an in-flight build WAITS (audited) and then builds, so no trigger needs pausing. */
    @Test
    void aRunThatMeetsALiveBuildWaitsAuditedAndBuildsOnceTheLiveBuildEnds(@TempDir Path cfg, @TempDir Path root) throws Exception {
        com.gamma.la.api.ScheduledIndexBuild.backoffForTest(100);
        java.util.concurrent.atomic.AtomicBoolean release = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger builds = new java.util.concurrent.atomic.AtomicInteger();
        com.gamma.la.api.IndexRoutes.forTest(req -> {
            builds.incrementAndGet();
            try {
                while (!release.get()) {
                    if (req.options().cancel().isCancelled()) throw new com.gamma.la.storage.IndexBuilder.CancelledException();
                    Thread.sleep(5);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return new com.gamma.la.storage.IndexBuilder.Result(1, req.store().directory(), null, 3, 3, 0, 3, 16, Map.of(), 5);
        });
        java.util.List<com.gamma.audit.Event> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.function.Consumer<com.gamma.audit.Event> sub = seen::add;
        EventLog.current().addSubscriber(sub);
        try (Ctx c = open(cfg, root, Map.of("owner", "analyst-1", "shares", List.of()))) {
            land(c.dir.resolve("day0.parquet"), 0);
            Thread first = new Thread(() -> run(c, true, "analyst-1"));
            first.start();
            for (int i = 0; i < 2_000 && builds.get() == 0; i++) Thread.sleep(5);   // the first build is live
            assertEquals(1, builds.get());
            Thread opener = new Thread(() -> {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                release.set(true);
            });
            opener.start();
            Outcome second = run(c, true, "analyst-1");
            first.join(20_000);
            assertEquals("BUILT", second.result(), second.message());
            assertEquals(2, builds.get(), "the waiting run built once, after the live build ended");
            var waited = seen.stream().filter(e -> LinkEventTypes.LINK_INDEX_SCHEDULED_RUN.equals(e.type()))
                    .filter(e -> "true".equals(String.valueOf(e.attributes().get("waiting")))).toList();
            assertFalse(waited.isEmpty(), "every wait is audited");
            assertTrue(waited.stream().allMatch(e -> "BUILD_IN_PROGRESS".equals(String.valueOf(e.attributes().get("code")))));
        } finally {
            release.set(true);
            EventLog.current().removeSubscriber(sub);
        }
    }

    @Test
    void anUnknownColumnIsAMappingRefusalAndNothingIsBuilt(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, Map.of("owner", "analyst-1", "shares", List.of()))) {
            land(c.dir.resolve("day0.parquet"), 0);
            Outcome o = new ScheduledLinkIndexBuilder().build(c.root, c.api.dataRoot(), new LinkIndexAccess.Request(
                    "xdr_index", "xdr_daily", "who", "nope", null, null, null, null, List.of(), "analyst-1", true, 60_000L));
            assertEquals("MAPPING_INVALID", o.code());
        }
    }
}
