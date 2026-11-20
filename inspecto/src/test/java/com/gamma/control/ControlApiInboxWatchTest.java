package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.EditionFeatures;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.onboard.ImportFollowOn;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.service.CollectorService.PreRun;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code BASIC-ONBOARD-1} P2, as rebuilt by {@code BASIC-ELT-PROFILE-1} B1 (O1), over real HTTP: {@code PUT /datasets/{id}/watch}
 * turns <i>Watch this folder</i> on and off on Basic by rewriting the import's Pipeline to the locked preset (a {@code local}
 * Collector on a 30 s schedule with the stability gate) and back to manual; the engine's poll cycle (driven here instead of
 * waiting) runs it - the pre-run steps judge the inbox, a file whose columns do not match is set aside with its reason, the
 * run takes the settled files, the follow-on and the Expectations run after; per file, a file still being written waits
 * alone; a file written after the pre-run check is not taken unjudged; the watch survives a restart (it is the Pipeline);
 * the feed answer keeps its shape; the refusals and the capability gates. Synthetic fixtures only.
 */
class ControlApiInboxWatchTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UPLOADER = "Bearer uploader", ANALYST = "Bearer analyst", ADMIN = "Bearer admin";
    private static final FileTime SETTLED = FileTime.fromMillis(System.currentTimeMillis() - 120_000L);
    private static final String EXPECTATION = "watched_ids_present";
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<String> ids = new ArrayList<>();
    private final List<String> probed = Collections.synchronizedList(new ArrayList<>());

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void cleanUp() {
        noLateStep();
        OnboardImport.followOnsForTest(null);
        Authenticators.forTest(null);
        EditionFeatures.overrideForTest(null);
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

    /** The test's pre-run step, made a no-op (the registry is the JVM's; a test cannot remove a name from outside it). */
    private static void noLateStep() {
        CollectorService.preRunStep("t.late", 200, (svc, p, scheduled) -> PreRun.GO);
        CollectorService.preRunStep("t.fail", 200, (svc, p, scheduled) -> PreRun.GO);
    }

    private static String wider(int rows) {
        return ControlApiArrivalNewColumnsTest.withColumn(ControlApiOnboardFilesTest.transfers(rows).replace("T0000", "W0000"),
                "fee", i -> "1." + i);
    }

    private static Map<?, ?> fee(Path root, String id) {
        return ControlApiImportPreRunStepsTest.column(root, id, "fee");
    }

    /** A host over {@code cfg}'s fixture Pipeline and {@code more} - as a restart registers what the config root holds. */
    private Ctx open(Path cfg, Path writeRoot, Path... more) throws Exception {
        return openOver(null, cfg, writeRoot, more);
    }

    /** As above over {@code status} (null: the file-backed store a bare host gets; a deployment's default is the database one). */
    private Ctx openOver(com.gamma.etl.StatusStore status, Path cfg, Path writeRoot, Path... more) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            List<Path> all = new ArrayList<>(List.of(pipe));
            all.addAll(List.of(more));
            CollectorService svc = status == null ? new CollectorService(all, 3600, 1)
                    : new CollectorService(all, List.of(), 3600, 1, status);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, byte[] body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        if (auth != null) b.header("Authorization", auth);
        b = body == null ? (method.equals("GET") ? b.GET() : b.method(method, BodyPublishers.noBody()))
                : b.method(method, BodyPublishers.ofByteArray(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> json(Ctx c, String method, String path, String body, String auth) throws Exception {
        return send(c, method, path, body == null ? null : body.getBytes(StandardCharsets.UTF_8), auth);
    }

    private static JsonNode data(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    private static String error(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body()).path("error").path("message").asText();
    }

    private String imported(Ctx c, String base, String kind, String content, String auth) throws Exception {
        String id = base + "_" + UUID.randomUUID().toString().substring(0, 8);
        ids.add(id);
        String name = "reference".equals(kind) ? "accounts.csv" : "transfers.csv";
        JsonNode d = data(send(c, "POST", "/onboard/files?name=" + name + "&kind=" + kind, content.getBytes(StandardCharsets.UTF_8), auth), 200);
        data(json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}", auth), 200);
        return id;
    }

    private static Path inbox(String id) {
        return Path.of("database", "inbox", id);
    }

    /** A file dropped two minutes ago: unmodified for longer than the 30 s settle window. */
    private static Path dropSettled(String id, String name, String content) throws Exception {
        Path f = inbox(id).resolve(name);
        Files.writeString(f, content);
        Files.setLastModifiedTime(f, SETTLED);
        return f;
    }

    private JsonNode feed(Ctx c, String id) throws Exception {
        return data(json(c, "GET", "/datasets/" + id + "/feed", null, null), 200);
    }

    /** One poll cycle of the engine, every scheduled Pipeline due (the test does not wait out the 30 s). */
    private static void poll(Ctx c) throws Exception {
        ControlApiImportPreRunStepsTest.poll(c.svc());
    }

    private static String pipelineFile(Path root, String id) throws Exception {
        return Files.readString(root.resolve(OnboardImport.CONFIG_DIR).resolve(id + "_pipeline.toon"));
    }

    private static Object lastStatus(Path writeRoot) {
        return new ComponentStore(writeRoot.resolve("registry")).get("expectation", EXPECTATION)
                .map(ComponentRegistry.Component::content)
                .map(c -> c.get("lastResult") instanceof Map<?, ?> r ? r.get("status") : null).orElse(null);
    }

    // ── Basic: the toggle, the scheduled run, the same steps as Run now ────────────────────────────────────

    @Test
    void onBasicAWatchedInboxIsImportedOnceSettledByTheScheduledRun(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of());   // Basic: no pipeline.automation
        OnboardImport.followOnsForTest(List.of(new ImportFollowOn() {
            public String key() { return "probe"; }
            public String does() { return "probes it"; }
            public Set<String> needs() { return Set.of(); }
            public Map<String, Object> run(Context ctx) {
                probed.add(ctx.actor());
                return Map.of("result", "DONE");
            }
        }));
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "watched", "stream", ControlApiOnboardFilesTest.transfers(40), null);
            new ComponentStore(root.resolve("registry")).write("expectation", EXPECTATION, Map.of(
                    "name", EXPECTATION, "kind", "non_null", "targetType", "pipeline", "target", id, "column", "transfer_id"));
            assertFalse(feed(c, id).get("watch").asBoolean(), "off by default");
            assertTrue(feed(c, id).get("watchable").asBoolean(), "offered on Basic");

            JsonNode on = data(json(c, "PUT", "/datasets/" + id + "/watch", "{\"watch\":true}", null), 200);
            assertTrue(on.get("watch").asBoolean(), on.toString());
            assertTrue(on.get("watchable").asBoolean(), "still offered: it turns the watch off");
            assertEquals("uploads", on.at("/feed/kind").asText(), "the feed answer keeps its shape (the UI reads it so)");
            // O1 (2026-11-19, reversing DT-17): the watch is the Pipeline's own schedule now
            String toon = pipelineFile(root, id);
            assertTrue(toon.contains("every: 30s") && toon.contains("schedule"), toon);
            assertTrue(toon.contains("window: 30s") && toon.contains("size_checks: 2"), toon);
            assertEquals(Boolean.TRUE, new ComponentStore(root.resolve("registry")).get("dataset", id).orElseThrow()
                    .content().get(InboxWatch.WATCH_KEY), "kept on the Dataset entry");
            JsonNode arrivals = data(json(c, "GET", "/datasets/" + id + "/arrivals", null, null), 200);
            assertTrue(arrivals.at("/feed/watch").asBoolean(), "Data status says the inbox is watched: " + arrivals);
            assertEquals("uploads", arrivals.at("/feed/kind").asText(), arrivals.toString());
            JsonNode again = data(json(c, "PUT", "/datasets/" + id + "/watch", "{\"watch\":true}", null), 200);
            assertTrue(again.get("watch").asBoolean(), "on again: nothing changes");
            probed.clear();   // Add data's own import followed on as the person

            dropSettled(id, "drop_a.csv", ControlApiOnboardFilesTest.transfers(10));
            dropSettled(id, "wrong.csv", "colour,shape\nred,square\n");
            poll(c);
            Path refused = Path.of("database", id, OnboardImport.REFUSED_DIR, "wrong.csv");
            assertTrue(Files.exists(refused), "the pre-run step sets aside a file whose columns do not match, as Run now does");
            assertEquals(1, feed(c, id).get("waiting").asInt(), "one poll is not enough: the size is seen twice first");
            assertEquals(List.of(), probed, "nothing committed, nothing followed on");
            poll(c);
            JsonNode after = feed(c, id);
            assertEquals(0, after.get("waiting").asInt(), "the scheduled run imported the settled file: " + after);
            assertEquals(List.of("scheduler"), probed, "the follow-on ran once, after the run");
            assertEquals("PASSED", lastStatus(root), "the Expectations ran after it");
            JsonNode list = data(json(c, "GET", "/datasets/" + id + "/arrivals", null, null), 200);
            String all = list.get("arrivals").toString();
            assertTrue(all.contains("drop_a.csv") && all.contains("REFUSED") && all.contains("does not have the columns"),
                    "the arrivals list names the import and the refusal with its reason: " + all);
            assertFalse(list.at("/feed").has("watchError"), list.toString());

            // a file imported before sent again is not imported again: set aside, and the arrivals say why (B3 part 2)
            long rows = OnboardImport.countRows(Path.of("database", id, "database"));
            dropSettled(id, "drop_a.csv", ControlApiOnboardFilesTest.transfers(10));
            int arrivalsBefore = list.get("arrivals").size();
            poll(c);
            poll(c);
            JsonNode later = data(json(c, "GET", "/datasets/" + id + "/arrivals", null, null), 200).get("arrivals");
            assertEquals(arrivalsBefore + 1, later.size(), later.toString());
            assertEquals("REFUSED", later.get(0).get("status").asText(), later.get(0).toString());
            assertTrue(later.get(0).get("reason").asText().contains("already imported"), later.get(0).toString());
            assertEquals(rows, OnboardImport.countRows(Path.of("database", id, "database")), "nothing ran");
            assertEquals(List.of("scheduler"), probed, "nothing followed on");
        }
    }

    @Test
    void aFileStillBeingWrittenWaitsAloneWhileTheSettledOnesAreTaken(@TempDir Path cfg, @TempDir Path root) throws Exception {
        // O1 (2026-11-19): per-file stability replaces the old whole-inbox settle rule
        EditionFeatures.overrideForTest(Set.of());
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "busy", "stream", ControlApiOnboardFilesTest.transfers(40), null);
            data(json(c, "PUT", "/datasets/" + id + "/watch", "{\"watch\":true}", null), 200);
            dropSettled(id, "done.csv", ControlApiOnboardFilesTest.transfers(5));
            Path growing = inbox(id).resolve("growing.csv");
            Files.writeString(growing, ControlApiOnboardFilesTest.transfers(3).replace("T0000", "G0000"));   // modified just now
            for (int i = 0; i < 3; i++) poll(c);
            assertEquals(1, feed(c, id).get("waiting").asInt(), "the settled file is taken; the one modified within 30 s waits");
            assertEquals(45, OnboardImport.countRows(Path.of("database", id, "database")));

            Files.writeString(growing, ControlApiOnboardFilesTest.transfers(6).replace("T0000", "G0000"));   // it grew
            Files.setLastModifiedTime(growing, SETTLED);
            poll(c);
            assertEquals(1, feed(c, id).get("waiting").asInt(), "its new size is seen once only");
            poll(c);
            assertEquals(0, feed(c, id).get("waiting").asInt(), "settled: taken");
            assertEquals(51, OnboardImport.countRows(Path.of("database", id, "database")));
        }
    }

    @Test
    void aFileThatSettlesAfterThePreRunCheckIsNotTakenUnjudged(@TempDir Path cfg, @TempDir Path root) throws Exception {
        // BASIC-ONBOARD-SETTLE-1, per file: the run takes only the files the pre-run step judged
        EditionFeatures.overrideForTest(Set.of());
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "late", "stream", ControlApiOnboardFilesTest.transfers(40), null);
            data(json(c, "PUT", "/datasets/" + id + "/watch", "{\"watch\":true}", null), 200);
            dropSettled(id, "checked.csv", ControlApiOnboardFilesTest.transfers(5));
            Path late = inbox(id).resolve("late.csv");
            Files.writeString(late, "colour,shape\nred,square\n");   // modified just now: not judged, seen once by the gate
            poll(c);
            assertTrue(Files.exists(late) && Files.exists(inbox(id).resolve("checked.csv")), "first sighting: nothing taken yet");
            // between the pre-run check and the read, the late file's window passes at the same size: the gate would take it
            CollectorService.preRunStep("t.late", 200, (svc, p, scheduled) -> {
                try {
                    if (id.equals(p) && Files.exists(late)) Files.setLastModifiedTime(late, SETTLED);   // this run only
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                return PreRun.GO;
            });
            poll(c);
            noLateStep();
            assertTrue(Files.exists(late), "the late file stays in the inbox: not taken, not judged and set aside");
            assertFalse(Files.exists(Path.of("database", id, OnboardImport.REFUSED_DIR, "late.csv")), "never judged half-written");
            assertEquals(1, feed(c, id).get("waiting").asInt(), "only the checked file was taken");
            String all = data(json(c, "GET", "/datasets/" + id + "/arrivals", null, null), 200).get("arrivals").toString();
            assertTrue(all.contains("checked.csv") && !all.contains("late.csv"), all);

            poll(c);
            assertTrue(Files.exists(Path.of("database", id, OnboardImport.REFUSED_DIR, "late.csv")), "judged on the next run: refused");
            assertEquals(0, feed(c, id).get("waiting").asInt());
        }
    }

    @Test
    void offRestoresTheManualPipelineAndARestartKeepsTheWatch(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of());
        String id;
        try (Ctx c = open(cfg, root)) {
            id = imported(c, "toggled", "stream", ControlApiOnboardFilesTest.transfers(40), null);
            data(json(c, "PUT", "/datasets/" + id + "/watch", "{\"watch\":true}", null), 200);
            JsonNode off = data(json(c, "PUT", "/datasets/" + id + "/watch", "{\"watch\":false}", null), 200);
            assertFalse(off.get("watch").asBoolean());
            assertTrue(off.get("watchable").asBoolean());
            assertNull(new ComponentStore(root.resolve("registry")).get("dataset", id).orElseThrow()
                    .content().get(InboxWatch.WATCH_KEY));
            String toon = pipelineFile(root, id);
            assertTrue(toon.contains("manual") && !toon.contains("every") && !toon.contains("stability"), "back to uploads: " + toon);
            dropSettled(id, "drop.csv", ControlApiOnboardFilesTest.transfers(4));
            poll(c);
            poll(c);
            assertEquals(1, feed(c, id).get("waiting").asInt(), "off: the poll cycle does not run it");
            data(json(c, "PUT", "/datasets/" + id + "/watch", "{\"watch\":true}", null), 200);
        }
        // a restart: nothing in memory; the host registers the import's Pipeline from the config root, and it is the watch
        try (Ctx c = open(cfg, root, root.resolve(OnboardImport.CONFIG_DIR).resolve(id + "_pipeline.toon"))) {
            assertTrue(feed(c, id).get("watch").asBoolean(), "the watch came back with the Pipeline");
            poll(c);
            poll(c);
            assertEquals(0, feed(c, id).get("waiting").asInt(), "and the poll cycle runs it");
        }
    }

    @Test
    void aRunThatDoesNotSucceedTakesTheKeptColumnBackAndDataStatusSaysSo(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of());
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "failing", "stream", ControlApiOnboardFilesTest.transfers(40), null);
            data(json(c, "PUT", "/datasets/" + id + "/watch", "{\"watch\":true}", null), 200);
            dropSettled(id, "wider.csv", wider(6));
            // a step after the import's own stops the run once it kept the column: the run does not succeed
            CollectorService.preRunStep("t.fail", 200, (svc, p, scheduled) -> id.equals(p) ? PreRun.SKIP : PreRun.GO);
            poll(c);
            noLateStep();
            assertNull(fee(root, id), "the column kept for a run that did not succeed is taken back");
            assertFalse(Files.readString(root.resolve(OnboardImport.CONFIG_DIR).resolve(id + "_schema.toon")).contains("fee"));
            JsonNode f = feed(c, id);
            assertEquals(InboxWatch.SERVER_FAULT, f.path("watchError").asText(), "Data status says the last import failed: " + f);
            assertEquals(InboxWatch.SERVER_FAULT, data(json(c, "GET", "/datasets/" + id + "/arrivals", null, null), 200)
                    .at("/feed/watchError").asText());

            poll(c);
            poll(c);
            assertEquals(46, OnboardImport.countRows(Path.of("database", id, "database")), "the next runs take it, column and all");
            assertNotNull(fee(root, id));
            assertFalse(feed(c, id).has("watchError"), "a run that succeeds clears it");
        }
    }

    @Test
    void aRunThatSetsTheFileAsideTakesTheKeptColumnBack(@TempDir Path cfg, @TempDir Path root) throws Exception {
        setAsideTakesTheColumnBack(null, cfg, root);
    }

    /** The default deployment's status store ({@code status.backend=db}): a projection that catches up only after the run. */
    @Test
    void aRunThatSetsTheFileAsideTakesTheKeptColumnBackUnderTheDatabaseStatusStore(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        setAsideTakesTheColumnBack(com.gamma.service.DbStatusStore.open("jdbc:duckdb:", null, null), cfg, root);
    }

    private void setAsideTakesTheColumnBack(com.gamma.etl.StatusStore status, Path cfg, Path root) throws Exception {
        // verifier finding: a quarantined file in a run whose pre-run kept a column is a run that failed
        EditionFeatures.overrideForTest(Set.of());
        try (Ctx c = openOver(status, cfg, root)) {
            String id = imported(c, "asided", "stream", ControlApiOnboardFilesTest.transfers(40), null);
            data(json(c, "PUT", "/datasets/" + id + "/watch", "{\"watch\":true}", null), 200);
            Path schema = root.resolve(OnboardImport.CONFIG_DIR).resolve(id + "_schema.toon");
            String before = Files.readString(schema);
            Path file = inbox(id).resolve("wider.csv");
            Files.writeString(file, wider(6));
            poll(c);   // modified just now: not judged; the gate sees it once
            Files.setLastModifiedTime(file, SETTLED);
            // after the import's step keeps the column, the schema is put back: the run reads the file with the old one
            CollectorService.preRunStep("t.fail", 200, (svc, p, scheduled) -> {
                try {
                    if (id.equals(p)) Files.writeString(schema, before);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                return PreRun.GO;
            });
            poll(c);
            noLateStep();
            assertFalse(new com.gamma.service.FileStatusStore().quarantine(c.svc().configFor(id).orElseThrow()).isEmpty(),
                    "the run set the file aside");
            assertEquals(40, OnboardImport.countRows(Path.of("database", id, "database")));
            assertNull(fee(root, id), "the column kept for the file the run set aside is taken back");
            // BASIC-WATCH-ARRIVALS-1: Data status names the file and the engine's reason, not a server fault
            assertEquals("'wider.csv' was set aside (field_mismatch)", feed(c, id).path("watchError").asText(),
                    "the file and its reason, not its rejected-rows companion: " + feed(c, id));
            for (Map<String, Object> line : sidecar(id))
                assertNull(line.get("keptColumns"), "a column taken back is not said to be kept: " + line);
        }
    }

    // ── BASIC-WATCH-ARRIVALS-1: what a scheduled run records after it ─────────────────────────────────────

    /** The arrivals sidecar lines of the import, oldest first. */
    private static List<Map<String, Object>> sidecar(String id) {
        return DatasetArrivals.sidecarLines(Path.of("database"), id);
    }

    private static Map<String, Object> lineFor(String id, String landed) {
        return sidecar(id).stream().filter(l -> landed.equals(l.get("landed"))).reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("no arrivals line for " + landed + ": " + sidecar(id)));
    }

    @Test
    void aScheduledRunWritesItsArrivalsLine(@TempDir Path cfg, @TempDir Path root) throws Exception {
        scheduledRunWritesItsArrivalsLine(null, cfg, root);
    }

    @Test
    void aScheduledRunWritesItsArrivalsLineUnderTheDatabaseStatusStore(@TempDir Path cfg, @TempDir Path root) throws Exception {
        scheduledRunWritesItsArrivalsLine(com.gamma.service.DbStatusStore.open("jdbc:duckdb:", null, null), cfg, root);
    }

    private void scheduledRunWritesItsArrivalsLine(com.gamma.etl.StatusStore status, Path cfg, Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of());
        OnboardImport.followOnsForTest(List.of(new ImportFollowOn() {
            public String key() { return "index"; }
            public String does() { return "indexes it"; }
            public Set<String> needs() { return Set.of(); }
            public Map<String, Object> run(Context ctx) {
                return Map.of("result", "BUILT", "mode", "append");
            }
        }));
        try (Ctx c = openOver(status, cfg, root)) {
            String id = imported(c, "recorded", "stream", ControlApiOnboardFilesTest.transfers(40), null);
            data(json(c, "PUT", "/datasets/" + id + "/watch", "{\"watch\":true}", null), 200);
            int before = sidecar(id).size();
            Path file = inbox(id).resolve("wider.csv");
            Files.writeString(file, wider(6));
            poll(c);   // modified just now: not judged; the gate sees it once
            assertEquals(before, sidecar(id).size(), "a run that takes nothing writes nothing");
            Files.setLastModifiedTime(file, SETTLED);
            poll(c);   // judged (its column kept) and taken in the same run
            assertEquals(46, OnboardImport.countRows(Path.of("database", id, "database")), "the scheduled run took it");
            assertEquals(before + 1, sidecar(id).size(), "one line for the one file the run took: " + sidecar(id));
            Map<String, Object> line = lineFor(id, "wider.csv");
            assertEquals("system", line.get("sender"), line.toString());
            assertEquals("append", line.get("mode"), line.toString());
            assertEquals("BUILT", line.get("index"), "the follow-on's index outcome: " + line);
            assertEquals("append", line.get("indexMode"), line.toString());
            assertEquals(List.of("fee"), line.get("keptColumns"), "the column the pre-run step kept: " + line);
            assertNotNull(line.get("batch"), line.toString());

            poll(c);
            assertEquals(before + 1, sidecar(id).size(), "a run that takes nothing writes nothing");
            if (status == null) {   // the route reads the service's store: the file-backed one has the run at once
                String all = data(json(c, "GET", "/datasets/" + id + "/arrivals", null, null), 200).get("arrivals").toString();
                assertTrue(all.contains("\"index\":\"BUILT\"") && all.contains("\"keptColumns\":[\"fee\"]"), all);
            }
        }
    }

    @Test
    void aScheduledRunThatSetsAFileAsideSaysSo(@TempDir Path cfg, @TempDir Path root) throws Exception {
        scheduledRunThatSetsAFileAsideSaysSo(null, cfg, root);
    }

    @Test
    void aScheduledRunThatSetsAFileAsideSaysSoUnderTheDatabaseStatusStore(@TempDir Path cfg, @TempDir Path root) throws Exception {
        scheduledRunThatSetsAFileAsideSaysSo(com.gamma.service.DbStatusStore.open("jdbc:duckdb:", null, null), cfg, root);
    }

    private void scheduledRunThatSetsAFileAsideSaysSo(com.gamma.etl.StatusStore status, Path cfg, Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of());
        try (Ctx c = openOver(status, cfg, root)) {
            String id = imported(c, "emptied", "stream", ControlApiOnboardFilesTest.transfers(40), null);
            data(json(c, "PUT", "/datasets/" + id + "/watch", "{\"watch\":true}", null), 200);
            dropSettled(id, "empty.csv", ControlApiOnboardFilesTest.transfers(0));   // the columns match; no row to read
            poll(c);
            poll(c);
            assertFalse(new com.gamma.service.FileStatusStore().quarantine(c.svc().configFor(id).orElseThrow()).isEmpty(),
                    "the run set the file aside");
            assertNull(fee(root, id), "no column was kept for it");
            assertEquals("'empty.csv' was set aside (empty)", feed(c, id).path("watchError").asText(), feed(c, id).toString());
            assertEquals("'empty.csv' was set aside (empty)", data(json(c, "GET", "/datasets/" + id + "/arrivals", null, null), 200)
                    .at("/feed/watchError").asText());

            poll(c);
            assertEquals("'empty.csv' was set aside (empty)", feed(c, id).path("watchError").asText(),
                    "a run that finds nothing to take does not clear it");
            dropSettled(id, "good.csv", ControlApiOnboardFilesTest.transfers(5).replace("T0000", "N0000"));
            poll(c);
            poll(c);
            assertEquals(45, OnboardImport.countRows(Path.of("database", id, "database")));
            assertFalse(feed(c, id).has("watchError"), "a run that takes a file and sets none aside clears it");
        }
    }

    @Test
    void aWatchedInboxWaitsWhileAddDataIsOff(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of());
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "selfoff", "stream", ControlApiOnboardFilesTest.transfers(40), null);
            data(json(c, "PUT", "/datasets/" + id + "/watch", "{\"watch\":true}", null), 200);
            data(json(c, "PUT", "/settings/data", "{\"selfImport\":false}", null), 200);
            dropSettled(id, "drop.csv", ControlApiOnboardFilesTest.transfers(4));
            poll(c);
            poll(c);
            assertTrue(Files.exists(inbox(id).resolve("drop.csv")), "Add data off in the Space: the watched inbox waits");
            data(json(c, "PUT", "/settings/data", "{\"selfImport\":true}", null), 200);
            poll(c);
            poll(c);
            assertEquals(0, feed(c, id).get("waiting").asInt(), "on again: taken");
        }
    }

    @Test
    void stoppingTheFeedTurnsTheWatchOff(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of(OnboardImport.AUTOMATION_FEATURE));
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "stopped", "stream", ControlApiOnboardFilesTest.transfers(40), null);
            data(json(c, "PUT", "/datasets/" + id + "/watch", "{\"watch\":true}", null), 200);
            JsonNode stopped = data(json(c, "DELETE", "/datasets/" + id + "/feed", null, null), 200);
            assertFalse(stopped.get("watch").asBoolean(), stopped.toString());
            assertNull(new ComponentStore(root.resolve("registry")).get("dataset", id).orElseThrow()
                    .content().get(InboxWatch.WATCH_KEY), "the Dataset entry no longer says watched");
            assertTrue(pipelineFile(root, id).contains("manual"));
        }
    }

    @Test
    void aWatchSwitchedOnBeforeB1IsMovedOntoThePipelineAtStartup(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of());
        String id;
        try (Ctx c = open(cfg, root)) {
            id = imported(c, "legacy", "stream", ControlApiOnboardFilesTest.transfers(40), null);
            // as the poller left it: the entry says watched, the Pipeline stays manual
            ComponentStore store = new ComponentStore(root.resolve("registry"));
            java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>(store.get("dataset", id).orElseThrow().content());
            entry.remove("name");
            entry.put(InboxWatch.WATCH_KEY, true);
            store.write("dataset", id, entry);
            assertFalse(feed(c, id).get("watch").asBoolean(), "a manual Pipeline is not watched");
        }
        try (Ctx c = open(cfg, root, root.resolve(OnboardImport.CONFIG_DIR).resolve(id + "_pipeline.toon"))) {
            assertTrue(pipelineFile(root, id).contains("every: 30s"), "moved onto the preset at startup");
            assertTrue(feed(c, id).get("watch").asBoolean(), "and watched again");
            dropSettled(id, "drop.csv", ControlApiOnboardFilesTest.transfers(4));
            poll(c);
            poll(c);
            assertEquals(0, feed(c, id).get("waiting").asInt());
        }
    }

    // ── refusals ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void refusals(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of(OnboardImport.AUTOMATION_FEATURE));
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "refusing", "stream", ControlApiOnboardFilesTest.transfers(40), null);
            String path = "/datasets/" + id + "/watch";
            for (String bad : List.of("{}", "{\"watch\":\"yes\"}", "{\"watch\":1}")) {
                HttpResponse<String> r = json(c, "PUT", path, bad, null);
                assertEquals(422, r.statusCode(), bad + " " + r.body());
            }
            String ref = imported(c, "accounts", "reference", ControlApiOnboardFilesTest.transfers(10), null);
            HttpResponse<String> reference = json(c, "PUT", "/datasets/" + ref + "/watch", "{\"watch\":true}", null);
            assertEquals(422, reference.statusCode(), reference.body());
            assertTrue(error(reference).contains("Reference"), reference.body());
            assertFalse(feed(c, ref).get("watchable").asBoolean(), "a Reference is not offered the toggle");
            // set through another door: the entry alone schedules nothing, so the poll never imports a Reference's inbox
            ComponentStore store = new ComponentStore(root.resolve("registry"));
            java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>(store.get("dataset", ref).orElseThrow().content());
            entry.remove("name");
            entry.put(InboxWatch.WATCH_KEY, true);
            store.write("dataset", ref, entry);
            dropSettled(ref, "more.csv", ControlApiOnboardFilesTest.transfers(3));
            poll(c);
            poll(c);
            assertEquals(1, feed(c, ref).get("waiting").asInt(), "a Reference's inbox is not imported by the poll");
            assertFalse(feed(c, ref).get("watch").asBoolean());

            data(json(c, "PUT", "/datasets/" + id + "/feed", "{\"kind\":\"folder\"}", null), 200);   // Standard: a schedule
            assertFalse(feed(c, id).get("watchable").asBoolean(), "a Dataset on a schedule is not offered the toggle");
            HttpResponse<String> scheduled = json(c, "PUT", path, "{\"watch\":true}", null);
            assertEquals(409, scheduled.statusCode(), scheduled.body());
            assertTrue(error(scheduled).contains("schedule"), scheduled.body());
            assertEquals(404, json(c, "PUT", "/datasets/no_such_ds/watch", "{\"watch\":true}", null).statusCode());
        }
    }

    // ── gates (armed: 401 / 403 / 200) ──────────────────────────────────────────────────────────────────────

    @Test
    void theWatchRouteNeedsCanUploadData(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of());
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case UPLOADER -> Optional.of(new Subject("uploader-1", Set.of("canUploadData")));
            case ANALYST -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents", "canAuthorWorkbench")));
            case ADMIN -> Optional.of(new Subject("admin-1", Set.of("canAdminister", "canUploadData")));
            default -> Optional.empty();
        });
        try (Ctx c = open(cfg, root)) {
            data(json(c, "PUT", "/settings/data", "{\"selfImport\":true}", ADMIN), 200);
            String id = imported(c, "gated", "stream", ControlApiOnboardFilesTest.transfers(40), UPLOADER);
            String path = "/datasets/" + id + "/watch";
            assertEquals(401, json(c, "PUT", path, "{\"watch\":true}", null).statusCode());
            assertEquals(403, json(c, "PUT", path, "{\"watch\":true}", ANALYST).statusCode());
            assertTrue(data(json(c, "PUT", path, "{\"watch\":true}", UPLOADER), 200).get("watch").asBoolean());
        }
    }
}
