package com.gamma.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B3b — the <b>acquisition driver</b>: {@link PipelineScheduler#dispatchAcquireCycle()} fetches remote files on
 * its own timer/budget/guard, independent of the ingest cycle. Driven with the ServiceLoader-registered
 * {@link FakeRemoteConnectorFactory} ({@code faketest} scheme), so no network is involved but the real
 * discover → stage → land path runs.
 *
 * <p>Timers are not started here (no {@code svc.start()}); each cycle is invoked directly by reflection, so the
 * assertions are deterministic — the only thing that fetches is the {@code dispatchAcquireCycle()} the test calls.
 */
class AcquisitionDriverTest {

    private static final String CSV = "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n";

    private static PipelineScheduler scheduler(CollectorService svc) throws Exception {
        Field f = CollectorService.class.getDeclaredField("pipelineScheduler");
        f.setAccessible(true);
        return (PipelineScheduler) f.get(svc);
    }

    private static long fileCount(Path root) throws Exception {
        if (!Files.exists(root)) return 0;
        try (var s = Files.walk(root)) { return s.filter(Files::isRegularFile).count(); }
    }

    private interface Condition { boolean holds() throws Exception; }

    /** Poll until {@code cond} holds, up to ~10s. */
    private static boolean awaitTrue(Condition cond) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (cond.holds()) return true;
            Thread.sleep(50);
        }
        return false;
    }

    /** A pipeline whose collector is the {@code faketest} remote scheme; inbox starts empty. */
    private static Path remotePipeline(Path dir, String name) throws Exception {
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, """
            partitionKey: EVENT_DATE
            raw:
              name: mini
              format: CSV
              fields[3]{name,selector,type}:
                ID,"0",VARCHAR
                AMT,"1",DOUBLE
                EVENT_DATE,"2",DATE
            mapping:
              canonicalName: mini
              rawName: mini
              rules[3]{targetColumn,sourceExpression,transformType}:
                ID,ID,DIRECT
                AMT,AMT,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
            """);
        String toon = """
            name: %s
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
            output:
              format: CSV
            processing:
              threads: 2
              file_pattern: "glob:**/*.csv"
              duplicate_check:
                enabled: true
                marker_extension: .processed
              schema_file: "%s"
              batch:
                max_files: 100
                max_bytes: 268435456
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                skip_junk_lines: 0
                skip_tail_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(name, dir, dir, dir, dir, dir, dir, dir, dir, dir,
                          schema.toString().replace("\\", "/"));
        Path p = dir.resolve("remote_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }

    @Test
    void dispatchAcquireCycleFetchesAndLandsRemoteFiles(@TempDir Path dir) throws Exception {
        Path remote = Files.createDirectories(dir.resolve("remote"));
        Files.writeString(remote.resolve("a.csv"), CSV);
        Files.writeString(remote.resolve("b.csv"), CSV);
        FakeRemoteConnectorFactory.reset(remote);

        Path cfg = remotePipeline(dir, "REMOTE_ETL");
        CollectorService svc = new CollectorService(List.of(cfg), 3600, 1);
        try {
            assertEquals(0, fileCount(dir.resolve("inbox")), "inbox starts empty — nothing landed yet");

            scheduler(svc).dispatchAcquireCycle();

            assertTrue(awaitTrue(() -> fileCount(dir.resolve("inbox")) == 2),
                    "the acquisition driver fetched both remote files and landed them in the inbox");
            assertEquals(2, FakeRemoteConnectorFactory.FETCHES.get(), "each remote file was fetched once");
        } finally {
            svc.close();
        }
    }

    @Test
    void periodicIngestCycleDoesNotFetchForARemoteCollector(@TempDir Path dir) throws Exception {
        Path remote = Files.createDirectories(dir.resolve("remote"));
        Files.writeString(remote.resolve("a.csv"), CSV);
        FakeRemoteConnectorFactory.reset(remote);

        Path cfg = remotePipeline(dir, "REMOTE_ETL");
        CollectorService svc = new CollectorService(List.of(cfg), 3600, 1);
        try {
            // The periodic driver is ingest-only (B3b): a separate acquisition timer does the fetching, so a
            // poll tick must not pull from the remote. It walks the (empty) inbox and processes nothing.
            scheduler(svc).dispatchCycle();

            // Give any (wrongly dispatched) fetch time to run, then assert it never happened.
            Thread.sleep(300);
            assertEquals(0, FakeRemoteConnectorFactory.FETCHES.get(),
                    "the periodic ingest cycle must not fetch — that is the acquisition driver's job");
            assertEquals(0, fileCount(dir.resolve("inbox")), "nothing was landed by the ingest cycle");
        } finally {
            svc.close();
        }
    }

    @Test
    void aSecondAcquisitionIsSkippedWhileTheFirstIsStillFetching(@TempDir Path dir) throws Exception {
        Path remote = Files.createDirectories(dir.resolve("remote"));
        Files.writeString(remote.resolve("a.csv"), CSV);
        FakeRemoteConnectorFactory.reset(remote);
        CountDownLatch gate = new CountDownLatch(1);
        FakeRemoteConnectorFactory.GATE.set(gate);   // hold every fetch in flight until released

        Path cfg = remotePipeline(dir, "REMOTE_ETL");
        CollectorService svc = new CollectorService(List.of(cfg), 3600, 1);
        try {
            scheduler(svc).dispatchAcquireCycle();                 // first: claims acquireGuard, blocks in fetch
            assertTrue(awaitTrue(() -> FakeRemoteConnectorFactory.FETCHES.get() == 1),
                    "the first acquisition started fetching (and holds the pipeline's acquire claim)");

            scheduler(svc).dispatchAcquireCycle();                 // second: same pipeline still fetching
            Thread.sleep(300);                                     // a wrongly-admitted second fetch would run here
            assertEquals(1, FakeRemoteConnectorFactory.FETCHES.get(),
                    "two acquisitions of the same pipeline must not overlap — the second is skipped, not queued");
        } finally {
            gate.countDown();                                      // release before close() drains the worker
            svc.close();
        }
    }
}
