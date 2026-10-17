package com.gamma.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The statistical Measure aggregates (D-AN5) RUN on DuckDB and return the golden values the committed
 * {@code measure-grammar.contract.json} publishes — the same values {@code query-spec.spec.ts} holds the offline
 * twin to, so the live and offline paths of one widget agree. Compiling the right-looking string is not evidence.
 */
class MeasureStatisticsExecutionTest {

    private static final double EPS = 1e-9;

    private static JsonNode statistics() throws Exception {
        return new ObjectMapper().readTree(MeasureGrammarContractTest.contractPath().toFile()).get("statistics");
    }

    /** An inline relation {@code (amount)} over the values; a JSON null is a SQL NULL the aggregate must ignore. */
    private static String relation(JsonNode values) {
        List<String> rows = new ArrayList<>();
        for (JsonNode v : values) rows.add("(" + (v.isNull() ? "NULL" : v.asText()) + "::BIGINT)");
        return "SELECT * FROM (VALUES " + String.join(", ", rows) + ") AS t(amount)";
    }

    private static Object run(JsonNode values, String agg) throws Exception {
        MeasureCompiler.Spec spec = MeasureCompiler.parse(Map.of("dataset", "d",
                "measures", List.of(Map.of("agg", agg, "field", "amount"))), 500, 10_000);
        MeasureCompiler.Compiled sql = MeasureCompiler.compile(spec);
        List<Map<String, Object>> rows = QueryExecutor.run(new QueryExecutor.Request("d", relation(values), sql.sql(),
                500, 0, List.of(), List.of(), sql.params())).rows();
        return rows.get(0).get(agg + "_amount");
    }

    @Test
    void eachStatisticReturnsTheContractsGoldenValue() throws Exception {
        JsonNode stats = statistics();
        stats.get("expected").fields().forEachRemaining(e -> {
            try {
                Object got = run(stats.get("values"), e.getKey());
                assertEquals(e.getValue().asDouble(), ((Number) got).doubleValue(), EPS, e.getKey());
            } catch (Exception ex) {
                throw new AssertionError(e.getKey(), ex);
            }
        });
        assertEquals(List.of("median", "stddev", "variance", "p05", "p25", "p75", "p90", "p95", "p99"),
                iterate(stats.get("expected")), "the contract's goldens cover every statistic");
    }

    @Test
    void anEvenCountMedianAveragesTheMiddlePair() throws Exception {
        JsonNode stats = statistics();
        assertEquals(stats.get("evenMedian").asDouble(),
                ((Number) run(stats.get("evenValues"), "median")).doubleValue(), EPS);
    }

    /** SAMPLE spread (n - 1): one value has no spread, so it is NULL, not 0 — what DuckDB's stddev_samp returns. */
    @Test
    void aSampleSpreadOverOneValueIsNull() throws Exception {
        JsonNode one = new ObjectMapper().readTree("[7]");
        assertNull(run(one, "stddev"));
        assertNull(run(one, "variance"));
        assertEquals(7.0, ((Number) run(one, "p95")).doubleValue(), EPS);
    }

    private static List<String> iterate(JsonNode obj) {
        List<String> out = new ArrayList<>();
        obj.fieldNames().forEachRemaining(out::add);
        return out;
    }
}
