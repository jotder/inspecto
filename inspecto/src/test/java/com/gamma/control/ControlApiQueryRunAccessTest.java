package com.gamma.control;

import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
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
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import com.gamma.access.ComponentAccess;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code POST /queries/{id}/run} honours component access (R3) under an ARMED authenticator: a query
 * the caller cannot view is a 404 indistinguishable from a missing one, and a query over a Dataset the
 * caller cannot view is refused exactly as {@code /bi/query} refuses it (404 "unknown dataset").
 */
class ControlApiQueryRunAccessTest {
    private final HttpClient client = HttpClient.newHttpClient();
    private static final Authenticator FAKE = ex -> {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        if (a == null || !a.startsWith("Bearer ")) return Optional.empty();
        ComponentAccess.heldRoles(ex, Set.of("developer"));
        return Optional.of(new Subject(a.substring(7), Set.of("canAuthorWorkbench")));
    };

    @AfterEach void tearDown() { Authenticators.forTest(null); }

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path cfg, Path root) throws Exception {
        Authenticators.forTest(FAKE);
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), root);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private ComponentStore registry(Ctx c) { return new ComponentStore(c.root.resolve("registry")); }

    private void seedView(Ctx c) throws Exception {
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition("sales_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES ('EU',10.0),('US',5.0)) AS t(region,amount)", "2026-07-08T00:00:00Z"));
    }

    private HttpResponse<String> run(Ctx c, String user, String id) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/queries/" + id + "/run"))
                .header("Authorization", "Bearer " + user)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String noCorrelation(String body) { return body.replaceAll("\"correlationId\":\"[^\"]*\"", ""); }

    @Test
    void restrictedQueryIs404IndistinguishableFromMissing(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            registry(c).write("query", "secret", Map.of("type", "sql", "text", "SELECT 42 AS answer",
                    "owner", "alice", "shares", List.of()));

            var bob = run(c, "bob", "secret");
            var missing = run(c, "bob", "nope");
            assertEquals(404, bob.statusCode(), bob.body());
            assertTrue(bob.body().contains("no query 'secret'"), bob.body());
            assertEquals(noCorrelation(missing.body().replace("nope", "secret")), noCorrelation(bob.body()), "must not leak existence");

            var alice = run(c, "alice", "secret");
            assertEquals(200, alice.statusCode(), alice.body());
            assertTrue(alice.body().contains("42"), alice.body());
        }
    }

    @Test
    void queryOverADatasetTheCallerCannotViewIsRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedView(c);
            registry(c).write("dataset", "sales_ds", Map.of("view", "sales_view", "owner", "alice", "shares", List.of()));
            registry(c).write("query", "open_q", Map.of("type", "sql", "datasetId", "sales_ds",
                    "text", "SELECT region, amount FROM sales_ds"));   // the query itself is unrestricted

            var bob = run(c, "bob", "open_q");
            assertEquals(404, bob.statusCode(), bob.body());
            assertTrue(bob.body().contains("unknown dataset 'sales_ds'"), bob.body());
            assertFalse(bob.body().contains("EU"), bob.body());

            var alice = run(c, "alice", "open_q");
            assertEquals(200, alice.statusCode(), alice.body());
            assertTrue(alice.body().contains("EU"), alice.body());
        }
    }
}
