package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.ops.ObjectType;
import com.gamma.ops.OperationalObject;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tags + Tag Rules over real HTTP (GLOSSARY §9): the tag registry and rule CRUD behind the
 * fail-closed write gates (write-root 503 → unsafe/invalid 422 → duplicate 409), atomic
 * {@code *_tag.toon}/{@code *_tagrule.toon} persistence, bulk apply, and the create-time
 * auto-apply hook. Modeled on {@code ControlApiQueueRoutesTest} / {@code ControlApiConfigWriteTest}.
 */
class ControlApiTagRoutesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir, Path writeRoot) throws Exception {
        if (writeRoot != null) {
            Files.createDirectories(writeRoot);
            System.setProperty("assist.write.root", writeRoot.toString());
        } else {
            System.clearProperty("assist.write.root");
        }
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    @AfterEach
    void clearWriteRoot() {
        System.clearProperty("assist.write.root");
    }

    @Test
    void writesFailClosedWithoutWriteRootButReadsStayOpen(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, null)) {
            assertEquals(503, send(c.port, "POST", "/tags", "{\"name\":\"urgent\"}").statusCode());
            assertEquals(503, send(c.port, "POST", "/tags/rules",
                    "{\"name\":\"r\",\"tag\":\"t\",\"filter\":{\"priority\":\"CRITICAL\"}}").statusCode());
            assertEquals(503, send(c.port, "DELETE", "/tags/rules/r", null).statusCode());
            assertEquals(200, send(c.port, "GET", "/tags", null).statusCode());
            assertEquals(200, send(c.port, "GET", "/tags/rules", null).statusCode());
        }
    }

    @Test
    void createValidatePersistAndListTags(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        try (Ctx c = open(dir, writeRoot)) {
            JsonNode created = json(send(c.port, "POST", "/tags", "{\"name\":\"urgent\"}"));
            assertEquals("urgent", created.get("name").asText());
            assertTrue(Files.exists(writeRoot.resolve("urgent_tag.toon")), "tag persisted for the boot rescan");

            // gates: duplicate → 409; blank / comma / path-escaping names → 422
            assertEquals(409, send(c.port, "POST", "/tags", "{\"name\":\"urgent\"}").statusCode());
            assertEquals(422, send(c.port, "POST", "/tags", "{\"name\":\"  \"}").statusCode());
            assertEquals(422, send(c.port, "POST", "/tags", "{\"name\":\"a,b\"}").statusCode());
            assertEquals(422, send(c.port, "POST", "/tags", "{\"name\":\"../evil\"}").statusCode());

            send(c.port, "POST", "/tags", "{\"name\":\"billing\"}");
            JsonNode list = json(send(c.port, "GET", "/tags", null));
            assertEquals(2, list.size());
            assertEquals("billing", list.get(0).get("name").asText(), "sorted by name");
            assertEquals("urgent", list.get(1).get("name").asText());
        }
    }

    @Test
    void tagRuleSaveApplyAutoApplyAndDelete(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        try (Ctx c = open(dir, writeRoot)) {
            OperationalObject critical = c.svc.objects().open(ObjectType.INCIDENT, "rejected files spike", "d",
                    "HIGH", "CRITICAL", null, null, "corr", Map.of());
            c.svc.objects().open(ObjectType.INCIDENT, "minor glitch", "d", "LOW", "LOW", null, null, "corr", Map.of());

            // a rule without criteria would tag everything → 422
            assertEquals(422, send(c.port, "POST", "/tags/rules",
                    "{\"name\":\"all\",\"tag\":\"x\",\"filter\":{}}").statusCode());

            // save; the rule's tag is implicitly registered and the rule is persisted
            JsonNode rule = json(send(c.port, "POST", "/tags/rules",
                    "{\"name\":\"critical-is-urgent\",\"tag\":\"urgent\",\"filter\":{\"type\":\"INCIDENT\",\"priority\":\"CRITICAL\"}}"));
            assertEquals("urgent", rule.get("tag").asText());
            assertTrue(Files.exists(writeRoot.resolve("critical-is-urgent_tagrule.toon")));
            assertEquals("urgent", json(send(c.port, "GET", "/tags", null)).get(0).get("name").asText(),
                    "saving a rule registers its tag");
            assertEquals(1, json(send(c.port, "GET", "/tags/rules", null)).size());

            // bulk apply: tags the matching incident once; idempotent on re-apply
            JsonNode applied = json(send(c.port, "POST", "/tags/rules/critical-is-urgent/apply", "{}"));
            assertEquals(1, applied.get("matched").asInt());
            assertEquals(1, applied.get("updated").asInt());
            JsonNode again = json(send(c.port, "POST", "/tags/rules/critical-is-urgent/apply", "{}"));
            assertEquals(1, again.get("matched").asInt());
            assertEquals(0, again.get("updated").asInt(), "idempotent — already tagged");
            JsonNode tagged = json(send(c.port, "GET", "/objects/" + critical.id(), null));
            assertEquals("urgent", tagged.get("attributes").get("tags").asText());

            // auto-apply: a new matching object is tagged at creation (links satisfy the ≥1-link contract)
            JsonNode created = json(send(c.port, "POST", "/objects",
                    "{\"title\":\"gateway down\",\"priority\":\"CRITICAL\",\"links\":[{\"to\":\"" + critical.id() + "\"}]}"));
            assertEquals("urgent", created.get("attributes").get("tags").asText(),
                    "Tag Rules auto-apply on create (Gmail-filter semantics)");
            JsonNode nonMatch = json(send(c.port, "POST", "/objects",
                    "{\"title\":\"small thing\",\"priority\":\"LOW\",\"links\":[{\"to\":\"" + critical.id() + "\"}]}"));
            assertFalse(nonMatch.get("attributes").has("tags"), "non-matching objects stay untagged");

            // unknown rule → 404 on apply; delete removes registry entry + file, then 404s
            assertEquals(404, send(c.port, "POST", "/tags/rules/ghost/apply", "{}").statusCode());
            JsonNode deleted = json(send(c.port, "DELETE", "/tags/rules/critical-is-urgent", null));
            assertTrue(deleted.get("fileRemoved").asBoolean());
            assertFalse(Files.exists(writeRoot.resolve("critical-is-urgent_tagrule.toon")));
            assertEquals(404, send(c.port, "DELETE", "/tags/rules/critical-is-urgent", null).statusCode());
            assertEquals(0, json(send(c.port, "GET", "/tags/rules", null)).size());
        }
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return V1Body.of(r.body());
    }


    // ── cross-entity tag assignments (BACKLOG D7) ────────────────────────────────────────────────

    @Test
    void appliesATagToAnObjectAndListsItBothWays(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        try (Ctx c = open(dir, writeRoot)) {
            OperationalObject inc = c.svc.objects().open(ObjectType.INCIDENT, "spike", "d",
                    "HIGH", "CRITICAL", null, null, "corr", Map.of());
            assertEquals(200, send(c.port, "POST", "/tags", "{\"name\":\"q3-audit\"}").statusCode());

            HttpResponse<String> applied = send(c.port, "POST",
                    "/tags/assignments/object/" + inc.id(), "{\"tag\":\"q3-audit\",\"actor\":\"alice\"}");
            assertEquals(200, applied.statusCode());
            assertEquals("q3-audit", JSON.readTree(applied.body()).at("/data/tag").asText());

            JsonNode on = JSON.readTree(send(c.port, "GET",
                    "/tags/assignments/object/" + inc.id(), null).body());
            assertEquals("q3-audit", on.at("/data/tags/0").asText());

            JsonNode targets = JSON.readTree(send(c.port, "GET", "/tags/q3-audit/targets", null).body());
            assertEquals(1, targets.at("/data").size());
            assertEquals(inc.id(), targets.at("/data/0/targetId").asText());
            assertEquals("object", targets.at("/data/0/targetKind").asText());
        }
    }

    @Test
    void reapplyingIsIdempotentAndUnassignIsToo(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        try (Ctx c = open(dir, writeRoot)) {
            OperationalObject inc = c.svc.objects().open(ObjectType.INCIDENT, "spike", "d",
                    "HIGH", "CRITICAL", null, null, "corr", Map.of());
            send(c.port, "POST", "/tags", "{\"name\":\"q3-audit\"}");
            String path = "/tags/assignments/object/" + inc.id();

            send(c.port, "POST", path, "{\"tag\":\"q3-audit\"}");
            assertEquals(200, send(c.port, "POST", path, "{\"tag\":\"q3-audit\"}").statusCode());
            assertEquals(1, JSON.readTree(send(c.port, "GET", "/tags/q3-audit/targets", null).body())
                    .at("/data").size(), "re-applying must not create a second edge");

            HttpResponse<String> first = send(c.port, "DELETE", path + "/q3-audit", null);
            assertEquals(200, first.statusCode());
            assertTrue(JSON.readTree(first.body()).at("/data/removed").asBoolean());
            // Already gone is success with removed=false, not a 404 — the UI may retry a failed request.
            assertFalse(JSON.readTree(send(c.port, "DELETE", path + "/q3-audit", null).body())
                    .at("/data/removed").asBoolean());
        }
    }

    @Test
    void refusesAnUnknownTagAnUnknownKindAndAnAbsentTarget(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        try (Ctx c = open(dir, writeRoot)) {
            OperationalObject inc = c.svc.objects().open(ObjectType.INCIDENT, "spike", "d",
                    "HIGH", "CRITICAL", null, null, "corr", Map.of());

            // A typo must not silently mint a tag — that is how a tag vocabulary rots.
            assertEquals(404, send(c.port, "POST", "/tags/assignments/object/" + inc.id(),
                    "{\"tag\":\"nope\"}").statusCode());
            assertEquals(400, send(c.port, "POST", "/tags/assignments/not-a-kind/x",
                    "{\"tag\":\"nope\"}").statusCode());
            assertEquals(404, send(c.port, "POST", "/tags/assignments/object/NOPE-404",
                    "{\"tag\":\"nope\"}").statusCode());
            assertEquals(400, send(c.port, "POST", "/tags/assignments/object/" + inc.id(),
                    "{}").statusCode(), "missing 'tag' is a 400");
        }
    }

    @Test
    void oneTagSpansKindsAndAnUnknownTagListsEmpty(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        try (Ctx c = open(dir, writeRoot)) {
            OperationalObject a = c.svc.objects().open(ObjectType.INCIDENT, "one", "d",
                    "HIGH", "CRITICAL", null, null, "corr", Map.of());
            OperationalObject b = c.svc.objects().open(ObjectType.INCIDENT, "two", "d",
                    "LOW", "LOW", null, null, "corr", Map.of());
            send(c.port, "POST", "/tags", "{\"name\":\"q3-audit\"}");
            send(c.port, "POST", "/tags/assignments/object/" + a.id(), "{\"tag\":\"q3-audit\"}");
            send(c.port, "POST", "/tags/assignments/object/" + b.id(), "{\"tag\":\"q3-audit\"}");

            assertEquals(2, JSON.readTree(send(c.port, "GET", "/tags/q3-audit/targets", null).body())
                    .at("/data").size());
            // An unknown tag is an empty list, not a 404: "nothing is labelled that" is a valid answer.
            assertEquals(0, JSON.readTree(send(c.port, "GET", "/tags/never-used/targets", null).body())
                    .at("/data").size());
        }
    }

    @Test
    void renamingATagMovesTheEdgesTheCsvTheRuleAndTheFile(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        try (Ctx c = open(dir, writeRoot)) {
            OperationalObject inc = c.svc.objects().open(ObjectType.INCIDENT, "spike", "d",
                    "HIGH", "CRITICAL", null, null, "corr", Map.of());
            send(c.port, "POST", "/tags/rules",
                    "{\"name\":\"criticals\",\"tag\":\"urgent\",\"filter\":{\"severity\":\"CRITICAL\"}}");
            send(c.port, "POST", "/tags/assignments/object/" + inc.id(), "{\"tag\":\"urgent\"}");

            JsonNode renamed = json(send(c.port, "POST", "/tags/urgent/rename", "{\"to\":\"sev1\"}"));
            assertEquals(1, renamed.get("assignments").asInt());
            assertEquals(1, renamed.get("objects").asInt(), "the object CSV must be re-projected");
            assertEquals("criticals", renamed.get("rules").get(0).asText(), "rules follow the rename");

            assertEquals("sev1", json(send(c.port, "GET", "/objects/" + inc.id(), null))
                    .get("attributes").get("tags").asText());
            assertEquals(1, JSON.readTree(send(c.port, "GET", "/tags/sev1/targets", null).body())
                    .at("/data").size());
            assertEquals(0, JSON.readTree(send(c.port, "GET", "/tags/urgent/targets", null).body())
                    .at("/data").size());
            assertEquals("sev1", json(send(c.port, "GET", "/tags/rules", null)).get(0).get("tag").asText());

            // The registry must survive a restart under the NEW name only.
            assertTrue(Files.exists(writeRoot.resolve("sev1_tag.toon")));
            assertFalse(Files.exists(writeRoot.resolve("urgent_tag.toon")));
            assertEquals(404, send(c.port, "POST", "/tags/urgent/rename", "{\"to\":\"x\"}").statusCode());
            assertEquals(400, send(c.port, "POST", "/tags/sev1/rename", "{}").statusCode());
            assertEquals(422, send(c.port, "POST", "/tags/sev1/rename", "{\"to\":\"a,b\"}").statusCode());
        }
    }

    @Test
    void deletingATagRemovesItsAssignmentsButNotWhileARuleAppliesIt(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        try (Ctx c = open(dir, writeRoot)) {
            OperationalObject inc = c.svc.objects().open(ObjectType.INCIDENT, "spike", "d",
                    "HIGH", "CRITICAL", null, null, "corr", Map.of());
            send(c.port, "POST", "/tags/rules",
                    "{\"name\":\"criticals\",\"tag\":\"urgent\",\"filter\":{\"severity\":\"CRITICAL\"}}");
            send(c.port, "POST", "/tags", "{\"name\":\"q3-audit\"}");
            send(c.port, "POST", "/tags/assignments/object/" + inc.id(), "{\"tag\":\"q3-audit\"}");
            send(c.port, "POST", "/tags/assignments/object/" + inc.id(), "{\"tag\":\"urgent\"}");

            // The rule would re-create it on the next matching object, so deleting it is a conflict.
            assertEquals(409, send(c.port, "DELETE", "/tags/urgent", null).statusCode());

            JsonNode deleted = json(send(c.port, "DELETE", "/tags/q3-audit", null));
            assertEquals(1, deleted.get("assignments").asInt());
            assertTrue(deleted.get("fileRemoved").asBoolean());
            assertFalse(Files.exists(writeRoot.resolve("q3-audit_tag.toon")));
            assertEquals("urgent", json(send(c.port, "GET", "/objects/" + inc.id(), null))
                    .get("attributes").get("tags").asText(), "only the deleted label leaves the CSV");
            assertEquals(0, JSON.readTree(send(c.port, "GET", "/tags/q3-audit/targets", null).body())
                    .at("/data").size(), "no orphaned edges may survive the tag");
            assertEquals(404, send(c.port, "DELETE", "/tags/q3-audit", null).statusCode());
        }
    }

    // ── D7 (c): a widget's `tags` array is a projection of the assignment store ──────────────────

    /** The `tags` array persisted in the widget component, as the API serves it. */
    private List<String> widgetTags(Ctx c, String id) throws Exception {
        JsonNode tags = json(send(c.port, "GET", "/components/widget/" + id, null)).at("/content/tags");
        List<String> out = new java.util.ArrayList<>();
        tags.forEach(t -> out.add(t.asText()));
        return out;
    }

    @Test
    void aWidgetsTagsArrayIsDerivedFromTheAssignmentStore(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("cfg"))) {
            // Create carries its tags INSIDE the config (a bundle/seed/template shape): they are adopted
            // as edges and the free-text names are registered, because assigning an unregistered tag 404s.
            assertEquals(200, send(c.port, "POST", "/components/widget",
                    "{\"id\":\"revenue\",\"vizType\":\"kpi\",\"tags\":[\"ra\",\"billing\"]}").statusCode());
            assertEquals(List.of("billing", "ra"), widgetTags(c, "revenue"), "alphabetical, from the edges");
            assertEquals(List.of("billing", "ra"), List.of(
                    json(send(c.port, "GET", "/tags/assignments/widget/revenue", null)).get("tags").get(0).asText(),
                    json(send(c.port, "GET", "/tags/assignments/widget/revenue", null)).get("tags").get(1).asText()));
            assertTrue(json(send(c.port, "GET", "/tags", null)).toString().contains("\"ra\""),
                    "the config's free-text tag was registered in the vocabulary");

            // An UPDATE may not resurrect a tag: the submitted array is overwritten from the edges, so a
            // stale client re-saving the widget cannot undo a removal made through the tags dialog.
            send(c.port, "DELETE", "/tags/assignments/widget/revenue/billing", null);
            assertEquals(List.of("ra"), widgetTags(c, "revenue"), "the chip goes with the edge");
            assertEquals(200, send(c.port, "PUT", "/components/widget/revenue",
                    "{\"vizType\":\"kpi\",\"tags\":[\"ra\",\"billing\",\"invented\"]}").statusCode());
            assertEquals(List.of("ra"), widgetTags(c, "revenue"), "edges are the truth on update");

            // Assigning through the shared dialog's route updates the chips as well as the edge.
            send(c.port, "POST", "/tags", "{\"name\":\"fraud\"}");
            send(c.port, "POST", "/tags/assignments/widget/revenue", "{\"tag\":\"fraud\"}");
            assertEquals(List.of("fraud", "ra"), widgetTags(c, "revenue"));
        }
    }

    @Test
    void aVocabularyChangeMovesWidgetProjectionsToo(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("cfg"))) {
            send(c.port, "POST", "/components/widget",
                    "{\"id\":\"usage\",\"vizType\":\"bar\",\"tags\":[\"ra\"]}");

            // Before D7 (c) `renameTag` skipped component targets ("no CSV to project"), which would have
            // left a stale array on every widget. The component half is composed at the edge, in TagRoutes.
            JsonNode renamed = json(send(c.port, "POST", "/tags/ra/rename", "{\"to\":\"revenue-assurance\"}"));
            assertEquals(1, renamed.get("widgets").asInt());
            assertEquals(List.of("revenue-assurance"), widgetTags(c, "usage"));

            JsonNode deleted = json(send(c.port, "DELETE", "/tags/revenue-assurance", null));
            assertEquals(1, deleted.get("widgets").asInt());
            assertTrue(widgetTags(c, "usage").isEmpty(), "an empty projection is removed, not left as []");
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
