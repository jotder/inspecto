package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code POST /inv/pattern/temporal} (LA-INVESTIGATION-OPS-DEFERRED-1) over real HTTP with an ARMED Authenticator: the per-ENTITY
 * series ({@code series: "entity"}; default stays per link) and the edge-index read. The proof is EQUIVALENCE: the same request through
 * the index ({@code index.enabled} on) and through the flat Dataset (off) gives the same findings, for both series and both modes, on a
 * corpus with a burst on one link, an hourly link, a self-loop, a NULL time and NULL endpoints. Each negative has a twin that succeeds.
 */
class ControlApiInvTemporalIndexedTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OWNER = "Bearer owner", STRANGER = "Bearer stranger";
    private static final String ENABLED = "index:\n  enabled: true\n";
    private static final String DISABLED = "index:\n  enabled: false\n";
    private final HttpClient client = HttpClient.newHttpClient();

    /**
     * A to B: six events in 25 s. P to Q: hourly. X talks to five different peers inside 20 s (no LINK has more than one event, so
     * only the ENTITY series sees the burst). E to E: a self-loop, three events 10 s apart. One NULL time, two NULL endpoints.
     */
    private static final String VIEW = "SELECT * FROM (VALUES "
            + "('A','B',TIMESTAMP '2026-01-01 10:00:00',1),('A','B',TIMESTAMP '2026-01-01 10:00:05',1),('A','B',TIMESTAMP '2026-01-01 10:00:10',1),"
            + "('A','B',TIMESTAMP '2026-01-01 10:00:15',1),('A','B',TIMESTAMP '2026-01-01 10:00:20',1),('A','B',TIMESTAMP '2026-01-01 10:00:25',1),"
            + "('A','B',CAST(NULL AS TIMESTAMP),1),"
            + "('P','Q',TIMESTAMP '2026-01-01 08:00:00',1),('P','Q',TIMESTAMP '2026-01-01 09:00:00',1),('P','Q',TIMESTAMP '2026-01-01 10:00:00',1),"
            + "('P','Q',TIMESTAMP '2026-01-01 11:00:00',1),('P','Q',TIMESTAMP '2026-01-01 12:00:00',1),"
            + "('X','P1',TIMESTAMP '2026-01-02 10:00:00',1),('X','P2',TIMESTAMP '2026-01-02 10:00:05',1),('P3','X',TIMESTAMP '2026-01-02 10:00:10',1),"
            + "('X','P4',TIMESTAMP '2026-01-02 10:00:15',1),('P5','X',TIMESTAMP '2026-01-02 10:00:20',1),"
            + "('E','E',TIMESTAMP '2026-01-03 10:00:00',1),('E','E',TIMESTAMP '2026-01-03 10:00:10',1),('E','E',TIMESTAMP '2026-01-03 10:00:20',1),"
            + "(CAST(NULL AS VARCHAR),'A',TIMESTAMP '2026-01-01 10:00:00',1),('A',CAST(NULL AS VARCHAR),TIMESTAMP '2026-01-01 10:00:00',1)"
            + ") AS v(s,t,ts,c)";

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
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case OWNER -> Optional.of(new Subject("analyst-1", Set.of("canBuildLinkIndex", "canManageIncidents")));
            case STRANGER -> Optional.of(new Subject("analyst-3", Set.of("canBuildLinkIndex", "canManageIncidents")));
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
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("n_view", "flow-x", List.of(), VIEW, "2026-10-05T00:00:00Z"));
            // `shares` PRESENT => restricted to the owner: STRANGER cannot view it.
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "n_ds", Map.of("view", "n_view", "owner", "analyst-1", "shares", List.of()));
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

    private interface Check {
        boolean ok() throws Exception;
    }

    /** State-based wait (never a fixed sleep). */
    private static void until(Check c, String what) throws Exception {
        long end = System.nanoTime() + 60_000_000_000L;
        while (!c.ok()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(10);
        }
    }

    private void build(Ctx c, String body) throws Exception {
        String id = data(send(c, "POST", "/inv/index/builds", body, OWNER), 202).get("buildId").asText();
        until(() -> {
            String st = data(send(c, "GET", "/inv/index/builds/" + id, null, OWNER), 200).get("status").asText();
            if (st.equals("FAILED") || st.equals("CANCELLED")) throw new AssertionError("build " + st);
            return st.equals("COMPLETED");
        }, "index build " + id);
    }

    private static void settings(Ctx c, String toon) throws Exception {
        Files.writeString(c.root.resolve("link-analysis.toon"), toon);
    }

    private static final String BUILD = "{\"dataset\":\"n_ds\",\"sourceCol\":\"s\",\"targetCol\":\"t\",\"timeCol\":\"ts\"}";
    private static final String COLS = "\"sourceCol\":\"s\",\"targetCol\":\"t\",\"timeCol\":\"ts\"";
    private static final String BURST = "\"windowSeconds\":30,\"minEvents\":5";

    private static String body(String mode, String extra) {
        return "{\"dataset\":\"n_ds\"," + COLS + ",\"mode\":\"" + mode + "\"" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private JsonNode temporal(Ctx c, String body) throws Exception {
        return data(send(c, "POST", "/inv/pattern/temporal", body, OWNER), 200);
    }

    /** What must be identical whichever store answered: every finding and the bookkeeping, not the {@code source} object. */
    private static List<String> findings(JsonNode d) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : d.get("results")) out.add(r.toString());
        out.add("truncated=" + d.get("truncated") + " rowCapped=" + d.get("rowCapped") + " skipped=" + d.get("skippedNoTime") + " series=" + d.get("series"));
        return out;
    }

    /** Index answer vs flat answer; returns the index answer. */
    private JsonNode same(Ctx c, String body) throws Exception {
        settings(c, ENABLED);
        JsonNode viaIndex = temporal(c, body);
        assertEquals("index", viaIndex.at("/source/kind").asText(), body + " -> " + viaIndex.get("source"));
        settings(c, DISABLED);
        JsonNode viaFlat = temporal(c, body);
        assertEquals("index_disabled", viaFlat.at("/source/reason").asText());
        assertEquals(findings(viaFlat), findings(viaIndex), body);
        settings(c, ENABLED);
        return viaIndex;
    }

    private static List<String> entities(JsonNode d) {
        List<String> who = new ArrayList<>();
        for (JsonNode r : d.get("results")) who.add(r.get("entity").asText() + ":" + r.get("events").asInt());
        return who;
    }

    @Test
    void theIndexAndTheFlatDatasetGiveTheSameFindingsForBothSeriesAndBothModes(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, ENABLED)) {
            build(c, BUILD);
            JsonNode linkBurst = same(c, body("burst", BURST));
            assertEquals(1, linkBurst.get("results").size(), "only A>B has five events in 30 s: " + linkBurst);
            assertEquals("A", linkBurst.at("/results/0/source").asText());
            assertEquals(1, linkBurst.get("skippedNoTime").asInt(), "the NULL-time row is counted on both paths");
            assertEquals("link", linkBurst.get("series").asText());

            JsonNode entityBurst = same(c, body("burst", BURST + ",\"series\":\"entity\""));
            List<String> who = entities(entityBurst);
            assertTrue(who.contains("X:5"), "X's five contacts with five peers are one burst of the ENTITY: " + who);
            assertTrue(who.contains("A:6") && who.contains("B:6"), "an entity takes part as source and as target: " + who);
            assertFalse(entityBurst.at("/results/0").has("source"), "an entity finding names the entity, not a link");
            assertFalse(who.stream().anyMatch(w -> w.startsWith("E:")), "E's self-loop has three events, below five: " + who);

            JsonNode self = same(c, body("burst", "\"windowSeconds\":30,\"minEvents\":3,\"series\":\"entity\""));
            assertTrue(entities(self).contains("E:3"), "a self-loop row is ONE event of E, not two: " + entities(self));

            same(c, body("periodicity", ""));
            JsonNode period = same(c, body("periodicity", "\"series\":\"entity\",\"maxCv\":0.2"));
            assertTrue(entities(period).contains("P:5") && entities(period).contains("Q:5"), period.toString());

            String notA = "{\"kind\":\"group\",\"op\":\"AND\",\"items\":[{\"kind\":\"condition\",\"field\":\"s\",\"operator\":\"!=\",\"value\":\"A\"}]}";
            JsonNode filtered = same(c, body("burst", BURST + ",\"series\":\"entity\",\"filter\":" + notA));
            assertFalse(entities(filtered).stream().anyMatch(w -> w.startsWith("A:") || w.startsWith("B:")), "the filter narrows the series first: " + filtered);
            assertTrue(entities(filtered).contains("X:5"), "twin: what the filter keeps is still found");
        }
    }

    @Test
    void theDefaultSeriesIsStillPerLink(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, ENABLED)) {
            JsonNode plain = temporal(c, body("burst", BURST));
            assertEquals("link", plain.get("series").asText(), "no series given: per link, as before");
            assertTrue(plain.at("/results/0").has("source") && !plain.at("/results/0").has("entity"));
            assertEquals("dataset", plain.at("/source/kind").asText(), "no index built: the flat Dataset answered");
            assertEquals("no_index", plain.at("/source/reason").asText());
        }
    }

    @Test
    void aSeriesOutsideTheEnumIs422AndAStrangerIs404(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, ENABLED)) {
            assertEquals(200, send(c, "POST", "/inv/pattern/temporal", body("burst", "\"series\":\"entity\""), OWNER).statusCode(), "twin");
            assertEquals(422, send(c, "POST", "/inv/pattern/temporal", body("burst", "\"series\":\"pair\""), OWNER).statusCode());
            assertEquals(404, send(c, "POST", "/inv/pattern/temporal", body("burst", ""), STRANGER).statusCode(),
                    "the base-Dataset view gate runs first: a Dataset shared away is absent, index or not");
        }
    }

    @Test
    void anIndexThatCannotAnswerExactlyFallsBackWithItsReasonAndTheTwinIsServed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, ENABLED)) {
            build(c, "{\"dataset\":\"n_ds\",\"sourceCol\":\"s\",\"targetCol\":\"t\"}");                 // an index with NO time column
            JsonNode d = temporal(c, body("burst", BURST));
            assertEquals("dataset", d.at("/source/kind").asText());
            assertEquals("column_not_indexed", d.at("/source/reason").asText());
            assertEquals(1, d.get("results").size(), "the flat read still answers");
            build(c, BUILD);
            assertEquals("index", temporal(c, body("burst", BURST)).at("/source/kind").asText(), "twin: an index that holds the time column");
        }
    }
}
