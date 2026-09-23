package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LA-08 {@code POST /inv/projection/multi} over a real ControlApi (plan gate G-R3): three Datasets join into
 * one graph with correct {@code __provenance_dataset} tags and zero orphaned edges, the shared filter/limit
 * gates, and the fail-closed access rule — a subject who cannot view ONE Dataset gets nothing from any.
 * Test-scope split package com.gamma.control, like {@link ControlApiInvProjectionTest}, for {@code new ControlApi}
 * and the {@link Authenticators#forTest} seam.
 */
class MultiProjectionContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** alice may view everything; bob is not on the {@code wires_ds} share list. */
    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return Optional.empty();
        String id = auth.substring(7);
        return Set.of("alice", "bob").contains(id) ? Optional.of(new Subject(id, Set.of())) : Optional.empty();
    };

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
    }

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path configDir, Path writeRoot, boolean authenticated) throws Exception {
        Authenticators.forTest(authenticated ? FAKE : null);
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private void dataset(Ctx c, String id, String sql, Map<String, Object> extra) throws Exception {
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition(id + "_view", "flow-x", List.of(), sql,
                "2026-09-23T00:00:00Z"));
        Map<String, Object> content = new java.util.LinkedHashMap<>(extra);
        content.put("view", id + "_view");
        new ComponentStore(c.root.resolve("registry")).write("dataset", id, content);
    }

    /** people (nodes, INTEGER ids), calls (edges), wires (edges, owner alice, shared with nobody else). */
    private void seed(Ctx c) throws Exception {
        dataset(c, "people_ds", "SELECT * FROM (VALUES (1,'Ann','GB'),(2,'Ben','FR'),(3,'Cat','DE'),(NULL,'ghost','XX'))"
                + " AS t(pid,name,country)", Map.of());
        dataset(c, "calls_ds", "SELECT * FROM (VALUES ('1','2',5),('1','2',7),('2','3',1)) AS t(caller,callee,secs)", Map.of());
        dataset(c, "wires_ds", "SELECT * FROM (VALUES ('3','1',50000),('1','3',20)) AS t(payer,payee,amount)",
                Map.of("owner", "alice", "shares", List.of()));
    }

    private static final String THREE = """
            {"nodes":[{"dataset":"people_ds","idColumn":"pid","labelColumn":"name","category":"PERSON","attributes":["country"]}],
             "edges":[{"dataset":"calls_ds","sourceColumn":"caller","targetColumn":"callee","type":"CALL"},
                      {"dataset":"wires_ds","sourceColumn":"payer","targetColumn":"payee","type":"WIRE","attributes":["amount"]}]}""";

    private HttpResponse<String> multi(int port, String body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/inv/projection/multi"))
                .method("POST", BodyPublishers.ofString(body));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    @Test
    void threeDatasetsJoinWithProvenanceAndNoOrphanedEdges(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, false)) {
            seed(c);
            HttpResponse<String> r = multi(c.port, THREE, null);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode d = json(r.body());

            JsonNode nodes = d.get("nodes");
            assertEquals(3, nodes.size(), "NULL id excluded: " + nodes);
            Set<String> ids = new HashSet<>();
            for (JsonNode n : nodes) {
                ids.add(n.get("id").asText());
                assertEquals("people_ds", n.get("__provenance_dataset").asText());
                assertEquals("PERSON", n.get("category").asText());
            }
            assertEquals("Ann", nodes.get(0).get("label").asText());
            assertEquals("GB", nodes.get(0).at("/attrs/country").asText());

            JsonNode edges = d.get("edges");
            assertEquals(4, edges.size(), edges.toString());
            int calls = 0, wires = 0;
            for (JsonNode e : edges) {
                assertTrue(ids.contains(e.get("source").asText()) && ids.contains(e.get("target").asText()),
                        "INTEGER node ids meet VARCHAR edge endpoints — no orphan: " + e);
                String prov = e.get("__provenance_dataset").asText();
                if (prov.equals("calls_ds")) { calls++; assertEquals("CALL", e.get("kind").asText()); }
                else { wires++; assertEquals("wires_ds", prov); assertEquals("WIRE", e.get("kind").asText());
                       assertTrue(e.at("/attrs/amount").isTextual()); }
            }
            assertEquals(2, calls, "1→2 folds to one edge of count 2, plus 2→3");
            assertEquals(2, wires);
            assertEquals(2, edges.get(0).get("count").asInt(), "calls fold like /inv/projection");
            assertEquals(3, d.get("mappings").size());
            assertFalse(d.get("truncated").asBoolean());
        }
    }

    @Test
    void filterAppliesToEveryEdgeMappingAndIsFieldValidatedPerDataset(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, false)) {
            seed(c);
            // amount exists only on wires_ds → a top-level filter naming it is refused for calls_ds.
            String top = THREE.replace("\"edges\":[", "\"filter\":{\"kind\":\"group\",\"op\":\"AND\",\"items\":[{\"kind\":\"condition\",\"field\":\"amount\",\"operator\":\">\",\"value\":\"10000\"}]},\"edges\":[");
            HttpResponse<String> bad = multi(c.port, top, null);
            assertEquals(422, bad.statusCode(), bad.body());
            assertTrue(bad.body().contains("amount") && bad.body().contains("calls_ds"), bad.body());

            // the same condition on the wires mapping alone narrows only it
            String perEdge = THREE.replace("\"type\":\"WIRE\",", "\"type\":\"WIRE\",\"filter\":{\"kind\":\"group\",\"op\":\"AND\",\"items\":[{\"kind\":\"condition\",\"field\":\"amount\",\"operator\":\">\",\"value\":\"10000\"}]},");
            HttpResponse<String> ok = multi(c.port, perEdge, null);
            assertEquals(200, ok.statusCode(), ok.body());
            JsonNode edges = json(ok.body()).get("edges");
            assertEquals(3, edges.size(), "2 call edges + the one wire over 10000: " + edges);
        }
    }

    @Test
    void limitIsPerMappingAndTruncationIsReported(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, false)) {
            seed(c);
            HttpResponse<String> r = multi(c.port, THREE.replaceFirst("\\{", "{\"limit\":1,"), null);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode d = json(r.body());
            assertEquals(1, d.get("nodes").size());
            assertEquals(2, d.get("edges").size(), "one per edge mapping");
            assertTrue(d.get("truncated").asBoolean());
            for (JsonNode m : d.get("mappings")) assertTrue(m.get("truncated").asBoolean(), m.toString());
        }
    }

    @Test
    void failClosedGates(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, false)) {
            seed(c);
            assertEquals(422, multi(c.port, "{}", null).statusCode(), "no mappings");
            assertEquals(404, multi(c.port, THREE.replace("wires_ds", "nope_ds"), null).statusCode(), "unknown dataset");
            assertEquals(422, multi(c.port, THREE.replace("\"payee\"", "\"payee; DROP\""), null).statusCode(), "unsafe identifier");
            assertEquals(422, multi(c.port, "{\"edges\":[\"calls_ds\"]}", null).statusCode(), "non-object mapping");
            assertEquals(422, multi(c.port, THREE.replace("\"callee\"", "\"no_such_col\""), null).statusCode(), "unknown column");
        }
    }

    /** 🔴 The access rule: one unviewable Dataset refuses the WHOLE call — no partial union. */
    @Test
    void aSubjectWhoCannotViewOneDatasetGetsNothing(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, true)) {
            seed(c);
            HttpResponse<String> bob = multi(c.port, THREE, "bob");
            assertEquals(404, bob.statusCode(), bob.body());
            assertTrue(bob.body().contains("wires_ds"), "the same answer as absence: " + bob.body());
            assertFalse(bob.body().contains("Ann"), "no rows from the datasets bob CAN view: " + bob.body());

            // without the unviewable mapping bob is served — the refusal is about wires_ds, not bob
            String noWires = THREE.replaceAll(",\\s*\\{\"dataset\":\"wires_ds\"[^}]*\\]\\}", "");
            assertEquals(200, multi(c.port, noWires, "bob").statusCode(), noWires);

            HttpResponse<String> alice = multi(c.port, THREE, "alice");
            assertEquals(200, alice.statusCode(), alice.body());
            assertEquals(4, json(alice.body()).get("edges").size());

            // the single-dataset route answers the same way (the gate is shared)
            HttpResponse<String> single = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + c.port + "/api/v1/inv/projection"))
                    .header("Authorization", "Bearer bob")
                    .method("POST", BodyPublishers.ofString(
                            "{\"dataset\":\"wires_ds\",\"sourceCol\":\"payer\",\"targetCol\":\"payee\"}")).build(),
                    BodyHandlers.ofString());
            assertEquals(404, single.statusCode(), single.body());
        }
    }

    private static JsonNode json(String raw) throws Exception {
        JsonNode n = JSON.readTree(raw);
        return n.has("data") ? n.get("data") : n;
    }
}
