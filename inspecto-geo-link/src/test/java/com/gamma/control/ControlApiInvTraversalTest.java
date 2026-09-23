package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LA-11 — server-side multi-hop traversal ({@code POST /inv/traversal/recursive-paths}) over real HTTP: the
 * happy path, every gate, and every fence (depth, cycles, edge yield, path limit). Each negative case carries
 * a positive twin in the same test — a probe that succeeds without the fence — so a fence that silently
 * stopped applying cannot pass.
 */
/* Test-scope split package com.gamma.control, like ControlApiInvProjectionTest: it drives the real dispatcher. */
class ControlApiInvTraversalTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    /**
     * Wires with a cycle A→B→C→A, two routes A…E (A-B-D-E, A-B-C-D-E), a tail E→F and a NULL endpoint.
     * Timestamps make A-B-C-D-E non-monotonic (C→D happens before B→C).
     */
    private void seedWires(Ctx c) throws Exception {
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition("wires_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES "
                        + "('A','B',10.0,TIMESTAMP '2026-01-01 01:00:00'),"
                        + "('B','C',20.0,TIMESTAMP '2026-01-01 05:00:00'),"
                        + "('C','A',30.0,TIMESTAMP '2026-01-01 06:00:00'),"
                        + "('C','D',40.0,TIMESTAMP '2026-01-01 03:00:00'),"
                        + "('B','D',50.0,TIMESTAMP '2026-01-01 02:00:00'),"
                        + "('D','E',60.0,TIMESTAMP '2026-01-01 07:00:00'),"
                        + "('E','F',70.0,TIMESTAMP '2026-01-09 07:00:00'),"
                        + "('Z',NULL,1.0,TIMESTAMP '2026-01-01 00:00:00')"
                        + ") AS t(sender,recipient,amount,executed_at)",
                "2026-09-23T00:00:00Z"));
        new ComponentStore(c.root.resolve("registry")).write("dataset", "wires_ds", Map.of("view", "wires_view"));
    }

    private HttpResponse<String> traverse(int port, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/inv/traversal/recursive-paths"))
                .method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private static String body(String extra) {
        return "{\"dataset\":\"wires_ds\",\"sourceCol\":\"sender\",\"targetCol\":\"recipient\",\"startNode\":\"A\""
                + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private JsonNode ok(Ctx c, String body) throws Exception {
        HttpResponse<String> r = traverse(c.port, body);
        assertEquals(200, r.statusCode(), r.body());
        JsonNode n = JSON.readTree(r.body());
        return n.get("data");
    }

    private static List<String> paths(JsonNode data) {
        List<String> out = new ArrayList<>();
        for (JsonNode p : data.get("paths")) {
            List<String> nodes = new ArrayList<>();
            p.get("nodes").forEach(n -> nodes.add(n.asText()));
            out.add(String.join("-", nodes));
        }
        return out;
    }

    @Test
    void findsEverySimplePathToTheTargetShortestFirst(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedWires(c);
            JsonNode data = ok(c, body("\"targetNode\":\"E\",\"weightCol\":\"amount\""));
            assertEquals(List.of("A-B-D-E", "A-B-C-D-E"), paths(data));
            assertEquals(3, data.get("paths").get(0).get("hops").asInt());
            assertEquals(120.0, data.get("paths").get(0).get("weight").asDouble(), 1e-9, "10+50+60");
            assertFalse(data.get("truncated").asBoolean());
            assertEquals(6, data.at("/fences/maxDepth").asInt(), "the plan's default depth");
            assertEquals(5000, data.at("/fences/timeoutMs").asInt());
        }
    }

    @Test
    void theDepthFenceStopsTheWalkAndItsTwinReachesFurther(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedWires(c);
            JsonNode fenced = ok(c, body("\"targetNode\":\"E\",\"maxDepth\":2"));
            assertEquals(List.of(), paths(fenced), "E is three hops away; a depth of 2 cannot reach it");
            JsonNode twin = ok(c, body("\"targetNode\":\"E\",\"maxDepth\":3"));
            assertEquals(List.of("A-B-D-E"), paths(twin), "the same probe one hop deeper succeeds");
            assertEquals(3, twin.at("/fences/maxDepth").asInt());

            JsonNode clamped = ok(c, body("\"maxDepth\":999"));
            assertEquals(10, clamped.at("/fences/maxDepth").asInt(), "the caller cannot lift the hard cap");
        }
    }

    @Test
    void aCycleIsNeverWalkedTwice(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedWires(c);
            // Without the cycle guard C→A would re-feed the frontier up to the depth cap: A-B-C-A-B-…
            JsonNode data = ok(c, body("\"maxDepth\":10"));
            List<String> all = paths(data);
            assertTrue(all.contains("A-B-C"), "the edge into the cycle is walked: " + all);
            for (String p : all) {
                String[] nodes = p.split("-");
                assertEquals(nodes.length, new HashSet<>(List.of(nodes)).size(), "a path revisits a node: " + p);
            }
            assertTrue(all.contains("A-B-C-D-E-F"), "the longest simple path is still found: " + all);
            assertEquals(8, all.size(), "every simple path from A, and nothing more: " + all);
        }
    }

    @Test
    void undirectedWalksEdgesBothWaysAndItsTwinDoesNot(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedWires(c);
            String probe = "\"startNode\":\"F\",\"targetNode\":\"E\"";
            JsonNode directed = ok(c, "{\"dataset\":\"wires_ds\",\"sourceCol\":\"sender\",\"targetCol\":\"recipient\"," + probe + "}");
            assertEquals(List.of(), paths(directed), "F has no outgoing wire");
            JsonNode undirected = ok(c, "{\"dataset\":\"wires_ds\",\"sourceCol\":\"sender\",\"targetCol\":\"recipient\","
                    + probe + ",\"direction\":\"UNDIRECTED\"}");
            assertEquals(List.of("F-E"), paths(undirected));
        }
    }

    @Test
    void theEdgeYieldFenceCapsEachLevelAndSaysSo(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedWires(c);
            JsonNode twin = ok(c, body(""));
            assertFalse(twin.get("edgeYieldCapped").asBoolean());
            assertEquals(8, twin.get("paths").size());

            JsonNode capped = ok(c, body("\"maxEdgeYield\":1"));
            assertTrue(capped.get("edgeYieldCapped").asBoolean(), capped.toString());
            assertTrue(capped.get("truncated").asBoolean(), "a capped walk is a partial answer");
            assertTrue(capped.get("paths").size() < 8, "the cap bounded the WORK, not just the result: " + capped);
            for (JsonNode p : capped.get("paths")) {
                assertTrue(p.get("hops").asInt() <= 5, "at most one path per level: " + capped);
            }
        }
    }

    @Test
    void thePathLimitTruncatesAndItsTwinDoesNot(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedWires(c);
            JsonNode limited = ok(c, body("\"limit\":2"));
            assertEquals(2, limited.get("paths").size());
            assertTrue(limited.get("truncated").asBoolean());
            assertFalse(limited.get("edgeYieldCapped").asBoolean(), "a result cut is not a work cut");
            JsonNode twin = ok(c, body("\"limit\":8"));
            assertFalse(twin.get("truncated").asBoolean());
        }
    }

    @Test
    void aMonotonicTemporalConstraintDropsOutOfOrderPaths(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedWires(c);
            JsonNode twin = ok(c, body("\"targetNode\":\"E\",\"temporalConstraint\":{\"timestampCol\":\"executed_at\"}"));
            assertEquals(List.of("A-B-D-E", "A-B-C-D-E"), paths(twin));
            JsonNode monotonic = ok(c, body("\"targetNode\":\"E\","
                    + "\"temporalConstraint\":{\"timestampCol\":\"executed_at\",\"monotonic\":true}"));
            assertEquals(List.of("A-B-D-E"), paths(monotonic), "C→D precedes B→C");

            JsonNode wide = ok(c, body("\"targetNode\":\"F\","
                    + "\"temporalConstraint\":{\"timestampCol\":\"executed_at\",\"maxTotalDurationHours\":1000}"));
            assertFalse(paths(wide).isEmpty());
            JsonNode narrow = ok(c, body("\"targetNode\":\"F\","
                    + "\"temporalConstraint\":{\"timestampCol\":\"executed_at\",\"maxTotalDurationHours\":72}"));
            assertEquals(List.of(), paths(narrow), "E→F lands eight days after A→B");
        }
    }

    @Test
    void aFilterPrunesEdgesBeforeTheWalk(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedWires(c);
            JsonNode data = ok(c, body("\"targetNode\":\"E\",\"filter\":"
                    + "{\"kind\":\"group\",\"op\":\"AND\",\"items\":[{\"kind\":\"condition\",\"field\":\"amount\",\"operator\":\"!=\",\"value\":50}]}"));
            // B→D (50) is filtered out, leaving only the route through C.
            assertEquals(List.of("A-B-C-D-E"), paths(data));
        }
    }

    /** A bare top-level condition used to render TRUE and prune nothing (B→D survived); now a 422. Twin above. */
    @Test
    void aBareConditionAtTheTopLevelIs422(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedWires(c);
            HttpResponse<String> bare = traverse(c.port, body("\"targetNode\":\"E\",\"filter\":"
                    + "{\"kind\":\"condition\",\"field\":\"amount\",\"operator\":\"!=\",\"value\":50}"));
            assertEquals(422, bare.statusCode(), bare.body());
            assertTrue(bare.body().contains("group"), bare.body());
            JsonNode twin = ok(c, body("\"targetNode\":\"E\",\"filter\":"
                    + "{\"kind\":\"group\",\"op\":\"AND\",\"items\":[{\"kind\":\"condition\",\"field\":\"amount\",\"operator\":\"!=\",\"value\":50}]}"));
            assertEquals(List.of("A-B-C-D-E"), paths(twin), "the grouped twin prunes B→D");
        }
    }

    @Test
    void unknownColumnsAndFilterFieldsAre422(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedWires(c);
            HttpResponse<String> col = traverse(c.port, body("\"weightCol\":\"nope\""));
            assertEquals(422, col.statusCode(), col.body());
            assertTrue(col.body().contains("nope"));
            HttpResponse<String> filter = traverse(c.port, body("\"filter\":"
                    + "{\"kind\":\"group\",\"op\":\"AND\",\"items\":[{\"kind\":\"condition\",\"field\":\"ghost\",\"operator\":\"=\",\"value\":1}]}"));
            assertEquals(422, filter.statusCode(), filter.body());
            HttpResponse<String> ts = traverse(c.port, body("\"temporalConstraint\":{\"timestampCol\":\"when\"}"));
            assertEquals(422, ts.statusCode(), ts.body());
            assertEquals(200, traverse(c.port, body("\"weightCol\":\"amount\"")).statusCode(), "twin: a real column");
        }
    }

    @Test
    void malformedBodiesAre422(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedWires(c);
            assertEquals(422, traverse(c.port, "{\"dataset\":\"wires_ds\",\"sourceCol\":\"sender\",\"targetCol\":\"recipient\"}").statusCode(),
                    "startNode is required");
            assertEquals(422, traverse(c.port, "{\"dataset\":\"wires_ds\",\"sourceCol\":\"sender; DROP\",\"targetCol\":\"recipient\",\"startNode\":\"A\"}").statusCode(),
                    "a non-identifier column is refused");
            assertEquals(422, traverse(c.port, body("\"direction\":\"SIDEWAYS\"")).statusCode());
            assertEquals(422, traverse(c.port, body("\"targetNode\":\"A\"")).statusCode());
            assertEquals(422, traverse(c.port, body("\"temporalConstraint\":{\"timestampCol\":\"executed_at\",\"maxTotalDurationHours\":0}")).statusCode());
        }
    }

    @Test
    void aStartNodeCarryingSqlIsJustAValue(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedWires(c);
            JsonNode data = ok(c, body("").replace("\"startNode\":\"A\"", "\"startNode\":\"A' OR 1=1 --\""));
            assertEquals(0, data.get("paths").size(), "the bound value matched no node");
        }
    }

    @Test
    void unknownDatasetIs404(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = traverse(c.port, body("").replace("wires_ds", "ghost_ds"));
            assertEquals(404, r.statusCode(), r.body());
        }
    }

    @Test
    void noWriteRootIs503(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            HttpResponse<String> r = traverse(c.port, body(""));
            assertEquals(503, r.statusCode(), r.body());
        }
    }

    /** LA-04 pattern: the traversal is audited as its own analytic act, with the partial-result flag. */
    @Test
    void aTraversalEmitsItsOwnAuditEvent(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedWires(c);
            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            EventLog.current().addSubscriber(sub);
            try {
                assertEquals(200, traverse(c.port, body("\"targetNode\":\"E\",\"limit\":1")).statusCode());
            } finally {
                EventLog.current().removeSubscriber(sub);
            }
            Event e = seen.stream().filter(x -> EventType.LINK_TRAVERSED.equals(x.type())).findFirst()
                    .orElseThrow(() -> new AssertionError("no LINK_TRAVERSED in " + seen));
            assertEquals("link.traversed", e.attributes().get("action"));
            assertEquals("wires_ds", e.attributes().get("dataset"));
            assertEquals("A", e.attributes().get("startNode"));
            assertEquals("E", e.attributes().get("targetNode"));
            assertEquals("1", e.attributes().get("paths"));
            assertEquals("true", e.attributes().get("truncated"));
        }
    }
}
