package com.gamma.control;

import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.la.core.LinkIds;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-4 step 6 (design §3.5) - the ROUTE-level parity test: the graph of {@code graph-algorithms-parity.fixture.json}, the file the
 * browser's and the engine's parity specs read, goes in as a REAL Dataset, becomes a REAL Investigation and Working Set, and
 * comes out of {@code POST /inv/graph/runs}; the answer must be the fixture's {@code expected} values. So the adapter, the wire
 * edge ids and the route are covered, not only the algorithms.
 *
 * <p>What is NOT asserted, and why: the fixture's PARALLEL edges ({@code e1} and {@code e4} are both a&gt;b) fold into one link
 * with count 2 in a Working Set - that is how the evaluator keys a link - so a figure that counts edges (the fixture's
 * {@code degree}) legitimately differs there. The simple-graph answers (components, k-core, triangles, shortest paths,
 * neighborhoods) do not, and are asserted. A fixture edge id {@code eN} is mapped to the wire id of its (source, target)
 * under the one kind the test gives every row.
 */
class ControlApiGraphRunParityTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path FIXTURE = Path.of("..", "..", "inspecto-ui", "projects", "link-analysis", "src", "graph", "graph-algorithms-parity.fixture.json");
    private static final String TOKEN = "Bearer analyst", KIND = "k";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", TOKEN);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode ok(Ctx c, String method, String path, String body) throws Exception {
        HttpResponse<String> r = send(c, method, path, body);
        assertEquals(200, r.statusCode(), method + " " + path + " -> " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private JsonNode runOf(Ctx c, String algorithm, String params) throws Exception {
        JsonNode d = ok(c, "POST", "/inv/graph/runs", "{\"investigationId\":\"fx\",\"algorithm\":\"" + algorithm + "\""
                + (params.isEmpty() ? "" : ",\"params\":" + params) + "}");
        assertEquals("COMPLETED", d.get("status").asText(), d.toString());
        return d.get("result");
    }

    private static List<String> texts(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(x -> out.add(x.asText()));
        return out;
    }

    @Test
    void theFixturesGraphThroughARealInvestigationGivesTheFixturesExpectedAnswers(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> "Bearer analyst".equals(ex.getRequestHeaders().getFirst("Authorization"))
                ? Optional.of(new Subject("analyst-1", Set.of("canManageIncidents", "canRunLinkGraphAnalysis"))) : Optional.empty());
        JsonNode fx = JSON.readTree(Files.readString(FIXTURE));
        JsonNode graph = fx.get("graph"), expected = fx.get("expected");

        StringBuilder values = new StringBuilder();
        Map<String, String> wire = new java.util.HashMap<>();                       // fixture edge id -> wire id
        for (JsonNode e : graph.get("edges")) {
            if (values.length() > 0) values.append(',');
            values.append("('").append(e.get(1).asText()).append("','").append(e.get(2).asText()).append("','").append(KIND).append("')");
            wire.put(e.get(0).asText(), LinkIds.encode(e.get(1).asText(), e.get(2).asText(), KIND));
        }
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            try (Ctx c = new Ctx(svc, api, api.port())) {
                Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: none\n");
                new ViewStore(root.resolve("views")).write(new ViewDefinition("fx_view", "flow-x", List.of(),
                        "SELECT caller, callee, channel FROM (VALUES " + values + ") AS t(caller,callee,channel)", "2026-09-30T00:00:00Z"));
                new ComponentStore(root.resolve("registry")).write("dataset", "fx_ds", Map.of("view", "fx_view"));
                ok(c, "POST", "/inv/investigations", "{\"id\":\"fx\",\"purpose\":\"parity\",\"dataset\":\"fx_ds\","
                        + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}");
                List<String> nodeIds = texts(graph.get("nodes"));
                ok(c, "POST", "/inv/investigations/fx/ops", "{\"op\":\"seed\",\"ids\":" + JSON.writeValueAsString(nodeIds) + "}");
                ok(c, "POST", "/inv/investigations/fx/ops", "{\"op\":\"expand\"}");
                assertEquals(nodeIds.size(), ok(c, "GET", "/inv/investigations/fx/working-set?of=entities", null).get("total").asInt());

                // components: ordered groups
                JsonNode comps = runOf(c, "connectedComponents", "").get("groups");
                assertEquals(expected.get("components").size(), comps.size());
                for (int i = 0; i < comps.size(); i++) assertEquals(texts(expected.get("components").get(i)), texts(comps.get(i)), "component " + i);

                // k-core and triangles: ranked [id, score] pairs
                for (String[] a : new String[][] {{"kCore", "kCore"}, {"triangleCount", "triangles"}}) {
                    JsonNode got = runOf(c, a[0], "").get("scores"), want = expected.get(a[1]);
                    assertEquals(want.size(), got.size(), a[0]);
                    for (int i = 0; i < want.size(); i++) {
                        assertEquals(want.get(i).get(0).asText(), got.get(i).get("id").asText(), a[0] + " id at rank " + i);
                        assertEquals(want.get(i).get(1).asDouble(), got.get(i).get("score").asDouble(), 0.0, a[0] + " score at rank " + i);
                    }
                }

                // shortest paths: node ids exact, edge ids = the wire ids of the fixture's edges
                assertTrue(expected.get("shortestPath").size() >= 5);
                for (JsonNode cs : expected.get("shortestPath")) {
                    JsonNode sel = runOf(c, "shortestPath", "{\"from\":\"" + cs.get("from").asText() + "\",\"to\":\"" + cs.get("to").asText()
                            + "\",\"direction\":\"" + cs.get("direction").asText() + "\"}").get("selection");
                    if (cs.get("nodeIds").isNull()) {
                        assertTrue(sel.isNull(), cs.toString());
                        continue;
                    }
                    assertEquals(texts(cs.get("nodeIds")), texts(sel.get("nodeIds")), cs.toString());
                    List<String> want = new ArrayList<>();
                    cs.get("edgeIds").forEach(e -> want.add(wire.get(e.asText())));
                    assertEquals(want, texts(sel.get("edgeIds")), cs.toString());
                }

                // neighborhoods: nodes exact; edges as a set (parallel fixture edges fold into one link here)
                for (JsonNode cs : expected.get("neighborhood")) {
                    JsonNode g = runOf(c, "neighborhood", "{\"node\":\"" + cs.get("node").asText() + "\",\"hops\":" + cs.get("hops").asInt()
                            + ",\"direction\":\"" + cs.get("direction").asText() + "\"}");
                    List<String> nodes = new ArrayList<>();
                    g.get("nodes").forEach(n -> nodes.add(n.get("id").asText()));
                    assertEquals(texts(cs.get("nodeIds")), nodes, cs.toString());
                    Set<String> wantEdges = new LinkedHashSet<>(), gotEdges = new LinkedHashSet<>();
                    cs.get("edgeIds").forEach(e -> wantEdges.add(wire.get(e.asText())));
                    g.get("edges").forEach(e -> gotEdges.add(e.get("id").asText()));
                    assertEquals(wantEdges, gotEdges, cs.toString());
                }
            }
        } finally {
            System.clearProperty("assist.write.root");
        }
    }
}
