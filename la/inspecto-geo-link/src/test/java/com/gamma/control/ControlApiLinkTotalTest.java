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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-COLUMN-ROLES-2 (operator decision D-2 a): a link read may name a Measure column ({@code measureCol}, an edge mapping's
 * {@code measureColumn}) that the server SUMs per folded link as {@code total}, beside the count and never as a grouping key -
 * over the Dataset in SQL and, for a neighbours read, from the link index, which answers exactly what the flat read answers.
 * A value that is no number counts nothing; a link with none has total null. Synthetic data only.
 */
class ControlApiLinkTotalTest {

    @BeforeAll static void raiseBudget() {
        System.setProperty("control.rateLimit.linkAnalysis.capacity", "10000");
    }

    @AfterAll static void restoreBudget() {
        System.clearProperty("control.rateLimit.linkAnalysis.capacity");
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient client = HttpClient.newHttpClient();

    /** A>B three times (10, 20, a non-number), A>C once with no amount, B>A once (5), and two kinds. */
    private static final String VIEW = "SELECT * FROM (VALUES "
            + "('A','B','call','10'),('A','B','call','20'),('A','B','sms','n/a'),('A','C','call',CAST(NULL AS VARCHAR)),"
            + "('B','A','call','5')) AS v(s,t,kind,amt)";

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    private static Ctx open(Path cfg, Path root, String settings) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            Files.writeString(root.resolve("link-analysis.toon"), settings);
            new ViewStore(root.resolve("views")).write(new ViewDefinition("t_view", "flow-x", List.of(), VIEW, "2026-11-05T00:00:00Z"));
            new ComponentStore(root.resolve("registry")).write("dataset", "t_ds", Map.of("view", "t_view"));
            return new Ctx(svc, api, api.port(), root);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private static JsonNode post(Ctx c, String path, String body, int expected) throws Exception {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").POST(BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
        assertEquals(expected, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static JsonNode get(Ctx c, String path) throws Exception {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path)).GET().build(),
                BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    /** source>target|kind -> count/total ("-" when the row carries no total). */
    private static Map<String, String> links(JsonNode rows) {
        Map<String, String> out = new TreeMap<>();
        for (JsonNode r : rows) {
            String total = !r.has("total") ? "-" : r.get("total").isNull() ? "null" : String.valueOf(r.get("total").asDouble());
            out.put(r.get("source").asText() + ">" + r.get("target").asText() + "|" + r.path("kind").asText(), r.get("count").asLong() + "/" + total);
        }
        return out;
    }

    private static final String COLS = "\"dataset\":\"t_ds\",\"sourceCol\":\"s\",\"targetCol\":\"t\",\"linkKindCol\":\"kind\"";

    @Test
    void aReadSumsItsMeasurePerLinkBesideTheCountAndStaysFolded(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "index:\n  enabled: false\n")) {
            Map<String, String> plain = links(post(c, "/inv/projection", "{" + COLS + "}", 200).get("rows"));
            assertEquals(Map.of("A>B|call", "2/-", "A>B|sms", "1/-", "A>C|call", "1/-", "B>A|call", "1/-"), plain, "no measure, no total");

            Map<String, String> summed = links(post(c, "/inv/projection", "{" + COLS + ",\"measureCol\":\"amt\"}", 200).get("rows"));
            assertEquals(Map.of("A>B|call", "2/30.0", "A>B|sms", "1/null", "A>C|call", "1/null", "B>A|call", "1/5.0"), summed,
                    "the same links, each with its sum; a non-number and a missing value count nothing");

            // a multi-Dataset read: an edge mapping names its own measure column
            JsonNode multi = post(c, "/inv/projection/multi", "{\"edges\":[{\"dataset\":\"t_ds\",\"sourceColumn\":\"s\",\"targetColumn\":\"t\","
                    + "\"measureColumn\":\"amt\"}]}", 200);
            Map<String, String> m = new TreeMap<>();
            for (JsonNode r : multi.get("edges")) m.put(r.get("source").asText() + ">" + r.get("target").asText(), r.get("count").asLong() + "/" + r.get("total"));
            assertEquals(Map.of("A>B", "3/30.0", "A>C", "1/null", "B>A", "1/5.0"), m);

            // a column the Dataset has not got is refused, never summed as nothing
            post(c, "/inv/projection", "{" + COLS + ",\"measureCol\":\"nope\"}", 422);
        }
    }

    @Test
    void aNeighboursReadFromTheIndexSumsWhatTheFlatReadSums(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "index:\n  enabled: true\n")) {
            String id = post(c, "/inv/index/builds", "{\"dataset\":\"t_ds\",\"sourceCol\":\"s\",\"targetCol\":\"t\",\"kindCol\":\"kind\",\"attrCols\":[\"amt\"]}", 202)
                    .get("buildId").asText();
            long end = System.nanoTime() + 60_000_000_000L;
            String st;
            do {
                st = get(c, "/inv/index/builds/" + id).get("status").asText();
                assertFalse(st.equals("FAILED") || st.equals("CANCELLED"), "build " + st);
                assertTrue(System.nanoTime() < end, "index build timed out");
                Thread.sleep(10);
            } while (!st.equals("COMPLETED"));

            String body = "{" + COLS + ",\"value\":\"A\",\"measureCol\":\"amt\"}";
            JsonNode viaIndex = post(c, "/inv/projection/neighbors", body, 200);
            assertEquals("index", viaIndex.at("/source/kind").asText(), viaIndex.get("source").toString());
            Files.writeString(root.resolve("link-analysis.toon"), "index:\n  enabled: false\n");
            JsonNode viaFlat = post(c, "/inv/projection/neighbors", body, 200);
            assertEquals("index_disabled", viaFlat.at("/source/reason").asText());
            assertEquals(links(viaFlat.get("rows")), links(viaIndex.get("rows")));
            assertEquals("2/30.0", links(viaIndex.get("rows")).get("A>B|call"), "not vacuous");

            // a measure the index does not carry is read flat, saying why
            Files.writeString(root.resolve("link-analysis.toon"), "index:\n  enabled: true\n");
            JsonNode notCarried = post(c, "/inv/projection/neighbors", "{" + COLS + ",\"value\":\"A\",\"measureCol\":\"s\"}", 200);
            assertEquals("column_not_indexed", notCarried.at("/source/reason").asText());
        }
    }
}
