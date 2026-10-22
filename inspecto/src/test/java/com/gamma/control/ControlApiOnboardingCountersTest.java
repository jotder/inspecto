package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The onboarding counters over real HTTP ({@code LA-ONBOARD-1} OB-13, A-10): {@code GET /onboarding/metrics}
 * ({@code canAdminister}; anyone with no sign-in) and {@code POST /onboarding/events} (open, closed vocabulary,
 * rate-limited). The file lives in the server-wide document home ({@code -Dsystem.config.dir} here) and holds only
 * timestamps and words of the closed vocabulary. Space create, the Add data commit and the first graph are counted where
 * they happen: {@code ControlApiSpaceSamplesTest} and {@code ControlApiOnboardLinkAnalysisTest} (geo-link).
 */
class ControlApiOnboardingCountersTest {

    private static final String ADMIN = "Bearer admin", ANALYST = "Bearer analyst";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void cleanUp() {
        Authenticators.forTest(null);
        System.clearProperty("system.config.dir");
    }

    private Ctx open(Path cfg, Path writeRoot, Path home) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        System.setProperty("system.config.dir", home.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        if (auth != null) b.header("Authorization", auth);
        b = body == null ? b.GET() : b.method(method, BodyPublishers.ofString(body)).header("Content-Type", "application/json");
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    private static void subjects() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case ADMIN -> Optional.of(new Subject("admin-1", Set.of("canAdminister")));
            case ANALYST -> Optional.of(new Subject("analyst-1", Set.of("canUploadData", "canAuthorWorkbench")));
            default -> Optional.empty();
        });
    }

    @Test
    void withoutSignInAnyoneReadsTheMetricsAndTheBrowserReportsItsSteps(@TempDir Path cfg, @TempDir Path root, @TempDir Path home) throws Exception {
        try (Ctx c = open(cfg, root, home)) {
            JsonNode m = data(send(c, "GET", "/onboarding/metrics", null, null), 200);
            assertTrue(m.get("recording").asBoolean(), m.toString());
            assertEquals(1, m.get("launches").asInt(), "the boot is counted as a launch: " + m);
            assertFalse(m.get("firstLaunch").isNull());
            assertTrue(m.get("guideCompleted").isNull());

            data(send(c, "POST", "/onboarding/events", "{\"event\":\"guide_completed\"}", null), 200);
            data(send(c, "POST", "/onboarding/events", "{\"event\":\"guide_completed\"}", null), 200);
            data(send(c, "POST", "/onboarding/events", "{\"event\":\"step_abandoned\",\"step\":\"add_data:columns\"}", null), 200);
            data(send(c, "POST", "/onboarding/events", "{\"event\":\"step_abandoned\",\"step\":\"add_data:columns\"}", null), 200);
            data(send(c, "POST", "/onboarding/events", "{\"event\":\"step_abandoned\",\"step\":\"space\"}", null), 200);

            m = data(send(c, "GET", "/onboarding/metrics", null, null), 200);
            assertTrue(m.get("guideCompletedSeconds").isNumber() && m.get("guideCompletedSeconds").asLong() >= 0, m.toString());
            assertEquals(2, m.at("/abandoned/add_data:columns").asInt(), m.toString());
            assertEquals(1, m.at("/abandoned/space").asInt(), m.toString());

            // the file sits in the server-wide home, append-only, one line per event; guide_completed is written once
            List<String> lines = Files.readAllLines(home.resolve(OnboardingRoutes.FILE));
            assertEquals(5, lines.size(), lines.toString());
            assertEquals(1, lines.stream().filter(l -> l.endsWith("\tguide_completed")).count(), lines.toString());
            assertTrue(lines.get(0).endsWith("\tlaunch"), lines.toString());
        }
    }

    @Test
    void onlyTheClosedVocabularyIsKeptAndNothingTypedReachesTheFile(@TempDir Path cfg, @TempDir Path root, @TempDir Path home) throws Exception {
        try (Ctx c = open(cfg, root, home)) {
            String secret = "ACC-0042-alice@example.com";
            assertEquals(422, send(c, "POST", "/onboarding/events", "{\"event\":\"" + secret + "\"}", null).statusCode());
            // recorded server-side only: a browser cannot claim a Dataset or a graph it never made
            assertEquals(422, send(c, "POST", "/onboarding/events", "{\"event\":\"first_graph\"}", null).statusCode());
            assertEquals(422, send(c, "POST", "/onboarding/events", "{\"event\":\"launch\"}", null).statusCode());
            assertEquals(422, send(c, "POST", "/onboarding/events", "{\"event\":\"step_abandoned\"}", null).statusCode());
            assertEquals(422, send(c, "POST", "/onboarding/events",
                    "{\"event\":\"step_abandoned\",\"step\":\"" + secret + "\"}", null).statusCode());
            // extra keys are ignored, never stored
            data(send(c, "POST", "/onboarding/events", "{\"event\":\"step_abandoned\",\"step\":\"data\",\"value\":\"" + secret + "\"}", null), 200);

            String file = Files.readString(home.resolve(OnboardingRoutes.FILE));
            assertFalse(file.contains(secret), file);
            assertFalse(file.contains("first_graph"), file);
            assertEquals(2, file.lines().count(), "launch + the one valid report: " + file);
            for (String line : file.lines().toList())
                assertTrue(line.matches("\\d{4}-\\d\\d-\\d\\dT[0-9:.]+Z\t(launch|step_abandoned\tdata)"), line);
        }
    }

    @Test
    void withSignInTheMetricsNeedCanAdministerAndAnySignedInPersonReports(@TempDir Path cfg, @TempDir Path root, @TempDir Path home) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, home)) {
            assertEquals(401, send(c, "GET", "/onboarding/metrics", null, null).statusCode());
            assertEquals(403, send(c, "GET", "/onboarding/metrics", null, ANALYST).statusCode());
            assertTrue(data(send(c, "GET", "/onboarding/metrics", null, ADMIN), 200).get("recording").asBoolean());

            assertEquals(401, send(c, "POST", "/onboarding/events", "{\"event\":\"guide_completed\"}", null).statusCode());
            data(send(c, "POST", "/onboarding/events", "{\"event\":\"guide_completed\"}", ANALYST), 200);
            assertFalse(data(send(c, "GET", "/onboarding/metrics", null, ADMIN), 200).get("guideCompleted").isNull());
        }
    }

    @Test
    void theReportsAreRateLimited(@TempDir Path cfg, @TempDir Path root, @TempDir Path home) throws Exception {
        try (Ctx c = open(cfg, root, home)) {
            int limited = 0;
            for (int i = 0; i < 40; i++)
                if (send(c, "POST", "/onboarding/events", "{\"event\":\"step_abandoned\",\"step\":\"graph\"}", null).statusCode() == 429)
                    limited++;
            assertTrue(limited > 0, "40 reports in a burst meet the standard bucket");
            assertTrue(Files.readAllLines(home.resolve(OnboardingRoutes.FILE)).size() < 40);
        }
    }
}
