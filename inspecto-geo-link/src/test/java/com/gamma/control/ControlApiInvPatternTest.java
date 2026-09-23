package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LA-14b server half — {@code POST /inv/pattern/branching} over real HTTP.
 *
 * <p>⛔ <b>Parity is the contract.</b> {@link #theServerFindsExactlyTheGoldenMatches} runs the golden fixture
 * {@code branching-parity.fixture.json} — the SAME file {@code branching-parity.spec.ts} feeds the browser matcher
 * — and asserts the same layers and the same legs. Both sides assert one {@code expected}, so they cannot drift
 * apart while both stay green.
 *
 * <p>{@link #theRingTheProjectionCutIsStillFound} is the reason the route exists: on a feed whose heavy links
 * fill the 2 000-row projection, {@code /inv/projection} drops every structuring leg (small, one-off — sorted
 * last), so the browser has nothing to find; the compiler runs over the whole Dataset and finds the ring.
 */
/* Test-scope split package com.gamma.control, like ControlApiInvTraversalTest: it drives the real dispatcher. */
class ControlApiInvPatternTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path FIXTURE = Path.of("..", "inspecto-ui", "src", "app", "modules", "admin", "studio",
            "link-analysis", "branching-parity.fixture.json");
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

    private static JsonNode fixture() throws Exception {
        return JSON.readTree(Files.readString(FIXTURE));
    }

    /** The fixture's rows as a VALUES relation {@code (src, dst, TS, AMOUNT)}, plus any extra SELECT unioned in. */
    private static String rowsSql(JsonNode fx, String extra) {
        StringBuilder sb = new StringBuilder("SELECT * FROM (VALUES ");
        boolean first = true;
        for (JsonNode r : fx.get("rows")) {
            if (!first) sb.append(',');
            first = false;
            sb.append("('").append(r.get(0).asText().replace("'", "''")).append("','")
              .append(r.get(1).asText().replace("'", "''")).append("','").append(r.get(2).asText()).append("',")
              .append(r.get(3).asDouble()).append(')');
        }
        sb.append(") AS t(src, dst, TS, AMOUNT)");
        return extra == null ? sb.toString() : sb + " UNION ALL " + extra;
    }

    private static void seed(Ctx c, String sql) throws Exception {
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition("tx_view", "flow-x", List.of(), sql,
                "2026-09-23T00:00:00Z"));
        new ComponentStore(c.root.resolve("registry")).write("dataset", "tx_ds", Map.of("view", "tx_view"));
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    /** The route body for the fixture's motif; {@code tweak} edits it before sending. */
    private static ObjectNode motif(JsonNode fx) {
        ObjectNode b = JSON.createObjectNode();
        b.put("dataset", "tx_ds").put("sourceCol", "src").put("targetCol", "dst").put("timeCol", fx.get("timeAttr").asText());
        b.set("stages", fx.get("stages").deepCopy());
        return b;
    }

    private JsonNode ok(Ctx c, String body) throws Exception {
        HttpResponse<String> r = post(c.port, "/inv/pattern/branching", body);
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    /** {@code normalizeEntityKey} — the browser's node key, so both sides are compared in one key space. */
    private static String key(String v) {
        return v.toLowerCase().replaceAll("\\s+", " ").replaceAll("[\\s.,;:]+$", "").trim();
    }

    /** A server match in the fixture's comparison form: sorted layers of keys, sorted {@code source>target@TS} legs. */
    private static Map<String, Object> canon(JsonNode match, JsonNode edges) {
        List<List<String>> layers = new ArrayList<>();
        for (JsonNode layer : match.get("layers")) {
            List<String> l = new ArrayList<>();
            layer.forEach(n -> l.add(key(n.asText())));
            l.sort(null);
            layers.add(l);
        }
        List<String> legs = new ArrayList<>();
        for (JsonNode id : match.get("edgeIds")) {
            JsonNode e = null;
            for (JsonNode x : edges) if (x.get("id").asText().equals(id.asText())) e = x;
            assertNotNull(e, "edge id " + id + " is not in the answer's edges");
            legs.add(key(e.get("source").asText()) + ">" + key(e.get("target").asText()) + "@" + e.at("/attrs/TS").asText());
        }
        legs.sort(null);
        return Map.of("layers", layers, "edges", legs);
    }

    private static List<Map<String, Object>> canonAll(JsonNode data) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode m : data.get("matches")) out.add(canon(m, data.get("edges")));
        return out;
    }

    private static List<Map<String, Object>> expected(JsonNode fx) {
        return JSON.convertValue(fx.get("expected"), JSON.getTypeFactory().constructCollectionType(List.class, Map.class));
    }

    @Test
    void theServerFindsExactlyTheGoldenMatches(@TempDir Path cfg, @TempDir Path root) throws Exception {
        JsonNode fx = fixture();
        try (Ctx c = open(cfg, root)) {
            seed(c, rowsSql(fx, null));
            JsonNode data = ok(c, motif(fx).toString());
            assertNull(data.get("refusal"), data.toString());
            assertEquals(expected(fx), canonAll(data), "the server must answer exactly what the browser golden says");
            assertFalse(data.get("truncated").asBoolean());
            assertEquals(5000, data.at("/fences/timeoutMs").asInt());
        }
    }

    @Test
    void theRingTheProjectionCutIsStillFound(@TempDir Path cfg, @TempDir Path root) throws Exception {
        JsonNode fx = fixture();
        // 2 100 heavy pairs, three rows each: every one outweighs a one-off deposit in `cnt DESC` order.
        String heavy = "SELECT 'HEAVY-' || i, 'PAYEE-' || i, '2026-04-01 10:00:00', 50000.0 FROM range(2100) r(i), range(3) k(j)";
        try (Ctx c = open(cfg, root)) {
            seed(c, rowsSql(fx, heavy));
            HttpResponse<String> proj = post(c.port, "/inv/projection",
                    "{\"dataset\":\"tx_ds\",\"sourceCol\":\"src\",\"targetCol\":\"dst\",\"attrCols\":[\"TS\",\"AMOUNT\"]}");
            assertEquals(200, proj.statusCode(), proj.body());
            JsonNode projected = JSON.readTree(proj.body()).get("data");
            assertTrue(projected.get("truncated").asBoolean(), "the projection is capped");
            for (JsonNode row : projected.get("rows"))
                assertFalse(row.get("target").asText().equals("MULE-HUB-01"),
                        "a structuring leg survived the cap, so this case proves nothing: " + row);

            JsonNode data = ok(c, motif(fx).toString());
            assertEquals(expected(fx), canonAll(data), "the whole-Dataset search still finds the ring");
            assertFalse(data.get("legCapped").asBoolean());
        }
    }

    @Test
    void theMatchLimitSetsTruncatedAndItsTwinDoesNot(@TempDir Path cfg, @TempDir Path root) throws Exception {
        JsonNode fx = fixture();
        // A second, renamed copy of ring A: two matches exist.
        String copy = rowsSql(fx, null).replace("SMURF-", "TWIN-SMURF-").replace("smurf-06.", "twin-smurf-06.")
                .replace("MULE-HUB-01", "TWIN-HUB").replace("RELAY-0", "TWIN-RELAY-").replace("OFFSHORE-77", "TWIN-EXIT");
        try (Ctx c = open(cfg, root)) {
            seed(c, rowsSql(fx, copy));
            JsonNode capped = ok(c, motif(fx).put("limit", 1).toString());
            assertEquals(1, capped.get("matches").size());
            assertTrue(capped.get("truncated").asBoolean(), "a limit that cut a match must say so");
            JsonNode twin = ok(c, motif(fx).toString());
            assertEquals(2, twin.get("matches").size());
            assertFalse(twin.get("truncated").asBoolean());
        }
    }

    @Test
    void refusalsUseTheBrowsersWordsAndNeverReadAsNone(@TempDir Path cfg, @TempDir Path root) throws Exception {
        JsonNode fx = fixture();
        try (Ctx c = open(cfg, root)) {
            seed(c, rowsSql(fx, null));
            ObjectNode noTime = motif(fx);
            noTime.remove("timeCol");
            assertTrue(ok(c, noTime.toString()).get("refusal").asText().startsWith("This pattern has a time window"));

            // §2.6: a view filtered to large amounts has removed every leg the band looks for.
            ObjectNode filtered = motif(fx);
            filtered.set("filter", JSON.readTree("{\"kind\":\"group\",\"op\":\"AND\",\"items\":[{\"kind\":\"condition\","
                    + "\"field\":\"AMOUNT\",\"operator\":\">=\",\"value\":\"5000\"}]}"));
            JsonNode refused = ok(c, filtered.toString());
            assertEquals("No link in this graph passes 900 ≤ AMOUNT < 1000. If the view filters AMOUNT (for example to ≥ 5 000), "
                    + "the legs this pattern looks for were removed before it ran — clear that filter rather than read this as \"none found\".",
                    refused.get("refusal").asText());
            assertEquals(0, refused.get("matches").size());

            ObjectNode notNumeric = motif(fx);
            ((ObjectNode) notNumeric.get("stages").get(0).get("threshold")).put("attr", "src");
            assertTrue(ok(c, notNumeric.toString()).get("refusal").asText().startsWith("No link carries a numeric src"));
        }
    }

    @Test
    void everyGateAnswersItsOwnStatus(@TempDir Path cfg, @TempDir Path root) throws Exception {
        JsonNode fx = fixture();
        try (Ctx c = open(cfg, root)) {
            seed(c, rowsSql(fx, null));
            assertEquals(404, post(c.port, "/inv/pattern/branching", motif(fx).put("dataset", "ghost_ds").toString()).statusCode());

            ObjectNode unknownAttr = motif(fx);
            ((ObjectNode) unknownAttr.get("stages").get(0).get("threshold")).put("attr", "NOPE");
            HttpResponse<String> r = post(c.port, "/inv/pattern/branching", unknownAttr.toString());
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("unknown column 'NOPE'"), r.body());

            ObjectNode injected = motif(fx).put("timeCol", "TS\"; DROP TABLE x; --");
            assertEquals(422, post(c.port, "/inv/pattern/branching", injected.toString()).statusCode());

            ObjectNode badShape = motif(fx);
            ((ObjectNode) badShape.get("stages").get(0)).put("shape", "fan-around");
            assertEquals(422, post(c.port, "/inv/pattern/branching", badShape.toString()).statusCode());

            ObjectNode bareFilter = motif(fx);
            bareFilter.set("filter", JSON.readTree("{\"kind\":\"condition\",\"field\":\"AMOUNT\",\"operator\":\">=\",\"value\":\"1\"}"));
            assertEquals(422, post(c.port, "/inv/pattern/branching", bareFilter.toString()).statusCode());

            // A threshold value is BOUND: a string where a number belongs is refused, never spliced into SQL.
            ObjectNode spliced = motif(fx);
            ((ObjectNode) spliced.get("stages").get(0).get("threshold")).put("min", "0) OR (1=1");
            assertEquals(422, post(c.port, "/inv/pattern/branching", spliced.toString()).statusCode());
        }
    }

    @Test
    void noWriteRootIs503(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            assertEquals(503, post(c.port, "/inv/pattern/branching", motif(fixture()).toString()).statusCode());
        }
    }

    /** LA-04 pattern: the search is audited as its own analytic act. */
    @Test
    void aPatternSearchEmitsItsOwnAuditEvent(@TempDir Path cfg, @TempDir Path root) throws Exception {
        JsonNode fx = fixture();
        try (Ctx c = open(cfg, root)) {
            seed(c, rowsSql(fx, null));
            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            EventLog.current().addSubscriber(sub);
            try {
                ok(c, motif(fx).toString());
            } finally {
                EventLog.current().removeSubscriber(sub);
            }
            Event e = seen.stream().filter(x -> EventType.LINK_PATTERN_MATCHED.equals(x.type())).findFirst()
                    .orElseThrow(() -> new AssertionError("no LINK_PATTERN_MATCHED in " + seen));
            assertEquals("link.pattern.matched", e.attributes().get("action"));
            assertEquals("tx_ds", e.attributes().get("dataset"));
            assertEquals("1", e.attributes().get("matches"));
            assertEquals("false", e.attributes().get("truncated"));
        }
    }
}
