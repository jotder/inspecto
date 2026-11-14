package com.gamma.la.storage;

import com.gamma.la.core.LinkTemporalDetector;
import com.gamma.la.core.TemporalCut;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DUCKDB-2-LOOP-QUERIES-1 C3: the {@link TemporalCut} statement must hand the detector every series it would report, so the
 * findings over the cut rows equal the findings over ALL the capped rows (the haul-everything read it replaces), for both
 * modes, both series kinds and a row cap that cuts a series in half. Randomised, seeded, with ties at a window edge, NULL
 * times, self-loops, zero gaps and exactly periodic series.
 */
class TemporalCutParityTest {

    private record Ev(String s, String t, Long ms) { }

    /** The data: a few hot links with bursts, some regular series, noise, ties, NULL times and self-loops. */
    private static List<Ev> corpus(long seed) {
        Random r = new Random(seed);
        List<Ev> out = new ArrayList<>();
        String[] n = {"a", "b", "c", "d", "e", "f", "g"};
        for (int i = 0; i < 400; i++) {                                     // noise, often tied (coarse clock)
            String s = n[r.nextInt(n.length)], t = n[r.nextInt(n.length)];  // s == t gives self-loops
            Long ms = r.nextInt(25) == 0 ? null : (long) r.nextInt(200) * 1_000;
            out.add(new Ev(s, t, ms));
        }
        for (int i = 0; i < 12; i++) out.add(new Ev("hot", "x", 50_000L + (long) r.nextInt(30) * 1_000));   // a burst
        for (int i = 0; i < 6; i++) out.add(new Ev("hot", "x", 60_000L));                                   // ties at one instant
        for (int i = 0; i < 10; i++) out.add(new Ev("tick", "tock", 1_000L + i * 7_000L));                    // exactly periodic
        for (int i = 0; i < 10; i++) out.add(new Ev("tick2", "tock", 1_000L + i * 7_000L + r.nextInt(300)));  // nearly periodic
        for (int i = 0; i < 5; i++) out.add(new Ev("same", "same", 9_000L));                                   // zero gaps, self-loop
        for (int i = 0; i < 6; i++) out.add(new Ev("edge", "w", i < 3 ? 0L : 10_000L));                      // ties AT the window edge
        return out;
    }

    /** The detection as the route runs it, over rows grouped by link with times ascending (NULL times skipped). */
    private static List<String> findings(List<Ev> rows, TemporalCut.Spec spec) {
        List<String> out = new ArrayList<>();
        Map<String, List<Long>> series = new LinkedHashMap<>();
        for (Ev e : rows) {
            if (e.ms() == null) continue;
            if (spec.entity()) {
                for (String who : e.s().equals(e.t()) ? List.of(e.s()) : List.of(e.s(), e.t()))
                    series.computeIfAbsent(who, k -> new ArrayList<>()).add(e.ms());
            } else {
                series.computeIfAbsent(e.s() + " -> " + e.t(), k -> new ArrayList<>()).add(e.ms());
            }
        }
        for (Map.Entry<String, List<Long>> s : series.entrySet()) {
            long[] times = s.getValue().stream().mapToLong(Long::longValue).toArray();
            Arrays.sort(times);
            if (spec.burst()) {
                for (LinkTemporalDetector.Burst b : LinkTemporalDetector.bursts(times, spec.windowMs(), spec.minEvents()))
                    out.add(s.getKey() + " " + b);
            } else {
                LinkTemporalDetector.Periodic p = LinkTemporalDetector.periodicity(times, spec.minEvents(), spec.maxCv());
                if (p != null) out.add(s.getKey() + " " + p);
            }
        }
        out.sort(null);
        return out;
    }

    @Test
    void theCutKeepsEverySeriesTheDetectorWouldReport() throws Exception {
        List<TemporalCut.Spec> specs = List.of(
                new TemporalCut.Spec(true, false, 10_000, 3, 0),
                new TemporalCut.Spec(true, false, 10_000, 6, 0),
                new TemporalCut.Spec(true, true, 5_000, 4, 0),
                new TemporalCut.Spec(true, false, 0, 2, 0),                 // a zero window: only exact ties
                new TemporalCut.Spec(false, false, 0, 3, 0.1),
                new TemporalCut.Spec(false, false, 0, 5, 0.0),              // only exactly periodic
                new TemporalCut.Spec(false, true, 0, 3, 0.5),
                new TemporalCut.Spec(false, false, 0, 3, 1.0));
        int compared = 0, nonEmpty = 0;
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            for (long seed = 1; seed <= 6; seed++) {
                List<Ev> corpus = corpus(seed);
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE OR REPLACE TABLE ev (s VARCHAR, t VARCHAR, ts BIGINT)");
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO ev VALUES (?, ?, ?)")) {
                    for (Ev e : corpus) {
                        ps.setString(1, e.s());
                        ps.setString(2, e.t());
                        if (e.ms() == null) ps.setNull(3, java.sql.Types.BIGINT); else ps.setLong(3, e.ms());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                for (int maxRows : new int[] {corpus.size() + 10, corpus.size(), 200, 37}) {
                    List<Ev> capped = new ArrayList<>();
                    try (Statement st = c.createStatement();
                         ResultSet rs = st.executeQuery("SELECT s, t, ts FROM ev ORDER BY s, t, ts NULLS LAST LIMIT " + maxRows)) {
                        while (rs.next())                               // getObject: wasNull() would speak of the LAST column read
                            capped.add(new Ev(rs.getString(1), rs.getString(2), rs.getObject(3) instanceof Number n ? n.longValue() : null));
                    }
                    int nulls = (int) capped.stream().filter(e -> e.ms() == null).count();
                    for (TemporalCut.Spec spec : specs) {
                        TemporalCut.Result cut;
                        try (Statement st = c.createStatement();
                             ResultSet rs = st.executeQuery(TemporalCut.sql("SELECT s, t, ts FROM ev", maxRows, spec))) {
                            cut = TemporalCut.read(rs);
                        }
                        List<Ev> kept = cut.rows().stream().map(k -> new Ev(k.s(), k.t(), k.ms())).toList();
                        String at = "seed " + seed + ", maxRows " + maxRows + ", " + spec;
                        List<String> expected = findings(capped, spec);
                        assertEquals(expected, findings(kept, spec), at);
                        assertEquals(nulls, cut.skippedNoTime(), at);
                        assertEquals(corpus.size() > maxRows, cut.rowCapped(), at);
                        assertTrue(kept.size() <= TemporalCut.maxOutputRows(maxRows, spec), at);
                        compared++;
                        if (!expected.isEmpty()) nonEmpty++;
                    }
                }
            }
        }
        assertEquals(6 * 4 * 8, compared);
        assertTrue(nonEmpty > compared / 2, "the corpus must exercise findings, not only empty answers: " + nonEmpty);
    }

    /**
     * The cut drops series that cannot hold a finding - the point of it - so far fewer rows reach the JVM. The Dataset is named
     * {@code src}, a name the statement must not shadow with a CTE of its own.
     */
    @Test
    void seriesWithNoFindingAreNotHauled() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE src AS SELECT 'n' || (i % 500) AS s, 'm' AS t, i * 997 AS ts FROM range(20000) r(i)");
            st.execute("INSERT INTO src SELECT 'hot', 'm', 5 FROM range(10)");
            try (ResultSet rs = st.executeQuery(TemporalCut.sql("SELECT s, t, ts FROM src", 100_000,
                    new TemporalCut.Spec(true, false, 1_000, 5, 0)))) {
                TemporalCut.Result cut = TemporalCut.read(rs);
                assertEquals(new TreeSet<>(List.of("hot")), new TreeSet<>(cut.rows().stream().map(TemporalCut.Row::s).toList()));
                assertEquals(10, cut.rows().size());
            }
        }
    }
}
