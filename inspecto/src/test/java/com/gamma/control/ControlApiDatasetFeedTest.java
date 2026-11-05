package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.EditionFeatures;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.PipelineTrigger;
import com.gamma.service.CollectorService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code LA-ARRIVAL-FEEDS-1} over real HTTP: {@code GET /datasets/{id}/feed} names the import's inbox and the files waiting
 * there (D-1: on Basic they are imported with Run now, never polled); {@code PUT} (Standard and above) turns the import's
 * own Pipeline into a scheduled watched-folder feed with the safe defaults and proposes the index Job; {@code DELETE} goes
 * back to uploads; the body refusals and the capability gates. Synthetic fixtures only.
 */
class ControlApiDatasetFeedTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UPLOADER = "Bearer uploader", ANALYST = "Bearer analyst", ADMIN = "Bearer admin";
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

    /** An import Dataset made with Add data from {@code rows} synthetic transfers. */
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

    // ── Basic (D-1): the inbox and the files waiting, imported with Run now ─────────────────────────────────

    @Test
    void onBasicTheInboxAndItsWaitingFilesAreShownAndRunNowImportsThem(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of());   // Basic: no pipeline.automation
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "inboxed", "stream", ControlApiOnboardFilesTest.transfers(40), null);
            JsonNode empty = data(json(c, "GET", "/datasets/" + id + "/feed", null, null), 200);
            assertEquals(0, empty.get("waiting").asInt(), empty.toString());
            assertEquals(inbox(id).toAbsolutePath().normalize().toString(), empty.get("inbox").asText());
            assertEquals("uploads", empty.at("/feed/kind").asText(), "Add data writes a manual import");
            assertFalse(empty.get("automatic").asBoolean(), "Basic does not poll");

            Files.writeString(inbox(id).resolve("drop_a.csv"), ControlApiOnboardFilesTest.transfers(10));
            Files.writeString(inbox(id).resolve("drop_b.csv"), ControlApiOnboardFilesTest.transfers(5));
            Files.writeString(inbox(id).resolve(".landing-123.csv"), "still arriving");   // an upload being landed
            Files.writeString(inbox(id).resolve("readme.txt"), "not this import's kind of file");
            JsonNode two = data(json(c, "GET", "/datasets/" + id + "/feed", null, null), 200);
            assertEquals(2, two.get("waiting").asInt(), two.toString());
            assertEquals(2, two.get("files").size());
            assertTrue(two.get("files").toString().contains("drop_a.csv") && two.get("files").toString().contains("drop_b.csv"), two.toString());
            assertFalse(two.get("truncated").asBoolean());

            HttpResponse<String> keep = json(c, "PUT", "/datasets/" + id + "/feed", "{\"kind\":\"folder\"}", null);
            assertEquals(503, keep.statusCode(), keep.body());
            assertTrue(error(keep).contains("Standard") && error(keep).contains("Run now"), keep.body());
            assertEquals(PipelineTrigger.Kind.MANUAL, PipelineTrigger.of(c.svc().configFor(id).orElseThrow().triggerConfig()).kind(),
                    "nothing was written");

            JsonNode run = data(json(c, "POST", "/datasets/" + id + "/run", null, null), 200);
            assertEquals(15, run.get("rowsIn").asLong(), run.toString());
            assertEquals(0, data(json(c, "GET", "/datasets/" + id + "/feed", null, null), 200).get("waiting").asInt(),
                    "Import waiting files took them");

            // the same name sent again is skipped by the import's duplicate check: it waits, and the inbox says why
            Files.writeString(inbox(id).resolve("drop_a.csv"), ControlApiOnboardFilesTest.transfers(10));
            Files.writeString(inbox(id).resolve("drop_c.csv"), ControlApiOnboardFilesTest.transfers(3));
            JsonNode again = data(json(c, "GET", "/datasets/" + id + "/feed", null, null), 200);
            assertEquals(2, again.get("waiting").asInt(), again.toString());
            assertEquals(1, again.get("alreadyImported").asInt(), again.toString());
            for (JsonNode f : again.get("files"))
                assertEquals(f.get("name").asText().equals("drop_a.csv"), f.get("alreadyImported").asBoolean(), again.toString());
            assertEquals(3, data(json(c, "POST", "/datasets/" + id + "/run", null, null), 200).get("rowsIn").asLong(),
                    "only the new file is read");
            JsonNode left = data(json(c, "GET", "/datasets/" + id + "/feed", null, null), 200);
            assertEquals(1, left.get("waiting").asInt(), left.toString());
            assertEquals(1, left.get("alreadyImported").asInt(), "the skipped file stays, still marked");
        }
    }

    // ── Standard: Keep this up to date, and back ────────────────────────────────────────────────────────────

    @Test
    void keepThisUpToDateWatchesTheInboxWithSafeDefaultsAndStopGoesBackToUploads(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of(OnboardImport.AUTOMATION_FEATURE));
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "watched", "stream", ControlApiOnboardFilesTest.transfers(40), null);
            // a collector setting made in the guided editor survives Keep and Stop (only the feed's own keys change)
            Path file = root.resolve(OnboardImport.CONFIG_DIR).resolve(id + "_pipeline.toon");
            java.util.Map<String, Object> raw = new java.util.LinkedHashMap<>(com.gamma.util.ToonHelper.load(file.toString()));
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> col = new java.util.LinkedHashMap<>((java.util.Map<String, Object>) raw.get("collector"));
            col.put("recursive_depth", 2);
            raw.put("collector", col);
            Files.writeString(file, com.gamma.config.io.ConfigCodec.toToon(raw));
            c.svc().refreshConfigs();
            assertEquals(2, c.svc().configFor(id).orElseThrow().collector().recursiveDepth());

            JsonNode on = data(json(c, "PUT", "/datasets/" + id + "/feed",
                    "{\"kind\":\"folder\",\"every\":\"5m\",\"readyMarker\":\".done\"}", null), 200);
            assertEquals("folder", on.at("/feed/kind").asText(), on.toString());
            assertEquals("5m", on.at("/feed/every").asText());
            assertEquals(".done", on.at("/feed/readyMarker").asText());
            assertTrue(on.get("automatic").asBoolean());

            var live = c.svc().configFor(id).orElseThrow();
            PipelineTrigger t = PipelineTrigger.of(live.triggerConfig());
            assertEquals(PipelineTrigger.Kind.SCHEDULE_INTERVAL, t.kind(), "the reloaded Pipeline polls on its own");
            assertEquals(300_000L, t.everyMs());
            assertTrue(live.collector().stability().enabled(), "a half-written file is held back");
            assertEquals(".done", live.collector().stability().readyMarker());
            assertEquals("checksum", live.collector().duplicate().mode(), "the same file sent again is recognised by content");
            assertEquals("ignore", live.collector().duplicate().onChange());
            assertEquals("local", live.collector().connector());
            assertTrue(live.collector().acceptsUpload(), "Add a file still works on a watched Dataset (DT-2)");
            assertTrue(live.dirs().quarantine() != null, "rejected files keep their quarantine");
            assertEquals(2, live.collector().recursiveDepth(), "the editor's setting is kept");
            assertTrue(Files.isDirectory(PipelineHistory.dirFor(root, id)), "the edit is kept in the Pipeline's history");

            JsonNode job = on.get("indexJob");
            assertEquals("la.index.build", job.get("type").asText(), on.toString());
            assertEquals(id, job.get("dataset").asText());
            assertEquals("pipeline.commit", job.get("on_signal").asText());
            assertTrue(job.get("when").asText().contains("$signal.pipeline == " + id), job.toString());
            assertEquals("from_account", job.get("source_col").asText(), job.toString());
            assertEquals("to_account", job.get("target_col").asText());
            assertEquals("true", job.get("allow_full").asText(), "the first build is full, every later one an append");

            JsonNode read = data(json(c, "GET", "/datasets/" + id + "/feed", null, null), 200);
            assertEquals("folder", read.at("/feed/kind").asText(), "the feed is read back from the Pipeline itself");
            assertFalse(read.has("indexJob"), "only the PUT proposes the Job");

            JsonNode off = data(json(c, "DELETE", "/datasets/" + id + "/feed", null, null), 200);
            assertEquals("uploads", off.at("/feed/kind").asText(), off.toString());
            var manual = c.svc().configFor(id).orElseThrow();
            assertEquals(PipelineTrigger.Kind.MANUAL, PipelineTrigger.of(manual.triggerConfig()).kind());
            assertFalse(manual.collector().stability().enabled());
            assertEquals("path", manual.collector().duplicate().mode(), "the feed's own keys are gone");
            assertEquals(2, manual.collector().recursiveDepth(), "the editor's setting is still kept");
            data(json(c, "POST", "/datasets/" + id + "/run", null, null), 200);   // the import still runs by hand
        }
    }

    @Test
    void keepThisUpToDateRefusesWhatItCannotDoSafely(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of(OnboardImport.AUTOMATION_FEATURE));
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "refused", "stream", ControlApiOnboardFilesTest.transfers(40), null);
            String path = "/datasets/" + id + "/feed";
            for (String[] bad : new String[][] {
                    {"{}", "kind must be"},
                    {"{\"kind\":\"s3\"}", "kind must be"},
                    {"{\"kind\":\"folder\",\"every\":\"30s\"}", "between 1m and 1d"},
                    {"{\"kind\":\"folder\",\"every\":\"2d\"}", "between 1m and 1d"},
                    {"{\"kind\":\"folder\",\"every\":\"soon\"}", "a number and a unit"},
                    {"{\"kind\":\"folder\",\"readyMarker\":\"../x\"}", "readyMarker"},
                    {"{\"kind\":\"folder\",\"connection\":\"box\"}", "takes no connection"},
                    {"{\"kind\":\"sftp\"}", "names its Connection"},
                    {"{\"kind\":\"sftp\",\"connection\":\"nowhere\"}", "no Connection 'nowhere'"},
                    {"{\"kind\":\"folder\",\"every\":5}", "must be a string"}}) {
                HttpResponse<String> r = json(c, "PUT", path, bad[0], null);
                assertEquals(422, r.statusCode(), bad[0] + " -> " + r.body());
                assertTrue(error(r).contains(bad[1]), bad[0] + " -> " + r.body());
            }
            assertEquals(PipelineTrigger.Kind.MANUAL, PipelineTrigger.of(c.svc().configFor(id).orElseThrow().triggerConfig()).kind(),
                    "a refused body writes nothing");

            String ref = imported(c, "ref", "reference", ControlApiOnboardFilesTest.accounts(20), null);
            HttpResponse<String> r = json(c, "PUT", "/datasets/" + ref + "/feed", "{\"kind\":\"folder\"}", null);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(error(r).contains("Reference"), r.body());

            assertEquals(404, json(c, "GET", "/datasets/no_such_import/feed", null, null).statusCode());

            // claimed like an import: a run (or a delete) of the same Dataset in flight makes the write wait its turn
            String key = c.root() + "|" + id;
            OnboardImport.IN_FLIGHT.add(key);
            try {
                HttpResponse<String> busy = json(c, "PUT", path, "{\"kind\":\"folder\"}", null);
                assertEquals(409, busy.statusCode(), busy.body());
            } finally {
                OnboardImport.IN_FLIGHT.remove(key);
            }

            // an approval policy over Pipelines refuses the rewrite, as it refuses Add data's own writes
            Files.writeString(root.resolve(ApprovalPolicy.FILE), "approval:\n  pipeline:\n    required: true\n");
            for (String m : List.of("PUT", "DELETE")) {
                HttpResponse<String> held = json(c, m, path, m.equals("PUT") ? "{\"kind\":\"folder\"}" : null, null);
                assertEquals(409, held.statusCode(), m + " " + held.body());
                assertTrue(error(held).contains("approval policy"), held.body());
            }
            assertEquals(PipelineTrigger.Kind.MANUAL, PipelineTrigger.of(c.svc().configFor(id).orElseThrow().triggerConfig()).kind(),
                    "nothing was written");
        }
    }

    // ── gates (armed: 401 / 403 / 200) ──────────────────────────────────────────────────────────────────────

    @Test
    void theFeedRoutesNeedCanUploadData(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of(OnboardImport.AUTOMATION_FEATURE));
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case UPLOADER -> Optional.of(new Subject("uploader-1", Set.of("canUploadData")));
            case ANALYST -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents", "canAuthorWorkbench")));
            case ADMIN -> Optional.of(new Subject("admin-1", Set.of("canAdminister", "canUploadData")));
            default -> Optional.empty();
        });
        try (Ctx c = open(cfg, root)) {
            data(json(c, "PUT", "/settings/data", "{\"selfImport\":true}", ADMIN), 200);
            String id = imported(c, "gated", "stream", ControlApiOnboardFilesTest.transfers(40), UPLOADER);
            String path = "/datasets/" + id + "/feed";
            for (String m : List.of("GET", "PUT", "DELETE")) {
                assertEquals(401, json(c, m, path, m.equals("PUT") ? "{\"kind\":\"folder\"}" : null, null).statusCode(), m);
                assertEquals(403, json(c, m, path, m.equals("PUT") ? "{\"kind\":\"folder\"}" : null, ANALYST).statusCode(), m);
            }
            data(json(c, "GET", path, null, UPLOADER), 200);
            assertEquals("folder", data(json(c, "PUT", path, "{\"kind\":\"folder\"}", UPLOADER), 200).at("/feed/kind").asText());
            assertEquals("uploads", data(json(c, "DELETE", path, null, UPLOADER), 200).at("/feed/kind").asText());
        }
    }
}
