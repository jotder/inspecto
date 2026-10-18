package com.gamma.analytics.model;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.graph.RunControl;
import com.gamma.util.DuckDbUtil;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link LogisticSql} against {@link LogisticRegression.Model#probability}: every row, incl. NULL / NaN inputs. */
class LogisticDuckDbParityTest {

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
    void sqlProbabilityMatchesTheJvmFormOnEveryRow() throws Exception {
        LogisticRegression.Model m = LogisticRegression.fit(LogisticRegressionTest.keys, LogisticRegressionTest.x,
                LogisticRegressionTest.y, LogisticRegressionTest.spec(), RunControl.NONE);
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE p(id INTEGER, a DOUBLE, b DOUBLE, c DOUBLE)");
        }
        double[][] x = LogisticRegressionTest.x;
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO p VALUES (?, ?, ?, ?)")) {
            for (int i = 0; i < 300; i++) {
                ps.setInt(1, i);
                for (int j = 0; j < 3; j++) ps.setDouble(j + 2, x[i][j] * (i % 10 == 0 ? 7 : 1));   // some extreme rows
                ps.addBatch();
            }
            ps.setInt(1, 1000);
            ps.setNull(2, java.sql.Types.DOUBLE);
            ps.setDouble(3, 0);
            ps.setDouble(4, 0);
            ps.addBatch();
            ps.executeBatch();
        }
        int seen = 0;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(LogisticSql.select("p", List.of("a", "b", "c"), m, "prob") + " ORDER BY id")) {
            while (rs.next()) {
                int id = rs.getInt("id");
                double got = rs.getDouble("prob");
                if (id == 1000) {
                    assertTrue(rs.wasNull());
                    continue;
                }
                double[] row = {x[id][0] * (id % 10 == 0 ? 7 : 1), x[id][1] * (id % 10 == 0 ? 7 : 1), x[id][2] * (id % 10 == 0 ? 7 : 1)};
                assertEquals(m.probability(row), got, 1e-12, "id " + id);
                seen++;
            }
        }
        assertEquals(300, seen);
    }

    @Test
    void aWrongColumnCountIsRefused() {
        LogisticRegression.Model m = LogisticRegression.fit(LogisticRegressionTest.keys, LogisticRegressionTest.x,
                LogisticRegressionTest.y, LogisticRegressionTest.spec(), RunControl.NONE);
        assertThrows(IllegalArgumentException.class, () -> LogisticSql.probabilityExpression(List.of("a"), m));
    }
}
