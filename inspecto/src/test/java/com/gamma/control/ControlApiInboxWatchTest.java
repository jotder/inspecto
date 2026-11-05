package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.EditionFeatures;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
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
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code BASIC-ONBOARD-1} P2 over real HTTP: {@code PUT /datasets/{id}/watch} turns the inbox poll on and off on Basic;
 * the one poller ({@link InboxWatch#tick}, driven here instead of waiting 30 s) imports a watched inbox through the same
 * path as Run now - a file whose columns do not match is set aside with its reason - only once every waiting file has
 * settled; a restart picks the watch up again; Data status says it; the refusals and the capability gates. Synthetic
 * fixtures only.
 */
class ControlApiInboxWatchTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UPLOADER = "Bearer uploader", ANALYST = "Bearer analyst", ADMIN = "Bearer admin";
    private static final FileTime SETTLED = FileTime.fromMillis(System.currentTimeMillis() - 120_000L);
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<String> ids = new ArrayList<>();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @org.junit.jupiter.api.BeforeEach
    void pauseTheDaemon() {
        InboxWatch.pausedForTest = true;   // these tests drive tick() themselves
    }

    @AfterEach
    void cleanUp() {
        InboxWatch.pausedForTest = false;
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

    // ── Basic: the toggle, the poll, the same path as Run now ──────────────────────────────────────────────

    @Test
    void onBasicAWatchedInboxIsImportedOnceSettledThroughTheRunNowPath(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of());   // Basic: no pipeline.automation
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "watched", "stream", ControlApiOnboardFilesTest.transfers(40), null);
            assertFalse(feed(c, id).get("watch").asBoolean(), "off by default");
            assertTrue(feed(c, id).get("watchable").asBoolean(), "offered on Basic");

            JsonNode on = data(json(c, "PUT", "/datasets/" + id + "/watch", "{\"watch\":true}", null), 200);
            assertTrue(on.get("watch").asBoolean(), on.toString());
            assertEquals("uploads", on.at("/feed/kind").asText(), "no schedule is written: the Pipeline stays manual");
            assertEquals(Boolean.TRUE, new ComponentStore(root.resolve("registry")).get("dataset", id).orElseThrow()
                    .content().get(InboxWatch.WATCH_KEY), "kept on the Dataset entry");
            JsonNode arrivals = data(json(c, "GET", "/datasets/" + id + "/arrivals", null, null), 200);
            assertTrue(arrivals.at("/feed/watch").asBoolean(), "Data status says the inbox is watched: " + arrivals);

            dropSettled(id, "drop_a.csv", ControlApiOnboardFilesTest.transfers(10));
            dropSettled(id, "wrong.csv", "colour,shape\nred,square\n");
            InboxWatch.tick();
            assertEquals(2, feed(c, id).get("waiting").asInt(), "one poll is not enough: the size is seen twice first");
            InboxWatch.tick();
            JsonNode after = feed(c, id);
            assertEquals(0, after.get("waiting").asInt(), "the poll imported the settled inbox: " + after);
            Path refused = Path.of("database", id, OnboardImport.REFUSED_DIR, "wrong.csv");
            assertTrue(Files.exists(refused), "a file whose columns do not match is set aside, as Run now does");
            JsonNode list = data(json(c, "GET", "/datasets/" + id + "/arrivals", null, null), 200);
            String all = list.get("arrivals").toString();
            assertTrue(all.contains("drop_a.csv") && all.contains("REFUSED") && all.contains("does not have the columns"),
                    "the arrivals list names the import and the refusal with its reason: " + all);
            assertFalse(list.at("/feed").has("watchError"), list.toString());

            // a file imported before under the same name is skipped by the run: the poll does not run for it alone
            dropSettled(id, "drop_a.csv", ControlApiOnboardFilesTest.transfers(10));
            int arrivalsBefore = list.get("arrivals").size();
            InboxWatch.tick();
            InboxWatch.tick();
            assertEquals(arrivalsBefore, data(json(c, "GET", "/datasets/" + id + "/arrivals", null, null), 200)
                    .get("arrivals").size(), "nothing ran");
        }
    }

    @Test
    void aFileStillBeingWrittenIsNotTakenAndHoldsTheWholeInbox(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of());
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "busy", "stream", ControlApiOnboardFilesTest.transfers(40), null);
            data(json(c, "PUT", "/datasets/" + id + "/watch", "{\"watch\":true}", null), 200);
            dropSettled(id, "done.csv", ControlApiOnboardFilesTest.transfers(5));
            Path growing = inbox(id).resolve("growing.csv");
            Files.writeString(growing, ControlApiOnboardFilesTest.transfers(3));   // modified just now
            for (int i = 0; i < 3; i++) InboxWatch.tick();
            assertEquals(2, feed(c, id).get("waiting").asInt(),
                    "a file modified within 30 s holds the run: the run would take every file waiting");

            Files.writeString(growing, ControlApiOnboardFilesTest.transfers(6));   // it grew
            Files.setLastModifiedTime(growing, SETTLED);
            InboxWatch.tick();
            assertEquals(2, feed(c, id).get("waiting").asInt(), "its new size is seen once only");
            InboxWatch.tick();
            assertEquals(0, feed(c, id).get("waiting").asInt(), "settled: both imported");
        }
    }

    @Test
    void aFileCreatedAfterTheSettleCheckIsLeftForALaterPoll(@TempDir Path cfg, @TempDir Path root) throws Exception {
        // BASIC-ONBOARD-SETTLE-1: the run takes only the files the poll checked, not whatever the inbox holds by then
        EditionFeatures.overrideForTest(Set.of());
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "late", "stream", ControlApiOnboardFilesTest.transfers(40), null);
            data(json(c, "PUT", "/datasets/" + id + "/watch", "{\"watch\":true}", null), 200);
            dropSettled(id, "checked.csv", ControlApiOnboardFilesTest.transfers(5));
            Path late = inbox(id).resolve("late.csv");
            InboxWatch.tick();
            InboxWatch.afterSettleCheckForTest = () -> {
                try {
                    Files.writeString(late, "colour,shape\nred,square\n");   // still being written, wrong so far
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            };
            try {
                InboxWatch.tick();
            } finally {
                InboxWatch.afterSettleCheckForTest = null;
            }
            assertTrue(Files.exists(late), "the late file stays in the inbox: not taken, not judged and set aside");
            assertFalse(Files.exists(Path.of("database", id, OnboardImport.REFUSED_DIR, "late.csv")), "never judged half-written");
            assertEquals(1, feed(c, id).get("waiting").asInt(), "only the checked file was taken");
            String all = data(json(c, "GET", "/datasets/" + id + "/arrivals", null, null), 200).get("arrivals").toString();
            assertTrue(all.contains("checked.csv") && !all.contains("late.csv"), all);

            Files.writeString(late, ControlApiOnboardFilesTest.transfers(4));   // the writer finishes
            Files.setLastModifiedTime(late, SETTLED);
            InboxWatch.tick();
            InboxWatch.tick();
            assertEquals(0, feed(c, id).get("waiting").asInt(), "settled on later polls: taken then");
        }
    }

    @Test
    void offStopsThePollAndARestartPicksTheWatchUpAgain(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of());
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "toggled", "stream", ControlApiOnboardFilesTest.transfers(40), null);
            data(json(c, "PUT", "/datasets/" + id + "/watch", "{\"watch\":true}", null), 200);
            JsonNode off = data(json(c, "PUT", "/datasets/" + id + "/watch", "{\"watch\":false}", null), 200);
            assertFalse(off.get("watch").asBoolean());
            assertNull(new ComponentStore(root.resolve("registry")).get("dataset", id).orElseThrow()
                    .content().get(InboxWatch.WATCH_KEY));
            dropSettled(id, "drop.csv", ControlApiOnboardFilesTest.transfers(4));
            InboxWatch.tick();
            InboxWatch.tick();
            assertEquals(1, feed(c, id).get("waiting").asInt(), "off: nothing is polled");

            data(json(c, "PUT", "/datasets/" + id + "/watch", "{\"watch\":true}", null), 200);
            InboxWatch.detach(c.api());   // as after a restart: nothing in memory, the entry still says watch
            InboxWatch.tick();
            InboxWatch.tick();
            assertEquals(1, feed(c, id).get("waiting").asInt(), "detached: not polled");
            InboxWatch.attach(c.api());
            InboxWatch.tick();   // reads the entries, first sighting
            InboxWatch.tick();
            assertEquals(0, feed(c, id).get("waiting").asInt(), "the watch came back from the Dataset entry");
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
            // set through another door: the poll never imports a Reference's inbox
            ComponentStore store = new ComponentStore(root.resolve("registry"));
            java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>(store.get("dataset", ref).orElseThrow().content());
            entry.remove("name");
            entry.put(InboxWatch.WATCH_KEY, true);
            store.write("dataset", ref, entry);
            InboxWatch.register(new InboxWatch.Inbox(c.api(), com.gamma.event.EventLog.currentSpaceId(), root, ref));
            dropSettled(ref, "more.csv", ControlApiOnboardFilesTest.transfers(3));
            InboxWatch.tick();
            InboxWatch.tick();
            assertEquals(1, feed(c, ref).get("waiting").asInt(), "a Reference's inbox is not imported by the poll");

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
