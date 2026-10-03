package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.la.api.InputFingerprintCache;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import com.gamma.util.DuckDbUtil;
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
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-3 step 8 - incremental append, compaction and the staleness probe's advice, over REAL HTTP with an ARMED Authenticator throughout
 * (with no Subject {@code withCapability} is a no-op and every gate would pass ungated). The Dataset is a plain {@code physicalRef}
 * store of parquet files under the legacy relative data root {@code database/}, planted at test time with pinned mtimes, so the
 * input fingerprint - and therefore "only new files" - is the real one.
 *
 * <p>The proof is EQUIVALENCE: after append, append and compact, a neighbours read and an Investigation expand answer the same rows
 * from the index as from the flat Dataset ({@code index.enabled} off), each tagged with the version that answered.
 */
class ControlApiIndexAppendTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OWNER = "Bearer owner", NOCAP = "Bearer nocap";
    private static final String ENABLED = "index:\n  enabled: true\n";
    private static final String DISABLED = "index:\n  enabled: false\n";
    private static final String BUILD = "{\"dataset\":\"f_ds\",\"sourceCol\":\"s\",\"targetCol\":\"t\",\"kindCol\":\"kind\",\"timeCol\":\"ts\",\"attrCols\":[\"c\"]";
    private final HttpClient client = HttpClient.newHttpClient();
    private final AtomicInteger seq = new AtomicInteger();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
        InputFingerprintCache.forTest(null, 0);
    }

    private static void subjects() {
        // the test moves files between requests: a clock that jumps past the TTL on every read means the fingerprint is always re-taken
        AtomicLong tick = new AtomicLong();
        InputFingerprintCache.forTest(() -> tick.addAndGet(60_000L), 30_000L);
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case OWNER -> Optional.of(new Subject("analyst-1", Set.of("canBuildLinkIndex", "canManageIncidents")));
            case NOCAP -> Optional.of(new Subject("analyst-1", Set.of()));
            default -> Optional.empty();
        });
    }

    private Ctx open(Path configDir, Path writeRoot, String store) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            Files.writeString(writeRoot.resolve("link-analysis.toon"), ENABLED);
            ComponentStore reg = new ComponentStore(writeRoot.resolve("registry"));
            reg.write("dataset", "f_ds", Map.of("physicalRef", store, "owner", "analyst-1", "shares", List.of()));
            // a view-backed Dataset: no files to list, so it can never be appended to
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("v_view", "flow-x", List.of(),
                    "SELECT * FROM (VALUES ('A','B','call',TIMESTAMP '2026-03-01 00:00:00','x')) AS v(s,t,kind,ts,c)", "2026-10-03T00:00:00Z"));
            reg.write("dataset", "v_ds", Map.of("view", "v_view", "owner", "analyst-1", "shares", List.of()));
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

    private static void until(Check c, String what) throws Exception {
        long end = System.nanoTime() + 60_000_000_000L;
        while (!c.ok()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(10);
        }
    }

    /** Starts a build with the given mode (null = the default, full) and waits for COMPLETED; returns the finished build. */
    private JsonNode build(Ctx c, String dataset, String mode) throws Exception {
        String body = BUILD.replace("f_ds", dataset) + (mode == null ? "" : ",\"mode\":\"" + mode + "\"") + "}";
        JsonNode started = data(send(c, "POST", "/inv/index/builds", body, OWNER), 202);
        String id = started.get("buildId").asText();
        assertEquals(mode == null ? "full" : mode, started.get("mode").asText());
        until(() -> {
            String st = data(send(c, "GET", "/inv/index/builds/" + id, null, OWNER), 200).get("status").asText();
            if (st.equals("FAILED") || st.equals("CANCELLED")) throw new AssertionError("build " + st);
            return st.equals("COMPLETED");
        }, "index build " + id);
        return data(send(c, "GET", "/inv/index/builds/" + id, null, OWNER), 200);
    }

    private HttpResponse<String> tryBuild(Ctx c, String dataset, String mode, String auth) throws Exception {
        return send(c, "POST", "/inv/index/builds", BUILD.replace("f_ds", dataset) + ",\"mode\":\"" + mode + "\"}", auth);
    }

    private JsonNode indexOf(Ctx c, String dataset) throws Exception {
        for (JsonNode ix : data(send(c, "GET", "/inv/index", null, OWNER), 200).get("indexes"))
            if (dataset.equals(ix.get("dataset").asText())) return ix;
        throw new AssertionError("no index of " + dataset);
    }

    private static void plant(Path file, String values, long mtime) throws Exception {
        Files.createDirectories(file.getParent());
        DuckDbUtil.loadDriver();
        java.io.File db = DuckDbUtil.tempDbFile("idx_append_");
        try (java.sql.Connection conn = DuckDbUtil.openConnection(db); java.sql.Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + values + ") AS v(s,t,kind,ts,c)) TO '" + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        Files.setLastModifiedTime(file, FileTime.fromMillis(mtime));
    }

    private static final String P1 = "('A','B','call',TIMESTAMP '2026-03-01 09:00:00','x'),('A','B','sms',TIMESTAMP '2026-03-02 09:00:00','y'),"
            + "('B','C','call',TIMESTAMP '2026-03-03 09:00:00','x'),('B','B','call',CAST(NULL AS TIMESTAMP),'z')";
    private static final String P2 = "('A','D','call',TIMESTAMP '2026-03-04 09:00:00','x'),('D','A',CAST(NULL AS VARCHAR),TIMESTAMP '2026-03-05 09:00:00','y'),"
            + "('C','D','call',TIMESTAMP '2026-03-05 10:00:00','x')";
    private static final String P3 = "('C','A','call',TIMESTAMP '2026-03-06 09:00:00','z'),('A','B','call',TIMESTAMP '2026-03-07 09:00:00','x'),"
            + "('E','A','sms',TIMESTAMP '2026-03-08 09:00:00','y')";

    // -- neighbours / expand through the index vs through the flat Dataset ----------------------------------------------------------

    private JsonNode neighbors(Ctx c, String value, String extra) throws Exception {
        return data(send(c, "POST", "/inv/projection/neighbors", "{\"dataset\":\"f_ds\",\"sourceCol\":\"s\",\"targetCol\":\"t\",\"value\":\"" + value + "\""
                + (extra.isEmpty() ? "" : "," + extra) + "}", OWNER), 200);
    }

    private static List<String> rows(JsonNode d) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : d.get("rows"))
            out.add(r.get("source").asText() + ">" + r.get("target").asText() + "|" + r.get("kind").asText() + "|" + r.get("count").asLong()
                    + "|" + (r.has("attrs") ? r.get("attrs").toString() : "-"));
        out.sort(null);
        return out;
    }

    private static void settings(Ctx c, String toon) throws Exception {
        Files.writeString(c.root.resolve("link-analysis.toon"), toon);
    }

    /** Every probed node answers the same rows from the index (version {@code version}, not stale) as from the flat Dataset; returns the row count compared. */
    private int sameNeighbours(Ctx c, long version) throws Exception {
        int compared = 0;
        for (String node : List.of("A", "B", "C", "D", "E", "nobody")) {
            for (String extra : List.of("", "\"linkKindCol\":\"kind\"", "\"attrCols\":[\"c\"]", "\"direction\":\"in\"")) {
                settings(c, ENABLED);
                JsonNode viaIndex = neighbors(c, node, extra);
                assertEquals("index", viaIndex.at("/source/kind").asText(), node + " " + extra + " -> " + viaIndex.get("source"));
                assertEquals(version, viaIndex.at("/source/version").asLong());
                assertFalse(viaIndex.at("/source/stale").asBoolean(), String.valueOf(viaIndex.get("source")));
                settings(c, DISABLED);
                JsonNode viaFlat = neighbors(c, node, extra);
                assertEquals(rows(viaFlat), rows(viaIndex), node + " " + extra);
                compared += rows(viaFlat).size();
            }
        }
        settings(c, ENABLED);
        return compared;
    }

    private JsonNode post(Ctx c, String path, String body) throws Exception {
        return data(send(c, "POST", path, body, OWNER), 200);
    }

    private String lastId;

    private JsonNode expand(Ctx c, String seed) throws Exception {
        String id = "x" + seq.incrementAndGet();
        post(c, "/inv/investigations", "{\"purpose\":\"test\",\"id\":\"" + id + "\",\"dataset\":\"f_ds\",\"sourceCol\":\"s\",\"targetCol\":\"t\",\"linkKindCol\":\"kind\"}");
        post(c, "/inv/investigations/" + id + "/ops", "{\"op\":\"seed\",\"ids\":" + seed + "}");
        lastId = id;
        return post(c, "/inv/investigations/" + id + "/ops", "{\"op\":\"expand\"}");
    }

    private void sameExpand(Ctx c, String seed, long version) throws Exception {
        settings(c, ENABLED);
        JsonNode viaIndex = expand(c, seed);
        assertEquals(version, viaIndex.at("/read/index/version").asLong(), seed + " -> " + viaIndex.get("read"));
        settings(c, DISABLED);
        JsonNode viaFlat = expand(c, seed);
        assertEquals(viaFlat.at("/read/fingerprint").asText(), viaIndex.at("/read/fingerprint").asText(), seed);
        assertEquals(viaFlat.at("/read/rowCount").asInt(), viaIndex.at("/read/rowCount").asInt());
        settings(c, ENABLED);
    }

    private static void rmTree(Path dir) {
        if (Files.isDirectory(dir)) try (var w = Files.walk(dir)) {
            w.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        } catch (java.io.IOException ignored) {
            // best effort
        }
    }

    // -- tests -----------------------------------------------------------------------------------------------------------------

    @Test
    void newFilesAreAppendedOnRequestAndEveryReadStaysEqualToTheFlatDatasetThroughAppendAndCompaction(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        String store = "idx_app_" + System.nanoTime();
        Path dir = Path.of("database").resolve(store);
        boolean hadDatabase = Files.isDirectory(Path.of("database"));
        try (Ctx c = open(cfg, root, store)) {
            plant(dir.resolve("p1.parquet"), P1, 1_700_000_000_000L);
            JsonNode v1 = build(c, "f_ds", null);
            assertEquals(1, v1.at("/result/version").asInt());
            assertEquals("full", v1.at("/result/builder").asText());
            JsonNode fresh = indexOf(c, "f_ds");
            assertFalse(fresh.get("stale").asBoolean());
            assertEquals("none", fresh.at("/plan/recommended").asText());
            assertEquals(0, fresh.get("deltas").asInt());
            sameNeighbours(c, 1);

            // a file arrives: the probe REPORTS it and recommends append - and builds nothing by itself
            plant(dir.resolve("p2.parquet"), P2, 1_700_000_001_000L);
            JsonNode probe = indexOf(c, "f_ds");
            assertTrue(probe.get("stale").asBoolean());
            assertEquals("append", probe.at("/plan/recommended").asText());
            assertTrue(probe.at("/plan/appendable").asBoolean());
            assertEquals(1, probe.at("/plan/added").asInt());
            assertTrue(probe.at("/plan/addedSample/0").asText().endsWith("/p2.parquet"), probe.toString());
            assertEquals(0, probe.at("/plan/removed").asInt());
            assertEquals(0, probe.at("/plan/changed").asInt());
            assertEquals(1, probe.get("version").asInt(), "looking at the plan never builds");
            // still served (stale-flagged) from v1 until someone asks for the append: Decision 5(b), reported not acted on
            JsonNode staleServed = neighbors(c, "A", "");
            assertEquals("index", staleServed.at("/source/kind").asText());
            assertTrue(staleServed.at("/source/stale").asBoolean());
            assertEquals(1, staleServed.at("/source/version").asInt());

            JsonNode v2 = build(c, "f_ds", "append");
            assertEquals(2, v2.at("/result/version").asInt(), "an append is a NEW immutable version");
            assertEquals("append", v2.at("/result/builder").asText());
            assertEquals(3, v2.at("/result/edges").asInt(), "the delta's edges");
            assertEquals(7, v2.at("/result/indexEdges").asInt(), "main + delta");
            JsonNode afterAppend = indexOf(c, "f_ds");
            assertFalse(afterAppend.get("stale").asBoolean(), String.valueOf(afterAppend.get("reason")));
            assertEquals("none", afterAppend.at("/plan/recommended").asText());
            assertEquals(1, afterAppend.get("deltas").asInt());
            assertEquals("append", afterAppend.get("builder").asText());
            assertTrue(sameNeighbours(c, 2) > 20);
            sameExpand(c, "[\"A\",\"B\"]", 2);

            plant(dir.resolve("p3.parquet"), P3, 1_700_000_002_000L);
            assertEquals(3, build(c, "f_ds", "append").at("/result/version").asInt());
            assertEquals(2, indexOf(c, "f_ds").get("deltas").asInt());
            sameNeighbours(c, 3);
            sameExpand(c, "[\"A\",\"C\"]", 3);

            // compaction is explicit and changes no answer; it carries what the index covers
            JsonNode v4 = build(c, "f_ds", "compact");
            assertEquals(4, v4.at("/result/version").asInt());
            assertEquals("compact", v4.at("/result/builder").asText());
            JsonNode compacted = indexOf(c, "f_ds");
            assertEquals(0, compacted.get("deltas").asInt());
            assertFalse(compacted.get("stale").asBoolean());
            assertEquals("none", compacted.at("/plan/recommended").asText());
            assertEquals(10, compacted.get("rows").asInt());
            sameNeighbours(c, 4);
            sameExpand(c, "[\"A\",\"B\"]", 4);
        } finally {
            rmTree(dir);
            if (!hadDatabase) Path.of("database").toFile().delete();
        }
    }

    @Test
    void aRewrittenOrRemovedFileForcesAFullBuildTheRouteRefusesAnAppendAndTheStaleIndexStillRefusesReads(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        String store = "idx_app_" + System.nanoTime();
        Path dir = Path.of("database").resolve(store);
        boolean hadDatabase = Files.isDirectory(Path.of("database"));
        try (Ctx c = open(cfg, root, store)) {
            plant(dir.resolve("p1.parquet"), P1, 1_700_000_000_000L);
            plant(dir.resolve("p2.parquet"), P2, 1_700_000_001_000L);
            build(c, "f_ds", null);
            plant(dir.resolve("p3.parquet"), P3, 1_700_000_002_000L);
            assertEquals("append", indexOf(c, "f_ds").at("/plan/recommended").asText());

            Files.setLastModifiedTime(dir.resolve("p1.parquet"), FileTime.fromMillis(1_700_000_009_000L));        // a covered file was REWRITTEN
            JsonNode touched = indexOf(c, "f_ds");
            assertEquals("full", touched.at("/plan/recommended").asText());
            assertFalse(touched.at("/plan/appendable").asBoolean());
            assertEquals(1, touched.at("/plan/changed").asInt());
            assertTrue(touched.at("/plan/reasons").toString().contains("input_files_changed"), touched.toString());
            HttpResponse<String> refused = tryBuild(c, "f_ds", "append", OWNER);
            assertEquals(409, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("run a full build"), refused.body());
            assertEquals("index_stale_refused", neighbors(c, "A", "").at("/source/reason").asText(), "removed rows could be exposed: still refused");
            assertEquals(1, indexOf(c, "f_ds").get("version").asInt(), "the refused append built nothing");

            Files.setLastModifiedTime(dir.resolve("p1.parquet"), FileTime.fromMillis(1_700_000_000_000L));        // restore, then REMOVE a covered file
            Files.delete(dir.resolve("p2.parquet"));
            JsonNode gone = indexOf(c, "f_ds");
            assertEquals("full", gone.at("/plan/recommended").asText());
            assertEquals(1, gone.at("/plan/removed").asInt());
            assertEquals(409, tryBuild(c, "f_ds", "append", OWNER).statusCode());

            // the way forward: a full build, after which the (new) file set is current
            JsonNode full = build(c, "f_ds", "full");
            assertEquals(2, full.at("/result/version").asInt());
            assertEquals("none", indexOf(c, "f_ds").at("/plan/recommended").asText());
            assertEquals("index", neighbors(c, "A", "").at("/source/kind").asText());
        } finally {
            rmTree(dir);
            if (!hadDatabase) Path.of("database").toFile().delete();
        }
    }

    @Test
    void appendAndCompactRefuseWhenThereIsNothingToDoAndKeepTheirGates(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        String store = "idx_app_" + System.nanoTime();
        Path dir = Path.of("database").resolve(store);
        boolean hadDatabase = Files.isDirectory(Path.of("database"));
        try (Ctx c = open(cfg, root, store)) {
            plant(dir.resolve("p1.parquet"), P1, 1_700_000_000_000L);
            assertEquals(409, tryBuild(c, "f_ds", "append", OWNER).statusCode(), "no index yet");
            assertEquals(409, tryBuild(c, "f_ds", "compact", OWNER).statusCode(), "no index yet");
            build(c, "f_ds", null);
            HttpResponse<String> none = tryBuild(c, "f_ds", "append", OWNER);
            assertEquals(409, none.statusCode(), "nothing was added");
            assertTrue(none.body().contains("no input file was added"), none.body());
            assertEquals(409, tryBuild(c, "f_ds", "compact", OWNER).statusCode(), "no deltas");
            assertEquals(422, tryBuild(c, "f_ds", "merge", OWNER).statusCode(), "an unknown mode");
            plant(dir.resolve("p2.parquet"), P2, 1_700_000_001_000L);
            // the capability gate covers every mode (a real Subject without canBuildLinkIndex)
            for (String mode : List.of("full", "append", "compact")) assertEquals(403, tryBuild(c, "f_ds", mode, NOCAP).statusCode(), mode);
            assertEquals(1, indexOf(c, "f_ds").get("version").asInt());

            // a view-backed Dataset has no files to list: full builds, never appends
            build(c, "v_ds", null);
            HttpResponse<String> view = tryBuild(c, "v_ds", "append", OWNER);
            assertEquals(409, view.statusCode(), view.body());
            assertTrue(view.body().contains("input_files_unknown"), view.body());
            assertEquals("full", indexOf(c, "v_ds").at("/plan/recommended").asText());
        } finally {
            rmTree(dir);
            if (!hadDatabase) Path.of("database").toFile().delete();
        }
    }

    @Test
    void aSealedReadKeepsReplayingByteForByteAcrossAnAppendAndTheRereadSaysTheIndexMoved(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        String store = "idx_app_" + System.nanoTime();
        Path dir = Path.of("database").resolve(store);
        boolean hadDatabase = Files.isDirectory(Path.of("database"));
        try (Ctx c = open(cfg, root, store)) {
            plant(dir.resolve("p1.parquet"), P1, 1_700_000_000_000L);
            build(c, "f_ds", null);
            JsonNode step = expand(c, "[\"B\"]");                                                  // B's edges are all in p1
            assertEquals(1, step.at("/read/index/version").asInt());
            String id = lastId;
            String plain = JSON.readTree(send(c, "POST", "/inv/investigations/" + id + "/replay", "{}", OWNER).body()).get("data").toString();

            plant(dir.resolve("p2.parquet"), P2, 1_700_000_001_000L);                              // A, C, D only: B's neighbours do not change
            build(c, "f_ds", "append");
            assertEquals(plain, JSON.readTree(send(c, "POST", "/inv/investigations/" + id + "/replay", "{}", OWNER).body()).get("data").toString(),
                    "a plain replay never touches the index");
            JsonNode reread = data(send(c, "POST", "/inv/investigations/" + id + "/replay", "{\"reread\":true}", OWNER), 200);
            assertFalse(reread.get("diverged").asBoolean(), reread.toString());
            assertEquals(1, reread.at("/drift/0/indexVersionSealed").asInt());
            assertEquals(2, reread.at("/drift/0/indexVersionNow").asInt(), "the index moved (an append), and the reread says so");
        } finally {
            rmTree(dir);
            if (!hadDatabase) Path.of("database").toFile().delete();
        }
    }
}
