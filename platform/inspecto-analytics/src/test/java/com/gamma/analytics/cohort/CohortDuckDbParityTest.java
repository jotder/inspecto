package com.gamma.analytics.cohort;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.analytics.time.TimeGrain;
import com.gamma.util.DuckDbUtil;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** JVM ↔ DuckDB parity for {@link Retention} and {@link Funnel} over seeded random events, across zones and options. */
class CohortDuckDbParityTest {

    @TempDir
    static Path spill;
    private static Connection conn;
    private static final String[] STEPS = {"visit", "signup", "trial", "pay"};

    @BeforeAll
    static void open() throws Exception {
        conn = DuckDbUtil.openInMemory(spill.resolve(DuckDbUtil.SPILL_DIR_NAME));
    }

    @AfterAll
    static void close() throws Exception {
        if (conn != null) conn.close();
    }

    /** Events over 14 months spanning both DST changes of Berlin and New York; entity ids with mixed text. */
    private static List<Funnel.Event> events(long seed) {
        Random r = new Random(seed);
        List<Funnel.Event> out = new ArrayList<>();
        Instant start = Instant.parse("2025-09-01T00:00:00Z");
        for (int i = 0; i < 400; i++) {
            String entity = i % 17 == 0 ? "é" + i : "u" + i;
            Instant at = start.plusSeconds(r.nextInt(14 * 30 * 86400));
            for (int s = 0; s < STEPS.length; s++) {
                if (s > 0 && r.nextInt(100) < 45) break;
                out.add(new Funnel.Event(entity, STEPS[s], at));
                at = at.plusSeconds(r.nextInt(40 * 3600));
                if (r.nextInt(100) < 15) out.add(new Funnel.Event(entity, STEPS[s], at));   // a repeat
            }
            if (r.nextInt(100) < 40) out.add(new Funnel.Event(entity, "visit", start.plusSeconds(r.nextInt(14 * 30 * 86400))));
        }
        out.add(new Funnel.Event(null, "visit", start));
        out.add(new Funnel.Event("nullstep", null, start));
        out.add(new Funnel.Event("nulltime", "visit", null));
        return out;
    }

    private static void load(List<Funnel.Event> events) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE ev (ent VARCHAR, stp VARCHAR, ts TIMESTAMPTZ)");
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO ev VALUES (?, ?, CASE WHEN ?::BIGINT IS NULL THEN NULL "
                + "ELSE (epoch_ms(?::BIGINT) AT TIME ZONE 'UTC') END)")) {
            for (Funnel.Event e : events) {
                ps.setString(1, e.entity());
                ps.setString(2, e.step());
                if (e.at() == null) {
                    ps.setNull(3, java.sql.Types.BIGINT);
                    ps.setNull(4, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(3, e.at().toEpochMilli());
                    ps.setLong(4, e.at().toEpochMilli());
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (Statement st = conn.createStatement()) {
            st.execute("SET TimeZone = 'Pacific/Auckland'");   // never the session zone
        }
    }

    @Test
    void retentionSqlEqualsJvmForEveryGrainZoneModeAndOption() throws Exception {
        List<Funnel.Event> events = events(21);
        load(events);
        List<Retention.Event> jvm = events.stream().map(e -> new Retention.Event(e.entity(), e.at())).toList();
        LocalDateTime through = LocalDateTime.of(2026, 7, 20, 13, 0);
        int checked = 0;
        for (TimeGrain grain : new TimeGrain[] {TimeGrain.DAY, TimeGrain.WEEK, TimeGrain.MONTH, TimeGrain.QUARTER})
            for (ZoneId zone : new ZoneId[] {ZoneId.of("UTC"), ZoneId.of("Asia/Kolkata"), ZoneId.of("Europe/Berlin"),
                    ZoneId.of("America/New_York")})
                for (Retention.Mode mode : Retention.Mode.values()) {
                    LocalDateTime from = grain == TimeGrain.MONTH ? LocalDateTime.of(2025, 11, 9, 0, 0) : null;
                    int maxPeriods = grain == TimeGrain.DAY ? 40 : 6;
                    Retention.Params p = new Retention.Params(grain, zone, through, from, mode, maxPeriods);
                    List<Retention.Cell> want = Retention.compute(jvm, p);
                    List<Retention.Cell> got = new ArrayList<>();
                    try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(Retention.sql("ev", "ent", "ts", p))) {
                        while (rs.next())
                            got.add(new Retention.Cell(rs.getTimestamp("cohort").toLocalDateTime(), rs.getInt("period"),
                                    rs.getLong("size"), rs.getLong("active")));
                        assertEquals(0, new Retention.Params(grain, zone, through, from, mode, maxPeriods).maxPeriods() - maxPeriods);
                    }
                    String where = grain + " " + zone + " " + mode;
                    assertFalse(want.isEmpty(), where);
                    assertEquals(want, got, where);
                    checked++;
                }
        assertEquals(32, checked);
    }

    @Test
    void retentionOverAZonelessDateColumnUsesItsOwnClock() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE dv (ent VARCHAR, d DATE)");
            st.execute("INSERT INTO dv VALUES ('a', DATE '2026-01-31'), ('a', DATE '2026-03-01'), ('b', DATE '2026-02-01'),"
                    + " ('c', NULL), (NULL, DATE '2026-01-01')");
        }
        Retention.Params p = new Retention.Params(TimeGrain.MONTH, null, LocalDateTime.of(2026, 3, 31, 0, 0), null,
                Retention.Mode.EXACT, 12);
        List<String> got = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(Retention.sql("dv", "ent", "d", p))) {
            while (rs.next()) got.add(rs.getTimestamp("cohort").toLocalDateTime().getMonthValue() + ":" + rs.getInt("period")
                    + ":" + rs.getLong("size") + ":" + rs.getLong("active"));
        }
        // a: Jan cohort, active again in March (period 2); b: Feb cohort
        assertEquals(List.of("1:0:1:1", "1:1:1:0", "1:2:1:1", "2:0:1:1", "2:1:1:0"), got);
    }

    @Test
    void funnelSqlEqualsJvmWithAndWithoutAWindow() throws Exception {
        List<Funnel.Event> events = events(33);
        load(events);
        for (Duration within : new Duration[] {null, Duration.ofHours(48), Duration.ofDays(30)}) {
            Funnel.Params p = new Funnel.Params(List.of(STEPS), within);
            List<Funnel.StepResult> want = Funnel.compute(events, p);
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(Funnel.sql("ev", "ent", "stp", "ts", p))) {
                int i = 0;
                while (rs.next()) {
                    Funnel.StepResult w = want.get(i++);
                    String where = "step " + w.step() + " within " + within;
                    assertEquals(w.name(), rs.getString("name"), where);
                    assertEquals(w.entities(), rs.getLong("entities"), where);
                    assertOpt(w.fromPrevious(), rs, "from_previous", where);
                    assertOpt(w.fromFirst(), rs, "from_first", where);
                }
                assertEquals(want.size(), i);
            }
            assertTrue(want.get(0).entities() > want.get(3).entities() && want.get(3).entities() > 0, "a real funnel: " + want);
        }
    }

    private static void assertOpt(java.util.OptionalDouble want, ResultSet rs, String col, String where) throws Exception {
        double v = rs.getDouble(col);
        if (want.isEmpty()) assertTrue(rs.wasNull(), where + " " + col + " is NULL");
        else assertEquals(want.getAsDouble(), v, 1e-12, where + " " + col);
    }
}
