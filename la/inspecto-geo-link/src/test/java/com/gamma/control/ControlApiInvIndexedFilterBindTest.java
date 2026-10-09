package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code LA-FILTER-SQL-BIND-1} (edge-index half) - a filter operand is a BOUND parameter on the INDEX reads
 * ({@code /inv/projection/neighbors}, {@code /inv/traversal/recursive-paths}, {@code /inv/pattern/temporal}), never text spliced
 * into the per-key, per-side {@code UNION ALL} statements. Each hostile value is also a ROW value (the {@code channel} attribute of
 * three events {@code alice -> p<i>}), so a response holding exactly that link proves the value round-tripped as data through the
 * index; every response must say {@code source.kind = index}, so the flat Dataset cannot answer in its place.
 */
class ControlApiInvIndexedFilterBindTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OWNER = "Bearer owner";
    private static final String ENABLED = "index:\n  enabled: true\n";
    private final HttpClient client = HttpClient.newHttpClient();

    /** quote, backslash + quote, comment openers, a statement separator + DROP, a block comment, LIKE wildcards, unicode. */
    private static final List<String> HOSTILE = List.of(
            "o'brien",
            "\\' OR '1'='1",
            "'; DROP TABLE n_ds; --",
            "x /* c */ y -- z",
            "100%_wild\\",
            "zürich ☃ 日本");

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

    /** Three events per link, seconds apart, so a burst of {@code minEvents 3} is exactly one link; plus {@code alice -> plain}. */
    private static String view() {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i <= HOSTILE.size(); i++) {
            String channel = i < HOSTILE.size() ? HOSTILE.get(i).replace("'", "''") : "plain";
            String target = i < HOSTILE.size() ? "p" + i : "plain";
            for (int k = 0; k < 3; k++)
                rows.add("('alice','" + target + "','" + channel + "',TIMESTAMP '2026-02-01 10:00:0" + k + "')");
        }
        return "SELECT * FROM (VALUES " + String.join(",", rows) + ") AS v(s,t,channel,ts)";
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            Files.writeString(writeRoot.resolve("link-analysis.toon"), ENABLED);
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("n_view", "flow-x", List.of(), view(), "2026-10-09T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "n_ds", Map.of("view", "n_view", "owner", "analyst-1"));
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", OWNER);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode data(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private void build(Ctx c) throws Exception {
        String id = data(send(c, "POST", "/inv/index/builds",
                "{\"dataset\":\"n_ds\",\"sourceCol\":\"s\",\"targetCol\":\"t\",\"timeCol\":\"ts\",\"attrCols\":[\"channel\"]}"), 202).get("buildId").asText();
        long end = System.nanoTime() + 60_000_000_000L;
        while (true) {
            String st = data(send(c, "GET", "/inv/index/builds/" + id, null), 200).get("status").asText();
            if (st.equals("FAILED") || st.equals("CANCELLED")) throw new AssertionError("build " + st);
            if (st.equals("COMPLETED")) return;
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for the index build");
            Thread.sleep(10);
        }
    }

    private static String filter(String operator, String value) throws Exception {
        return "{\"kind\":\"group\",\"op\":\"AND\",\"items\":[{\"kind\":\"condition\",\"field\":\"channel\",\"operator\":\""
                + operator + "\",\"value\":" + JSON.writeValueAsString(value) + "}]}";
    }

    private static String req(String extra) {
        return "{\"dataset\":\"n_ds\",\"sourceCol\":\"s\",\"targetCol\":\"t\"," + extra + "}";
    }

    private void servedFromTheIndex(JsonNode d, String what) {
        assertEquals("index", d.at("/source/kind").asText(), what + " -> " + d.get("source"));
    }

    private static List<String> targets(JsonNode rows) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : rows) out.add(r.get("target").asText());
        return out;
    }

    private static Set<String> secondNodes(JsonNode d) {
        Set<String> out = new java.util.TreeSet<>();
        for (JsonNode p : d.get("paths")) out.add(p.at("/nodes/1").asText());
        return out;
    }

    @Test
    void aHostileValueIsDataOnTheIndexedNeighbors(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> Optional.of(new Subject("analyst-1", Set.of("canBuildLinkIndex", "canManageIncidents"))));
        try (Ctx c = open(cfg, root)) {
            build(c);
            for (int i = 0; i < HOSTILE.size(); i++) {
                JsonNode d = data(send(c, "POST", "/inv/projection/neighbors",
                        req("\"attrCols\":[\"channel\"],\"value\":\"alice\",\"filter\":" + filter("=", HOSTILE.get(i)))), 200);
                servedFromTheIndex(d, HOSTILE.get(i));
                // the fold binds the key BEFORE the filter's values, on both sides: a swapped order finds no 'alice'
                assertEquals(List.of("p" + i), targets(d.get("rows")), HOSTILE.get(i));
            }
            JsonNode like = data(send(c, "POST", "/inv/projection/neighbors",
                    req("\"value\":\"alice\",\"filter\":" + filter("contains", "o'brien"))), 200);
            servedFromTheIndex(like, "contains");
            assertEquals(List.of("p0"), targets(like.get("rows")), "a LIKE operand is escaped in the bound pattern");
        }
    }

    @Test
    void aHostileValueIsDataOnTheIndexedRecursivePathsAcrossEveryUnionArm(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> Optional.of(new Subject("analyst-1", Set.of("canBuildLinkIndex", "canManageIncidents"))));
        try (Ctx c = open(cfg, root)) {
            build(c);
            for (int i = 0; i < HOSTILE.size(); i++) {
                // undirected, depth 2: level 0 is two arms (alice out/in), level 1 is two arms for the one frontier key; each arm repeats the binds
                JsonNode d = data(send(c, "POST", "/inv/traversal/recursive-paths",
                        req("\"startNode\":\"alice\",\"undirected\":true,\"maxDepth\":2,\"filter\":" + filter("=", HOSTILE.get(i)))), 200);
                servedFromTheIndex(d, HOSTILE.get(i));
                // three parallel events = three one-hop paths, all to the one link whose channel is the hostile value
                assertEquals(Set.of("p" + i), secondNodes(d), HOSTILE.get(i) + " -> " + d.get("paths"));
            }
            // a frontier of several keys at level 1 (the unfiltered walk reaches every p<i>): the per-key arms each bind their own copy
            JsonNode wide = data(send(c, "POST", "/inv/traversal/recursive-paths",
                    req("\"startNode\":\"alice\",\"undirected\":true,\"maxDepth\":2,\"filter\":" + filter("!=", HOSTILE.get(0)))), 200);
            servedFromTheIndex(wide, "!=");
            Set<String> expected = new java.util.TreeSet<>(Set.of("p1", "p2", "p3", "p4", "p5", "plain"));
            assertEquals(expected, secondNodes(wide), "every link but p0's: " + wide.get("paths"));
        }
    }

    @Test
    void aHostileValueIsDataOnTheIndexedTemporalScan(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> Optional.of(new Subject("analyst-1", Set.of("canBuildLinkIndex", "canManageIncidents"))));
        try (Ctx c = open(cfg, root)) {
            build(c);
            String burst = "\"timeCol\":\"ts\",\"mode\":\"burst\",\"windowSeconds\":30,\"minEvents\":3";
            JsonNode all = data(send(c, "POST", "/inv/pattern/temporal", req(burst)), 200);
            servedFromTheIndex(all, "unfiltered");
            assertEquals(HOSTILE.size() + 1, all.get("results").size(), "twin: with no filter every link is a burst");
            for (int i = 0; i < HOSTILE.size(); i++) {
                JsonNode d = data(send(c, "POST", "/inv/pattern/temporal", req(burst + ",\"filter\":" + filter("=", HOSTILE.get(i)))), 200);
                servedFromTheIndex(d, HOSTILE.get(i));
                // the one link whose channel is the hostile value; a dropped or mis-bound value filters nothing or everything
                assertEquals(1, d.get("results").size(), HOSTILE.get(i) + " -> " + d.get("results"));
                assertEquals("alice", d.at("/results/0/source").asText());
                assertEquals("p" + i, d.at("/results/0/target").asText(), HOSTILE.get(i));
            }
        }
    }
}
