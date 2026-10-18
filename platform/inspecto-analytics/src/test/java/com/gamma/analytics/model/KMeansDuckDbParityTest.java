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
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link KMeansSql} (nearest stored centroid in DuckDB) against {@link KMeans.Model#assign}: every row, incl. NULL / NaN. */
class KMeansDuckDbParityTest {

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
    void sqlAssignsEveryRowToTheClusterTheJvmFormDoes() throws Exception {
        Random r = new Random(11);
        double[][] train = new double[300][];
        for (int i = 0; i < train.length; i++)
            train[i] = new double[] {Math.rint(r.nextGaussian() * 300) / 10 + (i % 4) * 20, r.nextGaussian() * 1e-3 + (i % 3) * 5e-3, 1e6 + r.nextInt(50)};
        KMeans.Model model = KMeans.fit(train, KMeans.Spec.of(5, 2026), RunControl.NONE).model();

        double[][] probe = new double[500][];
        for (int i = 0; i < probe.length; i++)
            probe[i] = new double[] {r.nextGaussian() * 40 + 30, r.nextGaussian() * 5e-3, 1e6 + r.nextInt(60) - 5};
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE p(id INTEGER, a DOUBLE, b DOUBLE, c DOUBLE)");
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO p VALUES (?, ?, ?, ?)")) {
            for (int i = 0; i < probe.length; i++) {
                ps.setInt(1, i);
                for (int j = 0; j < 3; j++) ps.setDouble(j + 2, probe[i][j]);
                ps.addBatch();
            }
            // a NULL in an input and a NaN: no cluster
            ps.setInt(1, 1000);
            ps.setNull(2, java.sql.Types.DOUBLE);
            ps.setDouble(3, 0);
            ps.setDouble(4, 0);
            ps.addBatch();
            ps.setInt(1, 1001);
            ps.setDouble(2, Double.NaN);
            ps.setDouble(3, 0);
            ps.setDouble(4, 0);
            ps.addBatch();
            ps.executeBatch();
        }
        int seen = 0;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(KMeansSql.select("p", List.of("a", "b", "c"), model, "cluster") + " ORDER BY id")) {
            while (rs.next()) {
                int id = rs.getInt("id");
                int got = rs.getInt("cluster");
                if (id >= 1000) {
                    assertTrue(rs.wasNull(), "id " + id + ": a missing input has no cluster");
                    continue;
                }
                assertEquals(model.assign(probe[id]), got, "id " + id);
                seen++;
            }
        }
        assertEquals(500, seen);
    }

    @Test
    void aWrongColumnCountIsRefused() {
        KMeans.Model model = KMeans.fit(KMeansTest.blobs(), KMeans.Spec.of(3, 1), RunControl.NONE).model();
        assertThrows(IllegalArgumentException.class, () -> KMeansSql.clusterExpression(List.of("a"), model));
    }
}
