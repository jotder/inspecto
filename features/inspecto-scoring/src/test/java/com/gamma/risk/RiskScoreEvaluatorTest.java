package com.gamma.risk;

import com.gamma.mask.EvidenceMasker;
import com.gamma.query.DatasetRelation;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The evaluator over real DuckDB: an author's filter value is a typed literal, quote included. */
class RiskScoreEvaluatorTest {

    /** No Dataset in these tests declares a classified column, so the masker masks nothing. */
    private static final EvidenceMasker NO_MASK = EvidenceMasker.of(
            new com.gamma.pipeline.ComponentStore(Path.of("does-not-exist", "registry")), Path.of("does-not-exist", "cfg"),
            List.of("d"));

    @Test
    void aQuoteInsideAFilterValueIsEscapedNotInterpreted(@TempDir Path data) throws Exception {
        DuckDbUtil.loadDriver();
        Path dir = Files.createDirectories(data.resolve("notes"));
        String file = dir.resolve("data.parquet").toString().replace('\\', '/');
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES ('m1', 'it''s fraud'), ('m1', 'x'), ('m2', 'it''s fraud'), "
                    + "('m3', 'x')) AS v(msisdn, tag)) TO '" + file + "' (FORMAT PARQUET)");
        }
        RiskScoreModel m = RiskScoreModel.fromMap("q", Map.of("entityType", "subscriber", "highThreshold", 50,
                "factors", List.of(Map.of("id", "tagged", "dataset", "notes", "key", "msisdn", "measure", "count",
                        "weight", 10, "filters", List.of(Map.of("field", "tag", "op", "=", "value", "it's fraud"))))));
        var run = RiskScoreEvaluator.evaluate(m, id -> DatasetRelation.relationSql(Map.of("physicalRef", id), data, null), NO_MASK);
        assertEquals(List.of("m1", "m2"), run.scored().stream().map(RiskScorer.Scored::entityKey).toList(),
                "exactly the rows whose value is it's fraud — the quote neither broke nor widened the filter");
        run.scored().forEach(s -> assertEquals(10.0, s.score(), 1e-9));

        RiskScoreModel inject = RiskScoreModel.fromMap("q2", Map.of("entityType", "subscriber", "highThreshold", 50,
                "factors", List.of(Map.of("id", "t", "dataset", "notes", "key", "msisdn", "measure", "count",
                        "weight", 10, "filters", List.of(Map.of("field", "tag", "op", "=", "value", "x' OR '1'='1"))))));
        assertTrue(RiskScoreEvaluator.evaluate(inject,
                id -> DatasetRelation.relationSql(Map.of("physicalRef", id), data, null), NO_MASK).scored().isEmpty(),
                "an injection attempt is a literal that matches nothing");
    }

    @Test
    void aPreviewScoresOnlyTheNamedEntityAndAnUnknownOneScoresZero(@TempDir Path data) throws Exception {
        DuckDbUtil.loadDriver();
        Path dir = Files.createDirectories(data.resolve("notes"));
        String file = dir.resolve("data.parquet").toString().replace('\\', '/');
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES ('m1', 'a'), ('m2', 'b'), ('m2', 'c'), ('m3', 'd')) AS v(msisdn, tag)) TO '"
                    + file + "' (FORMAT PARQUET)");
        }
        RiskScoreModel m = RiskScoreModel.fromMap("p", Map.of("entityType", "subscriber", "highThreshold", 50,
                "factors", List.of(Map.of("id", "n", "dataset", "notes", "key", "msisdn", "measure", "count",
                        "weight", 10, "evidence", List.of("tag")))));
        java.util.function.Function<String, String> rel = id -> DatasetRelation.relationSql(Map.of("physicalRef", id), data, null);
        // Three entities and a one-entity cap: only the narrowing makes this succeed (without it, the cap trips).
        RiskScoreEvaluator.Preview p = RiskScoreEvaluator.preview(m, "m2", rel, NO_MASK);
        assertTrue(p.found());
        assertEquals("m2", p.scored().entityKey());
        assertEquals(20.0, p.scored().score(), 1e-9);
        assertEquals(2, p.scored().factors().get(0).evidence().size());

        RiskScoreEvaluator.Preview none = RiskScoreEvaluator.preview(m, "m9", rel, NO_MASK);
        assertFalse(none.found());
        assertEquals(0.0, none.scored().score(), 1e-9);
    }

    @Test
    void aRunOverTheEntityCapFailsAndARaisedPerModelCapPasses(@TempDir Path data) throws Exception {
        DuckDbUtil.loadDriver();
        Path dir = Files.createDirectories(data.resolve("notes"));
        String file = dir.resolve("data.parquet").toString().replace('\\', '/');
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES ('m1'), ('m2'), ('m3')) AS v(msisdn)) TO '" + file + "' (FORMAT PARQUET)");
        }
        java.util.function.Function<String, String> rel = id -> DatasetRelation.relationSql(Map.of("physicalRef", id), data, null);
        java.util.function.Function<Object, RiskScoreModel> capped = cap -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>(Map.of("entityType", "subscriber", "highThreshold", 50,
                    "factors", List.of(Map.of("id", "n", "dataset", "notes", "key", "msisdn", "measure", "count", "weight", 1))));
            m.put("maxEntities", cap);
            return RiskScoreModel.fromMap("cap", m);
        };
        // Exactly AT the cap passes (the cap is inclusive); one over FAILS, never a partial run.
        assertEquals(3, RiskScoreEvaluator.evaluate(capped.apply(3), rel, NO_MASK).scored().size());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RiskScoreEvaluator.evaluate(capped.apply(2), rel, NO_MASK));
        assertTrue(e.getMessage().contains("more than 2 entities"), e.getMessage());
        assertTrue(e.getMessage().contains("maxEntities") && e.getMessage().contains("-Drisk.score.maxEntities"),
                "the message says how to raise the cap: " + e.getMessage());

        for (Object bad : List.of(0, -5, 1.5, "lots", RiskScoreModel.MAX_ENTITIES_CEILING + 1))
            assertThrows(IllegalArgumentException.class, () -> capped.apply(bad), "refused at save: " + bad);
        assertEquals(RiskScoreModel.MAX_ENTITIES_CEILING, capped.apply(RiskScoreModel.MAX_ENTITIES_CEILING).maxEntities());
    }

    @Test
    void theSystemDefaultCapIgnoresABadValueAndNeverDisablesTheCap() {
        assertEquals(RiskScoreEvaluator.MAX_ENTITIES, RiskScoreEvaluator.defaultMaxEntities(null));
        assertEquals(500_000, RiskScoreEvaluator.defaultMaxEntities(" 500000 "));
        for (String bad : List.of("", "abc", "0", "-1", "1.5", "99999999999"))
            assertEquals(RiskScoreEvaluator.MAX_ENTITIES, RiskScoreEvaluator.defaultMaxEntities(bad), "kept default for '" + bad + "'");
    }

    @Test
    void aQueryFailureNeverQuotesASourceValue(@TempDir Path data) throws Exception {
        DuckDbUtil.loadDriver();
        Path dir = Files.createDirectories(data.resolve("spend"));
        String file = dir.resolve("data.parquet").toString().replace('\\', '/');
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES ('m1', '447700900123x')) AS v(msisdn, amount)) TO '"
                    + file + "' (FORMAT PARQUET)");
        }
        // A virtual Dataset casting the text column to a number: the cast fails AT RUN TIME on the cell value.
        Map<String, Object> ds = Map.of("sql", "SELECT msisdn, CAST(amount AS INTEGER) AS amount FROM spend",
                "sourceName", "spend");
        RiskScoreModel m = RiskScoreModel.fromMap("bad", Map.of("entityType", "subscriber", "highThreshold", 50,
                "factors", List.of(Map.of("id", "spend", "dataset", "spend_num", "key", "msisdn", "measure",
                        "sum(amount)", "weight", 1))));
        java.util.function.Function<String, String> rel = id -> DatasetRelation.relationSql(ds, data, null);

        // The premise, proven: the SAME query run raw DOES quote the value — so the wrapper is what hides it.
        Throwable rawT = assertThrows(Exception.class, () -> com.gamma.query.QueryExecutor.run(
                new com.gamma.query.QueryExecutor.Request("spend_num", rel.apply("spend_num"),
                        com.gamma.query.MeasureCompiler.compile(m.factors().get(0).valueSpec(10)), 10, 0,
                        List.of(), List.of())));
        assertTrue(rawT.getMessage().contains("447700900123"), "DuckDB's own message quotes the cell: " + rawT.getMessage());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> RiskScoreEvaluator.evaluate(m, rel, NO_MASK));
        assertFalse(e.getMessage().contains("447700900123"), e.getMessage());
        assertNull(e.getCause(), "the cause (which carries the DuckDB message) is not attached");
        assertTrue(e.getMessage().contains("factor 'spend'"), e.getMessage());
    }

    /** BI-QUERY-TRUNCATION-1: the evidence read asked for exactly its cap, so it could never report a cut. */
    @Test
    void evidenceReadPastItsCapIsFlaggedAndAnExactFitIsNot(@TempDir Path data) throws Exception {
        DuckDbUtil.loadDriver();
        Path dir = Files.createDirectories(data.resolve("notes"));
        String file = dir.resolve("data.parquet").toString().replace('\\', '/');
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES ('m1','a'),('m2','b'),('m3','c'),('m4','d')) AS v(msisdn, tag)) TO '"
                    + file + "' (FORMAT PARQUET)");
        }
        RiskScoreModel m = RiskScoreModel.fromMap("ev", Map.of("entityType", "subscriber", "highThreshold", 50,
                "factors", List.of(Map.of("id", "n", "dataset", "notes", "key", "msisdn", "measure", "count",
                        "weight", 10, "evidence", List.of("tag")))));
        java.util.function.Function<String, String> rel = id -> DatasetRelation.relationSql(Map.of("physicalRef", id), data, null);
        var cut = RiskScoreEvaluator.evaluate(m, rel, NO_MASK, 100, 3, com.gamma.sql.SqlSandboxPolicy.defaultPolicy());
        assertTrue(cut.evidenceTruncated(), "4 evidence rows, cap 3");
        assertEquals(4, cut.scored().size(), "scores are never refused over evidence");
        assertFalse(RiskScoreEvaluator.evaluate(m, rel, NO_MASK, 100, 4, com.gamma.sql.SqlSandboxPolicy.defaultPolicy())
                .evidenceTruncated(), "exactly the cap is not a cut");
    }
}
