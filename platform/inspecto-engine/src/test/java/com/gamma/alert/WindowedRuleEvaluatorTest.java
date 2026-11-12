package com.gamma.alert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PA-9b: {@link WindowedRuleEvaluator} over committed Parquet files — the window buffer, effectively-once Alerts
 * (the fired table), replays, {@code distinctOn}, late events, the boot rebuild and the committed-files listing.
 */
class WindowedRuleEvaluatorTest {

    private static final long T0 = 1_794_000_000_000L;   // epoch ms, aligned to the hour

    /** One CDR: a_number calls b_number for duration_s at event_ts, read from (topic, partition, offset). */
    record Cdr(String a, String b, double dur, long tsMs, long offset) {}

    private static int files;

    private static Path parquet(Path dir, List<Cdr> rows) throws Exception {
        Files.createDirectories(dir);
        Path f = dir.resolve("part-" + (files++) + ".parquet");
        StringBuilder values = new StringBuilder();
        for (Cdr c : rows) {
            if (values.length() > 0) values.append(", ");
            values.append("('").append(c.a).append("', '").append(c.b).append("', ").append(c.dur)
                    .append(", make_timestamp(").append(c.tsMs * 1000).append("), 'cdr', 0, ").append(c.offset).append(")");
        }
        try (Connection d = DriverManager.getConnection("jdbc:duckdb:"); Statement st = d.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + values + ") AS t(a_number, b_number, duration_s, event_ts, topic, "
                    + "\"partition\", \"offset\")) TO '" + f.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        return f;
    }

    /** {@code n} short calls from {@code a} to distinct B-numbers, one second apart from {@code startMs}. */
    private static List<Cdr> burst(String a, int n, long startMs, long firstOffset) {
        List<Cdr> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new Cdr(a, a + "-b" + i, 1, startMs + i * 1000L, firstOffset + i));
        return out;
    }

    private static AlertRule wangiri(Map<String, Object> window, int threshold) {
        Map<String, Object> w = new HashMap<>(Map.of("type", "sliding", "size", "10m", "slide", "1m", "time", "event_ts"));
        w.putAll(window);
        Map<String, Object> raw = new HashMap<>();
        raw.put("name", "wangiri-burst");
        raw.put("dataset", "voice_cdr");
        raw.put("measure", "countDistinct(b_number)");
        raw.put("by", List.of("a_number"));
        raw.put("comparator", "gte");
        raw.put("threshold", threshold);
        raw.put("severity", "WARNING");
        raw.put("when", Map.of("kind", "group", "op", "AND", "items",
                List.of(Map.of("kind", "condition", "field", "duration_s", "operator", "<", "value", "3"))));
        raw.put("window", w);
        return AlertRule.fromMap(raw);
    }

    private static AlertService alerts() {
        return new AlertService(List.of(), AlertWindowTest.noPipelines(), null);
    }

    private static WindowedRuleEvaluator evaluator(Path root, AlertRule rule, FiredWindows fired, AlertService alerts)
            throws Exception {
        return new WindowedRuleEvaluator("voice_cdr", List.of(rule), List.of(root), root.resolve(".spill"), fired, alerts);
    }

    @Test
    void aBurstRaisesOneAlertPerKeyAndWindowAndNeverTwice(@TempDir Path root) throws Exception {
        AlertRule rule = wangiri(Map.of("type", "tumbling", "slide", "10m"), 5);
        AlertService alerts = alerts();
        try (FiredWindows fired = new FiredWindows(null); var ev = evaluator(root, rule, fired, alerts)) {
            Path db = root.resolve("db");
            var first = ev.commit(List.of(parquet(db, burst("A1", 5, T0, 0))));
            assertEquals(1, first.newlyFired().size(), first.toString());
            var b = first.newlyFired().get(0);
            assertEquals(Map.of("a_number", "A1"), b.key());
            assertEquals(T0, b.windowStartMs());
            assertEquals(T0 + 600_000, b.windowEndMs());
            assertEquals(5, b.value());
            assertEquals(T0, b.firstEventMs());
            assertEquals(T0 + 4000, b.lastEventMs());
            List<Map<String, Object>> raised = alerts.recent(10);
            assertEquals(1, raised.size());
            assertTrue(String.valueOf(raised.get(0).get("message")).contains("a_number=A1"), raised.toString());

            var more = ev.commit(List.of(parquet(db, burst("A1", 3, T0 + 10_000, 100).stream()
                    .map(c -> new Cdr(c.a, "x" + c.b, 1, c.tsMs, c.offset)).toList())));
            assertTrue(more.newlyFired().isEmpty(), "the same (rule, key, window) already fired: " + more);
            assertEquals(1, more.breaches().size(), "it still breaches, and is reported as already fired");
            assertEquals(8, more.breaches().get(0).value());
            assertEquals(1, alerts.recent(10).size(), "no second Alert");
            assertEquals(1, fired.size());
        }
    }

    @Test
    void aReplayedSliceWithDistinctOnDoesNotInflateACount(@TempDir Path root) throws Exception {
        Path db = root.resolve("db");
        List<Cdr> four = burst("A1", 4, T0, 0);
        Path slice = parquet(db, four);
        Path replay = parquet(db, four);   // the lane re-delivers the same offsets in a new file
        AlertRule withIdentity = wangiri(Map.of("type", "tumbling", "slide", "10m",
                "distinctOn", List.of("topic", "partition", "offset")), 5);
        try (FiredWindows fired = new FiredWindows(null); var ev = evaluator(root, withIdentity, fired, alerts())) {
            ev.commit(List.of(slice));
            var out = ev.commit(List.of(replay));
            assertTrue(out.breaches().isEmpty(), "4 distinct events, re-delivered, are still 4: " + out);
            assertEquals(4, ev.bufferedRows());
        }
        // the same replay without an identity counts each row: countDistinct(b_number) stays 4, but count does not
        Map<String, Object> raw = new HashMap<>(withIdentity.toMap());
        raw.put("measure", "count");
        raw.put("window", Map.of("type", "tumbling", "size", "10m", "time", "event_ts"));
        try (FiredWindows fired = new FiredWindows(null); var ev = evaluator(root, AlertRule.fromMap(raw), fired, alerts())) {
            ev.commit(List.of(slice));
            assertEquals(1, ev.commit(List.of(replay)).newlyFired().size(), "without distinctOn the replay counts 8 rows");
        }
    }

    @Test
    void anEventLaterThanTheLatenessIsKeptButAlertsNothingAndIsCounted(@TempDir Path root) throws Exception {
        AlertRule rule = wangiri(Map.of("type", "tumbling", "slide", "10m", "lateness", "1m"), 3);
        try (FiredWindows fired = new FiredWindows(null); var ev = evaluator(root, rule, fired, alerts())) {
            Path db = root.resolve("db");
            ev.commit(List.of(parquet(db, burst("A1", 2, T0, 0))));
            // the Watermark moves to T0 + 11m: the first window [T0, T0+10m) closes at T0 + 11m
            ev.commit(List.of(parquet(db, List.of(new Cdr("A9", "z", 1, T0 + 660_000, 10)))));
            var late = ev.commit(List.of(parquet(db, List.of(new Cdr("A1", "late", 1, T0 + 5000, 11)))));
            assertTrue(late.breaches().isEmpty(), "the third call is in a closed window: " + late);
            assertEquals(1L, late.lateEvents().get(rule.name()));
            // one second inside the lateness it would still count
            AlertRule lenient = wangiri(Map.of("type", "tumbling", "slide", "10m", "lateness", "1m"), 3);
            try (FiredWindows f2 = new FiredWindows(null); var ev2 = evaluator(root, lenient, f2, alerts())) {
                ev2.commit(List.of(parquet(db, burst("A1", 2, T0, 0))));
                ev2.commit(List.of(parquet(db, List.of(new Cdr("A9", "z", 1, T0 + 659_000, 10)))));
                var inTime = ev2.commit(List.of(parquet(db, List.of(new Cdr("A1", "late", 1, T0 + 5000, 11)))));
                assertEquals(1, inTime.newlyFired().size(), "end + lateness is one second ahead of the Watermark: " + inTime);
                assertEquals(0L, inTime.lateEvents().get(rule.name()));
            }
        }
    }

    @Test
    void theBufferIsTrimmedPastSizePlusLateness(@TempDir Path root) throws Exception {
        AlertRule rule = wangiri(Map.of("type", "tumbling", "slide", "10m", "lateness", "1m"), 50);
        try (FiredWindows fired = new FiredWindows(null); var ev = evaluator(root, rule, fired, alerts())) {
            Path db = root.resolve("db");
            ev.commit(List.of(parquet(db, burst("A1", 3, T0, 0))));
            assertEquals(3, ev.bufferedRows());
            ev.commit(List.of(parquet(db, List.of(new Cdr("A2", "z", 1, T0 + 661_500, 9)))));
            assertEquals(2, ev.bufferedRows(), "a row goes once time + size + lateness <= Watermark (T0+661.5s): "
                    + "T0 and T0+1s go, T0+2s and the new row stay");
        }
    }

    @Test
    void aRestartRebuildsTheBufferAndTheFiredTableStopsARefire(@TempDir Path root) throws Exception {
        AlertRule rule = wangiri(Map.of(), 5);
        Path db = root.resolve("db");
        Path firedDb = root.resolve("state").resolve("fired.duckdb");
        Path slice = parquet(db, burst("A1", 5, T0, 0));
        try (FiredWindows fired = new FiredWindows(firedDb); var ev = evaluator(root, rule, fired, alerts())) {
            assertFalse(ev.commit(List.of(slice)).newlyFired().isEmpty());
        }
        AlertService afterRestart = alerts();
        try (FiredWindows fired = new FiredWindows(firedDb); var ev = evaluator(root, rule, fired, afterRestart)) {
            ev.load(WindowedRuleEvaluator.committedFiles(db));
            assertEquals(5, ev.bufferedRows());
            var replay = ev.commit(List.of(slice));   // the at-least-once lane re-delivers the last slice
            assertTrue(replay.newlyFired().isEmpty(), "every window already fired before the restart: " + replay);
            assertTrue(afterRestart.recent(10).isEmpty());
            var next = ev.commit(List.of(parquet(db, burst("A1", 1, T0 + 5000, 5).stream()
                    .map(c -> new Cdr(c.a, "new", 1, c.tsMs, c.offset)).toList())));
            assertTrue(next.newlyFired().isEmpty(), "rows rebuilt at boot count towards the window: " + next);
            assertFalse(next.breaches().isEmpty());
        }
    }

    @Test
    void theCommittedListingNeverReadsAStagingFolder(@TempDir Path root) throws Exception {
        Path db = root.resolve("db");
        Path committed = parquet(db.resolve("event_date=2026-11-12"), burst("A1", 1, T0, 0));
        parquet(db.resolve(".staging").resolve("c-42"), burst("A2", 1, T0, 1));
        Files.writeString(db.resolve("notes.txt"), "not data");
        assertEquals(List.of(committed), WindowedRuleEvaluator.committedFiles(db));
        assertEquals(List.of(), WindowedRuleEvaluator.committedFiles(root.resolve("absent")));
    }

    @Test
    void aStormAboveTheCapRaisesOneAlert(@TempDir Path root) throws Exception {
        Map<String, Object> raw = new HashMap<>(wangiri(Map.of("type", "tumbling", "slide", "10m"), 2).toMap());
        raw.put("stormCap", 2);
        AlertRule rule = AlertRule.fromMap(raw);
        AlertService alerts = alerts();
        try (FiredWindows fired = new FiredWindows(null); var ev = evaluator(root, rule, fired, alerts)) {
            List<Cdr> rows = new ArrayList<>();
            for (int k = 0; k < 3; k++) rows.addAll(burst("A" + k, 2, T0, k * 10L));
            var out = ev.commit(List.of(parquet(root.resolve("db"), rows)));
            assertEquals(3, out.newlyFired().size(), "three keys breached and are recorded as fired");
            List<Map<String, Object>> raised = alerts.recent(10);
            assertEquals(1, raised.size(), "one storm Alert stands for them: " + raised);
            assertTrue(String.valueOf(raised.get(0).get("message")).contains("3 keys breached"), raised.toString());
            assertEquals(3, fired.size());
        }
    }

    @Test
    void oneEvaluatorServesRulesThatShareTheTimeColumnAndIdentity(@TempDir Path root) throws Exception {
        AlertRule a = wangiri(Map.of(), 5);
        Map<String, Object> raw = new HashMap<>(a.toMap());
        raw.put("name", "other");
        raw.put("window", Map.of("type", "tumbling", "size", "5m", "time", "arrived_ts"));
        AlertRule other = AlertRule.fromMap(raw);
        try (FiredWindows fired = new FiredWindows(null)) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new WindowedRuleEvaluator(
                    "voice_cdr", List.of(a, other), List.of(root), null, fired, alerts()));
            assertTrue(e.getMessage().contains("share the event-time column"), e.getMessage());
        }
    }
}
