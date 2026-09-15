package com.gamma.service;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The polling session's own state on {@code GET /collectors}
 * ({@code DUCKLE-C9-WATCHER-NOT-A-RUN-1}, rescoped 2026-09-15 to the observability half).
 *
 * <p>🔴 <b>What this is NOT.</b> The row as adopted said the collector loop "conflates polls and runs,
 * which makes run counts misleading". It does not, and did not when the row was written:
 * {@code CollectorProcessor.ingest} returns on an empty candidate list <em>before</em>
 * {@code RunIds.next()} is called, so a quiet poll mints no Run. That was fixed in {@code 1fda46d5} on
 * 2026-09-13, two days before the row was adopted. ⛔ These tests must not be read as pinning a
 * miscount fix — there was no miscount.
 *
 * <p>🔴 <b>What it IS.</b> A poll that found nothing left no trace on any API surface, so a collector
 * polling quietly and one wedged since Tuesday looked identical. The Run ledger cannot answer this by
 * construction: a quiet poll correctly writes nothing to it. That is the gap.
 */
class CollectorServicePollStateTest {

    private static final String CSV = "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n";

    /** A pipeline whose inbox is EMPTY — the quiet-poll case this feature exists for. */
    private static Path quiet(Path root, String name) throws Exception {
        Path toon = TestConfigs.csv(root, PipelineConfigBatchTest.miniSchema()).name(name).write();
        Files.createDirectories(root.resolve("inbox"));
        return toon;
    }

    /** A pipeline with one file waiting, so its poll does real work. */
    private static Path withWork(Path root, String name) throws Exception {
        Path toon = TestConfigs.csv(root, PipelineConfigBatchTest.miniSchema()).name(name).write();
        Path inbox = root.resolve("inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("data.csv"), CSV);
        return toon;
    }

    private static Map<String, Object> rowFor(CollectorService svc, String pipeline) {
        return svc.collectors().stream()
                .filter(m -> pipeline.equalsIgnoreCase(String.valueOf(m.get("pipeline"))))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "no collector row for '" + pipeline + "' in " + svc.collectors()));
    }

    /**
     * 🔴 <b>The case the whole rescope is for.</b> A pipeline whose inbox is empty performs a real poll and
     * produces no Run — and before this, that left nothing anywhere. After one cycle its row must show that
     * it looked.
     */
    @Test
    void aQuietPollIsRecordedEvenThoughItMintsNoRun(@TempDir Path dir) throws Exception {
        Path cfg = quiet(dir, "quiet_one");
        try (CollectorService svc = new CollectorService(List.of(cfg), 3600, 1)) {
            assertNull(rowFor(svc, "quiet_one").get("lastPollAt"), "nothing has polled yet");

            svc.runAllOnce();

            Map<String, Object> row = rowFor(svc, "quiet_one");
            assertNotNull(row.get("lastPollAt"), "a poll that found nothing still happened");
            assertEquals(1L, row.get("pollCount"));
            assertNull(row.get("lastPollError"), "finding nothing is not an error");
        }
    }

    /**
     * ⚠ <b>Absent, not zero, before the first poll.</b> "Never looked" and "looked and found nothing" are
     * different answers, and a zeroed row would collapse them — which is the confusion this exists to
     * remove, not to re-create one field further down.
     */
    @Test
    void aPipelineNeverPolledReportsAbsentRatherThanZero(@TempDir Path dir) throws Exception {
        Path cfg = quiet(dir, "never_polled");
        try (CollectorService svc = new CollectorService(List.of(cfg), 3600, 1)) {
            Map<String, Object> row = rowFor(svc, "never_polled");
            assertTrue(row.containsKey("pollCount"), "the key is always present on the contract");
            assertNull(row.get("pollCount"), "…but null until a poll has actually happened");
            assertNull(row.get("lastPollAt"));
        }
    }

    /** Polls accumulate — the count is a session total, not a per-cycle flag. */
    @Test
    void pollCountAccumulatesAcrossCycles(@TempDir Path dir) throws Exception {
        Path cfg = quiet(dir, "counted");
        try (CollectorService svc = new CollectorService(List.of(cfg), 3600, 1)) {
            svc.runAllOnce();
            svc.runAllOnce();
            svc.runAllOnce();
            assertEquals(3L, rowFor(svc, "counted").get("pollCount"));
        }
    }

    /**
     * A poll that DOES work is recorded the same way — the state is about the session, not about whether
     * the cycle happened to find files. ⚠ Pinned because an implementation that only stamped the quiet
     * branch would pass the first test here and still be wrong.
     */
    @Test
    void aPollThatDidWorkIsRecordedToo(@TempDir Path dir) throws Exception {
        Path cfg = withWork(dir, "busy_one");
        try (CollectorService svc = new CollectorService(List.of(cfg), 3600, 1)) {
            svc.runAllOnce();
            Map<String, Object> row = rowFor(svc, "busy_one");
            assertNotNull(row.get("lastPollAt"));
            assertEquals(1L, row.get("pollCount"));
        }
    }
}
