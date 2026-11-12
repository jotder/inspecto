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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PA-9c: the Pattern SQL ({@link WindowSql#sequence}, {@link WindowSql#absence}) against an independent oracle — a
 * plain-Java evaluation of the semantics {@link AlertPattern} states, over the same rows. Every corpus carries the
 * boundary events: B exactly at A, exactly {@code within} after A and one µs past it; an A whose Window closes
 * exactly at the Watermark; an event exactly at an absence Window's start, one µs before its end and at its end; and
 * frontiers exactly at and one µs either side of {@code hi + lateness}.
 */
class PatternSqlOracleTest {

    private static final long S = 1_000_000L;   // one second in µs
    private static final long BASE = 1_794_000_000L * S;   // aligned to the hour
    private Connection duck;

    @BeforeEach
    void open() throws Exception {
        duck = DriverManager.getConnection("jdbc:duckdb:");
    }

    @AfterEach
    void close() throws Exception {
        duck.close();
    }

    /** One event: key, kind, amount, event time (µs) and the number of the commit that buffered it. */
    record Row(String key, String kind, double amount, long ts, long seq) {}

    /** One expected hit: key ("" without {@code by}), window start (µs), value, first and last event (µs). */
    record Hit(String key, long ws, double value, long first, long last) implements Comparable<Hit> {
        public int compareTo(Hit o) {
            int c = Long.compare(ws, o.ws);
            return c != 0 ? c : key.compareTo(o.key);
        }
    }

    private static Map<String, Object> cond(String field, String op, Object value) {
        return Map.of("kind", "condition", "field", field, "operator", op, "value", String.valueOf(value));
    }

    private static Map<String, Object> group(Map<String, Object> c) {
        return Map.of("kind", "group", "op", "AND", "items", List.of(c));
    }

    // ── the oracles ─────────────────────────────────────────────────────────────

    /** Step A: kind = 'A'. Step B: amount >= 50 — an A row with a large amount matches both, and must not self-pair. */
    private static boolean stepA(Row r) {
        return r.kind.equals("A");
    }

    private static boolean stepB(Row r) {
        return r.amount >= 50;
    }

    private static Set<Hit> sequenceOracle(long within, long late, boolean byKey, List<Row> buffer, long seq, long watermark) {
        Map<List<Object>, long[]> acc = new TreeMap<>((x, y) -> {
            int c = Long.compare((Long) x.get(1), (Long) y.get(1));
            return c != 0 ? c : ((String) x.get(0)).compareTo((String) y.get(0));
        });
        for (int i = 0; i < buffer.size(); i++) {
            Row a = buffer.get(i);
            if (!stepA(a) || !(a.ts + within + late > watermark)) continue;
            for (int j = 0; j < buffer.size(); j++) {
                Row b = buffer.get(j);
                if (i == j || !stepB(b) || (byKey && !a.key.equals(b.key))) continue;
                if (b.ts < a.ts || b.ts > a.ts + within) continue;
                if (a.seq != seq && b.seq != seq) continue;
                long[] v = acc.computeIfAbsent(List.of(byKey ? a.key : "", a.ts), k -> new long[]{0, Long.MAX_VALUE});
                v[0]++;
                v[1] = Math.min(v[1], b.ts);
            }
        }
        Set<Hit> out = new TreeSet<>();
        acc.forEach((k, v) -> out.add(new Hit((String) k.get(0), (Long) k.get(1), v[0], (Long) k.get(1), v[1])));
        return out;
    }

    /** {@code when}: amount < 100 when filtered. */
    private static Set<Hit> absenceOracle(long size, long slide, long late, boolean byKey, boolean filtered,
                                          List<Row> buffer, long from, long to) {
        List<Row> counted = buffer.stream().filter(r -> !filtered || r.amount < 100).toList();
        Map<List<Object>, Long> lastSeen = new HashMap<>();
        for (Row r : counted)
            lastSeen.merge(List.of(byKey ? r.key : "", Math.floorDiv(r.ts, slide) * slide + slide), r.ts, Math::max);
        Set<Hit> out = new TreeSet<>();
        lastSeen.forEach((k, last) -> {
            String key = (String) k.get(0);
            long ws = (Long) k.get(1);
            long close = ws + size + late;
            if (!(close > from && close <= to)) return;
            boolean silent = counted.stream().noneMatch(r -> (!byKey || r.key.equals(key)) && r.ts >= ws && r.ts < ws + size);
            if (silent) out.add(new Hit(key, ws, 0, last, last));
        });
        return out;
    }

    // ── the compiled SQL ────────────────────────────────────────────────────────

    private static AlertRule sequenceRule(long within, long late, boolean byKey) {
        Map<String, Object> w = new LinkedHashMap<>(Map.of("type", "tumbling", "size", within / S + "s", "time", "ts"));
        if (late > 0) w.put("lateness", late / S + "s");
        Map<String, Object> raw = new HashMap<>();
        raw.put("name", "seq");
        raw.put("dataset", "d");
        if (byKey) raw.put("by", List.of("k"));
        raw.put("window", w);
        raw.put("pattern", Map.of("type", "sequence",
                "steps", List.of(group(cond("kind", "=", "A")), group(cond("amount", ">=", 50)))));
        return AlertRule.fromMap(raw);
    }

    private static AlertRule absenceRule(String type, long size, long slide, long late, boolean byKey, boolean filtered) {
        Map<String, Object> w = new LinkedHashMap<>(Map.of("type", type, "size", size / S + "s", "time", "ts"));
        if ("sliding".equals(type)) w.put("slide", slide / S + "s");
        if (late > 0) w.put("lateness", late / S + "s");
        Map<String, Object> raw = new HashMap<>();
        raw.put("name", "abs");
        raw.put("dataset", "d");
        if (byKey) raw.put("by", List.of("k"));
        if (filtered) raw.put("when", group(cond("amount", "<", 100)));
        raw.put("window", w);
        raw.put("pattern", Map.of("type", "absence"));
        return AlertRule.fromMap(raw);
    }

    private void table(List<Row> rows) throws Exception {
        try (Statement st = duck.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE buf (k VARCHAR, kind VARCHAR, amount DOUBLE, ts TIMESTAMP, "
                    + WindowSql.SEQ + " BIGINT)");
        }
        try (PreparedStatement ps = duck.prepareStatement("INSERT INTO buf VALUES (?, ?, ?, make_timestamp(?), ?)")) {
            for (Row r : rows) {
                ps.setString(1, r.key);
                ps.setString(2, r.kind);
                ps.setDouble(3, r.amount);
                ps.setLong(4, r.ts);
                ps.setLong(5, r.seq);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private Set<Hit> run(AlertRule rule, WindowSql.Compiled c, List<Row> rows) throws Exception {
        table(rows);
        Set<Hit> out = new TreeSet<>();
        try (PreparedStatement ps = duck.prepareStatement(c.sql())) {
            for (int i = 0; i < c.params().size(); i++) ps.setObject(i + 1, c.params().get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    out.add(new Hit(rule.isGrouped() ? rs.getString(WindowSql.keyColumn(0)) : "",
                            rs.getLong(WindowSql.WINDOW_START), rs.getDouble(WindowSql.VALUE),
                            rs.getLong(WindowSql.FIRST), rs.getLong(WindowSql.LAST)));
            }
        }
        return out;
    }

    private Set<Hit> sequence(AlertRule rule, List<Row> rows, long seq, long watermark) throws Exception {
        return run(rule, WindowSql.sequence(rule, "buf", seq, watermark), rows);
    }

    private Set<Hit> absence(AlertRule rule, List<Row> rows, long from, long to) throws Exception {
        return run(rule, WindowSql.absence(rule, "buf", from, to), rows);
    }

    // ── the random corpora ──────────────────────────────────────────────────────

    @Test
    void sequenceAgreesWithTheOracleOnRandomBoundaryHeavyCorpora() throws Exception {
        Random rnd = new Random(9_003);
        int nonEmpty = 0;
        for (int round = 0; round < 200; round++) {
            long within = (1 + rnd.nextInt(10)) * 60 * S;
            long late = rnd.nextBoolean() ? 0 : (1 + rnd.nextInt(5)) * 60 * S;
            boolean byKey = rnd.nextBoolean();
            long seq = 1 + rnd.nextInt(3);
            List<Row> rows = new ArrayList<>();
            List<Long> as = new ArrayList<>();
            int n = 5 + rnd.nextInt(30);
            for (int i = 0; i < n; i++) {
                long ts = BASE + (long) (rnd.nextDouble() * 3 * within);
                String kind = rnd.nextInt(3) == 0 ? "A" : rnd.nextBoolean() ? "B" : "X";
                double amount = rnd.nextInt(4) * 25;   // 0, 25, 50, 75: half the rows match step B
                rows.add(new Row("k" + rnd.nextInt(3), kind, amount, ts, 1 + rnd.nextInt((int) seq)));
                if (kind.equals("A")) as.add(ts);
            }
            // boundary rows around an A: B at A, at A + within, one µs past it; and A self-matching both steps
            long a = BASE + within;
            rows.add(new Row("k0", "A", 75, a, seq));
            rows.add(new Row("k0", "B", 50, a, rnd.nextBoolean() ? seq : 1));
            rows.add(new Row("k0", "B", 50, a + within, rnd.nextBoolean() ? seq : 1));
            rows.add(new Row("k0", "B", 50, a + within + 1, seq));
            rows.add(new Row("k1", "B", 99, a + within / 2, seq));   // another key: pairs only without by
            as.add(a);
            // the Watermark: around some A's close (a + within + late), or open everything / nothing
            long anchor = as.get(rnd.nextInt(as.size())) + within + late;
            long watermark = switch (rnd.nextInt(5)) {
                case 0 -> anchor;
                case 1 -> anchor - 1;
                case 2 -> anchor + 1;
                case 3 -> Long.MIN_VALUE;
                default -> BASE + (long) (rnd.nextDouble() * 4 * within);
            };
            AlertRule rule = sequenceRule(within, late, byKey);
            Set<Hit> expected = sequenceOracle(within, late, byKey, rows, seq, watermark);
            assertEquals(expected, sequence(rule, rows, seq, watermark),
                    "round " + round + " within=" + within + " late=" + late + " by=" + byKey + " seq=" + seq + " wm=" + watermark);
            if (!expected.isEmpty()) nonEmpty++;
        }
        assertTrue(nonEmpty > 100, "the corpora must exercise matches, not only empty answers: " + nonEmpty);
    }

    @Test
    void absenceAgreesWithTheOracleOnRandomBoundaryHeavyCorpora() throws Exception {
        Random rnd = new Random(9_004);
        int nonEmpty = 0;
        for (int round = 0; round < 200; round++) {
            boolean sliding = rnd.nextBoolean();
            long slide = (1 + rnd.nextInt(3)) * 60 * S;
            long size = sliding ? slide * (1 + rnd.nextInt(5)) : slide;
            long late = rnd.nextBoolean() ? 0 : (1 + rnd.nextInt(3)) * 60 * S;
            boolean byKey = rnd.nextBoolean(), filtered = rnd.nextBoolean();
            List<Row> rows = new ArrayList<>();
            int n = 3 + rnd.nextInt(25);
            for (int i = 0; i < n; i++)
                rows.add(new Row("k" + rnd.nextInt(3), "X", rnd.nextInt(3) * 60, BASE + (long) (rnd.nextDouble() * 6 * size), 1));
            // boundary rows for the Window right after k0's event at BASE: at its start, one µs before its end, at its end
            long ws = BASE + slide;
            switch (rnd.nextInt(4)) {
                case 0 -> rows.add(new Row("k0", "X", 0, ws, 1));
                case 1 -> rows.add(new Row("k0", "X", 0, ws + size - 1, 1));
                case 2 -> rows.add(new Row("k0", "X", 0, ws + size, 1));
                default -> rows.add(new Row("k0", "X", 120, ws, 1));   // filtered out by when: does not break the silence
            }
            rows.add(new Row("k0", "X", 0, BASE, 1));
            // the frontier move: around the boundary Window's close, or a random span
            long close = ws + size + late;
            long from, to;
            switch (rnd.nextInt(5)) {
                case 0 -> { from = close - 1; to = close; }       // closes exactly at the frontier: judged
                case 1 -> { from = close - 2; to = close - 1; }   // one µs short: not yet
                case 2 -> { from = close; to = close + 1; }       // judged by the previous move: not again
                case 3 -> { from = Long.MIN_VALUE; to = close + rnd.nextInt(4) * size; }
                default -> { from = BASE + (long) (rnd.nextDouble() * 3 * size); to = from + (long) (rnd.nextDouble() * 4 * size); }
            }
            AlertRule rule = absenceRule(sliding ? "sliding" : "tumbling", size, slide, late, byKey, filtered);
            Set<Hit> expected = absenceOracle(size, slide, late, byKey, filtered, rows, from, to);
            assertEquals(expected, absence(rule, rows, from, to), "round " + round + " size=" + size + " slide=" + slide
                    + " late=" + late + " by=" + byKey + " when=" + filtered + " from=" + from + " to=" + to);
            if (!expected.isEmpty()) nonEmpty++;
        }
        assertTrue(nonEmpty > 50, "the corpora must exercise silences, not only empty answers: " + nonEmpty);
    }

    // ── named boundaries ────────────────────────────────────────────────────────

    @Test
    void aSequenceIsInclusiveAtBothEndsAndNeverPairsARowWithItself() throws Exception {
        long within = 600 * S, a = BASE;
        AlertRule rule = sequenceRule(within, 0, true);
        assertEquals(Set.of(new Hit("k", a, 1, a, a)),
                sequence(rule, List.of(new Row("k", "A", 0, a, 1), new Row("k", "B", 50, a, 1)), 1, Long.MIN_VALUE),
                "B at the same instant as A follows it");
        assertEquals(Set.of(new Hit("k", a, 1, a, a + within)),
                sequence(rule, List.of(new Row("k", "A", 0, a, 1), new Row("k", "B", 50, a + within, 1)), 1, Long.MIN_VALUE),
                "B exactly within after A");
        assertEquals(Set.of(), sequence(rule, List.of(new Row("k", "A", 0, a, 1), new Row("k", "B", 50, a + within + 1, 1)),
                1, Long.MIN_VALUE), "one µs past within");
        assertEquals(Set.of(), sequence(rule, List.of(new Row("k", "B", 50, a - 1, 1), new Row("k", "A", 0, a, 1)),
                1, Long.MIN_VALUE), "B before A is not a sequence");
        assertEquals(Set.of(), sequence(rule, List.of(new Row("k", "A", 99, a, 1)), 1, Long.MIN_VALUE),
                "a row matching both steps does not pair with itself");
        assertEquals(Set.of(), sequence(rule, List.of(new Row("k", "A", 0, a, 1), new Row("j", "B", 50, a + 1, 1)),
                1, Long.MIN_VALUE), "another key");
    }

    @Test
    void aSequenceCountsOnlyWhileAsWindowIsOpenAndOnlyWhatThisCommitTouched() throws Exception {
        long within = 600 * S, late = 60 * S, a = BASE;
        AlertRule rule = sequenceRule(within, late, true);
        List<Row> rows = List.of(new Row("k", "A", 0, a, 1), new Row("k", "B", 50, a + within, 2));
        assertEquals(1, sequence(rule, rows, 2, a + within + late - 1).size(), "A's Window open one µs before its close");
        assertEquals(Set.of(), sequence(rule, rows, 2, a + within + late), "a Watermark exactly at the close: B is late");
        assertEquals(Set.of(), sequence(rule, rows, 3, Long.MIN_VALUE), "neither A nor B arrived in commit 3");
        assertEquals(1, sequence(rule, rows, 1, Long.MIN_VALUE).size(), "A arrived in commit 1");
    }

    @Test
    void anAbsenceIsJudgedExactlyOnceWhenTheFrontierPassesHiPlusLateness() throws Exception {
        long size = 300 * S, late = 60 * S;
        AlertRule rule = absenceRule("tumbling", size, size, late, true, false);
        List<Row> rows = List.of(new Row("sw1", "X", 0, BASE + 10 * S, 1));
        long ws = BASE + size, close = ws + size + late;
        assertEquals(Set.of(), absence(rule, rows, Long.MIN_VALUE, close - 1), "not yet closed");
        assertEquals(Set.of(new Hit("sw1", ws, 0, BASE + 10 * S, BASE + 10 * S)), absence(rule, rows, close - 1, close),
                "closed exactly at the frontier");
        assertEquals(Set.of(), absence(rule, rows, close, close + 10 * size), "already judged");
    }

    @Test
    void anEventAtTheWindowStartBreaksTheSilenceAndOneAtItsEndDoesNot() throws Exception {
        long size = 300 * S;
        AlertRule rule = absenceRule("tumbling", size, size, 0, false, false);
        long ws = BASE + size;
        assertEquals(Set.of(), absence(rule, List.of(new Row("x", "X", 0, BASE, 1), new Row("x", "X", 0, ws, 1)),
                Long.MIN_VALUE, ws + size), "an event at lo");
        assertEquals(Set.of(), absence(rule, List.of(new Row("x", "X", 0, BASE, 1), new Row("x", "X", 0, ws + size - 1, 1)),
                Long.MIN_VALUE, ws + size), "an event one µs before hi");
        assertEquals(Set.of(new Hit("", ws, 0, BASE, BASE)),
                absence(rule, List.of(new Row("x", "X", 0, BASE, 1), new Row("x", "X", 0, ws + size, 1)),
                        Long.MIN_VALUE, ws + size), "an event at hi is outside [lo, hi): the Window was silent");
    }

    @Test
    void aStreamThatNeverSentAnEventIsNotAnAbsence() throws Exception {
        AlertRule rule = absenceRule("tumbling", 300 * S, 300 * S, 0, false, false);
        assertEquals(Set.of(), absence(rule, List.of(), Long.MIN_VALUE, BASE + 3600 * S),
                "a silence needs a last event; a feed that never published is a freshness rule's (maximumAge)");
    }

    @Test
    void stepConditionsAreBoundNotSpliced() throws Exception {
        Map<String, Object> raw = new HashMap<>();
        raw.put("name", "seq");
        raw.put("dataset", "d");
        raw.put("window", Map.of("type", "tumbling", "size", "10m", "time", "ts"));
        raw.put("pattern", Map.of("type", "sequence", "steps",
                List.of(group(cond("kind", "=", "A'); DROP TABLE buf; --")), group(cond("amount", ">=", 50)))));
        AlertRule rule = AlertRule.fromMap(raw);
        WindowSql.Compiled c = WindowSql.sequence(rule, "buf", 1, Long.MIN_VALUE);
        assertFalse(c.sql().contains("DROP TABLE"), c.sql());
        assertTrue(c.params().contains("A'); DROP TABLE buf; --"), c.params().toString());
        assertEquals(Set.of(), sequence(rule, List.of(new Row("k", "A", 0, BASE, 1), new Row("k", "B", 50, BASE, 1)),
                1, Long.MIN_VALUE));
    }
}
