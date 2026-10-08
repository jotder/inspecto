package com.gamma.control;

import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.workflow.ObjectType;
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
import com.gamma.access.Roles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Case-management routes stay {@code canAdminister} (merge / split / Case-Rule evaluate), proven with an armed
 * {@code Authenticator} whose Subjects carry the SEEDED grants ({@link Roles#SEED}). Moved from {@code ControlApiTriageGateTest}
 * (inspecto-ops) with the routes, MODULE-REORG-P7; the PATCH half of that test stays there.
 */
class ControlApiCaseTriageGateTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** {@code Bearer <seeded role>} → Subject {@code u-<role>} carrying exactly that role's seeded grants. */
    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return Optional.empty();
        String role = auth.substring("Bearer ".length());
        Roles.Def def = Roles.SEED.get(role);
        return def == null ? Optional.empty() : Optional.of(new Subject("u-" + role, def.capabilities()));
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

    @Test
    void mergeSplitAndCaseRuleEvaluateStayCanAdminister(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String kase = TestOpsEngine.of(c.svc).open(ObjectType.CASE, "ring", "d", "HIGH", null, Map.of()).id();
            assertEquals(403, send(c.port, "POST", "/objects/" + kase + "/merge",
                    "{\"sources\":[\"x\"]}", "operations").statusCode());
            assertEquals(403, send(c.port, "POST", "/objects/" + kase + "/split",
                    "{\"members\":[\"x\"]}", "operations").statusCode());
            assertEquals(403, send(c.port, "POST", "/cases/rules/any/evaluate", "{}", "operations").statusCode());
            // ...refused before existence-hiding, as for the other by-id routes
            assertEquals(403, send(c.port, "POST", "/objects/nope/merge", "{\"sources\":[\"x\"]}", "operations").statusCode());
            assertEquals(403, send(c.port, "POST", "/objects/nope/split", "{\"members\":[\"x\"]}", "operations").statusCode());

            // the probe that would otherwise succeed: the administrator passes the gate (and gets the service's own answer)
            assertEquals(404, send(c.port, "POST", "/cases/rules/any/evaluate", "{}", "admin").statusCode());
            assertEquals(404, send(c.port, "POST", "/objects/nope/merge", "{\"sources\":[\"x\"]}", "admin").statusCode());
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
