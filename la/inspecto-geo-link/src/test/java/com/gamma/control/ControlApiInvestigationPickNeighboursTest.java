package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-EXPAND-PICK-NEIGHBOURS-1, over real HTTP: an {@code expand} restricted to chosen neighbours ({@code only}).
 * <ul>
 *   <li>the sealed read holds only the chosen neighbours, the Working Set gains only them, and the entity is NOT marked
 *       expanded (its other links are unread);</li>
 *   <li>a restricted expand of at most {@code 50} neighbours is never held for four-eyes, a plain one is, and a longer
 *       list is judged as plain (operator 2026-10-13);</li>
 *   <li>the sealed read replays equivalent, a malformed list is a 422, and the log line names the restriction.</li>
 * </ul>
 * Fixture: {@code a} links to {@code h} and {@code b} (and itself); {@code h} has five more contacts.
 */
class ControlApiInvestigationPickNeighboursTest {
    @BeforeAll static void raiseLinkAnalysisBudget() {
        System.setProperty("control.rateLimit.linkAnalysis.capacity", "10000");
    }

    @AfterAll static void restoreLinkAnalysisBudget() {
        System.clearProperty("control.rateLimit.linkAnalysis.capacity");
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DISABLED = "index:\n  enabled: false\n";
    private static final String ROWS = String.join(",",
            "('a','h','voice',2)", "('a','b','voice',1)", "('a','b','voice',4)", "('h','x1','voice',1)", "('h','x2','voice',1)",
            "('h','x3','voice',2)", "('h','x4','voice',1)", "('h','x5','voice',1)", "('b','c','voice',3)", "('a','a','voice',5)");
    private final HttpClient client = HttpClient.newHttpClient();
    private final AtomicInteger seq = new AtomicInteger();

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
            Files.writeString(writeRoot.resolve("link-analysis.toon"), DISABLED);
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT caller, callee, channel, n FROM (VALUES " + ROWS + ") AS t(caller,callee,channel,n)", "2026-10-13T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "p_ds", Map.of("view", "calls_view"));
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

    private static JsonNode data(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private String investigation(Ctx c) throws Exception {
        String id = "i" + seq.incrementAndGet();
        data(send(c, "POST", "/inv/investigations", "{\"purpose\":\"test\",\"id\":\"" + id + "\",\"dataset\":\"p_ds\","
                + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}"), 200);
        data(send(c, "POST", "/inv/investigations/" + id + "/ops", "{\"op\":\"seed\",\"ids\":[\"a\"]}"), 200);
        return id;
    }

    private HttpResponse<String> expand(Ctx c, String id, String extra) throws Exception {
        return send(c, "POST", "/inv/investigations/" + id + "/ops", "{\"op\":\"expand\"" + extra + "}");
    }

    /** entityId -> expanded, from the Working Set relation. */
    private Map<String, Boolean> expanded(Ctx c, String id) throws Exception {
        JsonNode ws = data(send(c, "GET", "/inv/investigations/" + id + "/working-set?of=entities", null), 200);
        Map<String, Boolean> out = new TreeMap<>();
        for (JsonNode r : ws.get("rows")) out.put(r.get("entityId").asText(), r.get("expanded").asBoolean());
        return out;
    }

    @Test
    void theSealedReadHoldsOnlyTheChosenNeighboursAndTheEntityStaysUnexpanded(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = investigation(c);
            JsonNode step = data(expand(c, id, ",\"only\":[\"b\"]"), 200);
            assertEquals("[\"b\"]", step.at("/read/rung/only").toString(), step.get("read").toString());
            assertEquals(1, step.at("/read/rowCount").asInt(), "only the link to b was read: " + step.get("read"));
            String line = data(send(c, "GET", "/inv/investigations/" + id + "/log", null), 200).at("/entries/1/text").asText();
            assertTrue(line.contains("restricted to 1 chosen neighbour"), line);

            Map<String, Boolean> ws = expanded(c, id);
            assertEquals(List.of("a", "b"), List.copyOf(ws.keySet()), "h was not admitted: " + ws);
            assertFalse(ws.get("a"), "a restricted expand leaves the rest of a's links unread: " + ws);

            // a later full expand reads everything and marks it expanded
            data(expand(c, id, ""), 200);
            assertTrue(expanded(c, id).get("a"));
        }
    }

    @Test
    void aShortListOfChosenNeighboursSkipsFourEyesAndALongOneDoesNot(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            Files.writeString(c.root().resolve("link-analysis.toon"), DISABLED + "four_eyes_fan_out_above: 1\n");
            String id = investigation(c);
            assertEquals("pending", data(expand(c, id, ""), 200).get("status").asText(), "a plain expand with the fan-out unbounded is held");
            // one pending request is allowed per Investigation, so each case below uses a fresh one
            String id2 = investigation(c);
            assertTrue(!"pending".equals(data(expand(c, id2, ",\"only\":[\"h\"]"), 200).path("status").asText()), "a short list runs at once");

            StringBuilder many = new StringBuilder();
            for (int i = 0; i < 51; i++) many.append(i == 0 ? "" : ",").append("\"n").append(i).append('"');
            String id3 = investigation(c);
            assertEquals("pending", data(expand(c, id3, ",\"only\":[" + many + "]"), 200).get("status").asText(),
                    "51 chosen neighbours are judged as a plain expand");
        }
    }

    @Test
    void theRestrictedReadReplaysEquivalent(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = investigation(c);
            data(expand(c, id, ",\"only\":[\"h\",\"b\"]"), 200);
            JsonNode replay = data(send(c, "POST", "/inv/investigations/" + id + "/replay", "{\"reread\":true}"), 200);
            assertTrue(replay.get("equivalent").asBoolean(), replay.toString());
        }
    }

    @Test
    void aMalformedListIsRefusedAndAnUnknownNeighbourReadsNothing(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = investigation(c);
            assertEquals(422, expand(c, id, ",\"only\":\"b\"").statusCode());
            assertEquals(422, expand(c, id, ",\"only\":[\"\"]").statusCode());
            JsonNode none = data(expand(c, id, ",\"only\":[\"nobody\"]"), 200);
            assertEquals(0, none.at("/read/rowCount").asInt());
        }
    }

    private static final String LIST = "{\"dataset\":\"p_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\",\"list\":true,";

    @Test
    void theNeighbourListHasTheRealTotalAWindowAndIsNeverHeldByTheFanOutThreshold(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            // h has 6 distinct neighbours (a + x1..x5); a fan-out threshold of 1 refuses the flat read but never the list
            Files.writeString(c.root().resolve("link-analysis.toon"), DISABLED + "four_eyes_fan_out_above: 1\n");
            assertEquals(403, send(c, "POST", "/inv/projection/neighbors",
                    LIST.replace("\"list\":true,", "") + "\"value\":\"h\"}").statusCode());
            JsonNode d = data(send(c, "POST", "/inv/projection/neighbors", LIST + "\"value\":\"h\",\"limit\":4}"), 200);
            assertEquals(6, d.get("total").asInt(), d.toString());
            assertEquals(4, d.get("neighbours").size());
            assertTrue(d.get("hasMore").asBoolean());
            // every link is one row, so ties break by id
            assertEquals("a", d.at("/neighbours/0/id").asText());
            assertEquals("x1", d.at("/neighbours/1/id").asText());
            assertEquals("voice", d.at("/neighbours/0/kinds/0").asText());
            // the next window, and a window past the end
            JsonNode next = data(send(c, "POST", "/inv/projection/neighbors", LIST + "\"value\":\"h\",\"limit\":4,\"offset\":4}"), 200);
            assertEquals(2, next.get("neighbours").size());
            assertFalse(next.get("hasMore").asBoolean());
            assertEquals(6, next.get("total").asInt());
            // a self-link is no neighbour: a has h and b only
            JsonNode ofA = data(send(c, "POST", "/inv/projection/neighbors", LIST + "\"value\":\"a\"}"), 200);
            assertEquals(2, ofA.get("total").asInt());
            assertEquals("b", ofA.at("/neighbours/0/id").asText(), "two rows to b outrank one to h: " + ofA);
            assertEquals(2, ofA.at("/neighbours/0/count").asInt());
            // unknown dataset and missing value keep their gates
            assertEquals(404, send(c, "POST", "/inv/projection/neighbors", LIST.replace("p_ds", "nope") + "\"value\":\"a\"}").statusCode());
            assertEquals(422, send(c, "POST", "/inv/projection/neighbors", LIST + "\"limit\":3}").statusCode());
        }
    }
}
