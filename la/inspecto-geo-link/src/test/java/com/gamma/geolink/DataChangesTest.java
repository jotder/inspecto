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
    void lastLookIsTheNewestOfCreationStepsAndSealedReads() {
        Map<String, Object> header = Map.of("createdAt", "2026-10-01T00:00:00Z");
        List<Map<String, Object>> log = List.of(
                Map.of("step", 1, "at", "2026-10-02T00:00:00Z", "read", Map.of("readAt", "2026-10-09T08:00:00Z")),
                Map.of("step", 2, "at", "2026-10-03T00:00:00Z"));
        assertEquals(Instant.parse("2026-10-09T08:00:00Z"), DataChanges.lastLook(header, log), "a sealed read newer than every step wins");
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), DataChanges.lastLook(header, List.of()), "an empty log falls back to creation");
    }
}
