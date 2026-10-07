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
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * R3 on the Geo routes — a Dataset shared away from the caller is indistinguishable from an absent one on
 * {@code POST /geo/projection} and {@code POST /geo/routes}, exactly as it already is on {@code /inv/projection}
 * ({@link com.gamma.la.api.InvRoutes#relationFor}: unknown → 404, not viewable → the SAME 404).
 *
 * <p><b>No core-level gate covers {@code /geo/*}.</b> Neither route carries {@code withCapability}, the core
 * {@code ControlApi} knows the paths only for the Personal absent-stub and the {@code features.geoLink} probe, and
 * the per-Dataset {@code owner}/{@code shares} check lives in {@code ComponentAccess.canView}, which only the route
 * calls. Before this test the Geo routes resolved the Dataset and built its relation SQL without that call, so any
 * authenticated caller could read points and routes out of a Dataset it had been denied.
 *
 * <p>Package {@code com.gamma.control} in the geo-link module is a TEST-SCOPE split package, on purpose (see
 * {@link ControlApiGeoProjectionTest}): it drives the real dispatcher with an armed Authenticator.
 */
class ControlApiGeoDatasetViewGateTest {

    private static final String OWNER = "tok-owner", VIEWER = "tok-viewer", STRANGER = "tok-stranger";
    private static final String GEO_POINTS = """
            {"dataset":"%s","latCol":"lat","lonCol":"lon","entityCol":"who","kindCol":"kind"}""";
    private static final String GEO_ROUTES = """
            {"dataset":"%s","fromLatCol":"alat","fromLonCol":"alon","toLatCol":"blat","toLonCol":"blon",
             "fromCol":"who","toCol":"other","kindCol":"kind"}""";
    private static final String INV_PROJECTION = """
            {"dataset":"%s","sourceCol":"who","targetCol":"other"}""";

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
    }

    private static void subjects() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case OWNER -> Optional.of(new Subject("analyst-1", Set.of()));
            case VIEWER -> Optional.of(new Subject("analyst-2", Set.of()));
            case STRANGER -> Optional.of(new Subject("analyst-3", Set.of()));
            default -> Optional.empty();
        });
    }

    /** One view that serves all three projections; two Datasets over it, one restricted to its owner + one viewer. */
    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("sights_view", "flow-x", List.of(),
                    "SELECT * FROM (VALUES "
                            + "('alice', 'bob',   23.81, 90.41, 'call', 23.81, 90.41, 23.72, 90.40),"
                            + "('bob',   'carol', 23.72, 90.40, 'call', 23.72, 90.40, 40.71, -74.00)"
                            + ") AS t(who, other, lat, lon, kind, alat, alon, blat, blon)",
                    "2026-10-01T00:00:00Z"));
            ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
            // `shares` PRESENT ⇒ restricted: only the owner (and admins) see it, plus whoever is listed.
            store.write("dataset", "private_ds", Map.of("view", "sights_view", "owner", "analyst-1", "shares", List.of()));
            store.write("dataset", "shared_ds", Map.of("view", "sights_view", "owner", "analyst-1", "shares",
                    List.of(Map.of("subjectType", "user", "subjectId", "analyst-2", "access", "view"))));
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private int post(Ctx c, String route, String body, String token) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/" + route))
                .header("Content-Type", "application/json").header("Authorization", token)
                .method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString()).statusCode();
    }

    @Test
    void theOwnerReadsItsDatasetOnEveryProjectionRoute(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, post(c, "inv/projection", INV_PROJECTION.formatted("private_ds"), OWNER));
            assertEquals(200, post(c, "geo/projection", GEO_POINTS.formatted("private_ds"), OWNER));
            assertEquals(200, post(c, "geo/routes", GEO_ROUTES.formatted("private_ds"), OWNER));
        }
    }

    /** The control: /inv/projection already answers the shared-away 404 — the behaviour /geo/* must match. */
    @Test
    void aDatasetSharedAwayAnswersTheSame404OnInvAndOnBothGeoRoutes(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            assertEquals(404, post(c, "inv/projection", INV_PROJECTION.formatted("private_ds"), STRANGER),
                    "control: /inv/projection already enforces R3");
            assertEquals(404, post(c, "geo/projection", GEO_POINTS.formatted("private_ds"), STRANGER),
                    "/geo/projection must not read a Dataset the caller may not view");
            assertEquals(404, post(c, "geo/routes", GEO_ROUTES.formatted("private_ds"), STRANGER),
                    "/geo/routes must not read a Dataset the caller may not view");
            // indistinguishable from absence: the very same status as a Dataset that does not exist
            assertEquals(404, post(c, "geo/projection", GEO_POINTS.formatted("ghost_ds"), STRANGER));
        }
    }

    /** The other side of the gate — a share grants view, so the fix must not over-block. */
    @Test
    void aDatasetSharedWithTheCallerStaysReadable(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, post(c, "geo/projection", GEO_POINTS.formatted("shared_ds"), VIEWER));
            assertEquals(200, post(c, "geo/routes", GEO_ROUTES.formatted("shared_ds"), VIEWER));
            assertEquals(404, post(c, "geo/projection", GEO_POINTS.formatted("shared_ds"), STRANGER),
                    "a share names ONE user; everyone else is still shared away");
        }
    }
}
