package com.gamma.job;

import com.gamma.etl.ConsignmentEventBus;
import com.gamma.event.EventLog;
import com.gamma.query.DatasetAccess;
import com.gamma.util.JdbcDrivers;
import com.gamma.util.Scheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Platform Services Stage 2: a Job Run's {@code datasets} pins (com.gamma.query.RunSnapshot) are released when the Run
 * ends — on success, on failure and on a cancel ({@link JobService#interruptRuns}).
 */
class RunSnapshotReleaseTest {

    private static JobTypeProvider type(String id, Function<JobContext, JobResult> body) {
        return JobTypeProvider.of(new JobTypeDescriptor(id, id, "test", List.of(), List.of(), List.of(), List.of("datasets")),
                cfg -> new Job() {
                    @Override public String name() { return cfg.name(); }
                    @Override public String type() { return id; }
                    @Override public JobResult run() { throw new UnsupportedOperationException(); }
                    @Override public JobResult run(JobContext ctx) { return body.apply(ctx); }
                });
    }

    private static long pinned(Path data) {
        Path base = data.resolve(".run-snapshots");
        if (!Files.isDirectory(base)) return 0;
        try (Stream<Path> s = Files.list(base)) {
            return s.count();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Runs a Job of {@code body} that first reads Dataset {@code cdr}; {@code pinnedInRun} is how many Run directories it saw. */
    private static JobRun run(Path dir, Function<JobContext, JobResult> body, long[] pinnedInRun,
                              java.util.function.Consumer<JobService> whileRunning, CountDownLatch reading) throws Exception {
        Path data = seed(dir);
        PlatformServiceRegistry platform = new PlatformServiceRegistry();
        platform.register("datasets", DatasetAccess.class, DatasetAccess.over(EventLog.DEFAULT_SPACE_ID,
                id -> Optional.ofNullable("cdr".equals(id) ? Map.<String, Object>of("physicalRef", "cdr") : null),
                () -> data, () -> null));
        // the built-in Job Types that declare these grants must validate at registration; this test uses none of them
        for (Map.Entry<String, String> e : Map.of("mail", "com.gamma.notify.MailAccess",
                "notifications", "com.gamma.notify.NotificationAccess", "incidents", "com.gamma.objects.IncidentAccess",
                "objects", "com.gamma.objects.ObjectAccess", "schema", "com.gamma.pipeline.SchemaAccess",
                "link-index", "com.gamma.linkindex.LinkIndexAccess", "alerts", "com.gamma.alert.AlertAccess").entrySet())
            unused(platform, e.getKey(), e.getValue());
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(), new ConsignmentEventBus(), s, null, dir.resolve("audit").toString(),
                     null, null, data.toString(), null, platform)) {
            js.registerJobType(type("t.reads", ctx -> {
                ctx.services().get(DatasetAccess.class).read(ctx.spaceId(), "cdr");
                pinnedInRun[0] = pinned(data);
                reading.countDown();
                return body.apply(ctx);
            }));
            js.upsertJob(new JobConfig("reads", "t.reads", null, null, true, false, Map.of(), null, null));
            js.triggerRun("reads", "test").orElseThrow();
            whileRunning.accept(js);
            long until = System.nanoTime() + 20_000_000_000L;
            while (System.nanoTime() < until) {
                JobRun r = js.lastRunOf("reads").orElse(null);
                if (r != null && !"RUNNING".equals(r.status())) {
                    assertEquals(0, pinned(data), "the Run's pins are released once it has ended (" + r.status() + ")");
                    return r;
                }
                Thread.sleep(20);
            }
            fail("the Run never ended");
            return null;
        }
    }

    /** A data root holding one Parquet file in store {@code cdr}. */
    private static Path seed(Path dir) throws Exception {
        Path data = dir.resolve("data");
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:"); Statement st = conn.createStatement()) {
            Files.createDirectories(data.resolve("cdr"));
            st.execute("COPY (SELECT 1 AS id) TO '" + data.resolve("cdr/a.parquet").toString().replace("\\", "/")
                    + "' (FORMAT PARQUET)");
        }
        return data;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void unused(PlatformServiceRegistry platform, String id, String iface) {
        Class type;
        try {
            type = Class.forName(iface);
        } catch (ClassNotFoundException e) {
            return;
        }
        platform.register(id, type, java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type},
                (p, m, a) -> {
                    if (m.getDeclaringClass() == Object.class) return m.invoke(id, a);
                    throw new UnsupportedOperationException(id + " is not wired in this test");
                }));
    }

    @Test
    void aSuccessfulRunReleasesItsPins(@TempDir Path dir) throws Exception {
        long[] seen = {-1};
        JobRun r = run(dir, ctx -> JobResult.ok("read", 0L), seen, js -> { }, new CountDownLatch(1));
        assertEquals("SUCCESS", r.status(), r.message());
        assertEquals(1, seen[0], "precondition: the Run pinned its Dataset");
    }

    @Test
    void aFailedRunReleasesItsPins(@TempDir Path dir) throws Exception {
        long[] seen = {-1};
        JobRun r = run(dir, ctx -> { throw new IllegalStateException("boom"); }, seen, js -> { }, new CountDownLatch(1));
        assertEquals("FAILED", r.status(), r.message());
        assertEquals(1, seen[0], "precondition: the Run pinned its Dataset");
    }

    @Test
    void aCancelledRunReleasesItsPins(@TempDir Path dir) throws Exception {
        long[] seen = {-1};
        CountDownLatch reading = new CountDownLatch(1);
        JobRun r = run(dir, ctx -> {
            try {
                Thread.sleep(60_000);   // ignores every cooperative cancel; only the interrupt stops it
                return JobResult.ok("never", 0L);
            } catch (InterruptedException e) {
                throw new IllegalStateException("interrupted", e);
            }
        }, seen, js -> {
            try {
                assertTrue(reading.await(10, TimeUnit.SECONDS), "precondition: the Run is reading");
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            js.interruptRuns();
        }, reading);
        assertEquals("CANCELLED", r.status(), r.message());
        assertEquals(1, seen[0], "precondition: the Run pinned its Dataset");
    }

    @Test
    void aPackTestHarnessRunReleasesItsPinsOnSuccessAndFailure(@TempDir Path dir) throws Exception {
        Path data = seed(dir);
        long[] seen = {-1, -1};
        DatasetAccess datasets = DatasetAccess.over("default",
                id -> Optional.ofNullable("cdr".equals(id) ? Map.<String, Object>of("physicalRef", "cdr") : null),
                () -> data, () -> null);
        PackTestHarness h = PackTestHarness.create().service("datasets", DatasetAccess.class, datasets).load(
                type("t.ok", ctx -> {
                    ctx.services().get(DatasetAccess.class).read(ctx.spaceId(), "cdr");
                    seen[0] = pinned(data);
                    return JobResult.ok("read", 0L);
                }),
                type("t.fails", ctx -> {
                    ctx.services().get(DatasetAccess.class).read(ctx.spaceId(), "cdr");
                    seen[1] = pinned(data);
                    throw new IllegalStateException("boom");
                }));

        assertEquals("SUCCESS", h.run("t.ok", Map.of()).status());
        assertEquals(1, seen[0], "precondition: the Run pinned its Dataset");
        assertEquals(0, pinned(data), "released when the harness Run returned");

        assertEquals("FAILED", h.run("t.fails", Map.of()).status());
        assertEquals(1, seen[1], "precondition: the Run pinned its Dataset");
        assertEquals(0, pinned(data), "released when the harness Run failed");
    }
}
