package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.objects.ObjectType;
import com.gamma.ops.ObjectQuery;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code POST /cases/from-entities} over real HTTP (LA-CASE-CREATE-IN-PLACE-1, operator decision 2026-09-23:
 * <i>mint from the node</i>). A Link Analysis Entity becomes an INCIDENT keyed by its node id + source
 * Dataset, the new Case CONTAINS it, minting the same Entity again REUSES the object, the route is gated on
 * {@code canManageIncidents} for a Subject that lacks it (not merely with no Subject, where nothing is
 * checked), and a refused member leaves nothing behind — no orphan Case, no half-minted Incident.
 */
class ControlApiCaseFromEntitiesTest {

    private final HttpClient client = HttpClient.newHttpClient();

    /** {@code Bearer manager} → canManageIncidents; {@code Bearer analyst} → authenticated, WITHOUT it. */
    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if ("Bearer manager".equals(auth)) return Optional.of(new Subject("mia", Set.of("canManageIncidents")));
        if ("Bearer analyst".equals(auth)) return Optional.of(new Subject("ana", Set.of("canOperateRuns")));
        return Optional.empty();
    };

    private static final String TWO_ENTITIES = """
            {"title":"Layering ring","description":"from the graph","entities":[
              {"id":"entity:acme ltd","dataset":"orders","label":"ACME Ltd"},
              {"id":"entity:account:bob","dataset":"orders","label":"Bob"}]}""";

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
    }

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Authenticators.forTest(FAKE);
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    @Test
    void mintsAnIncidentPerEntityAndOpensACaseContainingThem(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = post(c.port, "/cases/from-entities", TWO_ENTITIES, "manager");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode body = V1Body.of(r.body());
            JsonNode kase = body.get("case");
            assertEquals("CASE", kase.get("objectType").asText());
            assertEquals("Layering ring", kase.get("title").asText());

            JsonNode members = body.get("members");
            assertEquals(2, members.size());
            for (JsonNode m : members) {
                assertEquals("INCIDENT", m.get("objectType").asText());
                assertTrue(m.get("minted").asBoolean(), "a first mint is new");
                assertEquals("orders", m.get("attributes").get("entityDataset").asText());
            }
            assertEquals("entity:acme ltd", members.get(0).get("attributes").get("entityKey").asText());
            assertEquals("ACME Ltd", members.get(0).get("title").asText(), "the label, never the lowercase key");

            // The Case CONTAINS both — the 2026-07-22 rule holds: it is born with members.
            JsonNode links = V1Body.of(get(c.port, "/objects/" + kase.get("id").asText() + "/links", "manager").body());
            assertEquals(2, links.size());
            for (JsonNode l : links) assertEquals("CONTAINS", l.get("relationship").asText().toUpperCase());
        }
    }

    @Test
    void mintingTheSameEntityAgainReusesItsObject(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String first = V1Body.of(post(c.port, "/cases/from-entities", TWO_ENTITIES, "manager").body())
                    .get("members").get(0).get("id").asText();

            HttpResponse<String> again = post(c.port, "/cases/from-entities", """
                    {"title":"Second look","entities":[
                      {"id":"entity:acme ltd","dataset":"orders","label":"Acme  Ltd."},
                      {"id":"entity:acme ltd","dataset":"invoices","label":"ACME Ltd"}]}""", "manager");
            assertEquals(200, again.statusCode(), again.body());
            JsonNode members = V1Body.of(again.body()).get("members");
            assertEquals(2, members.size());
            assertEquals(first, members.get(0).get("id").asText(), "same key + same Dataset → the SAME object");
            assertFalse(members.get(0).get("minted").asBoolean());
            assertNotEquals(first, members.get(1).get("id").asText(), "same key, another Dataset → another Entity");
            assertTrue(members.get(1).get("minted").asBoolean());

            List<OperationalObject> incidents = TestOpsEngine.of(c.svc).query(ObjectQuery.builder()
                    .objectType(ObjectType.INCIDENT).limit(ObjectQuery.MAX_LIMIT).build());
            assertEquals(3, incidents.size(), "acme@orders, bob@orders, acme@invoices — no duplicate");
            assertEquals(2, TestOpsEngine.of(c.svc).query(ObjectQuery.builder()
                    .objectType(ObjectType.CASE).limit(10).build()).size());
        }
    }

    @Test
    void refusedToASubjectWithoutCanManageIncidentsAndNothingIsCreated(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> denied = post(c.port, "/cases/from-entities", TWO_ENTITIES, "analyst");
            assertEquals(403, denied.statusCode(), denied.body());
            assertTrue(denied.body().contains("canManageIncidents"), "the missing capability is named");
            assertTrue(TestOpsEngine.of(c.svc).query(ObjectQuery.recent(10)).isEmpty());
        }
    }

    @Test
    void aRefusedMemberLeavesNoOrphanCaseAndNoMintedIncident(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // an unknown Incident named beside a valid Entity → 404, and the valid Entity was NOT minted
            HttpResponse<String> missing = post(c.port, "/cases/from-entities", """
                    {"title":"t","entities":[{"id":"entity:acme ltd","dataset":"orders"},{"objectId":"nope"}]}""",
                    "manager");
            assertEquals(404, missing.statusCode(), missing.body());

            // an existing object that is not an Incident → 422, same guarantee
            OperationalObject alert = TestOpsEngine.of(c.svc).open(ObjectType.ALERT, "disk full", "m", "HIGH",
                    "pipeA", Map.of());
            HttpResponse<String> wrongType = post(c.port, "/cases/from-entities",
                    "{\"title\":\"t\",\"entities\":[{\"id\":\"entity:acme ltd\",\"dataset\":\"orders\"},"
                            + "{\"objectId\":\"" + alert.id() + "\"}]}", "manager");
            assertEquals(422, wrongType.statusCode(), wrongType.body());

            List<OperationalObject> all = TestOpsEngine.of(c.svc).query(ObjectQuery.recent(10));
            assertEquals(List.of(alert.id()), all.stream().map(OperationalObject::id).toList(),
                    "only the seeded Alert exists — no Case, no minted Incident");
        }
    }

    @Test
    void anIncidentTheGraphAlreadyNamesJoinsAsIs(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            OperationalObject inc = TestOpsEngine.of(c.svc).open(ObjectType.INCIDENT, "sim swap", "d", "HIGH",
                    null, null, null, null, Map.of());
            HttpResponse<String> r = post(c.port, "/cases/from-entities",
                    "{\"title\":\"t\",\"entities\":[{\"objectId\":\"" + inc.id() + "\"}]}", "manager");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode members = V1Body.of(r.body()).get("members");
            assertEquals(1, members.size());
            assertEquals(inc.id(), members.get(0).get("id").asText());
            assertFalse(members.get(0).get("minted").asBoolean());
        }
    }

    @Test
    void malformedBodiesAreRefusedBeforeAnyWrite(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(400, post(c.port, "/cases/from-entities", "{\"title\":\"t\",\"entities\":[]}", "manager")
                    .statusCode(), "a Case CONTAINS its members — none named is refused");
            assertEquals(400, post(c.port, "/cases/from-entities",
                    "{\"entities\":[{\"id\":\"entity:a\",\"dataset\":\"d\"}]}", "manager").statusCode());
            assertEquals(422, post(c.port, "/cases/from-entities",
                    "{\"title\":\"t\",\"entities\":[{\"id\":\"acme\",\"dataset\":\"d\"}]}", "manager").statusCode(),
                    "not an Entity node id");
            assertEquals(422, post(c.port, "/cases/from-entities",
                    "{\"title\":\"t\",\"entities\":[{\"id\":\"entity:a\"}]}", "manager").statusCode(),
                    "no source Dataset → no identity");
            assertTrue(TestOpsEngine.of(c.svc).query(ObjectQuery.recent(10)).isEmpty());
        }
    }

    private HttpResponse<String> post(int port, String path, String body, String bearer) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Authorization", "Bearer " + bearer).header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> get(int port, String path, String bearer) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Authorization", "Bearer " + bearer).GET().build(), BodyHandlers.ofString());
    }
}
