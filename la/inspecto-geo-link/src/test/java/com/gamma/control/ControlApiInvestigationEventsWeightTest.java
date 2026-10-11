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
 * The Investigation's optional {@code eventsCol} over real HTTP: on a pre-aggregated Dataset (one row per pair and kind, with
 * an event count) an expand weighs each pair by {@code SUM(eventsCol)} - for {@code minEvents}, the {@code maxFanOut} rank and
 * the budget order - while an Investigation without it counts rows exactly as before.
 *
 * <p>Fixture rows {@code (caller, callee, channel, events)}: {@code a→b} voice 5 and again 2 (7 in all, two rows), {@code a→c}
 * voice 1, {@code a→d} sms 3, {@code a→e} voice NULL (one row's worth).
 */
class ControlApiInvestigationEventsWeightTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROWS = String.join(",",
            "('a','b','voice',5)", "('a','b','voice',2)", "('a','c','voice',1)", "('a','d','sms',3)", "('a','e','voice',NULL)");
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
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("links_view", "flow-x", List.of(),
                    "SELECT caller, callee, channel, events FROM (VALUES " + ROWS + ") AS t(caller,callee,channel,events)",
                    "2026-10-11T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "links_ds", Map.of("view", "links_view"));
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

    private HttpResponse<String> create(Ctx c, String id, String eventsCol) throws Exception {
        return send(c, "POST", "/inv/investigations", "{\"purpose\":\"test\",\"id\":\"" + id + "\",\"dataset\":\"links_ds\","
                + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\""
                + (eventsCol == null ? "" : ",\"eventsCol\":\"" + eventsCol + "\"") + "}");
    }

    private void seeded(Ctx c, String id, String eventsCol) throws Exception {
        data(create(c, id, eventsCol));
        op(c, id, "{\"op\":\"seed\",\"ids\":[\"a\"]}");
    }

    private JsonNode op(Ctx c, String id, String body) throws Exception {
        return data(send(c, "POST", "/inv/investigations/" + id + "/ops", body));
    }

    private static Set<String> admitted(JsonNode step) {
        Set<String> out = new TreeSet<>();
        for (JsonNode n : step.at("/delta/admitted")) out.add(n.asText());
        return out;
    }

    /** target -> sealed count of the expand at log line {@code step}. */
    private static Map<String, Long> counts(Path root, String id, int step) throws Exception {
        List<String> lines = Files.readAllLines(root.resolve("audit/snapshots/investigations/" + id + "/log.jsonl"));
        Map<String, Long> out = new TreeMap<>();
        for (JsonNode r : JSON.readTree(lines.get(step - 1)).at("/read/rows")) out.put(r.get("target").asText(), r.get("count").asLong());
        return out;
    }

    @Test
    void minEventsKeepsThePairsWhoseEventsReachIt(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seeded(c, "w", "events");
            assertEquals(Set.of("b", "d"), admitted(op(c, "w", "{\"op\":\"expand\",\"minEvents\":2}")));
            assertEquals(Map.of("b", 7L, "d", 3L), counts(root, "w", 2), "a pair weighs the SUM of its rows' events");
            seeded(c, "all", "events");
            op(c, "all", "{\"op\":\"expand\"}");
            assertEquals(Map.of("b", 7L, "c", 1L, "d", 3L, "e", 1L), counts(root, "all", 2), "a NULL count is one row's worth");
            String header = Files.readString(root.resolve("audit/snapshots/investigations/w/header.json"));
            assertTrue(header.contains("\"eventsCol\":\"events\""), "the binding is sealed in the header: " + header);
        }
    }

    @Test
    void theFanOutCapAndTheBudgetKeepTheHeaviest(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seeded(c, "f", "events");
            JsonNode capped = op(c, "f", "{\"op\":\"expand\",\"maxFanOut\":2}");
            assertEquals(Set.of("b", "d"), admitted(capped), "ranked by events, not lexically (c would come before d)");
            assertEquals(2, capped.at("/read/fanOutCapped").asInt(), capped.toString());
            seeded(c, "g", "events");
            JsonNode budget = op(c, "g", "{\"op\":\"expand\",\"budget\":1}");
            assertEquals(Set.of("b"), admitted(budget));
            assertTrue(budget.get("truncated").asBoolean(), budget.toString());
        }
    }

    @Test
    void withoutAnEventsColumnRowsAreCountedAsBeforeAndReplayIsUnchanged(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seeded(c, "old", null);
            assertEquals(Set.of("b"), admitted(op(c, "old", "{\"op\":\"expand\",\"minEvents\":2}")), "two rows a→b; every other pair one row");
            assertEquals(Map.of("b", 2L), counts(root, "old", 2));
            String header = Files.readString(root.resolve("audit/snapshots/investigations/old/header.json"));
            assertFalse(header.contains("eventsCol"), "an unweighted header carries no new key: " + header);
            for (String id : List.of("old")) {
                JsonNode replay = data(send(c, "POST", "/inv/investigations/" + id + "/replay", "{\"reread\":true}"));
                assertTrue(replay.get("equivalent").asBoolean(), replay.toString());
                assertFalse(replay.get("diverged").asBoolean(), replay.toString());
            }
            seeded(c, "new", "events");
            op(c, "new", "{\"op\":\"expand\",\"minEvents\":2}");
            JsonNode replay = data(send(c, "POST", "/inv/investigations/new/replay", "{\"reread\":true}"));
            assertTrue(replay.get("equivalent").asBoolean() && !replay.get("diverged").asBoolean(), replay.toString());
        }
    }

    @Test
    void aBadEventsColumnIsRefusedAndNothingIsCreated(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            for (String bad : List.of("nope", "channel", "bad;col")) {
                HttpResponse<String> r = create(c, "x", bad);
                assertEquals(422, r.statusCode(), bad + ": " + r.body());
            }
            assertTrue(create(c, "x", "channel").body().contains("integer"), "the type is named");
            assertEquals(404, send(c, "GET", "/inv/investigations/x/log", null).statusCode(), "nothing refused was written");
            assertEquals(200, create(c, "x", "events").statusCode(), "positive twin");
        }
    }
}
