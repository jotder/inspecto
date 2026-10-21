package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.etl.EditionFeatures;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.auth.Subject;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-EDITIONS-1 phase 3 (ED-4): the <b>Basic</b> edition is the Link Analysis EXPLORER. The Link Analysis modules are
 * installed, the explorer routes answer with NO sign-in, and the Investigation surface answers
 * {@code 503 CAPABILITY_UNAVAILABLE} "available in Standard and above" - because no module declares the
 * {@link EditionFeatures#INVESTIGATIONS} edition feature (only {@code inspecto-ops}, Standard and up, does). In
 * <b>Standard</b> (the feature present, a signed-in caller) both halves answer. Over real HTTP.
 *
 * <p>Basic is simulated by pinning the edition features empty ({@link EditionFeatures#overrideForTest}); Standard by
 * the classpath's own {@link InvestigationsEditionForTests} stand-in for {@code inspecto-ops}.
 */
class ControlApiBasicEditionLinkAnalysisTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN = "Bearer admin";
    private final HttpClient client = HttpClient.newHttpClient();

    /** {method, path} - explorer routes Basic serves: one per explorer route module. */
    private static final List<String[]> EXPLORER = List.of(
            new String[]{"GET", "/inv/schema/relationships"},                 // InvRoutes
            new String[]{"POST", "/inv/projection"},                         // InvRoutes: what a Link Analysis dashboard widget reads (D-LAE2: stays open)
            new String[]{"POST", "/inv/projection/neighbors/preview"},       // InvRoutes: the explorer's Grow preview (LA-GROW-EXCLUDE-1 GE-10)
            new String[]{"GET", "/inv/value-measures"},                       // ValueMeasureRoutes
            new String[]{"GET", "/inv/entity-identities"},                    // EntityIdentityRoutes
            new String[]{"GET", "/inv/entity-types"},                         // EntityProfileRoutes
            new String[]{"POST", "/inv/pattern/branching"});                  // PatternRoutes

    /**
     * {method, path} - the Geo map routes (GeoRoutes), Standard and up through their OWN edition feature (D-LAE5, operator
     * 2026-10-20), refused on Basic while every explorer route above stays up.
     */
    private static final List<String[]> GEO = List.of(
            new String[]{"POST", "/geo/projection"},
            new String[]{"POST", "/geo/routes"});

    /** {method, path} - one per Investigation route module, every one refused on Basic. */
    private static final List<String[]> INVESTIGATION = List.of(
            new String[]{"GET", "/inv/investigations"},                       // InvestigationRoutes
            new String[]{"POST", "/inv/investigations"},                      // InvestigationRoutes (a write)
            new String[]{"POST", "/inv/investigations/x/expand/preview"},     // InvestigationRoutes: the Grow preview (LA-GROW-EXCLUDE-1)
            new String[]{"GET", "/inv/investigations/x/dossier"},             // DossierRoutes
            new String[]{"GET", "/inv/investigations/x/dossier/bundle"},      // DossierBundleRoutes
            new String[]{"GET", "/inv/investigations/x/drafts"},              // DraftRoutes
            new String[]{"GET", "/inv/graph/algorithms"},                     // GraphRunRoutes
            new String[]{"GET", "/inv/index"},                                // IndexRoutes
            new String[]{"GET", "/inv/datasets/x/files"},                     // DatasetFilesRoutes
            new String[]{"GET", "/inv/investigations/x/case"},                // InvestigationCaseRoutes
            new String[]{"GET", "/inv/investigations/x/compare"},             // InvestigationComparisonRoutes
            new String[]{"GET", "/inv/investigations/x/coverage"},            // InvestigationCoverageRoutes
            new String[]{"GET", "/inv/investigations/x/members"},             // InvestigationMemberRoutes
            new String[]{"GET", "/inv/investigations/x/references"},          // InvestigationReferenceRoutes
            new String[]{"GET", "/inv/investigations/x/embeds"},              // InvestigationEmbedRoutes
            new String[]{"GET", "/public/investigations/x"},                  // PublicInvestigationRoutes
            new String[]{"GET", "/inv/investigation-templates"},              // InvestigationTemplateRoutes
            new String[]{"GET", "/inv/investigations/x/working-set"},         // WorkingSetRoutes
            new String[]{"GET", "/inv/investigations/x/measures"},            // InvestigationMeasureRoutes (geo-link)
            new String[]{"GET", "/inv/investigations/x/alert-rules"},         // InvestigationMeasureRoutes: Alert Rules on Investigations
            new String[]{"GET", "/inv/investigations/x/data-changes"},        // InvestigationDataChangesRoutes (geo-link)
            // LA-EDITIONS-1 ED-6 / LD-EDITIONS-12: third-party integration is Standard and above, with the Investigations it feeds.
            new String[]{"POST", "/inv/inbound/investigations"},              // InvestigationInboundRoutes (alert -> Investigation)
            new String[]{"POST", "/inv/deep-links/resolve"},                  // InvestigationInboundRoutes ("Open in Inspecto LA")
            new String[]{"GET", "/inv/integration"},                          // IntegrationRoutes (connector settings)
            new String[]{"GET", "/inv/uploads"},                              // UploadRoutes status reads + delete (LA-I2-GAPS-RESIDUALS-1)
            new String[]{"GET", "/inv/uploads/x"},
            new String[]{"DELETE", "/inv/uploads/x/UPL_1.csv"},
            new String[]{"POST", "/inv/uploads/x"},                         // UploadRoutes (LA-ANALYST-UPLOAD-1: Standard and above)
            new String[]{"POST", "/inv/investigations/x/handoff"});           // IntegrationRoutes (case hand-off)

    @AfterEach
    void restore() {
        EditionFeatures.overrideForTest(null);
        Authenticators.forTest(null);
    }

    @Test
    void basicServesTheExplorerWithoutSignInAndRefusesInvestigations(@TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of());   // Basic: no module declares any edition feature
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        try {
            int port = api.port();
            assertEquals(200, send(port, "POST", "/spaces", "{\"id\":\"s\"}", null).statusCode());

            HttpResponse<String> vm = send(port, "GET", "/spaces/s/inv/entity-types", null, null);
            assertEquals(200, vm.statusCode(), "an explorer route answers with no sign-in on Basic: " + vm.body());
            List<String> refusedExplorer = new ArrayList<>();
            for (String[] r : EXPLORER) {
                HttpResponse<String> res = send(port, r[0], "/spaces/s" + r[1], "{}", null);
                if (res.statusCode() == 503 || res.statusCode() == 401) refusedExplorer.add(r[0] + " " + r[1] + " -> " + res.statusCode() + " " + res.body());
            }
            assertTrue(refusedExplorer.isEmpty(), "explorer routes Basic must serve:\n" + String.join("\n", refusedExplorer));

            List<String> geoServed = new ArrayList<>();
            for (String[] r : GEO) {
                HttpResponse<String> res = send(port, r[0], "/spaces/s" + r[1], "{}", null);
                JsonNode err = res.body().isBlank() ? JSON.missingNode() : JSON.readTree(res.body()).at("/error");
                if (res.statusCode() != 503 || !ErrorCodes.CAPABILITY_UNAVAILABLE.equals(err.at("/errorCode").asText())
                        || !err.at("/message").asText().contains("available in Standard and above"))
                    geoServed.add(r[0] + " " + r[1] + " -> " + res.statusCode() + " " + res.body());
            }
            assertTrue(geoServed.isEmpty(), "Geo routes Basic must refuse by edition:\n" + String.join("\n", geoServed));

            List<String> leaks = new ArrayList<>();
            for (String[] r : INVESTIGATION) {
                HttpResponse<String> res = send(port, r[0], "/spaces/s" + r[1], "{}", null);
                JsonNode err = res.body().isBlank() ? JSON.missingNode() : JSON.readTree(res.body()).at("/error");
                if (res.statusCode() != 503 || !ErrorCodes.CAPABILITY_UNAVAILABLE.equals(err.at("/errorCode").asText())
                        || !err.at("/message").asText().contains("available in Standard and above"))
                    leaks.add(r[0] + " " + r[1] + " -> " + res.statusCode() + " " + res.body());
            }
            assertTrue(leaks.isEmpty(), "Investigation routes Basic must refuse:\n" + String.join("\n", leaks));

            JsonNode features = JSON.readTree(send(port, "GET", "/bootstrap", null, null).body()).at("/data/features");
            assertTrue(features.at("/geoLink").asBoolean(), "the explorer is installed on Basic: " + features);
            assertFalse(features.at("/investigations").asBoolean(true), "Basic reports no Investigations: " + features);
            assertTrue(features.has("geoMap") && !features.at("/geoMap").asBoolean(),
                    "Basic reports the Geo map present-and-false while geoLink stays true: " + features);
            assertTrue(features.has("laWidgets") && !features.at("/laWidgets").asBoolean(),
                    "Basic reports the LA dashboard widgets present-and-false (a UI flag; the routes stay open, asserted above): " + features);
        } finally {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    /**
     * D-LAE1 (operator 2026-10-20): Case history beside a node is Standard and up by an EXPLICIT gate. Before, Basic answered
     * its 503 only because no Case module was there to read ("Case management is not installed"); this pins the EDITION
     * refusal instead, so installing a Case module on a Basic host would not open the route.
     */
    @Test
    void caseHistoryIsRefusedByEditionOnBasicAndReachesItsHandlerOnStandard(@TempDir Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        try {
            int port = api.port();
            Authenticators.forTest(ex -> {
                if (!ADMIN.equals(ex.getRequestHeaders().getFirst("Authorization"))) return Optional.empty();
                Roles.Def def = Roles.effective(ex).get("admin");
                ComponentAccess.heldRoles(ex, Set.of("admin"));
                return Optional.of(new Subject("admin-1", def.capabilities(), def.dataScopes()));
            });
            assertEquals(200, send(port, "POST", "/spaces", "{\"id\":\"s\"}", ADMIN).statusCode());
            String body = "{\"key\":\"27820000001\",\"entityType\":\"msisdn\"}";

            EditionFeatures.overrideForTest(Set.of());   // Basic
            HttpResponse<String> basic = send(port, "POST", "/spaces/s/inv/case-history", body, ADMIN);
            JsonNode err = JSON.readTree(basic.body()).at("/error");
            assertEquals(503, basic.statusCode(), basic.body());
            assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, err.at("/errorCode").asText(), basic.body());
            assertTrue(err.at("/message").asText().contains("available in Standard and above"), "the EDITION refusal: " + basic.body());
            assertTrue(err.at("/message").asText().contains("Case history"), basic.body());

            EditionFeatures.overrideForTest(null);       // Standard: the classpath stand-in declares Investigations
            HttpResponse<String> standard = send(port, "POST", "/spaces/s/inv/case-history", body, ADMIN);
            assertFalse(standard.body().contains("available in Standard and above"),
                    "Standard is past the edition gate (it may still refuse for want of a Case module): " + standard.body());
        } finally {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    @Test
    void standardServesBothTheExplorerAndInvestigations(@TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> {
            if (!ADMIN.equals(ex.getRequestHeaders().getFirst("Authorization"))) return Optional.empty();
            Roles.Def def = Roles.effective(ex).get("admin");
            ComponentAccess.heldRoles(ex, Set.of("admin"));
            return Optional.of(new Subject("admin-1", def.capabilities(), def.dataScopes()));
        });
        assertTrue(EditionFeatures.present(EditionFeatures.INVESTIGATIONS), "the Standard classpath declares Investigations");
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        try {
            int port = api.port();
            assertEquals(200, send(port, "POST", "/spaces", "{\"id\":\"s\"}", ADMIN).statusCode());

            HttpResponse<String> vm = send(port, "GET", "/spaces/s/inv/entity-types", null, ADMIN);
            assertEquals(200, vm.statusCode(), vm.body());
            HttpResponse<String> list = send(port, "GET", "/spaces/s/inv/investigations", null, ADMIN);
            assertEquals(200, list.statusCode(), "Standard serves Investigations: " + list.body());
            HttpResponse<String> templates = send(port, "GET", "/spaces/s/inv/investigation-templates", null, ADMIN);
            assertEquals(200, templates.statusCode(), templates.body());

            List<String> refused = new ArrayList<>();
            for (String[] r : INVESTIGATION) {
                HttpResponse<String> res = send(port, r[0], "/spaces/s" + r[1], "{}", ADMIN);
                if (res.statusCode() == 503) refused.add(r[0] + " " + r[1] + " -> " + res.body());
            }
            assertTrue(refused.isEmpty(), "Standard must reach every Investigation handler:\n" + String.join("\n", refused));
            for (String[] r : GEO) {
                HttpResponse<String> res = send(port, r[0], "/spaces/s" + r[1], "{}", ADMIN);
                assertFalse(res.body().contains("available in Standard and above"), "Standard is past the Geo edition gate: " + r[1] + " " + res.body());
            }

            JsonNode features = JSON.readTree(send(port, "GET", "/bootstrap", null, ADMIN).body()).at("/data/features");
            assertTrue(features.at("/investigations").asBoolean(), "Standard reports Investigations: " + features);
            assertTrue(features.at("/geoMap").asBoolean(), "Standard reports the Geo map: " + features);
            assertTrue(features.at("/laWidgets").asBoolean(), "Standard reports the LA dashboard widgets: " + features);
        } finally {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
