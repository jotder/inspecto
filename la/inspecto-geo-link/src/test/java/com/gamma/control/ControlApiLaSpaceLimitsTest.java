package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code LA-CONFIG-LIMITS-1} P3 over real HTTP: every Space analysis limit is read at its code site from the Space's in-force
 * value. Each test states a value through {@code PUT /settings/link-analysis/limits} (the Space admin's route), drives the site
 * past it and sees that site's EXISTING behaviour (the refusal, naming the in-force value; the clamp; the truncated flag) at the
 * stated number - and, before stating anything, the decided new default (CL-10 doubled, CL-11 hops 3 / 6). No restart between:
 * a changed value applies to the next request.
 */
class ControlApiLaSpaceLimitsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROWS = String.join(",",
            "('a','b','voice','2026-09-01 10:30:00',10.0)", "('a','c','voice','2026-09-02 12:00:00',20.0)",
            "('b','c','sms','2026-09-03 21:30:00',30.0)", "('c','d','sms','2026-09-05 08:00:00',40.0)");
    private static final String COLS = "\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\"";
    private static final String OPS = "/inv/investigations/case-a/ops";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void reset() {
        System.clearProperty("la.embed.enabled");
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT caller, callee, channel, CAST(ts AS TIMESTAMP) AS ts, amt FROM (VALUES " + ROWS
                            + ") AS t(caller,callee,channel,ts,amt)", "2026-10-15T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            Files.writeString(writeRoot.resolve("link-analysis.toon"), "masking_mode: none\n");
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private JsonNode get(Ctx c, String path) throws Exception {
        return data(send(c, "GET", path, null));
    }

    private JsonNode post(Ctx c, String path, String body) throws Exception {
        return data(send(c, "POST", path, body));
    }

    /** The Space admin states limits (the route under test elsewhere): 200, persisted in the Space's link-analysis.toon. */
    private void limits(Ctx c, String body) throws Exception {
        HttpResponse<String> r = send(c, "PUT", "/settings/link-analysis/limits", body);
        assertEquals(200, r.statusCode(), r.body());
    }

    /** {@code status} and its message naming {@code expected} (the in-force value the site applied). */
    private void refused(HttpResponse<String> r, int status, String expected) {
        assertEquals(status, r.statusCode(), r.body());
        assertTrue(r.body().contains(expected), "expected '" + expected + "' in " + r.body());
    }

    private void create(Ctx c, String id) throws Exception {
        post(c, "/inv/investigations", "{\"purpose\":\"limits\",\"id\":\"" + id + "\"," + COLS + ",\"linkKindCol\":\"channel\","
                + "\"timeCol\":\"ts\",\"timeColZone\":\"UTC\"}");
    }

    /** The params the last log entry of {@code id} sealed. */
    private static JsonNode lastParams(Ctx c, String id) throws Exception {
        List<String> lines = Files.readAllLines(c.root.resolve("audit/snapshots/investigations/" + id + "/log.jsonl"));
        return JSON.readTree(lines.get(lines.size() - 1)).get("params");
    }

    // ── investigation.* and input.* on the op path ─────────────────────────────────────────────────────────────

    @Test
    void theExpandBudgetDefaultAndCeilingAreTheSpacesDoubledByDefault(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            create(c, "case-a");
            post(c, OPS, "{\"op\":\"seed\",\"ids\":[\"a\"]}");
            post(c, OPS, "{\"op\":\"expand\"}");
            assertEquals(4_000, lastParams(c, "case-a").get("budget").asInt(), "the default budget is the doubled 4,000");
            post(c, OPS, "{\"op\":\"expand\",\"budget\":99999}");
            assertEquals(40_000, lastParams(c, "case-a").get("budget").asInt(), "clamped to the doubled 40,000");

            limits(c, "{\"investigation.expand_budget_default\":7,\"investigation.expand_budget_max\":9}");
            post(c, OPS, "{\"op\":\"expand\"}");
            assertEquals(7, lastParams(c, "case-a").get("budget").asInt(), "the Space's default, no restart");
            post(c, OPS, "{\"op\":\"expand\",\"budget\":100}");
            assertEquals(9, lastParams(c, "case-a").get("budget").asInt(), "clamped to the Space's ceiling");
        }
    }

    @Test
    void theOpShapeCapsAreTheSpaces(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            create(c, "case-a");
            post(c, OPS, "{\"op\":\"seed\",\"ids\":[\"a\",\"b\",\"c\"]}");
            limits(c, "{\"input.max_ids\":2,\"input.note_length\":5,\"input.link_kinds\":1,\"input.recurring_excludes\":1,"
                    + "\"investigation.max_frontier\":1,\"input.purpose_length\":3,\"input.reveal_ids\":1}");
            refused(send(c, "POST", OPS, "{\"op\":\"seed\",\"ids\":[\"a\",\"b\",\"c\"]}"), 422, "at most 2 ids per op");
            refused(send(c, "POST", OPS, "{\"op\":\"annotate\",\"ids\":[\"a\"],\"note\":\"123456\"}"), 422, "'note' is at most 5 chars");
            refused(send(c, "POST", OPS, "{\"op\":\"expand\",\"linkKinds\":[\"sms\",\"voice\"]}"), 422, "at most 1 kinds");
            refused(send(c, "POST", OPS, "{\"op\":\"window\",\"window\":{\"timezone\":\"UTC\",\"exclude\":["
                    + "{\"rule\":\"yearly\",\"month\":1,\"day\":1},{\"rule\":\"yearly\",\"month\":12,\"day\":25}]}}"), 422, "at most 1 recurring rules");
            refused(send(c, "POST", OPS, "{\"op\":\"expand\"}"), 422, "an expand frontier is capped at 1 entities");
            refused(send(c, "POST", "/inv/investigations/case-a/reveal", "{\"tokens\":[\"masked:x\",\"masked:y\"]}"), 422, "a list of 1..1 masked ids");
            refused(send(c, "POST", "/inv/investigations", "{\"purpose\":\"four\",\"id\":\"case-b\"," + COLS + "}"), 422, "'purpose' is at most 3 chars");

            limits(c, "{\"input.max_ids\":null,\"input.note_length\":null,\"input.link_kinds\":null,\"input.recurring_excludes\":null,"
                    + "\"investigation.max_frontier\":null,\"input.purpose_length\":null,\"input.reveal_ids\":null}");
            post(c, OPS, "{\"op\":\"annotate\",\"ids\":[\"a\"],\"note\":\"123456\"}");
            post(c, OPS, "{\"op\":\"expand\",\"linkKinds\":[\"sms\",\"voice\"]}");
        }
    }

    @Test
    void connectSeedsAndHopsAreTheSpacesWithTheNewDefaultCeilingOfSix(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            create(c, "case-a");
            post(c, OPS, "{\"op\":\"seed\",\"ids\":[\"a\",\"d\"]}");
            post(c, OPS, "{\"op\":\"connect\",\"ids\":[\"a\",\"d\"]}");
            assertEquals(3, lastParams(c, "case-a").get("maxHops").asInt(), "the default hops stay 3 (CL-11)");
            post(c, OPS, "{\"op\":\"connect\",\"ids\":[\"a\",\"d\"],\"maxHops\":6}");
            assertEquals(6, lastParams(c, "case-a").get("maxHops").asInt(), "the ceiling is 6 by default (was 3)");
            refused(send(c, "POST", OPS, "{\"op\":\"connect\",\"ids\":[\"a\",\"d\"],\"maxHops\":7}"), 422, "'maxHops' is at most 6");

            limits(c, "{\"connect.max_seeds\":2,\"connect.max_hops\":2,\"connect.default_hops\":2}");
            refused(send(c, "POST", OPS, "{\"op\":\"connect\",\"ids\":[\"a\",\"b\",\"d\"]}"), 422, "'connect' takes 2 to 2 seeds");
            refused(send(c, "POST", OPS, "{\"op\":\"connect\",\"ids\":[\"a\",\"d\"],\"maxHops\":3}"), 422, "'maxHops' is at most 2");
            post(c, OPS, "{\"op\":\"connect\",\"ids\":[\"a\",\"d\"]}");
            assertEquals(2, lastParams(c, "case-a").get("maxHops").asInt(), "the Space's default hops");
        }
    }

    @Test
    void investigationReadPagesAreTheSpaces(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            create(c, "case-a");
            create(c, "case-b");
            post(c, OPS, "{\"op\":\"seed\",\"ids\":[\"a\"]}");
            post(c, OPS, "{\"op\":\"expand\"}");
            assertEquals(2, get(c, "/inv/investigations?limit=50").get("items").size());
            assertEquals(2, get(c, "/inv/investigations/case-a/log?limit=50").get("entries").size());
            assertTrue(get(c, "/inv/investigations/case-a/working-set?limit=50").get("rows").size() > 1);

            limits(c, "{\"investigation.list_page_max\":1,\"investigation.log_page_max\":1,\"working_set.page_max\":1}");
            JsonNode list = get(c, "/inv/investigations?limit=50");
            assertEquals(1, list.get("items").size());
            assertTrue(list.get("truncated").asBoolean());
            JsonNode log = get(c, "/inv/investigations/case-a/log?limit=50");
            assertEquals(1, log.get("entries").size());
            assertTrue(log.get("truncated").asBoolean());
            JsonNode ws = get(c, "/inv/investigations/case-a/working-set?limit=50");
            assertEquals(1, ws.get("rows").size());
            assertEquals(1, ws.get("limit").asInt());
        }
    }

    // ── traversal.*, neighbours.*, snapshots.*, files.*, input.projection_mappings on the exploration reads ──────────

    @Test
    void recursivePathFencesAreTheSpacesAndDoubledByDefault(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String req = "{" + COLS + ",\"startNode\":\"a\",\"maxDepth\":99,\"maxEdgeYield\":9999999,\"limit\":9999999}";
            JsonNode fences = post(c, "/inv/traversal/recursive-paths", req).get("fences");
            assertEquals(20, fences.get("maxDepth").asInt(), "the doubled ceiling");
            assertEquals(200_000, fences.get("maxEdgeYield").asInt());
            assertEquals(10_000, fences.get("timeoutMs").asInt());
            assertEquals(12, post(c, "/inv/traversal/recursive-paths", "{" + COLS + ",\"startNode\":\"a\"}").at("/fences/maxDepth").asInt(),
                    "the doubled default");

            limits(c, "{\"traversal.depth_max\":3,\"traversal.edge_yield_max\":50,\"traversal.timeout_s\":7,\"traversal.paths_max\":1}");
            JsonNode capped = post(c, "/inv/traversal/recursive-paths", req);
            assertEquals(3, capped.at("/fences/maxDepth").asInt());
            assertEquals(50, capped.at("/fences/maxEdgeYield").asInt());
            assertEquals(7_000, capped.at("/fences/timeoutMs").asInt());
            assertEquals(1, capped.get("paths").size());
            assertTrue(capped.get("truncated").asBoolean(), "more paths than the Space's cap: " + capped);
        }
    }

    @Test
    void projectionNeighbourAndMappingCapsAreTheSpaces(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            JsonNode all = post(c, "/inv/projection", "{" + COLS + "}");
            assertEquals(4, all.get("rows").size());
            assertFalse(all.get("truncated").asBoolean());
            assertEquals(400, post(c, "/inv/projection/neighbors", "{" + COLS + ",\"value\":\"c\",\"list\":true,\"limit\":9999}")
                    .get("limit").asInt(), "the doubled page ceiling");
            String twoMappings = "{\"edges\":[{\"dataset\":\"calls_ds\",\"sourceColumn\":\"caller\",\"targetColumn\":\"callee\"},"
                    + "{\"dataset\":\"calls_ds\",\"sourceColumn\":\"callee\",\"targetColumn\":\"caller\"}]}";
            post(c, "/inv/projection/multi", twoMappings);

            limits(c, "{\"traversal.rows_default\":2,\"neighbours.page_max\":1,\"input.projection_mappings\":1}");
            JsonNode two = post(c, "/inv/projection", "{" + COLS + "}");
            assertEquals(2, two.get("rows").size());
            assertTrue(two.get("truncated").asBoolean());
            assertEquals(1, post(c, "/inv/projection/neighbors", "{" + COLS + ",\"value\":\"c\",\"list\":true,\"limit\":9999}")
                    .get("limit").asInt());
            refused(send(c, "POST", "/inv/projection/multi", twoMappings), 422, "at most 1 mappings per call");
        }
    }

    @Test
    void snapshotAndFilePagesAreTheSpaces(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            post(c, "/inv/snapshots", "{\"id\":\"snp-1\",\"investigationId\":\"case-a\",\"manifestHash\":\"h\"}");
            post(c, "/inv/snapshots", "{\"id\":\"snp-2\",\"investigationId\":\"case-a\",\"manifestHash\":\"h\"}");
            assertEquals(2, get(c, "/inv/snapshots?limit=50").get("ids").size());
            refused(send(c, "GET", "/inv/datasets/calls_ds/files?limit=2001", null), 422, "from 1 to 2000");

            limits(c, "{\"snapshots.page_max\":1,\"files.page_max\":1}");
            JsonNode one = get(c, "/inv/snapshots?limit=50");
            assertEquals(1, one.get("ids").size());
            assertTrue(one.get("truncated").asBoolean());
            refused(send(c, "GET", "/inv/datasets/calls_ds/files?limit=2", null), 422, "from 1 to 1");
        }
    }

    // ── pattern.*, measures.*, comparison.*, reference.* ───────────────────────────────────────────────────────

    @Test
    void patternFencesAreTheSpacesAndDoubledByDefault(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String temporal = "{" + COLS + ",\"timeCol\":\"ts\",\"mode\":\"burst\"";
            JsonNode t = post(c, "/inv/pattern/temporal", temporal + "}").get("fences");
            assertEquals(400_000, t.get("maxRows").asInt());
            assertEquals(2_000, t.get("maxResults").asInt());
            assertEquals(10_000, t.get("timeoutMs").asInt());
            String branching = "{" + COLS + ",\"stages\":[{\"shape\":\"fan-out\",\"minBranches\":1}]}";
            JsonNode b = post(c, "/inv/pattern/branching", branching).get("fences");
            assertEquals(200_000, b.get("maxLegs").asInt());
            assertEquals(10_000, b.get("timeoutMs").asInt());

            limits(c, "{\"pattern.temporal_limit_max\":5,\"pattern.temporal_max_rows\":2,\"pattern.timeout_s\":3,\"pattern.max_legs\":1}");
            refused(send(c, "POST", "/inv/pattern/temporal", temporal + ",\"limit\":6}"), 422, "'limit' must be a number from 1 to 5");
            JsonNode capped = post(c, "/inv/pattern/temporal", temporal + "}");
            assertEquals(2, capped.at("/fences/maxRows").asInt());
            assertEquals(3_000, capped.at("/fences/timeoutMs").asInt());
            assertTrue(capped.get("rowCapped").asBoolean(), "4 rows over the Space's 2: " + capped);
            JsonNode legs = post(c, "/inv/pattern/branching", branching);
            assertEquals(1, legs.at("/fences/maxLegs").asInt());
            assertTrue(legs.get("legCapped").asBoolean(), "more legs than the Space's 1: " + legs);
        }
    }

    @Test
    void valueMeasureFencesAreTheSpaces(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String q = "/inv/value-measures?dataset=calls_ds&sourceCol=caller&targetCol=callee&linkKindCol=channel&name=valueWeightedLinks"
                    + "&valueCol=amt&timeCol=ts";
            String week = "&from=2026-09-01T00:00:00Z&to=2026-09-08T00:00:00Z";
            JsonNode all = get(c, q + week);
            assertEquals(20_000, all.at("/fences/maxEntities").asInt(), "doubled");
            assertEquals(31, all.at("/fences/maxWindowDays").asInt(), "a day window is not doubled");
            assertEquals(4, all.get("entities").size());

            limits(c, "{\"measures.max_entities\":1,\"measures.max_window_days\":2,\"measures.timeout_s\":4}");
            refused(send(c, "GET", q + week, null), 422, "the window may span at most 2 days");
            JsonNode capped = get(c, q + "&from=2026-09-01T00:00:00Z&to=2026-09-03T00:00:00Z");
            assertEquals(1, capped.at("/fences/maxEntities").asInt());
            assertEquals(4, capped.at("/fences/timeoutSeconds").asInt());
            assertEquals(1, capped.get("entities").size());
            assertTrue(capped.get("truncated").asBoolean(), capped.toString());
        }
    }

    @Test
    void comparisonAndReferenceCapsAreTheSpaces(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            create(c, "case-a");
            post(c, OPS, "{\"op\":\"seed\",\"ids\":[\"a\"]}");
            post(c, OPS, "{\"op\":\"expand\"}");
            String cmp = "/inv/investigations/case-a/compare?aFrom=2026-09-01T00:00:00Z&aTo=2026-09-03T00:00:00Z"
                    + "&bFrom=2026-09-03T00:00:00Z&bTo=2026-09-06T00:00:00Z&timezone=UTC";
            get(c, cmp);
            String keys = "{\"dataset\":\"calls_ds\",\"keyCol\":\"caller\",\"keys\":[\"a\",\"b\"]}";
            JsonNode rows = post(c, "/inv/reference-rows", keys);
            assertFalse(rows.get("truncated").asBoolean(), rows.toString());

            limits(c, "{\"comparison.max_entities\":1,\"reference.max_keys\":1}");
            refused(send(c, "GET", cmp, null), 422, "a comparison covers at most 1 entities");
            refused(send(c, "POST", "/inv/reference-rows", keys), 413, "at most 1 keys per call");
            limits(c, "{\"comparison.max_entities\":null,\"reference.max_keys\":null,\"comparison.max_rows\":1,\"reference.max_rows\":1}");
            refused(send(c, "GET", cmp, null), 422, "the comparison reads more than 1 links");
            JsonNode one = post(c, "/inv/reference-rows", keys);
            assertTrue(one.get("truncated").asBoolean(), "3 rows over the Space's 1: " + one);
        }
    }

    // ── dossier.*, input.references / labels / embed grants, identity.* ───────────────────────────────────────

    @Test
    void dossierCapsAreTheSpaces(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            create(c, "case-a");
            post(c, OPS, "{\"op\":\"seed\",\"ids\":[\"a\"]}");
            post(c, OPS, "{\"op\":\"expand\"}");
            get(c, "/inv/investigations/case-a/dossier");

            limits(c, "{\"dossier.max_steps\":1,\"dossier.max_snapshots\":1}");
            refused(send(c, "GET", "/inv/investigations/case-a/dossier", null), 422, "a dossier covers at most 1 steps");
            refused(send(c, "GET", "/inv/investigations/case-a/dossier?at=1&snapshots=snp-1,snp-2", null), 422, "at most 1 snapshots");
        }
    }

    @Test
    void referenceLabelEmbedAndDraftCapsAreTheSpaces(@TempDir Path cfg, @TempDir Path root) throws Exception {
        System.setProperty("la.embed.enabled", "true");
        try (Ctx c = open(cfg, root)) {
            create(c, "case-a");
            String refs = "/inv/investigations/case-a/references";
            assertEquals(200, get(c, refs).get("max").asInt());
            limits(c, "{\"input.references\":1,\"input.reference_label_length\":3,\"input.embed_active_grants\":1,"
                    + "\"input.draft_label_length\":3}");
            assertEquals(1, get(c, refs).get("max").asInt());
            refused(send(c, "POST", refs, "{\"system\":\"crm\",\"type\":\"case\",\"id\":\"1\",\"label\":\"abcd\"}"), 422, "'label' is at most 3 characters");
            post(c, refs, "{\"system\":\"crm\",\"type\":\"case\",\"id\":\"1\",\"label\":\"abc\"}");
            refused(send(c, "POST", refs, "{\"system\":\"crm\",\"type\":\"case\",\"id\":\"2\"}"), 409, "at most 1 external references");

            refused(send(c, "POST", "/inv/investigations/case-a/embeds", "{\"label\":\"abcd\"}"), 422, "'label' is at most 3 characters");
            assertEquals(200, send(c, "POST", "/inv/investigations/case-a/embeds", "{}").statusCode());
            refused(send(c, "POST", "/inv/investigations/case-a/embeds", "{}"), 409, "at most 1 live embed links");

            refused(send(c, "POST", "/inv/investigations/case-a/drafts", "{\"label\":\"abcd\"}"), 422, "'label' must be 1..3 characters");
        }
    }

    @Test
    void identityImportAndKeyCapsAreTheSpaces(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String imp = "{\"dataset\":\"calls_ds\",\"aCol\":\"caller\",\"bCol\":\"callee\",\"reason\":\"limits\",\"limit\":";
            refused(send(c, "POST", "/inv/entity-identities/import", imp + "10001}"), 422, "'limit' must be an integer 1..10000");
            limits(c, "{\"identity.import_max\":5,\"input.identity_key_length\":5}");
            refused(send(c, "POST", "/inv/entity-identities/import", imp + "6}"), 422, "'limit' must be an integer 1..5");
            refused(send(c, "POST", "/inv/entity-identities", "{\"a\":\"msisdn:123456\",\"b\":\"msisdn:1\",\"reason\":\"limits\"}"), 422,
                    "of at most 5 characters");
        }
    }

    /** A stored value outside the item's range never reaches a site: it reads as the default (P1a's rule, seen here at a site). */
    @Test
    void aHandEditOutsideTheRangeReadsAsTheDefaultAtTheSite(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: none\ntraversal:\n  depth_max: 999\n");
            assertEquals(20, post(c, "/inv/traversal/recursive-paths", "{" + COLS + ",\"startNode\":\"a\",\"maxDepth\":99}")
                    .at("/fences/maxDepth").asInt());
        }
    }
}
