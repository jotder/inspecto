package com.gamma.consignment;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link StreamWatermark} (consignment addressing §3.6): the per-stream completeness primitive, folded from the
 * catalog. Every assertion here is about the same asymmetry — advancing too far is a wrong answer that fires a
 * rule, while not advancing is merely no answer, so the fold errs toward "unknown" in every ambiguous case.
 */
class StreamWatermarkTest {

    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");
    private static final Duration H = StreamWatermark.DEFAULT_HORIZON;

    private static ProducerHighWater p(String producer, String eventTimeMax, Instant lastSeen) {
        return new ProducerHighWater(producer, eventTimeMax, lastSeen);
    }

    /** The whole reason this is not {@code max(event_time_max)}: a producer running ahead must not close a
     *  window a slower one still owes rows inside. */
    @Test
    void theLaggingProducerSetsTheWatermark() {
        Optional<LocalDateTime> wm = StreamWatermark.of(List.of(
                p("fast", "2026-08-10T11:00:00", NOW),
                p("slow", "2026-08-10T08:00:00", NOW)), H, NOW);

        assertEquals(Optional.of(LocalDateTime.parse("2026-08-10T08:00:00")), wm);
    }

    /** D4, the self-healing half: a producer silent past the horizon stops holding the stream back. */
    @Test
    void producerSilentPastTheHorizonDropsOut() {
        Optional<LocalDateTime> wm = StreamWatermark.of(List.of(
                p("live", "2026-08-10T11:00:00", NOW),
                p("gone", "2026-08-01T08:00:00", NOW.minus(Duration.ofDays(3)))), H, NOW);

        assertEquals(Optional.of(LocalDateTime.parse("2026-08-10T11:00:00")), wm);
    }

    /** …and the conservative half: still inside the horizon, it holds, even by a minute. */
    @Test
    void producerSilentButStillInsideTheHorizonStillHolds() {
        Optional<LocalDateTime> wm = StreamWatermark.of(List.of(
                p("live", "2026-08-10T11:00:00", NOW),
                p("quiet", "2026-08-09T13:00:00", NOW.minus(H).plus(Duration.ofMinutes(1)))), H, NOW);

        assertEquals(Optional.of(LocalDateTime.parse("2026-08-09T13:00:00")), wm);
    }

    /**
     * An in-horizon producer whose rows carry no bounds suppresses the whole answer. Skipping it instead would
     * advance the watermark past a producer that is demonstrably still delivering — the one unsafe direction.
     */
    @Test
    void anInHorizonProducerWithUnknownEventTimeSuppressesTheWatermark() {
        assertTrue(StreamWatermark.of(List.of(
                p("bounded", "2026-08-10T11:00:00", NOW),
                p("unbounded", null, NOW)), H, NOW).isEmpty());
    }

    /** The unattributed group (enrichment, Pipeline sinks) is a producer like any other — it writes no bounds
     *  today, so a stream those paths touch honestly has no watermark rather than a flattering one. */
    @Test
    void unattributedWritesAreAProducerNotAnExemption() {
        assertTrue(StreamWatermark.of(List.of(
                p("ingest", "2026-08-10T11:00:00", NOW),
                p(null, null, NOW)), H, NOW).isEmpty());
    }

    /** Staleness has to be proven: an unreadable last-seen keeps the producer in the set. */
    @Test
    void unknownLastSeenCountsAsInHorizon() {
        Optional<LocalDateTime> wm = StreamWatermark.of(List.of(
                p("live", "2026-08-10T11:00:00", NOW),
                p("undated", "2026-08-10T04:00:00", null)), H, NOW);

        assertEquals(Optional.of(LocalDateTime.parse("2026-08-10T04:00:00")), wm,
                "a producer with no readable last-seen must hold the watermark, not vanish from the set");
    }

    /** Malformed event-time text is unknown, not zero — the D3 posture applied to bad data. */
    @Test
    void malformedEventTimeIsUnknownNotEpoch() {
        assertTrue(StreamWatermark.of(List.of(p("junk", "not-a-timestamp", NOW)), H, NOW).isEmpty());
    }

    @Test
    void noHistoryAndAllStaleBothMeanUnknown() {
        assertTrue(StreamWatermark.of(List.of(), H, NOW).isEmpty(), "a stream with no catalog rows yet");
        assertTrue(StreamWatermark.of(null, H, NOW).isEmpty());
        assertTrue(StreamWatermark.of(List.of(p("gone", "2026-08-01T00:00:00", NOW.minus(Duration.ofDays(9)))),
                H, NOW).isEmpty(), "every producer past the horizon leaves nothing to fold");
    }

    @Test
    void windowClosesOnlyOnceLatenessHasElapsed() {
        Optional<LocalDateTime> wm = Optional.of(LocalDateTime.parse("2026-08-10T10:00:00"));
        LocalDateTime hi = LocalDateTime.parse("2026-08-10T09:00:00");

        assertTrue(StreamWatermark.windowComplete(wm, hi, Duration.ofMinutes(30)));
        assertTrue(StreamWatermark.windowComplete(wm, hi, Duration.ofHours(1)), "equality closes the window");
        assertFalse(StreamWatermark.windowComplete(wm, hi, Duration.ofHours(2)));
    }

    /** No watermark answers the same as a watermark that has not arrived: the window is not closed. */
    @Test
    void noWatermarkNeverClosesAWindow() {
        assertFalse(StreamWatermark.windowComplete(Optional.empty(),
                LocalDateTime.parse("2020-01-01T00:00:00"), Duration.ZERO));
    }

    /**
     * The delivery-table verify for step 4, end to end over a seeded catalog: two producers, and the window
     * closes only once <b>both</b> have passed {@code hi + allowedLateness}.
     */
    @Test
    void seededCatalogClosesAWindowOnlyWhenBothProducersHavePassedIt() throws Exception {
        LocalDateTime hi = LocalDateTime.parse("2026-08-10T09:00:00");
        Duration lateness = Duration.ofMinutes(30);

        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(
                    row("c1", "north", "/w/cdr/n1.parquet", "2026-08-10T10:00:00", NOW),
                    row("c2", "south", "/w/cdr/s1.parquet", "2026-08-10T08:00:00", NOW)));

            assertFalse(StreamWatermark.windowComplete(
                            StreamWatermark.of(db.producerHighWater("cdr"), H, NOW), hi, lateness),
                    "south is still behind hi+lateness, so the window cannot be closed");

            db.record(List.of(row("c3", "south", "/w/cdr/s2.parquet", "2026-08-10T09:45:00", NOW)));

            assertTrue(StreamWatermark.windowComplete(
                            StreamWatermark.of(db.producerHighWater("cdr"), H, NOW), hi, lateness),
                    "both producers have now passed hi+lateness");
        }
    }

    private static ConsignmentOutput row(String consignment, String producer, String path,
                                         String eventTimeMax, Instant writtenAt) {
        return new ConsignmentOutput(consignment, "run-1", "cdr", "dt=2026-08-10", "2026-08-10", path,
                1, 100, writtenAt.toString(), 0, ConsignmentOutput.State.LIVE, null,
                new EventTimeBounds("2026-08-10T00:00:00", eventTimeMax, 0), producer);
    }
}
