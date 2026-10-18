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
            new String[]{"GET", "/inv/value-measures"},                       // ValueMeasureRoutes
            new String[]{"GET", "/inv/entity-identities"},                    // EntityIdentityRoutes
            new String[]{"GET", "/inv/entity-types"},                         // EntityProfileRoutes
            new String[]{"POST", "/inv/pattern/branching"},                   // PatternRoutes
            new String[]{"POST", "/geo/projection"});                         // GeoRoutes

    /** {method, path} - one per Investigation route module, every one refused on Basic. */
    private static final List<String[]> INVESTIGATION = List.of(
            new String[]{"GET", "/inv/investigations"},                       // InvestigationRoutes
            new String[]{"POST", "/inv/investigations"},                      // InvestigationRoutes (a write)
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
            new String[]{"POST", "/inv/uploads/x"},                           // UploadRoutes (LA-ANALYST-UPLOAD-1: Standard and above)
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

            JsonNode features = JSON.readTree(send(port, "GET", "/bootstrap", null, ADMIN).body()).at("/data/features");
            assertTrue(features.at("/investigations").asBoolean(), "Standard reports Investigations: " + features);
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
