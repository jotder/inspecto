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
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-SEED-CONNECT-1, over real HTTP: the {@code connect} op - the shortest connection for every pair of seeds within 3 hops,
 * sealed as ONE step of the Investigation.
 * <p>Fixture (links are undirected; seeds 9001..9005): 9001-8001-9002 and 9001-8002-9002 (two 2-hop routes), 9001-8003-8004-9003 (EXACTLY
 * 3 hops), 9002-8101-8102-8103-9004 (4 hops: disconnected, never searched deeper) and 9005 reachable from 9001 ONLY through hub 7000
 * (degree 5, over the {@code hubThreshold} of 3 - reached, never walked through).
 */
class ControlApiInvestigationConnectOpTest {
    @BeforeAll static void raiseLinkAnalysisBudget() {
        System.setProperty("control.rateLimit.linkAnalysis.capacity", "10000");
    }

    @AfterAll static void restoreLinkAnalysisBudget() {
        System.clearProperty("control.rateLimit.linkAnalysis.capacity");
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DISABLED = "index:\n  enabled: false\n";
    private static final String ROWS = String.join(",",
            "('9001','8001','voice',3)", "('8001','9002','voice',2)", "('9001','8002','voice',1)", "('9002','8002','sms',1)",
            "('9001','8003','voice',1)", "('8003','8004','voice',1)", "('8004','9003','voice',1)",
            "('9002','8101','voice',1)", "('8101','8102','voice',1)", "('8102','8103','voice',1)", "('8103','9004','voice',1)",
            "('9001','7000','voice',1)", "('7000','9005','voice',1)", "('7000','6001','voice',1)", "('7000','6002','voice',1)",
            "('7000','6003','voice',1)");
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
        return id;
    }

    private HttpResponse<String> connect(Ctx c, String id, String seeds, String extra) throws Exception {
        return send(c, "POST", "/inv/investigations/" + id + "/ops", "{\"op\":\"connect\",\"ids\":[" + seeds + "]" + extra + "}");
    }

    private static final String SEEDS = "\"9001\",\"9002\",\"9003\",\"9004\",\"9005\"";

    private static JsonNode pair(JsonNode pairs, String a, String b) {
        for (JsonNode p : pairs) if (a.equals(p.get("a").asText()) && b.equals(p.get("b").asText())) return p;
        throw new AssertionError("no pair " + a + "/" + b + " in " + pairs);
    }

    private TreeSet<String> workingSetIds(Ctx c, String id) throws Exception {
        TreeSet<String> out = new TreeSet<>();
        for (JsonNode r : data(send(c, "GET", "/inv/investigations/" + id + "/working-set?of=entities", null), 200).get("rows"))
            out.add(r.get("entityId").asText());
        return out;
    }

    @Test
    void everyPairWithinThreeHopsIsReportedAndTheRestAreDisconnected(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = investigation(c);
            JsonNode step = data(connect(c, id, SEEDS, ",\"hubThreshold\":3"), 200);
            JsonNode pairs = step.at("/read/pairs");
            assertEquals(10, pairs.size(), "5 seeds are 10 pairs: " + pairs);

            JsonNode two = pair(pairs, "9001", "9002");
            assertTrue(two.get("connected").asBoolean());
            assertEquals(2, two.get("hops").asInt());
            assertEquals(2, two.get("routes").asInt(), "two shortest routes, via 8001 and 8002: " + two);
            assertEquals(3, two.get("route").size());

            JsonNode three = pair(pairs, "9001", "9003");
            assertTrue(three.get("connected").asBoolean(), "a pair at EXACTLY 3 hops is found: " + three);
            assertEquals(3, three.get("hops").asInt());

            JsonNode four = pair(pairs, "9002", "9004");
            assertFalse(four.get("connected").asBoolean(), "a pair at 4 hops is disconnected, never searched deeper: " + four);
            assertFalse(pair(pairs, "9001", "9004").get("connected").asBoolean());
            assertFalse(pair(pairs, "9002", "9003").get("connected").asBoolean(), "5 hops");

            JsonNode viaHub = pair(pairs, "9001", "9005");
            assertFalse(viaHub.get("connected").asBoolean(), "the only route runs through a hub, which is not walked: " + viaHub);
            assertEquals("[\"7000\"]", viaHub.get("notWalked").toString(), "and the pair names the hub it stopped at");

            // the Working Set holds the seeds plus the nodes of the example routes - nothing else
            assertEquals(new TreeSet<>(List.of("9001", "9002", "9003", "9004", "9005", "8001", "8003", "8004")), workingSetIds(c, id));
            assertEquals(1, step.at("/read/hubs").size(), step.toString());
            JsonNode log = data(send(c, "GET", "/inv/investigations/" + id + "/log", null), 200);
            String line = log.at("/entries/0/text").asText();
            assertTrue(line.contains("Connected 5 seeds within 3 hops") && line.contains("2 of 10 pairs connected"), line);
        }
    }

    @Test
    void theStepReplaysEquivalentAndReachesTheDossier(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = investigation(c);
            data(connect(c, id, "\"9001\",\"9002\",\"9003\"", ""), 200);
            JsonNode replay = data(send(c, "POST", "/inv/investigations/" + id + "/replay", "{\"reread\":true}"), 200);
            assertTrue(replay.get("equivalent").asBoolean(), replay.toString());

            HttpResponse<String> steps = send(c, "GET", "/inv/investigations/" + id + "/dossier?format=steps", null);
            assertEquals(200, steps.statusCode(), steps.body());
            assertTrue(steps.body().contains("Connected 3 seeds within 3 hops"), steps.body());
            JsonNode manifest = data(send(c, "GET", "/inv/investigations/" + id + "/dossier", null), 200).get("manifest");
            assertTrue(data(send(c, "POST", "/inv/investigations/" + id + "/dossier/verify", "{\"manifest\":" + manifest + "}"), 200)
                    .get("selfConsistent").asBoolean());
        }
    }

    @Test
    void aSealedStepSurvivesAnUndoAndAFreshReadOfTheLog(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = investigation(c);
            data(connect(c, id, "\"9001\",\"9002\"", ""), 200);
            assertTrue(workingSetIds(c, id).contains("8001"));
            data(send(c, "POST", "/inv/investigations/" + id + "/undo", ""), 200);
            assertEquals(new TreeSet<String>(), workingSetIds(c, id), "undoing the connect removes its seeds and routes");
        }
    }

    @Test
    void aMalformedConnectIsRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = investigation(c);
            assertEquals(422, connect(c, id, "\"9001\"", "").statusCode(), "one seed has no pair");
            assertEquals(422, connect(c, id, "\"9001\",\"9001\"", "").statusCode(), "a duplicate is not a second seed");
            StringBuilder many = new StringBuilder();
            for (int i = 0; i < 21; i++) many.append(i == 0 ? "" : ",").append("\"s").append(i).append('"');
            assertEquals(422, connect(c, id, many.toString(), "").statusCode(), "21 seeds are over the cap of 20");
            assertEquals(422, connect(c, id, "\"9001\",\"9002\"", ",\"maxHops\":4").statusCode(), "the hop ceiling is 3");
            assertEquals(422, connect(c, id, "\"9001\",\"9002\"", ",\"maxHops\":0").statusCode());
            assertEquals(422, send(c, "POST", "/inv/investigations/" + id + "/ops", "{\"op\":\"connect\"}").statusCode(), "no seeds");
            assertEquals(404, connect(c, "nope", "\"9001\",\"9002\"", "").statusCode(), "an unknown Investigation reads as absent");
            // a lower hop limit is honoured: at 2 the 3-hop pair is disconnected
            JsonNode two = data(connect(c, id, "\"9001\",\"9003\"", ",\"maxHops\":2"), 200);
            assertFalse(pair(two.at("/read/pairs"), "9001", "9003").get("connected").asBoolean());
        }
    }

    @Test
    void maskingShowsAliasesAndNeverTheRawIds(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            Files.writeString(c.root().resolve("link-analysis.toon"), DISABLED + "masking_mode: all\n");
            String id = investigation(c);
            HttpResponse<String> r = connect(c, id, "\"9001\",\"9002\",\"9003\"", "");
            assertEquals(200, r.statusCode(), r.body());
            for (String raw : List.of("9001", "9002", "9003", "8001", "8003", "8004"))
                assertFalse(r.body().contains("\"" + raw + "\""), raw + " leaked in the connect answer: " + r.body());
            String log = send(c, "GET", "/inv/investigations/" + id + "/log", null).body();
            assertFalse(log.contains("\"9001\""), "nor in the log: " + log);
            assertTrue(r.body().contains("\"pairs\""), "the pair table is still there, in aliases");
        }
    }

    @Test
    void anExcludedEntityIsNotRoutedThrough(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = investigation(c);
            data(send(c, "POST", "/inv/investigations/" + id + "/ops", "{\"op\":\"seed\",\"ids\":[\"8001\",\"8002\"]}"), 200);
            data(send(c, "POST", "/inv/investigations/" + id + "/ops", "{\"op\":\"exclude\",\"ids\":[\"8001\",\"8002\"],\"reason\":\"test\"}"), 200);
            JsonNode step = data(connect(c, id, "\"9001\",\"9002\"", ""), 200);
            assertFalse(pair(step.at("/read/pairs"), "9001", "9002").get("connected").asBoolean(),
                    "both routes ran through excluded entities: " + step.at("/read/pairs"));
        }
    }
}
