package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A Postgres snapshot's Run now, redesigned 2026-11-05 (operator): EXPORT the table into a temporary folder under the data
 * root (never the inbox), MOVE the complete file into the inbox, and let the ordinary import path take it; and no 30 s
 * poll for a snapshot. These run with no database: the snapshot is an ordinary Parquet import given snapshot settings, and
 * the export's hand-over ({@link OnboardImport#addStaged} with {@code moveIntoInbox}) is driven as
 * {@link PostgresSnapshot#refresh} drives it after its {@code COPY … TO}. The real-Postgres half is
 * {@code ControlApiPostgresSnapshotTest} (needs {@code INSPECTO_TEST_PG_URL}). Synthetic fixtures only.
 */
class ControlApiSnapshotExportTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<String> ids = new ArrayList<>();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void cleanUp() {
        Authenticators.forTest(null);
        InboxWatch.pausedForTest = false;
        Path data = Path.of("database").toAbsolutePath();
        for (String id : ids) {
            OnboardRoutes.deleteTree(data.resolve(id));
            OnboardRoutes.deleteTree(data.resolve("inbox").resolve(id));
        }
        OnboardRoutes.deleteTree(data.resolve(OnboardRoutes.DRAFTS));
        for (Path p : List.of(data.resolve("inbox"), data))
            try {
                Files.deleteIfExists(p);
            } catch (java.io.IOException notEmpty) {
                // another fixture's data lives there
            }
    }

    private Ctx open(Path cfg, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
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

    private HttpResponse<String> send(Ctx c, String method, String path, byte[] body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        b = body == null ? (method.equals("GET") ? b.GET() : b.method(method, BodyPublishers.noBody()))
                : b.method(method, BodyPublishers.ofByteArray(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    private static String error(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body()).path("error").path("message").asText();
    }

    private static final Path DATA = Path.of("database").toAbsolutePath().normalize();

    /** {@code n} rows {@code id, channel, amount} as Parquet: channel cycles mobile / branch / online. */
    private static Path parquet(Path target, int n, int first) throws Exception {
        Files.createDirectories(target.getParent());
        Path src = target.resolveSibling(target.getFileName() + ".csv");
        StringBuilder sb = new StringBuilder("row_id,channel,amount\n");
        String[] ch = {"mobile", "branch", "online"};
        for (int i = 0; i < n; i++) sb.append("R").append(first + i).append(',').append(ch[i % 3]).append(',').append(i).append(".5\n");
        Files.writeString(src, sb.toString());
        com.gamma.util.DuckDbUtil.loadDriver();
        try (java.sql.Connection conn = com.gamma.util.DuckDbUtil.openInMemory(null, List.of(target.getParent().toAbsolutePath()), null);
             java.sql.Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT row_id, channel, CAST(amount AS DOUBLE) AS amount FROM read_csv('"
                    + src.toAbsolutePath().toString().replace('\\', '/') + "', header = true, all_varchar = true)) TO '"
                    + target.toAbsolutePath().toString().replace('\\', '/') + "' (FORMAT parquet)");
        }
        Files.delete(src);
        return target;
    }

    /**
     * A committed Parquet import made a snapshot the way a Postgres commit makes one: snapshot settings next to its Pipeline
     * and {@code importSource: postgres} on its entry. The password is a reference to a variable no environment sets, so a
     * Run now fails at connecting - before anything is exported.
     */
    private String snapshot(Ctx c, Path tmp, List<Map<String, Object>> filter) throws Exception {
        JsonNode d = data(send(c, "POST", "/onboard/files?name=table.parquet&kind=stream",
                Files.readAllBytes(parquet(tmp.resolve("first.parquet"), 30, 1))), 200);
        String id = "snapx_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        ids.add(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", id);
        if (filter != null) body.put("filter", filter);
        data(send(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", JSON.writeValueAsBytes(body)), 200);
        Map<String, Object> conn = Map.of("host", "127.0.0.1", "port", 5432, "database", "nowhere", "user", "onboard_snap_ro",
                "password", "${ENV:INSPECTO_PG_NEVER_SET_" + id.toUpperCase() + "}");
        PostgresSnapshot.write(PostgresSnapshot.configFile(c.root(), id),
                PostgresSnapshot.config(Map.of("connection", conn, "schema", "public", "table", "transfers")));
        ComponentStore registry = new ComponentStore(c.root().resolve("registry"));
        Map<String, Object> entry = new LinkedHashMap<>(registry.get("dataset", id).orElseThrow().content());
        entry.remove("name");
        entry.put("importSource", PostgresSnapshot.SOURCE);
        registry.write("dataset", id, entry);
        return id;
    }

    private static String listing(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) return "[]";
        try (Stream<Path> w = Files.list(dir)) {
            return w.map(p -> p.getFileName().toString()).sorted().toList().toString();
        }
    }

    // ── Run now goes through the inbox path ─────────────────────────────────────────────────────────────────

    @Test
    void runNowOnASnapshotMovesTheCompleteExportIntoTheInboxAndImportsItLikeAnUpload(@TempDir Path cfg, @TempDir Path root,
                                                                                    @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = snapshot(c, tmp, List.of(Map.of("column", "channel", "op", "ne", "value", "branch")));
            assertEquals(20, OnboardImport.countRows(DATA.resolve(id).resolve("database")), "30 rows, a third of them branch");

            // what refresh does after its COPY … TO: the complete export, staged under the drafts folder, handed over
            Path stage = DATA.resolve(OnboardRoutes.DRAFTS).resolve("a" + UUID.randomUUID().toString().replace("-", ""));
            Path export = parquet(stage.resolve(OnboardRoutes.storedName("transfers.parquet")), 45, 100);
            Map<String, Object> content = new ComponentStore(root.resolve("registry")).get("dataset", id).orElseThrow().content();
            String key = root + "|" + id;
            assertTrue(OnboardImport.IN_FLIGHT.add(key));
            Path in = OnboardImport.inbox(DATA, id);
            long full = Files.size(export);
            List<String> seenInInbox = java.util.Collections.synchronizedList(new ArrayList<>());
            Thread watcher = Thread.ofVirtual().start(() -> {   // while the run lasts, nothing half-written shows in the inbox
                while (!Thread.currentThread().isInterrupted()) {
                    try (Stream<Path> w = Files.isDirectory(in) ? Files.list(in) : Stream.empty()) {
                        w.forEach(p -> {
                            try {
                                long size = Files.size(p);
                                if (size < full) seenInInbox.add(p.getFileName() + "@" + size);
                            } catch (java.io.IOException gone) {
                                // taken by the run between the listing and the size
                            }
                        });
                    } catch (java.io.IOException | RuntimeException racing) {
                        // the run moves files while we list
                    }
                }
            });
            @SuppressWarnings("unchecked")
            Map<String, Object> s = (Map<String, Object>) OnboardImport.addStaged(
                    new OnboardImport.Space(root, DATA, c.svc, "tester"), content, id, key, stage, export, "transfers.parquet",
                    "replace", null, true);
            watcher.interrupt();
            watcher.join();

            assertEquals(30, OnboardImport.countRows(DATA.resolve(id).resolve("database")),
                    "replace everything, through the row filter: 45 rows, 15 of them branch - " + s);
            assertEquals(15L, ((Number) s.get("rowsFilteredOut")).longValue(), String.valueOf(s));
            assertTrue((Boolean) s.get("replaced"), String.valueOf(s));
            assertEquals("[]", listing(in), "the inbox is empty again: the moved-in export was taken");
            assertFalse(Files.exists(stage), "the temporary export folder is gone");
            assertEquals(List.of(), seenInInbox, "the inbox only ever held the complete file");
            List<Map<String, Object>> lines = DatasetArrivals.sidecarLines(DATA, id);
            Map<String, Object> last = lines.get(lines.size() - 1);
            assertEquals("replace", last.get("mode"), "the arrival is recorded as a replace: " + lines);
            assertEquals(15, ((Number) last.get("rowsFilteredOut")).intValue(), "and how many rows the filter left out: " + lines);
        }
    }

    // ── a failed or half-written export never reaches the inbox ─────────────────────────────────────────────

    @Test
    void aFailedExportLeavesTheInboxTheDataAndNoTemporaryFolderBehind(@TempDir Path cfg, @TempDir Path root, @TempDir Path tmp)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = snapshot(c, tmp, null);
            Path db = DATA.resolve(id).resolve("database");
            assertEquals(30, OnboardImport.countRows(db));
            String drafts = listing(DATA.resolve(OnboardRoutes.DRAFTS));
            HttpResponse<String> run = send(c, "POST", "/datasets/" + id + "/run", null);
            assertNotEquals(200, run.statusCode(), run.body());
            assertTrue(error(run).contains("INSPECTO_PG_NEVER_SET"), "the export failed at connecting: " + run.body());
            assertEquals("[]", listing(OnboardImport.inbox(DATA, id)), "nothing reached the inbox");
            assertEquals(drafts, listing(DATA.resolve(OnboardRoutes.DRAFTS)), "no temporary export folder left behind");
            assertEquals(30, OnboardImport.countRows(db), "the data is unchanged");
            assertFalse(OnboardImport.IN_FLIGHT.contains(root + "|" + id), "the import is free again");
        }
    }

    @Test
    void anExportStillBeingWrittenIsInvisibleToRunNowAndToThePoll(@TempDir Path cfg, @TempDir Path root, @TempDir Path tmp)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            // an ordinary (not snapshot) Parquet import, watched: its inbox is read by Run now and by the 30 s poll
            JsonNode d = data(send(c, "POST", "/onboard/files?name=table.parquet&kind=stream",
                    Files.readAllBytes(parquet(tmp.resolve("first.parquet"), 30, 1))), 200);
            String id = "plain_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            ids.add(id);
            data(send(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit",
                    ("{\"name\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8)), 200);
            InboxWatch.pausedForTest = true;
            data(send(c, "PUT", "/datasets/" + id + "/watch", "{\"watch\":true}".getBytes(StandardCharsets.UTF_8)), 200);

            // where an export is written while it runs: the drafts folder of the data root, a half-written Parquet file
            Path stage = DATA.resolve(OnboardRoutes.DRAFTS).resolve("a" + UUID.randomUUID().toString().replace("-", ""));
            Path whole = parquet(stage.resolve("whole.parquet"), 50, 500);
            byte[] bytes = Files.readAllBytes(whole);
            Path half = stage.resolve(OnboardRoutes.storedName("transfers.parquet"));
            Files.write(half, java.util.Arrays.copyOf(bytes, bytes.length / 2));
            Files.delete(whole);

            for (int i = 0; i < 3; i++) InboxWatch.tick();
            JsonNode now = data(send(c, "POST", "/datasets/" + id + "/run", null), 200);
            assertEquals(0, now.get("rowsIn").asLong(), "Run now found nothing waiting: " + now);
            assertEquals(30, OnboardImport.countRows(DATA.resolve(id).resolve("database")), "nothing of the half file was imported");
            assertTrue(Files.exists(half), "the half-written export was not touched");
            assertTrue(DatasetArrivals.sidecarLines(DATA, id).stream().noneMatch(l -> String.valueOf(l.get("landed")).contains("transfers")),
                    "and no arrival names it");
        }
    }

    // ── a snapshot is never polled ──────────────────────────────────────────────────────────────────────────

    @Test
    void aSnapshotCannotBeWatchedAndTheFeedOffersNoToggle(@TempDir Path cfg, @TempDir Path root, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = snapshot(c, tmp, null);
            HttpResponse<String> on = send(c, "PUT", "/datasets/" + id + "/watch", "{\"watch\":true}".getBytes(StandardCharsets.UTF_8));
            assertEquals(422, on.statusCode(), on.body());
            assertTrue(error(on).contains("not polled"), on.body());
            assertNotEquals(Boolean.TRUE, new ComponentStore(root.resolve("registry")).get("dataset", id).orElseThrow().content()
                    .get(InboxWatch.WATCH_KEY), "nothing was switched on");
            JsonNode feed = data(send(c, "GET", "/datasets/" + id + "/feed", null), 200);
            assertFalse(feed.get("watchable").asBoolean(), feed.toString());
            assertFalse(feed.get("watch").asBoolean(), feed.toString());

            // an entry switched on before the redesign (watchInbox: true) is not polled either
            ComponentStore registry = new ComponentStore(root.resolve("registry"));
            Map<String, Object> entry = new LinkedHashMap<>(registry.get("dataset", id).orElseThrow().content());
            entry.remove("name");
            entry.put(InboxWatch.WATCH_KEY, true);
            registry.write("dataset", id, entry);
            assertFalse(data(send(c, "GET", "/datasets/" + id + "/feed", null), 200).get("watch").asBoolean(),
                    "a snapshot never reads as watched");
            data(send(c, "PUT", "/datasets/" + id + "/watch", "{\"watch\":false}".getBytes(StandardCharsets.UTF_8)), 200);
        }
    }
}
