package com.gamma.analytics.time;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.util.DuckDbUtil;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JVM ↔ DuckDB parity for {@link TimeGrain} (ANALYTICS-CORE-1 M5): every grain, every form, over instants packed around
 * DST transitions (spring forward, fall back, a 30-minute shift, a midnight gap, a midnight overlap) and week and year
 * boundaries — with DuckDB's session zone set to a zone none of the cases use, so a form that leaned on it would differ.
 */
class TimeGrainDuckDbParityTest {

    /** Zones with a transition in 2026, each with one transition instant to pack cases around. */
    private static final String[][] ZONES = {
            {"UTC", "2026-12-31T23:00:00Z"},
            {"Europe/Berlin", "2026-03-29T01:00:00Z"},          // 02:00 → 03:00
            {"Europe/Berlin", "2026-10-25T01:00:00Z"},          // 03:00 → 02:00, a repeated hour
            {"America/New_York", "2026-11-01T06:00:00Z"},
            {"America/Santiago", "2026-09-06T04:00:00Z"},       // 24:00 → 01:00, midnight never happens
            {"America/Havana", "2026-11-01T05:00:00Z"},         // 01:00 → 00:00, midnight happens twice
            {"Australia/Lord_Howe", "2026-10-03T15:30:00Z"},    // a 30-minute shift
            {"Asia/Kolkata", "2026-12-31T18:30:00Z"},           // +05:30, no DST, new year
    };
    private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    @TempDir
    static Path spill;
    private static Connection conn;

    @BeforeAll
    static void open() throws Exception {
        conn = DuckDbUtil.openInMemory(spill.resolve(DuckDbUtil.SPILL_DIR_NAME));
        try (Statement st = conn.createStatement()) {
            st.execute("SET TimeZone = 'Pacific/Chatham'");   // +12:45/+13:45: no case uses it
            st.execute("CREATE TABLE t (id INTEGER, ts TIMESTAMPTZ, wall TIMESTAMP)");
        }
    }

    @AfterAll
    static void close() throws Exception {
        if (conn != null) conn.close();
    }

    /** Every 7 minutes 13 seconds across 30 hours either side of the transition, plus odd micros, plus the edges. */
    private static List<Instant> around(Instant pivot) {
        List<Instant> out = new ArrayList<>();
        for (Instant t = pivot.minus(30, ChronoUnit.HOURS); t.isBefore(pivot.plus(30, ChronoUnit.HOURS));
             t = t.plusSeconds(433))
            out.add(t);
        for (int s = -2; s <= 2; s++) out.add(pivot.plusSeconds(s));
        out.add(pivot.plusNanos(123_456_000));
        return out;
    }

    @Test
    void zonedLabelAndStartAgreeWithDuckDbAroundEveryTransition() throws Exception {
        int checked = 0;
        for (String[] z : ZONES) {
            ZoneId zone = ZoneId.of(z[0]);
            List<Instant> cases = around(Instant.parse(z[1]));
            load(cases, null);
            for (TimeGrain g : TimeGrain.values()) {
                String sql = "SELECT id, strftime(" + g.labelSql("ts", zone) + ", '%Y-%m-%d %H:%M:%S'), epoch_us("
                        + g.truncateSql("ts", zone) + ") FROM t ORDER BY id";
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                    while (rs.next()) {
                        Instant in = cases.get(rs.getInt(1));
                        String at = g + " " + zone + " " + in;
                        assertEquals(LABEL.format(g.label(in, zone)), rs.getString(2), "label " + at);
                        assertEquals(micros(g.truncate(in, zone)), rs.getLong(3), "start " + at);
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked > 10_000, "checked " + checked);
    }

    @Test
    void wallClockTruncationAgreesWithDuckDbOnTimestampsAndDates() throws Exception {
        List<LocalDateTime> cases = new ArrayList<>();
        // Every day 2020-12-20 .. 2021-01-10 (ISO week 53) and 2026-12-20 .. 2027-01-10, at an odd time of day,
        // plus month and quarter edges and a leap day.
        for (String from : List.of("2020-12-20T23:59:59.999999", "2026-12-20T00:00:00", "2026-12-20T13:47:21.5"))
            for (int d = 0; d < 22; d++) cases.add(LocalDateTime.parse(from).plusDays(d));
        for (String s : List.of("2028-02-29T12:00", "2026-03-31T23:59:59", "2026-04-01T00:00", "2026-06-30T23:59:59.999999",
                "2026-07-01T00:00", "2026-09-30T11:11:11", "2026-10-01T00:00:01"))
            cases.add(LocalDateTime.parse(s));
        load(null, cases);
        int checked = 0;
        for (TimeGrain g : TimeGrain.values()) {
            String sql = "SELECT id, strftime(" + g.truncateSql("wall") + ", '%Y-%m-%d %H:%M:%S'), strftime("
                    + g.truncateSql("CAST(wall AS DATE)") + ", '%Y-%m-%d %H:%M:%S') FROM t ORDER BY id";
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    LocalDateTime in = cases.get(rs.getInt(1));
                    assertEquals(LABEL.format(g.truncate(in)), rs.getString(2), g + " " + in);
                    assertEquals(LABEL.format(g.truncate(in.toLocalDate()).atStartOfDay()), rs.getString(3),
                            g + " DATE " + in.toLocalDate());
                    checked++;
                }
            }
        }
        assertEquals(TimeGrain.values().length * cases.size(), checked);
    }

    private static void load(List<Instant> instants, List<LocalDateTime> walls) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM t");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO t VALUES (?, timezone('UTC', make_timestamp(?)), make_timestamp(?))")) {
            int n = instants != null ? instants.size() : walls.size();
            for (int i = 0; i < n; i++) {
                long us = instants != null ? micros(instants.get(i))
                        : micros(walls.get(i).toInstant(java.time.ZoneOffset.UTC));
                ps.setInt(1, i);
                ps.setLong(2, us);
                ps.setLong(3, us);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static long micros(Instant t) {
        return ChronoUnit.MICROS.between(Instant.EPOCH, t);
    }
}
