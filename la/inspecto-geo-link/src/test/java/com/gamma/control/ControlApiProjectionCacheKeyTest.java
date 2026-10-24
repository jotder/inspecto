package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.audit.Event;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.event.EventLog;
import com.gamma.la.api.InputFingerprintCache;
import com.gamma.la.api.ProjectionCache;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.SpaceSecretKeys;
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
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-P4-2 round 2: every component of the projection cache key is load-bearing (a change in it alone is a different ETag
 * and never a 304), a hit audits exactly as its miss did, the per-entry and total bounds hold, and the gates run before the
 * cache. Armed authenticator throughout; the Dataset is files-backed, so it IS cached.
 */
class ControlApiProjectionCacheKeyTest {

    @BeforeAll static void raiseBudget() {
        System.setProperty("control.rateLimit.linkAnalysis.capacity", "10000");
    }

    @AfterAll static void restoreBudget() {
        System.clearProperty("control.rateLimit.linkAnalysis.capacity");
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OWNER = "tok-owner", VIEWER = "tok-viewer";
    private static final String COLS = "\"dataset\":\"d_ds\",\"sourceCol\":\"s\",\"targetCol\":\"t\"";
    private static final String BODY = "{" + COLS + "}";
    private static final String NB = "{" + COLS + ",\"value\":\"B\"}";
    private static final List<Object> SHARE_VIEWER = List.of(Map.of("subjectType", "user", "subjectId", "analyst-2", "access", "view"));
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, Path root, Path dir, String store) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
        InputFingerprintCache.forTest(null, 0);
        ProjectionCache.forTest(null, 0);
        ProjectionCache.forTestCaps(0, 0);
    }

    private Ctx open(Path cfg, Path root, String store) throws Exception {
        ProjectionCache.forTest(null, 0);                                                         // earlier classes in this JVM leave entries
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case OWNER -> Optional.of(new Subject("analyst-1", Set.of("canBuildLinkIndex", "canManageIncidents")));
            case VIEWER -> Optional.of(new Subject("analyst-2", Set.of()));
            default -> Optional.empty();
        });
        AtomicLong tick = new AtomicLong();
        InputFingerprintCache.forTest(() -> tick.addAndGet(60_000L), 30_000L);
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            Ctx c = new Ctx(svc, api, root, Path.of("database").resolve(store), store);
            register(c, SHARE_VIEWER);
            Path p1 = c.dir().resolve("p1.parquet");
            if (!Files.exists(p1)) plant(p1, "('A','B'),('A','B'),('B','C')", 1_700_000_000_000L);
            return c;
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private Ctx open(Path cfg, Path root) throws Exception {
        return open(cfg, root, "cachekey_" + System.nanoTime());
    }

    private static void register(Ctx c, List<Object> shares) throws Exception {
        new ComponentStore(c.root().resolve("registry")).write("dataset", "d_ds", Map.of("physicalRef", c.store(), "owner", "analyst-1",
                "shares", shares));
    }

    private static void plant(Path file, String pairs, long mtime) throws Exception {
        Files.createDirectories(file.getParent());
        DuckDbUtil.loadDriver();
        java.io.File db = DuckDbUtil.tempDbFile("cachekey_");
        try (java.sql.Connection conn = DuckDbUtil.openConnection(db); java.sql.Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT s, t, TIMESTAMP '2026-03-01 09:00:00' AS ts FROM (VALUES " + pairs + ") AS v(s,t)) TO '"
                    + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        Files.setLastModifiedTime(file, FileTime.fromMillis(mtime));
    }

    private HttpResponse<String> send(Ctx c, String method, String route, String body, String token, String ifNoneMatch) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.api().port() + "/api/v1/" + route))
                .header("Content-Type", "application/json").header("Authorization", token);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body));
        if (ifNoneMatch != null) b.header("If-None-Match", ifNoneMatch);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> post(Ctx c, String route, String body, String token, String inm) throws Exception {
        return send(c, "POST", route, body, token, inm);
    }

    private static String etag(HttpResponse<String> r) {
        return r.headers().firstValue("ETag").orElse(null);
    }

    private static JsonNode data(HttpResponse<String> r) throws Exception {
        assertTrue(r.statusCode() == 200 || r.statusCode() == 202, r.body());                  // 202: an index build accepted
        return JSON.readTree(r.body()).get("data");
    }

    private void build(Ctx c) throws Exception {
        String id = data(send(c, "POST", "inv/index/builds", "{" + COLS + ",\"timeCol\":\"ts\"}", OWNER, null)).get("buildId").asText();
        for (int i = 0; i < 6_000; i++) {
            String st = data(send(c, "GET", "inv/index/builds/" + id, null, OWNER, null)).get("status").asText();
            if (st.equals("COMPLETED")) return;
            assertFalse(st.equals("FAILED") || st.equals("CANCELLED"), st);
            Thread.sleep(10);
        }
        throw new AssertionError("build never completed");
    }

    /** The warmed ETag of {@code route}/{@code body}; a repeat must be a 304 (the control every key test relies on). */
    private String warm(Ctx c, String route, String body, String token) throws Exception {
        HttpResponse<String> r = post(c, route, body, token, null);
        String tag = etag(r);
        assertNotNull(tag, r.body());
        assertEquals(304, post(c, route, body, token, tag).statusCode(), "control: the warmed entry answers 304");
        return tag;
    }

    /** After a change of ONE key input, the old ETag is a fresh 200 with a different ETag (never a 304). */
    private HttpResponse<String> assertMissWith(Ctx c, String route, String body, String token, String oldTag, String what) throws Exception {
        HttpResponse<String> r = post(c, route, body, token, oldTag);
        assertEquals(200, r.statusCode(), what + " must not answer 304 with the old ETag: " + r.body());
        assertNotEquals(oldTag, etag(r), what + " must change the ETag");
        return r;
    }

    // ── audit parity ──────────────────────────────────────────────────────────────────────────────────────────────

    private static final List<String> AUDIT_KEYS = List.of("dataset", "rows", "truncated", "value", "source", "indexVersion",
            "indexStale", "sourceReason");

    private static Map<String, Object> auditOf(Event e) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : AUDIT_KEYS) m.put(k, e.attributes().get(k));
        return m;
    }

    @Test
    void aHitAuditsTheSameFieldsAsItsMissOnBothRoutes(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            build(c);
            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            EventLog.current().addSubscriber(sub);
            try {
                String tag = etag(post(c, "inv/projection/neighbors", NB, OWNER, null));
                assertEquals(304, post(c, "inv/projection/neighbors", NB, OWNER, tag).statusCode());
                assertEquals(200, post(c, "inv/projection/neighbors", NB, OWNER, null).statusCode());
                String ptag = etag(post(c, "inv/projection", BODY, OWNER, null));
                assertEquals(304, post(c, "inv/projection", BODY, OWNER, ptag).statusCode());
            } finally {
                EventLog.current().removeSubscriber(sub);
            }
            List<Event> nb = seen.stream().filter(x -> LinkEventTypes.LINK_EXPANDED.equals(x.type())).toList();
            assertEquals(3, nb.size(), seen.toString());
            assertEquals("index", nb.get(0).attributes().get("source"), "control: the miss was answered by the index");
            assertNotNull(nb.get(0).attributes().get("indexVersion"));
            assertEquals(auditOf(nb.get(0)), auditOf(nb.get(1)), "a 304 hit audits as its miss");
            assertEquals(auditOf(nb.get(0)), auditOf(nb.get(2)), "a 200 hit audits as its miss");
            List<Event> pr = seen.stream().filter(x -> LinkEventTypes.LINK_PROJECTED.equals(x.type())).toList();
            assertEquals(2, pr.size(), seen.toString());
            assertEquals(auditOf(pr.get(0)), auditOf(pr.get(1)));
        }
    }

    // ── bounds ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aResultOverThePerEntryCapIsServedButNeverCached(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            ProjectionCache.forTestCaps(1, 0);                                                    // the 2-row projection is over the cap
            long skipped = ProjectionCache.skippedStores(), reads = ProjectionCache.reads();
            HttpResponse<String> first = post(c, "inv/projection", BODY, OWNER, null);
            assertEquals(2, data(first).get("rows").size());
            assertNull(etag(first), "an uncached answer publishes no ETag");
            HttpResponse<String> second = post(c, "inv/projection", BODY, OWNER, "*");
            assertEquals(200, second.statusCode(), "never a 304 for an uncached result");
            assertEquals(reads + 2, ProjectionCache.reads(), "both requests read the Dataset");
            assertEquals(skipped + 2, ProjectionCache.skippedStores());
            assertEquals(0, ProjectionCache.size());
            // control: a 1-row answer under the same cap is cached
            warm(c, "inv/projection", "{" + COLS + ",\"limit\":1}", OWNER);
        }
    }

    @Test
    void theTotalByteBoundEvictsTheLeastRecentlyUsed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String b1 = "{" + COLS + ",\"limit\":10}", b2 = "{" + COLS + ",\"limit\":11}", b3 = "{" + COLS + ",\"limit\":12}";
            post(c, "inv/projection", b1, OWNER, null);
            long one = ProjectionCache.bytes();
            assertTrue(one > 0);
            ProjectionCache.forTestCaps(0, one * 5 / 2);                                          // room for two entries, not three
            String t1 = etag(post(c, "inv/projection", b1, OWNER, null));
            String t2 = etag(post(c, "inv/projection", b2, OWNER, null));
            assertEquals(304, post(c, "inv/projection", b1, OWNER, t1).statusCode());           // b1 is now the most recent
            post(c, "inv/projection", b3, OWNER, null);                                          // evicts b2 (LRU), not b1
            assertEquals(2, ProjectionCache.size());
            assertTrue(ProjectionCache.bytes() <= one * 5 / 2);
            assertEquals(304, post(c, "inv/projection", b1, OWNER, t1).statusCode(), "the recently used entry stays");
            assertEquals(200, post(c, "inv/projection", b2, OWNER, t2).statusCode(), "the least recently used entry was evicted");
        }
    }

    @Test
    void anExpiredEntryLeavesMemoryWithoutBeingReadAgain(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            AtomicLong now = new AtomicLong(1_000_000L);
            ProjectionCache.forTest(now::get, 1_000L);
            post(c, "inv/projection", "{" + COLS + ",\"limit\":10}", OWNER, null);
            assertEquals(1, ProjectionCache.size());
            now.addAndGet(5_000L);                                                               // past the TTL; the entry is never read again
            post(c, "inv/projection", "{" + COLS + ",\"limit\":11}", OWNER, null);
            assertEquals(1, ProjectionCache.size(), "the put swept the expired entry");
        }
    }

    // ── each key component alone ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void theRouteIsPartOfTheKey(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String tag = warm(c, "inv/projection", NB, OWNER);                                    // the projection ignores `value`
            assertMissWith(c, "inv/projection/neighbors", NB, OWNER, tag, "another route");
        }
    }

    @Test
    void theBodyIsPartOfTheKey(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String tag = warm(c, "inv/projection", BODY, OWNER);
            HttpResponse<String> r = assertMissWith(c, "inv/projection", "{" + COLS + ",\"attrCols\":[\"ts\"]}", OWNER, tag, "another body");
            assertNotNull(data(r).get("rows").get(0).get("attrs"));
        }
    }

    /** Same path, new content and mtime: only the input fingerprint can tell (the file list is unchanged). */
    @Test
    void theInputFingerprintIsPartOfTheKey(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String tag = warm(c, "inv/projection", BODY, OWNER);
            plant(c.dir().resolve("p1.parquet"), "('X','Y')", 1_700_000_500_000L);
            HttpResponse<String> r = assertMissWith(c, "inv/projection", BODY, OWNER, tag, "a rewritten input file");
            assertEquals("X", data(r).get("rows").get(0).get("source").asText());
        }
    }

    @Test
    void theSpaceIsPartOfTheKey(@TempDir Path cfg, @TempDir Path root1, @TempDir Path cfg2, @TempDir Path root2) throws Exception {
        String store = "cachekey_space_" + System.nanoTime();
        String tag;
        try (Ctx c1 = open(cfg, root1, store)) {
            tag = warm(c1, "inv/projection", BODY, OWNER);
        }
        try (Ctx c2 = open(cfg2, root2, store)) {                                                 // same data, same body, same viewer
            assertMissWith(c2, "inv/projection", BODY, OWNER, tag, "another Space");
        }
    }

    @Test
    void theResolvedLimitIsPartOfTheKey(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String tag = warm(c, "inv/projection", BODY, OWNER);                                  // no `limit` in the body
            Files.writeString(c.root().resolve("link-analysis.toon"), "traversal:\n  rows_default: 1\n");
            HttpResponse<String> r = assertMissWith(c, "inv/projection", BODY, OWNER, tag, "a changed rows_default");
            assertEquals(1, data(r).get("rows").size());
        }
    }

    @Test
    void theDatasetEntryIsPartOfTheKey(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String tag = warm(c, "inv/projection", BODY, OWNER);
            List<Object> more = new ArrayList<>(SHARE_VIEWER);
            more.add(Map.of("subjectType", "user", "subjectId", "analyst-9", "access", "view"));
            register(c, more);
            assertMissWith(c, "inv/projection", BODY, OWNER, tag, "a changed Dataset registry entry");
        }
    }

    @Test
    void theIndexSettingIsPartOfTheKey(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String tag = warm(c, "inv/projection", BODY, OWNER);
            Files.writeString(c.root().resolve("link-analysis.toon"), "index:\n  enabled: false\n");
            assertMissWith(c, "inv/projection", BODY, OWNER, tag, "index.enabled");
        }
    }

    @Test
    void theEntityTypesAreAndTheMaskingModeIsPartOfTheKey(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String tag = warm(c, "inv/projection", BODY, OWNER);
            Files.writeString(c.root().resolve("link-analysis.toon"), "four_eyes_budget_above: 100000\n");
            tag = etag(assertMissWith(c, "inv/projection", BODY, OWNER, tag, "a four-eyes threshold"));
            Files.writeString(c.root().resolve("link-analysis.toon"), "four_eyes_budget_above: 100000\nmasking_mode: all\n");
            HttpResponse<String> masked = assertMissWith(c, "inv/projection", BODY, OWNER, tag, "the masking mode");
            assertNotEquals("A", data(masked).get("rows").get(0).get("source").asText());
        }
    }

    @Test
    void theSpaceMaskingKeyIsPartOfTheKey(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            Files.writeString(c.root().resolve("link-analysis.toon"), "masking_mode: all\n");
            HttpResponse<String> first = post(c, "inv/projection", BODY, OWNER, null);
            String alias = data(first).get("rows").get(0).get("source").asText();
            String tag = warm(c, "inv/projection", BODY, OWNER);
            Path key = SpaceSecretKeys.keyFile(c.root(), ".risk-score-mask.key");
            assertTrue(Files.exists(key), key.toString());
            Files.writeString(key, "ab".repeat(32));                                             // the Space key rotated
            HttpResponse<String> r = assertMissWith(c, "inv/projection", BODY, OWNER, tag, "a rotated masking key");
            assertNotEquals(alias, data(r).get("rows").get(0).get("source").asText(), "aliases under the new key");
        }
    }

    @Test
    void aRebuiltIndexIsANewNeighboursETag(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            build(c);
            String tag = warm(c, "inv/projection/neighbors", NB, OWNER);
            build(c);
            assertMissWith(c, "inv/projection/neighbors", NB, OWNER, tag, "a new index version");
        }
    }

    // ── gate order ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void revokingAccessAfterWarmingIsARefusalNotA304(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String tag = warm(c, "inv/projection", BODY, VIEWER);                                 // the twin probe: the warmed 304 succeeds
            String nbTag = warm(c, "inv/projection/neighbors", NB, VIEWER);
            register(c, List.of());                                                               // the share is removed
            HttpResponse<String> r = post(c, "inv/projection", BODY, VIEWER, tag);
            assertEquals(404, r.statusCode(), r.body());
            assertFalse(r.body().contains("\"rows\""), "never the cached body");
            assertNull(etag(r));
            assertEquals(404, post(c, "inv/projection/neighbors", NB, VIEWER, nbTag).statusCode());
            assertEquals(404, post(c, "inv/projection", BODY, VIEWER, null).statusCode());
        }
    }
}
