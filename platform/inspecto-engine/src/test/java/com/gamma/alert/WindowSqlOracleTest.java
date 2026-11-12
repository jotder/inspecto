package com.gamma.alert;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PA-9b: the window SQL compiler ({@link WindowSql}) against an independent oracle — a plain-Java evaluation of the
 * same semantics over the same rows. Every corpus carries boundary events: at a window's start, one microsecond before
 * its end, at its end (outside it), and around {@code end + lateness} relative to the Watermark.
 */
class WindowSqlOracleTest {

    private static final long S = 1_000_000L;   // one second in µs
    private static final long BASE = 1_794_000_000L * S;   // 2026-11, aligned to the hour
    private Connection duck;

    @BeforeEach
    void open() throws Exception {
        duck = DriverManager.getConnection("jdbc:duckdb:");
    }

    @AfterEach
    void close() throws Exception {
        duck.close();
    }

    /** One event: key {@code a}, counterpart {@code b}, a duration and the event time (epoch µs). */
    record Row(String a, String b, double dur, long ts) {}

    /** One expected breach: key ("" without {@code by}), window start (µs), value. */
    record Hit(String key, long ws, double value) implements Comparable<Hit> {
        public int compareTo(Hit o) {
            int c = Long.compare(ws, o.ws);
            return c != 0 ? c : key.compareTo(o.key);
        }
    }

    // ── the oracle ───────────────────────────────────────────────────────────────

    private static boolean when(Row r, boolean filtered) {
        return !filtered || r.dur < 3;
    }

    private static double measure(String m, List<Row> rows) {
        return switch (m) {
            case "count" -> rows.size();
            case "countDistinct(b)" -> rows.stream().map(Row::b).distinct().count();
            case "sum(dur)" -> rows.stream().mapToDouble(Row::dur).sum();
            case "max(dur)" -> rows.stream().mapToDouble(Row::dur).max().orElseThrow();
            default -> throw new IllegalArgumentException(m);
        };
    }

    /** The semantics the class doc of WindowSql states, evaluated row by row. */
    private static Set<Hit> oracle(long size, long slide, long late, String m, boolean byKey, boolean filtered,
                                   String cmp, double threshold, List<Row> buffer, List<Row> fresh, long watermark) {
        Set<List<Object>> touched = new HashSet<>();
        for (Row r : fresh) {
            if (!when(r, filtered)) continue;
            long latest = Math.floorDiv(r.ts, slide) * slide;
            for (long k = 0; k < size / slide; k++) touched.add(List.of(byKey ? r.a : "", latest - k * slide));
        }
        Set<Hit> out = new TreeSet<>();
        for (List<Object> t : touched) {
            String key = (String) t.get(0);
            long ws = (Long) t.get(1);
            if (!(ws + size + late > watermark)) continue;
            List<Row> in = buffer.stream().filter(r -> when(r, filtered) && (!byKey || r.a.equals(key))
                    && r.ts >= ws && r.ts < ws + size).toList();
            if (in.isEmpty()) continue;
            double v = measure(m, in);
            if ("gt".equals(cmp) ? v > threshold : v >= threshold) out.add(new Hit(key, ws, v));
        }
        return out;
    }

    // ── the compiled SQL ────────────────────────────────────────────────────────

    private static AlertRule rule(String type, long size, long slide, long late, String m, boolean byKey,
                                  boolean filtered, String cmp, double threshold) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("type", type);
        w.put("size", size / S + "s");
        if ("sliding".equals(type)) w.put("slide", slide / S + "s");
        w.put("time", "ts");
        if (late > 0) w.put("lateness", late / S + "s");
        Map<String, Object> raw = new HashMap<>();
        raw.put("name", "r");
        raw.put("dataset", "d");
        raw.put("measure", m);
        if (byKey) raw.put("by", List.of("a"));
        raw.put("comparator", cmp);
        raw.put("threshold", threshold);
        raw.put("window", w);
        if (filtered) raw.put("when", Map.of("kind", "group", "op", "AND", "items",
                List.of(Map.of("kind", "condition", "field", "dur", "operator", "<", "value", "3"))));
        return AlertRule.fromMap(raw);
    }

    private void table(String name, List<Row> rows) throws Exception {
        try (Statement st = duck.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE " + name + " (a VARCHAR, b VARCHAR, dur DOUBLE, ts TIMESTAMP)");
        }
        try (PreparedStatement ps = duck.prepareStatement("INSERT INTO " + name + " VALUES (?, ?, ?, make_timestamp(?))")) {
            for (Row r : rows) {
                ps.setString(1, r.a);
                ps.setString(2, r.b);
                ps.setDouble(3, r.dur);
                ps.setLong(4, r.ts);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private Set<Hit> compiled(AlertRule rule, List<Row> buffer, List<Row> fresh, long watermark) throws Exception {
        table("buf", buffer);
        table("fresh", fresh);
        WindowSql.Compiled c = WindowSql.breaches(rule, "buf", "fresh", watermark);
        Set<Hit> out = new TreeSet<>();
        try (PreparedStatement ps = duck.prepareStatement(c.sql())) {
            for (int i = 0; i < c.params().size(); i++) ps.setObject(i + 1, c.params().get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Hit(rule.by().isEmpty() ? "" : rs.getString(WindowSql.keyColumn(0)),
                        rs.getLong(WindowSql.WINDOW_START), rs.getDouble(WindowSql.VALUE)));
            }
        }
        return out;
    }

    // ── tests ───────────────────────────────────────────────────────────────────

    @Test
    void randomCorporaWithBoundaryEventsAgreeWithTheOracle() throws Exception {
        Random rnd = new Random(20261112);
        String[] measures = {"count", "countDistinct(b)", "sum(dur)", "max(dur)"};
        int hits = 0;
        for (int trial = 0; trial < 300; trial++) {
            boolean sliding = rnd.nextBoolean();
            long slide = (1 + rnd.nextInt(4)) * 10 * S;
            long size = sliding ? slide * (1 + rnd.nextInt(5)) : slide;
            long late = rnd.nextInt(3) * 10 * S;
            String m = measures[rnd.nextInt(measures.length)];
            boolean byKey = rnd.nextBoolean(), filtered = rnd.nextBoolean();
            String cmp = rnd.nextBoolean() ? "gt" : "gte";
            double threshold = 1 + rnd.nextInt(m.startsWith("sum") ? 12 : 4);
            List<Row> buffer = new ArrayList<>(), fresh = new ArrayList<>();
            int n = 5 + rnd.nextInt(25);
            for (int i = 0; i < n; i++) {
                long win = BASE + (rnd.nextInt(8)) * slide;
                // boundary-heavy event times: a window start, one µs before an end, an end, around end + lateness
                long[] at = {win, win + size - 1, win + size, win + size + late - 1, win + size + late,
                        win + rnd.nextInt((int) (slide / S)) * S + rnd.nextInt(1000)};
                Row r = new Row("k" + rnd.nextInt(3), "b" + rnd.nextInt(5), rnd.nextInt(6), at[rnd.nextInt(at.length)]);
                buffer.add(r);
                if (rnd.nextInt(3) == 0) fresh.add(r);
            }
            long watermark = rnd.nextInt(4) == 0 ? Long.MIN_VALUE
                    : buffer.stream().mapToLong(Row::ts).max().orElseThrow() - rnd.nextInt(3) * slide;
            AlertRule rule = rule(sliding ? "sliding" : "tumbling", size, slide, late, m, byKey, filtered, cmp, threshold);
            Set<Hit> expected = oracle(size, slide, late, m, byKey, filtered, cmp, threshold, buffer, fresh, watermark);
            assertEquals(expected, compiled(rule, buffer, fresh, watermark), "trial " + trial + ": " + rule.toMap()
                    + " buffer=" + buffer + " fresh=" + fresh + " watermark=" + watermark);
            hits += expected.size();
        }
        assertTrue(hits > 100, "the corpus must exercise breaches, not only their absence: " + hits);
    }

    @Test
    void aWindowIsHalfOpenAtLoAndHi() throws Exception {
        AlertRule rule = rule("tumbling", 60 * S, 60 * S, 0, "count", true, false, "gte", 2);
        long lo = BASE, hi = BASE + 60 * S;
        Row atLo = new Row("k", "x", 1, lo), beforeHi = new Row("k", "y", 1, hi - 1), atHi = new Row("k", "z", 1, hi);
        assertEquals(Set.of(new Hit("k", lo, 2)),
                compiled(rule, List.of(atLo, beforeHi, atHi), List.of(atLo, beforeHi, atHi), Long.MIN_VALUE),
                "lo and hi - 1µs count in [lo, hi); hi starts the next window, which holds one row");
    }

    @Test
    void aWindowIsEvaluatedUntilItsEndPlusLatenessReachesTheWatermark() throws Exception {
        AlertRule rule = rule("tumbling", 60 * S, 60 * S, 30 * S, "count", true, false, "gte", 2);
        long lo = BASE, closes = BASE + 60 * S + 30 * S;
        List<Row> rows = List.of(new Row("k", "x", 1, lo), new Row("k", "y", 1, lo + S));
        assertEquals(1, compiled(rule, rows, rows, closes - 1).size(), "open one µs before end + lateness");
        assertEquals(Set.of(), compiled(rule, rows, rows, closes), "closed at end + lateness: a late commit raises nothing");
    }

    @Test
    void onlyTouchedKeysAndWindowsAreEvaluated() throws Exception {
        AlertRule rule = rule("tumbling", 60 * S, 60 * S, 0, "count", true, false, "gte", 2);
        List<Row> breachedBefore = List.of(new Row("old", "x", 1, BASE), new Row("old", "y", 1, BASE + S));
        Row other = new Row("new", "z", 1, BASE + 2 * S);
        List<Row> buffer = new ArrayList<>(breachedBefore);
        buffer.add(other);
        assertEquals(Set.of(), compiled(rule, buffer, List.of(other), Long.MIN_VALUE),
                "key 'old' breaches in the buffer but this commit did not touch it");
        Row nextWindow = new Row("old", "w", 1, BASE + 61 * S);
        buffer.add(nextWindow);
        assertEquals(Set.of(), compiled(rule, buffer, List.of(nextWindow), Long.MIN_VALUE),
                "the commit touched key 'old' in the next window only, which holds one row");
    }

    @Test
    void aSlidingWindowCountsAnEventInEveryWindowItFallsIn() throws Exception {
        AlertRule rule = rule("sliding", 30 * S, 10 * S, 0, "count", false, false, "gte", 2);
        Row r1 = new Row("k", "x", 1, BASE + 25 * S), r2 = new Row("k", "y", 1, BASE + 31 * S);
        assertEquals(Set.of(new Hit("", BASE + 10 * S, 2), new Hit("", BASE + 20 * S, 2)),
                compiled(rule, List.of(r1, r2), List.of(r2), Long.MIN_VALUE),
                "r2 falls in the windows starting at 10s, 20s and 30s; r1 shares the first two");
    }

    @Test
    void noAuthoredValueReachesTheStatementText() {
        Map<String, Object> raw = new HashMap<>(Map.of("name", "r", "dataset", "d", "measure", "count", "comparator", "gte",
                "threshold", 31337, "by", List.of("a"),
                "window", Map.of("type", "tumbling", "size", "10m", "time", "ts"),
                "when", Map.of("kind", "group", "op", "AND", "items",
                        List.of(Map.of("kind", "condition", "field", "b", "operator", "=", "value", "x'); DROP TABLE buf; --")))));
        WindowSql.Compiled c = WindowSql.breaches(AlertRule.fromMap(raw), "buf", "fresh", 42);
        assertFalse(c.sql().contains("DROP"), c.sql());
        assertFalse(c.sql().contains("31337"), "the threshold is a parameter");
        assertTrue(c.params().contains("x'); DROP TABLE buf; --"));
        assertTrue(c.params().contains(42L), "the Watermark is a parameter");
    }
}
