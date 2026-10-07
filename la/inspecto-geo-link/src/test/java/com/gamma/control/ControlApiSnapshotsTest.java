package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
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
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-03 — durable Link Analysis evidence snapshots ({@code POST|GET /inv/snapshots},
 * {@code POST /inv/snapshots/attach}), one test per gate plus the happy path, over real HTTP.
 *
 * <p>The property under test is not "it persists" but <b>"it cannot be un-persisted"</b>. Decision D-S1
 * settled that a saved view is not evidence because reopening re-projects live data; a snapshot store that
 * allowed an id to be overwritten, or that let an attachment mutate the sealed record, would inherit exactly
 * that defect in a new object. The 409 and the separate attachment log are the whole design, so they are what
 * these tests pin.
 */
class ControlApiSnapshotsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
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

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json")
                .method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .GET().build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> postAs(int port, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        return client.send(b.method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    /** A 2xx /api/v1 body is the ENVELOPE — the payload lives under `data`. */
    private JsonNode data(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body()).get("data");
    }

    private static String snapshot(String id) {
        return "{\"id\":\"" + id + "\",\"title\":\"Layering chain\",\"manifestHash\":\"fnv1a64:deadbeef\","
                + "\"nodes\":[{\"id\":\"entity:ACC-1\",\"data\":{\"label\":\"ACC-1\",\"kind\":\"entity\"}},"
                + "{\"id\":\"entity:ACC-2\",\"data\":{\"label\":\"ACC-2\",\"kind\":\"entity\"}}],"
                + "\"edges\":[{\"id\":\"e1\",\"source\":\"entity:ACC-1\",\"target\":\"entity:ACC-2\"}],"
                + "\"metrics\":{\"degree\":{\"entity:ACC-1\":1}},\"annotations\":[],"
                + "\"origin\":{\"sourceId\":\"entity-projection\",\"dataset\":\"mule_transfers\"}}";
    }

    @Test
    void sealsASnapshotAndListsIt(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> created = post(c.port, "/inv/snapshots", snapshot("snap-1"));
            assertEquals(200, created.statusCode(), created.body());
            assertEquals("snap-1", data(created).get("id").asText());

            // The sealed record keeps the CONTENT, not id references — storing ids only would give the
            // snapshot the same defect that makes a saved view not-evidence.
            Path f = root.resolve("audit").resolve("snapshots").resolve("snap-1.json");
            assertTrue(Files.isRegularFile(f), "sealed to " + f);
            JsonNode stored = JSON.readTree(Files.readString(f));
            assertEquals(2, stored.get("nodes").size(), "full nodes are stored, not ids");
            assertEquals(1, stored.get("edges").size());
            assertEquals("fnv1a64:deadbeef", stored.get("manifestHash").asText());

            JsonNode listed = data(get(c.port, "/inv/snapshots"));
            assertEquals(1, listed.get("total").asInt());
            assertFalse(listed.get("truncated").asBoolean());
            assertEquals("snap-1", listed.get("ids").get(0).asText());
        }
    }

    /** The gate the whole object exists for: evidence is never silently replaced. */
    @Test
    void refusesToOverwriteASealedSnapshot(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, post(c.port, "/inv/snapshots", snapshot("snap-1")).statusCode());

            HttpResponse<String> again = post(c.port, "/inv/snapshots", snapshot("snap-1"));
            assertEquals(409, again.statusCode(), again.body());

            // and the original content is untouched
            String stored = Files.readString(root.resolve("audit").resolve("snapshots").resolve("snap-1.json"));
            assertTrue(stored.contains("Layering chain"));
        }
    }

    @Test
    void refusesAnUnsafeIdAndAnUnfingerprintedSnapshot(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> traversal = post(c.port, "/inv/snapshots",
                    "{\"id\":\"../escape\",\"manifestHash\":\"h\"}");
            assertEquals(422, traversal.statusCode(), traversal.body());
            assertTrue(traversal.body().contains("id"), traversal.body());

            HttpResponse<String> unhashed = post(c.port, "/inv/snapshots", "{\"id\":\"snap-2\"}");
            assertEquals(422, unhashed.statusCode(), unhashed.body());
            assertTrue(unhashed.body().contains("manifestHash"), unhashed.body());
        }
    }

    @Test
    void refusesANonIntegerLimit(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = get(c.port, "/inv/snapshots?limit=lots");
            assertEquals(422, r.statusCode(), r.body());
        }
    }

    /**
     * 🔴 Attaching must NOT reopen the sealed record. If it did, the file's bytes would change and the
     * fingerprint that makes it evidence would no longer describe what is on disk.
     */
    @Test
    void attachmentDoesNotMutateTheSealedSnapshot(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, post(c.port, "/inv/snapshots", snapshot("snap-1")).statusCode());
            Path f = root.resolve("audit").resolve("snapshots").resolve("snap-1.json");
            String before = Files.readString(f);

            HttpResponse<String> attached = post(c.port, "/inv/snapshots/attach",
                    "{\"snapshotId\":\"snap-1\",\"caseId\":\"CASE-7\"}");
            assertEquals(200, attached.statusCode(), attached.body());
            assertEquals("CASE-7", data(attached).get("attachedTo").get(0).asText());

            assertEquals(before, Files.readString(f), "the sealed snapshot is byte-identical after attach");
            assertTrue(Files.isRegularFile(root.resolve("audit").resolve("snapshots").resolve("attachments.jsonl")));
        }
    }

    @Test
    void refusesAttachToAnUnknownSnapshot(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = post(c.port, "/inv/snapshots/attach",
                    "{\"snapshotId\":\"nope\",\"caseId\":\"CASE-7\"}");
            assertEquals(404, r.statusCode(), r.body());
        }
    }

    @Test
    void refusesAttachWithoutACaseId(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, post(c.port, "/inv/snapshots", snapshot("snap-1")).statusCode());
            HttpResponse<String> r = post(c.port, "/inv/snapshots/attach", "{\"snapshotId\":\"snap-1\"}");
            assertEquals(422, r.statusCode(), r.body());
        }
    }

    /**
     * The gate itself, exercised with an ARMED Authenticator.
     *
     * 🔴 <b>Why this test is not optional.</b> Every other test in this class builds the API with no
     * {@link Subject}, and {@code withCapability} is a NO-OP without one — so those seven tests pass through
     * an effectively ungated route and prove nothing about the gating they depend on. That is precisely the
     * hole {@code tools/check-authgate-coverage.mjs} refuses to let ship, and it went red on these two routes
     * before this test existed.
     *
     * ⚠ Three statuses on purpose: 401 is authentication, 403 is the GATE — a present Subject LACKING the
     * capability — and only the 403 case distinguishes a gated route from one that merely requires a login.
     */
    @Test
    void sealingAndAttachingRequireCanManageIncidents(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> "Bearer valid".equals(ex.getRequestHeaders().getFirst("Authorization"))
                ? Optional.of(new Subject("jdoe", Set.of("canManageIncidents")))
                : "Bearer plain".equals(ex.getRequestHeaders().getFirst("Authorization"))
                ? Optional.of(new Subject("nobody", Set.of()))
                : Optional.empty());
        try (Ctx c = open(cfg, root)) {
            assertEquals(401, post(c.port, "/inv/snapshots", snapshot("snap-1")).statusCode(),
                    "no credential is a clean 401, never a 500");

            HttpResponse<String> denied = postAs(c.port, "/inv/snapshots", snapshot("snap-1"), "Bearer plain");
            assertEquals(403, denied.statusCode(), "a Subject WITHOUT the capability is refused: " + denied.body());
            assertTrue(denied.body().contains("canManageIncidents"), "the refusal names the capability");

            assertEquals(403, postAs(c.port, "/inv/snapshots/attach",
                            "{\"snapshotId\":\"snap-1\",\"caseId\":\"CASE-7\"}", "Bearer plain").statusCode(),
                    "attach is gated identically — sealing and attaching are the same Case work");

            // ⛔ And the refused write left NOTHING on disk: a 403 that still sealed would be worse than an
            // open route, because the trail would show evidence nobody was allowed to create.
            assertFalse(Files.isRegularFile(root.resolve("audit").resolve("snapshots").resolve("snap-1.json")),
                    "a refused seal writes nothing");

            assertEquals(200, postAs(c.port, "/inv/snapshots", snapshot("snap-1"), "Bearer valid").statusCode());
            assertEquals(200, postAs(c.port, "/inv/snapshots/attach",
                    "{\"snapshotId\":\"snap-1\",\"caseId\":\"CASE-7\"}", "Bearer valid").statusCode());
        } finally {
            Authenticators.forTest(null);
        }
    }
}
