package com.gamma.analytics.time;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.util.DuckDbUtil;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** JVM ↔ DuckDB parity for {@link HourOfWeek} slots and {@link SeasonalProfile}, under two session zones. */
class HourOfWeekDuckDbParityTest {

    @TempDir
    static Path spill;
    private static Connection conn;
    private static final ZoneId[] ZONES = {ZoneId.of("UTC"), ZoneId.of("Asia/Kolkata"), ZoneId.of("America/New_York"),
            ZoneId.of("Europe/Berlin")};

    @BeforeAll
    static void open() throws Exception {
        conn = DuckDbUtil.openInMemory(spill.resolve(DuckDbUtil.SPILL_DIR_NAME));
    }

    @AfterAll
    static void close() throws Exception {
        if (conn != null) conn.close();
    }

    /** Hourly instants across both 2026 DST changes of New York and Berlin, plus off-hour minutes. */
    private static List<Instant> instants() {
        List<Instant> out = new ArrayList<>();
        Random r = new Random(7);
        for (Instant t = Instant.parse("2026-03-07T00:00:00Z"); t.isBefore(Instant.parse("2026-04-03T00:00:00Z"));
             t = t.plusSeconds(3600)) out.add(t.plusSeconds(r.nextInt(3600)));
        for (Instant t = Instant.parse("2026-10-20T00:00:00Z"); t.isBefore(Instant.parse("2026-11-10T00:00:00Z"));
             t = t.plusSeconds(3600)) out.add(t.plusSeconds(r.nextInt(3600)));
        return out;
    }

    private static void setSessionZone(String tz) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("SET TimeZone = '" + tz + "'");
        }
    }

    @Test
    void slotSqlEqualsJvmInEveryZoneWhateverTheSessionZone() throws Exception {
        List<Instant> ts = instants();
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE ev (i INTEGER, t TIMESTAMPTZ)");
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO ev VALUES (?, (epoch_ms(?::BIGINT) AT TIME ZONE 'UTC'))")) {
            for (int i = 0; i < ts.size(); i++) {
                ps.setInt(1, i);
                ps.setLong(2, ts.get(i).toEpochMilli());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        for (String session : new String[] {"UTC", "Pacific/Auckland"}) {
            setSessionZone(session);
            for (ZoneId zone : ZONES)
                for (HourOfWeek kind : HourOfWeek.values()) {
                    try (Statement st = conn.createStatement();
                         ResultSet rs = st.executeQuery("SELECT i, " + kind.slotSql("t", zone) + " FROM ev ORDER BY i")) {
                        int rows = 0;
                        while (rs.next()) {
                            rows++;
                            assertEquals(kind.slot(ts.get(rs.getInt(1)), zone), rs.getInt(2),
                                    kind + " " + zone + " session " + session + " " + ts.get(rs.getInt(1)));
                        }
                        assertEquals(ts.size(), rows);
                    }
                }
        }
        setSessionZone("UTC");
    }

    @Test
    void profileAndStrengthMatchPerKey() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE ob (k VARCHAR, w TIMESTAMP, v DOUBLE)");
        }
        Random r = new Random(11);
        List<Integer> slotsA = new ArrayList<>(), slotsB = new ArrayList<>();
        List<Double> valsA = new ArrayList<>(), valsB = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO ob VALUES (?, ?, ?)")) {
            for (int h = 0; h < 24 * 21; h++) {
                java.time.LocalDateTime w = java.time.LocalDateTime.of(2026, 9, 7, 0, 0).plusHours(h);
                int s = HourOfWeek.HOUR_OF_WEEK.slot(w);
                double a = (s % 24 < 8 ? 3 : 20) + Math.rint(r.nextDouble() * 400) / 100, b = Math.rint(r.nextDouble() * 1000) / 10;
                ps.setString(1, "a"); ps.setTimestamp(2, java.sql.Timestamp.valueOf(w)); ps.setDouble(3, a); ps.addBatch();
                slotsA.add(s); valsA.add(a);
                ps.setString(1, "b"); ps.setTimestamp(2, java.sql.Timestamp.valueOf(w)); ps.setDouble(3, b); ps.addBatch();
                slotsB.add(s); valsB.add(b);
            }
            ps.setString(1, "a"); ps.setTimestamp(2, java.sql.Timestamp.valueOf("2026-09-08 00:00:00")); ps.setNull(3, java.sql.Types.DOUBLE);
            ps.addBatch();                                                        // a NULL value is left out
            ps.executeBatch();
        }
        String slot = HourOfWeek.HOUR_OF_WEEK.slotSql("w");
        var pa = SeasonalProfile.of(HourOfWeek.HOUR_OF_WEEK, slotsA.stream().mapToInt(Integer::intValue).toArray(),
                valsA.stream().mapToDouble(Double::doubleValue).toArray());
        var pb = SeasonalProfile.of(HourOfWeek.HOUR_OF_WEEK, slotsB.stream().mapToInt(Integer::intValue).toArray(),
                valsB.stream().mapToDouble(Double::doubleValue).toArray());
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(SeasonalProfile.strengthSql("ob", slot, "v", List.of("k")))) {
            assertTrue(rs.next());
            assertEquals("a", rs.getString("k"));
            assertEquals(pa.n(), rs.getLong("n"));
            assertEquals(pa.strength().getAsDouble(), rs.getDouble("strength"), 1e-9);
            assertTrue(rs.next());
            assertEquals(pb.strength().getAsDouble(), rs.getDouble("strength"), 1e-9);
            assertFalse(rs.next());
        }
        assertTrue(pa.strength().getAsDouble() > pb.strength().getAsDouble());
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(SeasonalProfile.profileSql("ob", slot, "v", List.of("k")))) {
            int i = 0;
            while (rs.next() && "a".equals(rs.getString("k"))) {
                var e = pa.slots().get(i++);
                assertEquals(e.slot(), rs.getInt("slot"));
                assertEquals(e.n(), rs.getLong("n"));
                assertEquals(e.mean(), rs.getDouble("mean"), 1e-9);
            }
            assertEquals(168, i);
        }
        // no keys: one series, a single row
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(SeasonalProfile.strengthSql("ob", slot, "v", List.of()))) {
            assertTrue(rs.next());
            assertEquals(pa.n() + pb.n(), rs.getLong("n"));
            assertFalse(rs.next());
        }
    }
}
