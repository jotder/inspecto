package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.ops.OperationalObject;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.workflow.ObjectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Read-time masking of the {@code key.<column>} attributes on {@code GET /objects} and {@code GET /objects/{id}}
 * (TELCO-FRAUD-MASKED-DASHBOARD-1 (c), D-P8): a caller without {@code canRevealLinkEntities} sees the {@code masked:<16 hex>}
 * token where the Space classifies the column MSISDN; a revealer sees the raw number. Real HTTP under an armed
 * authenticator, so every capability check is real. The title is masked too (origin's ObjectReadMasking, 72e0ef611).
 * An Alert is not an object (it lives in the AlertStore, read by {@code GET /alerts}, which carries no attributes).
 */
class ControlApiObjectReadMaskingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RAW = "99970001401";
    private final HttpClient client = HttpClient.newHttpClient();

    private static final Authenticator FAKE = ex -> {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        if (a == null || !a.startsWith("Bearer ")) return Optional.empty();
        String user = a.substring(7);
        return Optional.of(new Subject(user, switch (user) {
            case "revealer" -> Set.of("canRevealLinkEntities");
            case "worker" -> Set.of("canWorkIncidents", "canAdminister");
            case "workrev" -> Set.of("canWorkIncidents", "canAdminister", "canRevealLinkEntities");
            default -> Set.of();
        }));
    };

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
    }

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir, Path root, boolean classify) throws Exception {
        Authenticators.forTest(FAKE);
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            new ComponentStore(root.resolve("registry")).write("dataset", "fraud_ds", Map.of("columns", List.of(
                    Map.of("name", "msisdn", "classification", classify ? "MSISDN" : "INTERNAL"), Map.of("name", "region"))));
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private static OperationalObject open(Ctx c, ObjectType type) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("rule", "tf_rule");
        attrs.put("dataset", "fraud_ds");
        attrs.put("key", "msisdn=" + RAW);
        attrs.put("key.msisdn", RAW);
        attrs.put("key.region", "EU");
        attrs.put("alertKey", "tf_rule|msisdn=" + RAW);
        return TestOpsEngine.of(c.svc).open(type, "tf_rule breached for msisdn=" + RAW, "d", "CRITICAL", null, null, null, null, attrs);
    }

    private HttpResponse<String> get(Ctx c, String user, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Authorization", "Bearer " + user).GET().build(), BodyHandlers.ofString());
    }

    private void assertMasked(JsonNode obj) {
        JsonNode a = obj.get("attributes");
        assertTrue(a.get("key.msisdn").asText().matches("masked:[0-9a-f]{16}"), a.toString());
        assertEquals("msisdn=" + a.get("key.msisdn").asText(), a.get("key").asText());
        assertEquals("tf_rule|msisdn=" + a.get("key.msisdn").asText(), a.get("alertKey").asText());
        assertEquals("EU", a.get("key.region").asText(), "an unclassified column keeps its value");
        assertFalse(a.toString().contains(RAW), "no raw number in any attribute: " + a);
        assertFalse(obj.get("title").asText().contains(RAW), "the title carries the token too: " + obj.get("title"));
    }

    @Test
    void aViewerSeesTheTokenOnListAndDetail(@TempDir Path dir, @TempDir Path root) throws Exception {
        try (Ctx c = open(dir, root, true)) {
            OperationalObject incident = open(c, ObjectType.INCIDENT), second = open(c, ObjectType.INCIDENT);
            for (String id : List.of(incident.id(), second.id())) {
                JsonNode one = JSON.readTree(get(c, "viewer", "/objects/" + id).body()).get("data");
                assertMasked(one);
            }
            JsonNode list = JSON.readTree(get(c, "viewer", "/objects?limit=50").body()).get("data");
            assertEquals(2, list.size());
            list.forEach(this::assertMasked);
            String token = list.get(0).get("attributes").get("key.msisdn").asText();
            JsonNode forRevealer = JSON.readTree(get(c, "revealer", "/objects/" + incident.id()).body()).get("data");
            assertNotEquals(token, forRevealer.get("attributes").get("key.msisdn").asText());
        }
    }

    @Test
    void aRevealerSeesTheRawKey(@TempDir Path dir, @TempDir Path root) throws Exception {
        try (Ctx c = open(dir, root, true)) {
            OperationalObject incident = open(c, ObjectType.INCIDENT);
            JsonNode a = JSON.readTree(get(c, "revealer", "/objects/" + incident.id()).body()).get("data").get("attributes");
            assertEquals(RAW, a.get("key.msisdn").asText());
            assertEquals("msisdn=" + RAW, a.get("key").asText());
            JsonNode list = JSON.readTree(get(c, "revealer", "/objects").body()).get("data");
            assertEquals(RAW, list.get(0).get("attributes").get("key.msisdn").asText());
        }
    }

    @Test
    void aColumnNotClassifiedMsisdnIsNotMasked(@TempDir Path dir, @TempDir Path root) throws Exception {
        try (Ctx c = open(dir, root, false)) {
            OperationalObject incident = open(c, ObjectType.INCIDENT);
            JsonNode a = JSON.readTree(get(c, "viewer", "/objects/" + incident.id()).body()).get("data").get("attributes");
            assertEquals(RAW, a.get("key.msisdn").asText());
        }
    }

    private HttpResponse<String> send(Ctx c, String user, String method, String path, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Authorization", "Bearer " + user).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json)).build(), BodyHandlers.ofString());
    }

    /** Every write route that echoes the object (ack, assign, transition, PATCH, category, postmortem, impact, findings). */
    private List<HttpResponse<String>> mutate(Ctx c, String user, String id) throws Exception {
        String p = "/objects/" + id;
        return List.of(send(c, user, "POST", p + "/ack", "{}"),
                send(c, user, "POST", p + "/assign", "{\"assignee\":\"ana\"}"),
                send(c, user, "POST", p + "/transition", "{\"action\":\"ack\"}"),
                send(c, user, "PATCH", p, "{\"priority\":\"P1\"}"),
                send(c, user, "PUT", p + "/category", "{\"category\":\"fraud\"}"),
                send(c, user, "PUT", p + "/postmortem", "{\"postmortem\":{\"summary\":\"s\"}}"),
                send(c, user, "PUT", p + "/impact", "{\"impact\":{\"suspected\":\"100\",\"currency\":\"USD\"}}"),
                send(c, user, "PUT", p + "/findings", "{\"findings\":{}}"));
    }

    @Test
    void aMutationEchoCarriesTheTokenForANonRevealerAndTheRawKeyForARevealer(@TempDir Path dir, @TempDir Path root) throws Exception {
        try (Ctx c = open(dir, root, true)) {
            String id = open(c, ObjectType.INCIDENT).id();
            int echoed = 0;
            for (HttpResponse<String> r : mutate(c, "worker", id)) {
                if (r.statusCode() != 200) continue;   // a refusal (e.g. findings on an Incident) echoes no object
                echoed++;
                assertMasked(JSON.readTree(r.body()).get("data"));
            }
            assertTrue(echoed >= 5, "ack/assign/PATCH/category/postmortem/impact echo the object: " + echoed);
            for (HttpResponse<String> r : mutate(c, "workrev", id)) {
                if (r.statusCode() != 200) continue;
                assertEquals(RAW, JSON.readTree(r.body()).get("data").get("attributes").get("key.msisdn").asText());
            }
        }
    }

    @Test
    void aMutationEchoOfAnObjectWithNoDatasetFailsClosed(@TempDir Path dir, @TempDir Path root) throws Exception {
        try (Ctx c = open(dir, root, true)) {
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("key.msisdn", RAW);      // no `dataset`: cannot prove the column unclassified
            String id = TestOpsEngine.of(c.svc).open(ObjectType.INCIDENT, "hand made " + RAW, "d", "HIGH", null, null, null, null, attrs).id();
            JsonNode viewer = JSON.readTree(get(c, "viewer", "/objects/" + id).body()).get("data");
            assertTrue(viewer.get("attributes").get("key.msisdn").asText().startsWith("masked:"), viewer.toString());
            assertFalse(viewer.get("title").asText().contains(RAW));
            HttpResponse<String> acked = send(c, "worker", "POST", "/objects/" + id + "/assign", "{\"assignee\":\"ana\"}");
            assertEquals(200, acked.statusCode(), acked.body());
            JsonNode echo = JSON.readTree(acked.body()).get("data");
            assertTrue(echo.get("attributes").get("key.msisdn").asText().startsWith("masked:"), echo.toString());
            assertEquals(RAW, JSON.readTree(get(c, "revealer", "/objects/" + id).body()).get("data")
                    .get("attributes").get("key.msisdn").asText());
        }
    }

    @Test
    void byEntityDoesNotConfirmAClassifiedKeyToANonRevealer(@TempDir Path dir, @TempDir Path root) throws Exception {
        try (Ctx c = open(dir, root, true)) {
            open(c, ObjectType.INCIDENT);
            String q = "/objects/by-entity?type=msisdn&id=" + RAW;
            assertEquals(0, JSON.readTree(get(c, "viewer", q).body()).get("data").size(), "indistinguishable from absence");
            JsonNode hit = JSON.readTree(get(c, "revealer", q).body()).get("data");
            assertEquals(1, hit.size());
        }
    }

    /** LA-CASE-HISTORY-UPLOAD-FOLLOWUPS-1 (1): Link Analysis's case history reads through the engine seam, which must
     *  hide exactly what the route hides — a non-revealer learns of no object matched through a classified key. */
    @Test
    void theEngineSeamBehindCaseHistoryHidesWhatTheRouteHides(@TempDir Path dir, @TempDir Path root) throws Exception {
        try (Ctx c = open(dir, root, true)) {
            open(c, ObjectType.INCIDENT);
            var seam = c.svc.objects().orElseThrow();
            for (String who : List.of("viewer", "revealer")) {
                int http = JSON.readTree(get(c, who, "/objects/by-entity?type=msisdn&id=" + RAW).body()).get("data").size();
                var ex = new com.gamma.spi.auth.SweepExchange("/objects/by-entity");
                com.gamma.spi.auth.RequestAttrs.attr(ex, com.gamma.spi.auth.RequestAttrs.ATTR_SUBJECT,
                        new Subject(who, "revealer".equals(who) ? Set.of("canRevealLinkEntities") : Set.of()));
                try {
                    Map<String, Object> r = seam.byEntity(root, ex, "msisdn", RAW, null, 50);
                    assertEquals(http, ((List<?>) r.get("objects")).size(), who + " seam must match the route");
                    assertEquals(http, r.get("total"), who);
                } finally {
                    com.gamma.spi.auth.RequestAttrs.REQUEST_SCOPES.remove(ex);
                }
            }
        }
    }

    @Test
    void byEntityStillMatchesAnUnclassifiedKeyForANonRevealer(@TempDir Path dir, @TempDir Path root) throws Exception {
        try (Ctx c = open(dir, root, false)) {
            open(c, ObjectType.INCIDENT);
            assertEquals(1, JSON.readTree(get(c, "viewer", "/objects/by-entity?type=msisdn&id=" + RAW).body()).get("data").size());
        }
    }

    @Test
    void anEntityMintedObjectMasksItsEntityKey(@TempDir Path dir, @TempDir Path root) throws Exception {
        try (Ctx c = open(dir, root, true)) {
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("entityKey", "entity:msisdn:" + RAW);
            attrs.put("entityDataset", "fraud_ds");
            String id = TestOpsEngine.of(c.svc).open(ObjectType.INCIDENT, "Entity " + RAW, "d", "HIGH", null, null, null, null, attrs).id();
            JsonNode v = JSON.readTree(get(c, "viewer", "/objects/" + id).body()).get("data");
            assertFalse(v.toString().contains(RAW), v.toString());
            assertTrue(v.get("attributes").get("entityKey").asText().startsWith("entity:msisdn:masked:"));
            assertEquals(0, JSON.readTree(get(c, "viewer", "/objects/by-entity?type=msisdn&id=" + RAW).body()).get("data").size());
            assertTrue(get(c, "revealer", "/objects/" + id).body().contains(RAW));
        }
    }

    // ── OBJECT-READ-MASKING follow-ups: graph titles, OBJECT_OPENED events, q / correlationId probes ──

    private static String ids(JsonNode rows) {
        StringBuilder b = new StringBuilder();
        rows.forEach(r -> b.append(r.get("id").asText()).append(' '));
        return b.toString();
    }

    @Test
    void theGraphMasksEachNodeTitleWithThatNodesOwnKeys(@TempDir Path dir, @TempDir Path root) throws Exception {
        try (Ctx c = open(dir, root, true)) {
            OperationalObject a = open(c, ObjectType.INCIDENT), b = open(c, ObjectType.INCIDENT);
            TestOpsEngine.of(c.svc).link(a.id(), b.id(), "RELATES_TO", "t");
            JsonNode viewer = JSON.readTree(get(c, "viewer", "/objects/" + a.id() + "/graph").body()).get("data");
            assertEquals(2, viewer.get("nodes").size());
            assertFalse(viewer.toString().contains(RAW), viewer.toString());
            viewer.get("nodes").forEach(n -> assertTrue(n.get("title").asText().contains("masked:"), n.toString()));
            assertTrue(get(c, "revealer", "/objects/" + a.id() + "/graph").body().contains(RAW));
        }
    }

    @Test
    void qAndCorrelationIdCannotProbeARawKeyForANonRevealer(@TempDir Path dir, @TempDir Path root) throws Exception {
        try (Ctx c = open(dir, root, true)) {
            OperationalObject o = open(c, ObjectType.INCIDENT);
            Map<String, String> attrs = new LinkedHashMap<>(o.attributes());
            OperationalObject withCid = TestOpsEngine.of(c.svc).open(ObjectType.INCIDENT, "other " + RAW, "d", "HIGH",
                    null, null, null, "case-" + RAW, attrs);
            for (String base : List.of("/objects", "/objects?limit=50&offset=0")) {
                assertEquals(0, JSON.readTree(get(c, "viewer", base + (base.contains("?") ? "&" : "?") + "q=" + RAW).body()).get("data").size(), "viewer q=raw");
                assertEquals(0, JSON.readTree(get(c, "viewer", base + (base.contains("?") ? "&" : "?") + "correlationId=case-" + RAW).body()).get("data").size(), "viewer correlationId=raw");
            }
            JsonNode page = JSON.readTree(get(c, "viewer", "/objects?q=" + RAW).body());
            assertEquals(0, page.get("metadata").get("pagination").get("total").asInt(), "the total counts the post-filtered set");
            assertEquals(2, JSON.readTree(get(c, "revealer", "/objects?q=" + RAW).body()).get("data").size());
            assertEquals(1, JSON.readTree(get(c, "revealer", "/objects?correlationId=case-" + RAW).body()).get("data").size());
            // the masked token a viewer SEES is matchable (documented): it filters the rows that show it
            String token = JSON.readTree(get(c, "viewer", "/objects/" + o.id()).body()).get("data").get("attributes").get("key.msisdn").asText();
            assertEquals(2, JSON.readTree(get(c, "viewer", "/objects?q=" + token).body()).get("data").size());
            String maskedCid = JSON.readTree(get(c, "viewer", "/objects/" + withCid.id()).body()).get("data").get("correlationId").asText();
            assertEquals(ids(JSON.readTree(get(c, "viewer", "/objects?correlationId=" + maskedCid).body()).get("data")).trim(), withCid.id());
        }
    }

    @Test
    void aCommentThatCarriesTheKeyIsMaskedForANonRevealerAndLinksAreStructuralOnly(@TempDir Path dir, @TempDir Path root) throws Exception {
        try (Ctx c = open(dir, root, true)) {
            String id = open(c, ObjectType.INCIDENT).id(), other = open(c, ObjectType.INCIDENT).id();
            assertEquals(200, send(c, "workrev", "POST", "/objects/" + id + "/comments",
                    "{\"body\":\"called " + RAW + " back\"}").statusCode());
            for (String path : List.of("/objects/" + id + "/comments", "/notes/object/" + id + "/comments")) {
                String viewer = get(c, "viewer", path).body();
                assertFalse(viewer.contains(RAW), path + ": " + viewer);
                assertTrue(viewer.contains("called masked:"), path + ": " + viewer);
                assertTrue(get(c, "revealer", path).body().contains("called " + RAW + " back"), path);
            }
            // The comment POST echo for a non-revealer is masked too.
            assertFalse(send(c, "worker", "POST", "/objects/" + id + "/comments", "{\"body\":\"x " + RAW + "\"}").body().contains(RAW));
            // ... and so are the attachment-create and RCA-apply echoes; a revealer's echo keeps the raw key.
            String attach = "{\"name\":\"cdr\",\"uri\":\"file:///e/x\",\"caption\":\"for " + RAW + "\"}";
            String rca = "{\"sections\":[\"Called " + RAW + "\"]}";
            for (String[] call : List.of(new String[]{"/attachments", attach}, new String[]{"/rca", rca})) {
                var masked = send(c, "worker", "POST", "/objects/" + id + call[0], call[1]);
                assertEquals(200, masked.statusCode(), masked.body());
                assertFalse(masked.body().contains(RAW), call[0] + ": " + masked.body());
                assertTrue(masked.body().contains("masked:"), call[0] + ": " + masked.body());
                assertTrue(send(c, "workrev", "POST", "/objects/" + id + call[0], call[1]).body().contains(RAW), call[0]);
            }
            // ObjectLink carries ids, types, a relationship word and a timestamp, no free text: pin the exact shape.
            assertEquals(200, send(c, "worker", "POST", "/objects/" + id + "/links", "{\"to\":\"" + other + "\"}").statusCode());
            JsonNode links = JSON.readTree(get(c, "viewer", "/objects/" + id + "/links").body()).get("data");
            assertTrue(links.size() >= 1);
            links.forEach(l -> {
                List<String> names = new java.util.ArrayList<>();
                l.fieldNames().forEachRemaining(names::add);
                assertEquals(List.of("from", "fromType", "to", "toType", "relationship", "createdAt"), names);
                assertFalse(l.toString().contains(RAW));
            });
        }
    }
}
