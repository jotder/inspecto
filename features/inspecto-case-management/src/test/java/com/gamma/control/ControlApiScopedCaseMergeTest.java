package com.gamma.control;

import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.workflow.ObjectType;
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
 * SEC-7d on the Case merge route (moved from {@code ControlApiScopedObjectsTest}, MODULE-REORG-P7): the by-id scope guard only
 * sees the URL id, so a merge SOURCE named in the body must be gated too - merging absorbs AND CLOSES the source.
 */
class ControlApiScopedCaseMergeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** {@code Bearer fraud} → scoped to {fraud}; {@code Bearer all} → authenticated, unscoped. */
    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        // canAdminister: assign/merge became capability-gated 2026-09-15 (ROUTE-UNGATED-DEFAULT-1 step 2b);
        // this class tests the DATA-SCOPE guard beneath that gate, so its subjects carry the capability.
        // canManageIncidents: same reason, 2026-09-16 — `POST /objects` (opening an Incident) gained its own
        // capability, and the gate wraps the scope guard, so without it the create-with-links case would
        // answer 403 before existence-hiding ever got to answer 404, testing the wrong thing.
        // canWorkIncidents: same reason, 2026-09-26 — `/assign` moved from canAdminister to it.
        if ("Bearer fraud".equals(auth))
            return Optional.of(new Subject("ana", Set.of("canOperateRuns", "canAdminister", "canManageIncidents", "canWorkIncidents"), Set.of("fraud")));
        if ("Bearer all".equals(auth))
            return Optional.of(new Subject("root", Set.of("canOperateRuns", "canAdminister", "canManageIncidents", "canWorkIncidents")));   // dataScopes null = unscoped
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

    @Test
    void outOfScopeMergeSourceIs404AndTheHiddenCaseIsNotClosed(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            var objects = TestOpsEngine.of(c.svc);
            OperationalObject fraud = objects.open(ObjectType.INCIDENT, "sim swap", "d", "HIGH", null, null, null,
                    "corr", Map.of(com.gamma.opsapi.ObjectRoutes.ATTR_CASE_TYPE, "fraud"));
            OperationalObject billing = objects.open(ObjectType.INCIDENT, "rating drift", "d", "HIGH", null, null, null,
                    "corr", Map.of(com.gamma.opsapi.ObjectRoutes.ATTR_CASE_TYPE, "billing"));

            assertEquals(404, post(c.port, "/objects/" + fraud.id() + "/merge",
                    "{\"sources\":[\"" + billing.id() + "\"]}", "fraud").statusCode(),
                    "merge source out of scope: indistinguishable from absence");

            // the hidden object is untouched (a merge would have CLOSED it)
            JsonNode after = V1Body.of(get(c.port, "/objects/" + billing.id(), "all").body());
            assertEquals("IDENTIFIED", after.get("status").asText(), "the out-of-scope object was not closed by the merge");

            // and the gate is the SCOPE, not the shape: the unscoped subject reaches the service, which
            // refuses the same call on its own merits (these are INCIDENTs, not CASEs).
            assertEquals(422, post(c.port, "/objects/" + fraud.id() + "/merge",
                    "{\"sources\":[\"" + billing.id() + "\"]}", "all").statusCode());
        }
    }

    private HttpResponse<String> get(int port, String path, String bearer) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Authorization", "Bearer " + bearer).GET().build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> post(int port, String path, String body, String bearer) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "application/json")
                .method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }
}
