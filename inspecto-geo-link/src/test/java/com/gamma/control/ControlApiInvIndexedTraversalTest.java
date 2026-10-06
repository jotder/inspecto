package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.la.api.InputFingerprintCache;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.util.DuckDbUtil;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-3 step 5 - {@code POST /inv/traversal/recursive-paths} answered FROM THE INDEX, over real HTTP with an ARMED
 * Authenticator throughout (with no Subject, {@code withCapability} is a no-op and the gates would pass ungated).
 *
 * <p>The proof is EQUIVALENCE: the same request answers the same path set from the index and from the flat Dataset
 * ({@code index.enabled} off) when no fence fires, on a corpus planted at test time (parallel edges, a self-loop, cycles,
 * multi-kind, NULL kind, NULL time, weights, a NULL-endpoint row that both paths drop). Each negative case has a positive twin
 * in the same test: a probe that WOULD be served from the index but for the one thing being tested.
 */
class ControlApiInvIndexedTraversalTest {
    /** Each test compares the indexed and flat paths call by call, well past the Link Analysis bucket's
     *  burst; the operator-tunable capacity is raised for this class only. */
    @BeforeAll static void raiseLinkAnalysisBudget() {
        System.setProperty("control.rateLimit.linkAnalysis.capacity", "10000");
    }

    @AfterAll static void restoreLinkAnalysisBudget() {
        System.clearProperty("control.rateLimit.linkAnalysis.capacity");
    }


    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OWNER = "Bearer owner", STRANGER = "Bearer stranger";
    private static final String ENABLED = "index:\n  enabled: true\n";
    private static final String DISABLED = "index:\n  enabled: false\n";
    private final HttpClient client = HttpClient.newHttpClient();

    /** The planted corpus; DST day in America/New_York is 2026-03-08. */
    private static final String G_VIEW = "SELECT * FROM (VALUES "
            + "('A','B','call',TIMESTAMP '2026-03-08 01:00:00',10.0,'x'),"
            + "('A','B','call',TIMESTAMP '2026-03-08 09:00:00',20.0,'x'),"          // parallel edge
            + "('A','B','sms',TIMESTAMP '2026-03-09 09:00:00',CAST(NULL AS DOUBLE),'y'),"
            + "('A','B',CAST(NULL AS VARCHAR),TIMESTAMP '2026-03-09 10:00:00',2.5,'y')," // NULL kind
            + "('A','C','call',CAST(NULL AS TIMESTAMP),5.0,'x'),"                    // NULL time
            + "('C','A','call',TIMESTAMP '2026-03-10 09:00:00',6.0,'z'),"
            + "('B','B','call',TIMESTAMP '2026-03-10 10:00:00',1.0,'x'),"            // self-loop
            + "('B','C','call',TIMESTAMP '2026-03-09 11:00:00',3.0,'x'),"
            + "('B','D','sms',TIMESTAMP '2026-03-08 03:00:00',4.0,'y'),"
            + "('C','D','call',TIMESTAMP '2026-03-12 09:00:00',7.0,'x'),"
            + "('D','A',CAST(NULL AS VARCHAR),TIMESTAMP '2026-03-13 09:00:00',CAST(NULL AS DOUBLE),'z'),"
            + "('D','E','call',TIMESTAMP '2026-03-14 09:00:00',8.0,'x'),"
            + "(CAST(NULL AS VARCHAR),'A','call',TIMESTAMP '2026-03-14 09:00:00',1.0,'x'),"   // dropped by both paths
            + "('A',CAST(NULL AS VARCHAR),'call',TIMESTAMP '2026-03-14 09:00:00',1.0,'x')"
            + ") AS v(s,t,kind,ts,w,c)";

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
            case OWNER -> Optional.of(new Subject("analyst-1", Set.of("canBuildLinkIndex")));
            case STRANGER -> Optional.of(new Subject("analyst-3", Set.of("canBuildLinkIndex")));
            default -> Optional.empty();
        });
    }

    /** {@code hub_ds}: H has 25 children and G has 20, each child with one child of its own (a frontier of 25 / 20 keys at depth 2). */
    private static String hubView() {
        List<String> rows = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            rows.add(String.format("('H','h%02d')", i));
            rows.add(String.format("('h%02d','hh%02d')", i, i));
        }
        for (int i = 1; i <= 20; i++) {
            rows.add(String.format("('G','g%02d')", i));
            rows.add(String.format("('g%02d','gg%02d')", i, i));
        }
        return "SELECT * FROM (VALUES " + String.join(",", rows) + ") AS v(s,t)";
    }

    private Ctx open(Path configDir, Path writeRoot, String settings) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            Files.writeString(writeRoot.resolve("link-analysis.toon"), settings);
            ViewStore views = new ViewStore(writeRoot.resolve("views"));
            views.write(new ViewDefinition("g_view", "flow-x", List.of(), G_VIEW, "2026-10-02T00:00:00Z"));
            views.write(new ViewDefinition("hub_view", "flow-x", List.of(), hubView(), "2026-10-02T00:00:00Z"));
            ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
            // `shares` PRESENT => restricted to the owner: STRANGER cannot view any of them.
            for (String ds : List.of("g_ds", "g_ny", "hub_ds"))
                store.write("dataset", ds, Map.of("view", ds.equals("hub_ds") ? "hub_view" : "g_view", "owner", "analyst-1", "shares", List.of()));
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

    /** State-based wait (never a fixed sleep): a slow runner only makes the loop longer. */
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

    private static final String G_MAPPING = "\"sourceCol\":\"s\",\"targetCol\":\"t\",\"kindCol\":\"kind\",\"timeCol\":\"ts\",\"weightCol\":\"w\",\"attrCols\":[\"c\"]";

    private void buildCorpusIndexes(Ctx c) throws Exception {
        build(c, "{\"dataset\":\"g_ds\"," + G_MAPPING + "}");
        build(c, "{\"dataset\":\"g_ny\"," + G_MAPPING + ",\"timeColZone\":\"America/New_York\"}");
        build(c, "{\"dataset\":\"hub_ds\",\"sourceCol\":\"s\",\"targetCol\":\"t\"}");
    }

    private static void settings(Ctx c, String toon) throws Exception {
        Files.writeString(c.root.resolve("link-analysis.toon"), toon);
    }

    private JsonNode traverse(Ctx c, String body) throws Exception {
        return data(send(c, "POST", "/inv/traversal/recursive-paths", body, OWNER), 200);
    }

    private static String req(String dataset, String start, String extra) {
        return "{\"dataset\":\"" + dataset + "\",\"sourceCol\":\"s\",\"targetCol\":\"t\",\"startNode\":\"" + start + "\""
                + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    /** Paths as a sorted list of {@code nodes|hops|weight} so ordering cannot hide or fake a difference. */
    private static List<String> normalised(JsonNode data) {
        List<String> out = new ArrayList<>();
        for (JsonNode p : data.get("paths")) {
            List<String> nodes = new ArrayList<>();
            p.get("nodes").forEach(n -> nodes.add(n.asText()));
            out.add(String.join(">", nodes) + "|" + p.get("hops").asInt() + "|" + (p.get("weight").isNull() ? "-" : p.get("weight").asText()));
        }
        out.sort(null);
        return out;
    }

    /** Runs {@code body} through the index (enabled) and through the flat Dataset (disabled) and compares. Returns the index answer. */
    private JsonNode equivalent(Ctx c, String body) throws Exception {
        settings(c, ENABLED);
        JsonNode viaIndex = traverse(c, body);
        assertEquals("index", viaIndex.at("/source/kind").asText(), "served from the index: " + body + " -> " + viaIndex.get("source"));
        settings(c, DISABLED);
        JsonNode viaFlat = traverse(c, body);
        assertEquals("dataset", viaFlat.at("/source/kind").asText());
        assertEquals("index_disabled", viaFlat.at("/source/reason").asText());
        assertEquals(normalised(viaFlat), normalised(viaIndex), "the same path set, " + body);
        assertEquals(viaFlat.get("edgeYieldCapped").asBoolean(), viaIndex.get("edgeYieldCapped").asBoolean(), body);
        assertEquals(viaFlat.get("truncated").asBoolean(), viaIndex.get("truncated").asBoolean(), body);
        assertEquals(viaFlat.get("fences"), viaIndex.get("fences"), "fences keep their meaning");
        settings(c, ENABLED);
        assertFalse(viaIndex.get("paths").isEmpty() && !body.contains("nobody"), "the probe finds paths, so equality is not vacuous: " + body);
        return viaIndex;
    }

    // -- (1) equivalence -------------------------------------------------------------------------------------------------

    @Test
    void theSameRequestAnswersTheSamePathSetFromTheIndexAndFromTheFlatDataset(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, ENABLED)) {
            buildCorpusIndexes(c);
            String d2 = "\"maxDepth\":2";
            equivalent(c, req("g_ds", "A", d2));                                                   // directed, parallel edges, cycle, self-loop
            equivalent(c, req("g_ds", "A", "\"maxDepth\":1"));
            equivalent(c, req("g_ds", "B", d2));                                                   // starts on a self-loop
            equivalent(c, req("g_ds", "C", d2));                                                   // a cycle C-A-C is refused
            equivalent(c, req("g_ds", "A", d2 + ",\"direction\":\"UNDIRECTED\""));
            equivalent(c, req("g_ds", "D", d2 + ",\"direction\":\"UNDIRECTED\""));
            equivalent(c, req("g_ds", "A", d2 + ",\"weightCol\":\"w\""));                          // weights incl. NULL (counted as 0)
            equivalent(c, req("g_ds", "A", d2 + ",\"targetNode\":\"D\",\"weightCol\":\"w\""));
            equivalent(c, req("g_ds", "A", d2 + ",\"weightCol\":\"w\",\"temporalConstraint\":{\"timestampCol\":\"ts\"}"));            // NULL time not walked
            equivalent(c, req("g_ds", "A", d2 + ",\"temporalConstraint\":{\"timestampCol\":\"ts\",\"monotonic\":true}"));            // B->D (03:00) precedes A->B edges
            equivalent(c, req("g_ds", "A", d2 + ",\"temporalConstraint\":{\"timestampCol\":\"ts\",\"maxTotalDurationHours\":30}"));
            equivalent(c, req("g_ds", "A", d2 + ",\"direction\":\"UNDIRECTED\",\"weightCol\":\"w\",\"temporalConstraint\":{\"timestampCol\":\"ts\",\"monotonic\":true,\"maxTotalDurationHours\":48}"));
            equivalent(c, req("g_ds", "A", d2 + ",\"temporalConstraint\":{\"timestampCol\":\"ts\",\"monotonic\":true,\"maxGapHours\":3}"));      // A-B 01:00 then B-D 03:00 is a 2 h gap
            equivalent(c, req("g_ds", "A", d2 + ",\"direction\":\"UNDIRECTED\",\"temporalConstraint\":{\"timestampCol\":\"ts\",\"monotonic\":true,\"maxGapHours\":30,\"maxTotalDurationHours\":48}"));
            String kindCall ="{\"kind\":\"group\",\"op\":\"AND\",\"items\":[{\"kind\":\"condition\",\"field\":\"kind\",\"operator\":\"=\",\"value\":\"call\"}]}";
            equivalent(c, req("g_ds", "A", d2 + ",\"filter\":" + kindCall));                       // a filter on an indexed (kind) column
            String attrOrKind = "{\"kind\":\"group\",\"op\":\"OR\",\"items\":[{\"kind\":\"condition\",\"field\":\"c\",\"operator\":\"=\",\"value\":\"y\"},"
                    + "{\"kind\":\"condition\",\"field\":\"s\",\"operator\":\"=\",\"value\":\"C\"},{\"kind\":\"condition\",\"field\":\"kind\",\"operator\":\"isNull\"}]}";
            equivalent(c, req("g_ds", "A", d2 + ",\"filter\":" + attrOrKind));                     // attr, source, and a NULL-kind test
            // an index with a NON-UTC zone serves a request that does not use time (the zone cannot matter)
            equivalent(c, req("g_ny", "A", d2 + ",\"weightCol\":\"w\""));
            equivalent(c, req("hub_ds", "G", d2));                                                 // a frontier of exactly the cap (20)
        }
    }

    @Test
    void whenTheYieldFenceFiresBothPathsReportItAndOnlyTheFlagIsComparable(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, ENABLED)) {
            buildCorpusIndexes(c);
            String body = req("hub_ds", "H", "\"maxDepth\":1,\"maxEdgeYield\":3");
            JsonNode viaIndex = traverse(c, body);
            assertEquals("index", viaIndex.at("/source/kind").asText());
            settings(c, DISABLED);
            JsonNode viaFlat = traverse(c, body);
            assertTrue(viaIndex.get("edgeYieldCapped").asBoolean());
            assertTrue(viaFlat.get("edgeYieldCapped").asBoolean());
            assertTrue(viaIndex.get("truncated").asBoolean());
            assertEquals(3, viaIndex.get("paths").size(), "never more than the yield");
            assertEquals(3, viaFlat.get("paths").size());
            // the twin: the same request with room to spare fires no fence on either path
            settings(c, ENABLED);
            JsonNode roomy = traverse(c, req("hub_ds", "H", "\"maxDepth\":1,\"maxEdgeYield\":100"));
            assertFalse(roomy.get("edgeYieldCapped").asBoolean());
            assertEquals(25, roomy.get("paths").size());
        }
    }

    // -- (2) the negative paths, each with a probe that would be served from the index ----------------------------------

    @Test
    void indexDisabledIsTheFlatPathWithItsReasonAndTheIndexAnswersOnceEnabled(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, DISABLED)) {
            buildCorpusIndexes(c);                                                                 // an index exists, but index.enabled is false (the default)
            String body = req("g_ds", "A", "\"maxDepth\":2");
            JsonNode off = traverse(c, body);
            assertEquals("dataset", off.at("/source/kind").asText());
            assertEquals("index_disabled", off.at("/source/reason").asText());
            assertEquals(List.of("paths", "truncated", "edgeYieldCapped", "fences", "source"), fieldNames(off), "today's body plus source, nothing else");
            settings(c, ENABLED);
            JsonNode on = traverse(c, body);
            assertEquals("index", on.at("/source/kind").asText());
            assertFalse(on.at("/source/stale").asBoolean());
            assertEquals(1, on.at("/source/version").asInt());
            assertEquals(normalised(off), normalised(on));
        }
    }

    private static List<String> fieldNames(JsonNode n) {
        List<String> out = new ArrayList<>();
        n.fieldNames().forEachRemaining(out::add);
        return out;
    }

    @Test
    void noIndexOrAMappingTheIndexLacksFallsBackWithItsReason(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, ENABLED)) {
            String body = req("g_ds", "A", "\"maxDepth\":2");
            assertEquals("no_index", traverse(c, body).at("/source/reason").asText());              // nothing built yet
            build(c, "{\"dataset\":\"g_ds\"," + G_MAPPING + "}");
            assertEquals("index", traverse(c, body).at("/source/kind").asText(), "the twin: once built it is served");
            // the same Dataset walked over other columns is a different graph: no index for that mapping
            String other = "{\"dataset\":\"g_ds\",\"sourceCol\":\"t\",\"targetCol\":\"s\",\"startNode\":\"A\",\"maxDepth\":2}";
            assertEquals("mapping_not_indexed", traverse(c, other).at("/source/reason").asText());
        }
    }

    @Test
    void aColumnOrFilterTheIndexDoesNotHoldFallsBackAndTheFlatPathStillValidatesIt(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, ENABLED)) {
            build(c, "{\"dataset\":\"g_ds\",\"sourceCol\":\"s\",\"targetCol\":\"t\",\"kindCol\":\"kind\"}");   // no weight, no time, no attrs
            String d2 = "\"maxDepth\":2";
            assertEquals("index", traverse(c, req("g_ds", "A", d2)).at("/source/kind").asText(), "the probe is servable");
            assertEquals("column_not_indexed", traverse(c, req("g_ds", "A", d2 + ",\"weightCol\":\"w\"")).at("/source/reason").asText());
            assertEquals("column_not_indexed",
                    traverse(c, req("g_ds", "A", d2 + ",\"temporalConstraint\":{\"timestampCol\":\"ts\"}")).at("/source/reason").asText());
            String onKind = "{\"kind\":\"group\",\"items\":[{\"kind\":\"condition\",\"field\":\"kind\",\"operator\":\"=\",\"value\":\"call\"}]}";
            assertEquals("index", traverse(c, req("g_ds", "A", d2 + ",\"filter\":" + onKind)).at("/source/kind").asText(), "a filter on an indexed column is served");
            String onUnindexed = "{\"kind\":\"group\",\"items\":[{\"kind\":\"condition\",\"field\":\"c\",\"operator\":\"=\",\"value\":\"x\"}]}";
            JsonNode fell = traverse(c, req("g_ds", "A", d2 + ",\"filter\":" + onUnindexed));
            assertEquals("filter_not_indexed", fell.at("/source/reason").asText());
            assertTrue(fell.get("paths").size() > 0, "and the flat path answered it");
            // a column that does not exist is still the flat path's 422, not a silent index answer
            String bogus = "{\"kind\":\"group\",\"items\":[{\"kind\":\"condition\",\"field\":\"nope\",\"operator\":\"=\",\"value\":\"x\"}]}";
            assertEquals(422, send(c, "POST", "/inv/traversal/recursive-paths", req("g_ds", "A", d2 + ",\"filter\":" + bogus), OWNER).statusCode());
        }
    }

    @Test
    void aTemporalConstraintOverATimeColumnReadInAnotherZoneIsNotServable(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, ENABLED)) {
            build(c, "{\"dataset\":\"g_ny\"," + G_MAPPING + ",\"timeColZone\":\"America/New_York\"}");
            String temporal = req("g_ny", "A", "\"maxDepth\":2,\"temporalConstraint\":{\"timestampCol\":\"ts\",\"monotonic\":true}");
            JsonNode fell = traverse(c, temporal);
            assertEquals("time_zone_not_servable", fell.at("/source/reason").asText());
            assertTrue(fell.get("paths").size() > 0);
            assertEquals("index", traverse(c, req("g_ny", "A", "\"maxDepth\":2")).at("/source/kind").asText(), "the twin: no time use, same index, served");
        }
    }

    @Test
    void depthOverTheCapFallsBackAndDepthAtTheCapIsServed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, ENABLED)) {
            buildCorpusIndexes(c);
            assertEquals("index", traverse(c, req("g_ds", "A", "\"maxDepth\":2")).at("/source/kind").asText());
            JsonNode deep = traverse(c, req("g_ds", "A", "\"maxDepth\":3"));
            assertEquals("depth_over_index_cap", deep.at("/source/reason").asText());
            assertTrue(deep.get("paths").toString().contains("\"A\",\"B\",\"C\",\"D\""), "the flat path walked the third level: " + deep.get("paths"));
            assertEquals("depth_over_index_cap", traverse(c, req("g_ds", "A", "")).at("/source/reason").asText(), "the default depth (6) is over the cap");
        }
    }

    @Test
    void aFrontierOverTheCapIsFoundMidWalkAndTheWholeAnswerComesFromTheFlatPath(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, ENABLED)) {
            buildCorpusIndexes(c);
            assertEquals("index", traverse(c, req("hub_ds", "H", "\"maxDepth\":1")).at("/source/kind").asText(), "25 keys at depth 1 is one lookup: served");
            assertEquals("index", traverse(c, req("hub_ds", "G", "\"maxDepth\":2")).at("/source/kind").asText(), "20 keys is the cap: served");
            JsonNode over = traverse(c, req("hub_ds", "H", "\"maxDepth\":2"));
            assertEquals("dataset", over.at("/source/kind").asText());
            assertEquals("frontier_over_index_cap", over.at("/source/reason").asText());
            List<String> paths = normalised(over);
            assertEquals(50, paths.size(), "complete: 25 one-hop and 25 two-hop paths, none dropped with the discarded index walk");
            assertTrue(paths.contains("H>h25>hh25|2|-"), paths.toString());
            settings(c, DISABLED);
            assertEquals(paths, normalised(traverse(c, req("hub_ds", "H", "\"maxDepth\":2"))), "identical to the flat answer");
        }
    }

    @Test
    void aDatasetWhoseDefinitionChangedIsRefusedNotServedStale(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, ENABLED)) {
            buildCorpusIndexes(c);
            String body = req("g_ds", "A", "\"maxDepth\":2");
            JsonNode before = traverse(c, body);
            assertEquals("index", before.at("/source/kind").asText());
            // the Dataset's view now hides a row: the index would still show it, so it must not answer
            new ViewStore(root.resolve("views")).write(new ViewDefinition("g_view", "flow-x", List.of(),
                    G_VIEW + " WHERE NOT (s = 'D' AND t = 'E')", "2026-10-02T00:00:00Z"));
            JsonNode after = traverse(c, body);
            assertEquals("dataset", after.at("/source/kind").asText());
            assertEquals("index_stale_refused", after.at("/source/reason").asText());
            // restored: the same index serves again
            new ViewStore(root.resolve("views")).write(new ViewDefinition("g_view", "flow-x", List.of(), G_VIEW, "2026-10-02T00:00:00Z"));
            assertEquals("index", traverse(c, body).at("/source/kind").asText());
        }
    }

    @Test
    void aDatasetSharedAwayIsTheSame404EvenThoughAnIndexExists(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, ENABLED)) {
            buildCorpusIndexes(c);
            String body = req("g_ds", "A", "\"maxDepth\":2");
            assertEquals("index", data(send(c, "POST", "/inv/traversal/recursive-paths", body, OWNER), 200).at("/source/kind").asText(), "the owner is served");
            HttpResponse<String> stranger = send(c, "POST", "/inv/traversal/recursive-paths", body, STRANGER);
            assertEquals(404, stranger.statusCode(), stranger.body());
            HttpResponse<String> absent = send(c, "POST", "/inv/traversal/recursive-paths", req("no_such_ds", "A", "\"maxDepth\":2"), STRANGER);
            assertEquals(404, absent.statusCode());
            assertEquals(JSON.readTree(absent.body()).findValue("message").asText().replace("no_such_ds", "g_ds"),
                    JSON.readTree(stranger.body()).findValue("message").asText(), "indistinguishable from absence");
        }
    }

    @Test
    void fourEyesStillRefusesASensitiveReadOnTheIndexPath(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, ENABLED)) {
            buildCorpusIndexes(c);
            settings(c, ENABLED + "four_eyes_budget_above: 100\n");
            // depth 2 x yield 10 000 = 20 000 rows > 100: refused, exactly as the flat path refuses it
            HttpResponse<String> r = send(c, "POST", "/inv/traversal/recursive-paths", req("g_ds", "A", "\"maxDepth\":2"), OWNER);
            assertEquals(403, r.statusCode(), r.body());
            // the twin: a request inside the threshold (2 x 40 = 80) is served from the index
            assertEquals("index", traverse(c, req("g_ds", "A", "\"maxDepth\":2,\"maxEdgeYield\":40")).at("/source/kind").asText());
        }
    }

    @Test
    void theAuditEventNamesWhereTheAnswerCameFrom(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, ENABLED)) {
            buildCorpusIndexes(c);
            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            EventLog.current().addSubscriber(sub);
            try {
                traverse(c, req("g_ds", "A", "\"maxDepth\":2"));
                traverse(c, req("g_ds", "A", "\"maxDepth\":3"));
            } finally {
                EventLog.current().removeSubscriber(sub);
            }
            List<Event> traversed = seen.stream().filter(x -> LinkEventTypes.LINK_TRAVERSED.equals(x.type())).toList();
            assertEquals(2, traversed.size(), seen.toString());
            assertEquals("index", traversed.get(0).attributes().get("source"));
            assertEquals("1", traversed.get(0).attributes().get("indexVersion"));
            assertEquals("false", traversed.get(0).attributes().get("indexStale"));
            assertEquals("dataset", traversed.get(1).attributes().get("source"));
            assertEquals("depth_over_index_cap", traversed.get(1).attributes().get("sourceReason"));
            assertNull(traversed.get(1).attributes().get("indexVersion"));
        }
    }

    // -- (3) staleness grounded in the Dataset's input files (D-3 design 5.3a / 5.4): ONE definition with GET /inv/index ---------

    private static final long T0 = 1_700_000_000_000L;
    private static final String FILES_BODY = "{\"dataset\":\"files_ds\",\"sourceCol\":\"who\",\"targetCol\":\"other\"}";
    private static final String FILES_REQ = "{\"dataset\":\"files_ds\",\"sourceCol\":\"who\",\"targetCol\":\"other\",\"startNode\":\"alice\",\"maxDepth\":2}";

    /** A Parquet file of {@code (who, other)} rows at {@code file} with a pinned mtime (no sleeps: the clock never decides). */
    private static void parquet(Path file, long mtime, String values) throws Exception {
        Files.createDirectories(file.getParent());
        DuckDbUtil.loadDriver();
        java.io.File db = DuckDbUtil.tempDbFile("idx_trav_");
        try (java.sql.Connection conn = DuckDbUtil.openConnection(db); java.sql.Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + values + ") t(who,other)) TO '" + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(mtime));
    }

    private static void touch(Path file, long mtime) throws Exception {
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(mtime));
    }

    private interface FilesBody {
        void run(Ctx c, Path dir, AtomicLong now) throws Exception;
    }

    /** A file-backed Dataset {@code files_ds} (one Parquet file) under a legacy Space's relative {@code database/} root, and a fake fingerprint clock. */
    private void withFiles(Path cfg, Path root, FilesBody body) throws Exception {
        subjects();
        String store = "idx_trav_" + System.nanoTime();
        Path dir = Path.of("database").resolve(store);
        boolean hadDatabase = Files.isDirectory(Path.of("database"));
        AtomicLong now = new AtomicLong(1_000L);
        InputFingerprintCache.forTest(now::get, 30_000L);
        try (Ctx c = open(cfg, root, ENABLED)) {
            new ComponentStore(root.resolve("registry")).write("dataset", "files_ds",
                    Map.of("physicalRef", store, "owner", "analyst-1", "shares", List.of()));
            parquet(dir.resolve("p1.parquet"), T0, "('alice','bob'),('bob','carol'),('alice','carol')");
            build(c, FILES_BODY);
            body.run(c, dir, now);
        } finally {
            InputFingerprintCache.forTest(null, 0);
            if (Files.isDirectory(dir)) try (var w = Files.walk(dir)) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
            if (!hadDatabase) Path.of("database").toFile().delete();
        }
    }

    /** Lets the cached fingerprint expire, so the next request lists the files again. */
    private static void expire(AtomicLong now) {
        now.addAndGet(31_000L);
    }

    @Test
    void anAddedFileIsServedFromTheIndexFlaggedStaleAndTheFlatPathHasMore(@TempDir Path cfg, @TempDir Path root) throws Exception {
        withFiles(cfg, root, (c, dir, now) -> {
            JsonNode fresh = traverse(c, FILES_REQ);
            assertEquals("index", fresh.at("/source/kind").asText());
            assertFalse(fresh.at("/source/stale").asBoolean(), String.valueOf(fresh.get("source")));
            assertEquals("known", fresh.at("/source/fingerprint").asText());

            parquet(dir.resolve("p2.parquet"), T0 + 1_000, "('carol','dave')");                     // an ADDITION: removes nothing
            expire(now);
            JsonNode added = traverse(c, FILES_REQ);
            assertEquals("index", added.at("/source/kind").asText(), "only additions: served, not refused: " + added.get("source"));
            assertTrue(added.at("/source/stale").asBoolean());
            assertTrue(added.at("/source/staleReason").asText().contains("input_files_changed: 1 files added since the build"),
                    added.at("/source/staleReason").asText());
            assertEquals("known", added.at("/source/fingerprint").asText());
            // the documented difference: the index misses the new rows, so the flag is honest
            settings(c, DISABLED);
            JsonNode flat = traverse(c, FILES_REQ);
            assertEquals(normalised(fresh), normalised(added), "the index still answers as of the build");
            assertEquals(normalised(added).size() + 1, normalised(flat).size(), "the flat Dataset finds the path through the new file");
            assertTrue(normalised(flat).contains("alice>carol>dave|2|-"), normalised(flat).toString());
            assertFalse(normalised(added).contains("alice>carol>dave|2|-"));
        });
    }

    @Test
    void aTouchedOrDeletedFileRefusesTheIndexAndTheFlatAnswerIsComplete(@TempDir Path cfg, @TempDir Path root) throws Exception {
        withFiles(cfg, root, (c, dir, now) -> {
            Path p1 = dir.resolve("p1.parquet");
            List<String> built = normalised(traverse(c, FILES_REQ));
            touch(p1, T0 + 5_000);                                                                    // TOUCHED: may have lost rows
            expire(now);
            JsonNode touched = traverse(c, FILES_REQ);
            assertEquals("dataset", touched.at("/source/kind").asText());
            assertEquals("index_stale_refused", touched.at("/source/reason").asText());
            assertTrue(touched.at("/source/details").asText().contains("input files changed"), touched.get("source").toString());
            assertEquals(built, normalised(touched), "the flat answer is complete");

            touch(p1, T0);                                                                            // the twin: restored, served again
            expire(now);
            assertEquals("index", traverse(c, FILES_REQ).at("/source/kind").asText());

            parquet(dir.resolve("p2.parquet"), T0 + 1_000, "('alice','zed')");                        // keeps the Dataset non-empty
            Files.delete(p1);                                                                         // DELETED
            expire(now);
            JsonNode gone = traverse(c, FILES_REQ);
            assertEquals("dataset", gone.at("/source/kind").asText());
            assertEquals("index_stale_refused", gone.at("/source/reason").asText());
            assertEquals(List.of("alice>zed|1|-"), normalised(gone), "the flat Dataset no longer has p1's rows: the index must not show them");
        });
    }

    @Test
    void aDatasetWithNoEnumerableFilesIsServedWithAnUnknownFingerprint(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        InputFingerprintCache.forTest(null, 0);
        try (Ctx c = open(cfg, root, ENABLED)) {
            buildCorpusIndexes(c);                                                                    // view-backed: nothing to list
            JsonNode r = traverse(c, req("g_ds", "A", "\"maxDepth\":2"));
            assertEquals("index", r.at("/source/kind").asText());
            assertFalse(r.at("/source/stale").asBoolean());
            assertEquals("unknown", r.at("/source/fingerprint").asText(), "currency is not knowable, and the response says so");
        }
    }

    @Test
    void theFingerprintIsCachedForTheTtlAndABuildCompletionInvalidatesIt(@TempDir Path cfg, @TempDir Path root) throws Exception {
        withFiles(cfg, root, (c, dir, now) -> {
            long start = InputFingerprintCache.loads();
            traverse(c, FILES_REQ);
            assertEquals(start + 1, InputFingerprintCache.loads(), "the first request lists the files");
            traverse(c, FILES_REQ);
            traverse(c, FILES_REQ);
            assertEquals(start + 1, InputFingerprintCache.loads(), "within the TTL nothing is listed again");

            parquet(dir.resolve("p2.parquet"), T0 + 1_000, "('carol','dave')");
            assertFalse(traverse(c, FILES_REQ).at("/source/stale").asBoolean(), "cached: the addition is not seen yet (stated trade-off)");
            now.addAndGet(29_999L);
            assertEquals(start + 1, InputFingerprintCache.loads());
            now.addAndGet(1L);                                                                        // the TTL is up
            assertTrue(traverse(c, FILES_REQ).at("/source/stale").asBoolean(), "expired: listed again, the addition is seen");
            assertEquals(start + 2, InputFingerprintCache.loads());

            build(c, FILES_BODY);                                                                     // a build completes: its entries are dropped
            // the completion callback may land a moment after the status flips: wait on the state (a fresh listing), not a sleep
            until(() -> {
                traverse(c, FILES_REQ);
                return InputFingerprintCache.loads() >= start + 3;
            }, "the build completion to invalidate the cached fingerprint");
            assertEquals(start + 3, InputFingerprintCache.loads(), "invalidated by the completion, not by the clock");
            assertFalse(traverse(c, FILES_REQ).at("/source/stale").asBoolean(), "the new version covers both files");
        });
    }
}
