package com.gamma.analytics.split;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.analytics.split.HoldoutSplit.HashSplit;
import com.gamma.analytics.split.HoldoutSplit.Role;
import com.gamma.analytics.split.HoldoutSplit.TimeSplit;
import com.gamma.util.DuckDbUtil;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** JVM ↔ DuckDB parity for the holdout split: the SQL roles and predicates against the JVM functions. */
class HoldoutSplitDuckDbParityTest {

    @TempDir
    static Path spill;
    private static Connection conn;

    @BeforeAll
    static void open() throws Exception {
        conn = DuckDbUtil.openInMemory(spill.resolve(DuckDbUtil.SPILL_DIR_NAME));
    }

    @AfterAll
    static void close() throws Exception {
        if (conn != null) conn.close();
    }

    @Test
    void hashRolesAndPredicatesMatchTheJvm() throws Exception {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 3000; i++) keys.add("k" + i);
        keys.addAll(List.of("", "Alice", "MiXeD Case", "o'brien", "été", "東京", "emoji 😀", "a\"b"));
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE e (\"entity key\" VARCHAR)");
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO e VALUES (?)")) {
            for (String k : keys) {
                ps.setString(1, k);
                ps.addBatch();
            }
            ps.setNull(1, java.sql.Types.VARCHAR);
            ps.addBatch();
            ps.executeBatch();
        }
        for (HashSplit s : List.of(new HashSplit(42, 7000, 1500), new HashSplit(-9, 6000, 2000), HashSplit.of(0, 0.8, 0, 0.2))) {
            String col = "entity key";
            String sql = "SELECT \"entity key\", " + s.bucketSql(col) + " AS b, " + s.roleSql(col) + " AS r, "
                    + s.predicateSql(col, Role.TRAIN) + " AS is_train, " + s.predicateSql(col, Role.VALIDATION) + " AS is_val, "
                    + s.predicateSql(col, Role.TEST) + " AS is_test FROM e";
            int checked = 0;
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    String k = rs.getString(1);
                    if (k == null) {
                        rs.getInt("b");
                        assertTrue(rs.wasNull());
                        assertNull(rs.getString("r"), "a NULL key has no role");
                        continue;
                    }
                    Role role = s.role(k);
                    assertEquals(s.bucket(k), rs.getInt("b"), "bucket of " + k);
                    assertEquals(role.wire(), rs.getString("r"), "role of " + k);
                    assertEquals(role == Role.TRAIN, rs.getBoolean("is_train"), k);
                    assertEquals(role == Role.VALIDATION, rs.getBoolean("is_val"), k);
                    assertEquals(role == Role.TEST, rs.getBoolean("is_test"), k);
                    checked++;
                }
            }
            assertEquals(keys.size(), checked);
        }
    }

    @Test
    void anIntegerKeyHashesAsItsText() throws Exception {
        HashSplit s = new HashSplit(5, 5000, 2500);
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT k, " + s.bucketSql("k") + " FROM (SELECT UNNEST([-3, 0, 17, 123456789012]) AS k)")) {
            while (rs.next()) assertEquals(s.bucket(Long.toString(rs.getLong(1))), rs.getInt(2));
        }
    }

    @Test
    void timeSplitMatchesTheJvm() throws Exception {
        // 13 distinct days, several rows per day, plus a NULL bucket
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE ts AS SELECT DATE '2026-01-01' + (i % 13)::INTEGER AS day, i AS v FROM range(40) r(i)");
            st.execute("INSERT INTO ts VALUES (NULL, 999)");
        }
        for (TimeSplit s : List.of(TimeSplit.lastBuckets(2, 3), TimeSplit.lastBuckets(0, 1), TimeSplit.fractions(0.15, 0.2),
                TimeSplit.fractions(0.1, 0.1))) {
            int rows = 0;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT date_diff('day', DATE '2026-01-01', day) AS idx, split FROM ("
                         + s.sql("ts", "day", "split") + ")")) {
                while (rs.next()) {
                    int idx = rs.getInt("idx");
                    if (rs.wasNull()) {
                        assertNull(rs.getString("split"), "a NULL bucket has no role");
                        continue;
                    }
                    assertEquals(s.role(idx, 13).wire(), rs.getString("split"), s + " day " + idx);
                    rows++;
                }
            }
            assertEquals(40, rows, s.toString());
        }
    }

    @Test
    void timeSplitWithNoTrainBucketFailsAsTheJvm() throws Exception {
        TimeSplit s = TimeSplit.lastBuckets(10, 3);
        assertThrows(IllegalArgumentException.class, () -> s.counts(13));
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE ts2 AS SELECT DATE '2026-01-01' + i::INTEGER AS day FROM range(13) r(i)");
            SQLException e = assertThrows(SQLException.class, () -> {
                try (ResultSet rs = st.executeQuery(s.sql("ts2", "day", "split"))) {
                    while (rs.next()) { /* drain */ }
                }
            });
            assertTrue(e.getMessage().contains("no train bucket"), e.getMessage());
        }
    }
}
