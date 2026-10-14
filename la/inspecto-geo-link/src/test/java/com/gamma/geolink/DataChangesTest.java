package com.gamma.geolink;

import com.gamma.signal.DeliveryAnomalySignal;
import com.gamma.signal.Ref;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S5 selection: both edges of the window and the "newer than the last look" edge, plus the Pipeline attribution filter. */
class DataChangesTest {

    private static final Instant LOOK = Instant.parse("2026-10-10T12:00:00Z");
    /** A window of the local days 2026-10-05 .. 2026-10-07 (to is exclusive midnight 08), in UTC. */
    private static final Map<String, Object> WINDOW = Map.of("from", "2026-10-05T00:00:00Z", "to", "2026-10-08T00:00:00Z");
    private static final Set<String> PIPES = Set.of("calls");

    private static Signal anomaly(String pipeline, String day, Instant at) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("pipeline", pipeline);
        p.put("batchId", "b1");
        p.put("kind", "RENAMED_CORRECTION");
        p.put("day", day);
        p.put("file", "calls_" + day + "_v2.csv");
        p.put("previousFile", "calls_" + day + ".csv");
        p.put("detail", "d");
        return new Signal(null, DeliveryAnomalySignal.TYPE, at, Severity.WARN, Ref.of("pipeline", pipeline),
                Ref.of("pipeline", pipeline), "b1", null, null, null, "m", p, 1);
    }

    private static List<DataChanges.Change> pick(Map<String, Object> window, Signal... s) {
        return DataChanges.select(List.of(s), PIPES, window, LOOK);
    }

    /** The real emitter (DayManifest) signals the template's bucket key, not an ISO date: found in the 2026-10-14 live drive. */
    @Test
    void theManifestsCompactAndHourlyBucketKeysPlaceADayAndComeBackAsIso() {
        Instant later = LOOK.plusSeconds(60);
        List<DataChanges.Change> daily = pick(WINDOW, anomaly("calls", "20261006", later));
        assertEquals(1, daily.size(), "yyyyMMdd inside the window");
        assertEquals("2026-10-06", daily.get(0).day(), "shown as an ISO day");
        assertEquals(1, pick(WINDOW, anomaly("calls", "2026100714", later)).size(), "yyyyMMddHH takes its day");
        assertTrue(pick(WINDOW, anomaly("calls", "20261004", later)).isEmpty(), "compact day before the window");
        assertTrue(pick(WINDOW, anomaly("calls", "20261008", later)).isEmpty(), "compact day after the window");
    }

    @Test
    void firstAndLastDayOfTheWindowAreInsideTheDayBeforeAndAfterAreNot() {
        Instant later = LOOK.plusSeconds(60);
        assertEquals(1, pick(WINDOW, anomaly("calls", "2026-10-05", later)).size(), "first window day is inside");
        assertEquals(1, pick(WINDOW, anomaly("calls", "2026-10-07", later)).size(), "last window day is inside ('to' is exclusive midnight)");
        assertTrue(pick(WINDOW, anomaly("calls", "2026-10-04", later)).isEmpty(), "the day before the window is outside");
        assertTrue(pick(WINDOW, anomaly("calls", "2026-10-08", later)).isEmpty(), "the day 'to' falls on at midnight is outside");
    }

    @Test
    void windowDaysAreLocalToTheWindowZone() {
        // 2026-10-05T22:00Z is 2026-10-06 in +02:00: the window's first local day is the 6th, so the 5th is outside.
        Map<String, Object> w = Map.of("from", "2026-10-05T22:00:00Z", "to", "2026-10-06T22:00:00Z", "timezone", "Europe/Paris");
        Instant later = LOOK.plusSeconds(60);
        assertEquals(1, pick(w, anomaly("calls", "2026-10-06", later)).size());
        assertTrue(pick(w, anomaly("calls", "2026-10-05", later)).isEmpty());
        assertTrue(pick(w, anomaly("calls", "2026-10-07", later)).isEmpty());
    }

    @Test
    void onlyASignalStrictlyNewerThanTheLastLookCounts() {
        assertTrue(pick(WINDOW, anomaly("calls", "2026-10-06", LOOK.minusMillis(1))).isEmpty(), "older: the analyst already saw this data");
        assertTrue(pick(WINDOW, anomaly("calls", "2026-10-06", LOOK)).isEmpty(), "same instant is not 'after you last looked'");
        assertEquals(1, pick(WINDOW, anomaly("calls", "2026-10-06", LOOK.plusMillis(1))).size());
    }

    @Test
    void aSignalFromAnotherPipelineOrAnotherTypeIsIgnored() {
        Instant later = LOOK.plusSeconds(60);
        assertTrue(pick(WINDOW, anomaly("billing", "2026-10-06", later)).isEmpty(), "a Pipeline that does not deliver into the Dataset");
        Signal other = new Signal(null, "job.run.completed", later, Severity.INFO, Ref.of("pipeline", "calls"), null, null, null, null,
                null, "m", Map.of("pipeline", "calls", "day", "2026-10-06"), 1);
        assertTrue(pick(WINDOW, other).isEmpty());
    }

    @Test
    void anOpenWindowAdmitsEveryDayAndAnUnparseableDayIsDropped() {
        Instant later = LOOK.plusSeconds(60);
        assertEquals(2, pick(null, anomaly("calls", "2020-01-01", later), anomaly("calls", "2030-01-01", later)).size());
        assertTrue(pick(null, anomaly("calls", "not-a-day", later)).isEmpty());
    }

    @Test
    void lastLookIsTheNewestOfCreationAndStepsThatReadData() {
        Map<String, Object> header = Map.of("createdAt", "2026-10-01T00:00:00Z");
        List<Map<String, Object>> log = List.of(
                Map.of("step", 1, "kind", "op", "op", "expand", "at", "2026-10-02T00:00:00Z", "read", Map.of("readAt", "2026-10-09T08:00:00Z")),
                Map.of("step", 2, "kind", "op", "op", "connect", "at", "2026-10-10T00:00:00Z", "read", Map.of()));
        assertEquals(Instant.parse("2026-10-10T00:00:00Z"), DataChanges.lastLook(header, log), "a read step counts at its step time or its sealed readAt, the newer");
        assertEquals(Instant.parse("2026-10-09T08:00:00Z"), DataChanges.lastLook(header, log.subList(0, 1)), "a sealed read newer than its step wins");
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), DataChanges.lastLook(header, List.of()), "an empty log falls back to creation");
    }

    @Test
    void aStepThatReadsNothingNeverMovesTheLastLook() {
        Map<String, Object> header = Map.of("createdAt", "2026-10-01T00:00:00Z");
        List<Map<String, Object>> log = List.of(
                Map.of("step", 1, "kind", "op", "op", "expand", "at", "2026-10-02T00:00:00Z", "read", Map.of("readAt", "2026-10-02T00:00:00Z")),
                Map.of("step", 2, "kind", "op", "op", "annotate", "at", "2026-10-05T00:00:00Z"),
                Map.of("step", 3, "kind", "op", "op", "exclude", "at", "2026-10-06T00:00:00Z"),
                Map.of("step", 4, "kind", "undo", "undoes", 1, "at", "2026-10-07T00:00:00Z"));
        assertEquals(Instant.parse("2026-10-02T00:00:00Z"), DataChanges.lastLook(header, log),
                "annotate, exclude and an undo (even of the read step) do not re-silence the warning");
    }

    @Test
    void aCompactYyyyMMddDayFromALiveFeedIsPlacedInTheWindowAndReportedAsIso() {
        Instant later = LOOK.plusSeconds(60);
        var hit = pick(WINDOW, anomaly("calls", "20261006", later));
        assertEquals(1, hit.size(), "the day manifest's compact bucket is a day, not noise");
        assertEquals("2026-10-06", hit.get(0).day());
        assertTrue(pick(WINDOW, anomaly("calls", "20261009", later)).isEmpty(), "a compact day outside the window is still outside");
        assertTrue(pick(WINDOW, anomaly("calls", "202610", later)).isEmpty(), "a month bucket is placed in no window");
    }
}
