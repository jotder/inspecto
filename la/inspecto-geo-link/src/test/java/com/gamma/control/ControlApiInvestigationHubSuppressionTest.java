package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Supernode suppression on the Investigation {@code expand} over real HTTP: a candidate with more distinct contacts than
 * the rung's {@code hubThreshold} is ADMITTED and flagged {@code highConnectivity}, left out of later frontiers, and
 * expanded only through the analyst's sealed override ({@code expandHubs} / {@code includeHubs}).
 *
 * <p>Fixture: {@code a→h}, {@code a→b}, {@code h→x1..x5}, {@code b→c}. Distinct contacts: {@code h} 6 (a, x1..x5), {@code b} 2,
 * {@code a} 2, {@code c} and each {@code x} 1. With {@code hubThreshold: 3}, expanding from {@code a} admits {@code b} and
 * {@code h} and flags {@code h} only.
 */
class ControlApiInvestigationHubSuppressionTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROWS = String.join(",",
            "('a','h','voice')", "('a','b','voice')", "('h','x1','voice')", "('h','x2','voice')", "('h','x3','voice')",
            "('h','x4','voice')", "('h','x5','voice')", "('b','c','voice')");
    private static final Set<String> XS = Set.of("x1", "x2", "x3", "x4", "x5");
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT caller, callee, channel FROM (VALUES " + ROWS + ") AS t(caller,callee,channel)",
                    "2026-10-11T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port() + "/api/v1" + path))
                .header("Content-Type", "application/json");
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    /** A new Investigation over the fixture, seeded with {@code a}. */
    private void seeded(Ctx c, String id) throws Exception {
        data(send(c, "POST", "/inv/investigations", "{\"purpose\":\"test\",\"id\":\"" + id + "\",\"dataset\":\"calls_ds\","
                + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}"));
        op(c, id, "{\"op\":\"seed\",\"ids\":[\"a\"]}");
    }

    private JsonNode op(Ctx c, String id, String body) throws Exception {
        return data(send(c, "POST", "/inv/investigations/" + id + "/ops", body));
    }

    private HttpResponse<String> opRaw(Ctx c, String id, String body) throws Exception {
        return send(c, "POST", "/inv/investigations/" + id + "/ops", body);
    }

    private static Set<String> admitted(JsonNode step) {
        Set<String> out = new TreeSet<>();
        for (JsonNode n : step.at("/delta/admitted")) out.add(n.asText());
        return out;
    }

    /** entityId -> highConnectivity, from the Working Set relation. */
    private Map<String, Boolean> flags(Ctx c, String id) throws Exception {
        JsonNode ws = data(send(c, "GET", "/inv/investigations/" + id + "/working-set?of=entities", null));
        boolean listed = false;
        for (JsonNode col : ws.get("columns")) listed |= "highConnectivity".equals(col.asText());
        assertTrue(listed, "the entities relation declares the column: " + ws.get("columns"));
        Map<String, Boolean> out = new TreeMap<>();
        for (JsonNode r : ws.get("rows")) out.put(r.get("entityId").asText(), r.get("highConnectivity").asBoolean());
        return out;
    }

    private String logText(Ctx c, String id, int index) throws Exception {
        return data(send(c, "GET", "/inv/investigations/" + id + "/log", null)).at("/entries/" + index + "/text").asText();
    }

    @Test
    void aHubIsAdmittedFlaggedAndNotExpandedFurther(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seeded(c, "t");
            JsonNode first = op(c, "t", "{\"op\":\"expand\",\"hubThreshold\":3}");
            assertEquals(Set.of("b", "h"), admitted(first), "a hub is displayed, never dropped");
            assertEquals(1, first.at("/read/hubsFlagged").asInt(), first.toString());
            assertEquals(3, first.at("/read/rung/hubThreshold").asInt());
            String line = logText(c, "t", 1);
            assertTrue(line.contains("Flagged 1 entity as high connectivity (more than 3 distinct contacts)") && line.contains(": h."), line);

            Map<String, Boolean> f = flags(c, "t");
            assertTrue(f.get("h"), f.toString());
            assertFalse(f.get("b") || f.get("a"), f.toString());

            JsonNode second = op(c, "t", "{\"op\":\"expand\"}");
            assertEquals(Set.of("c"), admitted(second), "h is left out of the frontier, so x1..x5 stay out");
            assertEquals(List.of("h"), List.of(second.at("/read/rung/hubsHeld/0").asText()));
            assertEquals(500, second.at("/read/rung/hubThreshold").asInt(), "the shipped default is resolved into the rung");
            assertEquals(0, second.at("/read/hubsFlagged").asInt());
            assertTrue(logText(c, "t", 2).contains("Left out 1 high-connectivity entity (not expanded): h."), logText(c, "t", 2));

            HttpResponse<String> named = opRaw(c, "t", "{\"op\":\"expand\",\"ids\":[\"h\"]}");
            assertEquals(422, named.statusCode(), "a named hub is refused without the override, never silently dropped");
            assertTrue(named.body().contains("expandHubs"), named.body());
        }
    }

    @Test
    void theAnalystOverrideExpandsThroughTheHubAndIsSealed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seeded(c, "o");
            op(c, "o", "{\"op\":\"expand\",\"hubThreshold\":3}");
            JsonNode through = op(c, "o", "{\"op\":\"expand\",\"ids\":[\"h\"],\"expandHubs\":[\"h\"]}");
            assertEquals(XS, admitted(through));
            assertTrue(logText(c, "o", 2).contains("Analyst override: expanded through high-connectivity h."), logText(c, "o", 2));
            String log = Files.readString(root.resolve("audit/snapshots/investigations/o/log.jsonl"));
            assertTrue(log.contains("\"expandHubs\":[\"h\"]"), "the override is sealed in the op: " + log);
            assertTrue(flags(c, "o").get("h"), "still flagged after the override");

            seeded(c, "all");
            op(c, "all", "{\"op\":\"expand\",\"hubThreshold\":3}");
            JsonNode every = op(c, "all", "{\"op\":\"expand\",\"includeHubs\":true}");
            Set<String> want = new TreeSet<>(XS);
            want.add("c");
            assertEquals(want, admitted(every));
            assertTrue(logText(c, "all", 2).contains("expanded through every high-connectivity entity"), logText(c, "all", 2));
        }
    }

    @Test
    void replayIsDeterministicAndTheRereadFindsNoDrift(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seeded(c, "r");
            op(c, "r", "{\"op\":\"expand\",\"hubThreshold\":3}");
            op(c, "r", "{\"op\":\"expand\"}");
            op(c, "r", "{\"op\":\"expand\",\"expandHubs\":[\"h\"]}");
            JsonNode replay = data(send(c, "POST", "/inv/investigations/r/replay", "{\"reread\":true}"));
            assertTrue(replay.get("equivalent").asBoolean(), replay.toString());
            assertFalse(replay.get("diverged").asBoolean(), replay.toString());

            // the flag lives in the sealed read, not in today's setting: raising the Space default changes nothing on replay
            Files.writeString(root.resolve("link-analysis.toon"), "hub_threshold: 100\n");
            assertTrue(data(send(c, "POST", "/inv/investigations/r/replay", "{}")).get("equivalent").asBoolean());
            assertTrue(flags(c, "r").get("h"));

            data(send(c, "POST", "/inv/investigations/r/undo", ""));
            data(send(c, "POST", "/inv/investigations/r/undo", ""));
            data(send(c, "POST", "/inv/investigations/r/undo", ""));
            assertFalse(flags(c, "r").containsKey("h"), "undoing the expand that flagged it removes the hub and its flag");
            assertTrue(data(send(c, "POST", "/inv/investigations/r/replay", "{}")).get("equivalent").asBoolean());
        }
    }

    @Test
    void anEntityAlreadyInTheWorkingSetIsNotNewlyFlaggedByAnotherFrontier(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seeded(c, "p");
            op(c, "p", "{\"op\":\"seed\",\"ids\":[\"b\"]}");   // b: a seed, degree 2 (a, c), outside the next frontier
            JsonNode step = op(c, "p", "{\"op\":\"expand\",\"ids\":[\"a\"],\"hubThreshold\":1}");
            assertEquals(Set.of("h"), admitted(step));
            assertEquals(1, step.at("/read/hubsFlagged").asInt(), step.toString());
            Map<String, Boolean> f = flags(c, "p");
            assertTrue(f.get("h"), "h is added by this step and above the threshold: " + f);
            assertFalse(f.get("b"), "b was in the Working Set before this step, so a's rows do not flag it: " + f);
            assertTrue(data(send(c, "POST", "/inv/investigations/p/replay", "{\"reread\":true}")).get("equivalent").asBoolean());
        }
    }

    @Test
    void theSpaceSettingIsTheDefaultThreshold(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seeded(c, "d");
            assertEquals(0, op(c, "d", "{\"op\":\"expand\"}").at("/read/hubsFlagged").asInt(), "default 500: nothing here is a hub");
            Files.writeString(root.resolve("link-analysis.toon"), "hub_threshold: 3\n");
            seeded(c, "s");
            JsonNode s = op(c, "s", "{\"op\":\"expand\"}");
            assertEquals(3, s.at("/read/rung/hubThreshold").asInt());
            assertEquals(1, s.at("/read/hubsFlagged").asInt());
            seeded(c, "w");
            assertEquals(0, op(c, "w", "{\"op\":\"expand\",\"hubThreshold\":10}").at("/read/hubsFlagged").asInt(),
                    "the op's own threshold wins over the Space's");
        }
    }

    @Test
    void invalidHubOptionsAreRefusedAndNeverLogged(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seeded(c, "v");
            for (String bad : List.of("{\"op\":\"expand\",\"hubThreshold\":0}", "{\"op\":\"expand\",\"hubThreshold\":-5}",
                    "{\"op\":\"expand\",\"hubThreshold\":1.5}", "{\"op\":\"expand\",\"hubThreshold\":\"many\"}",
                    "{\"op\":\"expand\",\"includeHubs\":\"yes\"}", "{\"op\":\"expand\",\"expandHubs\":\"h\"}",
                    "{\"op\":\"expand\",\"expandHubs\":[\"\"]}", "{\"op\":\"expand\",\"expandHubs\":[\"zz\"]}"))
                assertEquals(422, opRaw(c, "v", bad).statusCode(), bad);
            assertEquals(1, data(send(c, "GET", "/inv/investigations/v/log", null)).get("entries").size(), "nothing refused reached the log");
            assertEquals(Set.of("b", "h"), admitted(op(c, "v", "{\"op\":\"expand\",\"hubThreshold\":1}")), "positive twin");
        }
    }
}
