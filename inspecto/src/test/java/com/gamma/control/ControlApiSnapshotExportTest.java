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
     * A committed Parquet import made a snapshot the way a Postgres commit makes one: a saved {@code postgres} Connection
     * (saved through {@code POST /connections}, so the scheme's own check passes it), snapshot settings naming it next to
     * its Pipeline, and {@code importSource: postgres} on its entry. The password is a reference to a variable no
     * environment sets, so a Run now fails at connecting - before anything is exported.
     */
    private String snapshot(Ctx c, Path tmp, List<Map<String, Object>> filter) throws Exception {
        return snapshot(c, tmp, filter, false);
    }

    /** {@code legacy}: the shape a snapshot committed before BASIC-ELT-PROFILE-1 BE-4 has - its connection inline. */
    private String snapshot(Ctx c, Path tmp, List<Map<String, Object>> filter, boolean legacy) throws Exception {
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
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("source", PostgresSnapshot.SOURCE);
        if (legacy) {
            settings.put("connection", conn);
            settings.put("schema", "public");
            settings.put("table", "transfers");
        } else {
            String connId = PostgresSnapshot.connectionId(id);
            Map<String, Object> saved = new LinkedHashMap<>();
            saved.put("id", connId);
            saved.put("connector", "postgres");
            saved.put("host", conn.get("host"));
            saved.put("port", conn.get("port"));
            saved.put("database", conn.get("database"));
            saved.put("username", conn.get("user"));
            saved.put("password", conn.get("password"));
            saved.put("options", Map.of("schema", "public", "table", "transfers"));
            data(send(c, "POST", "/connections", JSON.writeValueAsBytes(saved)), 200);
            settings.put("connection", connId);
        }
        PostgresSnapshot.write(PostgresSnapshot.configFile(c.root(), id), settings);
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
            // both shapes: a saved Connection (BE-4) and the inline connection of a snapshot committed before it
            for (boolean legacy : List.of(false, true)) {
                String id = snapshot(c, tmp, null, legacy);
                Path db = DATA.resolve(id).resolve("database");
                assertEquals(30, OnboardImport.countRows(db));
                String drafts = listing(DATA.resolve(OnboardRoutes.DRAFTS));
                HttpResponse<String> run = send(c, "POST", "/datasets/" + id + "/run", null);
                assertEquals(422, run.statusCode(), run.body());
                assertTrue(error(run).contains("INSPECTO_PG_NEVER_SET"), "the export failed at connecting: " + run.body());
                assertEquals("[]", listing(OnboardImport.inbox(DATA, id)), "nothing reached the inbox");
                assertEquals(drafts, listing(DATA.resolve(OnboardRoutes.DRAFTS)), "no temporary export folder left behind");
                assertEquals(30, OnboardImport.countRows(db), "the data is unchanged");
                assertFalse(OnboardImport.IN_FLIGHT.contains(root + "|" + id), "the import is free again");
            }
        }
    }

    // ── O7: the snapshot's Pipeline reads Postgres itself ───────────────────────────────────────────────────

    private static Path pipelineFile(Path root, String id) {
        return root.resolve(OnboardImport.CONFIG_DIR).resolve(id + "_pipeline.toon");
    }

    private static void assertSwitched(Ctx c, String id) throws Exception {
        String connId = PostgresSnapshot.connectionId(id);
        com.gamma.etl.PipelineConfig cfg = c.svc().configFor(id).orElseThrow();
        assertEquals("postgres", cfg.collector().connector(), "the Pipeline reads Postgres itself");
        assertEquals(connId, cfg.collector().connection(), "through the snapshot's saved Connection");
        assertEquals(com.gamma.etl.PipelineConfig.ArrivalLoad.Mode.REPLACE, cfg.arrivalLoad().mode(),
                "a run outside Add data replaces the rows instead of adding a second copy");
        assertFalse(cfg.processing().duplicateCheckEnabled(), "the Pipeline never refuses its own re-export");
        assertEquals(connId, com.gamma.util.ToonHelper.load(PostgresSnapshot.configFile(c.root(), id).toString()).get("connection"),
                "the snapshot settings name the Connection");
    }

    /**
     * O7: Run now switches the snapshot's Pipeline to {@code collector.connector: postgres} before it exports - an old
     * snapshot (its connection inline) migrates first: its Connection {@code <id>_postgres} is saved from the inline block
     * and {@code _snapshot.toon} rewritten to name it. Here the export then fails (no database), so the switch is seen on
     * its own; a second Run now changes nothing (the switch is idempotent).
     */
    @Test
    void runNowSwitchesTheSnapshotsPipelineOnceMigratingAnOldSnapshotFirst(@TempDir Path cfg, @TempDir Path root, @TempDir Path tmp)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            for (boolean legacy : List.of(true, false)) {
                String id = snapshot(c, tmp, null, legacy);
                String connId = PostgresSnapshot.connectionId(id);
                Path connFile = root.resolve(connId + "_connection.toon");
                assertEquals(!legacy, Files.exists(connFile));
                assertTrue(Files.readString(pipelineFile(root, id)).contains("connector: local"), "before: the import's own inbox");

                HttpResponse<String> run = send(c, "POST", "/datasets/" + id + "/run", null);
                assertEquals(422, run.statusCode(), run.body());
                assertTrue(error(run).contains("INSPECTO_PG_NEVER_SET"), "Run now's own wording for a failed copy: " + run.body());
                assertSwitched(c, id);
                String saved = Files.readString(connFile);
                assertTrue(saved.contains("postgres") && saved.contains("transfers") && saved.contains("${ENV:INSPECTO_PG_NEVER_SET_"),
                        "the Connection holds the inline block's host, table and reference: " + saved);
                assertEquals(200, send(c, "GET", "/connections/" + connId, null).statusCode(), "and is registered");
                assertEquals(30, OnboardImport.countRows(DATA.resolve(id).resolve("database")), "the data is unchanged");

                byte[] pipeline = Files.readAllBytes(pipelineFile(root, id)), settings = Files.readAllBytes(PostgresSnapshot.configFile(root, id)),
                        connection = Files.readAllBytes(connFile);
                assertEquals(422, send(c, "POST", "/datasets/" + id + "/run", null).statusCode());
                assertArrayEquals(pipeline, Files.readAllBytes(pipelineFile(root, id)), "a switched Pipeline is left as it is");
                assertArrayEquals(settings, Files.readAllBytes(PostgresSnapshot.configFile(root, id)));
                assertArrayEquals(connection, Files.readAllBytes(connFile));
                assertFalse(OnboardImport.IN_FLIGHT.contains(root + "|" + id), "the import is free again");
            }
        }
    }

    /** O7: a Connection already saved under the snapshot's name for another table is never taken over by the migration. */
    @Test
    void theMigrationNeverTakesOverAnotherConnectionOfTheSnapshotsName(@TempDir Path cfg, @TempDir Path root, @TempDir Path tmp)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = snapshot(c, tmp, null, true);
            String connId = PostgresSnapshot.connectionId(id);
            Map<String, Object> other = new LinkedHashMap<>();
            other.put("id", connId);
            other.put("connector", "postgres");
            other.put("host", "127.0.0.1");
            other.put("port", 5432);
            other.put("database", "nowhere");
            other.put("username", "onboard_snap_ro");
            other.put("password", "${ENV:INSPECTO_PG_NEVER_SET_" + id.toUpperCase() + "}");
            other.put("options", Map.of("schema", "public", "table", "other_table"));
            data(send(c, "POST", "/connections", JSON.writeValueAsBytes(other)), 200);
            byte[] theirs = Files.readAllBytes(root.resolve(connId + "_connection.toon"));
            byte[] pipeline = Files.readAllBytes(pipelineFile(root, id));

            HttpResponse<String> run = send(c, "POST", "/datasets/" + id + "/run", null);
            assertEquals(422, run.statusCode(), "Run now still copies through the inline block: " + run.body());
            assertTrue(error(run).contains("INSPECTO_PG_NEVER_SET"), run.body());
            assertArrayEquals(theirs, Files.readAllBytes(root.resolve(connId + "_connection.toon")), "the other Connection is untouched");
            assertInstanceOf(Map.class, com.gamma.util.ToonHelper.load(PostgresSnapshot.configFile(root, id).toString()).get("connection"),
                    "the snapshot keeps its inline connection");
            assertArrayEquals(pipeline, Files.readAllBytes(pipelineFile(root, id)), "and its Pipeline is not switched");
        }
    }

    /** O7: a snapshot commit runs its draft's copy as before, then switches its Pipeline (no database: the draft is given its snapshot). */
    @Test
    @SuppressWarnings("unchecked")
    void aSnapshotCommitImportsItsCopyThenSwitchesThePipeline(@TempDir Path cfg, @TempDir Path root, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, root)) {
            JsonNode d = data(send(c, "POST", "/onboard/files?name=transfers.parquet&kind=stream",
                    Files.readAllBytes(parquet(tmp.resolve("first.parquet"), 30, 1))), 200);
            String id = "snapc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            ids.add(id);
            // what POST /onboard/postgres stores beside its copy
            Path draftJson = DATA.resolve(OnboardRoutes.DRAFTS).resolve(d.get("draftId").asText()).resolve("draft.json");
            Map<String, Object> draft = JSON.readValue(Files.readAllBytes(draftJson), LinkedHashMap.class);
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("source", PostgresSnapshot.SOURCE);
            snap.put("connection", Map.of("host", "127.0.0.1", "port", 5432, "database", "nowhere", "user", "onboard_snap_ro",
                    "password", "${ENV:INSPECTO_PG_NEVER_SET_" + id.toUpperCase() + "}"));
            snap.put("schema", "public");
            snap.put("table", "transfers");
            draft.put("snapshot", snap);
            Files.write(draftJson, JSON.writeValueAsBytes(draft));

            JsonNode made = data(send(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit",
                    ("{\"name\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8)), 200);
            assertEquals(30, made.get("rowsAfter").asLong(), "the first run imports the draft's copy: " + made);
            assertSwitched(c, id);
            assertEquals("postgres", new ComponentStore(root.resolve("registry")).get("dataset", id).orElseThrow().content().get("importSource"));
            assertFalse(OnboardImport.IN_FLIGHT.contains(root + "|" + id), "the import is free again");
        }
    }

    /**
     * O7: once switched, Run now's own run takes only the export it checked - the run fetches nothing itself - and replaces
     * the rows; a run not started by Run now (the Pipeline run outside Add data) replaces them too, through the Pipeline's
     * {@code output.load: replace}, instead of adding a second full copy.
     */
    @Test
    void aSwitchedSnapshotsRunsReplaceItsRowsWhoeverStartsThem(@TempDir Path cfg, @TempDir Path root, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = snapshot(c, tmp, null);
            assertEquals(422, send(c, "POST", "/datasets/" + id + "/run", null).statusCode());   // switches; no database to copy
            assertSwitched(c, id);
            Path db = DATA.resolve(id).resolve("database");
            assertEquals(30, OnboardImport.countRows(db));
            List<String> fetched = java.util.Collections.synchronizedList(new ArrayList<>());
            java.util.function.Consumer<com.gamma.audit.Event> tap = e -> {
                if (com.gamma.audit.EventType.FILE_DISCOVERED.equals(e.type()) && id.equals(e.pipeline()))
                    fetched.add(String.valueOf(e.attributes().get("file")));
            };
            com.gamma.event.EventLog.global().addSubscriber(tap);
            try {
                // Run now, after its COPY … TO: the complete export handed to the replace path
                Path stage = DATA.resolve(OnboardRoutes.DRAFTS).resolve("a" + UUID.randomUUID().toString().replace("-", ""));
                Path export = parquet(stage.resolve(OnboardRoutes.storedName("transfers.parquet")), 45, 100);
                Map<String, Object> content = new ComponentStore(root.resolve("registry")).get("dataset", id).orElseThrow().content();
                String key = root + "|" + id;
                assertTrue(OnboardImport.IN_FLIGHT.add(key));
                Map<String, Object> s = (Map<String, Object>) OnboardImport.addStaged(new OnboardImport.Space(root, DATA, c.svc, "tester"),
                        content, id, key, stage, export, "transfers.parquet", "replace", null, true);
                assertEquals(45, OnboardImport.countRows(db), "the export replaced the rows: " + s);
                assertEquals(List.of(), fetched, "Run now's run fetched nothing itself: the checked export is its one copy");

                // a run not started by Run now: the engine fetches (here it cannot: no database) and takes what the inbox holds
                parquet(OnboardImport.inbox(DATA, id).resolve("transfers_zzexport.parquet"), 12, 500);
                c.svc().runPipeline(id).orElseThrow();
                assertFalse(fetched.isEmpty(), "a run outside Add data does fetch the table itself");
                assertEquals(12, OnboardImport.countRows(db), "output.load: replace - not 45 + 12");
            } finally {
                com.gamma.event.EventLog.global().removeSubscriber(tap);
            }
        }
    }

    /** The thread is waiting for a Pipeline's run lock (the service's {@code RunLease.acquire}: the guard or the db lease). */
    private static boolean waitsForTheRunLock(Thread t) {
        if (t.getState() == Thread.State.RUNNABLE || t.getState() == Thread.State.TERMINATED) return false;
        for (StackTraceElement f : t.getStackTrace())
            if ((f.getClassName().endsWith("PipelineRunGuard") || f.getClassName().endsWith("RunLease")) && f.getMethodName().equals("acquire"))
                return true;
        return false;
    }

    /**
     * O7 review finding (HIGH): while Run now holds the snapshot, its run-scoped {@code replace} is open. A run of the
     * Pipeline started then by anyone else (here a synchronous run, as {@code POST /pipelines/{id}/run} starts one) is
     * refused before it does anything: had it gone ahead, it would have committed its own copy into the Dataset while the
     * choice was open, the choice would have taken its rows as Run now's own, and Run now's replace would have kept them -
     * 57 rows, two copies, behind a successful Run now. The interleaving is forced: the other run fetches (a file it lands,
     * as its fetch would) only once its pre-run steps let it go ahead, and pauses at its commit until Run now waits for the
     * run lock it holds.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aRunStartedWhileRunNowHoldsTheSnapshotIsRefusedSoItsCopyIsNeverKept(@TempDir Path cfg, @TempDir Path root, @TempDir Path tmp)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = snapshot(c, tmp, null);
            assertEquals(422, send(c, "POST", "/datasets/" + id + "/run", null).statusCode());   // switches; no database to copy
            assertSwitched(c, id);
            Path db = DATA.resolve(id).resolve("database");
            assertEquals(30, OnboardImport.countRows(db));
            Path in = OnboardImport.inbox(DATA, id);
            Thread[] other = new Thread[1];
            java.util.concurrent.atomic.AtomicBoolean fetches = new java.util.concurrent.atomic.AtomicBoolean(true);
            java.util.concurrent.atomic.AtomicBoolean pauses = new java.util.concurrent.atomic.AtomicBoolean(true);
            java.util.concurrent.CountDownLatch atCommit = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch runNowWaits = new java.util.concurrent.CountDownLatch(1);
            CollectorService.preRunStep("test.o7.fetch", 900, (svc, p, scheduled) -> {
                if (id.equals(p) && Thread.currentThread() == other[0] && fetches.compareAndSet(true, false))
                    try {
                        parquet(in.resolve("transfers_zzother.parquet"), 12, 500);   // the other run's own copy
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                return CollectorService.PreRun.GO;
            });
            CollectorService.commitStep("test.o7.pause", 10, CollectorService.CommitPhase.COMMIT, (svc, e) -> {
                if (id.equals(e.pipeline()) && pauses.compareAndSet(true, false)) {
                    atCommit.countDown();
                    try {
                        runNowWaits.await(60, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException stop) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            String key = root + "|" + id;
            try {
                assertTrue(OnboardImport.IN_FLIGHT.add(key), "Run now has claimed the snapshot (refresh, before its export)");
                java.util.concurrent.atomic.AtomicReference<Throwable> otherFailed = new java.util.concurrent.atomic.AtomicReference<>();
                other[0] = Thread.ofPlatform().start(() -> {
                    try {
                        c.svc().runPipeline(id);
                    } catch (Throwable t) {
                        otherFailed.set(t);
                    }
                });
                long until = System.currentTimeMillis() + 60_000;
                while (atCommit.getCount() > 0 && other[0].isAlive() && System.currentTimeMillis() < until) Thread.sleep(20);
                pauses.set(false);

                // Run now, after its COPY … TO: the complete export handed to the replace path
                Path stage = DATA.resolve(OnboardRoutes.DRAFTS).resolve("a" + UUID.randomUUID().toString().replace("-", ""));
                Path export = parquet(stage.resolve(OnboardRoutes.storedName("transfers.parquet")), 45, 100);
                Map<String, Object> content = new ComponentStore(root.resolve("registry")).get("dataset", id).orElseThrow().content();
                java.util.concurrent.atomic.AtomicReference<Throwable> runNowFailed = new java.util.concurrent.atomic.AtomicReference<>();
                Thread runNow = Thread.ofPlatform().start(() -> {
                    try {
                        OnboardImport.addStaged(new OnboardImport.Space(root, DATA, c.svc, "tester"), content, id, key, stage, export,
                                "transfers.parquet", "replace", null, true);
                    } catch (Throwable t) {
                        runNowFailed.set(t);
                    }
                });
                if (atCommit.getCount() == 0)   // the other run went ahead: let it commit only once Run now's choice is open
                    while (!waitsForTheRunLock(runNow) && runNow.isAlive() && System.currentTimeMillis() < until) Thread.sleep(20);
                runNowWaits.countDown();
                other[0].join(120_000);
                runNow.join(120_000);

                assertNull(runNowFailed.get(), () -> "Run now: " + runNowFailed.get());
                assertEquals(45, OnboardImport.countRows(db), "one copy - Run now's export - never 45 + the other run's 12");
                assertNotNull(otherFailed.get(), "the other run was refused while Run now held the snapshot");
                assertTrue(otherFailed.get().getMessage().contains("being imported or changed right now"), otherFailed.get().toString());
                assertTrue(fetches.get(), "and it fetched nothing: it was refused before its acquisition");
            } finally {
                // no removal seam outside the service package: the test steps become no-ops
                CollectorService.preRunStep("test.o7.fetch", 900, (svc, p, scheduled) -> CollectorService.PreRun.GO);
                CollectorService.commitStep("test.o7.pause", 10, CollectorService.CommitPhase.COMMIT, (svc, e) -> { });
                runNowWaits.countDown();
                OnboardImport.IN_FLIGHT.remove(key);
            }
        }
    }

    /** O7 review finding: under an approval policy holding Pipeline changes, Run now migrates nothing (no half-migration). */
    @Test
    void anApprovalPolicyOverPipelinesStopsTheWholeSwitchBeforeAnythingIsWritten(@TempDir Path cfg, @TempDir Path root, @TempDir Path tmp)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = snapshot(c, tmp, null, true);
            Path connFile = root.resolve(PostgresSnapshot.connectionId(id) + "_connection.toon");
            byte[] settings = Files.readAllBytes(PostgresSnapshot.configFile(root, id)), pipeline = Files.readAllBytes(pipelineFile(root, id));
            Files.writeString(root.resolve(ApprovalPolicy.FILE), "approval:\n  pipeline:\n    required: true\n");

            HttpResponse<String> run = send(c, "POST", "/datasets/" + id + "/run", null);
            assertEquals(422, run.statusCode(), "Run now still copies through the inline block: " + run.body());
            assertTrue(error(run).contains("INSPECTO_PG_NEVER_SET"), run.body());
            assertFalse(Files.exists(connFile), "no Connection saved");
            assertArrayEquals(settings, Files.readAllBytes(PostgresSnapshot.configFile(root, id)), "the snapshot settings untouched");
            assertArrayEquals(pipeline, Files.readAllBytes(pipelineFile(root, id)), "the Pipeline untouched");
        }
    }

    /**
     * B13: an export whose every row the import's row filter leaves out adds nothing: the run sets it aside and Replace
     * everything is refused, the older rows kept (the older rows are still there during the run now).
     */
    @Test
    void aReplaceThatAddsNoRowIsRefusedAndTheOlderRowsStay(@TempDir Path cfg, @TempDir Path root, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = snapshot(c, tmp, List.of(Map.of("column", "channel", "op", "ne", "value", "branch")));
            Path db = DATA.resolve(id).resolve("database");
            assertEquals(20, OnboardImport.countRows(db));
            Path stage = DATA.resolve(OnboardRoutes.DRAFTS).resolve("a" + UUID.randomUUID().toString().replace("-", ""));
            Path export = stage.resolve(OnboardRoutes.storedName("transfers.parquet"));
            Files.createDirectories(stage);
            com.gamma.util.DuckDbUtil.loadDriver();
            try (java.sql.Connection conn = com.gamma.util.DuckDbUtil.openInMemory(null, List.of(stage.toAbsolutePath()), null);
                 java.sql.Statement st = conn.createStatement()) {
                st.execute("COPY (SELECT 'B' || g AS row_id, 'branch' AS channel, CAST(g AS DOUBLE) AS amount FROM range(9) t(g)) TO '"
                        + export.toAbsolutePath().toString().replace('\\', '/') + "' (FORMAT parquet)");
            }
            Map<String, Object> content = new ComponentStore(root.resolve("registry")).get("dataset", id).orElseThrow().content();
            String key = root + "|" + id;
            assertTrue(OnboardImport.IN_FLIGHT.add(key));
            com.gamma.spi.auth.ApiException refused = assertThrows(com.gamma.spi.auth.ApiException.class, () -> OnboardImport.addStaged(
                    new OnboardImport.Space(root, DATA, c.svc, "tester"), content, id, key, stage, export, "transfers.parquet", "replace", null, true));
            assertEquals(422, refused.status, refused.getMessage());
            assertTrue(refused.getMessage().contains("nothing was imported"), refused.getMessage());
            assertEquals(20, OnboardImport.countRows(db), "nothing was replaced");
        }
    }

    /**
     * O7 review finding: the judge itself. A Replace everything whose run commits no row and sets nothing aside (its file is
     * never taken) leaves every older row in place during the run, so "the rows after" is not zero: the run must be judged
     * by the rows it added (after + replaced - before), not by the rows left. The HTTP paths found so far are refused before
     * the run (no rows, other columns) or set the file aside, so the run is driven here as Add data's job drives it.
     */
    @Test
    void aReplaceWhoseRunCommitsNothingIsJudgedByTheRowsItAdded(@TempDir Path cfg, @TempDir Path root, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = snapshot(c, tmp, null);
            Path db = DATA.resolve(id).resolve("database");
            assertEquals(30, OnboardImport.countRows(db));
            Map<String, Object> content = new ComponentStore(root.resolve("registry")).get("dataset", id).orElseThrow().content();
            OnboardImport.Job job = new OnboardImport.Job(new OnboardImport.Space(root, DATA, c.svc, "tester"), id, "stream",
                    null, "gone.parquet", content, true);
            job.landed = tmp.resolve("gone.parquet");   // landed nowhere the run reads: nothing taken, nothing set aside
            com.gamma.spi.auth.ApiException refused = assertThrows(com.gamma.spi.auth.ApiException.class, () -> job.runChecked(30));
            assertEquals(422, refused.status, refused.getMessage());
            assertTrue(refused.getMessage().contains("nothing was imported"), refused.getMessage());
            assertEquals(30, OnboardImport.countRows(db));
        }
    }

    /**
     * Operator ruling 2026-11-20: a Postgres snapshot has no feed - Keep this up to date and Stop the feed are refused with
     * 422 and a path-free reason, as Watch this folder is, and nothing is written. Every gate of both routes, in order.
     */
    @Test
    void aSnapshotRefusesKeepThisUpToDateAndStopTheFeedBehindEveryGate(@TempDir Path cfg, @TempDir Path root, @TempDir Path tmp)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = snapshot(c, tmp, null);
            String path = "/datasets/" + id + "/feed";
            byte[] pipeline = Files.readAllBytes(pipelineFile(root, id));
            String admin = "Bearer admin", uploader = "Bearer uploader", analyst = "Bearer analyst";
            Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
                case "Bearer uploader" -> java.util.Optional.of(new com.gamma.spi.auth.Subject("uploader-1", java.util.Set.of("canUploadData")));
                case "Bearer analyst" -> java.util.Optional.of(new com.gamma.spi.auth.Subject("analyst-1", java.util.Set.of("canManageIncidents")));
                case "Bearer admin" -> java.util.Optional.of(new com.gamma.spi.auth.Subject("admin-1", java.util.Set.of("canAdminister", "canUploadData")));
                default -> java.util.Optional.empty();
            });
            try {
                for (String m : List.of("PUT", "DELETE")) {
                    byte[] body = m.equals("PUT") ? "{\"kind\":\"folder\"}".getBytes(StandardCharsets.UTF_8) : null;
                    assertEquals(401, authed(c, m, path, body, null).statusCode(), m);
                    assertEquals(403, authed(c, m, path, body, analyst).statusCode(), m);
                }
                data(authed(c, "PUT", "/settings/data", "{\"selfImport\":false}".getBytes(StandardCharsets.UTF_8), admin), 200);
                for (String m : List.of("PUT", "DELETE"))
                    assertEquals(403, authed(c, m, path, m.equals("PUT") ? "{\"kind\":\"folder\"}".getBytes(StandardCharsets.UTF_8) : null,
                            uploader).statusCode(), m + ": Add data is off");
                data(authed(c, "PUT", "/settings/data", "{\"selfImport\":true}".getBytes(StandardCharsets.UTF_8), admin), 200);
                assertEquals(404, authed(c, "PUT", "/datasets/no_such_import/feed", "{\"kind\":\"folder\"}".getBytes(StandardCharsets.UTF_8),
                        uploader).statusCode());
                assertEquals(404, authed(c, "DELETE", "/datasets/no_such_import/feed", null, uploader).statusCode());

                // Basic keeps nothing up to date at all (503, first); Stop the feed is refused for the snapshot itself
                com.gamma.etl.EditionFeatures.overrideForTest(null);
                assertEquals(503, authed(c, "PUT", path, "{\"kind\":\"folder\"}".getBytes(StandardCharsets.UTF_8), uploader).statusCode());
                List<HttpResponse<String>> refusals = new ArrayList<>();
                refusals.add(authed(c, "DELETE", path, null, uploader));
                // Standard: both refused for the snapshot, before the body is read
                com.gamma.etl.EditionFeatures.overrideForTest(java.util.Set.of(OnboardImport.AUTOMATION_FEATURE));
                refusals.add(authed(c, "PUT", path, "{\"kind\":\"folder\"}".getBytes(StandardCharsets.UTF_8), uploader));
                refusals.add(authed(c, "PUT", path, "{}".getBytes(StandardCharsets.UTF_8), uploader));
                refusals.add(authed(c, "DELETE", path, null, uploader));
                for (HttpResponse<String> r : refusals) {
                    assertEquals(422, r.statusCode(), r.body());
                    assertTrue(error(r).contains("Postgres snapshot has no feed"), r.body());
                    for (String leak : List.of(root.toString(), root.toString().replace('\\', '/'), DATA.toString(), ".toon"))
                        assertFalse(r.body().contains(leak), "no server path in the answer (" + leak + "): " + r.body());
                }
                assertArrayEquals(pipeline, Files.readAllBytes(pipelineFile(root, id)), "nothing was written");
                assertEquals(200, authed(c, "GET", path, null, uploader).statusCode(), "the feed is still readable");
            } finally {
                com.gamma.etl.EditionFeatures.overrideForTest(null);
            }
        }
    }

    private HttpResponse<String> authed(Ctx c, String method, String path, byte[] body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        if (auth != null) b.header("Authorization", auth);
        b = body == null ? (method.equals("GET") ? b.GET() : b.method(method, BodyPublishers.noBody()))
                : b.method(method, BodyPublishers.ofByteArray(body));
        return client.send(b.build(), BodyHandlers.ofString());
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
            data(send(c, "PUT", "/datasets/" + id + "/watch", "{\"watch\":true}".getBytes(StandardCharsets.UTF_8)), 200);

            // where an export is written while it runs: the drafts folder of the data root, a half-written Parquet file
            Path stage = DATA.resolve(OnboardRoutes.DRAFTS).resolve("a" + UUID.randomUUID().toString().replace("-", ""));
            Path whole = parquet(stage.resolve("whole.parquet"), 50, 500);
            byte[] bytes = Files.readAllBytes(whole);
            Path half = stage.resolve(OnboardRoutes.storedName("transfers.parquet"));
            Files.write(half, java.util.Arrays.copyOf(bytes, bytes.length / 2));
            Files.delete(whole);

            for (int i = 0; i < 3; i++) ControlApiImportPreRunStepsTest.poll(c.svc());   // B1: the watch is a schedule
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
