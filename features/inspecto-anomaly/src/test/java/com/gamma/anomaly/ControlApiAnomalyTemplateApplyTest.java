package com.gamma.anomaly;

import com.gamma.access.Roles;
import com.gamma.control.ControlApi;
import com.gamma.service.SpaceManager;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live-verification finding: a Space Template whose Anomaly Model declares {@code peers} (mobile-money
 * {@code mm_wallet_activity}) was REFUSED at apply in a deployed server - "the column lineage of dataset 'wallets'
 * cannot be traced" - because the cohort-column check reads the staged Space's Pipelines under the CALLER's Safety
 * Policy, whose roots do not include the staged Space (every relative {@code dirs.*} reads "outside the allowed
 * roots", the Pipeline reads as unloadable, lineage fails closed). Surefire's {@code assist.safety.roots} includes
 * java.io.tmpdir, which hid it; cleared here so the policy is the deployed one.
 */
class ControlApiAnomalyTemplateApplyTest {

    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
        com.gamma.etl.EditionFeatures.overrideForTest(null);
    }

    private static void copy(Path src, Path dst) throws Exception {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : walk.sorted().toList()) {
                Path t = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else {
                    Files.createDirectories(t.getParent());
                    Files.copy(p, t);
                }
            }
        }
    }

    /** The shipped mobile-money template cut down to the Anomaly Model, its two Datasets, their Pipelines and seeds. */
    private static void slimTemplate(Path to) throws Exception {
        Path src = Path.of("..", "..", "spaces", "_templates", "mobile-money");
        Files.createDirectories(to);
        Files.copy(src.resolve("template.toon"), to.resolve("template.toon"));
        for (String feed : List.of("wallets", "wallet_txn")) {
            copy(src.resolve("config").resolve(feed), to.resolve("config").resolve(feed));
            copy(src.resolve("data").resolve(feed), to.resolve("data").resolve(feed));
            Path ds = Path.of("config", "registry", "datasets", feed + ".toon");
            Files.createDirectories(to.resolve(ds).getParent());
            Files.copy(src.resolve(ds), to.resolve(ds));
        }
        Path model = Path.of("config", "registry", "anomaly-models", "mm_wallet_activity.toon");
        Files.createDirectories(to.resolve(model).getParent());
        Files.copy(src.resolve(model), to.resolve(model));
    }

    @Test
    void aTemplateModelWithPeersAppliesUnderTheDeployedSafetyPolicy(@TempDir Path root) throws Exception {
        slimTemplate(root.resolve("_templates").resolve("mm"));
        Authenticators.forTest(ex -> Optional.of(new Subject("admin-1", Set.of(Roles.CAN_ADMINISTER,
                Roles.CAN_AUTHOR_WORKBENCH, Roles.CAN_AUTHOR_ALERT_RULES, Roles.CAN_ONBOARD_CONNECTIONS,
                Roles.CAN_MANAGE_INCIDENTS, "canWorkIncidents"))));
        String saved = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", "");
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        try {
            api.start();
            assertEquals(200, post(api.port(), "{\"id\":\"first\"}").statusCode());
            HttpResponse<String> r = post(api.port(), "{\"id\":\"mm1\",\"template\":\"mm\"}");
            assertEquals(200, r.statusCode(), r.body());
            assertTrue(Files.isRegularFile(root.resolve("mm1/config/registry/anomaly-models/mm_wallet_activity.toon")));
        } finally {
            api.close();
            spaces.close();
            if (saved == null) System.clearProperty("assist.safety.roots");
            else System.setProperty("assist.safety.roots", saved);
        }
    }

    /**
     * TELCO-FRAUD-STORY-GAPS-1 (live, 2026-10-23): the WHOLE shipped telco-fraud template was refused at create on any
     * edition with Anomaly Detection - "template dataset 'tf_subscriber_anomaly_latest' is refused ... reserved prefix
     * 'anomaly_scores_'" - because its dashboard-tile Dataset read the model's store under another id. The golden test
     * runs in a module without this validator, so only a live create found it.
     */
    @Test
    void theShippedTelcoFraudTemplateAppliesWithAnomalyDetectionPresent(@TempDir Path root) throws Exception {
        copy(Path.of("..", "..", "spaces", "_templates", "telco-fraud"), root.resolve("_templates").resolve("tf"));
        // the Standard+ features the pack's Alert Rules and Case Rule need (as TelcoFraudTemplateGoldenTest does)
        com.gamma.etl.EditionFeatures.overrideForTest(Set.of(com.gamma.etl.EditionFeatures.ALERT_DISPATCH,
                com.gamma.etl.EditionFeatures.INVESTIGATIONS));
        Authenticators.forTest(ex -> Optional.of(new Subject("admin-1", Set.of(Roles.CAN_ADMINISTER,
                Roles.CAN_AUTHOR_WORKBENCH, Roles.CAN_AUTHOR_ALERT_RULES, Roles.CAN_ONBOARD_CONNECTIONS,
                Roles.CAN_MANAGE_INCIDENTS, "canWorkIncidents"))));
        String saved = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", "");
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        try {
            api.start();
            assertEquals(200, post(api.port(), "{\"id\":\"first\"}").statusCode());
            HttpResponse<String> r = post(api.port(), "{\"id\":\"tf1\",\"template\":\"tf\"}");
            assertEquals(200, r.statusCode(), r.body());
            assertTrue(Files.isRegularFile(root.resolve("tf1/config/registry/anomaly-models/tf_subscriber_usage.toon")));
        } finally {
            api.close();
            spaces.close();
            if (saved == null) System.clearProperty("assist.safety.roots");
            else System.setProperty("assist.safety.roots", saved);
        }
    }

    private HttpResponse<String> post(int port, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/spaces"))
                .header("Content-Type", "application/json").header("Authorization", "Bearer x")
                .POST(BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }
}
