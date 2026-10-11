package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.la.api.InputFingerprintCache;
import com.gamma.util.DuckDbUtil;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The supernode degree surfaced and served from the edge index, over real HTTP:
 * <ul>
 *   <li>the SEALED {@code read.hubs [{id, degree}]} reaches the Working Set ({@code contacts} beside {@code highConnectivity}),
 *       the step result and the log view (masked like every id) - replay-pure, and an older log line reads as before;</li>
 *   <li>an expand the index served FRESH counts the hub degree from the index ({@code read.hubDegreeSource: index}),
 *       and it equals the flat count ({@code flat}) rung by rung;</li>
 *   <li>a weighted Investigation ({@code eventsCol}) is served from an index whose {@code weightCol} is that column, sealing
 *       the same rows as the flat {@code SUM}; any other index keeps the flat read with its reason.</li>
 * </ul>
 * Fixture distinct contacts: {@code h} 8 (a, b, x1..x5, y1 - y1 by sms only), {@code b} 4 (a, c, z, h).
 */
class ControlApiInvestigationHubDegreeTest {
    @BeforeAll static void raiseLinkAnalysisBudget() {
        System.setProperty("control.rateLimit.linkAnalysis.capacity", "10000");
    }

    @AfterAll static void restoreLinkAnalysisBudget() {
        System.clearProperty("control.rateLimit.linkAnalysis.capacity");
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENABLED = "index:\n  enabled: true\n";
    private static final String DISABLED = "index:\n  enabled: false\n";
    private static final String ROWS = String.join(",",
            "('a','h','voice',2)", "('a','b','voice',1)", "('a','b','voice',4)", "('h','x1','voice',NULL)", "('h','x2','voice',NULL)",
            "('h','x3','voice',2)", "('h','x4','voice',1)", "('h','x5','voice',1)", "('h','a','sms',1)", "('h','y1','sms',3)",
            "('b','c','voice',3)", "('c','b','voice',1)", "('x1','h','voice',1)", "('b','z','voice',1)", "('b','h','voice',1)",
            "('a','a','voice',5)");
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
                    "SELECT caller, callee, channel, n FROM (VALUES " + ROWS + ") AS t(caller,callee,channel,n)", "2026-10-11T00:00:00Z"));
            ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
            for (String ds : List.of("w_ds", "u_ds")) store.write("dataset", ds, Map.of("view", "calls_view"));
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

    private static void settings(Ctx c, String toon) throws Exception {
        Files.writeString(c.root().resolve("link-analysis.toon"), toon);
    }

    private interface Check {
        boolean ok() throws Exception;
    }

    private static void until(Check c, String what) throws Exception {
        long end = System.nanoTime() + 60_000_000_000L;
        while (!c.ok()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(10);
        }
    }

    private void build(Ctx c, String body) throws Exception {
        String id = data(send(c, "POST", "/inv/index/builds", body), 202).get("buildId").asText();
        until(() -> {
            String st = data(send(c, "GET", "/inv/index/builds/" + id, null), 200).get("status").asText();
            if (st.equals("FAILED") || st.equals("CANCELLED")) throw new AssertionError("build " + st);
            return st.equals("COMPLETED");
        }, "index build " + id);
    }

    /** A fresh Investigation over {@code ds}, seeded with {@code seed}, {@code pre} ops applied; the id. */
    private String investigation(Ctx c, String ds, String eventsCol, String seed, List<String> pre) throws Exception {
        String id = "i" + seq.incrementAndGet();
        data(send(c, "POST", "/inv/investigations", "{\"purpose\":\"test\",\"id\":\"" + id + "\",\"dataset\":\"" + ds + "\","
                + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\""
                + (eventsCol == null ? "" : ",\"eventsCol\":\"" + eventsCol + "\"") + "}"), 200);
        op(c, id, "{\"op\":\"seed\",\"ids\":" + seed + "}");
        for (String p : pre) op(c, id, p);
        return id;
    }

    private JsonNode op(Ctx c, String id, String body) throws Exception {
        return data(send(c, "POST", "/inv/investigations/" + id + "/ops", body), 200);
    }

    private JsonNode log(Ctx c, String id) throws Exception {
        return data(send(c, "GET", "/inv/investigations/" + id + "/log", null), 200);
    }

    /** entityId -> contacts (null when unflagged), from the Working Set relation. */
    private Map<String, JsonNode> contacts(Ctx c, String id) throws Exception {
        JsonNode ws = data(send(c, "GET", "/inv/investigations/" + id + "/working-set?of=entities", null), 200);
        assertTrue(ws.get("columns").toString().contains("\"highConnectivity\",\"contacts\""), ws.get("columns").toString());
        Map<String, JsonNode> out = new TreeMap<>();
        for (JsonNode r : ws.get("rows")) {
            assertEquals(r.get("highConnectivity").asBoolean(), !r.get("contacts").isNull(), "contacts exactly on the flagged: " + r);
            out.put(r.get("entityId").asText(), r.get("contacts"));
        }
        return out;
    }

    private JsonNode replay(Ctx c, String id, boolean reread) throws Exception {
        return data(send(c, "POST", "/inv/investigations/" + id + "/replay", reread ? "{\"reread\":true}" : "{}"), 200);
    }

    // -- 1. the sealed degree, surfaced ----------------------------------------------------------------------------------

    @Test
    void theSealedHubDegreeReachesTheWorkingSetTheStepAndTheLog(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = investigation(c, "u_ds", null, "[\"a\"]", List.of());
            JsonNode step = op(c, id, "{\"op\":\"expand\",\"hubThreshold\":4}");
            assertEquals("[{\"degree\":8,\"id\":\"h\"}]", step.at("/read/hubs").toString(), step.get("read").toString());
            assertEquals("flat", step.at("/read/hubDegreeSource").asText());
            assertEquals(1, step.at("/read/hubsFlagged").asInt());

            Map<String, JsonNode> ct = contacts(c, id);
            assertEquals(8, ct.get("h").asLong(), ct.toString());
            assertTrue(ct.get("b").isNull() && ct.get("a").isNull(), "b (4) is not above 4: " + ct);

            JsonNode entry = log(c, id).at("/entries/1/read");
            assertEquals("[{\"degree\":8,\"id\":\"h\"}]", entry.get("hubs").toString(), entry.toString());
            assertEquals("flat", entry.get("hubDegreeSource").asText());
            assertFalse(entry.has("rows"), "the sealed rows still stay out of the log view");

            // replay-pure: a later threshold setting changes nothing; replay and the reread agree
            settings(c, DISABLED + "hub_threshold: 1\n");
            assertTrue(replay(c, id, true).get("equivalent").asBoolean());
            assertEquals(8, contacts(c, id).get("h").asLong());

            // an exclusion drops the entity and its contacts with it; undo brings both back from the sealed read
            op(c, id, "{\"op\":\"exclude\",\"ids\":[\"h\"],\"reason\":\"noise\"}");
            assertFalse(contacts(c, id).containsKey("h"));
            data(send(c, "POST", "/inv/investigations/" + id + "/undo", ""), 200);
            assertEquals(8, contacts(c, id).get("h").asLong());
        }
    }

    @Test
    void theHubIdsInTheLogAreMaskedLikeEveryOtherId(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            settings(c, DISABLED + "masking_mode: all\n");
            String id = investigation(c, "u_ds", null, "[\"a\"]", List.of());
            JsonNode step = op(c, id, "{\"op\":\"expand\",\"hubThreshold\":4}");
            String hub = step.at("/read/hubs/0/id").asText();
            assertTrue(hub.startsWith("masked:"), step.at("/read/hubs").toString());
            assertEquals(8, step.at("/read/hubs/0/degree").asLong());
            JsonNode entry = log(c, id).at("/entries/1/read");
            assertEquals(hub, entry.at("/hubs/0/id").asText(), "the same pseudonym in the log view");
            assertFalse(log(c, id).toString().contains("\"h\""), "the raw hub id never leaves");
            Map<String, JsonNode> ct = contacts(c, id);
            assertEquals(8, ct.get(hub).asLong(), ct.toString());
        }
    }

    @Test
    void anOlderLogLineReadsAsBeforeAndReplaysToItsRecordedHashes(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String flagged = investigation(c, "u_ds", null, "[\"a\"]", List.of());
            op(c, flagged, "{\"op\":\"expand\",\"hubThreshold\":4}");
            String plain = investigation(c, "u_ds", null, "[\"a\"]", List.of());
            op(c, plain, "{\"op\":\"expand\",\"hubThreshold\":100}");
            // rewrite each line as the release before wrote it: a supernode-era line had hubs but no hubDegreeSource,
            // a line before supernode suppression had neither
            Path f1 = root.resolve("audit/snapshots/investigations/" + flagged + "/log.jsonl");
            Files.writeString(f1, Files.readString(f1).replace("\"hubDegreeSource\":\"flat\",", ""));
            Path f2 = root.resolve("audit/snapshots/investigations/" + plain + "/log.jsonl");
            Files.writeString(f2, Files.readString(f2).replace("\"hubDegreeSource\":\"flat\",\"hubs\":[],", ""));
            assertFalse(Files.readString(f1).contains("hubDegreeSource") || Files.readString(f2).contains("\"hubs\""));

            assertTrue(replay(c, flagged, false).get("equivalent").asBoolean(), "the contacts never entered the hash");
            assertTrue(replay(c, plain, false).get("equivalent").asBoolean());
            JsonNode r1 = log(c, flagged).at("/entries/1/read");
            assertFalse(r1.has("hubDegreeSource"), r1.toString());
            assertEquals("[{\"degree\":8,\"id\":\"h\"}]", r1.get("hubs").toString());
            assertEquals(8, contacts(c, flagged).get("h").asLong(), "the degree an older line sealed is shown");
            JsonNode r2 = log(c, plain).at("/entries/1/read");
            assertFalse(r2.has("hubs") || r2.has("hubDegreeSource"), "absent field: " + r2);
            for (JsonNode v : contacts(c, plain).values()) assertTrue(v.isNull());
        }
    }

    // -- 2. the hub degree from the index --------------------------------------------------------------------------------

    /** One rung through the fresh index and through the flat Dataset: the same rows AND the same sealed hubs. */
    private JsonNode sameHubs(Ctx c, String seed, List<String> pre, String extra) throws Exception {
        settings(c, ENABLED);
        String a = investigation(c, "u_ds", null, seed, pre);
        JsonNode viaIndex = op(c, a, "{\"op\":\"expand\"" + extra + "}");
        assertEquals(1, viaIndex.at("/read/index/version").asInt(), "served: " + extra + " -> " + viaIndex.get("read"));
        assertEquals("index", viaIndex.at("/read/hubDegreeSource").asText(), extra);
        settings(c, DISABLED);
        String b = investigation(c, "u_ds", null, seed, pre);
        JsonNode viaFlat = op(c, b, "{\"op\":\"expand\"" + extra + "}");
        assertEquals("flat", viaFlat.at("/read/hubDegreeSource").asText());
        assertEquals(viaFlat.at("/read/fingerprint"), viaIndex.at("/read/fingerprint"), extra);
        assertEquals(viaFlat.at("/read/hubs"), viaIndex.at("/read/hubs"), seed + extra);
        assertEquals(contacts(c, b), contacts(c, a));
        assertEquals(viaFlat.get("workingSet"), viaIndex.get("workingSet"));
        assertTrue(replay(c, a, false).get("equivalent").asBoolean());
        return viaIndex;
    }

    @Test
    void theHubDegreeCountedFromTheIndexEqualsTheFlatCount(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            build(c, "{\"dataset\":\"u_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"kindCol\":\"channel\"}");
            JsonNode all = sameHubs(c, "[\"a\"]", List.of(), ",\"hubThreshold\":3");
            assertEquals("[{\"degree\":4,\"id\":\"b\"},{\"degree\":8,\"id\":\"h\"}]", all.at("/read/hubs").toString(), "not vacuous");
            JsonNode voice = sameHubs(c, "[\"a\"]", List.of(), ",\"hubThreshold\":3,\"linkKinds\":[\"voice\"]");
            assertEquals(7, voice.at("/read/hubs/1/degree").asLong(), "y1 (sms) no longer counts: " + voice.at("/read/hubs"));
            sameHubs(c, "[\"a\"]", List.of("{\"op\":\"exclude\",\"ids\":[\"x1\",\"z\"],\"reason\":\"noise\"}"), ",\"hubThreshold\":3");
            sameHubs(c, "[\"a\"]", List.of(), ",\"hubThreshold\":7");                                // h only, at 8
            sameHubs(c, "[\"a\"]", List.of(), ",\"hubThreshold\":8");                                // nothing: degree must EXCEED
            sameHubs(c, "[\"a\"]", List.of(), ",\"hubThreshold\":1,\"direction\":\"out\"");
            sameHubs(c, "[\"x1\",\"c\"]", List.of(), ",\"hubThreshold\":2,\"direction\":\"reciprocal\"");
            sameHubs(c, "[\"a\"]", List.of("{\"op\":\"seed\",\"ids\":[\"b\"]}"), ",\"ids\":[\"a\"],\"hubThreshold\":1");   // admitted b is never newly flagged
        }
    }

    @Test
    void aRungTheIndexDidNotServeCountsTheHubDegreeFlat(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            build(c, "{\"dataset\":\"u_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"kindCol\":\"channel\"}");
            settings(c, ENABLED);
            String id = investigation(c, "u_ds", null, "[\"a\"]", List.of());
            JsonNode bounded = op(c, id, "{\"op\":\"expand\",\"hubThreshold\":3,\"candidateDegreeMax\":100}");
            assertEquals("rung_not_indexable", bounded.at("/read/fallback/reason").asText());
            assertEquals("flat", bounded.at("/read/hubDegreeSource").asText());
            assertEquals("[{\"degree\":4,\"id\":\"b\"},{\"degree\":8,\"id\":\"h\"}]", bounded.at("/read/hubs").toString());
        }
    }

    /** A Parquet file of {@code (who, other)} rows with a pinned mtime (the clock never decides). */
    private static void parquet(Path file, long mtime, String values) throws Exception {
        Files.createDirectories(file.getParent());
        DuckDbUtil.loadDriver();
        java.io.File db = DuckDbUtil.tempDbFile("hub_stale_");
        try (java.sql.Connection conn = DuckDbUtil.openConnection(db); java.sql.Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + values + ") t(who,other)) TO '" + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(mtime));
    }

    /**
     * The KNOWN inconsistency of a stale-served rung (link-analysis.md section 4): the rows come from the older index version,
     * but the hub degree is counted flat over the CURRENT Dataset ({@code hubDegreeSource: flat}) - so a file added since the
     * build already raises the sealed degree while the rows do not yet show its links.
     */
    @Test
    void aStaleServedRungCountsTheHubDegreeFlatOverTheCurrentDataset(@TempDir Path cfg, @TempDir Path root) throws Exception {
        String store = "hub_stale_" + System.nanoTime();
        Path dir = Path.of("database").resolve(store);
        boolean hadDatabase = Files.isDirectory(Path.of("database"));
        AtomicLong now = new AtomicLong(1_000L);
        InputFingerprintCache.forTest(now::get, 30_000L);
        long t0 = 1_700_000_000_000L;
        try (Ctx c = open(cfg, root)) {
            new ComponentStore(root.resolve("registry")).write("dataset", "files_ds", Map.of("physicalRef", store));
            parquet(dir.resolve("p1.parquet"), t0, "('a','h'),('h','x1'),('h','x2'),('h','x3')");
            build(c, "{\"dataset\":\"files_ds\",\"sourceCol\":\"who\",\"targetCol\":\"other\"}");
            settings(c, ENABLED);
            String create = "{\"purpose\":\"test\",\"dataset\":\"files_ds\",\"sourceCol\":\"who\",\"targetCol\":\"other\",\"id\":\"";

            String fresh = "i" + seq.incrementAndGet();
            data(send(c, "POST", "/inv/investigations", create + fresh + "\"}"), 200);
            op(c, fresh, "{\"op\":\"seed\",\"ids\":[\"a\"]}");
            JsonNode f = op(c, fresh, "{\"op\":\"expand\",\"hubThreshold\":3}");
            assertFalse(f.at("/read/index/stale").asBoolean(), f.get("read").toString());
            assertEquals("index", f.at("/read/hubDegreeSource").asText());
            assertEquals("[{\"degree\":4,\"id\":\"h\"}]", f.at("/read/hubs").toString());

            parquet(dir.resolve("p2.parquet"), t0 + 1_000, "('h','x4'),('h','x5')");   // an addition: served stale, not refused
            now.addAndGet(31_000L);
            String stale = "i" + seq.incrementAndGet();
            data(send(c, "POST", "/inv/investigations", create + stale + "\"}"), 200);
            op(c, stale, "{\"op\":\"seed\",\"ids\":[\"a\"]}");
            JsonNode s = op(c, stale, "{\"op\":\"expand\",\"hubThreshold\":3}");
            assertEquals(1, s.at("/read/index/version").asInt(), "served from the older version: " + s.get("read"));
            assertTrue(s.at("/read/index/stale").asBoolean(), s.get("read").toString());
            assertEquals(f.at("/read/fingerprint"), s.at("/read/fingerprint"), "the rows are the older version's");
            assertEquals("flat", s.at("/read/hubDegreeSource").asText());
            assertEquals("[{\"degree\":6,\"id\":\"h\"}]", s.at("/read/hubs").toString(), "the degree is the current Dataset's");
            assertEquals(6, contacts(c, stale).get("h").asLong());
            assertTrue(replay(c, stale, false).get("equivalent").asBoolean(), "replay reads the sealed degree");
        } finally {
            InputFingerprintCache.forTest(null, 0);
            if (Files.isDirectory(dir)) try (var w = Files.walk(dir)) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
            if (!hadDatabase) Path.of("database").toFile().delete();
        }
    }

    // -- 3. the weighted expand from the index ---------------------------------------------------------------------------

    @Test
    void aWeightedExpandIsServedFromAnIndexCarryingItsEventsColumnAndEqualsTheFlatSum(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            build(c, "{\"dataset\":\"w_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"kindCol\":\"channel\",\"weightCol\":\"n\"}");
            build(c, "{\"dataset\":\"u_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"kindCol\":\"channel\"}");
            for (String extra : List.of("", ",\"minEvents\":3", ",\"maxFanOut\":1", ",\"direction\":\"in\"", ",\"linkKinds\":[\"sms\"]",
                    ",\"hubThreshold\":3", ",\"budget\":2")) {
                for (String seed : List.of("[\"a\"]", "[\"h\",\"b\"]")) {
                    settings(c, ENABLED);
                    String a = investigation(c, "w_ds", "n", seed, List.of());
                    JsonNode viaIndex = op(c, a, "{\"op\":\"expand\"" + extra + "}");
                    assertEquals(1, viaIndex.at("/read/index/version").asInt(), "weighted, served: " + extra + " " + viaIndex.get("read"));
                    settings(c, DISABLED);
                    String b = investigation(c, "w_ds", "n", seed, List.of());
                    JsonNode viaFlat = op(c, b, "{\"op\":\"expand\"" + extra + "}");
                    assertEquals(viaFlat.at("/read/fingerprint"), viaIndex.at("/read/fingerprint"), "SUM parity: " + seed + extra);
                    assertEquals(viaFlat.at("/read/fanOutCapped"), viaIndex.at("/read/fanOutCapped"));
                    assertEquals(viaFlat.at("/read/hubs"), viaIndex.at("/read/hubs"));
                    assertEquals(viaFlat.get("truncated"), viaIndex.get("truncated"));
                    settings(c, ENABLED);
                    assertTrue(replay(c, a, true).get("equivalent").asBoolean(), "the index-sealed weighted read rereads the same");
                }
            }
            // the sealed counts really are the SUM (a→b: 1 + 4; a NULL weight is one row's worth)
            settings(c, ENABLED);
            String s = investigation(c, "w_ds", "n", "[\"h\"]", List.of());
            op(c, s, "{\"op\":\"expand\"}");
            JsonNode rows = JSON.readTree(Files.readAllLines(root.resolve("audit/snapshots/investigations/" + s + "/log.jsonl")).get(1)).at("/read/rows");
            Map<String, Long> got = new TreeMap<>();
            for (JsonNode r : rows) got.put(r.get("source").asText() + ">" + r.get("target").asText() + "|" + r.get("kind").asText(), r.get("count").asLong());
            assertEquals(1L, got.get("h>x1|voice"));
            assertEquals(2L, got.get("h>x3|voice"));
            assertEquals(3L, got.get("h>y1|sms"));

            // an index without that weightCol keeps the flat read, saying why; the unweighted twin over it is served
            String u = investigation(c, "u_ds", "n", "[\"a\"]", List.of());
            JsonNode fell = op(c, u, "{\"op\":\"expand\"}");
            assertEquals("rung_not_indexable", fell.at("/read/fallback/reason").asText(), fell.get("read").toString());
            assertTrue(fell.at("/read/fallback/details").asText().contains("weightCol"), fell.get("read").toString());
            String t = investigation(c, "u_ds", null, "[\"a\"]", List.of());
            assertEquals(1, op(c, t, "{\"op\":\"expand\"}").at("/read/index/version").asInt());
        }
    }
}
