package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.la.core.DatasetProvider;
import com.gamma.la.core.DatasetProviders;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code LA-FILTER-SQL-BIND-1} — a Link Analysis filter's operand values are BOUND parameters on the flat reads
 * ({@code /inv/projection}, {@code /neighbors}, {@code /multi}, {@code /traversal/recursive-paths},
 * {@code /pattern/temporal}), never text in the statement. Each hostile value is also a ROW value, so the route
 * returning exactly that row proves the value round-tripped as data; the port-level test proves it never reached
 * the statement text (a literal-rendering mutant returns the same rows, so only the text check can catch it).
 */
class ControlApiInvFilterBindTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** quote, backslash + quote, comment openers, a statement separator + DROP, a block comment, wildcards, unicode. */
    private static final List<String> HOSTILE = List.of(
            "o'brien",
            "\\' OR '1'='1",
            "'; DROP TABLE txns_ds; --",
            "x /* c */ y -- z",
            "100%_wild\\",
            "zürich ☃ 日本");

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    /** {@code alice -> p<i>} with {@code channel = HOSTILE[i]}, plus one plain row {@code alice -> plain}. */
    private void seed(Ctx c) throws Exception {
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < HOSTILE.size(); i++)
            values.append("('alice','p").append(i).append("','").append(HOSTILE.get(i).replace("'", "''"))
                    .append("','2026-01-05'),");
        values.append("('alice','plain','plain','2026-01-05')");
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition("txns_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES " + values + ") AS t(payer,payee,channel,booked_at)", "2026-07-08T00:00:00Z"));
        new ComponentStore(c.root.resolve("registry")).write("dataset", "txns_ds", Map.of("view", "txns_view"));
    }

    private static Map<String, Object> filter(String field, String operator, String value) {
        Map<String, Object> leaf = new LinkedHashMap<>();
        leaf.put("kind", "condition");
        leaf.put("field", field);
        leaf.put("operator", operator);
        leaf.put("value", value);
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("kind", "group");
        group.put("op", "AND");
        group.put("items", List.of(leaf));
        return group;
    }

    private JsonNode post(Ctx c, String path, Map<String, Object> body) throws Exception {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .method("POST", BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        JsonNode n = JSON.readTree(r.body());
        return n.has("data") ? n.get("data") : n;       // peel the v1 envelope
    }

    private static Map<String, Object> base() {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("dataset", "txns_ds");
        b.put("sourceCol", "payer");
        b.put("targetCol", "payee");
        return b;
    }

    private static List<String> targets(JsonNode rows) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : rows) out.add(r.get("target").asText());
        return out;
    }

    /** The port: no hostile text in the statement, one {@code ?} per bind, and the bind is the operand verbatim. */
    @Test
    void thePortBindsEveryOperandAndPutsNoneInTheStatementText(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            DatasetProvider p = DatasetProviders.require();
            for (String h : HOSTILE) {
                DatasetProvider.BoundFilter f = p.predicateBound(filter("channel", "=", h));
                assertEquals(List.of(h), f.binds(), "the operand is bound verbatim");
                assertFalse(f.sql().contains(h) || f.sql().contains("DROP") || f.sql().contains("o'brien"), f.sql());
                assertEquals(1, f.sql().chars().filter(ch -> ch == '?').count(), f.sql());
            }
            // contains pre-escapes LIKE wildcards in the BOUND pattern; the text still carries no operand
            DatasetProvider.BoundFilter like = p.predicateBound(filter("channel", "contains", "o'brien"));
            assertEquals(List.of("%o'brien%"), like.binds());
            assertFalse(like.sql().contains("brien"), like.sql());
            // a non-group root is refused exactly as predicate() refuses it
            assertThrows(IllegalArgumentException.class, () -> p.predicateBound(Map.of("kind", "condition", "field", "a")));
        }
    }

    @Test
    void aHostileValueIsDataOnProjectionAndNeighbors(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c);
            for (int i = 0; i < HOSTILE.size(); i++) {
                Map<String, Object> body = base();
                body.put("filter", filter("channel", "=", HOSTILE.get(i)));
                assertEquals(List.of("p" + i), targets(post(c, "/inv/projection", body).get("rows")), HOSTILE.get(i));
                // the neighbour pair binds BEFORE the filter's: a swapped order compares 'alice' to the operand
                body.put("value", "alice");
                assertEquals(List.of("p" + i), targets(post(c, "/inv/projection/neighbors", body).get("rows")), HOSTILE.get(i));
            }
            Map<String, Object> like = base();
            like.put("filter", filter("channel", "contains", "o'brien"));
            assertEquals(List.of("p0"), targets(post(c, "/inv/projection", like).get("rows")));
        }
    }

    @Test
    void aHostileValueIsDataOnMultiWithTwoFiltersAndOnTraversal(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c);
            for (int i = 0; i < HOSTILE.size(); i++) {
                // request-level filter AND the edge's own: two bound sets, in text order
                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("dataset", "txns_ds");
                edge.put("sourceColumn", "payer");
                edge.put("targetColumn", "payee");
                edge.put("type", "PAY");
                edge.put("filter", filter("channel", "!=", "plain"));
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("nodes", List.of());
                body.put("edges", List.of(edge));
                body.put("filter", filter("channel", "=", HOSTILE.get(i)));
                JsonNode edges = post(c, "/inv/projection/multi", body).get("edges");
                assertEquals(1, edges.size(), HOSTILE.get(i) + " -> " + edges);
                assertEquals("p" + i, edges.get(0).get("target").asText());

                Map<String, Object> walk = base();
                walk.put("startNode", "alice");
                walk.put("filter", filter("channel", "=", HOSTILE.get(i)));
                JsonNode paths = post(c, "/inv/traversal/recursive-paths", walk).get("paths");
                assertEquals(1, paths.size(), HOSTILE.get(i) + " -> " + paths);
                assertTrue(paths.get(0).toString().contains("p" + i), paths.toString());
            }
        }
    }

    @Test
    void aHostileValueIsDataOnTheTemporalScan(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seed(c);
            for (String h : HOSTILE) {
                Map<String, Object> body = base();
                body.put("timeCol", "booked_at");
                body.put("mode", "burst");
                body.put("filter", filter("channel", "=", h));
                // a lost or mis-ordered bind is a 422 (bind count / type mismatch), not a 200
                assertEquals("burst", post(c, "/inv/pattern/temporal", body).get("mode").asText(), h);
            }
        }
    }
}
