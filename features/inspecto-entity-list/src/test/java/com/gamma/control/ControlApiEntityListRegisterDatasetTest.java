package com.gamma.control;

import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.CollectorService;
import com.gamma.service.SpaceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import com.gamma.access.Roles;
import com.gamma.access.ComponentAccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ASSURE-ENTITY-LISTS-RESIDUALS-1 (3) over real HTTP: {@code POST /entity-lists/{id}/register-dataset} registers the
 * Dataset over a list's Parquet sidecar ON AN EXPLICIT CALL, through the validated Dataset save path — and a list
 * write never does. Real Spaces on disk (a Space has a data root, so the sidecar is really written), with a Subject
 * per caller: a route with no Subject makes {@code withCapability} a no-op, so the 403 tests prove the gate is armed.
 *
 * <p>Callers: {@code operations} holds canManageIncidents (writes lists) but NOT canAuthorWorkbench;
 * {@code pipeline-developer} the reverse; {@code admin} approves and sets the approval policy.
 */
class ControlApiEntityListRegisterDatasetTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OPS = "Bearer ops", DEV = "Bearer dev", ADMIN = "Bearer admin", NONE = "Bearer none";
    private static final String BLOCK = "{\"id\":\"bl\",\"title\":\"Fraud blocks\",\"purpose\":\"block\","
            + "\"entityType\":\"msisdn\",\"reason\":\"FR-9\"}";
    private static final String REF = "entity_list_bl";
    private final HttpClient client = HttpClient.newHttpClient();
    private String inheritedWriteRoot;

    @BeforeEach
    void arm() {
        inheritedWriteRoot = System.getProperty("assist.write.root");
        System.clearProperty("assist.write.root");
        Authenticators.forTest(ex -> {
            String h = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            if (NONE.equals(h)) return Optional.of(new Subject("nobody", Set.of()));
            String[] who = switch (h) {
                case OPS -> new String[] {"ops-1", "operations"};
                case DEV -> new String[] {"dev-1", "pipeline-developer"};
                case ADMIN -> new String[] {"admin-1", "admin"};
                default -> null;
            };
            if (who == null) return Optional.empty();
            Roles.Def def = Roles.effective(ex).get(who[1]);
            ComponentAccess.heldRoles(ex, Set.of(who[1]));
            return Optional.of(new Subject(who[0], def.capabilities(), def.dataScopes()));
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
        if (inheritedWriteRoot != null) System.setProperty("assist.write.root", inheritedWriteRoot);
    }

    /** {@code data} = false leaves the Space with no data root on disk, so no sidecar can be written. */
    private static void space(Path spaces, String id, boolean data) throws Exception {
        Files.createDirectories(spaces.resolve(id).resolve("config").resolve("registry"));
        if (data) Files.createDirectories(spaces.resolve(id).resolve("data"));
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private interface Body {
        void run(int port, Path spaces) throws Exception;
    }

    private void withSpaces(Path spaces, Body body) throws Exception {
        SpaceManager manager = SpaceManager.discover(spaces);
        ControlApi api = new ControlApi(manager, 0);
        api.start();
        try {
            body.run(api.port(), spaces);
        } finally {
            api.close();
            manager.close();
            MetricRegistry.global().reset();
        }
    }

    private HttpResponse<String> dataset(int port, String space) throws Exception {
        return send(port, "GET", "/spaces/" + space + "/components/dataset/" + REF, null, ADMIN);
    }

    private HttpResponse<String> register(int port, String space, String auth) throws Exception {
        return send(port, "POST", "/spaces/" + space + "/entity-lists/bl/register-dataset", "{}", auth);
    }

    private void createList(int port, String space) throws Exception {
        data(send(port, "POST", "/spaces/" + space + "/entity-lists", BLOCK, OPS), 201);
        data(send(port, "POST", "/spaces/" + space + "/entity-lists/bl/members",
                "{\"add\":[\"447700900001\"],\"reason\":\"FR-9\"}", OPS), 200);
    }

    @Test
    void registersTheSidecarDatasetOnAnExplicitCallAndTheDatasetReadsTheList(@TempDir Path spaces) throws Exception {
        space(spaces, "acme", true);
        withSpaces(spaces, (port, root) -> {
            createList(port, "acme");
            // The list write did NOT register a Dataset — that is the whole point of the explicit action.
            assertEquals(404, dataset(port, "acme").statusCode(), "a list write must never write Dataset config");

            JsonNode out = data(register(port, "acme", DEV), 201);
            assertEquals(REF, out.get("datasetId").asText());
            assertEquals("bl", out.get("listId").asText());
            assertEquals(REF, out.at("/dataset/content/physicalRef").asText());

            JsonNode stored = data(dataset(port, "acme"), 200);
            assertEquals(REF, stored.at("/content/physicalRef").asText());
            assertEquals("dev-1", stored.at("/content/owner").asText(), "stamped from the caller, as a hand-authored Dataset");
            assertTrue(Files.isRegularFile(root.resolve("acme/config/registry/datasets/" + REF + ".toon")));

            // The Dataset really reads the sidecar through the ordinary relation path.
            JsonNode rows = data(send(port, "GET", "/spaces/acme/datasets/" + REF + "/rows", null, ADMIN), 200);
            assertEquals(1, rows.get("rows").size(), rows.toString());
            assertTrue(rows.get("rows").get(0).toString().contains("447700900001"), rows.toString());
        });
    }

    @Test
    void aSecondCallIs409AndNeverRewritesTheDataset(@TempDir Path spaces) throws Exception {
        space(spaces, "acme", true);
        withSpaces(spaces, (port, root) -> {
            createList(port, "acme");
            data(register(port, "acme", DEV), 201);
            // The author edits the Dataset; a repeat of the button must not clobber the edit.
            String edited = "{\"physicalRef\":\"" + REF + "\",\"description\":\"edited by hand\"}";
            data(send(port, "PUT", "/spaces/acme/components/dataset/" + REF, edited, DEV), 200);

            HttpResponse<String> again = register(port, "acme", DEV);
            assertEquals(409, again.statusCode(), again.body());
            assertEquals("edited by hand", data(dataset(port, "acme"), 200).at("/content/description").asText());
        });
    }

    @Test
    void aHandAuthoredDatasetOfTheSameIdBlocksTheRegistrationUntouched(@TempDir Path spaces) throws Exception {
        space(spaces, "acme", true);
        withSpaces(spaces, (port, root) -> {
            createList(port, "acme");
            data(send(port, "POST", "/spaces/acme/components/dataset",
                    "{\"id\":\"" + REF + "\",\"physicalRef\":\"somewhere_else\"}", DEV), 200);
            HttpResponse<String> r = register(port, "acme", DEV);
            assertEquals(409, r.statusCode(), r.body());
            assertEquals("somewhere_else", data(dataset(port, "acme"), 200).at("/content/physicalRef").asText());
        });
    }

    @Test
    void anUnknownListIs404AndNothingIsWritten(@TempDir Path spaces) throws Exception {
        space(spaces, "acme", true);
        withSpaces(spaces, (port, root) -> {
            HttpResponse<String> r = register(port, "acme", DEV);
            assertEquals(404, r.statusCode(), r.body());
            assertEquals(404, dataset(port, "acme").statusCode());
        });
    }

    @Test
    void aListWithNoSidecarOnDiskIs409BecauseTheDatasetWouldReadNothing(@TempDir Path spaces) throws Exception {
        space(spaces, "bare", false);   // identical but for the missing data root: the one thing the probe changes
        withSpaces(spaces, (port, root) -> {
            createList(port, "bare");
            HttpResponse<String> r = register(port, "bare", DEV);
            assertEquals(409, r.statusCode(), r.body());
            assertTrue(r.body().contains("no sidecar"), r.body());
            assertEquals(404, dataset(port, "bare").statusCode());
        });
    }

    @Test
    void theDatasetAuthoringCapabilityIsRequiredNotTheListWriteOne(@TempDir Path spaces) throws Exception {
        space(spaces, "acme", true);
        withSpaces(spaces, (port, root) -> {
            createList(port, "acme");
            // operations may write the list (canManageIncidents) but may not author a Dataset.
            assertEquals(403, register(port, "acme", OPS).statusCode());
            assertEquals(403, register(port, "acme", NONE).statusCode());
            assertEquals(404, dataset(port, "acme").statusCode(), "a refused caller wrote nothing");
            // ... and the capability alone is enough: pipeline-developer cannot write lists but registers.
            assertEquals(403, send(port, "POST", "/spaces/acme/entity-lists/bl/members", "{\"add\":[\"1\"]}", DEV).statusCode());
            assertEquals(201, register(port, "acme", DEV).statusCode());
        });
    }

    @Test
    void underAnApprovalPolicyForDatasetsTheRegistrationIsRefusedNotHeld(@TempDir Path spaces) throws Exception {
        space(spaces, "acme", true);
        withSpaces(spaces, (port, root) -> {
            createList(port, "acme");
            data(send(port, "PUT", "/spaces/acme/settings/approval", "{\"approval\":{\"dataset\":{\"required\":true}}}", ADMIN), 200);
            HttpResponse<String> r = register(port, "acme", DEV);
            assertEquals(409, r.statusCode(), r.body());
            assertFalse(Files.exists(root.resolve("acme/config/registry/datasets/" + REF + ".toon")));
            JsonNode pending = data(send(port, "GET", "/spaces/acme/pending-changes", null, ADMIN), 200);
            assertEquals(0, pending.get("items").size(),"refused, so no Pending Change that approve could not replay: " + pending);
        });
    }

    @Test
    void withoutAWriteRootTheRouteIs503(@TempDir Path cfg) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        try (CollectorService svc = new CollectorService(List.of(pipe), 3600, 1); ControlApi api = new ControlApi(svc, 0)) {
            api.start();
            assertEquals(503, send(api.port(), "POST", "/entity-lists/bl/register-dataset", "{}", DEV).statusCode());
        }
    }
}
