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
