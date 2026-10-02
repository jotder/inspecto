package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.la.core.LinkEventTypes;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-3 step 6 - {@code POST /inv/projection/neighbors} and the Investigation {@code expand} read answered FROM THE INDEX, over
 * real HTTP with an ARMED Authenticator throughout (with no Subject, {@code withCapability} is a no-op).
 *
 * <p>The proof is EQUIVALENCE on a corpus planted at test time (parallel edges, a self-loop, a reciprocal pair, NULL kinds,
 * NULL endpoints that both paths drop, a hub with distinct per-child counts): the same request through the index
 * ({@code index.enabled} on) and through the flat Dataset (off). A neighbours read is compared as a SET (the flat statement has
 * no tie-break beyond source and target) and, on the hub, in order; an expand is compared by its sealed {@code fingerprint}, which is
 * {@code sha256(canonical(rows))} and so also proves the order. Each negative case has a positive twin served from the index.
 */
class ControlApiInvIndexedExpansionTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OWNER = "Bearer owner", STRANGER = "Bearer stranger";
    private static final String ENABLED = "index:\n  enabled: true\n";
    private static final String DISABLED = "index:\n  enabled: false\n";
    private final HttpClient client = HttpClient.newHttpClient();
    private final AtomicInteger seq = new AtomicInteger();

    private static final String BASE_ROWS = String.join(",",
            "('A','B','call',TIMESTAMP '2026-03-01 09:00:00','x')", "('A','B','call',TIMESTAMP '2026-03-01 10:00:00','x')",
            "('A','B','call',TIMESTAMP '2026-03-02 10:00:00','y')", "('A','B','sms',TIMESTAMP '2026-03-02 11:00:00','y')",
            "('A','B',CAST(NULL AS VARCHAR),TIMESTAMP '2026-03-03 11:00:00','y')",       // NULL kind
            "('B','A','call',TIMESTAMP '2026-03-04 09:00:00','x')", "('B','A','call',TIMESTAMP '2026-03-05 09:00:00','x')",
            "('B','B','call',TIMESTAMP '2026-03-04 10:00:00','x')", "('B','B','call',TIMESTAMP '2026-03-04 11:00:00','z')",   // self-loop
            "('A','C','call',TIMESTAMP '2026-03-01 09:00:00','x')", "('C','A','call',TIMESTAMP '2026-03-06 09:00:00','z')",
            "('C','D','call',TIMESTAMP '2026-03-01 09:00:00','x')", "('C','D','call',TIMESTAMP '2026-03-02 09:00:00','x')",
            "('C','D','call',TIMESTAMP '2026-03-03 09:00:00','x')", "('C','D','call',TIMESTAMP '2026-03-04 09:00:00','x')",
            "('D','C','sms',TIMESTAMP '2026-03-01 09:00:00','y')", "('D','E','call',TIMESTAMP '2026-03-01 09:00:00','x')",
            "('E','F','call',TIMESTAMP '2026-03-01 09:00:00','x')", "('X','A','call',TIMESTAMP '2026-03-01 09:00:00','x')",
            "('X','B','call',TIMESTAMP '2026-03-01 09:00:00','x')",
            "(CAST(NULL AS VARCHAR),'A','call',TIMESTAMP '2026-03-01 09:00:00','x')",     // dropped by both paths
            "('A',CAST(NULL AS VARCHAR),'call',TIMESTAMP '2026-03-01 09:00:00','x')");

    /** The base rows plus {@code H}: child {@code hNN} has NN parallel edges (distinct counts 1..25), each child one child of its own. */
    private static String view() {
        List<String> rows = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            for (int k = 0; k < i; k++) rows.add(String.format("('H','h%02d','call',TIMESTAMP '2026-03-01 09:00:00','x')", i));
            rows.add(String.format("('h%02d','hh%02d','call',TIMESTAMP '2026-03-01 09:00:00','x')", i, i));
        }
        return "SELECT * FROM (VALUES " + BASE_ROWS + "," + String.join(",", rows) + ") AS v(s,t,kind,ts,c)";
    }

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
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("n_view", "flow-x", List.of(), view(), "2026-10-03T00:00:00Z"));
            ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
            // `shares` PRESENT => restricted to the owner: STRANGER cannot view any of them.
            for (String ds : List.of("n_ds", "n_min"))
                store.write("dataset", ds, Map.of("view", "n_view", "owner", "analyst-1", "shares", List.of()));
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

    private static final String FULL = "{\"dataset\":\"n_ds\",\"sourceCol\":\"s\",\"targetCol\":\"t\",\"kindCol\":\"kind\",\"timeCol\":\"ts\",\"attrCols\":[\"c\"]}";
    private static final String MIN = "{\"dataset\":\"n_min\",\"sourceCol\":\"s\",\"targetCol\":\"t\",\"kindCol\":\"kind\"}";

    private static void settings(Ctx c, String toon) throws Exception {
        Files.writeString(c.root.resolve("link-analysis.toon"), toon);
    }

    // -- neighbours ------------------------------------------------------------------------------------------------------

    private static String nbody(String dataset, String value, String extra) {
        return "{\"dataset\":\"" + dataset + "\",\"sourceCol\":\"s\",\"targetCol\":\"t\",\"value\":\"" + value + "\"" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private JsonNode neighbors(Ctx c, String body) throws Exception {
        return data(send(c, "POST", "/inv/projection/neighbors", body, OWNER), 200);
    }

    private static List<String> rows(JsonNode d) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : d.get("rows"))
            out.add(r.get("source").asText() + ">" + r.get("target").asText() + "|" + r.get("kind").asText() + "|" + r.get("count").asLong()
                    + "|" + (r.has("attrs") ? r.get("attrs").toString() : "-"));
        return out;
    }

    private static List<String> sorted(List<String> l) {
        List<String> c = new ArrayList<>(l);
        c.sort(null);
        return c;
    }

    /** Index answer vs flat answer for one neighbours request; returns the index answer. */
    private JsonNode sameNeighbors(Ctx c, String body, boolean ordered) throws Exception {
        settings(c, ENABLED);
        JsonNode viaIndex = neighbors(c, body);
        assertEquals("index", viaIndex.at("/source/kind").asText(), body + " -> " + viaIndex.get("source"));
        settings(c, DISABLED);
        JsonNode viaFlat = neighbors(c, body);
        assertEquals("index_disabled", viaFlat.at("/source/reason").asText());
        assertEquals(ordered ? rows(viaFlat) : sorted(rows(viaFlat)), ordered ? rows(viaIndex) : sorted(rows(viaIndex)), body);
        assertEquals(viaFlat.get("truncated").asBoolean(), viaIndex.get("truncated").asBoolean(), body);
        assertEquals(viaFlat.get("columnTypes"), viaIndex.get("columnTypes"));
        settings(c, ENABLED);
        return viaIndex;
    }

    @Test
    void aNeighboursReadAnswersTheSameRowsFromTheIndexAndFromTheFlatDataset(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, ENABLED)) {
            build(c, FULL);
            String kindAttr = "\"linkKindCol\":\"kind\",\"attrCols\":[\"c\"]";
            for (String v : List.of("A", "B", "C", "D", "E", "X", "F")) {
                assertFalse(sameNeighbors(c, nbody("n_ds", v, kindAttr), false).get("rows").isEmpty(), "not vacuous: " + v);
                sameNeighbors(c, nbody("n_ds", v, "\"linkKindCol\":\"kind\""), false);          // folds over c: fewer, bigger groups
                sameNeighbors(c, nbody("n_ds", v, ""), false);                                  // folds over kind too
            }
            JsonNode b = sameNeighbors(c, nbody("n_ds", "B", kindAttr), false);                 // parallel edges, NULL kind, a self-loop
            List<String> rows = rows(b);
            assertTrue(rows.contains("B>B|call|1|{\"c\":\"x\"}") && rows.contains("B>B|call|1|{\"c\":\"z\"}"), "the self-loop is counted once, not twice: " + rows);
            assertTrue(rows.contains("A>B|call|2|{\"c\":\"x\"}"), rows.toString());
            assertTrue(rows.stream().anyMatch(r -> r.startsWith("A>B|null|1|")), "the NULL-kind row folds on its own: " + rows);
            String callOnly = "{\"kind\":\"group\",\"op\":\"AND\",\"items\":[{\"kind\":\"condition\",\"field\":\"kind\",\"operator\":\"=\",\"value\":\"call\"}]}";
            sameNeighbors(c, nbody("n_ds", "A", kindAttr + ",\"filter\":" + callOnly), false);
            String orAttr = "{\"kind\":\"group\",\"op\":\"OR\",\"items\":[{\"kind\":\"condition\",\"field\":\"c\",\"operator\":\"=\",\"value\":\"y\"},"
                    + "{\"kind\":\"condition\",\"field\":\"kind\",\"operator\":\"isNull\"}]}";
            sameNeighbors(c, nbody("n_ds", "A", kindAttr + ",\"filter\":" + orAttr), false);
            // a value nobody has: an empty neighbourhood, still answered by the index
            assertTrue(sameNeighbors(c, nbody("n_ds", "nobody", kindAttr), false).get("rows").isEmpty());
        }
    }

    @Test
    void aHubIsCutAtTheLimitInTheFlatOrderAndSaysTruncated(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, ENABLED)) {
            build(c, FULL);
            JsonNode cut = sameNeighbors(c, nbody("n_ds", "H", "\"linkKindCol\":\"kind\",\"limit\":10"), true);   // ordered: the per-child counts are all distinct
            assertTrue(cut.get("truncated").asBoolean());
            assertEquals(10, cut.get("rows").size());
            assertEquals("h25", cut.at("/rows/0/target").asText(), "heaviest first");
            assertEquals(25, cut.at("/rows/0/count").asLong());
            // the twin: room for all 25 children and no truncation
            JsonNode all = sameNeighbors(c, nbody("n_ds", "H", "\"linkKindCol\":\"kind\",\"limit\":100"), true);
            assertFalse(all.get("truncated").asBoolean());
            assertEquals(25, all.get("rows").size());
        }
    }

    @Test
    void aNeighboursReadFallsBackWithItsReasonAndTheTwinIsServed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, DISABLED)) {
            build(c, FULL);
            build(c, MIN);
            String body = nbody("n_ds", "A", "\"linkKindCol\":\"kind\"");
            JsonNode off = neighbors(c, body);
            assertEquals("index_disabled", off.at("/source/reason").asText(), "an index exists but index.enabled is off (the default)");
            assertEquals(List.of("rows", "truncated", "columnTypes", "source"), fieldNames(off), "today's body plus source, nothing else");
            settings(c, ENABLED);
            assertEquals("index", neighbors(c, body).at("/source/kind").asText(), "the twin: enabled");

            // an attribute / filter the index does not hold: the n_min index has no `c`
            assertEquals("index", neighbors(c, nbody("n_min", "A", "\"linkKindCol\":\"kind\"")).at("/source/kind").asText());
            assertEquals("column_not_indexed", neighbors(c, nbody("n_min", "A", "\"attrCols\":[\"c\"]")).at("/source/reason").asText());
            String onC = "{\"kind\":\"group\",\"items\":[{\"kind\":\"condition\",\"field\":\"c\",\"operator\":\"=\",\"value\":\"x\"}]}";
            JsonNode fell = neighbors(c, nbody("n_min", "A", "\"filter\":" + onC));
            assertEquals("filter_not_indexed", fell.at("/source/reason").asText());
            assertFalse(fell.get("rows").isEmpty(), "and the flat path answered it");
            // a kind column the index was not built with
            assertEquals("column_not_indexed", neighbors(c, nbody("n_ds", "A", "\"linkKindCol\":\"c\"")).at("/source/reason").asText());
            // an unknown column is still the flat path's 422
            String bogus = "{\"kind\":\"group\",\"items\":[{\"kind\":\"condition\",\"field\":\"nope\",\"operator\":\"=\",\"value\":\"x\"}]}";
            assertEquals(422, send(c, "POST", "/inv/projection/neighbors", nbody("n_ds", "A", "\"filter\":" + bogus), OWNER).statusCode());

            // no index at all for a mapping
            assertEquals("mapping_not_indexed", neighbors(c, "{\"dataset\":\"n_ds\",\"sourceCol\":\"t\",\"targetCol\":\"s\",\"value\":\"A\"}").at("/source/reason").asText());

            // a Dataset whose definition changed is refused, not served stale; restored, it serves again
            new ViewStore(root.resolve("views")).write(new ViewDefinition("n_view", "flow-x", List.of(), view() + " WHERE NOT (s = 'E')", "2026-10-03T00:00:00Z"));
            assertEquals("index_stale_refused", neighbors(c, body).at("/source/reason").asText());
            new ViewStore(root.resolve("views")).write(new ViewDefinition("n_view", "flow-x", List.of(), view(), "2026-10-03T00:00:00Z"));
            assertEquals("index", neighbors(c, body).at("/source/kind").asText());
        }
    }

    private static List<String> fieldNames(JsonNode n) {
        List<String> out = new ArrayList<>();
        n.fieldNames().forEachRemaining(out::add);
        return out;
    }

    @Test
    void aNeighboursReadKeepsItsGatesAndAuditsWhichStoreAnswered(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, ENABLED)) {
            build(c, FULL);
            String body = nbody("n_ds", "A", "\"linkKindCol\":\"kind\"");
            assertEquals("index", neighbors(c, body).at("/source/kind").asText(), "the owner is served");
            HttpResponse<String> stranger = send(c, "POST", "/inv/projection/neighbors", body, STRANGER);
            assertEquals(404, stranger.statusCode(), stranger.body());   // shared away: the same 404 as absence, whether or not an index exists

            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            EventLog.current().addSubscriber(sub);
            try {
                neighbors(c, body);
                settings(c, DISABLED);
                neighbors(c, body);
            } finally {
                EventLog.current().removeSubscriber(sub);
            }
            List<Event> expanded = seen.stream().filter(x -> LinkEventTypes.LINK_EXPANDED.equals(x.type())).toList();
            assertEquals(2, expanded.size(), seen.toString());
            assertEquals("index", expanded.get(0).attributes().get("source"));
            assertEquals("1", expanded.get(0).attributes().get("indexVersion"));
            assertEquals("false", expanded.get(0).attributes().get("indexStale"));
            assertEquals("dataset", expanded.get(1).attributes().get("source"));
            assertEquals("index_disabled", expanded.get(1).attributes().get("sourceReason"));
            assertNull(expanded.get(1).attributes().get("indexVersion"));

            // four-eyes: a read above the threshold is refused before anything is read; a read inside it is served
            settings(c, ENABLED + "four_eyes_budget_above: 50\n");
            assertEquals(403, send(c, "POST", "/inv/projection/neighbors", nbody("n_ds", "A", "\"limit\":100"), OWNER).statusCode());
            assertEquals("index", neighbors(c, nbody("n_ds", "A", "\"limit\":40")).at("/source/kind").asText());
        }
    }

    // -- Investigation expand --------------------------------------------------------------------------------------------

    private JsonNode post(Ctx c, String path, String body) throws Exception {
        return data(send(c, "POST", path, body, OWNER), 200);
    }

    /** A fresh Investigation (own id), seeded, with {@code pre} ops applied, then one expand; returns the expand step. */
    private JsonNode expand(Ctx c, String seed, List<String> pre, String expandExtra, String investigationExtra) throws Exception {
        String id = "e" + seq.incrementAndGet();
        post(c, "/inv/investigations", "{\"purpose\":\"test\",\"id\":\"" + id + "\",\"dataset\":\"n_ds\",\"sourceCol\":\"s\",\"targetCol\":\"t\","
                + "\"linkKindCol\":\"kind\"" + investigationExtra + "}");
        post(c, "/inv/investigations/" + id + "/ops", "{\"op\":\"seed\",\"ids\":" + seed + "}");
        for (String p : pre) post(c, "/inv/investigations/" + id + "/ops", p);
        lastId = id;
        return post(c, "/inv/investigations/" + id + "/ops", "{\"op\":\"expand\"" + (expandExtra.isEmpty() ? "" : "," + expandExtra) + "}");
    }

    private String lastId;

    /** The replay answer's {@code data} (the envelope's timestamp and correlation id differ per call by design). */
    private String replayData(Ctx c, String id) throws Exception {
        return JSON.readTree(send(c, "POST", "/inv/investigations/" + id + "/replay", "{}", OWNER).body()).get("data").toString();
    }

    /** The same rung through the index and through the flat Dataset must seal the same rows: fingerprint (order included), count, cap. */
    private JsonNode sameExpand(Ctx c, String seed, List<String> pre, String extra) throws Exception {
        settings(c, ENABLED);
        JsonNode viaIndex = expand(c, seed, pre, extra, "");
        assertEquals(1, viaIndex.at("/read/index/version").asInt(), "served from the index: " + extra + " " + seed + " -> " + viaIndex.get("read"));
        settings(c, DISABLED);
        JsonNode viaFlat = expand(c, seed, pre, extra, "");
        assertTrue(viaFlat.get("read").path("index").isMissingNode(), "the flat read says nothing about an index: " + viaFlat.get("read"));
        assertEquals(viaFlat.at("/read/fingerprint").asText(), viaIndex.at("/read/fingerprint").asText(), extra + " " + seed);
        assertEquals(viaFlat.at("/read/rowCount").asInt(), viaIndex.at("/read/rowCount").asInt());
        assertEquals(viaFlat.at("/read/fanOutCapped").asLong(), viaIndex.at("/read/fanOutCapped").asLong());
        assertEquals(viaFlat.get("truncated").asBoolean(), viaIndex.get("truncated").asBoolean());
        assertEquals(viaFlat.at("/delta"), viaIndex.at("/delta"));
        settings(c, ENABLED);
        return viaIndex;
    }

    @Test
    void anExpandSealsTheSameRowsFromTheIndexAndFromTheFlatDataset(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, ENABLED)) {
            build(c, FULL);
            String a = "[\"A\"]", ab = "[\"A\",\"B\"]", abc = "[\"A\",\"B\",\"C\"]";
            assertTrue(sameExpand(c, a, List.of(), "").at("/read/rowCount").asInt() > 0, "not vacuous");
            sameExpand(c, ab, List.of(), "");
            sameExpand(c, abc, List.of(), "");
            for (String dir : List.of("out", "in", "either", "reciprocal")) {
                sameExpand(c, ab, List.of(), "\"direction\":\"" + dir + "\"");
                sameExpand(c, abc, List.of(), "\"direction\":\"" + dir + "\",\"minEvents\":2");
            }
            sameExpand(c, ab, List.of(), "\"linkKinds\":[\"call\"]");                                          // NULL and sms kinds drop out
            sameExpand(c, ab, List.of(), "\"linkKinds\":[\"sms\",\"call\"],\"minEvents\":2");
            sameExpand(c, abc, List.of(), "\"minEvents\":3");
            sameExpand(c, "[\"C\"]", List.of(), "\"minEvents\":5");                                           // nothing passes: an empty read, still index-served
            JsonNode capped = sameExpand(c, ab, List.of(), "\"maxFanOut\":1");
            assertTrue(capped.at("/read/fanOutCapped").asLong() > 0, "the fan-out cap fired, so the comparison is not vacuous");
            sameExpand(c, abc, List.of(), "\"maxFanOut\":2,\"direction\":\"out\"");
            assertTrue(sameExpand(c, ab, List.of(), "\"budget\":2").get("truncated").asBoolean(), "the budget fired");
            // an excluded entity neither spends the budget nor takes part: prune, then expand
            sameExpand(c, abc, List.of("{\"op\":\"exclude\",\"ids\":[\"C\"],\"reason\":\"noise\"}"), "");
            sameExpand(c, "[\"X\",\"B\"]", List.of("{\"op\":\"exclude\",\"ids\":[\"B\"],\"reason\":\"noise\"}"), "\"direction\":\"reciprocal\"");
            sameExpand(c, "[\"H\"]", List.of(), "\"maxFanOut\":7");                                           // the hub, ranked per anchor
            // exactly the frontier cap (20) is served
            List<String> twenty = new ArrayList<>();
            for (int i = 1; i <= 20; i++) twenty.add(String.format("\"h%02d\"", i));
            sameExpand(c, "[" + String.join(",", twenty) + "]", List.of(), "");
        }
    }

    @Test
    void aRungTheIndexCannotAnswerExactlyKeepsTheFlatCteAndATwinIsServed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, ENABLED)) {
            build(c, FULL);
            String timed = ",\"timeCol\":\"ts\",\"timeColZone\":\"UTC\"";
            assertFalse(expand(c, "[\"A\"]", List.of(), "", timed).at("/read/index").isMissingNode(), "the twin: a simple rung over the same Investigation is served");
            // a window, minDistinctDays, a candidate degree bound: the flat CTE (no index recorded)
            JsonNode windowed = expand(c, "[\"A\"]", List.of(), "\"window\":{\"from\":\"2026-03-01T00:00:00Z\",\"to\":\"2026-03-03T00:00:00Z\"}", timed);
            assertTrue(windowed.at("/read/index").isMissingNode(), "windowed: " + windowed.get("read"));
            assertTrue(windowed.at("/read/rowCount").asInt() > 0);
            assertTrue(expand(c, "[\"A\"]", List.of(), "\"minDistinctDays\":2", timed).at("/read/index").isMissingNode());
            assertTrue(expand(c, "[\"A\"]", List.of(), "\"candidateDegreeMin\":2", "").at("/read/index").isMissingNode());
            assertTrue(expand(c, "[\"A\"]", List.of(), "\"candidateDegreeMax\":5", "").at("/read/index").isMissingNode());
            // a frontier of 21 is over the cap; 20 is the twin (above)
            List<String> ids = new ArrayList<>();
            for (int i = 1; i <= 21; i++) ids.add(String.format("\"h%02d\"", i));
            JsonNode over = expand(c, "[" + String.join(",", ids) + "]", List.of(), "", "");
            assertTrue(over.at("/read/index").isMissingNode(), "frontier 21: " + over.get("read"));
            assertTrue(over.at("/read/rowCount").asInt() > 0, "and the flat CTE answered it");
        }
    }

    @Test
    void anExpandOverAStaleOrMissingOrDisabledIndexUsesTheFlatReadWithTodaysShape(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, ENABLED)) {
            // no index yet
            JsonNode none = expand(c, "[\"A\"]", List.of(), "", "");
            assertTrue(none.at("/read/index").isMissingNode());
            build(c, FULL);
            assertFalse(expand(c, "[\"A\"]", List.of(), "", "").at("/read/index").isMissingNode(), "the twin: built");
            // the Dataset's definition changed: refused (flat read, no index recorded)
            new ViewStore(root.resolve("views")).write(new ViewDefinition("n_view", "flow-x", List.of(), view() + " WHERE NOT (s = 'E')", "2026-10-03T00:00:00Z"));
            assertTrue(expand(c, "[\"A\"]", List.of(), "", "").at("/read/index").isMissingNode());
            new ViewStore(root.resolve("views")).write(new ViewDefinition("n_view", "flow-x", List.of(), view(), "2026-10-03T00:00:00Z"));
            assertFalse(expand(c, "[\"A\"]", List.of(), "", "").at("/read/index").isMissingNode(), "restored: served again");
            // disabled: exactly today's step shape
            settings(c, DISABLED);
            JsonNode off = expand(c, "[\"A\"]", List.of(), "", "");
            assertEquals(List.of("rowCount", "fingerprint", "readAt", "fanOutCapped", "rung"), fieldNames(off.get("read")));
        }
    }

    @Test
    void replayIsByteIdenticalAfterANewIndexVersionAndAnIndexMoveIsReportedApartFromDrift(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, ENABLED)) {
            build(c, FULL);
            JsonNode step = expand(c, "[\"A\",\"B\"]", List.of(), "", "");
            assertEquals(1, step.at("/read/index/version").asInt());
            String id = lastId;
            // the sealed log line carries read.index beside - never inside - the fingerprint
            JsonNode log = data(send(c, "GET", "/inv/investigations/" + id + "/log", null, OWNER), 200);
            assertEquals(2, log.at("/entries").size(), log.toString());
            String plain = replayData(c, id);
            String reread1 = send(c, "POST", "/inv/investigations/" + id + "/replay", "{\"reread\":true}", OWNER).body();
            JsonNode r1 = JSON.readTree(reread1).get("data");
            assertFalse(r1.get("diverged").asBoolean(), reread1);
            assertEquals(1, r1.at("/drift/0/indexVersionSealed").asInt());
            assertEquals(1, r1.at("/drift/0/indexVersionNow").asInt());

            build(c, FULL);                                                                           // v2 now exists
            assertEquals(plain, replayData(c, id), "a plain replay never touches the index");
            JsonNode r2 = data(send(c, "POST", "/inv/investigations/" + id + "/replay", "{\"reread\":true}", OWNER), 200);
            assertFalse(r2.get("diverged").asBoolean(), "the same rows: the move is not drift");
            assertFalse(r2.at("/drift/0/diverged").asBoolean());
            assertEquals(1, r2.at("/drift/0/indexVersionSealed").asInt());
            assertEquals(2, r2.at("/drift/0/indexVersionNow").asInt(), "the index moved, and the reread says so");
            assertEquals(r2.at("/drift/0/sealedFingerprint").asText(), r2.at("/drift/0/currentFingerprint").asText());

            // index off now: the reread is answered by the Dataset, no version now, still no drift
            settings(c, DISABLED);
            JsonNode r3 = data(send(c, "POST", "/inv/investigations/" + id + "/replay", "{\"reread\":true}", OWNER), 200);
            assertFalse(r3.get("diverged").asBoolean());
            assertEquals(1, r3.at("/drift/0/indexVersionSealed").asInt());
            assertTrue(r3.at("/drift/0/indexVersionNow").isNull());

            // an Investigation sealed with the index OFF reports no index fields at all (today's drift row)
            JsonNode flat = expand(c, "[\"A\"]", List.of(), "", "");
            assertTrue(flat.at("/read/index").isMissingNode());
            JsonNode r4 = data(send(c, "POST", "/inv/investigations/" + lastId + "/replay", "{\"reread\":true}", OWNER), 200);
            assertFalse(r4.at("/drift/0").has("indexVersionSealed"));
            assertFalse(r4.get("diverged").asBoolean());
        }
    }

    @Test
    void theSteppedAuditEventNamesTheIndexOnlyWhenItAnswered(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, ENABLED)) {
            build(c, FULL);
            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            EventLog.current().addSubscriber(sub);
            try {
                expand(c, "[\"A\"]", List.of(), "", "");
                settings(c, DISABLED);
                expand(c, "[\"A\"]", List.of(), "", "");
            } finally {
                EventLog.current().removeSubscriber(sub);
            }
            List<Event> expands = seen.stream().filter(x -> LinkEventTypes.LINK_INVESTIGATION_STEPPED.equals(x.type())
                    && "expand".equals(x.attributes().get("op"))).toList();
            assertEquals(2, expands.size(), seen.toString());
            assertEquals("index", expands.get(0).attributes().get("source"));
            assertEquals("1", expands.get(0).attributes().get("indexVersion"));
            assertEquals("false", expands.get(0).attributes().get("indexStale"));
            assertNull(expands.get(1).attributes().get("source"));
            assertEquals(expands.get(0).attributes().get("fingerprint"), expands.get(1).attributes().get("fingerprint"));
        }
    }
}
