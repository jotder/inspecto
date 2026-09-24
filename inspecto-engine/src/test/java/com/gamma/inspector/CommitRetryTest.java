package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import com.gamma.event.EventLog;
import com.gamma.signal.Signal;
import com.gamma.signal.Signals;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * X1 — the ingest lane's retry is the next cycle's re-encounter of a FAILED Consignment's files; these
 * pin that it is now BOUNDED: attempts are recorded durably, backoff withholds a file from the run path
 * (never from the pending count), exhaustion quarantines under {@code retry_exhausted} and emits one
 * CRITICAL Signal, and a commit spends the record.
 *
 * <p>The deterministic COMMIT fault: {@code dirs.backup} is a regular FILE, so {@code backupFile}'s
 * {@code createDirectories} throws a non-{@code NoSuchFile} IOException after the outputs are written —
 * exactly the "output landed, side effect failed" demotion to FAILED that leaves the file in the inbox.
 */
class CommitRetryTest {

    private String space;
    private EventLog log;

    @BeforeEach
    void isolateSignals() {
        space = "commit-retry-" + UUID.randomUUID();
        log = EventLog.create();
        EventLog.register(space, log);
        org.slf4j.MDC.put(EventLog.SPACE_MDC_KEY, space);
    }

    @AfterEach
    void restore() {
        org.slf4j.MDC.remove(EventLog.SPACE_MDC_KEY);
        EventLog.unregister(space);
        for (String k : List.of("ingest.retry.max", "ingest.retry.backoff.initialMs", "ingest.retry.backoff.maxMs"))
            System.clearProperty(k);
    }

    private static PipelineConfig brokenBackup(Path dir) throws Exception {
        return brokenBackup(dir, "");
    }

    private static PipelineConfig brokenBackup(Path dir, String processingSection) throws Exception {
        Files.createDirectories(dir);
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, processingSection);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Files.createDirectories(Path.of(cfg.dirs().poll()));
        Files.writeString(Path.of(cfg.dirs().poll()).resolve("feed.csv"), "ID,AMT,EVENT_DATE\nr1,1.0,2020-04-03\n");
        Files.writeString(Path.of(cfg.dirs().backup()), "not a directory");   // the deterministic COMMIT fault
        return cfg;
    }

    private static long batchRows(PipelineConfig cfg) throws Exception {
        Path p = Path.of(cfg.dirs().batchesFilePath());
        return Files.exists(p) ? Files.readString(p).lines().count() - 1 : 0;
    }

    @Test
    void aFailedCommitIsRetriedThenExhaustedIntoQuarantineWithOneCriticalSignal(@TempDir Path dir) throws Exception {
        System.setProperty("ingest.retry.max", "2");
        System.setProperty("ingest.retry.backoff.initialMs", "0");   // due immediately — the cap is under test
        PipelineConfig cfg = brokenBackup(dir);
        File feed = Path.of(cfg.dirs().poll()).resolve("feed.csv").toFile();

        CollectorProcessor.run(cfg);                                   // attempt 1: FAILED at commit
        assertTrue(feed.exists(), "a COMMIT failure leaves the file in the inbox — that is the retry");
        CommitRetry.Record r = CommitRetry.recordFor(feed, cfg);
        assertNotNull(r, "the attempt is on the record");
        assertEquals(1, r.attempts);
        assertTrue(r.lastError.contains("commit failed"), r.lastError);
        assertEquals(1, batchRows(cfg), "attempt 1 audited FAILED");

        CollectorProcessor.run(cfg);                                   // attempt 2: cap reached
        assertFalse(feed.exists(), "exhausted ⇒ quarantined, not left to loop forever");
        assertNull(CommitRetry.recordFor(feed, cfg), "the record is spent with the file");
        try (Stream<Path> q = Files.walk(Path.of(cfg.dirs().quarantine()))) {
            assertTrue(q.anyMatch(p -> p.getFileName().toString().equals("feed.csv")
                            && p.getParent().getFileName().toString().equals(CommitRetry.REASON_RETRY_EXHAUSTED)),
                    "quarantined under " + CommitRetry.REASON_RETRY_EXHAUSTED);
        }
        List<Signal> exhausted = Signals.query(log.store(), CommitRetry.SIGNAL_TYPE, null, null, null, null, 10);
        assertEquals(1, exhausted.size(), "ONE exhaustion Signal per Consignment");
        assertEquals(com.gamma.signal.Severity.CRITICAL, exhausted.get(0).severity());
        assertEquals(2, ((Number) exhausted.get(0).payload().get("attempts")).intValue());
        assertEquals(List.of("feed.csv"), exhausted.get(0).payload().get("files"));
        assertEquals(2, batchRows(cfg), "both attempts audited");

        CollectorProcessor.run(cfg);                                   // nothing left to retry
        assertEquals(2, batchRows(cfg), "a quarantined file is not re-encountered");
    }

    @Test
    void backoffWithholdsTheFileFromTheRunPathButNotFromThePendingCount(@TempDir Path dir) throws Exception {
        System.setProperty("ingest.retry.max", "5");
        System.setProperty("ingest.retry.backoff.initialMs", "3600000");   // an hour: the retry is not due
        PipelineConfig cfg = brokenBackup(dir);
        File feed = Path.of(cfg.dirs().poll()).resolve("feed.csv").toFile();

        CollectorProcessor.run(cfg);                                   // attempt 1: FAILED
        assertEquals(1, CommitRetry.recordFor(feed, cfg).attempts);
        assertEquals(1, batchRows(cfg));

        CollectorProcessor.run(cfg);                                   // not due: no attempt, no audit row
        assertEquals(1, CommitRetry.recordFor(feed, cfg).attempts, "withheld — no second attempt");
        assertEquals(1, batchRows(cfg), "nothing ran, so nothing was audited");
        assertTrue(feed.exists());
        assertEquals(1, CollectorProcessor.countPending(cfg),
                "a file waiting out its backoff is still honestly PENDING");
    }

    @Test
    void aSuccessfulCommitSpendsTheRecord(@TempDir Path dir) throws Exception {
        System.setProperty("ingest.retry.max", "5");
        System.setProperty("ingest.retry.backoff.initialMs", "0");
        PipelineConfig cfg = brokenBackup(dir);
        File feed = Path.of(cfg.dirs().poll()).resolve("feed.csv").toFile();

        CollectorProcessor.run(cfg);                                   // attempt 1: FAILED
        assertEquals(1, CommitRetry.recordFor(feed, cfg).attempts);

        Files.delete(Path.of(cfg.dirs().backup()));                    // the operator fixes the destination
        CollectorProcessor.run(cfg);                                   // attempt 2: commits
        assertFalse(feed.exists(), "committed ⇒ backed up out of the inbox");
        assertNull(CommitRetry.recordFor(feed, cfg), "a commit spends the attempt record");
        assertTrue(Signals.query(log.store(), CommitRetry.SIGNAL_TYPE, null, null, null, null, 10).isEmpty(),
                "no exhaustion Signal for a Consignment that recovered");
    }

    // ── the per-pipeline processing.retry block (X1 deferral, 2026-09-25) ──────────────────────────

    @Test
    void aPipelinesRetryBlockOverridesTheGlobalCapAndBackoff(@TempDir Path dir) throws Exception {
        System.setProperty("ingest.retry.max", "5");                              // global: 5, an hour apart
        System.setProperty("ingest.retry.backoff.initialMs", "3600000");
        PipelineConfig cfg = brokenBackup(dir, """
              retry:
                max_attempts: 2
                initial_backoff: 0s
                max_backoff: 1m
            """);
        File feed = Path.of(cfg.dirs().poll()).resolve("feed.csv").toFile();
        assertEquals(new CommitRetry.Policy(2, 0L, 60_000L), CommitRetry.policy(cfg));

        CollectorProcessor.run(cfg);                                   // attempt 1 — due at once (0s)
        assertEquals(1, CommitRetry.recordFor(feed, cfg).attempts);
        CollectorProcessor.run(cfg);                                   // attempt 2 — the PIPELINE's cap
        assertFalse(feed.exists(), "the pipeline's cap of 2 bit, not the global 5");
        assertEquals(1, Signals.query(log.store(), CommitRetry.SIGNAL_TYPE, null, null, null, null, 10).size());
    }

    @Test
    void anUnsetKeyInheritsItsGlobalAndAnAbsentBlockIsTodaysBehaviour(@TempDir Path dir) throws Exception {
        System.setProperty("ingest.retry.max", "7");
        System.setProperty("ingest.retry.backoff.initialMs", "1234");
        System.setProperty("ingest.retry.backoff.maxMs", "5678");
        assertEquals(new CommitRetry.Policy(7, 1234L, 5678L), CommitRetry.policy(brokenBackup(dir.resolve("a"))),
                "no block ⇒ the -D globals whole");
        assertEquals(new CommitRetry.Policy(3, 1234L, 5678L), CommitRetry.policy(brokenBackup(dir.resolve("b"), """
              retry:
                max_attempts: 3
            """)), "each key independently optional");
        System.clearProperty("ingest.retry.max");
        System.clearProperty("ingest.retry.backoff.initialMs");
        System.clearProperty("ingest.retry.backoff.maxMs");
        assertEquals(new CommitRetry.Policy(5, 60_000L, 3_600_000L), CommitRetry.policy(brokenBackup(dir.resolve("c"))),
                "the defaults are today's global behaviour");
    }

    @Test
    void aPipelineMayTurnBoundingOffWithMaxAttemptsZero(@TempDir Path dir) throws Exception {
        System.setProperty("ingest.retry.max", "2");
        System.setProperty("ingest.retry.backoff.initialMs", "0");
        PipelineConfig cfg = brokenBackup(dir, """
              retry:
                max_attempts: 0
            """);
        File feed = Path.of(cfg.dirs().poll()).resolve("feed.csv").toFile();
        for (int i = 0; i < 3; i++) CollectorProcessor.run(cfg);
        assertTrue(feed.exists(), "0 = unbounded for this pipeline, whatever the global says");
        assertNull(CommitRetry.recordFor(feed, cfg));
    }

    @Test
    void maxZeroKeepsThePreX1UnboundedBehaviour(@TempDir Path dir) throws Exception {
        System.setProperty("ingest.retry.max", "0");
        PipelineConfig cfg = brokenBackup(dir);
        File feed = Path.of(cfg.dirs().poll()).resolve("feed.csv").toFile();
        for (int i = 0; i < 3; i++) CollectorProcessor.run(cfg);
        assertTrue(feed.exists(), "unbounded: the file is never quarantined for retrying");
        assertNull(CommitRetry.recordFor(feed, cfg), "unbounded: nothing is recorded");
        assertEquals(3, batchRows(cfg), "every cycle re-attempted, as before X1");
    }
}
