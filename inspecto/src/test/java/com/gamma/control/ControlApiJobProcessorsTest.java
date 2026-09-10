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
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /jobs/processors} over real HTTP ({@code PROCESSOR-CATALOG-ROUTE-1}, consignment-chain §14.2):
 * the {@link com.gamma.consignment.ConsignmentProcessor} ids a {@code consignment.process} Job may select,
 * so the post-sync chain editor can offer them instead of leaving the author to type an id from memory.
 *
 * <p><b>A read route, so there is no write-root gate, no payload to validate and no path to jail</b> — the
 * gate surface the {@code endpoint} skill enumerates is empty here on purpose. What is left to prove is that
 * the route is reachable at all (it shares its first segment with the {@code /jobs/&#123;name&#125;} regex),
 * that it serves a genuinely classpath-deployed provider, and that it is bounded.
 *
 * <p>⚠ The catalog's per-branch behaviour — a shadowed duplicate id, an unloadable provider, truncation — is
 * proven in {@code ProcessorCatalogTest} in {@code inspecto-engine}, which can vary the classpath through a
 * loader it builds. It cannot be done through HTTP: the handler runs on the {@code HttpServer}'s threads, so
 * a context classloader set by the test is never the one {@code ServiceLoader} consults.
 */
class ControlApiJobProcessorsTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        svc.jobServiceOrCreate();
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    /**
     * The happy path. ⚠ A 2xx {@code /api/v1} body is the envelope, so the catalog lives under {@code data}
     * — {@link V1Body#of} peels it, as every other route's test does.
     */
    @Test
    void servesTheClasspathDeployedProcessorByItsSelectableId(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode data = V1Body.of(get(c.port, "/jobs/processors").body());

            JsonNode row = byId(data.get("processors"), CatalogProbeProcessor.ID);
            assertEquals(CatalogProbeProcessor.class.getName(), row.get("className").asText(),
                    "the providing class travels with the id, so an operator can tell two jars apart");
            assertFalse(row.get("shadowed").asBoolean(), "nothing else claims this id");
            assertEquals(data.get("processors").size(), data.get("total").asInt(),
                    "nothing was left out, so total matches what was served");
            assertFalse(data.get("truncated").asBoolean());
            assertEquals(0, data.get("unusable").asInt(), "no provider on this classpath fails to load");
        }
    }

    /**
     * The bound and the honesty of it: a diagnostic read must declare a cap and report the TRUE total when
     * the cap bites. Both keys are always present so a client never has to distinguish "not truncated" from
     * "the field is missing".
     */
    @Test
    void theCatalogIsBoundedAndSaysSoInEveryResponse(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode data = V1Body.of(get(c.port, "/jobs/processors").body());

            assertTrue(data.has("truncated"), "always present, never inferred from a missing key");
            assertTrue(data.has("total"));
            assertTrue(data.get("processors").size() <= data.get("total").asInt(),
                    "the served list can never claim more than the true total");
        }
    }

    /** Every served id is one a chain could actually name — the catalog is a vocabulary, not an inventory. */
    @Test
    void everyServedIdIsOneAnAuthoredChainCouldName(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode data = V1Body.of(get(c.port, "/jobs/processors").body());

            for (JsonNode row : data.get("processors")) {
                String id = row.get("id").asText();
                assertFalse(id.isBlank(), "a blank id would render as an empty choice");
                assertFalse(id.contains(","), "a comma cannot survive the chain parameter's comma split");
            }
        }
    }

    @Test
    void theFixedSubPathWinsOverTheJobNameRoute(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // /jobs/{name} is a single-segment regex too, so registration order is load-bearing: a 404
            // "no job 'processors'" here would mean the catalog got shadowed by the job-by-name route.
            assertEquals(200, get(c.port, "/jobs/processors").statusCode());
        }
    }

    private static JsonNode byId(JsonNode processors, String id) {
        return StreamSupport.stream(processors.spliterator(), false)
                .filter(n -> id.equals(n.get("id").asText())).findFirst()
                .orElseThrow(() -> new AssertionError("no '" + id + "' in the catalog: " + processors));
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).GET().build(),
                BodyHandlers.ofString());
    }
}
