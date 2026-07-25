package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.ops.ObjectType;
import com.gamma.ops.OperationalObject;
import com.gamma.pipeline.ComponentStore;
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
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BACKLOG D10 over real HTTP: the kind-addressed {@code /notes/{targetKind}/{targetId}/…} surface.
 * Covers a comment on a saved {@code link-analysis-view}, the shipped {@code /objects/{id}/comments}
 * path still working (and staying a separate note family), an unknown target kind rejected, an absent
 * target rejected, and the SEC-7d data-scope guard still enforced when the target is an object — the
 * generic surface must not be a way around {@code ObjectRoutes.scoped}.
 */
class ControlApiNoteRoutesTest {

    private final HttpClient client = HttpClient.newHttpClient();

    /** {@code Bearer fraud} → scoped to {fraud}; {@code Bearer all} → authenticated, unscoped. */
    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if ("Bearer fraud".equals(auth))
            return Optional.of(new Subject("ana", Set.of("canOperateRuns"), Set.of("fraud")));
        if ("Bearer all".equals(auth))
            return Optional.of(new Subject("root", Set.of("canOperateRuns")));
        return Optional.empty();
    };

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
        System.clearProperty("assist.write.root");
    }

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /** Boot the API over a write root that already holds one saved link-analysis-view ("ring-1"). */
    private Ctx open(Path dir, boolean authenticated) throws Exception {
        Authenticators.forTest(authenticated ? FAKE : null);
        Path writeRoot = dir.resolve("cfg");
        Files.createDirectories(writeRoot);
        System.setProperty("assist.write.root", writeRoot.toString());
        new ComponentStore(writeRoot.resolve("registry"))
                .write("link-analysis-view", "ring-1", Map.of("name", "ring-1", "title", "SIM-swap ring"));
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    @Test
    void commentOnASavedLinkAnalysisViewRoundTrips(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, false)) {
            HttpResponse<String> created = post(c.port, "/notes/link-analysis-view/ring-1/comments",
                    "{\"body\":\"odd cluster\",\"author\":\"alice\"}", null);
            assertEquals(200, created.statusCode(), created.body());
            JsonNode note = V1Body.of(created.body());
            assertEquals("link-analysis-view", note.get("targetKind").asText());
            assertEquals("ring-1", note.get("targetId").asText());
            assertEquals("COMMENT", note.get("kind").asText(), "note kind stays orthogonal to target kind");

            JsonNode listed = V1Body.of(get(c.port, "/notes/link-analysis-view/ring-1/comments", null).body());
            assertEquals(1, listed.size());
            assertEquals("odd cluster", listed.get(0).get("body").asText());
            assertEquals(1, V1Body.of(get(c.port, "/notes/link-analysis-view/ring-1", null).body()).size(),
                    "the un-suffixed read returns every note kind");
        }
    }

    @Test
    void unknownKindAndAbsentTargetFailClosed(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, false)) {
            assertEquals(400, post(c.port, "/notes/banana/ring-1/comments", "{\"body\":\"x\"}", null).statusCode(),
                    "an unknown target kind is a 400, not a silent orphan note");
            assertEquals(404, post(c.port, "/notes/link-analysis-view/nope/comments", "{\"body\":\"x\"}", null).statusCode(),
                    "a known kind with an absent id is a 404");
            assertEquals(404, get(c.port, "/notes/link-analysis-view/nope", null).statusCode());
            assertEquals(400, post(c.port, "/notes/link-analysis-view/ring-1/comments", "{}", null).statusCode(),
                    "'body' is still required");
        }
    }

    @Test
    void objectSurfaceKeepsWorkingAndStaysASeparateFamily(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, false)) {
            OperationalObject obj = c.svc.objects().open(ObjectType.CASE, "investigation", "d", "HIGH",
                    null, null, null, "corr", Map.of());

            // the shipped route, unchanged
            assertEquals(200, post(c.port, "/objects/" + obj.id() + "/comments",
                    "{\"body\":\"legacy path\",\"author\":\"alice\"}", null).statusCode());
            JsonNode viaObjects = V1Body.of(get(c.port, "/objects/" + obj.id() + "/comments", null).body());
            assertEquals(1, viaObjects.size());
            assertEquals("object", viaObjects.get(0).get("targetKind").asText());
            assertEquals(obj.id(), viaObjects.get(0).get("objectId").asText(), "shipped JSON key retained");

            // the same note, read through the generic surface
            assertEquals(1, V1Body.of(get(c.port, "/notes/object/" + obj.id() + "/comments", null).body()).size());
            // and a same-id note on another family does not bleed into it
            assertEquals(404, get(c.port, "/notes/link-analysis-view/" + obj.id(), null).statusCode());
        }
    }

    @Test
    void dataScopeGuardStillAppliesToObjectTargets(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            OperationalObject billing = c.svc.objects().open(ObjectType.INCIDENT, "rating drift", "d", "HIGH",
                    null, null, null, "corr", Map.of(ObjectRoutes.ATTR_CASE_TYPE, "billing"));

            assertEquals(404, get(c.port, "/notes/object/" + billing.id() + "/comments", "fraud").statusCode(),
                    "SEC-7d: an out-of-scope object's notes are indistinguishable from absence");
            assertEquals(404, post(c.port, "/notes/object/" + billing.id() + "/comments",
                    "{\"body\":\"peek\"}", "fraud").statusCode(), "and cannot be written either");
            assertEquals(404, get(c.port, "/objects/" + billing.id() + "/comments", "fraud").statusCode(),
                    "the shipped route is gated the same way");

            assertEquals(200, post(c.port, "/notes/object/" + billing.id() + "/comments",
                    "{\"body\":\"in scope\"}", "all").statusCode(), "the unscoped subject is unaffected");
        }
    }

    private HttpResponse<String> get(int port, String path, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).GET();
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> post(int port, String path, String body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json")
                .method("POST", BodyPublishers.ofString(body));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
