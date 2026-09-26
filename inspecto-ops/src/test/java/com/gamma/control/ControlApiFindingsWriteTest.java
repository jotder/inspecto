package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.event.Event;
import com.gamma.event.EventType;
import com.gamma.objects.ObjectType;
import com.gamma.ops.OperationalObject;
import com.gamma.opsapi.ObjectRoutes;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code PUT /objects/{id}/findings} over real HTTP, with an armed {@code Authenticator} (operator decision
 * 2026-09-25): saving FINDINGS VALUES on a Case is collaboration, open to anyone who can see the Case — like a
 * comment or an attachment — while changing its disposition stays on the {@code canAdminister} PATCH.
 *
 * <p>The caller is a real Subject WITHOUT {@code canAdminister} and scoped to {@code fraud}: with no Subject at
 * all nothing is checked, so an ungated route would pass every assertion here vacuously.
 */
class ControlApiFindingsWriteTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** {@code Bearer viewer} → authenticated, scoped to {fraud}, no triage capability. */
    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if ("Bearer viewer".equals(auth))
            return Optional.of(new Subject("vic", Set.of("canOperateRuns"), Set.of("fraud")));
        return Optional.empty();
    };

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

    private static OperationalObject seedCase(Ctx c, String caseType) {
        return TestOpsEngine.of(c.svc).open(ObjectType.CASE, "fraud ring", "d", "HIGH", null, null, null,
                "corr", Map.of(ObjectRoutes.ATTR_CASE_TYPE, caseType));
    }

    @Test
    void aViewerWithoutCanAdministerSavesFindingsOnAVisibleCase(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            OperationalObject seed = seedCase(c, "fraud");
            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            c.svc.eventLog().addSubscriber(sub);
            try {
                HttpResponse<String> r = send(c.port, "PUT", "/objects/" + seed.id() + "/findings",
                        "{\"findings\":{\"disposition\":\"CONFIRMED\",\"recordsAffected\":\"40\",\"summary\":\"ring\"}}");
                assertEquals(200, r.statusCode(), r.body());
                JsonNode attrs = V1Body.of(r.body()).get("attributes");
                assertEquals(Map.of("disposition", "CONFIRMED", "recordsAffected", "40", "summary", "ring"),
                        JSON.readValue(attrs.get("findings").asText(), Map.class), "the blob is the canonical home (D3)");
                assertEquals("40", attrs.get("recordsAffected").asText(), "the flat copy the C4 roll-up sums");
                assertFalse(attrs.has("impactAmount"), "the Case's money is its typed impact (WS-10), not a Findings copy");
                assertEquals("fraud", attrs.get(ObjectRoutes.ATTR_CASE_TYPE).asText(), "the rest of the bag survives");
            } finally {
                c.svc.eventLog().removeSubscriber(sub);
            }
            Event audit = seen.stream()
                    .filter(e -> EventType.OBJECT_ACTIVITY.equals(e.type()))
                    .filter(e -> "findings".equals(e.attributes().get("action")))
                    .findFirst().orElseThrow(() -> new AssertionError("no findings activity event: " + seen));
            assertEquals(seed.id(), audit.attributes().get("objectId"));
            assertEquals("vic", audit.attributes().get("actor"), "the actor is the authenticated Subject");
        }
    }

    @Test
    void theRouteRefusesAnyKeyButFindingsSoItCannotChangeTheDisposition(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            OperationalObject seed = seedCase(c, "fraud");
            String path = "/objects/" + seed.id() + "/findings";

            HttpResponse<String> r = send(c.port, "PUT", path,
                    "{\"findings\":{\"disposition\":\"CONFIRMED\"},\"priority\":\"LOW\"}");
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("priority"), "the refusal names the key: " + r.body());
            assertEquals(422, send(c.port, "PUT", path,
                    "{\"findings\":{},\"attributes\":{\"assignees\":\"me\"}}").statusCode(),
                    "a second attributes bag would be a way round the gate");
            assertEquals(422, send(c.port, "PUT", path,
                    "{\"findings\":{\"disposition\":{\"nested\":true}}}").statusCode(),
                    "a Findings value is a scalar");
            assertEquals(400, send(c.port, "PUT", path, "{}").statusCode(), "no findings at all");

            OperationalObject after = TestOpsEngine.of(c.svc).get(seed.id()).orElseThrow();
            assertEquals(seed.priority(), after.priority(), "nothing was written");
            assertNull(after.attributes().get("findings"));
        }
    }

    @Test
    void anOutOfScopeCaseIsIndistinguishableFromAnAbsentOne(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            OperationalObject billing = seedCase(c, "billing");
            String body = "{\"findings\":{\"disposition\":\"CONFIRMED\"}}";
            HttpResponse<String> hidden = send(c.port, "PUT", "/objects/" + billing.id() + "/findings", body);
            assertEquals(404, hidden.statusCode(), hidden.body());
            assertNull(TestOpsEngine.of(c.svc).get(billing.id()).orElseThrow().attributes().get("findings"));
            assertEquals(404, send(c.port, "PUT", "/objects/no-such-id/findings", body).statusCode());
        }
    }

    @Test
    void aValueTheSpecRefusesIs422(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            OperationalObject seed = seedCase(c, "fraud");
            HttpResponse<String> r = send(c.port, "PUT", "/objects/" + seed.id() + "/findings",
                    "{\"findings\":{\"disposition\":\"MAYBE\"}}");
            assertEquals(422, r.statusCode(), "a value outside the built-in disposition ladder");
            assertTrue(r.body().contains("disposition"), r.body());
        }
    }

    @Test
    void thePatchStaysClosedToTheSameViewer(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            OperationalObject seed = seedCase(c, "fraud");
            assertEquals(403, send(c.port, "PATCH", "/objects/" + seed.id(), "{\"priority\":\"LOW\"}").statusCode());
            assertEquals(403, send(c.port, "PATCH", "/objects/" + seed.id(),
                    "{\"attributes\":{\"findings\":\"{\\\"disposition\\\":\\\"CONFIRMED\\\"}\"}}").statusCode(),
                    "the PATCH is unchanged: canAdminister, whatever it carries");
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Authorization", "Bearer viewer")
                .header("Content-Type", "application/json")
                .method(method, BodyPublishers.ofString(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
