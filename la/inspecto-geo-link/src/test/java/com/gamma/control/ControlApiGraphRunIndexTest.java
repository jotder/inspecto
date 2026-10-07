package com.gamma.control;

import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.audit.Event;
import com.gamma.event.EventLog;
import com.gamma.la.core.LinkEventTypes;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-3 step 7 - a Graph Run {@code input:"index"} over REAL HTTP with an ARMED Authenticator. The proof is that the same question through
 * the Working Set ({@code input:"workingSet"}, the default) and through the index gives the same graph, that every refusal is a stated
 * 422 and never a silent reroute, and that {@code workingSet} is untouched by the index setting.
 */
class ControlApiGraphRunIndexTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ANALYST = "Bearer analyst", OTHER = "Bearer other";
    private static final String ON = "masking_mode: none\nindex:\n  enabled: true\n";
    private static final String OFF = "masking_mode: none\nindex:\n  enabled: false\n";
    private static final String CREATE = "{\"id\":\"inv-g\",\"purpose\":\"Fraud referral FR-12\",\"dataset\":\"calls_ds\","
            + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
    private static final String INV = "/inv/investigations/inv-g";
    private static final List<String> NODES = List.of("n1", "n2", "n3", "n4", "n5");
    private static final String BUILD = "{\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"kindCol\":\"channel\"}";
    private static final String IDX = "\"input\":\"index\",\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\","
            + "\"linkKindCol\":\"channel\"";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
    }

    private static void subjects() {
        Set<String> caps = Set.of("canManageIncidents", "canRunLinkGraphAnalysis", "canBuildLinkIndex");
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case ANALYST -> Optional.of(new Subject("analyst-1", caps));
            case OTHER -> Optional.of(new Subject("analyst-2", caps));
            default -> Optional.empty();
        });
    }

    private Ctx open(Path configDir, Path writeRoot, String settings) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            Files.writeString(writeRoot.resolve("link-analysis.toon"), settings);
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT caller, callee, channel FROM (VALUES ('n1','n2','voice'),('n1','n2','voice'),('n1','n2','voice'),"
                            + "('n1','n2','voice'),('n1','n2','voice'),('n2','n3','voice'),('n2','n3','voice'),('n2','n3','voice'),"
                            + "('n2','n3','voice'),('n2','n3','voice'),('n1','n3','voice'),('n3','n3','voice'),"
                            + "('n3','n4','sms'),('n4','n5','voice')) AS t(caller,callee,channel)", "2026-09-30T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode data(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private JsonNode ok(Ctx c, String method, String path, String body, String auth) throws Exception {
        return data(send(c, method, path, body, auth), 200);
    }

    private void investigation(Ctx c) throws Exception {
        ok(c, "POST", "/inv/investigations", CREATE, ANALYST);
        ok(c, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":" + JSON.writeValueAsString(NODES) + "}", ANALYST);
        ok(c, "POST", INV + "/ops", "{\"op\":\"expand\"}", ANALYST);
    }

    private void build(Ctx c) throws Exception {
        String id = data(send(c, "POST", "/inv/index/builds", BUILD, ANALYST), 202).get("buildId").asText();
        long end = System.nanoTime() + 60_000_000_000L;
        for (;;) {
            String st = ok(c, "GET", "/inv/index/builds/" + id, null, ANALYST).get("status").asText();
            if (st.equals("FAILED") || st.equals("CANCELLED")) throw new AssertionError("build " + st);
            if (st.equals("COMPLETED")) return;
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for the index build");
            Thread.sleep(10);
        }
    }

    private static void settings(Ctx c, String toon) throws Exception {
        Files.writeString(c.root.resolve("link-analysis.toon"), toon);
    }

    private static String run(String algorithm, String extra) {
        return "{\"investigationId\":\"inv-g\",\"algorithm\":\"" + algorithm + "\"" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private HttpResponse<String> start(Ctx c, String body, String auth) throws Exception {
        return send(c, "POST", "/inv/graph/runs", body, auth);
    }

    private static List<String> ids(JsonNode list, String field) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : list) out.add(field == null ? n.asText() : n.get(field).asText());
        out.sort(null);
        return out;
    }

    /** The response with every field that legitimately differs between two identical runs removed. */
    private static JsonNode stable(JsonNode run) {
        ObjectNode copy = run.deepCopy();
        for (String k : List.of("runId", "createdAt", "finishedAt", "cached")) copy.remove(k);
        ((ObjectNode) copy.get("consumed")).remove("elapsedMs");
        ((ObjectNode) copy.get("consumed")).remove("work");
        if (copy.has("result") && copy.get("result").isObject()) ((ObjectNode) copy.get("result")).remove("elapsedMs");
        return copy;
    }

    // ── the catalogue ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    void theCatalogueNamesTheEnginesThatCanRunEachAlgorithm(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, ON)) {
            JsonNode d = ok(c, "GET", "/inv/graph/algorithms", null, ANALYST);
            assertEquals("memory", d.get("engine").asText(), "the single engine field is unchanged");
            Set<String> indexed = new TreeSet<>();
            for (JsonNode a : d.get("algorithms")) {
                List<String> engines = ids(a.get("engines"), null);
                if (engines.contains("index")) indexed.add(a.get("id").asText());
                assertTrue(engines.contains("memory"), a.get("id").asText());
                assertEquals(engines.contains("index") ? 2 : 1, engines.size());
            }
            assertEquals(new TreeSet<>(List.of("degreeCentrality", "egoNetwork", "neighborhood")), indexed);
        }
    }

    // ── workingSet is untouched ─────────────────────────────────────────────────────────────────────────────

    @Test
    void aWorkingSetRunIsTheSameWhetherOrNotInputIsStatedAndWhateverTheIndexSettingIs(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, OFF)) {
            investigation(c);
            String p = "\"params\":{\"node\":\"n2\",\"direction\":\"both\"}";
            JsonNode plain = ok(c, "POST", "/inv/graph/runs", run("egoNetwork", p), ANALYST);
            assertNull(plain.get("source"), "no source key on a Working Set run: the body is today's");
            assertEquals("memory", plain.get("engine").asText());
            assertFalse(plain.get("input").has("kind"));
            JsonNode stated = ok(c, "POST", "/inv/graph/runs", run("egoNetwork", p + ",\"input\":\"workingSet\""), ANALYST);
            assertEquals(stable(plain), stable(stated));
            build(c);
            settings(c, ON);
            JsonNode afterIndex = ok(c, "POST", "/inv/graph/runs", run("egoNetwork", p + ",\"kinds\":null"), ANALYST);
            assertEquals(stable(plain), stable(afterIndex), "an index, built and enabled, changes nothing for the Working Set path");
        }
    }

    // ── every refusal is stated ─────────────────────────────────────────────────────────────────────────────

    private String refused(Ctx c, String body) throws Exception {
        HttpResponse<String> r = start(c, body, ANALYST);
        assertEquals(422, r.statusCode(), r.body());
        return r.body();
    }

    @Test
    void everyRefusalIsA422ThatNamesItsCauseAndNothingFallsBackToTheWorkingSet(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, OFF)) {
            investigation(c);
            String node = "\"params\":{\"node\":\"n2\"}";
            assertTrue(refused(c, run("egoNetwork", node + ",\"input\":\"memory\"")).contains("\\\"workingSet\\\" or \\\"index\\\""));
            assertTrue(refused(c, run("egoNetwork", node + ",\"dataset\":\"calls_ds\"")).contains("only for input \\\"index\\\""));
            // the setting is off: the index is not consulted, and the run does NOT quietly use the Working Set
            assertTrue(refused(c, run("egoNetwork", node + "," + IDX)).contains("index_disabled"));
            settings(c, ON);
            assertTrue(refused(c, run("egoNetwork", node + "," + IDX)).contains("no_index"));
            build(c);
            // the positive twin of all of the above
            assertEquals("COMPLETED", ok(c, "POST", "/inv/graph/runs", run("egoNetwork", node + "," + IDX), ANALYST).get("status").asText());

            assertTrue(refused(c, run("pageRank", IDX)).contains("pageRank"), "a non-native algorithm names itself");
            assertTrue(refused(c, run("neighborhood", "\"params\":{\"node\":\"n2\",\"hops\":3}," + IDX)).contains("index cap of 2"));
            assertTrue(refused(c, run("egoNetwork", node + ",\"at\":0," + IDX)).contains("'at'"), "a log step is not served from the index");
            assertTrue(refused(c, run("egoNetwork", node + ",\"seeds\":[\"n1\"]," + IDX)).contains("only for degreeCentrality"));
            assertTrue(refused(c, run("degreeCentrality", IDX)).contains("'seeds'"));
            List<String> many = new ArrayList<>();
            for (int i = 0; i < 21; i++) many.add("s" + i);
            assertTrue(refused(c, run("degreeCentrality", "\"seeds\":" + JSON.writeValueAsString(many) + "," + IDX)).contains("cap is 20"));
            assertTrue(refused(c, run("egoNetwork", node + ",\"input\":\"index\",\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\""))
                    .contains("column_not_indexed"), "the index has a kind column the request did not name");
            assertTrue(refused(c, run("egoNetwork", node + ",\"input\":\"index\",\"dataset\":\"calls_ds\",\"sourceCol\":\"callee\",\"targetCol\":\"caller\","
                    + "\"linkKindCol\":\"channel\"")).contains("mapping_not_indexed"));
            assertTrue(refused(c, run("egoNetwork", node + ",\"kinds\":[\"voice\"],\"input\":\"index\",\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\","
                    + "\"targetCol\":\"callee\"")).contains("'kinds' needs 'linkKindCol'"));

            // hidden entities: the index cannot honour a hide, so it refuses; the Working Set run still works and counts them
            ok(c, "POST", INV + "/ops", "{\"op\":\"hide\",\"ids\":[\"n5\"]}", ANALYST);
            assertTrue(refused(c, run("egoNetwork", node + "," + IDX)).contains("hides entities"));
            assertEquals(1, ok(c, "POST", "/inv/graph/runs", run("egoNetwork", node), ANALYST).get("input").get("hiddenEntities").asInt());
        }
    }

    // ── the index answers what the Working Set answers ──────────────────────────────────────────────────────

    @Test
    void anIndexRunGivesTheWorkingSetsGraphAndSaysWhereItCameFrom(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, ON)) {
            investigation(c);
            build(c);
            for (String dir : List.of("out", "in", "both")) {
                String p = "\"params\":{\"node\":\"n2\",\"hops\":2,\"direction\":\"" + dir + "\"}";
                JsonNode ws = ok(c, "POST", "/inv/graph/runs", run("neighborhood", p), ANALYST);
                JsonNode ix = ok(c, "POST", "/inv/graph/runs", run("neighborhood", p + "," + IDX), ANALYST);
                assertEquals("COMPLETED", ix.get("status").asText(), ix.toString());
                assertEquals("index", ix.get("engine").asText());
                assertEquals(ids(ws.get("result").get("nodes"), "id"), ids(ix.get("result").get("nodes"), "id"), dir);
                assertEquals(ids(ws.get("result").get("edges"), "id"), ids(ix.get("result").get("edges"), "id"), dir);
                assertFalse(ix.get("result").get("edges").isEmpty(), "not vacuous: " + dir);
            }
            JsonNode ego = ok(c, "POST", "/inv/graph/runs", run("egoNetwork", "\"params\":{\"node\":\"n3\"},\"kinds\":[\"voice\"]," + IDX), ANALYST);
            JsonNode egoWs = ok(c, "POST", "/inv/graph/runs", run("egoNetwork", "\"params\":{\"node\":\"n3\"},\"kinds\":[\"voice\"]"), ANALYST);
            assertEquals(ids(egoWs.get("result").get("edges"), "id"), ids(ego.get("result").get("edges"), "id"), "the kinds filter agrees too");
            assertTrue(ids(ego.get("result").get("edges"), "id").contains(LinkIds.encode("n3", "n3", "voice")), "the self-loop");
            assertFalse(ids(ego.get("result").get("edges"), "id").contains(LinkIds.encode("n3", "n4", "sms")), "sms was filtered out");

            // what it says about itself
            assertEquals("index", ego.get("source").get("kind").asText());
            assertEquals(1, ego.get("source").get("version").asLong());
            assertFalse(ego.get("source").get("stale").asBoolean());
            assertEquals("index", ego.get("input").get("kind").asText());
            assertTrue(ego.get("input").get("estimated").asBoolean());
            assertEquals(1, ego.get("input").get("version").asLong());
            assertEquals(0, ego.get("input").get("droppedDangling").asInt());

            // a degree ranking is seeds only, with the Working Set's figures for those seeds
            JsonNode deg = ok(c, "POST", "/inv/graph/runs", run("degreeCentrality", "\"seeds\":[\"n3\",\"n1\",\"zz\"]," + IDX), ANALYST);
            JsonNode all = ok(c, "POST", "/inv/graph/runs", run("degreeCentrality", ""), ANALYST);
            List<String> got = new ArrayList<>(), want = new ArrayList<>();
            for (JsonNode s : deg.get("result").get("scores")) got.add(s.get("id").asText() + "=" + s.get("score").asDouble());
            for (JsonNode s : all.get("result").get("scores"))
                if (Set.of("n3", "n1").contains(s.get("id").asText())) want.add(s.get("id").asText() + "=" + s.get("score").asDouble());
            assertEquals(want, got);
            assertEquals(2, got.size(), "the seeds only - five nodes exist");
        }
    }

    // ── gates ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aStrangerGets404ForAnIndexRunAsForAnyRun(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, ON)) {
            investigation(c);
            build(c);
            String body = run("egoNetwork", "\"params\":{\"node\":\"n2\"}," + IDX);
            assertEquals(200, start(c, body, ANALYST).statusCode());
            assertEquals(404, start(c, body, OTHER).statusCode(), "not the Investigation's reader: absent");
            assertEquals(404, start(c, body.replace("\"calls_ds\"", "\"nobody_ds\""), ANALYST).statusCode(), "an unknown Dataset");
        }
    }

    @Test
    void anIndexRunPastItsBudgetAndTheCapEndBudgetExceededWithTheReasonAndNoResult(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, ON)) {
            investigation(c);
            build(c);
            // n2 has 3 raw rows... the estimate is the seeds' raw degree + 1 node each; a budget of 2 nodes is below it
            JsonNode over = ok(c, "POST", "/inv/graph/runs", run("egoNetwork", "\"params\":{\"node\":\"n2\"},\"budget\":{\"maxNodes\":2}," + IDX), ANALYST);
            assertEquals("BUDGET_EXCEEDED", over.get("status").asText());
            assertEquals("NODES", over.get("exceeded").asText());
            assertFalse(over.has("result"));
            assertTrue(over.get("reason").asText().contains("estimated"), over.get("reason").asText());
            assertEquals("index", over.get("source").get("kind").asText());
            // the twin with room
            assertEquals("COMPLETED", ok(c, "POST", "/inv/graph/runs", run("egoNetwork", "\"params\":{\"node\":\"n2\"}," + IDX), ANALYST).get("status").asText());
        }
    }

    // ── masking after the cache ─────────────────────────────────────────────────────────────────────────────

    @Test
    void anIndexResultIsMaskedAfterTheCacheAndARawIdIsAnAbsentNode(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "masking_mode: all\nindex:\n  enabled: true\n")) {
            investigation(c);
            build(c);
            Set<String> masked = new TreeSet<>();
            for (JsonNode e : ok(c, "GET", INV + "/working-set?of=entities", null, ANALYST).get("rows")) masked.add(e.get("entityId").asText());
            assertTrue(masked.stream().allMatch(s -> s.startsWith("masked:")), masked.toString());
            // the pseudonym of n3 is the one whose neighbourhood has the most nodes (n3 touches n1, n2, n4 and itself)
            JsonNode best = null;
            String bestId = null;
            for (String m : masked) {
                JsonNode d = ok(c, "POST", "/inv/graph/runs", run("egoNetwork", "\"params\":{\"node\":\"" + m + "\"}," + IDX), ANALYST);
                if (best == null || d.get("result").get("nodes").size() > best.get("result").get("nodes").size()) {
                    best = d;
                    bestId = m;
                }
            }
            assertEquals(4, best.get("result").get("nodes").size(), "n3 touches n1, n2 and n4");
            JsonNode again = ok(c, "POST", "/inv/graph/runs", run("egoNetwork", "\"params\":{\"node\":\"" + bestId + "\"}," + IDX), ANALYST);
            assertTrue(again.get("cached").asBoolean(), "the second identical run is a cache hit");
            for (JsonNode d : List.of(best, again)) {
                String text = d.toString();
                for (String raw : NODES) assertFalse(text.contains("\"" + raw + "\""), "raw id " + raw + " in " + text);
                for (JsonNode e : d.get("result").get("edges"))
                    for (String part : LinkIds.decode(e.get("id").asText())) assertFalse(NODES.contains(part), "raw endpoint in an edge id");
            }
            // a RAW id while masking hides it names no node: an empty answer, the same as for an unknown id
            JsonNode raw = ok(c, "POST", "/inv/graph/runs", run("egoNetwork", "\"params\":{\"node\":\"n2\"}," + IDX), ANALYST);
            assertEquals(0, raw.get("result").get("nodes").size());
        }
    }

    // ── the audit trail ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void theAuditTrailNamesTheSourceAndTheIndexVersion(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        List<Event> seen = new CopyOnWriteArrayList<>();
        Consumer<Event> sub = seen::add;
        EventLog.current().addSubscriber(sub);
        try (Ctx c = open(cfg, root, ON)) {
            investigation(c);
            build(c);
            JsonNode ix = ok(c, "POST", "/inv/graph/runs", run("egoNetwork", "\"params\":{\"node\":\"n2\"}," + IDX), ANALYST);
            JsonNode ws = ok(c, "POST", "/inv/graph/runs", run("egoNetwork", "\"params\":{\"node\":\"n2\"}"), ANALYST);
            long end = System.nanoTime() + 15_000_000_000L;
            while (seen.stream().filter(e -> e.type().equals(LinkEventTypes.LINK_GRAPH_RUN_COMPLETED)).count() < 2) {
                if (System.nanoTime() > end) throw new AssertionError("no completed events");
                Thread.sleep(10);
            }
            Event done = find(seen, LinkEventTypes.LINK_GRAPH_RUN_COMPLETED, ix.get("runId").asText());
            assertEquals("index", done.attributes().get("source"));
            assertEquals("1", String.valueOf(done.attributes().get("indexVersion")));
            assertEquals("index", done.attributes().get("engine"));
            Event started = find(seen, LinkEventTypes.LINK_GRAPH_RUN_STARTED, ix.get("runId").asText());
            assertEquals("index", started.attributes().get("source"));
            Event wsDone = find(seen, LinkEventTypes.LINK_GRAPH_RUN_COMPLETED, ws.get("runId").asText());
            assertEquals("workingSet", wsDone.attributes().get("source"));
            assertNull(wsDone.attributes().get("indexVersion"));
            assertNotEquals(done.attributes().get("key"), wsDone.attributes().get("key"));
        } finally {
            EventLog.current().removeSubscriber(sub);
        }
    }

    private static Event find(List<Event> seen, String type, String runId) {
        return seen.stream().filter(e -> type.equals(e.type()) && runId.equals(e.attributes().get("runId"))).findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " for " + runId + " in " + seen.stream().map(Event::type).toList()));
    }
}
