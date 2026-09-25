package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
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
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Stage-5 {@code SpaceRoutes} CRUD over real HTTP: create + boot a space without restart, list it, confirm the
 * per-space seam then resolves it, and delete it (deregister-only vs {@code ?purge=true} file removal). Drives a
 * multi-space ControlApi built from an initially-empty {@code -Dspaces.root} container.
 */
class ControlApiSpacesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();   // drop the booted spaces' space-labelled series (shared process-wide registry)
        }
    }

    private Ctx open(Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);   // empty container, but CRUD-capable (root remembered)
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        return new Ctx(spaces, api, api.port(), root);
    }

    @Test
    void createListSeamAndDeleteSpacesOverHttp(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(0, json(send(c.port, "GET", "/spaces", null)).size(), "starts empty");
            // capability probe: the discover runtime is CRUD-capable even with no spaces yet
            assertTrue(json(send(c.port, "GET", "/spaces/_meta", null)).get("multiSpace").asBoolean(),
                    "discover mode advertises multiSpace=true");

            // ── create + boot a space ──
            HttpResponse<String> created = send(c.port, "POST", "/spaces",
                    "{\"id\":\"acme\",\"display_name\":\"ACME Corp\",\"description\":\"the acme space\"}");
            assertEquals(200, created.statusCode(), created.body());
            assertEquals("acme", json(created).get("id").asText());
            assertEquals("ACME Corp", json(created).get("displayName").asText());
            assertTrue(Files.isDirectory(root.resolve("acme").resolve("config")), "space dir minted on disk");

            // listed, and the per-space seam now resolves it (empty pipeline list, not a 404)
            JsonNode list = json(send(c.port, "GET", "/spaces", null));
            assertTrue(list.isArray() && list.size() == 1 && "acme".equals(list.get(0).get("id").asText()));
            HttpResponse<String> pipes = send(c.port, "GET", "/spaces/acme/runs", null);
            assertEquals(200, pipes.statusCode());
            assertEquals(0, json(pipes).size(), "fresh space hosts no pipelines yet");

            // duplicate → 409; invalid id → 400
            assertEquals(409, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            assertEquals(400, send(c.port, "POST", "/spaces", "{\"id\":\"Bad Id\"}").statusCode());

            // ── delete without purge: deregistered (seam 404s) but files remain ──
            HttpResponse<String> del = send(c.port, "DELETE", "/spaces/acme", null);
            assertEquals(200, del.statusCode(), del.body());
            assertFalse(json(del).get("purged").asBoolean());
            assertEquals(0, json(send(c.port, "GET", "/spaces", null)).size());
            assertEquals(404, send(c.port, "GET", "/spaces/acme/runs", null).statusCode(), "deregistered → seam 404");
            assertTrue(Files.isDirectory(root.resolve("acme")), "files kept when not purging");

            // deleting an unknown space → 404
            assertEquals(404, send(c.port, "DELETE", "/spaces/ghost", null).statusCode());

            // ── a second space, deleted WITH purge: its directory tree is removed ──
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"beta\"}").statusCode());
            assertTrue(Files.isDirectory(root.resolve("beta")));
            assertEquals(200, send(c.port, "DELETE", "/spaces/beta?purge=true", null).statusCode());
            assertFalse(Files.exists(root.resolve("beta")), "purge removed the space directory");
        }
    }

    /**
     * Bundles ship NO Spaces (operator decision 2026-09-25), so a fresh install boots with an empty spaces root.
     * That is a clean state, not a broken one: the probes and the Space list answer, a Space-scoped route answers a
     * clear 503 naming what to do (it used to be a 500 + stack trace on every request), and creating a Space makes
     * the same route answer 200.
     */
    @Test
    void zeroSpacesIsACleanStateNotA500(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "GET", "/health", null).statusCode());
            HttpResponse<String> ready = send(c.port, "GET", "/ready", null);
            assertEquals(200, ready.statusCode(), ready.body());
            assertEquals(0, json(ready).get("pipelines").asInt(), "zero Spaces is still READY, with no Pipelines");
            assertEquals(0, json(send(c.port, "GET", "/spaces", null)).size());

            HttpResponse<String> runs = send(c.port, "GET", "/runs", null);
            assertEquals(503, runs.statusCode(), runs.body());
            JsonNode err = json(runs).get("error");
            assertEquals("CAPABILITY_UNAVAILABLE", err.get("errorCode").asText());
            assertTrue(err.get("message").asText().contains("No Space is attached"), err.toString());

            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            assertEquals(200, send(c.port, "GET", "/runs", null).statusCode(), "the attached Space now serves it");
        }
    }

    @Test
    void updatesSpaceMetadataOverHttp(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\",\"display_name\":\"ACME\"}").statusCode());

            // rename + re-describe (the id/folder is immutable)
            HttpResponse<String> up = send(c.port, "PUT", "/spaces/acme",
                    "{\"display_name\":\"ACME Corp\",\"description\":\"renamed\"}");
            assertEquals(200, up.statusCode(), up.body());
            assertEquals("acme", json(up).get("id").asText());
            assertEquals("ACME Corp", json(up).get("displayName").asText());
            assertEquals("renamed", json(up).get("description").asText());

            // persisted: the list reflects the new name (manifest rewritten on disk)
            assertEquals("ACME Corp", json(send(c.port, "GET", "/spaces", null)).get(0).get("displayName").asText());

            // unknown → 404; the default space is not editable → 400
            assertEquals(404, send(c.port, "PUT", "/spaces/ghost", "{\"display_name\":\"x\"}").statusCode());
            assertEquals(400, send(c.port, "PUT", "/spaces/default", "{\"display_name\":\"x\"}").statusCode());
        }
    }

    /**
     * A Standard/Enterprise server that hosts ZERO spaces must still answer its recovery route. Reachable in
     * production by deleting the last space (there is no last-space guard); {@code main()} refuses to boot an
     * empty {@code -Dspaces.root}, so this is the delete path, not a fresh install. The gate used to resolve
     * {@code writeRoot()} for every authenticated request, which calls {@code SpaceManager.current()} and
     * throws {@code IllegalStateException("No spaces are hosted")} — bricking the server: every route 500ed,
     * including the {@code POST /spaces} that would recover it.
     */
    @Test
    void authenticatedCreateSucceedsWhenNoSpaceIsHostedYet(@TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> "Bearer valid".equals(ex.getRequestHeaders().getFirst("Authorization"))
                ? Optional.of(new Subject("jdoe", Set.of("canAdminister")))
                : "Bearer plain".equals(ex.getRequestHeaders().getFirst("Authorization"))
                ? Optional.of(new Subject("nobody", Set.of()))
                : Optional.empty());
        try (Ctx c = open(root)) {
            assertEquals(0, c.spaces.size(), "precondition: an armed Authenticator over an empty container");

            HttpResponse<String> created = authed(c.port, "POST", "/spaces", "{\"id\":\"acme\"}");
            assertEquals(200, created.statusCode(), created.body());
            assertEquals("acme", json(created).get("id").asText());

            // the gate still authenticates — a missing credential is a clean 401, never a 500
            assertEquals(401, send(c.port, "POST", "/spaces", "{\"id\":\"beta\"}").statusCode());

            // ⛔ The RECOVERY guarantee is that POST /spaces needs NO capability WHILE ZERO Spaces are
            // hosted: gating it there would brick a server exactly as the writeRoot() resolution once did.
            // (ROUTE-UNGATED-DEFAULT-1, 2026-09-15 - gating it unconditionally turned this test red, which
            // is how the constraint was rediscovered.) 2026-09-17: the exemption was UNCONDITIONAL, so on a
            // populated server any authenticated caller could create Spaces while DELETE needed
            // canAdminister. Now: a Space exists, so a capability-less subject is refused...
            assertEquals(403, send(c.port, "POST", "/spaces", "{\"id\":\"delta\"}", "Bearer plain").statusCode(),
                    "with a Space hosted, creating another is administration");
            assertEquals(403, send(c.port, "POST", "/spaces/import?id=delta", "", "Bearer plain").statusCode(),
                    "import is the same posture as create");
            // ...and canAdminister still creates, as it deletes
            assertEquals(200, authed(c.port, "POST", "/spaces", "{\"id\":\"delta\"}").statusCode());
            assertEquals(200, authed(c.port, "DELETE", "/spaces/delta", null).statusCode());

            // and deregistering back down to zero leaves the server recoverable rather than bricked -
            // by ANY authenticated caller, capability or not: that is the recovery route
            assertEquals(200, authed(c.port, "DELETE", "/spaces/acme", null).statusCode());
            assertEquals(0, c.spaces.size());
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"gamma\"}", "Bearer plain").statusCode(),
                    "on an empty container creation needs no capability - it is the recovery route");

        } finally {
            Authenticators.forTest(null);
        }
    }

    /**
     * {@code ?purge=true} on the last space dir on disk is refused (409): it would leave an empty spaces root,
     * which {@code main()} exits rather than boots — an irrecoverable state over HTTP. The predicate counts
     * DIRECTORIES, not hosted spaces, so a space deregistered without purge still counts as a survivor.
     */
    @Test
    void purgingTheLastSpaceOnDiskIsRefused(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());

            HttpResponse<String> refused = send(c.port, "DELETE", "/spaces/acme?purge=true", null);
            assertEquals(409, refused.statusCode(), refused.body());
            assertTrue(Files.isDirectory(root.resolve("acme")), "refused purge left the tree intact");
            assertEquals(1, c.spaces.size(), "refused purge did not deregister it either");

            // a second space on disk makes the purge safe again
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"beta\"}").statusCode());
            assertEquals(200, send(c.port, "DELETE", "/spaces/acme?purge=true", null).statusCode());
            assertFalse(Files.exists(root.resolve("acme")));

            // beta is now last: deregister-only is still allowed and keeps the files for re-discovery
            assertEquals(200, send(c.port, "DELETE", "/spaces/beta", null).statusCode());
            assertEquals(0, c.spaces.size());
            assertTrue(Files.isDirectory(root.resolve("beta")), "deregister-only keeps the last tree on disk");

            // and an unhosted-but-on-disk space is still the reason a purge of a hosted one can proceed
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"gamma\"}").statusCode());
            assertEquals(200, send(c.port, "DELETE", "/spaces/gamma?purge=true", null).statusCode(),
                    "beta's tree survives on disk, so purging gamma is recoverable");
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        return send(port, method, path, body, null);
    }

    private HttpResponse<String> authed(int port, String method, String path, String body) throws Exception {
        return send(port, method, path, body, "Bearer valid");
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (auth != null) b.header("Authorization", auth);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception { return V1Body.of(r.body()); }
}
