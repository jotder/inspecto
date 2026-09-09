package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EDG-01 cell 7 — the Personal-side falsification the largest extraction never got.
 *
 * <p>Seven cells moved a feature out of the core behind {@code ServiceLoader}. Six had a test proving the
 * core refuses correctly without the module; cell 7 — {@code inspecto-ops}, and at <b>49 stubbed paths</b>
 * the biggest surface of the seven — had none. It was filed as one of {@code SPEC-NOPROOF-1}'s six Musts.
 * {@code NoGeoLinkShipsInThePersonalBuildTest} and {@code NoExchangeShipsInThePersonalBuildTest} are the
 * sibling proofs; this is the missing third.
 *
 * <p><b>The distinction it defends is 503-not-404</b>, and it is a product promise rather than a detail:
 * a 503 naming {@code inspecto-ops} tells an operator the feature exists and their bundle lacks it, so the
 * UI renders an explained panel; a 404 says the endpoint was never real. {@link AbsentObjectRoutes} exists
 * only to make that difference, and until now nothing checked that it does.
 *
 * <p>Three failures this catches, each from a plausible change:
 * <ol>
 *   <li><b>404</b> on any path — {@code AbsentObjectRoutes.SURFACE} lost an entry, or a route module
 *       renamed a path and only one side was updated;</li>
 *   <li><b>200</b> on any path — {@code inspecto-ops} has leaked onto the Personal classpath;</li>
 *   <li>{@code /bootstrap}'s {@code features.ops} turning true — the stubs started counting as routes.
 *       ⚠ That is a real regression shape, not a hypothetical: it is the bug cell 3b shipped and fixed,
 *       and it works only because {@link ApiContext#stub} keeps stubs invisible to
 *       {@link ApiContext#hasRoute}, which {@code BootstrapRoutes:82} derives the flag from.</li>
 * </ol>
 *
 * <p>⚠ <b>The path list below is deliberately its own copy, not read from {@code AbsentObjectRoutes}.</b>
 * Deriving it from the class under test would make the first assertion circular — it would prove only that
 * every path this class stubs is stubbed. An independent list is what turns "the stub list lost a path"
 * into a 404 here, which is the same reasoning {@code AbsentExchangeRoutes}' javadoc gives for its own
 * sibling test. The cost of an independent copy is that it can drift the *other* way, so the last test
 * pins the two counts together by reading the stub's source.
 *
 * <p>⛔ <b>Not asserted: the SURFACE array's ORDER.</b> Its javadoc calls the ordering load-bearing because
 * first-match means a catch-all would swallow its literal siblings — true of the real module, but for the
 * stubs every entry throws the identical 503 and {@link ApiContext#stub} dedupes by exact
 * {@code (method, pattern)} key, so which one matches cannot change a response. Asserting an order that
 * cannot fail would be theatre.
 *
 * <p><b>Mutation-proven both ways, 2026-09-09, and the second case is why the count test exists:</b>
 * <ul>
 *   <li>removing the {@code /rca/templates} stub failed <b>2 of 3</b> — the surface test on a real
 *       {@code NOT_FOUND} body, and the count test on 48-vs-49;</li>
 *   <li>removing the {@code /objects/analytics} stub failed <b>only 1 of 3</b>. The
 *       {@code /objects/([^/]+)} catch-all answers that path with the same 503, so the HTTP test
 *       <b>cannot see</b> the loss. 🔴 Every literal path shadowed by a catch-all is invisible to an
 *       over-the-wire check, and there are two such catch-alls here — so the source cross-check is not
 *       belt-and-braces, it is the only assertion that covers those rows.</li>
 * </ul>
 *
 * <p>⛔ Do not "fix" a failure here by deleting this test. A 200 means an optional module is in the Personal
 * bundle; a 404 means the stub and the module's surface drifted apart. Both are the defect, not the test.
 */
class NoOperationalObjectsShipInThePersonalBuildTest {

    private static final String ABSENT_OBJECT_ROUTES =
            "inspecto/src/main/java/com/gamma/control/AbsentObjectRoutes.java";

    /**
     * Every path {@code inspecto-ops} owns, with the regex captures filled in — an independent transcription
     * of the module's surface. Distinct id segments per row so a failure message names one row unambiguously.
     */
    private static final String[][] SURFACE = {
            {"GET", "/objects"},
            {"GET", "/objects/analytics"},
            {"POST", "/objects"},
            {"POST", "/objects/id1/ack"},
            {"POST", "/objects/id2/resolve"},
            {"POST", "/objects/id3/transition"},
            {"POST", "/objects/id4/assign"},
            {"POST", "/objects/id5/watch"},
            {"POST", "/objects/id6/unwatch"},
            {"GET", "/objects/id7/watchers"},
            {"POST", "/objects/id8/links"},
            {"GET", "/objects/id9/links"},
            {"DELETE", "/objects/id10/links"},
            {"POST", "/objects/id11/merge"},
            {"POST", "/objects/id12/split"},
            {"GET", "/objects/id13/graph"},
            {"POST", "/objects/id14/comments"},
            {"GET", "/objects/id15/comments"},
            {"POST", "/objects/id16/attachments"},
            {"GET", "/objects/id17/attachments"},
            {"POST", "/objects/id18/rca"},
            {"GET", "/objects/id19"},
            {"GET", "/rca/templates"},
            {"GET", "/workflows/id20"},
            {"GET", "/findings/id21"},
            {"GET", "/cases/rules"},
            {"POST", "/cases/rules"},
            {"DELETE", "/cases/rules/id22"},
            {"POST", "/cases/rules/id23/evaluate"},
            {"GET", "/notes/id24/id25"},
            {"GET", "/notes/id26/id27/comments"},
            {"GET", "/notes/id28/id29/attachments"},
            {"POST", "/notes/id30/id31/comments"},
            {"POST", "/notes/id32/id33/attachments"},
            {"GET", "/queues"},
            {"GET", "/queues/id34"},
            {"POST", "/queues"},
            {"GET", "/tags"},
            {"POST", "/tags"},
            {"POST", "/tags/id35/rename"},
            {"GET", "/tags/rules"},
            {"POST", "/tags/rules"},
            {"DELETE", "/tags/rules/id36"},
            {"POST", "/tags/rules/id37/apply"},
            {"GET", "/tags/id38/targets"},
            {"GET", "/tags/assignments/id39/id40"},
            {"POST", "/tags/assignments/id41/id42"},
            {"DELETE", "/tags/assignments/id43/id44/id45"},
            {"DELETE", "/tags/id46"},
    };

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
    void everyOperationalObjectPathAnswers503NotInstalledOnThePersonalBuild(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            for (String[] r : SURFACE) {
                HttpResponse<String> res = send(c.port, r[0], r[1], "{}");
                assertEquals(503, res.statusCode(),
                        r[0] + " " + r[1] + " must answer 503 (not 404 — a lost stub; not 200 — a leaked "
                                + "module): " + res.body());
                JsonNode err = V1Body.of(res.body()).get("error");
                assertNotNull(err, r[1] + " must carry the v1 error object: " + res.body());
                assertTrue(err.get("message").asText().contains("inspecto-ops"),
                        "the refusal must name the module that would fix it, so the operator knows the "
                                + "feature is absent rather than nonexistent: " + err);
            }
        }
    }

    @Test
    void bootstrapReportsOpsAbsentSoTheSpaHidesTheEntries(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode features = V1Body.of(send(c.port, "GET", "/bootstrap", null).body()).get("features");
            assertNotNull(features, "bootstrap must carry features");
            assertTrue(features.has("ops"), "present and false, never merely absent: " + features);
            assertFalse(features.get("ops").asBoolean(),
                    "features.ops is derived from hasRoute(POST /objects); a true here means the stubs "
                            + "started counting as real routes: " + features);
        }
    }

    /**
     * The independent list above cannot notice a path ADDED to the stub, so pin the two counts together by
     * reading the stub's own source — the idiom {@code MapNodeKeyContractTest} uses for the same reason.
     */
    @Test
    void theStubSurfaceAndThisTestStayInStep() throws IOException {
        String source = Files.readString(repoFile(ABSENT_OBJECT_ROUTES));
        int from = source.indexOf("SURFACE = {");
        int to = source.indexOf("};", from);
        assertTrue(from > 0 && to > from,
                "AbsentObjectRoutes.SURFACE moved or was renamed — re-anchor this scan before trusting it");

        Matcher rows = Pattern.compile("\\{\"(?:GET|POST|DELETE|PUT|PATCH)\",\\s*\"[^\"]+\"\\}")
                .matcher(source.substring(from, to));
        int stubbed = 0;
        while (rows.find()) stubbed++;

        assertEquals(stubbed, SURFACE.length,
                "AbsentObjectRoutes stubs " + stubbed + " path(s) and this test exercises " + SURFACE.length
                        + ". A path added to the stub without a case here is untested; one removed from the "
                        + "stub turns into a 404 on Personal. Update both.");
    }

    /** Walk up from the module's CWD to the repo root, so the path works under surefire and an IDE alike. */
    private static Path repoFile(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 4 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new AssertionError("cannot locate " + relative + " from " + Path.of("").toAbsolutePath());
    }
}
