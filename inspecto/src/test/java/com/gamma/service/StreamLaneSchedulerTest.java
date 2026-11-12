package com.gamma.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The continuous lane's WIRING (ASSURE-PUSH-INGEST-1): a {@code trigger: {type: stream}} remote Pipeline gets a
 * lane on the acquisition tick, the lane's drain runs the real acquire → ingest path with no timer started, and
 * pausing removes the lane. Uses the {@code faketest} connector, which cannot count its backlog ({@code -1}), so
 * the lane drains on {@code max_wait}.
 */
class StreamLaneSchedulerTest {

    private static final String CSV = "1,10,2020-01-01\n2,20,2020-01-02\n";

    private static PipelineScheduler scheduler(CollectorService svc) throws Exception {
        Field f = CollectorService.class.getDeclaredField("pipelineScheduler");
        f.setAccessible(true);
        return (PipelineScheduler) f.get(svc);
    }

    private static Path pipeline(Path dir, String trigger) throws Exception {
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, com.gamma.etl.PipelineConfigBatchTest.miniSchema());
        String toon = """
            name: LANE_ETL
            active: true
            version: 1
            dirs:
              poll: %s/inbox
              database: %s/db
              backup: %s/backup
              temp: %s/temp
              errors: %s/errors
              quarantine: %s/quarantine
              markers: %s/markers
              status_dir: %s/status
              log_dir: %s/logs
            collector:
              connector: faketest
            %s
            output:
              format: CSV
            processing:
              threads: 2
              file_pattern: "glob:**/*.csv"
              duplicate_check:
                enabled: true
                marker_extension: .processed
              schema_file: "%s"
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                skip_junk_lines: 0
                skip_tail_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(dir, dir, dir, dir, dir, dir, dir, dir, dir, trigger, schema.toString().replace("\\", "/"));
        Path p = dir.resolve("lane_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }

    @Test
    void aStreamTriggeredPipelineGetsALaneThatAcquiresAndIngestsWithoutATimer(@TempDir Path dir) throws Exception {
        Path remote = Files.createDirectories(dir.resolve("remote"));
        FakeRemoteConnectorFactory.reset(remote);
        Path cfg = pipeline(dir, "trigger:\n  type: stream\n  records: 1000\n  max_wait: 1s");
        CollectorService svc = new CollectorService(List.of(cfg), 3600, 1);
        try {
            PipelineScheduler s = scheduler(svc);
            s.reconcileStreamLanes();
            assertEquals(java.util.Set.of("lane_etl"), lower(s));

            long arrived = System.currentTimeMillis();
            Files.writeString(remote.resolve("a.csv"), CSV);
            long deadline = arrived + 15_000;
            while (svc.pipelines().get(0).committedBatches() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20);
            long committed = System.currentTimeMillis();
            assertEquals(1, svc.pipelines().get(0).committedBatches(),
                    "the lane alone (no timer is running) fetched and ingested the file");
            System.out.printf("STREAM-LANE-SCHEDULER arrival->commit=%dms (T=1000ms, faketest, real ingest)%n",
                    committed - arrived);

            svc.pause(svc.pipelines().get(0).name());
            s.reconcileStreamLanes();
            assertTrue(s.streamLaneIds().isEmpty(), "a paused Pipeline loses its lane");
        } finally {
            svc.close();
        }
    }

    @Test
    void aDeadLaneIsReplacedOnTheNextReconcile(@TempDir Path dir) throws Exception {
        FakeRemoteConnectorFactory.reset(Files.createDirectories(dir.resolve("remote")));
        Path cfg = pipeline(dir, "trigger:\n  type: stream\n  records: 1000\n  max_wait: 60s");
        CollectorService svc = new CollectorService(List.of(cfg), 3600, 1);
        try {
            PipelineScheduler s = scheduler(svc);
            s.reconcileStreamLanes();
            String id = s.streamLaneIds().iterator().next();
            com.gamma.acquire.StreamLane first = s.streamLane(id);
            FakeRemoteConnectorFactory.FAIL_CREATE.set("boom");   // the lane's open throws -> not an AcquisitionException
            long deadline = System.currentTimeMillis() + 5_000;
            while (!first.dead() && System.currentTimeMillis() < deadline) Thread.sleep(20);
            assertTrue(first.dead(), "an unexpected throw marks the lane dead");
            FakeRemoteConnectorFactory.FAIL_CREATE.set(null);
            s.reconcileStreamLanes();
            assertNotSame(first, s.streamLane(id), "reconcile replaced the dead lane");
            assertFalse(s.streamLane(id).dead());
        } finally {
            svc.close();
        }
    }

    @Test
    void aDefaultPollPipelineGetsNoLane(@TempDir Path dir) throws Exception {
        FakeRemoteConnectorFactory.reset(Files.createDirectories(dir.resolve("remote")));
        CollectorService svc = new CollectorService(List.of(pipeline(dir, "")), 3600, 1);
        try {
            scheduler(svc).reconcileStreamLanes();
            assertTrue(scheduler(svc).streamLaneIds().isEmpty());
        } finally {
            svc.close();
        }
    }

    private static java.util.Set<String> lower(PipelineScheduler s) {
        return s.streamLaneIds().stream().map(x -> x.toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * PA-9d: the clock floor is told a Pipeline is drained only when no run of it is in progress and nothing landed in
     * its inbox waits to be ingested (a landed but uncommitted slice is not the connector's backlog), at most once a second.
     */
    @Test
    void theStreamIsReportedDrainedOnlyWhenNothingIsInFlight(@TempDir Path dir) throws Exception {
        Path cfg = pipeline(dir, "trigger:\n  type: stream\n  records: 1000\n  max_wait: 1s");
        CollectorService svc = new CollectorService(List.of(cfg), 3600, 1);
        try {
            PipelineScheduler s = scheduler(svc);
            java.util.List<String> told = new java.util.concurrent.CopyOnWriteArrayList<>();
            s.onStreamIdle(told::add);
            Path inbox = Files.createDirectories(dir.resolve("inbox"));
            Path landed = inbox.resolve("slice.csv");
            Files.writeString(landed, CSV);
            s.streamIdle("lane_etl");
            assertTrue(told.isEmpty(), "a landed slice waits to be ingested: not drained");
            Files.delete(landed);
            Field running = PipelineScheduler.class.getDeclaredField("running");
            running.setAccessible(true);
            @SuppressWarnings("unchecked") java.util.Set<String> runs = (java.util.Set<String>) running.get(s);
            runs.add("lane_etl");
            s.streamIdle("lane_etl");
            assertTrue(told.isEmpty(), "a run in progress: not drained");
            runs.remove("lane_etl");
            s.streamIdle("lane_etl");
            assertEquals(List.of("lane_etl"), told, "drained");
            s.streamIdle("lane_etl");
            assertEquals(1, told.size(), "at most once a second");
        } finally {
            svc.close();
        }
    }

    /**
     * PA-9d: a Space folder's Real-time Detection state is its own, even with the process-wide -Djobs.audit.dir set:
     * two Spaces sharing one fired table would suppress each other's Alerts for the same rule, key and Window.
     */
    @Test
    void realtimeStateIsPerSpaceEvenWithAProcessWideAuditDir(@TempDir Path dir) throws Exception {
        String before = System.getProperty("jobs.audit.dir");
        System.setProperty("jobs.audit.dir", dir.resolve("shared").toString());
        try {
            Path a = CollectorService.realtimeStateDir(SpaceRoot.under(Files.createDirectories(dir.resolve("a").resolve("config")).getParent()));
            Path b = CollectorService.realtimeStateDir(SpaceRoot.under(Files.createDirectories(dir.resolve("b").resolve("config")).getParent()));
            assertNotEquals(a, b);
            assertTrue(a.startsWith(dir.resolve("a")) && b.startsWith(dir.resolve("b")), a + " / " + b);
            assertEquals(Path.of(dir.resolve("shared").toString(), "realtime"), CollectorService.realtimeStateDir(SpaceRoot.legacy()),
                    "the legacy single-tenant root keeps the override");
        } finally {
            if (before == null) System.clearProperty("jobs.audit.dir");
            else System.setProperty("jobs.audit.dir", before);
        }
    }
}
