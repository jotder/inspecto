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
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * COMPONENT-KIND-KEY-CENSUS-1, {@code expectation} third of DUCKLE-C3-DEAD-PROPERTY-1: a top-level key
 * of an expectation body that nothing reads is refused, on <b>both</b> routes that persist one.
 *
 * <p>🔴 {@code expectation} has TWO write paths and they fail differently, which is why one census is
 * shared between them ({@code ComponentRoutes.refuseUnknownComponentKeys}):
 * <ul>
 *   <li>{@code POST|PUT /expectations} ({@link ExpectationRoutes}) — what the SPA saves through. It
 *       rebuilds the persisted content from {@code Expectation.toMap()}, so an unknown key was
 *       <b>silently dropped</b>: 200, key gone, no diagnostic.</li>
 *   <li>{@code POST|PUT /components/expectation} ({@link ComponentRoutes}) — a raw-body back door that
 *       never ran {@code Expectation.fromMap} at all, so an unknown key was <b>persisted dead</b>, and
 *       the resulting component was then served by {@code GET /expectations}.</li>
 * </ul>
 *
 * <p>⚠ The negative halves matter more than the positive one. The accepted set is NOT just
 * {@code ConfigSpecs.expectation()}'s fields: {@code when} is the {@code condition} kind's predicate
 * tree, read by {@code Expectation.fromMap} and compiled by {@code ConditionSql}, yet the spec predates
 * the 2026-07-18 {@code condition} promotion and declares it nowhere — a naive spec-derived refusal
 * would have rejected every condition expectation. Same trap as {@code dashboard.description}.
 * {@code lastResult}/{@code createdAt}/{@code updatedAt} are route bookkeeping and would have rejected
 * every GET→PUT round trip.
 */
class ControlApiExpectationCensusTest {

    private final HttpClient client = HttpClient.newHttpClient();

    /** The full upsert shape the SPA sends (expectation-form.dialog.ts save()) — every key, none extra. */
    private static final String STUDIO_SAVE_SHAPE = """
            {"name":"e_ok","description":"d","targetType":"pipeline","target":"orders","column":"ID",\
            "kind":"range","min":1,"max":9,"pattern":null,"refDataset":null,"refColumn":null,\
            "when":null,"severity":"MINOR","enabled":true}""";

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir, Path writeRoot) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        String prior = System.getProperty("assist.write.root");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        else System.clearProperty("assist.write.root");
        try {
            ControlApi api = new ControlApi(svc, 0);   // captures the write root at construction
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            if (prior != null) System.setProperty("assist.write.root", prior);
            else System.clearProperty("assist.write.root");
        }
    }

    /** A key nothing reads is refused rather than silently dropped, and no expectation is written. */
    @Test
    void anUnknownTopLevelKeyIsRefusedInsteadOfSilentlyDropped(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            HttpResponse<String> bad = send(c.port, "POST", "/expectations",
                    "{\"name\":\"e1\",\"kind\":\"non_null\",\"target\":\"orders\",\"column\":\"ID\","
                            + "\"thresholdPct\":5}");
            assertEquals(422, bad.statusCode(), bad.body());
            assertTrue(bad.body().contains("thresholdPct"), bad.body());
            assertEquals(0, list(c.port).size(), "a refused expectation must not be written");

            // UPDATE is the same gate, not just CREATE
            assertEquals(200, send(c.port, "POST", "/expectations", STUDIO_SAVE_SHAPE).statusCode());
            HttpResponse<String> badUpdate = send(c.port, "PUT", "/expectations/e_ok",
                    "{\"name\":\"e_ok\",\"kind\":\"non_null\",\"target\":\"orders\",\"column\":\"ID\","
                            + "\"thresholdPct\":5}");
            assertEquals(422, badUpdate.statusCode(), badUpdate.body());
            assertTrue(badUpdate.body().contains("thresholdPct"), badUpdate.body());
        }
    }

    /** The real Studio save shape, the author's {@code x-} escape hatch, and a GET→PUT round trip. */
    @Test
    void theRealSaveShapesStillWrite(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            assertEquals(200, send(c.port, "POST", "/expectations", STUDIO_SAVE_SHAPE).statusCode());

            assertEquals(200, send(c.port, "POST", "/expectations",
                    "{\"name\":\"e_x\",\"kind\":\"non_null\",\"target\":\"orders\",\"column\":\"ID\","
                            + "\"x-owner-team\":\"risk\"}").statusCode());

            // ⚠ a GET→PUT round trip echoes the route's own bookkeeping back at it — it must be accepted,
            // or the census would reject essentially every real edit made from a fetched expectation.
            JsonNode fetched = list(c.port).get(0);
            assertTrue(fetched.has("lastResult") && fetched.has("createdAt") && fetched.has("updatedAt"),
                    "the persisted envelope is what a round trip replays: " + fetched);
            assertEquals(200, send(c.port, "PUT", "/expectations/" + fetched.get("name").asText(),
                    fetched.toString()).statusCode());
        }
    }

    /**
     * {@code when} is spec-UNDECLARED but {@code Expectation.fromMap}-read ⇒ must pass — and this body
     * also carries NO {@code column}, which {@code ConfigSpecs.expectation()} declares REQUIRED. Both
     * halves are deliberate: the census refuses unknown NAMES only, it does not run the spec's
     * required-field or enum rules (the spec's {@code kind} enum does not list {@code condition} either).
     */
    @Test
    void theConditionKindsUndeclaredWhenTreeIsAcceptedAndRequiredFieldRulesAreNotRun(@TempDir Path dir)
            throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            HttpResponse<String> ok = send(c.port, "POST", "/expectations",
                    "{\"name\":\"e_cond\",\"kind\":\"condition\",\"target\":\"orders\","
                            + "\"when\":{\"field\":\"ID\",\"op\":\"is_null\"}}");
            assertEquals(200, ok.statusCode(), ok.body());
            assertTrue(V1Body.of(ok.body()).has("when"), ok.body());
        }
    }

    /** The raw-body back door persisted dead keys and skipped {@code Expectation} entirely — now gated. */
    @Test
    void theComponentsBackDoorIsCensusedToo(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            HttpResponse<String> bad = send(c.port, "POST", "/components/expectation",
                    "{\"id\":\"e3\",\"kind\":\"non_null\",\"target\":\"orders\",\"column\":\"ID\","
                            + "\"thresholdPct\":5}");
            assertEquals(422, bad.statusCode(), bad.body());
            assertTrue(bad.body().contains("thresholdPct"), bad.body());
            assertEquals(404, send(c.port, "GET", "/components/expectation/e3", null).statusCode(),
                    "a refused expectation must not be written");

            assertEquals(200, send(c.port, "POST", "/components/expectation",
                    "{\"id\":\"e4\",\"kind\":\"non_null\",\"target\":\"orders\",\"column\":\"ID\"}")
                    .statusCode());
        }
    }

    /**
     * The count runs on a plain Statement, so a failure DuckDB finds while binding it arrives behind the
     * driver's pending-query preamble; the author must read the real error first
     * ({@code DUCKDB-PREAMBLE-OTHER-422S-1}).
     */
    @Test
    void anEvaluationFailureIsReportedWithoutTheDriverPreamble(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            assertEquals(200, send(c.port, "POST", "/expectations",
                    "{\"name\":\"e_gone\",\"kind\":\"non_null\",\"target\":\"no_such_store\",\"column\":\"ID\"}")
                    .statusCode());
            HttpResponse<String> r = send(c.port, "POST", "/expectations/e_gone/evaluate", null);
            assertEquals(422, r.statusCode(), r.body());
            String message = V1Body.of(r.body()).get("error").get("message").asText();
            assertFalse(message.contains("pending query result"), "the driver preamble leaked: " + message);
            assertTrue(message.startsWith("expectation evaluation failed: IO Error: No files found"), message);
        }
    }

    private JsonNode list(int port) throws Exception {
        return V1Body.of(send(port, "GET", "/expectations", null).body());
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
