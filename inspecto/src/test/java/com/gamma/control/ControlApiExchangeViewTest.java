package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BACKLOG D9 over real HTTP: the Exchange {@code kind} axis carries a saved View. A view is metadata that
 * reads Datasets, so its grant closure spans <em>every</em> Dataset it reads (revoking any one closes the
 * view), it is live-mode only, and only an {@code entity-projection} view is shareable at all — the other
 * graph sources' roots are Pipelines/catalog assets the Exchange cannot grant.
 */
class ControlApiExchangeViewTest {

    private static final String VIEW = "link-analysis-view";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    private Ctx open(Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    /** Two spaces, two datasets, and a two-root entity-projection view plus a lineage view. */
    private void seed(int port) throws Exception {
        assertEquals(200, send(port, "POST", "/spaces", "{\"id\":\"finance\"}").statusCode());
        assertEquals(200, send(port, "POST", "/spaces", "{\"id\":\"audit\"}").statusCode());
        for (String ds : new String[]{"payments", "accounts"})
            assertEquals(200, send(port, "POST", "/spaces/finance/components/dataset",
                    "{\"id\":\"" + ds + "\",\"physicalRef\":\"" + ds + "\"}").statusCode());
        // an entity-projection view reads its Datasets through projection MAPPINGS, not roots/from
        assertEquals(200, send(port, "POST", "/spaces/finance/components/" + VIEW,
                "{\"id\":\"fraud_ring\",\"sourceId\":\"entity-projection\",\"query\":{\"projections\":["
                        + "{\"datasetId\":\"payments\",\"sourceCol\":\"a\",\"targetCol\":\"b\"},"
                        + "{\"datasetId\":\"accounts\",\"sourceCol\":\"a\",\"targetCol\":\"b\"}]}}").statusCode());
        // a lineage view's roots are catalog assets — not Datasets, so it is not shareable
        assertEquals(200, send(port, "POST", "/spaces/finance/components/" + VIEW,
                "{\"id\":\"asset_flow\",\"sourceId\":\"lineage\",\"query\":{\"from\":\"some_asset\"}}").statusCode());
    }

    @Test
    void savedViewSharesLiveWithItsFullDatasetClosure(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            seed(c.port);

            // offering the view before its datasets → 409, naming the dataset to offer first
            HttpResponse<String> early = send(c.port, "POST", "/exchange/offers",
                    "{\"kind\":\"" + VIEW + "\",\"item\":\"fraud_ring\",\"owner\":\"finance\"}");
            assertEquals(409, early.statusCode(), early.body());

            // offer both datasets, then the view — its closure records BOTH roots
            for (String ds : new String[]{"payments", "accounts"})
                assertEquals(200, send(c.port, "POST", "/exchange/offers",
                        "{\"kind\":\"dataset\",\"item\":\"" + ds + "\",\"owner\":\"finance\"}").statusCode());
            HttpResponse<String> vo = send(c.port, "POST", "/exchange/offers",
                    "{\"kind\":\"" + VIEW + "\",\"item\":\"fraud_ring\",\"owner\":\"finance\"}");
            assertEquals(200, vo.statusCode(), vo.body());
            assertEquals(2, json(vo).get("datasets").size(), vo.body());

            // an explicit snapshot mode is REJECTED, not coerced to live (a view has no rows of its own)
            HttpResponse<String> snap = send(c.port, "POST", "/exchange/requests",
                    "{\"kind\":\"" + VIEW + "\",\"item\":\"fraud_ring\",\"owner\":\"finance\","
                            + "\"consumer\":\"audit\",\"mode\":\"snapshot\"}");
            assertEquals(422, snap.statusCode(), snap.body());

            // the real request defaults to live and pulls in a grant per dataset (3 total for audit)
            HttpResponse<String> req = send(c.port, "POST", "/exchange/requests",
                    "{\"kind\":\"" + VIEW + "\",\"item\":\"fraud_ring\",\"owner\":\"finance\",\"consumer\":\"audit\"}");
            assertEquals(200, req.statusCode(), req.body());
            assertEquals("live", json(req).get("mode").asText());
            String vid = json(req).get("id").asText();
            assertEquals(3, json(send(c.port, "GET", "/exchange/grants?space=audit", null)).size());

            // not renderable while pending
            assertEquals(403, send(c.port, "GET", "/exchange/views/finance/fraud_ring?consumer=audit", null).statusCode());

            // approving the view activates the whole closure; roots come back as grant-checked shared refs
            assertEquals(200, send(c.port, "POST", "/exchange/grants/" + vid + "/approve", "").statusCode());
            HttpResponse<String> render = send(c.port, "GET", "/exchange/views/finance/fraud_ring?consumer=audit", null);
            assertEquals(200, render.statusCode(), render.body());
            assertTrue(json(render).get("readOnly").asBoolean());
            JsonNode mappings = json(render).get("content").get("query").get("projections");
            assertEquals("shared/finance/payments", mappings.get(0).get("datasetId").asText());
            assertEquals("shared/finance/accounts", mappings.get(1).get("datasetId").asText());
            assertEquals("a", mappings.get(0).get("sourceCol").asText(), "the rest of the mapping survives");

            // revoking ONE of the two dataset grants closes the view — a partial closure is not a free pass
            assertEquals(200, send(c.port, "POST",
                    "/exchange/grants/audit~finance~dataset~accounts/revoke", "").statusCode());
            assertEquals(403, send(c.port, "GET", "/exchange/views/finance/fraud_ring?consumer=audit", null).statusCode());
        }
    }

    /**
     * The single-mapping shape (`query.projection`, no `projections` array) is what the shipped saved views
     * actually carry, so the closure must be derived from it too — not only from the multi-mapping array.
     */
    @Test
    void aSingleMappingViewIsShareableToo(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            seed(c.port);
            assertEquals(200, send(c.port, "POST", "/spaces/finance/components/" + VIEW,
                    "{\"id\":\"one_hop\",\"sourceId\":\"entity-projection\",\"query\":{\"projection\":"
                            + "{\"datasetId\":\"payments\",\"sourceCol\":\"a\",\"targetCol\":\"b\"}}}").statusCode());
            assertEquals(200, send(c.port, "POST", "/exchange/offers",
                    "{\"kind\":\"dataset\",\"item\":\"payments\",\"owner\":\"finance\"}").statusCode());
            HttpResponse<String> vo = send(c.port, "POST", "/exchange/offers",
                    "{\"kind\":\"" + VIEW + "\",\"item\":\"one_hop\",\"owner\":\"finance\"}");
            assertEquals(200, vo.statusCode(), vo.body());
            assertEquals(1, json(vo).get("datasets").size(), vo.body());
            assertEquals("payments", json(vo).get("datasets").get(0).asText());

            String vid = json(send(c.port, "POST", "/exchange/requests",
                    "{\"kind\":\"" + VIEW + "\",\"item\":\"one_hop\",\"owner\":\"finance\",\"consumer\":\"audit\"}"))
                    .get("id").asText();
            assertEquals(200, send(c.port, "POST", "/exchange/grants/" + vid + "/approve", "").statusCode());
            HttpResponse<String> render = send(c.port, "GET", "/exchange/views/finance/one_hop?consumer=audit", null);
            assertEquals(200, render.statusCode(), render.body());
            assertEquals("shared/finance/payments",
                    json(render).get("content").get("query").get("projection").get("datasetId").asText());
        }
    }

    /** Only an entity-projection view is shareable: other sources' roots are not Datasets we can grant. */
    @Test
    void onlyAnEntityProjectionViewIsShareable(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            seed(c.port);
            HttpResponse<String> r = send(c.port, "POST", "/exchange/offers",
                    "{\"kind\":\"" + VIEW + "\",\"item\":\"asset_flow\",\"owner\":\"finance\"}");
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("entity-projection"), r.body());
        }
    }

    /** The snapshot refresh path is dataset-only — a view offer is not reachable through it. */
    @Test
    void refreshDoesNotApplyToAView(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            seed(c.port);
            for (String ds : new String[]{"payments", "accounts"})
                assertEquals(200, send(c.port, "POST", "/exchange/offers",
                        "{\"kind\":\"dataset\",\"item\":\"" + ds + "\",\"owner\":\"finance\"}").statusCode());
            assertEquals(200, send(c.port, "POST", "/exchange/offers",
                    "{\"kind\":\"" + VIEW + "\",\"item\":\"fraud_ring\",\"owner\":\"finance\"}").statusCode());
            assertEquals(404, send(c.port, "POST", "/exchange/refresh",
                    "{\"owner\":\"finance\",\"item\":\"fraud_ring\"}").statusCode());
        }
    }

    @Test
    void unknownKindIsStillRejected(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            seed(c.port);
            HttpResponse<String> r = send(c.port, "POST", "/exchange/offers",
                    "{\"kind\":\"dashboard\",\"item\":\"fraud_ring\",\"owner\":\"finance\"}");
            assertEquals(400, r.statusCode(), r.body());
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception { return V1Body.of(r.body()); }
}
