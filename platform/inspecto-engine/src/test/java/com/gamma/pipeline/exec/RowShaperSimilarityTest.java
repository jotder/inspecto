package com.gamma.pipeline.exec;

import com.gamma.analytics.similarity.Similarity;
import com.gamma.analytics.similarity.SimilaritySql.Method;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineRel;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code transform.similarity} (ANALYTICS-CORE-1 S1, catalog {@code quality.match.fuzzy}) — {@link RowShaper} over an
 * embedded DuckDB input. Every written score equals the Analytics Kernel's JVM twin of the method, so a figure in the
 * output can be recomputed outside DuckDB; rows are never dropped, merged or reordered (D-M1: a candidate, never an
 * identity).
 */
class RowShaperSimilarityTest {

    private File db;
    private Connection conn;
    private int runs;

    private static final String[][] ROWS = {
            {"1", "José Müller", "jose  MUELLER"},
            {"2", "Vladimir Putin", "Putin Vladimir"},
            {"3", "Acme Trading LLC", "ACME Holding"},
            {"4", "Crème Brûlée", "creme brulee"},
            {"5", "", "anything"},
            {"6", null, "x"},
    };

    @BeforeEach
    void open() throws Exception {
        db = DuckDbUtil.tempDbFile("rssim_");
        conn = DuckDbUtil.openConnection(db);
        StringBuilder values = new StringBuilder();
        for (String[] r : ROWS)
            values.append(values.isEmpty() ? "" : ", ").append("(").append(r[0]).append(", ")
                    .append(r[1] == null ? "NULL" : "'" + r[1] + "'").append(", '").append(r[2]).append("')");
        exec("CREATE TABLE src AS SELECT * FROM (VALUES " + values + ") t(id, name_a, name_b)");
    }

    @AfterEach
    void close() throws Exception {
        if (conn != null) conn.close();
        DuckDbUtil.deleteTempDb(db);
    }

    private String run(Map<String, Object> cfg) throws SQLException {
        return RowShaper.shape(conn, PipelineNode.of("n", "transform.similarity", cfg), "src", "n_" + (++runs))
                .stream().filter(r -> r.rel().equals(PipelineRel.DATA)).findFirst().orElseThrow().table();
    }

    private static Map<String, Object> cfg(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void everyMethodWritesTheKernelsScoreForEveryRowNormalizedByDefault() throws Exception {
        for (Method m : Method.values()) {
            String out = run(cfg("left", "name_a", "right", "name_b", "method", m.configName()));
            List<Object[]> got = rows("SELECT id, similarity FROM \"" + out + "\" ORDER BY id");
            assertEquals(ROWS.length, got.size(), m + ": every inbound row is kept");
            for (int i = 0; i < ROWS.length; i++) {
                String a = ROWS[i][1], b = ROWS[i][2];
                if (a == null) { assertNull(got.get(i)[1], m + ": NULL on a side gives no score"); continue; }
                double expected = m.score(Similarity.normalize(a), Similarity.normalize(b));
                assertEquals(expected, (Double) got.get(i)[1], 0.0, m + " row " + ROWS[i][0]);
            }
        }
        // the hand-checkable ones: accents and case gone, order-free tokens; no transliteration (müller is not mueller)
        String out = run(cfg("left", "name_a", "right", "name_b", "method", "token_jaccard"));
        assertEquals(1.0, scalar("SELECT similarity FROM \"" + out + "\" WHERE id = 2"));
        assertEquals(1.0, scalar("SELECT similarity FROM \"" + out + "\" WHERE id = 4"));
        assertEquals(0.25, scalar("SELECT similarity FROM \"" + out + "\" WHERE id = 3"), 1e-15);   // {acme} of 4 words
        assertEquals(0.0, scalar("SELECT similarity FROM \"" + out + "\" WHERE id = 5"), "an empty side scores 0");
    }

    @Test
    void normalizeOffComparesTheRawText() throws Exception {
        String out = run(cfg("left", "name_a", "right", "name_b", "method", "levenshtein", "normalize", false));
        assertEquals(Method.LEVENSHTEIN.score("Crème Brûlée", "creme brulee"),
                scalar("SELECT similarity FROM \"" + out + "\" WHERE id = 4"), 0.0);
        assertTrue(scalar("SELECT similarity FROM \"" + out + "\" WHERE id = 4") < 1.0);
    }

    @Test
    void aThresholdAddsACandidateFlagAndKeepScoreFalseKeepsOnlyTheFlag() throws Exception {
        String out = run(cfg("left", "name_a", "right", "name_b", "method", "token_jaccard", "threshold", 0.5,
                "score_column", "name_score", "candidate_column", "review"));
        assertEquals(List.of("id", "name_a", "name_b", "name_score", "review"), columns(out));
        List<Object[]> got = rows("SELECT id, review FROM \"" + out + "\" ORDER BY id");
        assertEquals(List.of(false, true, false, true, false), got.subList(0, 5).stream().map(r -> r[1]).toList());
        assertNull(got.get(5)[1], "no score, no candidate flag");

        String only = run(cfg("left", "name_a", "right", "name_b", "threshold", 0.9, "keep_score", false));
        assertEquals(List.of("id", "name_a", "name_b", "similarity_candidate"), columns(only),
                "the scratch score column is not left behind");
    }

    /**
     * The per-value length cap ({@link SimilarityStep#MAX_TEXT_LENGTH}, characters of the raw text): a value at the cap
     * scores as before, one character past it fails the run naming the cap - the kernel's caps fail closed.
     */
    @Test
    void aValueLongerThanTheCapFailsTheRunAndOneAtTheCapScores() throws Exception {
        int cap = SimilarityStep.MAX_TEXT_LENGTH;
        exec("CREATE TABLE src_ok AS SELECT 1 AS id, repeat('é', " + cap + ") AS name_a, repeat('e', " + cap
                + ") AS name_b");
        String out = RowShaper.shape(conn, PipelineNode.of("n", "transform.similarity",
                        cfg("left", "name_a", "right", "name_b", "method", "levenshtein")), "src_ok", "n_ok").stream()
                .filter(r -> r.rel().equals(PipelineRel.DATA)).findFirst().orElseThrow().table();
        assertEquals(1.0, scalar("SELECT similarity FROM \"" + out + "\""), 0.0, "accents normalized away, at the cap");

        exec("CREATE TABLE src_long AS SELECT * FROM (VALUES (1, 'a', 'a'), (2, 'b', repeat('b', " + (cap + 1)
                + "))) t(id, name_a, name_b)");
        SQLException e = assertThrows(SQLException.class, () -> RowShaper.shape(conn,
                PipelineNode.of("n", "transform.similarity", cfg("left", "name_a", "right", "name_b")), "src_long",
                "n_long"));
        assertTrue(e.getMessage().contains("longer than " + cap + " characters"), e.getMessage());
        assertTrue(e.getMessage().contains("'name_a' or 'name_b'"), e.getMessage());
    }

    @Test
    void refusalsNameTheProblem() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> run(cfg("left", "name_a", "right", "nope")));
        assertTrue(missing.getMessage().contains("'nope' is not in the inbound data"), missing.getMessage());
        IllegalArgumentException collides = assertThrows(IllegalArgumentException.class,
                () -> run(cfg("left", "name_a", "right", "name_b", "score_column", "ID")));
        assertTrue(collides.getMessage().contains("collides"), collides.getMessage());
        IllegalArgumentException method = assertThrows(IllegalArgumentException.class,
                () -> run(cfg("left", "name_a", "right", "name_b", "method", "soundex")));
        assertTrue(method.getMessage().contains("'soundex' is not one of [jaro, jaro_winkler, levenshtein, token_jaccard]"),
                method.getMessage());
        assertThrows(IllegalArgumentException.class, () -> run(cfg("left", "name_a", "right", "name_b", "threshold", 1.5)));
        assertThrows(IllegalArgumentException.class, () -> run(cfg("left", "name_a", "right", "name_b", "keep_score", false)));
        assertThrows(IllegalArgumentException.class, () -> run(cfg("left", "name_a", "right", "name_b", "salt", "x")));
        IllegalArgumentException injected = assertThrows(IllegalArgumentException.class,
                () -> run(cfg("left", "name_a\" ; DROP TABLE src; --", "right", "name_b")));
        assertTrue(injected.getMessage().contains("not a plain column name"), injected.getMessage());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────────

    private void exec(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private double scalar(String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getDouble(1);
        }
    }

    private List<Object[]> rows(String sql) throws SQLException {
        List<Object[]> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Object[] r = new Object[n];
                for (int i = 0; i < n; i++) r[i] = rs.getObject(i + 1);
                out.add(r);
            }
        }
        return out;
    }

    private List<String> columns(String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM \"" + table + "\" LIMIT 0")) {
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) out.add(rs.getMetaData().getColumnName(i));
        }
        return out;
    }
}
