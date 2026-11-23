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

    @BeforeEach
    void setUp() { Authenticators.forTest(FAKE); }

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
        ConsignmentOutputStores.use(null);
        System.clearProperty("consignment.steps");
    }

    private record Ctx(CollectorService svc, ControlApi api, DbConsignmentOutputStore store) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
            store.close();
        }
    }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Files.writeString(toon, Files.readString(toon).replace("active: true", "active: false") + STEPS);
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
            String pipeline = row.get("pipeline").asText();
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
