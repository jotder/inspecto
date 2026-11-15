package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first run, for the person who creates the Space ({@code LA-FULL-CYCLE-TEST-1} phase 1, findings 1 and 2), over real
 * HTTP on a multi-Space server where people sign in, with the SEEDED role table: an {@code admin} creates a Space, turns
 * Add data on in it the way the Inspecto LA create does ({@code PUT /spaces/{id}/settings/data}), adds a file, saves a
 * Link Analysis view and changes a Link Analysis setting and an analysis limit. Every step answers 200.
 *
 * <p>The switch itself is unchanged: Add data is off in a new Space until it is turned on, the refusal says only what is
 * true of a Space with no Pipeline, and a caller without {@code canAdminister} cannot turn it on - whatever else they hold.
 */
class ControlApiFirstRunTest {

    private static final String ADMIN = "Bearer admin", ANALYST = "Bearer analyst", POWER = "Bearer power";
    private static final String SPACE = "/spaces/mobile-money";
    private final HttpClient client = HttpClient.newHttpClient();
    private SpaceManager spaces;
    private ControlApi api;

    /** Each caller holds exactly what the seeded role of that name grants in the Space the request names. */
    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> {
            String role = switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
                case ADMIN -> "admin";
                case ANALYST -> "operations";   // canUploadData, neither canAdminister nor canAuthorWorkbench
                case POWER -> "power";          // canAuthorWorkbench and canUploadData, not canAdminister
                default -> null;
            };
            if (role == null) return Optional.empty();
            Roles.Def def = Roles.effective(ex).get(role);
            ComponentAccess.heldRoles(ex, Set.of(role));
            return Optional.of(new Subject(role + "-1", def.capabilities(), def.dataScopes()));
        });
    }

    @AfterEach
    void close() {
        Authenticators.forTest(null);
        if (api != null) api.close();
        if (spaces != null) spaces.close();
        MetricRegistry.global().reset();
    }

    private HttpResponse<String> send(String method, String path, byte[] body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + api.port() + "/api/v1" + path))
                .header("Authorization", auth);
        b = body == null ? (method.equals("GET") ? b.GET() : b.method(method, BodyPublishers.noBody()))
                : b.method(method, BodyPublishers.ofByteArray(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> json(String method, String path, String body, String auth) throws Exception {
        return send(method, path, body == null ? null : body.getBytes(StandardCharsets.UTF_8), auth);
    }

    private static JsonNode data(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    private static String error(HttpResponse<String> r) throws Exception {
        return V1Body.envelope(r.body()).path("error").path("message").asText();
    }

    @Test
    void theAdministratorWhoCreatesASpaceAddsAFileSavesAViewAndChangesASetting(@TempDir Path root) throws Exception {
        spaces = SpaceManager.discover(root);
        api = new ControlApi(spaces, 0);
        api.start();
        byte[] csv = ControlApiOnboardFilesTest.transfers(60).getBytes(StandardCharsets.UTF_8);

        // 1. Create a Space (the Inspecto LA dialog sends the id and the name).
        data(json("POST", "/spaces", "{\"id\":\"mobile-money\",\"display_name\":\"Mobile money\"}", ADMIN), 200);
        // 1b. A Space a signed-in person creates is closed (D5): others are not there until the Space defines their role.
        assertEquals(404, json("GET", SPACE + "/settings/data", null, ANALYST).statusCode(), "not admitted yet");
        data(json("PUT", SPACE + "/access/roles", "{\"roles\":["
                + "{\"name\":\"operations\",\"capabilities\":[\"canUploadData\"]},"
                + "{\"name\":\"power\",\"capabilities\":[\"canAuthorWorkbench\",\"canUploadData\"]}]}", ADMIN), 200);

        // 2. Where people sign in, Add data is off in a new Space, and the refusal claims nothing about Pipelines:
        //    this Space has none.
        JsonNode off = data(json("GET", SPACE + "/settings/data", null, ADMIN), 200);
        assertTrue(off.get("selfImport").isNull(), "nothing stated yet: " + off);
        assertFalse(off.get("selfImportInForce").asBoolean(), off.toString());
        HttpResponse<String> locked = send("POST", SPACE + "/onboard/files?name=transfers.csv&kind=stream", csv, ADMIN);
        assertEquals(403, locked.statusCode(), locked.body());
        assertEquals("Add data is turned off in this Space; an administrator can turn it on", error(locked));
        assertEquals(OnboardRoutes.SELF_IMPORT_OFF, error(locked));

        // 3. Only an administrator turns it on: not an analyst who may upload, not an author who may upload.
        for (String notAdmin : new String[] {ANALYST, POWER}) {
            HttpResponse<String> refused = json("PUT", SPACE + "/settings/data", "{\"selfImport\":true}", notAdmin);
            assertEquals(403, refused.statusCode(), notAdmin + ": " + refused.body());
        }
        assertFalse(Files.exists(root.resolve("mobile-money/config").resolve(DataSettings.FILE)), "a refused write writes nothing");
        assertEquals(403, send("POST", SPACE + "/onboard/files?name=transfers.csv&kind=stream", csv, ANALYST).statusCode(),
                "still off for everyone");

        // 4. The creator turns it on, as the create flow and the Turn on Add data button do.
        JsonNode on = data(json("PUT", SPACE + "/settings/data", "{\"selfImport\":true}", ADMIN), 200);
        assertTrue(on.get("selfImportInForce").asBoolean(), on.toString());

        // 5. Add a file: it becomes a Dataset.
        JsonNode draft = data(send("POST", SPACE + "/onboard/files?name=transfers.csv&kind=stream", csv, ADMIN), 200);
        JsonNode summary = data(json("POST", SPACE + "/onboard/files/" + draft.get("draftId").asText() + "/commit",
                "{\"name\":\"transfers\"}", ADMIN), 200);
        assertEquals(60, summary.get("rowsAfter").asLong(), summary.toString());
        data(json("GET", SPACE + "/components/dataset/transfers", null, ADMIN), 200);

        // 6. Save a view of it (File, Save view), which the seeded admin could not do before LA-FULL-CYCLE-TEST-1 D1.
        String view = "{\"id\":\"first-graph\",\"title\":\"First graph\",\"sourceId\":\"entity-projection\","
                + "\"query\":{\"projection\":{\"datasetId\":\"transfers\",\"sourceCol\":\"from_account\",\"targetCol\":\"to_account\"}}}";
        data(json("POST", SPACE + "/components/link-analysis-view", view, ADMIN), 200);
        assertEquals("First graph", data(json("GET", SPACE + "/components/link-analysis-view/first-graph", null, ADMIN), 200)
                .at("/content/title").asText());

        // 7. Change a Link Analysis setting and an analysis limit (Settings, Link Analysis and Analysis limits).
        data(json("PUT", SPACE + "/settings/link-analysis", "{\"maskingMode\":\"typed\"}", ADMIN), 200);
        assertEquals("typed", data(json("GET", SPACE + "/settings/link-analysis", null, ADMIN), 200).get("maskingMode").asText());
        data(json("PUT", SPACE + "/settings/link-analysis/limits", "{\"traversal.timeout_s\":7}", ADMIN), 200);

        // An analyst of the Space may now add a file too; turning Add data off again stays the administrator's.
        data(send("POST", SPACE + "/onboard/files?name=more.csv&kind=stream", csv, ANALYST), 200);
        assertEquals(403, json("PUT", SPACE + "/settings/data", "{\"selfImport\":false}", ANALYST).statusCode());
    }
}
