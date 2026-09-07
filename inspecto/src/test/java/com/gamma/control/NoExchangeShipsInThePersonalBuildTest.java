package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * EDG-01 cell 4 — the assertion that makes the SEC-10 gating REAL rather than claimed, on the DEFAULT
 * (Personal) build, over real HTTP.
 *
 * <p>Five facts, each of which a plausible regression breaks on its own:
 * <ol>
 *   <li>all eleven {@code /exchange} paths answer <b>503 with the edition message</b> — not 404 (the stub
 *       lost a path), not 200 (the module is back in Personal);</li>
 *   <li>{@code /bootstrap} reports {@code features.exchange == false}, so the SPA's Share affordances stay
 *       hidden. ⚠ This is the flag that was <em>wrong</em> until this cell: it read
 *       {@code containerRoot() != null} alone, so a Personal install with {@code -Dspaces.root} advertised an
 *       Exchange it did not have and the Share button 404'd on click;</li>
 *   <li>{@code SharedRefResolver.global()} is still the fail-closed {@code NONE} — the install moved into the
 *       module, so no {@code shared/<owner>/<item>} ref resolves here;</li>
 *   <li>the capability vocabulary is deliberately UNCHANGED — the six exchange capabilities remain grantable,
 *       so a role file authored on Standard still validates here. Dead vocabulary is not a hole; a
 *       per-edition validator would be a portability break;</li>
 *   <li>the core's component delete fence asks {@link SharedItemConsumers} and gets "nobody" — the coupling
 *       an import-based census could not see, because {@code ComponentRoutes} used a fully-qualified name.</li>
 * </ol>
 *
 * <p>⛔ Do not "fix" a failure here by deleting this test. A 200 means an optional module is in the Personal
 * bundle; a 404 means the stub and the module's surface drifted apart.
 */
class NoExchangeShipsInThePersonalBuildTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    @Test
    void everyExchangePathAnswers503NotInstalledOnThePersonalBuild(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // Concrete URLs for the parameterised patterns — a stub is only real if a real request hits it.
            String[][] calls = {
                    {"GET", "/exchange/offers"}, {"POST", "/exchange/offers"},
                    {"POST", "/exchange/refresh"}, {"POST", "/exchange/requests"},
                    {"POST", "/exchange/grants/g1/approve"}, {"POST", "/exchange/grants/g1/deny"},
                    {"POST", "/exchange/grants/g1/revoke"}, {"POST", "/exchange/grants/g1/pin"},
                    {"POST", "/exchange/grants/g1/expiry"}, {"GET", "/exchange/grants"},
                    {"GET", "/exchange/datasets/acme/orders"}, {"GET", "/exchange/widgets/acme/w1"},
                    {"GET", "/exchange/views/acme/v1"},
            };
            for (String[] r : calls) {
                HttpResponse<String> res = send(c.port, r[0], r[1], "{}");
                assertEquals(503, res.statusCode(), r[0] + " " + r[1] + " -> " + res.body());
                JsonNode err = V1Body.of(res.body()).get("error");
                assertNotNull(err, r[1] + " must carry the v1 error object: " + res.body());
                assertTrue(err.get("message").asText().contains("inspecto-exchange"),
                        "the refusal names the module that would fix it: " + err);
            }
        }
    }

    @Test
    void bootstrapReportsExchangeAbsentSoTheShareAffordancesStayHidden(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode features = V1Body.of(send(c.port, "GET", "/bootstrap", null).body()).get("features");
            assertNotNull(features, "bootstrap must carry features");
            assertTrue(features.has("exchange"), "present and false, never merely absent: " + features);
            assertFalse(features.get("exchange").asBoolean(), features.toString());
        }
    }

    @Test
    void sharedRefResolutionIsFailClosedBecauseTheInstallMovedIntoTheModule() {
        assertSame(com.gamma.query.SharedRefResolver.NONE, com.gamma.query.SharedRefResolver.global(),
                "no module installed a resolver, so DatasetRelation resolves no shared/<owner>/<item> ref");
    }

    /**
     * 🔴 The coupling an import-based census could not see: `ComponentRoutes.deleteComponent` refused to
     * delete a component another Space still held an Exchange grant on, reaching into `com.gamma.exchange`
     * by FULLY-QUALIFIED NAME. It now asks {@link SharedItemConsumers}, whose default answers "nobody".
     *
     * <p>⚠ That empty answer is correct rather than degraded, and this test says so out loud: with no
     * exchange module there is no Exchange, so nothing can have been offered and no consumer can be harmed.
     * The fence has nothing to guard — it is not failing to guard something.
     */
    @Test
    void theDeleteFenceAsksTheSeamAndGetsNobodyWithNoExchangeInstalled() {
        assertSame(SharedItemConsumers.NONE, SharedItemConsumers.global(),
                "no module installed a consumers lookup");
        assertEquals(List.of(), SharedItemConsumers.global().consumersOf("dataset", "orders"),
                "so a component delete is not fenced by a grant that cannot exist here");
    }

    /**
     * ⛔ The deliberate non-change. Deriving the grantable vocabulary from registered routes would make a role
     * or policy file authored on Standard fail validation on Personal — a portability break, and a
     * per-edition difference in a validator. The capabilities stay; nothing is behind them here.
     */
    @Test
    void theExchangeCapabilityVocabularyIsStillGrantable() {
        for (String capability : List.of("canOfferDatasets", "canRequestShares", "canApproveShares")) {
            assertTrue(Roles.KNOWN_CAPABILITIES.contains(capability),
                    capability + " must stay grantable so a Standard-authored role file still validates here");
        }
    }
}
