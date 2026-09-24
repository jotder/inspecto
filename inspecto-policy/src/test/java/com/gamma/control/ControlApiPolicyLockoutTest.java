package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
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
 * F7 — self-lockout ({@code docs/superpower/policy-authoring-ux-design.md} §1, S2): a draft that would
 * DENY the saver's own next {@code PUT /access/policies} is refused (422 {@code would-lock-out}) before
 * it reaches disk — the route PEP runs before every handler, so once such a doc is live the only
 * recovery is a disk edit. Scope per D9 (taken on recommendation): the SAVER only.
 */
class ControlApiPolicyLockoutTest {

    private final HttpClient client = HttpClient.newHttpClient();

    /** {@code Bearer <id>} → a Subject holding canConfigureAccess (every caller here may author policies). */
    private static final Authenticator AUTH = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return Optional.empty();
        return Optional.of(new Subject(auth.substring(7), Set.of("canConfigureAccess"), null, Map.of()));
    };

    private record Ctx(CollectorService svc, ControlApi api, int port, Path writeRoot) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path writeRoot = Files.createDirectories(dir.resolve("cfg"));
        System.setProperty("assist.write.root", writeRoot.toString());
        Authenticators.forTest(AUTH);
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port(), writeRoot);
    }

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
        System.clearProperty("assist.write.root");
    }

    @Test
    void f7_aDraftThatWouldDenyTheSaversNextPolicySaveIs422AndLeavesTheFileUntouched(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(200, send(c.port, "PUT", "/access/policies", """
                    {"policies":[{"name":"freeze-mallory","effect":"deny","when":"subject.id == 'mallory'"}]}""",
                    "root").statusCode());
            Path file = c.writeRoot().resolve("access-policies.toon");
            String before = Files.readString(file);

            for (String draft : List.of(
                    // denies root's own write — the next PUT /access/policies would be a 403
                    "{\"name\":\"no-root-writes\",\"effect\":\"deny\",\"target\":{\"actions\":[\"write\"]},\"when\":\"subject.id == 'root'\"}",
                    // denies every access configurer's write, root included
                    "{\"name\":\"freeze-admins\",\"effect\":\"deny\",\"target\":{\"actions\":[\"write\"]},\"when\":\"subject.capabilities contains 'canConfigureAccess'\"}",
                    // untargeted (every action) — the lockout does not depend on naming 'write'
                    "{\"name\":\"shun-root\",\"effect\":\"deny\",\"when\":\"subject.id == 'root'\"}")) {
                HttpResponse<String> r = send(c.port, "PUT", "/access/policies", "{\"policies\":[" + draft + "]}", "root");
                assertEquals(422, r.statusCode(), r.body());
                assertTrue(r.body().contains("would-lock-out"), r.body());
                assertEquals(before, Files.readString(file), "a refused draft never reaches disk");
                assertEquals(200, send(c.port, "GET", "/objects", null, "root").statusCode());
            }
            // the saver's next save still works — nothing was locked
            assertEquals(200, send(c.port, "PUT", "/access/policies", "{\"policies\":[]}", "root").statusCode());
        }
    }

    @Test
    void d9_aDraftThatDeniesSomeoneElseIsAccepted(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(200, send(c.port, "PUT", "/access/policies", """
                    {"policies":[{"name":"no-root-writes","effect":"deny","target":{"actions":["write"]},
                      "when":"subject.id == 'root'"}]}""", "ana").statusCode(),
                    "only the saver is protected (D9): ana may deny root");
            assertEquals(403, send(c.port, "PUT", "/access/policies", "{\"policies\":[]}", "root").statusCode(),
                    "and it bites root at the route PEP");
            assertEquals(200, send(c.port, "PUT", "/access/policies", "{\"policies\":[]}", "ana").statusCode());
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String subject) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (subject != null) b.header("Authorization", "Bearer " + subject);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
