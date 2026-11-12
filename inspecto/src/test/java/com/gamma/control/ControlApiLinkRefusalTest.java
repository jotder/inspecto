package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `SPACE-LINK-CENSUS-1` LC-3 over real HTTP: the census delete refusal ({@code -Dlinks.delete}, D-LC3) for each kind a
 * census link can name and the route deletes it through, and the save refusal of an active config adding a dangling
 * link ({@code -Dlinks.save}, D-LC1, refuse honoured on Standard/Advanced only per D-LC5). Both modes default to
 * {@code shadow}: nothing is refused, the response reports.
 *
 * <p>"Standard" here is the edition {@link BootstrapRoutes#edition()} reports when people sign in: a test
 * {@link Authenticators} that signs every request in as an author.
 */
class ControlApiLinkRefusalTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    @AfterEach
    void clearModes() {
        System.clearProperty(LinkRefusals.DELETE_PROPERTY);
        System.clearProperty(LinkRefusals.SAVE_PROPERTY);
        Authenticators.forTest(null);
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
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

    /** Standard edition: people sign in (an author who may also administer, so no kind gate interferes). */
    private static void signIn() {
        Authenticators.forTest(ex -> Optional.of(new Subject("author", Set.of("canAuthorWorkbench", "canAdminister"))));
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Authorization", "Bearer author");
        if (body != null) b.header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static Path component(Path root, String dir, String id, String body) throws Exception {
        Path d = root.resolve("registry").resolve(dir);
        Files.createDirectories(d);
        Path f = d.resolve(id + ".toon");
        Files.writeString(f, body);
        return f;
    }

    /** A Space whose Dataset {@code ds1} feeds widget {@code w1}, which dashboard {@code ops} shows; widget
     *  {@code w2} reads query {@code q1}; Pipeline {@code orders_feed} (inactive) exists. */
    private static void space(Path root) throws Exception {
        Files.writeString(root.resolve("orders_feed_pipeline.toon"),
                "name: Orders Feed\nactive: false\ndirs:\n  poll: in\n  database: out\nprocessing:\n  threads: 1\n");
        component(root, "datasets", "ds1", "name: ds1\nphysicalRef: orders_feed/database\n");
        component(root, "widgets", "w1", "name: w1\ndatasetId: ds1\n");
        component(root, "dashboards", "ops", "name: ops\ntiles[1]{widgetId}:\n  w1\n");
        component(root, "queries", "q1", "name: q1\ntext: SELECT 1\n");
        component(root, "widgets", "w2", "name: w2\nqueryId: q1\n");
    }

    /** The resource of a 2xx, the error {@code details} of a refusal (the v1 envelope). */
    private static JsonNode body(HttpResponse<String> r) throws Exception {
        return r.statusCode() >= 400 ? V1Body.envelope(r.body()).get("error").get("details") : V1Body.of(r.body());
    }

    private static void assertDependent(JsonNode list, String kind, String name, String key) {
        assertNotNull(list, "dependents listed");
        assertEquals(1, list.size(), list.toString());
        assertEquals(kind, list.get(0).get("kind").asText());
        assertEquals(name, list.get(0).get("name").asText());
        assertEquals(key, list.get(0).get("key").asText());
    }

    // ── delete (D-LC3) ──────────────────────────────────────────────────────────────────────

    @Test
    void shadowIsTheDefaultDeleteGoesThroughAndListsWhatItBroke(@TempDir Path cfg, @TempDir Path root) throws Exception {
        space(root);
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = send(c.port, "DELETE", "/components/dataset/ds1", null);
            assertEquals(200, r.statusCode(), r.body());
            assertDependent(body(r).get("brokenLinks"), "widget", "w1", "datasetId");
            assertFalse(Files.exists(root.resolve("registry/datasets/ds1.toon")), "shadow deletes as today");
        }
    }

    @Test
    void refuseModeRefusesADatasetDeleteWith409ListingTheDependents(@TempDir Path cfg, @TempDir Path root) throws Exception {
        space(root);
        System.setProperty(LinkRefusals.DELETE_PROPERTY, "refuse");
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = send(c.port, "DELETE", "/components/dataset/ds1", null);
            assertEquals(409, r.statusCode(), r.body());
            assertDependent(body(r).get("dependents"), "widget", "w1", "datasetId");
            assertTrue(Files.exists(root.resolve("registry/datasets/ds1.toon")), "refused: the Dataset stays");

            HttpResponse<String> forced = send(c.port, "DELETE", "/components/dataset/ds1?force=true", null);
            assertEquals(200, forced.statusCode(), forced.body());
            assertDependent(body(forced).get("brokenLinks"), "widget", "w1", "datasetId");
            assertFalse(Files.exists(root.resolve("registry/datasets/ds1.toon")));
        }
    }

    @Test
    void refuseModeRefusesAWidgetDeleteADashboardShows(@TempDir Path cfg, @TempDir Path root) throws Exception {
        space(root);
        System.setProperty(LinkRefusals.DELETE_PROPERTY, "refuse");
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = send(c.port, "DELETE", "/components/widget/w1", null);
            assertEquals(409, r.statusCode(), r.body());
            assertDependent(body(r).get("dependents"), "dashboard", "ops", "tiles[0].widgetId");
            assertTrue(Files.exists(root.resolve("registry/widgets/w1.toon")));
            assertEquals(200, send(c.port, "DELETE", "/components/widget/w1?force=true", null).statusCode());
        }
    }

    @Test
    void refuseModeRefusesAQueryDeleteAWidgetReads(@TempDir Path cfg, @TempDir Path root) throws Exception {
        space(root);
        System.setProperty(LinkRefusals.DELETE_PROPERTY, "refuse");
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = send(c.port, "DELETE", "/components/query/q1", null);
            assertEquals(409, r.statusCode(), r.body());
            assertDependent(body(r).get("dependents"), "widget", "w2", "queryId");
            assertTrue(Files.exists(root.resolve("registry/queries/q1.toon")));
            assertEquals(200, send(c.port, "DELETE", "/components/query/q1?force=true", null).statusCode());
        }
    }

    @Test
    void shadowListsAQueryAndAWidgetDependentToo(@TempDir Path cfg, @TempDir Path root) throws Exception {
        space(root);
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> q = send(c.port, "DELETE", "/components/query/q1", null);
            assertEquals(200, q.statusCode(), q.body());
            assertDependent(body(q).get("brokenLinks"), "widget", "w2", "queryId");
            HttpResponse<String> w = send(c.port, "DELETE", "/components/widget/w1", null);
            assertEquals(200, w.statusCode(), w.body());
            assertDependent(body(w).get("brokenLinks"), "dashboard", "ops", "tiles[0].widgetId");
        }
    }

    @Test
    void offModeDeletesWithNoReportAndAnUnusedComponentIsNeverRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        space(root);
        System.setProperty(LinkRefusals.DELETE_PROPERTY, "refuse");
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> unused = send(c.port, "DELETE", "/components/dashboard/ops", null);
            assertEquals(200, unused.statusCode(), "nothing links to a dashboard: " + unused.body());
            assertNull(body(unused).get("brokenLinks"));
        }
        System.setProperty(LinkRefusals.DELETE_PROPERTY, "off");
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = send(c.port, "DELETE", "/components/dataset/ds1", null);
            assertEquals(200, r.statusCode(), r.body());
            assertNull(body(r).get("brokenLinks"), "off reports nothing");
        }
    }

    @Test
    void theExistingPipelineRefusalIsUnchangedWhenTheModeIsOff(@TempDir Path cfg, @TempDir Path root) throws Exception {
        space(root);
        component(root, "expectations", "rowcount", "name: rowcount\ntarget: orders_feed\n");
        System.setProperty(LinkRefusals.DELETE_PROPERTY, "off");
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = send(c.port, "DELETE", "/config/pipeline/orders_feed", null);
            assertEquals(409, r.statusCode(), "the Pipeline dependents refusal stays in every mode: " + r.body());
            assertTrue(Files.exists(root.resolve("orders_feed_pipeline.toon")));
        }
    }

    @Test
    void aConfigFileNothingLinksToDeletesInRefuseMode(@TempDir Path cfg, @TempDir Path root) throws Exception {
        space(root);
        Files.writeString(root.resolve("daily_enrich.toon"), "name: daily\ntriggers:\n  on_pipeline: orders_feed\n");
        System.setProperty(LinkRefusals.DELETE_PROPERTY, "refuse");
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = send(c.port, "DELETE", "/config/enrichment/daily", null);
            assertEquals(200, r.statusCode(), r.body());
            assertNull(body(r).get("brokenLinks"));
        }
    }

    // ── save (D-LC1) ────────────────────────────────────────────────────────────────────────

    private static String enrichment(String onPipeline, boolean overwrite) {
        return """
                {"type":"enrichment",%s"config":{
                   "name":"daily",
                   "input":{"database":"out","format":"PARQUET","partitions":["year","month","day"]},
                   "output":{"database":"enriched/daily","format":"PARQUET","partitions":["year","month","day"]},
                   "transform":"SELECT * FROM input",
                   "triggers":{"on_pipeline":"%s"}}}""".formatted(overwrite ? "\"overwrite\":true," : "", onPipeline);
    }

    private static String job(String onPipeline, boolean enabled) {
        return """
                {"type":"job","config":{"job":{"name":"nightly","type":"report","on_pipeline":"%s","enabled":%s}}}"""
                .formatted(onPipeline, enabled);
    }

    private static JsonNode linkFinding(JsonNode findings) {
        for (JsonNode f : findings)
            if (f.get("code").asText().endsWith("_LINK_DANGLING")) return f;
        return null;
    }

    @Test
    void shadowSavesAnActiveConfigWithABrokenLinkAndWarns(@TempDir Path cfg, @TempDir Path root) throws Exception {
        space(root);
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = send(c.port, "POST", "/config/write", enrichment("no_such_feed", false));
            assertEquals(200, r.statusCode(), r.body());
            JsonNode f = linkFinding(body(r).get("findings"));
            assertNotNull(f, "shadow warns: " + r.body());
            assertEquals("WARN_LINK_DANGLING", f.get("code").asText());
            assertEquals("triggers.on_pipeline", f.get("fieldPath").asText());
            assertTrue(f.get("message").asText().contains("no_such_feed"));
        }
    }

    @Test
    void refuseOnBasicActsAsShadow(@TempDir Path cfg, @TempDir Path root) throws Exception {
        space(root);
        System.setProperty(LinkRefusals.SAVE_PROPERTY, "refuse");
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = send(c.port, "POST", "/config/write", enrichment("no_such_feed", false));
            assertEquals(200, r.statusCode(), "D-LC5: no refuse mode on Basic: " + r.body());
            assertEquals("WARN_LINK_DANGLING", linkFinding(body(r).get("findings")).get("code").asText());
        }
    }

    @Test
    void refuseOnStandardRefusesAnActiveConfigAddingABrokenLink(@TempDir Path cfg, @TempDir Path root) throws Exception {
        space(root);
        System.setProperty(LinkRefusals.SAVE_PROPERTY, "refuse");
        signIn();
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = send(c.port, "POST", "/config/write", enrichment("no_such_feed", false));
            assertEquals(422, r.statusCode(), r.body());
            JsonNode f = linkFinding(body(r).get("findings"));
            assertEquals("ERR_LINK_DANGLING", f.get("code").asText());
            assertEquals("triggers.on_pipeline", f.get("fieldPath").asText());
            assertTrue(f.get("message").asText().contains("no_such_feed"), f.toString());
            assertFalse(Files.exists(root.resolve("daily_enrich.toon")), "refused: nothing written");

            HttpResponse<String> ok = send(c.port, "POST", "/config/write", enrichment("orders_feed", false));
            assertEquals(200, ok.statusCode(), "a resolving link saves: " + ok.body());
            assertNull(linkFinding(body(ok).get("findings")));

            // the patch door is the same check over the merged draft
            HttpResponse<String> patch = send(c.port, "POST", "/config/patch",
                    "{\"type\":\"enrichment\",\"name\":\"daily\",\"patch\":{\"triggers\":{\"on_pipeline\":\"no_such_feed\"}}}");
            assertEquals(422, patch.statusCode(), patch.body());
            assertTrue(Files.readString(root.resolve("daily_enrich.toon")).contains("orders_feed"), "refused patch writes nothing");
        }
    }

    @Test
    void aDraftJobSavesWithABrokenLinkAnActiveOneIsRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        space(root);
        System.setProperty(LinkRefusals.SAVE_PROPERTY, "refuse");
        signIn();
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> draft = send(c.port, "POST", "/config/write", job("no_such_feed", false));
            assertEquals(200, draft.statusCode(), "a draft (enabled: false) saves with broken links: " + draft.body());
            assertNull(linkFinding(body(draft).get("findings")));

            HttpResponse<String> active = send(c.port, "POST", "/config/write",
                    job("no_such_feed", true).replace("{\"type\":\"job\",", "{\"type\":\"job\",\"overwrite\":true,"));
            assertEquals(422, active.statusCode(), "activating it is refused: " + active.body());
            assertEquals("on_pipeline", linkFinding(body(active).get("findings")).get("fieldPath").asText());
        }
    }

    @Test
    void aComponentSaveIsCheckedAndAnAlreadyBrokenLinkIsNotNew(@TempDir Path cfg, @TempDir Path root) throws Exception {
        space(root);
        component(root, "widgets", "stale", "name: stale\nvizType: bar\ndatasetId: gone\n");
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> shadow = send(c.port, "POST", "/components/widget",
                    "{\"id\":\"w9\",\"vizType\":\"bar\",\"datasetId\":\"no_such_ds\"}");
            assertEquals(200, shadow.statusCode(), shadow.body());
            JsonNode warn = body(shadow).get("linkWarnings");
            assertNotNull(warn, "shadow warns: " + shadow.body());
            assertEquals("WARN_LINK_DANGLING", warn.get(0).get("code").asText());
            assertEquals("datasetId", warn.get(0).get("fieldPath").asText());
        }
        System.setProperty(LinkRefusals.SAVE_PROPERTY, "refuse");
        signIn();
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = send(c.port, "POST", "/components/widget",
                    "{\"id\":\"w10\",\"vizType\":\"bar\",\"datasetId\":\"no_such_ds\"}");
            assertEquals(422, r.statusCode(), r.body());
            assertEquals("ERR_LINK_DANGLING", body(r).get("findings").get(0).get("code").asText());
            assertFalse(Files.exists(root.resolve("registry/widgets/w10.toon")));

            HttpResponse<String> same = send(c.port, "PUT", "/components/widget/stale",
                    "{\"vizType\":\"line\",\"datasetId\":\"gone\"}");
            assertEquals(200, same.statusCode(), "a link broken before this save is not one it adds: " + same.body());
        }
    }
}
