package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.la.api.IndexRoutes;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.la.storage.IndexBuilder;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-3 step 4 - {@code /inv/index/*} over REAL HTTP with an ARMED Authenticator throughout: with no Subject attached
 * {@code withCapability} is a no-op and every gate below would pass against an ungated route.
 *
 * <p>Subjects: {@code analyst-1} owns both Datasets and holds {@code canBuildLinkIndex}; {@code nocap-1} is the same
 * person WITHOUT it; {@code analyst-2} may VIEW {@code shared_ds} only; {@code analyst-3} may view neither; {@code admin-1} holds
 * {@code canAdminister}. A builder probe that blocks until released (or cancelled) makes RUNNING deterministic; the real
 * {@link IndexBuilder} answers everything that needs files.
 *
 * <p>Package {@code com.gamma.control} in the geo-link module is a TEST-SCOPE split package, on purpose (see
 * {@link ControlApiGeoDatasetViewGateTest}).
 */
class ControlApiIndexTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OWNER = "Bearer owner", NOCAP = "Bearer nocap", VIEWER = "Bearer viewer", STRANGER = "Bearer stranger", ADMIN = "Bearer admin";
    private static final String CAN = "canBuildLinkIndex";
    private static final String PRIVATE_BUILD = "{\"dataset\":\"private_ds\",\"sourceCol\":\"who\",\"targetCol\":\"other\",\"kindCol\":\"kind\",\"timeCol\":\"ts\"}";
    private static final String SHARED_BUILD = "{\"dataset\":\"shared_ds\",\"sourceCol\":\"who\",\"targetCol\":\"other\",\"kindCol\":\"kind\"}";
    private static final String SHARED_BUILD_2 = "{\"dataset\":\"shared_ds\",\"sourceCol\":\"who\",\"targetCol\":\"other\"}";
    private static final String VIEW_SQL = "SELECT * FROM (VALUES ('alice','bob','call',TIMESTAMP '2026-01-01 00:00:00'),"
            + "('bob','carol','sms',TIMESTAMP '2026-01-02 00:00:00'),('alice','carol','call',TIMESTAMP '2026-01-03 00:00:00')) AS t(who,other,kind,ts)";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
        IndexRoutes.forTest(null);
    }

    private static void subjects() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case OWNER -> Optional.of(new Subject("analyst-1", Set.of(CAN)));
            case NOCAP -> Optional.of(new Subject("analyst-1", Set.of()));
            case VIEWER -> Optional.of(new Subject("analyst-2", Set.of(CAN)));
            case STRANGER -> Optional.of(new Subject("analyst-3", Set.of(CAN)));
            case ADMIN -> Optional.of(new Subject("admin-1", Set.of("canAdminister")));
            default -> Optional.empty();
        });
    }

    /** {@code settings} is the Space's {@code link-analysis.toon}; a null {@code writeRoot} is a read-only control plane. */
    private Ctx open(Path configDir, Path writeRoot, String settings) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            if (writeRoot != null) {
                if (!settings.isEmpty()) Files.writeString(writeRoot.resolve("link-analysis.toon"), settings);
                writeView(writeRoot, VIEW_SQL);
                ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
                // `shares` PRESENT => restricted: only the owner (and admins) see it, plus whoever is listed.
                store.write("dataset", "private_ds", Map.of("view", "sights_view", "owner", "analyst-1", "shares", List.of()));
                new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("empty_view", "flow-x", List.of(),
                        "SELECT * FROM (VALUES ('a','b','c',TIMESTAMP '2026-01-01 00:00:00')) AS t(who,other,kind,ts) WHERE 1 = 0", "2026-10-02T00:00:00Z"));
                store.write("dataset", "empty_ds", Map.of("view", "empty_view"));
                store.write("dataset", "shared_ds", Map.of("view", "sights_view", "owner", "analyst-1", "shares",
                        List.of(Map.of("subjectType", "user", "subjectId", "analyst-2", "access", "view"))));
            }
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private static void writeView(Path writeRoot, String sql) throws Exception {
        new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("sights_view", "flow-x", List.of(), sql, "2026-10-02T00:00:00Z"));
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

    private JsonNode ok(Ctx c, String method, String path, String body, String auth) throws Exception {
        return data(send(c, method, path, body, auth), 200);
    }

    private interface Check {
        boolean ok() throws Exception;
    }

    private static void until(Check c, String what) throws Exception {
        long end = System.nanoTime() + 30_000_000_000L;
        while (!c.ok()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(10);
        }
    }

    private String status(Ctx c, String id, String auth) throws Exception {
        return ok(c, "GET", "/inv/index/builds/" + id, null, auth).get("status").asText();
    }

    private void awaitStatus(Ctx c, String id, String auth, String want) throws Exception {
        until(() -> want.equals(status(c, id, auth)), "build " + id + " to be " + want);
    }

    /** Starts a build and accepts the 202 (never 200: a build is never answered inline). */
    private String start(Ctx c, String body, String auth) throws Exception {
        HttpResponse<String> r = send(c, "POST", "/inv/index/builds", body, auth);
        JsonNode d = data(r, 202);
        assertTrue(r.headers().firstValue("Location").orElse("").endsWith("/inv/index/builds/" + d.get("buildId").asText()), "Location names the build");
        return d.get("buildId").asText();
    }

    /** Builds that block until released or cancelled, like the real builder, then answer a canned result. */
    private static final class Blocking implements Function<IndexBuilder.Request, IndexBuilder.Result> {
        volatile boolean release;

        @Override
        public IndexBuilder.Result apply(IndexBuilder.Request req) {
            IndexBuilder.CancelToken token = req.options().cancel();
            try {
                while (!release) {
                    if (token.isCancelled()) throw new IndexBuilder.CancelledException();
                    Thread.sleep(5);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return new IndexBuilder.Result(1, req.store().directory(), null, 3, 3, 0, 3, 16, Map.of(), 5);
        }
    }

    /** What an error body says, minus anything that varies per request: code + message with ids and the Dataset name masked. */
    private String errorKey(HttpResponse<String> r, String... masks) throws Exception {
        JsonNode root = JSON.readTree(r.body());
        String key = r.statusCode() + "|" + root.findValue("code") + "|" + root.findValue("message");
        for (String m : masks) key = key.replace(m, "<x>");
        return key;
    }

    // -- the happy path -------------------------------------------------------------------------------------------------

    @Test
    void theOwnerBuildsAnIndexAndItIsListedWithItsManifestSummary(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "")) {
            JsonNode before = ok(c, "GET", "/inv/index", null, OWNER);
            assertEquals(0, before.get("total").asInt());
            assertFalse(before.get("enabled").asBoolean(), "index.enabled defaults to false (design Decision 8)");

            String id = start(c, PRIVATE_BUILD, OWNER);
            awaitStatus(c, id, OWNER, "COMPLETED");
            JsonNode build = ok(c, "GET", "/inv/index/builds/" + id, null, OWNER);
            assertEquals("private_ds", build.get("dataset").asText());
            assertEquals(3, build.get("result").get("edges").asInt());
            assertEquals(1, build.get("result").get("version").asInt());
            assertTrue(build.get("result").get("bytes").asLong() > 0);
            assertFalse(build.toString().contains(root.toString()), "no server path in a build view");

            JsonNode list = ok(c, "GET", "/inv/index", null, OWNER);
            assertEquals(1, list.get("total").asInt());
            JsonNode ix = list.get("indexes").get(0);
            assertEquals("private_ds", ix.get("dataset").asText());
            assertEquals(1, ix.get("version").asInt());
            assertEquals(3, ix.get("rows").asLong());
            assertTrue(ix.get("nodes").asLong() > 0 && ix.get("bytes").asLong() > 0);
            assertFalse(ix.get("builtAt").asText().isBlank());
            assertEquals("who", ix.get("mapping").get("sourceCol").asText());
            assertEquals("ts", ix.get("mapping").get("timeCol").asText());
            assertFalse(ix.get("stale").asBoolean(), "just built: " + ix.get("reason"));
            assertTrue(ix.get("reason").isNull());
            assertTrue(Files.isDirectory(root.resolve("la-index")), "the index lives under the Space write root");

            // a second build of the same index publishes the next version (the first finished, so it is no duplicate)
            String again = start(c, PRIVATE_BUILD, OWNER);
            awaitStatus(c, again, OWNER, "COMPLETED");
            assertEquals(2, ok(c, "GET", "/inv/index", null, OWNER).get("indexes").get(0).get("version").asInt());
        }
    }

    @Test
    void theIndexGoesStaleWhenTheDatasetsRelationChanges(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "")) {
            String id = start(c, PRIVATE_BUILD, OWNER);
            awaitStatus(c, id, OWNER, "COMPLETED");
            assertFalse(ok(c, "GET", "/inv/index", null, OWNER).get("indexes").get(0).get("stale").asBoolean());

            writeView(root, VIEW_SQL + " WHERE who <> 'bob'");                                     // re-point the Dataset's view
            JsonNode ix = ok(c, "GET", "/inv/index", null, OWNER).get("indexes").get(0);
            assertTrue(ix.get("stale").asBoolean(), "the Dataset's relation changed");
            assertTrue(ix.get("reason").asText().contains("relation SQL changed"), ix.get("reason").asText());
            assertEquals(1, ix.get("version").asInt(), "stale but present: the index is still listed");

            writeView(root, VIEW_SQL);                                                              // put it back: fresh again
            assertFalse(ok(c, "GET", "/inv/index", null, OWNER).get("indexes").get(0).get("stale").asBoolean());
        }
    }

    @Test
    void theEnabledFlagIsEchoedFromTheSpacesSettings(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "index:\n  enabled: true\n")) {
            assertTrue(ok(c, "GET", "/inv/index", null, OWNER).get("enabled").asBoolean());
        }
    }

    // -- Decision 2: the base-Dataset gate -------------------------------------------------------------------------------

    @Test
    void aDatasetTheCallerMayNotViewIsIndistinguishableFromAbsentOnEveryRoute(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "")) {
            // the probe that WOULD succeed: the owner builds the very same request fine
            String id = start(c, PRIVATE_BUILD, OWNER);
            awaitStatus(c, id, OWNER, "COMPLETED");
            assertEquals(1, ok(c, "GET", "/inv/index", null, OWNER).get("total").asInt());

            HttpResponse<String> hidden = send(c, "POST", "/inv/index/builds", PRIVATE_BUILD, STRANGER);
            HttpResponse<String> ghost = send(c, "POST", "/inv/index/builds", PRIVATE_BUILD.replace("private_ds", "ghost_ds"), STRANGER);
            assertEquals(404, hidden.statusCode(), hidden.body());
            assertEquals(errorKey(ghost, "ghost_ds"), errorKey(hidden, "private_ds"), "shared-away == absent: same status, code and message");
            assertEquals(0, ok(c, "GET", "/inv/index", null, STRANGER).get("total").asInt(), "their listing shows nothing of it");
            assertEquals(1, ok(c, "GET", "/inv/index", null, OWNER).get("total").asInt());

            HttpResponse<String> unknown = send(c, "GET", "/inv/index/builds/ib-nope", null, STRANGER);
            HttpResponse<String> theirs = send(c, "GET", "/inv/index/builds/" + id, null, STRANGER);
            assertEquals(404, theirs.statusCode());
            assertEquals(errorKey(unknown, "ib-nope"), errorKey(theirs, id), "another's build == an unknown build");
        }
    }

    @Test
    void aSharedDatasetIsBuildableAndListedForItsViewerAndAbsentForEveryoneElse(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "")) {
            String id = start(c, SHARED_BUILD, VIEWER);
            awaitStatus(c, id, VIEWER, "COMPLETED");
            assertEquals(1, ok(c, "GET", "/inv/index", null, VIEWER).get("total").asInt());
            assertEquals(1, ok(c, "GET", "/inv/index", null, OWNER).get("total").asInt(), "the owner sees an index over their own Dataset too");
            assertEquals(404, send(c, "POST", "/inv/index/builds", SHARED_BUILD, STRANGER).statusCode(), "a share names ONE user");
            assertEquals(0, ok(c, "GET", "/inv/index", null, STRANGER).get("total").asInt());
        }
    }

    @Test
    void aStarterWhoLosesTheDatasetSeesTheNotFoundOfAnUnknownBuild(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        Blocking probe = new Blocking();
        IndexRoutes.forTest(probe);
        try (Ctx c = open(cfg, root, "")) {
            String id = start(c, SHARED_BUILD, VIEWER);
            awaitStatus(c, id, VIEWER, "RUNNING");
            // the share is revoked
            new ComponentStore(root.resolve("registry")).write("dataset", "shared_ds", Map.of("view", "sights_view", "owner", "analyst-1", "shares", List.of()));
            assertEquals(404, send(c, "GET", "/inv/index/builds/" + id, null, VIEWER).statusCode());
            assertEquals(404, send(c, "POST", "/inv/index/builds/" + id + "/cancel", null, VIEWER).statusCode());
            assertEquals(0, ok(c, "GET", "/inv/index", null, VIEWER).get("total").asInt());
            assertEquals(404, send(c, "GET", "/inv/index/builds/" + id, null, OWNER).statusCode(), "and the Dataset's owner is not the starter");
            probe.release = true;
        }
    }

    // -- staleness grounded in the Dataset's input files (D-3 design 5.3a) ----------------------------------------------------

    /** A 3-edge Parquet file at {@code file} with a pinned mtime (no sleeps: the clock never decides). */
    private static void parquet(Path file, long mtime) throws Exception {
        Files.createDirectories(file.getParent());
        DuckDbUtil.loadDriver();
        java.io.File db = DuckDbUtil.tempDbFile("idx_fp_");
        try (java.sql.Connection conn = DuckDbUtil.openConnection(db); java.sql.Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES ('alice','bob','call'),('bob','carol','sms'),('alice','carol','call')) t(who,other,kind)) TO '"
                    + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(mtime));
    }

    private JsonNode indexOf(Ctx c, String dataset) throws Exception {
        for (JsonNode ix : ok(c, "GET", "/inv/index", null, OWNER).get("indexes")) if (dataset.equals(ix.get("dataset").asText())) return ix;
        throw new AssertionError("no index of " + dataset);
    }

    @Test
    void anAddedFileMakesTheIndexStaleWithoutRemovedInputAndARemovedOrTouchedFileSetsIt(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        // a legacy Space's data root is the relative directory "database": a uniquely named store, removed at the end
        String store = "idx_fp_" + System.nanoTime();
        Path dir = Path.of("database").resolve(store);
        boolean hadDatabase = Files.isDirectory(Path.of("database"));
        try (Ctx c = open(cfg, root, "")) {
            new ComponentStore(root.resolve("registry")).write("dataset", "files_ds",
                    Map.of("physicalRef", store, "owner", "analyst-1", "shares", List.of()));
            Path p1 = dir.resolve("p1.parquet");
            parquet(p1, 1_700_000_000_000L);
            String body = "{\"dataset\":\"files_ds\",\"sourceCol\":\"who\",\"targetCol\":\"other\"}";
            awaitStatus(c, start(c, body, OWNER), OWNER, "COMPLETED");

            JsonNode fresh = indexOf(c, "files_ds");
            assertFalse(fresh.get("stale").asBoolean(), String.valueOf(fresh.get("reason")));
            assertEquals("known", fresh.get("fingerprint").asText());
            assertEquals(1, fresh.get("inputFiles").asInt());
            assertFalse(fresh.get("removedInput").asBoolean());

            Path p2 = dir.resolve("p2.parquet");                                                    // an ADDITION
            parquet(p2, 1_700_000_001_000L);
            JsonNode added = indexOf(c, "files_ds");
            assertTrue(added.get("stale").asBoolean());
            assertTrue(added.get("reasons").toString().contains("input_files_changed"), added.toString());
            assertFalse(added.get("removedInput").asBoolean(), "additions only: nothing removed could be exposed");

            Files.delete(p2);                                                                       // back to the built set
            assertFalse(indexOf(c, "files_ds").get("stale").asBoolean());

            Files.setLastModifiedTime(p1, java.nio.file.attribute.FileTime.fromMillis(1_700_000_002_000L));   // TOUCHED
            JsonNode touched = indexOf(c, "files_ds");
            assertTrue(touched.get("stale").asBoolean());
            assertTrue(touched.get("removedInput").asBoolean(), "a replaced file may have dropped rows");
            Files.setLastModifiedTime(p1, java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000L));
            assertFalse(indexOf(c, "files_ds").get("stale").asBoolean());

            Files.delete(p1);                                                                       // DELETED
            JsonNode removed = indexOf(c, "files_ds");
            assertTrue(removed.get("stale").asBoolean());
            assertTrue(removed.get("removedInput").asBoolean());
        } finally {
            if (Files.isDirectory(dir)) try (var w = Files.walk(dir)) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
            if (!hadDatabase) Path.of("database").toFile().delete();
        }
    }

    @Test
    void aDatasetWithNoEnumerableFilesReportsAnUnknownFingerprintAndIsNotStale(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "")) {
            awaitStatus(c, start(c, PRIVATE_BUILD, OWNER), OWNER, "COMPLETED");
            JsonNode ix = indexOf(c, "private_ds");                                                // a view-backed Dataset
            assertFalse(ix.get("stale").asBoolean());
            assertEquals("unknown", ix.get("fingerprint").asText(), "cannot tell: no currency is claimed");
            assertFalse(ix.has("inputFiles"));
            assertFalse(ix.get("removedInput").asBoolean());
        }
    }

    // -- cancel ------------------------------------------------------------------------------------------------------

    @Test
    void onlyTheStarterOrAnAdministratorCancelsAndANonStarterSeesTheNotFoundOfAnUnknownBuild(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        Blocking probe = new Blocking();
        IndexRoutes.forTest(probe);
        try (Ctx c = open(cfg, root, "")) {
            String id = start(c, SHARED_BUILD, OWNER);
            awaitStatus(c, id, OWNER, "RUNNING");

            // analyst-2 may VIEW the Dataset but did not start the build: the 404 of an unknown build, not a 403
            HttpResponse<String> nonStarter = send(c, "POST", "/inv/index/builds/" + id + "/cancel", null, VIEWER);
            HttpResponse<String> unknown = send(c, "POST", "/inv/index/builds/ib-nope/cancel", null, VIEWER);
            assertEquals(404, nonStarter.statusCode(), nonStarter.body());
            assertEquals(errorKey(unknown, "ib-nope"), errorKey(nonStarter, id));
            assertEquals(404, send(c, "POST", "/inv/index/builds/" + id + "/cancel", null, STRANGER).statusCode());
            assertFalse(ok(c, "GET", "/inv/index/builds/" + id, null, OWNER).get("cancelRequested").asBoolean(), "nothing was cancelled");

            // an administrator may stop any build
            JsonNode c1 = data(send(c, "POST", "/inv/index/builds/" + id + "/cancel", null, ADMIN), 202);
            assertTrue(c1.get("cancelRequested").asBoolean());
            awaitStatus(c, id, OWNER, "CANCELLED");
            assertEquals(409, send(c, "POST", "/inv/index/builds/" + id + "/cancel", null, OWNER).statusCode(), "a finished build cannot be cancelled");

            // the starter stops their own
            String mine = start(c, SHARED_BUILD_2, OWNER);
            awaitStatus(c, mine, OWNER, "RUNNING");
            data(send(c, "POST", "/inv/index/builds/" + mine + "/cancel", null, OWNER), 202);
            awaitStatus(c, mine, OWNER, "CANCELLED");
            assertEquals(0, ok(c, "GET", "/inv/index", null, OWNER).get("total").asInt(), "a cancelled build published nothing");
        }
    }

    // -- 409 · 403 · 422 · 503 -------------------------------------------------------------------------------------------

    @Test
    void aSecondStartOfTheSameIndexWhileOneIsLiveIs409ButAnotherMappingIsNot(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        Blocking probe = new Blocking();
        IndexRoutes.forTest(probe);
        try (Ctx c = open(cfg, root, "")) {
            String id = start(c, SHARED_BUILD, OWNER);
            awaitStatus(c, id, OWNER, "RUNNING");
            HttpResponse<String> dup = send(c, "POST", "/inv/index/builds", SHARED_BUILD, OWNER);
            assertEquals(409, dup.statusCode(), dup.body());
            assertTrue(dup.body().contains(id), "names the live build: " + dup.body());
            HttpResponse<String> other = send(c, "POST", "/inv/index/builds", SHARED_BUILD, VIEWER);
            assertEquals(409, other.statusCode(), "another caller starting the same index is the same duplicate");
            assertFalse(other.body().contains(id), "another viewer does not learn the starter's build id: " + other.body());
            start(c, SHARED_BUILD_2, OWNER);                                                         // another mapping = another index
            probe.release = true;
            awaitStatus(c, id, OWNER, "COMPLETED");
        }
    }

    @Test
    void startingNeedsTheCapabilityButReadingNeedsNone(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "")) {
            HttpResponse<String> denied = send(c, "POST", "/inv/index/builds", PRIVATE_BUILD, NOCAP);   // the owner, WITHOUT the capability
            assertEquals(403, denied.statusCode(), denied.body());
            assertTrue(denied.body().contains(CAN), denied.body());
            assertEquals(401, send(c, "POST", "/inv/index/builds", PRIVATE_BUILD, "Bearer nobody").statusCode());
            String id = start(c, PRIVATE_BUILD, OWNER);
            awaitStatus(c, id, NOCAP, "COMPLETED");                                                  // same person, no capability: may still READ
            assertEquals(1, ok(c, "GET", "/inv/index", null, NOCAP).get("total").asInt());
        }
    }

    @Test
    void badRequestsAreRefused422BeforeAnyBuildExists(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "")) {
            for (String bad : List.of(
                    "{\"sourceCol\":\"who\",\"targetCol\":\"other\"}",                                            // no dataset
                    "{\"dataset\":\"private_ds\",\"targetCol\":\"other\"}",                                       // no sourceCol
                    "{\"dataset\":\"private_ds\",\"sourceCol\":\"who\"}",                                         // no targetCol
                    "{\"dataset\":\"private_ds\",\"sourceCol\":\"who\",\"targetCol\":\"other\",\"extra\":1}",
                    "{\"dataset\":\"private_ds\",\"sourceCol\":\"nope\",\"targetCol\":\"other\"}",                // not a column
                    "{\"dataset\":\"private_ds\",\"sourceCol\":\"who\",\"targetCol\":\"other\",\"attrCols\":[\"ghost\"]}",
                    "{\"dataset\":\"private_ds\",\"sourceCol\":\"who\",\"targetCol\":\"other\",\"attrCols\":\"kind\"}",
                    "{\"dataset\":\"private_ds\",\"sourceCol\":\"who\",\"targetCol\":\"other\",\"timeColZone\":\"UTC\"}",   // a zone with no time column
                    "{\"dataset\":\"private_ds\",\"sourceCol\":\"who\",\"targetCol\":\"other\",\"timeCol\":\"ts\",\"timeColZone\":\"Mars/Base\"}",
                    "{\"dataset\":\"private_ds\",\"sourceCol\":\"\",\"targetCol\":\"other\"}")) {
                HttpResponse<String> r = send(c, "POST", "/inv/index/builds", bad, OWNER);
                assertEquals(422, r.statusCode(), bad + " -> " + r.body());
            }
            assertEquals(0, ok(c, "GET", "/inv/index", null, OWNER).get("total").asInt());
        }
    }

    @Test
    void aBuildWhoseEstimateIsOverTheDiskBudgetIsRefusedUpFrontNamingTheEstimate(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "index:\n  max_disk_bytes: 100\n")) {
            HttpResponse<String> r = send(c, "POST", "/inv/index/builds", PRIVATE_BUILD, OWNER);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("204 bytes"), "3 rows x 34 bytes x 2 = 204, named: " + r.body());
            assertTrue(r.body().contains("max_disk_bytes = 100"), r.body());
            assertFalse(Files.exists(root.resolve("la-index")), "refused before any byte was written");
            // the budget is read per request: raising it lets the same request through, and 0 means no limit
            Files.writeString(root.resolve("link-analysis.toon"), "index:\n  max_disk_bytes: 204\n");
            awaitStatus(c, start(c, PRIVATE_BUILD, OWNER), OWNER, "COMPLETED");
            Files.writeString(root.resolve("link-analysis.toon"), "index:\n  max_disk_bytes: 0\n");
            awaitStatus(c, start(c, PRIVATE_BUILD, OWNER), OWNER, "COMPLETED");
        }
    }

    @Test
    void aReadOnlyControlPlaneIs503(@TempDir Path cfg) throws Exception {
        subjects();
        try (Ctx c = open(cfg, null, "")) {
            assertEquals(503, send(c, "POST", "/inv/index/builds", PRIVATE_BUILD, OWNER).statusCode());
            assertEquals(503, send(c, "GET", "/inv/index", null, OWNER).statusCode());
            assertEquals(503, send(c, "GET", "/inv/index/builds/ib-x", null, OWNER).statusCode());
            assertEquals(503, send(c, "POST", "/inv/index/builds/ib-x/cancel", null, OWNER).statusCode());
        }
    }

    // -- audit -------------------------------------------------------------------------------------------------------

    @Test
    void theLifeOfABuildIsAudited_withoutColumnNames(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        List<Event> seen = new CopyOnWriteArrayList<>();
        Consumer<Event> sub = seen::add;
        EventLog.current().addSubscriber(sub);
        try (Ctx c = open(cfg, root, "")) {
            String id = start(c, PRIVATE_BUILD, OWNER);
            awaitStatus(c, id, OWNER, "COMPLETED");
            until(() -> seen.stream().filter(e -> e.type().startsWith("LINK_INDEX_BUILD_")).count() >= 2, "the started and completed events");
            Event started = find(seen, LinkEventTypes.LINK_INDEX_BUILD_STARTED, id);
            Event completed = find(seen, LinkEventTypes.LINK_INDEX_BUILD_COMPLETED, id);
            assertEquals("private_ds", completed.attributes().get("dataset"));
            assertEquals(started.attributes().get("mappingHash"), completed.attributes().get("mappingHash"));
            assertFalse(String.valueOf(completed.attributes().get("mappingHash")).isBlank());
            assertEquals("3", String.valueOf(completed.attributes().get("edges")));
            assertEquals("3", String.valueOf(completed.attributes().get("rows")));
            assertEquals("1", String.valueOf(completed.attributes().get("version")));
            assertTrue(completed.attributes().containsKey("elapsedMs") && completed.attributes().containsKey("buckets"));
            for (Event e : seen)
                if (e.type().startsWith("LINK_INDEX_BUILD_"))
                    for (var a : e.attributes().entrySet())
                        assertFalse(Set.of("who", "other", "kind", "ts").contains(String.valueOf(a.getValue())),
                                "a column name leaked into the audit: " + e.type() + " " + a);
        } finally {
            EventLog.current().removeSubscriber(sub);
        }
    }

    @Test
    void aCancelledAndAFailedBuildAreAudited(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        List<Event> seen = new CopyOnWriteArrayList<>();
        Consumer<Event> sub = seen::add;
        EventLog.current().addSubscriber(sub);
        Blocking probe = new Blocking();
        IndexRoutes.forTest(probe);
        try (Ctx c = open(cfg, root, "")) {
            String id = start(c, SHARED_BUILD, OWNER);
            awaitStatus(c, id, OWNER, "RUNNING");
            data(send(c, "POST", "/inv/index/builds/" + id + "/cancel", null, OWNER), 202);
            awaitStatus(c, id, OWNER, "CANCELLED");
            until(() -> seen.stream().anyMatch(e -> LinkEventTypes.LINK_INDEX_BUILD_CANCELLED.equals(e.type())), "the cancelled event");
            assertEquals(id, find(seen, LinkEventTypes.LINK_INDEX_BUILD_CANCELLED, id).attributes().get("runId"));
        } finally {
            EventLog.current().removeSubscriber(sub);
        }
    }

    @Test
    void aFailedBuildIsAuditedAndNamesTheClassOnly(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        List<Event> seen = new CopyOnWriteArrayList<>();
        Consumer<Event> sub = seen::add;
        EventLog.current().addSubscriber(sub);
        try (Ctx c = open(cfg, root, "")) {
            String id = start(c, "{\"dataset\":\"empty_ds\",\"sourceCol\":\"who\",\"targetCol\":\"other\"}", OWNER);   // a relation with no edge
            awaitStatus(c, id, OWNER, "FAILED");
            JsonNode b = ok(c, "GET", "/inv/index/builds/" + id, null, OWNER);
            assertEquals("IndexBuildException", b.get("failure").asText(), "the class, never the message");
            assertFalse(b.has("result"));
            until(() -> seen.stream().anyMatch(e -> LinkEventTypes.LINK_INDEX_BUILD_FAILED.equals(e.type())), "the failed event");
            assertEquals("IndexBuildException", find(seen, LinkEventTypes.LINK_INDEX_BUILD_FAILED, id).attributes().get("failure"));
            assertEquals(0, ok(c, "GET", "/inv/index", null, OWNER).get("total").asInt(), "a failed build published nothing");
        } finally {
            EventLog.current().removeSubscriber(sub);
        }
    }

    private static Event find(List<Event> seen, String type, String runId) {
        return seen.stream().filter(e -> type.equals(e.type()) && runId.equals(e.attributes().get("runId"))).findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " for " + runId + " in " + seen.stream().map(Event::type).toList()));
    }

    @Test
    void theCapabilityIsSeededToTheSameRolesAsGraphRuns() {
        for (String role : List.of("operations", "support", "power", "admin"))
            assertEquals(Roles.SEED.get(role).capabilities().contains(Roles.CAN_RUN_LINK_GRAPH_ANALYSIS),
                    Roles.SEED.get(role).capabilities().contains(Roles.CAN_BUILD_LINK_INDEX), role);
        assertNotEquals(0, Roles.SEED.get("admin").capabilities().stream().filter(Roles.CAN_BUILD_LINK_INDEX::equals).count());
        assertFalse(Roles.SEED.get("business").capabilities().contains(Roles.CAN_BUILD_LINK_INDEX));
        assertFalse(Roles.SEED.get("developer").capabilities().contains(Roles.CAN_BUILD_LINK_INDEX));
    }
}
