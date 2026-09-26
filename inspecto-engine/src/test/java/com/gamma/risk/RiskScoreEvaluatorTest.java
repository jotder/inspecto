package com.gamma.risk;

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
            RiskScoreModel.fromMap("none", Map.of("entityType", "x", "highThreshold", 1,
                    "factors", List.of(Map.of("id", "f", "dataset", "d", "key", "k", "measure", "count", "weight", 1)))));

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
}
