package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import com.gamma.event.EventLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * X1 deferrals — the operator affordance over {@link CommitRetry} (design
 * {@code retry-affordance-design.md}, decisions 2026-09-25): list the pending retries of one pipeline,
 * retry one FILE now (backoff cleared, attempt count KEPT — Q2), cancel one file (quarantined NOW under
 * {@code retry_cancelled} — Q1). Every operation reports an {@link CommitRetry.Outcome} and never throws.
 */
class CommitRetryAffordanceTest {

    private String space;

    @BeforeEach
    void isolateSignals() {
        space = "commit-retry-aff-" + UUID.randomUUID();
        EventLog.register(space, EventLog.create());
        org.slf4j.MDC.put(EventLog.SPACE_MDC_KEY, space);
        System.setProperty("ingest.retry.max", "5");
        System.setProperty("ingest.retry.backoff.initialMs", "3600000");   // an hour: never due by itself
    }

    @AfterEach
    void restore() {
        org.slf4j.MDC.remove(EventLog.SPACE_MDC_KEY);
        EventLog.unregister(space);
        for (String k : List.of("ingest.retry.max", "ingest.retry.backoff.initialMs", "ingest.retry.backoff.maxMs"))
            System.clearProperty(k);
    }

    /** One real failed attempt on {@code feed.csv}: the COMMIT fault is a {@code dirs.backup} that is a FILE. */
    private static PipelineConfig failedOnce(Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTestRef.writePipeline(dir, "").toString());
        Files.createDirectories(Path.of(cfg.dirs().poll()));
        Files.writeString(Path.of(cfg.dirs().poll()).resolve("feed.csv"), "ID,AMT,EVENT_DATE\nr1,1.0,2020-04-03\n");
        Files.writeString(Path.of(cfg.dirs().backup()), "not a directory");
        CollectorProcessor.run(cfg);
        return cfg;
    }

    private static File feed(PipelineConfig cfg) { return Path.of(cfg.dirs().poll()).resolve("feed.csv").toFile(); }

    @Test
    void listReportsThePendingFileByPollRelativePath(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = failedOnce(dir);
        CommitRetry.Listing l = CommitRetry.list(cfg, 100);
        assertTrue(l.keepsRetryState());
        assertEquals(1, l.total());
        assertFalse(l.truncated());
        CommitRetry.Pending p = l.retries().get(0);
        assertEquals("feed.csv", p.file());
        assertEquals(1, p.attempts());
        assertFalse(p.due(), "an hour of backoff is not due");
        assertTrue(p.inInbox());
        assertTrue(p.readable());
        assertEquals(5, l.policy().maxAttempts());
    }

    @Test
    void listWithNoStatusDirSaysThePipelineKeepsNoRetryState(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, "");
        String noStatus = Files.readString(toon).lines().filter(line -> !line.contains("status_dir"))
                .reduce("", (a, b) -> a + b + "\n");
        Files.writeString(toon, noStatus);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        CommitRetry.Listing l = CommitRetry.list(cfg, 100);
        assertFalse(l.keepsRetryState(), "no status_dir is NOT the same answer as 'nothing pending'");
        assertEquals(0, l.total());
        assertEquals(CommitRetry.Result.NO_RETRY_STATE,
                CommitRetry.retryNow(cfg, feed(cfg)).result());
        assertEquals(CommitRetry.Result.NO_RETRY_STATE,
                CommitRetry.cancel(cfg, feed(cfg)).result());
    }

    @Test
    void listTruncatesButReportsTheTrueTotal(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = failedOnce(dir);
        Path retries = Path.of(cfg.dirs().manifestsDir()).toAbsolutePath().resolveSibling("retries");
        Files.createDirectories(retries.resolve("sub"));
        Files.writeString(retries.resolve("sub/other.csv.retry.json"), "{\"attempts\":2}");
        CommitRetry.Listing l = CommitRetry.list(cfg, 1);
        assertEquals(2, l.total());
        assertTrue(l.truncated());
        assertEquals(1, l.retries().size());
        assertEquals(List.of("feed.csv", "sub/other.csv"),
                CommitRetry.list(cfg, 10).retries().stream().map(CommitRetry.Pending::file).toList(),
                "sorted, poll-relative, forward slashes");
    }

    @Test
    void anUnreadableSidecarIsListedNotThrown(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = failedOnce(dir);
        Path side = Path.of(cfg.dirs().manifestsDir()).toAbsolutePath().resolveSibling("retries").resolve("feed.csv.retry.json");
        Files.writeString(side, "{not json");
        CommitRetry.Pending p = CommitRetry.list(cfg, 10).retries().get(0);
        assertFalse(p.readable());
        assertEquals(CommitRetry.Result.FAILED, CommitRetry.retryNow(cfg, feed(cfg)).result(),
                "reported, never thrown");
    }

    @Test
    void retryNowClearsTheDueTimeButKeepsTheAttemptCount(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = failedOnce(dir);
        assertTrue(Instant.parse(CommitRetry.recordFor(feed(cfg), cfg).nextRetryAt).isAfter(Instant.now()));

        CommitRetry.Outcome o = CommitRetry.retryNow(cfg, feed(cfg));
        assertEquals(CommitRetry.Result.RESCHEDULED, o.result());
        assertTrue(o.acted());
        assertEquals(1, o.record().attempts, "Q2: the attempt count is kept — the cap still bites");
        CommitRetry.Record r = CommitRetry.recordFor(feed(cfg), cfg);
        assertEquals(1, r.attempts);
        assertNull(r.nextRetryAt, "the due time is cleared");
        assertEquals(List.of(feed(cfg)), CommitRetry.due(cfg, List.of(feed(cfg))), "admitted on the next cycle");

        CollectorProcessor.run(cfg);                              // the next cycle retries it (and fails again)
        assertEquals(2, CommitRetry.recordFor(feed(cfg), cfg).attempts, "counting continued from 1, not from 0");
    }

    @Test
    void cancelQuarantinesNowUnderItsOwnReasonAndSpendsTheRecord(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = failedOnce(dir);
        CommitRetry.Outcome o = CommitRetry.cancel(cfg, feed(cfg));
        assertEquals(CommitRetry.Result.CANCELLED, o.result());
        assertTrue(o.acted());
        assertEquals(CommitRetry.REASON_RETRY_CANCELLED, o.quarantineReason());
        assertFalse(feed(cfg).exists(), "Q1: cancel decides the file's fate — it leaves the inbox");
        assertTrue(Files.exists(Path.of(cfg.dirs().quarantine()).resolve("retry_cancelled").resolve("feed.csv")));
        assertNull(CommitRetry.recordFor(feed(cfg), cfg), "the record is spent with the file");
        assertEquals(0, CommitRetry.list(cfg, 10).total());

        CommitRetry.Outcome again = CommitRetry.cancel(cfg, feed(cfg));
        assertEquals(CommitRetry.Result.ALREADY_QUARANTINED, again.result(), "says so instead of appearing to act");
        assertFalse(again.acted());
        assertEquals("retry_cancelled", again.quarantineReason());
        assertEquals(CommitRetry.Result.ALREADY_QUARANTINED, CommitRetry.retryNow(cfg, feed(cfg)).result());
    }

    @Test
    void aFileWithNoRecordOrNotInTheInboxIsNotActedOn(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = failedOnce(dir);
        File fresh = Path.of(cfg.dirs().poll()).resolve("fresh.csv").toFile();
        Files.writeString(fresh.toPath(), "ID,AMT,EVENT_DATE\n");
        assertEquals(CommitRetry.Result.NO_RECORD, CommitRetry.cancel(cfg, fresh).result());
        assertTrue(fresh.exists(), "a file that never failed is never quarantined by cancel");
        assertEquals(CommitRetry.Result.NO_RECORD, CommitRetry.retryNow(cfg, fresh).result());
        File gone = Path.of(cfg.dirs().poll()).resolve("gone.csv").toFile();
        assertEquals(CommitRetry.Result.NOT_IN_INBOX, CommitRetry.cancel(cfg, gone).result());
    }

    @Test
    void thePerFileClearSpendsOnlyThatFilesRecord(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = failedOnce(dir);
        CommitRetry.clear(feed(cfg), cfg);
        assertNull(CommitRetry.recordFor(feed(cfg), cfg));
        assertTrue(feed(cfg).exists(), "clearing a record moves nothing");
    }

    @Test
    void inboxFileRefusesAPathThatLeavesThePollRoot(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = failedOnce(dir);
        assertEquals(feed(cfg).getAbsoluteFile().toPath().normalize(), CommitRetry.inboxFile(cfg, "feed.csv").toPath());
        assertNotNull(CommitRetry.inboxFile(cfg, "sub/feed.csv"));
        assertNull(CommitRetry.inboxFile(cfg, "../feed.csv"));
        assertNull(CommitRetry.inboxFile(cfg, "sub/../../feed.csv"));
        assertNull(CommitRetry.inboxFile(cfg, dir.resolve("x.csv").toAbsolutePath().toString()));
        assertNull(CommitRetry.inboxFile(cfg, ""));
    }
}
