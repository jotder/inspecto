package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.BasicProfile;
import com.gamma.etl.BasicProfile.Entry;
import com.gamma.etl.EditionFeatures;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.inspector.PostgresCollectorConnector;
import com.gamma.pipeline.PipelineTrigger;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code BASIC-ELT-PROFILE-1} Gate over real HTTP: every Basic entry point ({@link BasicProfile.Entry}) is driven on a Basic and
 * a Standard server, and is offered or refused exactly as the Basic profile declares - a locked one answers {@code 503} with
 * its feature's refusal, an offered one does its work. What an offered one writes stays inside the profile: a Collector scheme
 * Basic keeps, {@code trigger: manual} or the watch preset, no Step beyond Basic's - even when the request names a
 * Standard-only option. Nothing is refused on load (DT-9 as narrowed, ruling O2): the import is registered on both editions.
 *
 * <p>Falsified (2026-11-20): with the {@code offered()} check removed from {@code DatasetCompaction.setRetention} the Basic
 * retention probe answers 200 and the Basic test fails; with {@link Entry#KEEP_UP_TO_DATE} declared Basic's own while
 * {@code DatasetFeed.keepUpToDate} still asks {@code EditionFeatures.present(PIPELINE_AUTOMATION)} the route refuses where the
 * profile offers it, and the Basic test fails - the route follows the declaration, not a constant of its own.
 */
class ControlApiBasicProfileGateTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<String> ids = new ArrayList<>();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void cleanUp() {
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
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, byte[] body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        b = body == null ? b.method(method, BodyPublishers.noBody()) : b.method(method, BodyPublishers.ofByteArray(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> json(Ctx c, String method, String path, String body) throws Exception {
        return send(c, method, path, body.getBytes(StandardCharsets.UTF_8));
    }

    private static String error(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body()).path("error").path("message").asText();
    }

    /** What the import's registered Pipeline uses lies inside the Basic profile; {@code watched}: the watch preset, else manual. */
    private static void insideTheProfile(Ctx c, String id, boolean watched) {
        PipelineConfig cfg = c.svc().configFor(id).orElseThrow(() -> new AssertionError(id + " is registered (nothing refused on load)"));
        String scheme = cfg.collector().connector();
        assertNull(BasicProfile.schemeFeature(scheme), "a scheme Basic keeps: " + scheme);
        PipelineTrigger t = PipelineTrigger.of(cfg.triggerConfig());
        if (watched) {
            assertEquals(PipelineTrigger.Kind.SCHEDULE_INTERVAL, t.kind(), String.valueOf(cfg.triggerConfig()));
            assertEquals(PipelineTrigger.of(Map.of("every", BasicProfile.WATCH_EVERY)).everyMs(), t.everyMs(),
                    "the one schedule Basic writes: " + cfg.triggerConfig());
        } else {
            assertEquals(PipelineTrigger.Scheduler.MANUAL, t.scheduler(), String.valueOf(cfg.triggerConfig()));
        }
        // a built-in Step's kind is its node type's last part (filter -> transform.filter, sql -> transform.sql)
        for (PipelineConfig.Step st : cfg.steps())
            assertNull(BasicProfile.stepFeature("transform." + st.kind()), "a Step Basic keeps: " + st.kind());
    }

    @Test
    void onBasicEachEntryPointIsOfferedOrRefusedAsTheProfileDeclares(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of());   // Basic: no module declares the Standard features
        everyEntryPoint(cfg, root, false);
    }

    @Test
    void onStandardEveryEntryPointIsOffered(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.copyOf(BasicProfile.SCREEN_FLAGS.values()));   // what inspecto-ops declares
        everyEntryPoint(cfg, root, true);
    }

    @Test
    void theWatchPresetAndThePostgresSchemeAreTheProfilesOwn() {
        assertEquals(BasicProfile.WATCH_EVERY, InboxWatch.EVERY, "Watch this folder writes the profile's schedule");
        assertEquals(PipelineTrigger.of(Map.of("every", BasicProfile.WATCH_EVERY)).everyMs(), InboxWatch.EVERY_MS);
        assertTrue(BasicProfile.SCHEMES.contains(PostgresCollectorConnector.SCHEME), "Choose a table reads through a scheme Basic keeps");
        assertNull(BasicProfile.Entry.ADD_FILE.feature());
    }

    private void everyEntryPoint(Path cfg, Path root, boolean standard) throws Exception {
        Map<Entry, HttpResponse<String>> answered = new EnumMap<>(Entry.class);
        try (Ctx c = open(cfg, root)) {
            // Add a file - the commit names Standard-only options; the import is still Basic's
            String id = "gate_" + UUID.randomUUID().toString().substring(0, 8);
            ids.add(id);
            HttpResponse<String> up = send(c, "POST", "/onboard/files?name=transfers.csv&kind=stream",
                    ControlApiOnboardFilesTest.transfers(20).getBytes(StandardCharsets.UTF_8));
            assertEquals(200, up.statusCode(), up.body());
            String draft = V1Body.of(up.body()).get("draftId").asText();
            HttpResponse<String> commit = json(c, "POST", "/onboard/files/" + draft + "/commit", "{\"name\":\"" + id + "\","
                    + "\"trigger\":{\"every\":\"5m\"},\"collector\":{\"connector\":\"sftp\"},\"steps\":[{\"sql\":{\"query\":\"SELECT 1\"}}]}");
            answered.put(Entry.ADD_FILE, commit);
            assertEquals(200, commit.statusCode(), commit.body());
            insideTheProfile(c, id, false);

            // Watch this folder - a cadence in the body is not taken: the preset is
            HttpResponse<String> watch = json(c, "PUT", "/datasets/" + id + "/watch", "{\"watch\":true,\"every\":\"5m\"}");
            answered.put(Entry.WATCH_FOLDER, watch);
            assertEquals(200, watch.statusCode(), watch.body());
            insideTheProfile(c, id, true);
            assertEquals(200, json(c, "PUT", "/datasets/" + id + "/watch", "{\"watch\":false}").statusCode());
            insideTheProfile(c, id, false);

            // the duplicate check is a kept scheme's setting: set through the authoring route every Pipeline uses, every edition
            HttpResponse<String> dup = json(c, "POST", "/config/patch", "{\"type\":\"pipeline\",\"name\":\"" + id + "\",\"patch\":"
                    + "{\"collector\":{\"duplicate\":{\"mode\":\"content\",\"on_change\":\"ignore\"}}}}");
            assertEquals(200, dup.statusCode(), dup.body());
            JsonNode written = V1Body.of(send(c, "GET", "/config/pipeline/" + id, null).body()).at("/config/collector/duplicate");
            assertEquals("content", written.path("mode").asText(), written.toString());
            assertEquals("ignore", written.path("on_change").asText(), written.toString());

            // Choose a table - past every edition check to the connection's own refusal (no database is needed)
            String var = "INSPECTO_PG_GATE_UNSET_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
            HttpResponse<String> pg = json(c, "POST", "/onboard/postgres/test", "{\"connection\":" + JSON.writeValueAsString(Map.of(
                    "host", "127.0.0.1", "port", 5432, "database", "db", "user", "reader", "password", "${ENV:" + var + "}")) + "}");
            answered.put(Entry.POSTGRES_TABLE, pg);
            assertEquals(422, pg.statusCode(), pg.body());
            assertTrue(error(pg).contains(var), pg.body());

            // Keep this up to date, a retention policy - Standard's
            answered.put(Entry.KEEP_UP_TO_DATE, json(c, "PUT", "/datasets/" + id + "/feed", "{\"kind\":\"folder\"}"));
            answered.put(Entry.RETENTION, json(c, "PUT", "/datasets/" + id + "/storage", "{\"retention\":{\"days\":30}}"));
        }

        assertEquals(EnumSet.allOf(Entry.class), answered.keySet(), "a probe per Basic entry point");
        for (Entry e : Entry.values()) {
            HttpResponse<String> r = answered.get(e);
            assertEquals(standard || e.feature() == null, e.offered(), e + " is offered on " + (standard ? "Standard" : "Basic"));
            if (e.offered()) {
                assertNotEquals(503, r.statusCode(), e + ": " + r.body());
                assertTrue(r.statusCode() < 300 || e == Entry.POSTGRES_TABLE, e + ": " + r.body());
            } else {
                assertEquals(503, r.statusCode(), e + ": " + r.body());
                assertTrue(error(r).contains(EditionFeatures.refusal(e.feature())), e + " names its feature: " + r.body());
            }
        }
    }
}
