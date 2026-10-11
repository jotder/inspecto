package com.gamma.exchange;

import com.gamma.exchange.ExchangeSnapshots.SnapshotMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The scheduled snapshot refresh's due rule and outcome recording (SHARE-SNAPSHOT-ON-APPROVE-1). */
class ExchangeSnapshotSweepTest {

    private static final long HOUR = 3_600_000L;

    private static Exchange shared(Path root, String cadence, String mode) {
        Exchange ex = Exchange.under(root);
        ex.putOffer(new Offer("dataset", "tax", "finance", "", Map.of(), "a", 1L).withRefresh(cadence));
        ex.request("dataset", "tax", "finance", "audit", "r", "p", mode);
        ex.approve(ShareGrant.idFor("dataset", "tax", "finance", "audit"), "a");
        return ex;
    }

    private static SnapshotMeta meta(long at) {
        return new SnapshotMeta("v" + at, 3, Instant.ofEpochMilli(at).toString(), List.of());
    }

    @Test
    void refreshesOnceAnIntervalHasElapsedAndRecordsTheOutcome(@TempDir Path root) {
        Exchange ex = shared(root, "hourly", "snapshot");
        List<String> published = new ArrayList<>();
        long t0 = 10 * HOUR;

        // never attempted -> due
        assertEquals(List.of("finance/tax"), ExchangeSnapshotSweep.sweep(ex, t0, o -> false,
                (o, i) -> { published.add(o + "/" + i); return meta(t0); }));
        Map<String, Object> last = ExchangeSnapshots.readLastRefresh(
                ExchangeSnapshots.itemDir(ex.dir(), "finance", "tax")).orElseThrow();
        assertEquals("published", last.get("status"));
        assertEquals("schedule", last.get("trigger"));

        // inside the interval -> not due; after it -> due again
        assertTrue(ExchangeSnapshotSweep.sweep(ex, t0 + HOUR - 1, o -> false, (o, i) -> meta(t0)).isEmpty());
        assertEquals(1, ExchangeSnapshotSweep.sweep(ex, t0 + HOUR, o -> false, (o, i) -> meta(t0 + HOUR)).size());
    }

    @Test
    void aFailureIsRecordedAndWaitsAFullIntervalInsteadOfRetryingEveryTick(@TempDir Path root) {
        Exchange ex = shared(root, "hourly", "snapshot");
        long t0 = 10 * HOUR;
        assertEquals(1, ExchangeSnapshotSweep.sweep(ex, t0, o -> false,
                (o, i) -> { throw new IllegalStateException("no table"); }).size());
        Map<String, Object> last = ExchangeSnapshots.readLastRefresh(
                ExchangeSnapshots.itemDir(ex.dir(), "finance", "tax")).orElseThrow();
        assertEquals("failed", last.get("status"));
        assertEquals("no table", last.get("error"));
        assertTrue(ExchangeSnapshotSweep.sweep(ex, t0 + 60_000, o -> false, (o, i) -> meta(t0)).isEmpty());
    }

    @Test
    void noCadenceNoSnapshotConsumerOrAPausedOwnerMeansNothingRuns(@TempDir Path root) {
        long now = 10 * HOUR;
        ExchangeSnapshotSweep.Publisher never = (o, i) -> fail("must not publish");
        assertTrue(ExchangeSnapshotSweep.sweep(shared(root.resolve("a"), "none", "snapshot"), now, o -> false, never).isEmpty());
        assertTrue(ExchangeSnapshotSweep.sweep(shared(root.resolve("b"), "daily", "live"), now, o -> false, never).isEmpty());
        assertTrue(ExchangeSnapshotSweep.sweep(shared(root.resolve("c"), "daily", "snapshot"), now, "finance"::equals, never).isEmpty());

        Exchange revoked = shared(root.resolve("d"), "daily", "snapshot");
        revoked.revoke(ShareGrant.idFor("dataset", "tax", "finance", "audit"), "a");
        assertTrue(ExchangeSnapshotSweep.sweep(revoked, now, o -> false, never).isEmpty());
    }
}
