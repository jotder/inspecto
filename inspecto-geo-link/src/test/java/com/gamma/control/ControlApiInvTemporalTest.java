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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code POST /inv/pattern/temporal} (burst / periodicity over each link's event times) over real HTTP: the findings, every
 * 422 / 404 / 503 gate, the four-eyes refusal and the row cap. Negative probes each have a twin that succeeds.
 */
/* Test-scope split package com.gamma.control, like ControlApiInvPatternTest: it drives the real dispatcher. */
class ControlApiInvTemporalTest {

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

    private static void seed(Ctx c, String name, String sql) throws Exception {
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition(name + "_view", "flow-x", List.of(), sql, "2026-10-04T00:00:00Z"));
        new ComponentStore(c.root.resolve("registry")).write("dataset", name, Map.of("view", name + "_view"));
    }

    /**
     * X to Y: six events inside 25 s, one two hours later, one with no time. P to Q: hourly, five events. M to N: irregular.
     * The two directions are separate links (Y to X has one event).
     */
    private static final String CALLS = "SELECT * FROM (VALUES "
            + "('X','Y',TIMESTAMP '2026-01-01 10:00:00'),('X','Y',TIMESTAMP '2026-01-01 10:00:05'),"
            + "('X','Y',TIMESTAMP '2026-01-01 10:00:10'),('X','Y',TIMESTAMP '2026-01-01 10:00:15'),"
            + "('X','Y',TIMESTAMP '2026-01-01 10:00:20'),('X','Y',TIMESTAMP '2026-01-01 10:00:25'),"
            + "('X','Y',TIMESTAMP '2026-01-01 12:00:00'),('X','Y',CAST(NULL AS TIMESTAMP)),('Y','X',TIMESTAMP '2026-01-01 10:00:01'),"
            + "('P','Q',TIMESTAMP '2026-01-01 08:00:00'),('P','Q',TIMESTAMP '2026-01-01 09:00:00'),('P','Q',TIMESTAMP '2026-01-01 10:00:00'),"
            + "('P','Q',TIMESTAMP '2026-01-01 11:00:00'),('P','Q',TIMESTAMP '2026-01-01 12:00:00'),"
            + "('M','N',TIMESTAMP '2026-01-01 08:00:00'),('M','N',TIMESTAMP '2026-01-01 08:05:00'),('M','N',TIMESTAMP '2026-01-01 11:00:00'),"
            + "('M','N',TIMESTAMP '2026-01-01 11:01:00'),('M','N',TIMESTAMP '2026-01-02 03:00:00')"
            + ") AS t(a,b,happened)";

    private HttpResponse<String> post(Ctx c, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/inv/pattern/temporal"))
                .method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private static String body(String mode, String extra) {
        return "{\"dataset\":\"calls_ds\",\"sourceCol\":\"a\",\"targetCol\":\"b\",\"timeCol\":\"happened\",\"mode\":\"" + mode + "\""
                + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private JsonNode ok(Ctx c, String body) throws Exception {
        HttpResponse<String> r = post(c, body);
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static List<String> links(JsonNode data) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : data.get("results")) out.add(r.get("source").asText() + ">" + r.get("target").asText());
        return out;
    }

    @Test
    void aBurstIsFoundOnTheLinkThatHasOneAndNotOnTheRest(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c, "calls_ds", CALLS);
            JsonNode d = ok(c, body("burst", "\"windowSeconds\":30,\"minEvents\":5"));
            assertEquals(List.of("X>Y"), links(d), "six events in 25 s; the hourly and irregular links never reach five in 30 s");
            JsonNode b = d.get("results").get(0);
            assertEquals(6, b.get("events").asInt());
            assertEquals("2026-01-01T10:00", b.get("start").asText().substring(0, 16));
            assertEquals(1, d.get("skippedNoTime").asInt(), "the NULL-time row is counted, not silently dropped");
            assertFalse(d.get("truncated").asBoolean());
            // twin: a stricter bar finds nothing, so the answer above is the threshold at work
            assertEquals(List.of(), links(ok(c, body("burst", "\"windowSeconds\":30,\"minEvents\":7"))));
        }
    }

    @Test
    void aRegularSeriesIsPeriodicAndTheIrregularOneIsNot(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c, "calls_ds", CALLS);
            JsonNode d = ok(c, body("periodicity", ""));
            assertTrue(links(d).contains("P>Q"));
            assertFalse(links(d).contains("M>N"), "irregular gaps");
            assertFalse(links(d).contains("X>Y"), "a burst then a long silence is not regular");
            JsonNode pq = d.get("results").get(0);
            assertEquals(3600.0, pq.get("periodSeconds").asDouble(), 1e-6);
            // twin: the same series fails a bar of six events (it has five) - the threshold is what decided it
            assertFalse(links(ok(c, body("periodicity", "\"minEvents\":6"))).contains("P>Q"));
        }
    }

    @Test
    void aFilterNarrowsTheSeriesBeforeDetection(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c, "calls_ds", CALLS);
            String notX = ",\"filter\":{\"kind\":\"group\",\"op\":\"AND\",\"items\":[{\"kind\":\"condition\",\"field\":\"a\",\"operator\":\"!=\",\"value\":\"X\"}]}";
            assertEquals(List.of(), links(ok(c, body("burst", "\"windowSeconds\":30,\"minEvents\":5" + notX))));
        }
    }

    @Test
    void malformedRequestsAre422(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c, "calls_ds", CALLS);
            assertEquals(200, post(c, body("burst", "")).statusCode(), "twin: the plain request is fine");
            for (String bad : List.of(
                    body("wavelet", ""),                                                  // unknown mode
                    body("burst", "\"windowSeconds\":0"),                                 // below range: never silently clamped
                    body("burst", "\"windowSeconds\":999999"),
                    body("burst", "\"minEvents\":1"),
                    body("periodicity", "\"minEvents\":2"),
                    body("periodicity", "\"maxCv\":2"),
                    body("burst", "\"windowSeconds\":\"60\""),                            // wrong type
                    body("burst", "\"nope\":1"),                                          // unknown field
                    body("burst", "").replace("\"timeCol\":\"happened\",", ""),                 // a time column is required
                    body("burst", "").replace("\"a\"", "\"a; DROP\""),                    // not an identifier
                    body("burst", "").replace("\"timeCol\":\"happened\"", "\"timeCol\":\"ghost\""))) {
                HttpResponse<String> r = post(c, bad);
                assertEquals(422, r.statusCode(), bad + " -> " + r.body());
            }
        }
    }

    @Test
    void unknownDatasetIs404AndNoWriteRootIs503(@TempDir Path cfg, @TempDir Path root, @TempDir Path cfg2) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(404, post(c, body("burst", "").replace("calls_ds", "ghost_ds")).statusCode());
        }
        try (Ctx c = open(cfg2, null)) {
            assertEquals(503, post(c, body("burst", "")).statusCode());
        }
    }

    /** D-U7: this route has no Investigation to hold a request, so a sensitive read is refused rather than run. */
    @Test
    void aSensitiveReadIsRefusedByFourEyes(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c, "calls_ds", CALLS);
            assertEquals(200, post(c, body("burst", "")).statusCode(), "twin: no threshold set, no gate");
            Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: none\nfour_eyes_budget_above: 100\n");
            HttpResponse<String> r = post(c, body("burst", ""));
            assertEquals(403, r.statusCode(), r.body());
            assertTrue(r.body().contains("four-eyes"), r.body());
        }
    }

    /** The scan is bounded: more rows than the cap answer {@code rowCapped} + {@code truncated}, never a silent cut. */
    @Test
    void aScanPastTheRowCapSaysSo(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c, "big_ds", "SELECT 'A' AS a, 'B' AS b, TIMESTAMP '2026-01-01 00:00:00' + to_seconds(i) AS happened FROM range(200005) r(i)");
            JsonNode d = ok(c, body("burst", "").replace("calls_ds", "big_ds"));
            assertTrue(d.get("rowCapped").asBoolean());
            assertTrue(d.get("truncated").asBoolean());
            assertEquals(200_000, d.at("/fences/maxRows").asInt());
            seed(c, "calls_ds", CALLS);
            assertFalse(ok(c, body("burst", "")).get("rowCapped").asBoolean(), "twin: a small Dataset is not capped");
        }
    }
}
