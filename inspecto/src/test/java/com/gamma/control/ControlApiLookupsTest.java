package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.pipeline.exec.StepReceipt;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LOOKUP-1 — {@code GET /lookups} over real HTTP: every keyed {@code transform.join} of the loaded Pipelines with its
 * last run's counts from the Step Receipts; a plain join is not listed; a dry run's receipt is not the last run; an
 * open read; receipts switched off answer {@code known.lastRun=false} with no {@code lastRun}.
 */
class ControlApiLookupsTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private static final Authenticator FAKE = ex -> "Bearer plain".equals(ex.getRequestHeaders().getFirst("Authorization"))
            ? Optional.of(new Subject("nobody", Set.of())) : Optional.empty();

    private static final String STEPS = """
            output_store: enriched_calls
            steps[2]:
              - join:
                  reference: reference/dial_digit
                  key: dial_digit_key
                  match: longest_prefix
                  on[2]: SERVED_MSISDN, OTHER_MSISDN
              - join:
                  reference: reference/region_dim
                  on: id
            """;

    private static final String PRIOR_ROOTS = System.getProperty("assist.safety.roots");

    @BeforeEach
    void setUp() { Authenticators.forTest(FAKE); }

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
        ConsignmentOutputStores.use(null);
        System.clearProperty("consignment.steps");
        // surefire sets the roots for every test: restore them, never clear
        if (PRIOR_ROOTS != null) System.setProperty("assist.safety.roots", PRIOR_ROOTS);
        else System.clearProperty("assist.safety.roots");
        LookupRoutes.clearWarm();
    }

    private record Ctx(CollectorService svc, ControlApi api, DbConsignmentOutputStore store) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
            store.close();
        }
    }

    private Ctx open(Path dir) throws Exception { return open(dir, STEPS); }

    private Ctx open(Path dir, String steps) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Files.writeString(toon, Files.readString(toon).replace("active: true", "active: false") + steps);
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:");
        ConsignmentOutputStores.use(store);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, store);
    }

    private JsonNode lookups(Ctx c) throws Exception {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + c.api.port() + "/api/v1/lookups"))
                .header("Authorization", "Bearer plain").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    private static StepReceipt receipt(String pipeline, String consignment, int seq, long hit, boolean simulated) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("lookup.SERVED_MSISDN.hit", hit);
        counts.put("lookup.SERVED_MSISDN.miss", 2L);
        counts.put("reference.rows", 40L);
        return new StepReceipt(StepReceipt.VERSION, consignment, pipeline, StepReceipt.LANE_JOB, "run-" + seq, seq,
                "join", "transform.join", StepReceipt.Outcome.OK, 10,
                List.of(new StepReceipt.Outlet("data", 10)), null, null,
                new StepReceipt.Diagnostics(null, null, counts), "2026-11-23T10:15:0" + seq + ".000Z", 3, simulated, false);
    }

    @Test
    void aKeyedJoinIsListedWithItsLastRunsCountsAndAPlainJoinIsNot(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode body = lookups(c);
            assertEquals(1, body.get("total").asInt(), body.toString());
            JsonNode row = body.get("lookups").get(0);
            // Receipts carry the name the job runner gives the Stage-2 chain it lifts, never the row's own name.
            com.gamma.etl.PipelineConfig cfg = c.svc.loadedPipelines().get(0);
            String pipeline = com.gamma.pipeline.PipelineLift.stageTwo(cfg).name();
            assertEquals(cfg.identity().pipelineName(), row.get("pipeline").asText());
            assertNotEquals(row.get("pipeline").asText(), pipeline, "the test must not reuse the listed name");
            assertEquals("join", row.get("step").asText(), body.toString());
            assertEquals("reference/dial_digit", row.get("reference").asText());
            assertEquals("longest_prefix", row.get("match").asText());
            assertEquals("[\"SERVED_MSISDN\",\"OTHER_MSISDN\"]", row.get("lookedUpBy").toString());
            assertTrue(row.get("lastRun").isNull(), "no run yet: " + body);
            assertTrue(body.get("known").get("lastRun").asBoolean());

            c.store.recordSteps(List.of(receipt(pipeline, "c-old", 1, 5, false)));
            c.store.recordSteps(List.of(receipt(pipeline, "c-new", 2, 7, false)));
            c.store.recordSteps(List.of(receipt(pipeline, "c-dry", 3, 99, true)));   // a dry run is not the last run

            JsonNode last = lookups(c).get("lookups").get(0).get("lastRun");
            assertEquals("c-new", last.get("consignmentId").asText(), last.toString());
            assertEquals("OK", last.get("outcome").asText());
            assertEquals(10, last.get("rowsIn").asLong());
            assertEquals(7, last.get("counts").get("lookup.SERVED_MSISDN.hit").asLong());
            assertEquals(40, last.get("counts").get("reference.rows").asLong());

            // the last run's unmatched-key sample: fingerprint, length, shape and count, never the value
            assertEquals(0, lookups(c).get("lookups").get(0).get("unmatched").size());
            c.store.recordLookupMisses(pipeline, "join", "c-new", List.of(
                    new com.gamma.pipeline.exec.LookupMissSample.Entry("SERVED_MSISDN", "fp:0123456789abcdef", 12,
                            "4479••••••••", 5)));
            JsonNode miss = lookups(c).get("lookups").get(0).get("unmatched").get(0);
            assertEquals("SERVED_MSISDN", miss.get("mapping").asText());
            assertEquals("fp:0123456789abcdef", miss.get("fingerprint").asText());
            assertEquals(12, miss.get("length").asInt());
            assertEquals("4479••••••••", miss.get("shape").asText());
            assertEquals(5, miss.get("n").asLong());
            // a sample stored by an OLDER run is not the last run's: a newer run that failed stored nothing
            c.store.recordLookupMisses(pipeline, "join", "c-old", List.of(
                    new com.gamma.pipeline.exec.LookupMissSample.Entry("SERVED_MSISDN", "fp:fedcba9876543210", 3,
                            "9••", 1)));
            assertEquals(0, lookups(c).get("lookups").get(0).get("unmatched").size(),
                    "the last run is c-new; c-old's sample is not shown beside it");
            c.store.recordLookupMisses(pipeline, "join", "c-new", List.of());   // a run where every value matched
            assertEquals(0, lookups(c).get("lookups").get(0).get("unmatched").size(), "a clean run clears the sample");
        }
    }

    private HttpResponse<String> test(Ctx c, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.api.port() + "/api/v1/lookups/test"))
                .header("Authorization", "Bearer plain").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** LOOKUP-1 P3 — test-a-key: the point engine's answer for one value, the index kept warm and rebuilt when its Reference changes. */
    @Test
    void aValueIsTestedThroughThePointEngineAndRefreshSeesANewReference(@TempDir Path dir) throws Exception {
        System.setProperty("assist.safety.roots", dir.toString());
        Path ref = dir.resolve("refdata").resolve("dial.csv");
        Files.createDirectories(ref.getParent());
        Files.writeString(ref, "dial_digit_key,provider_desc\n249,Sudan\n24991,Operator A\n");
        String steps = """
                output_store: enriched_calls
                steps[1]:
                  - join:
                      reference: %s
                      key: dial_digit_key
                      match: longest_prefix
                      on: SERVED_MSISDN
                      columns: provider_desc
                      default[1]: provider_desc=UNKNOWN
                      details: true
                """.formatted(ref.toString().replace('\\', '/'));
        try (Ctx c = open(dir, steps)) {
            String name = c.svc.loadedPipelines().get(0).identity().pipelineName();
            HttpResponse<String> r = test(c, "{\"pipeline\":\"" + name + "\",\"step\":\"join\",\"value\":\"249915555\"}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode a = V1Body.of(r.body());
            assertTrue(a.get("hit").asBoolean(), a.toString());
            assertEquals("24991", a.get("matchedKey").asText());
            assertEquals("prefix", a.get("matchKind").asText());
            JsonNode cols = a.get("mappings").get(0).get("columns");
            assertEquals("Operator A", cols.get("SERVED_MSISDN_provider_desc").asText(), a.toString());
            assertEquals(2, a.get("index").get("rows").asLong());

            JsonNode miss = V1Body.of(test(c, "{\"pipeline\":\"" + name + "\",\"step\":\"join\",\"value\":\"999\"}").body());
            assertFalse(miss.get("hit").asBoolean());
            assertEquals("none", miss.get("matchKind").asText());
            assertEquals("UNKNOWN", miss.get("mappings").get(0).get("columns").get("SERVED_MSISDN_provider_desc").asText());

            assertEquals("first use", a.get("index").get("reloaded").asText());
            assertTrue(a.get("index").get("watchesReference").asBoolean());
            assertTrue(miss.get("index").get("reloaded").isNull(), "kept: " + miss);

            // the Reference changes: the next answer sees it, with no reload asked
            Files.writeString(ref, "dial_digit_key,provider_desc\n249,Sudan\n24991,Operator A\n999,Nine\n");
            JsonNode fresh = V1Body.of(test(c, "{\"pipeline\":\"" + name + "\",\"step\":\"join\",\"value\":\"999\"}").body());
            assertTrue(fresh.get("hit").asBoolean(), fresh.toString());
            assertEquals(3, fresh.get("index").get("rows").asLong());
            assertEquals("reference changed", fresh.get("index").get("reloaded").asText());
            JsonNode asked = V1Body.of(test(c, "{\"pipeline\":\"" + name + "\",\"step\":\"join\",\"value\":\"999\","
                    + "\"refresh\":true}").body());
            assertEquals("reload asked", asked.get("index").get("reloaded").asText());

            assertEquals(404, test(c, "{\"pipeline\":\"" + name + "\",\"step\":\"nope\",\"value\":\"1\"}").statusCode());
            assertEquals(400, test(c, "{\"value\":\"1\"}").statusCode());
        }
    }

    @Test
    void aRangeAndAnAsofLookupAnswerAndOverlappingRangesAreRefused(@TempDir Path dir) throws Exception {
        System.setProperty("assist.safety.roots", dir.toString());
        Path ref = dir.resolve("refdata").resolve("band.csv");
        Files.createDirectories(ref.getParent());
        // a CSV path reference is read as text, so bounds compare as text (the batch join too): pad them
        Files.writeString(ref, "lo,hi,band\n01,09,small\n10,99,mid\n");
        String steps = """
                output_store: enriched_calls
                steps[1]:
                  - join:
                      reference: %s
                      from: lo
                      to: hi
                      match: range
                      on: id
                """.formatted(ref.toString().replace('\\', '/'));
        try (Ctx c = open(dir, steps)) {
            String name = c.svc.loadedPipelines().get(0).identity().pipelineName();
            HttpResponse<String> r = test(c, "{\"pipeline\":\"" + name + "\",\"step\":\"join\",\"value\":\"12\"}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode a = V1Body.of(r.body());
            assertEquals("range", a.get("matchKind").asText(), a.toString());
            assertEquals("10..99", a.get("matchedKey").asText());
            assertEquals("mid", a.get("mappings").get(0).get("columns").get("id_band").asText(), a.toString());

            // a Reference whose ranges overlap is refused, as the batch join refuses it
            Files.writeString(ref, "lo,hi,band\n01,09,small\n05,99,mid\n");
            HttpResponse<String> overlap = test(c, "{\"pipeline\":\"" + name + "\",\"step\":\"join\",\"value\":\"7\","
                    + "\"refresh\":true}");
            assertEquals(422, overlap.statusCode(), overlap.body());
            assertTrue(overlap.body().contains("overlap"), overlap.body());
        }
    }

    @Test
    void anAsofLookupAnswersForTheTimeGiven(@TempDir Path dir) throws Exception {
        System.setProperty("assist.safety.roots", dir.toString());
        Path ref = dir.resolve("refdata").resolve("rates.csv");
        Files.createDirectories(ref.getParent());
        Files.writeString(ref, "code,eff,rate\nA,2026-01-01,1\nA,2026-03-01,2\n");
        String steps = """
                output_store: enriched_calls
                steps[1]:
                  - join:
                      reference: %s
                      key: code
                      from: eff
                      at: CALL_TIME
                      match: asof
                      on: SERVED_MSISDN
                      columns: rate
                """.formatted(ref.toString().replace('\\', '/'));
        try (Ctx c = open(dir, steps)) {
            String name = c.svc.loadedPipelines().get(0).identity().pipelineName();
            String body = "{\"pipeline\":\"" + name + "\",\"step\":\"join\",\"value\":\"A\",\"at\":\"%s\"}";
            JsonNode feb = V1Body.of(test(c, body.formatted("2026-02-15")).body());
            assertEquals("1", feb.get("mappings").get(0).get("columns").get("SERVED_MSISDN_rate").asText(), feb.toString());
            assertEquals("A@2026-01-01", feb.get("matchedKey").asText());
            JsonNode mar = V1Body.of(test(c, body.formatted("2026-03-01")).body());
            assertEquals("2", mar.get("mappings").get(0).get("columns").get("SERVED_MSISDN_rate").asText(), mar.toString());
            JsonNode before = V1Body.of(test(c, body.formatted("2025-12-31")).body());
            assertFalse(before.get("hit").asBoolean(), before.toString());
        }
    }

    @Test
    void withReceiptsOffTheLastRunIsUnknown(@TempDir Path dir) throws Exception {
        System.setProperty("consignment.steps", "false");
        try (Ctx c = open(dir)) {
            JsonNode body = lookups(c);
            assertFalse(body.get("known").get("lastRun").asBoolean(), body.toString());
            assertFalse(body.get("lookups").get(0).has("lastRun"), body.toString());
        }
    }
}
