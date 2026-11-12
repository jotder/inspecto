package com.gamma.alert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PA-9c: {@link WindowedRuleEvaluator} running the {@code sequence} and {@code absence} Patterns over committed
 * Parquet — across commits, effectively once, late events, the clock floor of a silent Stream, and a restart.
 */
class WindowedPatternEvaluatorTest {

    private static final long T0 = 1_794_000_000_000L;   // epoch ms, aligned to the hour
    private static final long MIN = 60_000L;

    /** One event: key, event type, amount, event time (ms) and its Kafka offset. */
    record Ev(String key, String type, double amount, long tsMs, long offset) {}

    private static int files;

    private static Path parquet(Path dir, Ev... rows) throws Exception {
        Files.createDirectories(dir);
        Path f = dir.resolve("part-" + (files++) + ".parquet");
        StringBuilder values = new StringBuilder();
        for (Ev e : rows) {
            if (values.length() > 0) values.append(", ");
            values.append("('").append(e.key).append("', '").append(e.type).append("', ").append(e.amount)
                    .append(", make_timestamp(").append(e.tsMs * 1000).append("), 'ev', 0, ").append(e.offset).append(")");
        }
        try (Connection d = DriverManager.getConnection("jdbc:duckdb:"); Statement st = d.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + values + ") AS t(msisdn, event_type, amount, event_ts, topic, "
                    + "\"partition\", \"offset\")) TO '" + f.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        return f;
    }

    private static Map<String, Object> eq(String field, String value) {
        return Map.of("kind", "group", "op", "AND", "items",
                List.of(Map.of("kind", "condition", "field", field, "operator", "=", "value", value)));
    }

    /** SIM swap, then a transfer within 30 minutes, per MSISDN; lateness 2m. */
    private static AlertRule swapThenTransfer() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("name", "swap-then-transfer");
        raw.put("dataset", "telco_events");
        raw.put("by", List.of("msisdn"));
        raw.put("severity", "WARNING");
        raw.put("window", Map.of("type", "tumbling", "size", "30m", "time", "event_ts", "lateness", "2m",
                "distinctOn", List.of("topic", "partition", "offset")));
        raw.put("pattern", Map.of("type", "sequence", "steps", List.of(eq("event_type", "sim_swap"), eq("event_type", "transfer"))));
        return AlertRule.fromMap(raw);
    }

    /** A silent 5-minute tumbling Window, per key or for the whole Stream; lateness 1m. */
    private static AlertRule silent(boolean byKey) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("name", "feed-silent");
        raw.put("dataset", "telco_events");
        if (byKey) raw.put("by", List.of("msisdn"));
        raw.put("window", new LinkedHashMap<>(Map.of("type", "tumbling", "size", "5m", "time", "event_ts", "lateness", "1m")));
        raw.put("pattern", Map.of("type", "absence"));
        return AlertRule.fromMap(raw);
    }

    private static AlertService alerts() {
        return new AlertService(List.of(), AlertWindowTest.noPipelines(), null);
    }

    private static WindowedRuleEvaluator evaluator(Path root, AlertRule rule, FiredWindows fired, AlertService alerts)
            throws Exception {
        return new WindowedRuleEvaluator("telco_events", List.of(rule), List.of(root), root.resolve(".spill"), fired, alerts);
    }

    // ── sequence ────────────────────────────────────────────────────────────────

    @Test
    void aSequenceAcrossTwoCommitsRaisesOnceAndAReplayRaisesNothing(@TempDir Path root) throws Exception {
        AlertService alerts = alerts();
        Path db = root.resolve("db");
        try (FiredWindows fired = new FiredWindows(null); var ev = evaluator(root, swapThenTransfer(), fired, alerts)) {
            var first = ev.commit(List.of(parquet(db, new Ev("m1", "sim_swap", 0, T0, 1), new Ev("m2", "transfer", 900, T0, 2))));
            assertTrue(first.breaches().isEmpty(), "A alone, and another key's B before any A: " + first);

            Path second = parquet(db, new Ev("m1", "transfer", 900, T0 + 10 * MIN, 3), new Ev("m2", "sim_swap", 0, T0 + MIN, 4));
            var out = ev.commit(List.of(second));
            assertEquals(1, out.newlyFired().size(), out.toString());
            var b = out.newlyFired().get(0);
            assertEquals(Map.of("msisdn", "m1"), b.key());
            assertEquals(T0, b.windowStartMs(), "the fired window start is A's event time");
            assertEquals(T0 + 30 * MIN, b.windowEndMs());
            assertEquals(T0, b.firstEventMs());
            assertEquals(T0 + 10 * MIN, b.lastEventMs());
            List<Map<String, Object>> raised = alerts.recent(10);
            assertEquals(1, raised.size());
            assertEquals("sequence", alerts.openAlerts().get(0).attributes().get("lastWindow.pattern"));

            var replay = ev.commit(List.of(parquet(db, new Ev("m1", "transfer", 900, T0 + 10 * MIN, 3))));
            assertTrue(replay.breaches().isEmpty(), "the same offset re-delivered is not buffered twice: " + replay);
            var second2 = ev.commit(List.of(parquet(db, new Ev("m1", "transfer", 500, T0 + 20 * MIN, 5))));
            assertEquals(1, second2.breaches().size(), "a second B for the same A still matches");
            assertTrue(second2.newlyFired().isEmpty(), "but (rule, key, A) has fired: " + second2);
            assertEquals(1, alerts.recent(10).size());
        }
    }

    @Test
    void aSequenceIsJudgedOnEventTimeNotArrival(@TempDir Path root) throws Exception {
        Path db = root.resolve("db");
        try (FiredWindows fired = new FiredWindows(null); var ev = evaluator(root, swapThenTransfer(), fired, alerts())) {
            // B arrives first, A later in a second commit, A's event time before B's: still A then B
            ev.commit(List.of(parquet(db, new Ev("m1", "transfer", 900, T0 + 5 * MIN, 1))));
            var out = ev.commit(List.of(parquet(db, new Ev("m1", "sim_swap", 0, T0, 2))));
            assertEquals(1, out.newlyFired().size(), out.toString());
            assertEquals(T0, out.newlyFired().get(0).windowStartMs());
        }
    }

    @Test
    void aBAfterAsWindowClosedRaisesNothingAndAStepOlderThanEveryOpenWindowIsLate(@TempDir Path root) throws Exception {
        AlertService alerts = alerts();
        Path db = root.resolve("db");
        try (FiredWindows fired = new FiredWindows(null); var ev = evaluator(root, swapThenTransfer(), fired, alerts)) {
            ev.commit(List.of(parquet(db, new Ev("m1", "sim_swap", 0, T0, 1))));
            // another key's traffic moves the Watermark exactly to A's close: T0 + 30m + 2m
            ev.commit(List.of(parquet(db, new Ev("m9", "call", 0, T0 + 32 * MIN, 2))));
            var out = ev.commit(List.of(parquet(db, new Ev("m1", "transfer", 900, T0 + 29 * MIN, 3))));
            assertTrue(out.breaches().isEmpty(), "A's Window closed at the Watermark: " + out);
            assertEquals(0L, out.lateEvents().get("swap-then-transfer"), "the B itself could still follow a later A");
            // an A exactly at Watermark - size - lateness: its Window closed exactly at the Watermark
            var old = ev.commit(List.of(parquet(db, new Ev("m3", "sim_swap", 0, T0, 4))));
            assertEquals(1L, old.lateEvents().get("swap-then-transfer"), "an A whose Window closed before it arrived");
            assertTrue(old.breaches().isEmpty(), old.toString());
            assertTrue(alerts.recent(10).isEmpty());
        }
    }

    @Test
    void aBJustBeforeAsCloseStillRaises(@TempDir Path root) throws Exception {
        Path db = root.resolve("db");
        try (FiredWindows fired = new FiredWindows(null); var ev = evaluator(root, swapThenTransfer(), fired, alerts())) {
            ev.commit(List.of(parquet(db, new Ev("m1", "sim_swap", 0, T0, 1))));
            ev.commit(List.of(parquet(db, new Ev("m9", "call", 0, T0 + 32 * MIN - 1, 2))));   // one ms short of the close
            var out = ev.commit(List.of(parquet(db, new Ev("m1", "transfer", 900, T0 + 30 * MIN, 3))));
            assertEquals(1, out.newlyFired().size(), out.toString());
            assertEquals(0L, out.lateEvents().get("swap-then-transfer"));
        }
    }

    @Test
    void manyAsForOneKeyRaiseOneAlertAndRecordEach(@TempDir Path root) throws Exception {
        AlertService alerts = alerts();
        Path db = root.resolve("db");
        try (FiredWindows fired = new FiredWindows(null); var ev = evaluator(root, swapThenTransfer(), fired, alerts)) {
            var out = ev.commit(List.of(parquet(db, new Ev("m1", "sim_swap", 0, T0, 1), new Ev("m1", "sim_swap", 0, T0 + MIN, 2),
                    new Ev("m1", "transfer", 900, T0 + 2 * MIN, 3))));
            assertEquals(2, out.newlyFired().size());
            assertEquals(1, alerts.recent(10).size(), "one Alert per key per commit");
            assertEquals("2", alerts.openAlerts().get(0).attributes().get("lastWindow.windows"), "both As, on one Alert");
            assertEquals(2, fired.size());
        }
    }

    // ── absence ─────────────────────────────────────────────────────────────────

    @Test
    void aKeyThatFallsSilentIsAbsentOnceTheWatermarkClosesTheWindow(@TempDir Path root) throws Exception {
        AlertService alerts = alerts();
        Path db = root.resolve("db");
        try (FiredWindows fired = new FiredWindows(null); var ev = evaluator(root, silent(true), fired, alerts)) {
            ev.commit(List.of(parquet(db, new Ev("sw1", "hb", 0, T0 + 2 * MIN, 1), new Ev("sw2", "hb", 0, T0 + 2 * MIN, 2))));
            // sw2 keeps talking; sw1 is silent in [T0+5m, T0+10m), which closes at T0+11m
            var open = ev.commit(List.of(parquet(db, new Ev("sw2", "hb", 0, T0 + 7 * MIN, 3),
                    new Ev("sw2", "hb", 0, T0 + 11 * MIN - 1, 4))));
            assertTrue(open.breaches().isEmpty(), "the Window is not closed one ms before hi + lateness: " + open);
            var closed = ev.commit(List.of(parquet(db, new Ev("sw2", "hb", 0, T0 + 11 * MIN, 5))));
            assertEquals(1, closed.newlyFired().size(), closed.toString());
            var b = closed.newlyFired().get(0);
            assertEquals(Map.of("msisdn", "sw1"), b.key());
            assertEquals(T0 + 5 * MIN, b.windowStartMs());
            assertEquals(T0 + 10 * MIN, b.windowEndMs());
            assertEquals(0, b.value());
            assertEquals(T0 + 2 * MIN, b.lastEventMs(), "last seen");
            assertEquals("absence", alerts.openAlerts().get(0).attributes().get("lastWindow.pattern"));

            // sw2 then falls silent in [T0+15m, T0+20m); sw1 stays silent but is not raised again
            var later = ev.commit(List.of(parquet(db, new Ev("sw2", "hb", 0, T0 + 30 * MIN, 6))));
            assertEquals(List.of(Map.of("msisdn", "sw2")), later.newlyFired().stream().map(WindowedRuleEvaluator.Breach::key).toList(),
                    "one Alert per silence, not one per silent Window: " + later);
            // PA-9d: still the rule's one Alert - sw1's closed Window healed it off, sw2's silence is on it now
            assertEquals(1, alerts.recent(10).size());
            assertEquals(java.time.Instant.ofEpochMilli(T0 + 15 * MIN).toString(),
                    alerts.openAlerts().get(0).attributes().get("lastWindow.windowStart"));
        }
    }

    @Test
    void theClockFloorClosesTheWindowOfAStreamThatSendsNothingAtAll(@TempDir Path root) throws Exception {
        AlertService alerts = alerts();
        Path db = root.resolve("db");
        try (FiredWindows fired = new FiredWindows(null); var ev = evaluator(root, silent(false), fired, alerts)) {
            ev.commit(List.of(parquet(db, new Ev("sw1", "hb", 0, T0 + MIN, 1))));
            // no commit ever again: only the clock moves. The Window [T0+5m, T0+10m) closes at T0+11m.
            assertTrue(ev.tick(T0 + 11 * MIN - 1).breaches().isEmpty(), "one ms before hi + lateness");
            var out = ev.tick(T0 + 11 * MIN);
            assertEquals(1, out.newlyFired().size(), out.toString());
            assertEquals(Map.of(), out.newlyFired().get(0).key(), "the whole Stream");
            assertEquals(T0 + 5 * MIN, out.newlyFired().get(0).windowStartMs());
            assertEquals(1, alerts.recent(10).size());
            assertTrue(ev.tick(T0 + 60 * MIN).breaches().isEmpty(), "judged once");
            assertEquals(1, alerts.recent(10).size());
            assertEquals((T0 + 60 * MIN) * 1000, ev.judgedUs());
            assertEquals((T0 + MIN) * 1000, ev.watermarkUs(), "the clock never moves the event-time Watermark");
        }
    }

    @Test
    void aTickJudgesNoMeasureOrSequenceRule(@TempDir Path root) throws Exception {
        AlertService alerts = alerts();
        Path db = root.resolve("db");
        try (FiredWindows fired = new FiredWindows(null); var ev = evaluator(root, swapThenTransfer(), fired, alerts)) {
            ev.commit(List.of(parquet(db, new Ev("m1", "sim_swap", 0, T0, 1))));
            assertEquals(1, ev.commit(List.of(parquet(db, new Ev("m1", "transfer", 900, T0 + MIN, 2)))).newlyFired().size());
            var tick = ev.tick(T0 + 10 * MIN);
            assertTrue(tick.breaches().isEmpty(), "a tick re-reads no sequence: " + tick);
            assertTrue(tick.lateEvents().isEmpty());
            assertEquals(1, alerts.recent(10).size());
        }
    }

    @Test
    void aTickOnAnEvaluatorThatNeverBufferedARowRaisesNothing(@TempDir Path root) throws Exception {
        AlertService alerts = alerts();
        try (FiredWindows fired = new FiredWindows(null); var ev = evaluator(root, silent(false), fired, alerts)) {
            assertTrue(ev.tick(T0 + 60 * MIN).breaches().isEmpty());
            assertTrue(alerts.recent(10).isEmpty(), "a Stream that never sent an event is a freshness rule's to catch");
        }
    }

    @Test
    void anEventLandingInAWindowTheClockJudgedSilentIsLate(@TempDir Path root) throws Exception {
        AlertService alerts = alerts();
        Path db = root.resolve("db");
        try (FiredWindows fired = new FiredWindows(null); var ev = evaluator(root, silent(true), fired, alerts)) {
            ev.commit(List.of(parquet(db, new Ev("sw1", "hb", 0, T0 + MIN, 1))));
            assertEquals(1, ev.tick(T0 + 11 * MIN).newlyFired().size());
            var late = ev.commit(List.of(parquet(db, new Ev("sw1", "hb", 0, T0 + 7 * MIN, 2))));
            assertEquals(1L, late.lateEvents().get("feed-silent"), "it fell in a Window already judged: " + late);
            assertTrue(late.newlyFired().isEmpty(), late.toString());
            assertEquals(1, alerts.recent(10).size(), "the Alert stands; nothing is retracted or raised again");
        }
    }

    @Test
    void aRestartDoesNotJudgeAgainWhatClosedBeforeTheLoadedWatermark(@TempDir Path root) throws Exception {
        Path db = root.resolve("db");
        // sw1's silence [T0+5m, T0+10m) closed at T0+11m, before the restart; its row is still buffered (trimmed at T0+12m)
        parquet(db, new Ev("sw1", "hb", 0, T0 + MIN, 1));
        parquet(db, new Ev("sw2", "hb", 0, T0 + 11 * MIN + 30_000, 2));
        AlertService alerts = alerts();
        try (FiredWindows fired = new FiredWindows(null); var ev = evaluator(root, silent(true), fired, alerts)) {
            ev.load(WindowedRuleEvaluator.committedFiles(db));
            assertEquals(2, ev.bufferedRows());
            assertEquals((T0 + 11 * MIN + 30_000) * 1000, ev.judgedUs(), "the load counts closed Windows as judged");
            assertTrue(ev.tick(T0 + 12 * MIN - 1).newlyFired().isEmpty(), "sw1's old silence closed before the restart");
            assertTrue(alerts.recent(10).isEmpty());
            // after the restart, sw2 falls silent: [T0+15m, T0+20m) closes at T0+21m
            assertEquals(List.of(Map.of("msisdn", "sw2")), ev.tick(T0 + 21 * MIN).newlyFired().stream()
                    .map(WindowedRuleEvaluator.Breach::key).toList());
        }
    }
}
