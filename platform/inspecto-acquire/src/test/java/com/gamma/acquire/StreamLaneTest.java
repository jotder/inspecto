package com.gamma.acquire;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** The continuous lane's decision loop in isolation: drain on N, drain on T, reset on empty, reopen on failure. */
class StreamLaneTest {

    /** A connector whose only live method is the backlog probe. */
    static final class Probe implements CollectorConnector {
        volatile long pending;
        volatile boolean fail;
        final AtomicInteger closes = new AtomicInteger();
        @Override public long pendingRecords() throws AcquisitionException {
            if (fail) throw new AcquisitionException("broker down");
            return pending;
        }
        @Override public void close() { closes.incrementAndGet(); }
        @Override public String scheme() { return "probe"; }
        @Override public EnumSet<Capability> capabilities() { return EnumSet.noneOf(Capability.class); }
        @Override public List<RemoteFile> discover(DiscoveryContext ctx) { return List.of(); }
        @Override public Readiness readiness(RemoteFile f) { return Readiness.READY; }
        @Override public InputStream open(RemoteFile f) { throw new UnsupportedOperationException(); }
        @Override public Path fetchTo(RemoteFile f, Path d) { throw new UnsupportedOperationException(); }
        @Override public void post(RemoteFile f, PostAction a) { }
    }

    private final Probe probe = new Probe();
    private final AtomicLong now = new AtomicLong(1_000);
    private final AtomicInteger drained = new AtomicInteger();
    private final AtomicInteger opens = new AtomicInteger();

    private StreamLane lane(long n, long t) {
        return new StreamLane("p", n, t, 50, () -> { opens.incrementAndGet(); return probe; },
                drained::incrementAndGet, now::get);
    }

    @Test
    void drainsAsSoonAsTheBacklogReachesN() {
        StreamLane l = lane(100, 60_000);
        probe.pending = 99;
        assertFalse(l.step());
        probe.pending = 100;
        assertTrue(l.step(), "N reached — no wait");
        assertEquals(1, drained.get());
    }

    @Test
    void drainsASmallBacklogOnceItHasWaitedT() {
        StreamLane l = lane(100, 5_000);
        probe.pending = 3;
        assertFalse(l.step());                    // starts the clock at 1000
        now.set(5_999);
        assertFalse(l.step(), "4999 ms waited < T");
        now.set(6_000);
        assertTrue(l.step(), "T waited");
        assertEquals(1, drained.get());
        now.set(6_001);
        assertFalse(l.step(), "the wait restarts after a drain");
    }

    @Test
    void anEmptyProbeResetsTheWait() {
        StreamLane l = lane(100, 5_000);
        probe.pending = 3;
        l.step();
        probe.pending = 0;
        now.set(10_000);
        assertFalse(l.step());
        probe.pending = 3;
        assertFalse(l.step(), "a new backlog starts a new wait");
        now.set(15_000);
        assertTrue(l.step());
    }

    @Test
    void aConnectorThatCannotCountDrainsOnTAlone() {
        StreamLane l = lane(1, 5_000);
        probe.pending = -1;
        assertFalse(l.step(), "-1 is not a count, so it never meets N");
        now.set(6_000);
        assertTrue(l.step());
    }

    @Test
    void aProbeFailureClosesAndReopensTheConnectorAndDrainsNothing() {
        StreamLane l = lane(1, 5_000);
        probe.fail = true;
        assertFalse(l.step());
        assertEquals(1, probe.closes.get());
        probe.fail = false;
        probe.pending = 5;
        assertTrue(l.step());
        assertEquals(2, opens.get(), "reopened after the failure");
    }

    @Test
    void theConnectorStaysOpenAcrossProbes() {
        StreamLane l = lane(1_000, 60_000);
        probe.pending = 1;
        for (int i = 0; i < 20; i++) l.step();
        assertEquals(1, opens.get(), "one connector (one consumer) for the lane's life");
        l.close();
        assertEquals(1, probe.closes.get());
    }

    @Test
    void anErrorInTheProbeKillsTheLaneAndCountsIt() {
        StreamLane l = new StreamLane("dies-probe", 1, 5_000, 50, () -> { throw new OutOfMemoryError("probe"); },
                drained::incrementAndGet, now::get);
        double before = deaths("dies-probe");
        assertFalse(l.step());
        assertTrue(l.dead());
        assertEquals(before + 1, deaths("dies-probe"));
        probe.pending = 5;
        assertFalse(l.step(), "a dead lane never steps again");
        assertEquals(0, drained.get());
    }

    @Test
    void aThrowingDrainKillsTheLane() {
        StreamLane l = new StreamLane("dies-drain", 1, 5_000, 50, () -> probe,
                () -> { throw new IllegalStateException("drain blew up"); }, now::get);
        probe.pending = 1;
        assertTrue(l.step(), "it did try to drain");
        assertTrue(l.dead());
        assertEquals(1, probe.closes.get(), "the dead lane released its connector");
        assertFalse(l.step());
    }

    @Test
    void anOrdinaryProbeFailureDoesNotKillTheLane() {
        StreamLane l = lane(1, 5_000);
        probe.fail = true;
        l.step();
        assertFalse(l.dead());
    }

    @Test
    void anAlwaysThrowingProbeIsReplacedAtGrowingBoundedSpacing() {
        // The owner's loop: every 200 ms tick, replace the dead lane once its shared backoff is ready.
        StreamLane.Backoff b = new StreamLane.Backoff(1_000, 8_000, now::get);
        double before = deaths("backoff-dies");
        List<Long> deathsAt = new java.util.ArrayList<>();
        for (int tick = 0; tick < 3_000; tick++, now.addAndGet(200)) {   // 10 minutes
            if (!b.ready()) continue;
            StreamLane l = new StreamLane("backoff-dies", 1, 5_000, 50, () -> { throw new OutOfMemoryError("x"); },
                    drained::incrementAndGet, now::get, b);
            assertFalse(l.step());
            assertTrue(l.dead());
            deathsAt.add(now.get());
        }
        assertTrue(deathsAt.size() < 200, "not one replacement per tick: " + deathsAt.size() + " of 3000");
        for (int i = 2; i < deathsAt.size(); i++) {
            long gap = deathsAt.get(i) - deathsAt.get(i - 1);
            assertTrue(gap <= 8_200, "spacing is capped: " + gap);
            if (i < 5) assertTrue(gap >= deathsAt.get(i - 1) - deathsAt.get(i - 2), "spacing grows: " + deathsAt);
        }
        assertTrue(deathsAt.get(deathsAt.size() - 1) - deathsAt.get(deathsAt.size() - 2) >= 4_000, "reached the cap band");
        assertEquals(before + deathsAt.size(), deaths("backoff-dies"), "one death counted per replacement");
    }

    @Test
    void aHealthyProbeResetsTheDeathBackoff() {
        StreamLane.Backoff b = new StreamLane.Backoff(1_000, 8_000, now::get);
        for (int i = 0; i < 6; i++) b.fail();
        assertFalse(b.ready());
        now.addAndGet(8_001);
        StreamLane l = new StreamLane("backoff-ok", 1_000, 60_000, 50, () -> probe, drained::incrementAndGet, now::get, b);
        probe.pending = 0;
        assertFalse(l.step());
        assertEquals(0, b.streak(), "a healthy probe resets the death streak");
        assertEquals(1, b.fail());
        assertTrue(b.ready(), "so the next death restarts at once again");
    }

    @Test
    void anUnreachableBrokerIsReprobedWithBackoffAndResetsOnSuccess() {
        StreamLane l = lane(1, 5_000);
        probe.fail = true;
        for (int i = 0; i < 300; i++, now.addAndGet(200)) l.step();   // 60 s of 200 ms probe ticks
        assertTrue(opens.get() < 30, "backoff spaced the reopens: " + opens.get() + " of 300 ticks");
        probe.fail = false;
        probe.pending = 5;
        now.addAndGet(60_001);
        assertTrue(l.step(), "a reachable broker drains again");
        probe.fail = true;
        l.step();                                  // a new streak starts at 1: no wait
        probe.fail = false;
        assertTrue(l.step(), "success reset the probe backoff, so the next probe is immediate");
    }

    @SuppressWarnings("unchecked")
    private static double deaths(String pipeline) {
        var snap = com.gamma.metrics.MetricRegistry.global().snapshot("inspecto_stream_lane_deaths_total"::equals);
        var entry = (java.util.Map<String, Object>) snap.get("inspecto_stream_lane_deaths_total");
        if (entry == null) return 0;
        for (var row : (List<java.util.Map<String, Object>>) entry.get("series"))
            if (String.valueOf(row.get("labels")).contains(pipeline)) return ((Number) row.get("value")).doubleValue();
        return 0;
    }

    @Test
    void theStartedLaneDrainsOnItsOwnThreadAndStopsOnClose() throws Exception {
        StreamLane l = new StreamLane("p", 10, 60_000, 10, () -> probe, drained::incrementAndGet, System::currentTimeMillis);
        probe.pending = 10;
        l.start(null);
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (drained.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5);
        l.close();
        int after = drained.get();
        assertTrue(after > 0, "the lane drained without being stepped");
        Thread.sleep(100);
        assertEquals(after, drained.get(), "nothing drains after close");
    }
}
