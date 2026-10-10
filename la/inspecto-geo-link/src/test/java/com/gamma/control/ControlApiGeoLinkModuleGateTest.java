package com.gamma.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.ErrorCodes;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-MODULE-GATE: on a Space whose {@code modules.toon} disables {@code geoLink}, EVERY LA route module answers 404
 * MODULE_DISABLED - not only the ones that happened to override {@code featureIds()}. One representative route per
 * route module, over real HTTP with an armed Authenticator; the same routes on an enabled Space reach their handler.
 */
class ControlApiGeoLinkModuleGateTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN = "Bearer admin";
    private final HttpClient client = HttpClient.newHttpClient();

    /** {method, path} - one per RouteModule registering /inv or /geo routes. */
    private static final List<String[]> ROUTES = List.of(
            new String[]{"GET", "/inv/schema/relationships"},                 // InvRoutes
            new String[]{"POST", "/geo/projection"},                          // GeoRoutes
            new String[]{"GET", "/inv/investigations"},                       // InvestigationRoutes
            new String[]{"GET", "/inv/investigations/x/dossier"},             // DossierRoutes
            new String[]{"GET", "/inv/investigations/x/dossier/bundle"},      // DossierBundleRoutes
            new String[]{"GET", "/inv/investigations/x/drafts"},              // DraftRoutes
            new String[]{"GET", "/inv/entity-identities"},                    // EntityIdentityRoutes
            new String[]{"GET", "/inv/graph/algorithms"},                     // GraphRunRoutes
            new String[]{"GET", "/inv/index"},                                // IndexRoutes
            new String[]{"GET", "/inv/investigations/x/case"},                // InvestigationCaseRoutes
            new String[]{"GET", "/inv/investigations/x/compare"},             // InvestigationComparisonRoutes
            new String[]{"GET", "/inv/investigations/x/coverage"},            // InvestigationCoverageRoutes
            new String[]{"GET", "/inv/investigations/x/members"},             // InvestigationMemberRoutes
            new String[]{"GET", "/inv/investigations/x/references"},          // InvestigationReferenceRoutes
            new String[]{"GET", "/inv/investigation-templates"},              // InvestigationTemplateRoutes
            new String[]{"POST", "/inv/pattern/branching"},                   // PatternRoutes
            new String[]{"GET", "/inv/value-measures"},                       // ValueMeasureRoutes
            new String[]{"GET", "/inv/investigations/x/working-set"},         // WorkingSetRoutes
            new String[]{"GET", "/inv/investigations/x/measures"});           // InvestigationMeasureRoutes (geo-link)

    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> {
            if (!ADMIN.equals(ex.getRequestHeaders().getFirst("Authorization"))) return Optional.empty();
            Roles.Def def = Roles.effective(ex).get("admin");
            ComponentAccess.heldRoles(ex, java.util.Set.of("admin"));
            return Optional.of(new Subject("admin-1", def.capabilities(), def.dataScopes()));
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    @Test
    void everyLaRouteModuleIsGatedByGeoLinkPerSpace(@TempDir Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        try {
            int port = api.port();
            HttpResponse<String> mk = send(port, "POST", "/spaces", "{\"id\":\"off\"}");
            assertEquals(200, mk.statusCode(), mk.body());
            assertEquals(200, send(port, "POST", "/spaces", "{\"id\":\"on\"}").statusCode());
            HttpResponse<String> put = send(port, "PUT", "/spaces/off/settings/modules", "{\"disabled\":[\"geoLink\"]}");
            assertEquals(200, put.statusCode(), put.body());

            List<String> leaks = new java.util.ArrayList<>();
            for (String[] r : ROUTES) {
                HttpResponse<String> off = send(port, r[0], "/spaces/off" + r[1], "{}");
                String code = off.statusCode() == 404 ? JSON.readTree(off.body()).at("/error/errorCode").asText() : "";
                if (!ErrorCodes.MODULE_DISABLED.equals(code)) leaks.add(r[0] + " " + r[1] + " -> " + off.statusCode() + " " + off.body());

                HttpResponse<String> on = send(port, r[0], "/spaces/on" + r[1], "{}");
                assertNotEquals(401, on.statusCode(), r[1]);
                String onCode = on.body().isBlank() ? "" : JSON.readTree(on.body()).at("/error/errorCode").asText();
                assertNotEquals(ErrorCodes.MODULE_DISABLED, onCode, "enabled Space must reach the handler: " + r[1]);
            }
            assertTrue(leaks.isEmpty(), "routes not gated by geoLink:\n" + String.join("\n", leaks));
        } finally {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .header("Authorization", ADMIN).header("Content-Type", "application/json");
        b.method(method, "GET".equals(method) ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
