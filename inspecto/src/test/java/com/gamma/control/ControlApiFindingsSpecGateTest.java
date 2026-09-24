package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
import com.sun.net.httpserver.HttpExchange;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D1 = (b) (operator, 2026-09-25, {@code docs/superpower/findings-spec-authoring-ui-design.md} §9): writing a
 * {@code findings-spec} component through the generic {@code /components} CRUD is gated on
 * {@code canManageIncidents} — the capability the people who resolve Cases already hold — instead of
 * {@code canAuthorWorkbench}. Every other kind is unchanged.
 *
 * <p>⚠ A REAL Subject is attached on every request ({@link Authenticators#forTest}): with no Subject,
 * {@code withCapability} is a no-op and every assertion here would pass against an ungated route. The
 * seeded roles' capabilities come from {@link Roles#SEED}, so a re-seed that moves the grant fails here.
 */
class ControlApiFindingsSpecGateTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private static final String SPEC = """
            {"name":"case","objectType":"case","sections":[
              {"key":"outcome","label":"Outcome","type":"select",
               "options":[{"value":"WIN","label":"Win"},{"value":"LOSS","label":"Loss"}]}]}
            """;
    private static final String SPEC_EDIT = """
            {"objectType":"case","sections":[{"key":"note","label":"Note","type":"multiline"}]}
            """;

    /** Bearer = seeded role name; "business-incidents" = a Business desk granted canManageIncidents. */
    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return Optional.empty();
        String who = auth.substring(7);
        if (who.equals("business-incidents")) {
            Set<String> caps = new HashSet<>(Roles.SEED.get("business").capabilities());
            caps.add(Roles.CAN_MANAGE_INCIDENTS);
            return subject(ex, who, caps, "business");
        }
        Roles.Def def = Roles.SEED.get(who);
        return def == null ? Optional.empty() : subject(ex, who, def.capabilities(), who);
    };

    private static Optional<Subject> subject(HttpExchange ex, String id, Set<String> caps, String role) {
        ComponentAccess.heldRoles(ex, Set.of(role));
        return Optional.of(new Subject(id, Set.copyOf(caps)));
    }

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
        String prior = System.getProperty("assist.write.root");
        System.setProperty("assist.write.root", dir.resolve("wr").toString());
        try {
            ControlApi api = new ControlApi(svc, 0);   // captures the write root at construction
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            if (prior != null) System.setProperty("assist.write.root", prior);
            else System.clearProperty("assist.write.root");
        }
    }

    @Test
    void theSeedsPremiseHolds() {
        // The test is only meaningful if the roles it exercises split the two capabilities this way.
        assertTrue(Roles.SEED.get("operations").capabilities().contains(Roles.CAN_MANAGE_INCIDENTS));
        assertFalse(Roles.SEED.get("operations").capabilities().contains(Roles.CAN_AUTHOR_WORKBENCH));
        assertTrue(Roles.SEED.get("developer").capabilities().contains(Roles.CAN_AUTHOR_WORKBENCH));
        assertFalse(Roles.SEED.get("developer").capabilities().contains(Roles.CAN_MANAGE_INCIDENTS));
        assertFalse(Roles.SEED.get("business").capabilities().contains(Roles.CAN_MANAGE_INCIDENTS));
    }

    @Test
    void operationsHoldingCanManageIncidentsCreatesUpdatesRestoresAndDeletesAFindingsSpec(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(200, send(c, "POST", "/components/findings-spec", SPEC, "operations").statusCode());
            assertEquals(200, send(c, "PUT", "/components/findings-spec/case", SPEC_EDIT, "operations").statusCode());
            assertEquals(200, send(c, "POST", "/components/findings-spec/case/versions/1/restore", null, "operations").statusCode());
            assertEquals(200, send(c, "DELETE", "/components/findings-spec/case", null, "operations").statusCode());
        }
    }

    @Test
    void aBusinessDeskGrantedCanManageIncidentsMayAuthorTheSpec(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(200, send(c, "POST", "/components/findings-spec", SPEC, "business-incidents").statusCode());
            assertEquals(200, send(c, "PUT", "/components/findings-spec/case", SPEC_EDIT, "business-incidents").statusCode());
            assertEquals(200, send(c, "DELETE", "/components/findings-spec/case", null, "business-incidents").statusCode());
        }
    }

    @Test
    void aRoleWithoutCanManageIncidentsIsRefused403EvenWithCanAuthorWorkbench(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            for (String who : List.of("business", "developer")) {
                assertEquals(403, send(c, "POST", "/components/findings-spec", SPEC, who).statusCode(), who);
            }
            assertEquals(200, send(c, "POST", "/components/findings-spec", SPEC, "operations").statusCode());
            for (String who : List.of("business", "developer")) {
                assertEquals(403, send(c, "PUT", "/components/findings-spec/case", SPEC_EDIT, who).statusCode(), who);
                assertEquals(403, send(c, "POST", "/components/findings-spec/case/versions/1/restore", null, who).statusCode(), who);
                assertEquals(403, send(c, "DELETE", "/components/findings-spec/case", null, who).statusCode(), who);
            }
            // Reads stay open, as every read is.
            assertEquals(200, send(c, "GET", "/components/findings-spec/case", null, "business").statusCode());
        }
    }

    /** The generic route URL-decodes the kind segment, so a double-encoded kind must not reach it ungated. */
    @Test
    void anEncodedKindCannotSlipPastTheFindingsSpecGate(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(403, send(c, "POST", "/components/findings%252Dspec", SPEC, "developer").statusCode());
        }
    }

    @Test
    void everyOtherKindStillNeedsCanAuthorWorkbench(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String ds = "{\"id\":\"cdr\",\"label\":\"CDRs\"}";
            assertEquals(403, send(c, "POST", "/components/dataset", ds, "operations").statusCode(),
                    "canManageIncidents does not open the other kinds");
            assertEquals(200, send(c, "POST", "/components/dataset", ds, "developer").statusCode());
            assertEquals(403, send(c, "PUT", "/components/dataset/cdr", "{\"label\":\"x\"}", "operations").statusCode());
            assertEquals(403, send(c, "DELETE", "/components/dataset/cdr", null, "operations").statusCode());
            assertEquals(200, send(c, "PUT", "/components/dataset/cdr", "{\"label\":\"x\"}", "developer").statusCode());
            assertEquals(200, send(c, "DELETE", "/components/dataset/cdr", null, "developer").statusCode());
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        if (body != null) b.header("Content-Type", "application/json");
        b.header("Authorization", "Bearer " + bearer);
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
