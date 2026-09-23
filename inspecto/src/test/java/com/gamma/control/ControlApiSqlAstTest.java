package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code POST /components/sql/ast} — the READ-ONLY structure of author SQL, as DuckDB's own
 * {@code json_serialize_sql} parse tree (AUTHORING-REDESIGN-1 (c), design Step 1).
 *
 * <p>Every gate the route has, over real HTTP: 200/{@code ok:true}, 200/{@code ok:false} on a syntax
 * error (a parse failure is the ANSWER, not a server error — the case an implementer reflexively turns
 * into a 422), 400 on a missing {@code sql} or an unknown {@code fragment}, the {@code predicate}
 * wrapping, and the operator's Q3 decision: NO {@code SqlGuard} on this route — nothing executes, and
 * {@code json_serialize_sql} itself refuses anything but a SELECT (T5).
 */
class ControlApiSqlAstTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    @Test
    void aStatementReturnsTheEngineParseTreeVerbatim(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> res = post(c, "{\"sql\":\"SELECT a FROM t WHERE b > 1\"}");
            assertEquals(200, res.statusCode(), res.body());
            JsonNode data = V1Body.of(res.body());
            assertTrue(data.get("ok").asBoolean(), res.body());
            // Passed through verbatim: DuckDB's own top-level shape, not a house model.
            JsonNode where = data.get("ast").get("statements").get(0).get("node").get("where_clause");
            assertEquals("COMPARE_GREATERTHAN", where.get("type").asText(), res.body());
        }
    }

    @Test
    void aPredicateIsWrappedAndItsWhereClauseReadOut(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> res = post(c,
                    "{\"sql\":\"STATUS = 'SHIPPED' AND GROSS >= 30\",\"fragment\":\"predicate\"}");
            assertEquals(200, res.statusCode(), res.body());
            JsonNode data = V1Body.of(res.body());
            assertTrue(data.get("ok").asBoolean(), res.body());
            JsonNode ast = data.get("ast");
            assertEquals("CONJUNCTION_AND", ast.get("type").asText(), res.body());
            assertEquals("STATUS", ast.get("children").get(0).get("left").get("column_names").get(0).asText());
        }
    }

    /** T6: a parse error is DATA. 200 with {@code ok:false} and DuckDB's message + position, never a 4xx/5xx. */
    @Test
    void aSyntaxErrorIsAnOkFalseAnswerNotAnError(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> res = post(c, "{\"sql\":\"amount > > 1\",\"fragment\":\"predicate\"}");
            assertEquals(200, res.statusCode(), res.body());
            JsonNode data = V1Body.of(res.body());
            assertFalse(data.get("ok").asBoolean(), res.body());
            JsonNode err = data.get("error");
            assertTrue(err.get("message").asText().contains("syntax error"), res.body());
            // The position is into what the AUTHOR wrote, not into the hidden wrapper statement.
            assertEquals(9, err.get("position").asInt(), res.body());
            assertNull(data.get("ast"), res.body());
        }
    }

    /** T5: only SELECT serializes, and a smuggled second statement refuses the whole call. */
    @Test
    void aNonSelectIsRefusedByTheEngineItself(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode del = V1Body.of(post(c, "{\"sql\":\"DELETE FROM t WHERE a = 1\"}").body());
            assertFalse(del.get("ok").asBoolean(), del.toString());
            JsonNode drop = V1Body.of(post(c,
                    "{\"sql\":\"a = 1; DROP TABLE t\",\"fragment\":\"predicate\"}").body());
            assertFalse(drop.get("ok").asBoolean(), drop.toString());
        }
    }

    /** A "predicate" that carries its own clauses is not a row predicate — refused, not half-read. */
    @Test
    void aPredicateThatSmugglesClausesIsNotAPredicate(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            for (String pred : List.of("a > 1 ORDER BY 1", "a > 1 GROUP BY a", "a > 1 UNION SELECT 2", "a > 1 LIMIT 3")) {
                JsonNode data = V1Body.of(post(c,
                        "{\"sql\":\"" + pred + "\",\"fragment\":\"predicate\"}").body());
                assertFalse(data.get("ok").asBoolean(), pred + " -> " + data);
                assertEquals("NOT_A_PREDICATE", data.get("error").get("subtype").asText(), pred + " -> " + data);
            }
        }
    }

    /**
     * Q3 (operator 2026-09-23): no {@code SqlGuard} here. Its lexical blocklist false-rejects legal
     * predicates naming {@code set}/{@code replace}; and since nothing binds, even a {@code read_csv} of a
     * REAL file is only parsed — the file is never opened. The sibling {@code describe} route binds, and
     * keeps its guard ({@code ControlApiComponentsTest.describeRefusesFileReadingSqlTheExecutorWouldAlsoRefuse}).
     */
    @Test
    void thereIsNoLexicalGuardBecauseNothingBinds(@TempDir Path dir) throws Exception {
        Path leak = dir.resolve("leak.csv");
        Files.writeString(leak, "secret,amount\nacme,42\n");
        try (Ctx c = open(dir)) {
            JsonNode legal = V1Body.of(post(c,
                    "{\"sql\":\"replace(set, 'x', '') = 'y'\",\"fragment\":\"predicate\"}").body());
            assertTrue(legal.get("ok").asBoolean(), legal.toString());

            HttpResponse<String> res = post(c, "{\"sql\":\"SELECT * FROM read_csv('"
                    + leak.toString().replace("\\", "/") + "')\"}");
            assertEquals(200, res.statusCode(), res.body());
            assertTrue(V1Body.of(res.body()).get("ok").asBoolean(), res.body());
            assertFalse(res.body().contains("acme"), "parse-only: the file's contents never appear");
        }
    }

    @Test
    void aMissingSqlOrAnUnknownFragmentIs400(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(400, post(c, "{}").statusCode());
            assertEquals(400, post(c, "{\"sql\":\"   \"}").statusCode());
            assertEquals(400, post(c, "{\"sql\":\"a > 1\",\"fragment\":\"join\"}").statusCode());
        }
    }

    private HttpResponse<String> post(Ctx c, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/components/sql/ast"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body)).build();
        return client.send(req, BodyHandlers.ofString());
    }
}
