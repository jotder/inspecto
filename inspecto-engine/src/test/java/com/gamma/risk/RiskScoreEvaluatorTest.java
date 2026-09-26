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
        var run = RiskScoreEvaluator.evaluate(m, id -> DatasetRelation.relationSql(Map.of("physicalRef", id), data, null));
        assertEquals(List.of("m1", "m2"), run.scored().stream().map(RiskScorer.Scored::entityKey).toList(),
                "exactly the rows whose value is it's fraud — the quote neither broke nor widened the filter");
        run.scored().forEach(s -> assertEquals(10.0, s.score(), 1e-9));

        RiskScoreModel inject = RiskScoreModel.fromMap("q2", Map.of("entityType", "subscriber", "highThreshold", 50,
                "factors", List.of(Map.of("id", "t", "dataset", "notes", "key", "msisdn", "measure", "count",
                        "weight", 10, "filters", List.of(Map.of("field", "tag", "op", "=", "value", "x' OR '1'='1"))))));
        assertTrue(RiskScoreEvaluator.evaluate(inject,
                id -> DatasetRelation.relationSql(Map.of("physicalRef", id), data, null)).scored().isEmpty(),
                "an injection attempt is a literal that matches nothing");
    }
}
