package com.gamma.control;

import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code MEASURE-PROBE-SPACE-ROOT-1} — <b>a measure Alert Rule in a named Space reads that Space's registry.</b>
 *
 * <p>Until 2026-09-25 {@code CollectorService} handed the measure probe the JVM-wide {@code -Dassist.write.root}
 * as its registry root. In multi-Space mode that property is normally unset, so the probe resolved no Dataset and
 * returned empty for every rule: a measure rule could never fire, however far its value was past the threshold.
 * Set to one Space's config, it would have made every Space read that one Space's Datasets.
 *
 * <p>Two Spaces carry the same Dataset and the same rule ({@code count > 5}); only {@code acme}'s data breaches it.
 * The property is cleared for the run, so the old wiring fails the positive assertion — that is the mutation check.
 */
class ControlApiMeasureRuleSpaceRootTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private String inheritedWriteRoot;

    @BeforeEach
    void isolate() {
        inheritedWriteRoot = System.getProperty("assist.write.root");
        System.clearProperty("assist.write.root");
        // Alert Rules are Professional+ (control.alert.dispatch); this module's tests run as Personal.
        com.gamma.etl.EditionFeatures.overrideForTest(java.util.Set.of(com.gamma.etl.EditionFeatures.ALERT_DISPATCH));
    }

    @AfterEach
    void restore() {
        com.gamma.etl.EditionFeatures.overrideForTest(null);
        if (inheritedWriteRoot == null) System.clearProperty("assist.write.root");
        else System.setProperty("assist.write.root", inheritedWriteRoot);
    }

    private HttpResponse<String> post(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                        .header("Content-Type", "application/json").POST(BodyPublishers.ofString("{}")).build(),
                BodyHandlers.ofString());
    }

    /** A Space on disk: the {@code cases} Dataset over {@code rows} Parquet rows, and one measure rule. */
    private static void space(Path spacesRoot, String id, int rows) throws Exception {
        Path config = Files.createDirectories(spacesRoot.resolve(id).resolve("config").resolve("registry"));
        Files.createDirectories(config.resolve("datasets"));
        Files.writeString(config.resolve("datasets").resolve("cases.toon"),
                "name: cases\nphysicalRef: cases/database\ndescription: open cases\n");
        Files.createDirectories(config.resolve("alert-rules"));
        Files.writeString(config.resolve("alert-rules").resolve("too_many_cases.toon"),
                "name: too_many_cases\ndataset: cases\nmeasure: count\ncomparator: gt\nthreshold: 5\nseverity: WARNING\n");
        Path data = Files.createDirectories(spacesRoot.resolve(id).resolve("data").resolve("cases").resolve("database"));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("COPY (SELECT range AS case_id FROM range(" + rows + ")) TO '"
                    + data.resolve("part-0.parquet").toString().replace("\\", "/") + "' (FORMAT PARQUET)");
        }
    }

    @Test
    void aMeasureRuleFiresInTheSpaceWhoseDataBreachesItAndNowhereElse(@TempDir Path root) throws Exception {
        space(root, "acme", 10);   // breaches count > 5
        space(root, "beta", 2);    // same rule, does not breach
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        try {
            int port = api.port();
            HttpResponse<String> acme = post(port, "/spaces/acme/alerts/evaluate");
            assertEquals(200, acme.statusCode(), acme.body());
            assertTrue(acme.body().contains("too_many_cases"),
                    "acme's rule must fire from acme's own registry (no -Dassist.write.root is set): " + acme.body());

            HttpResponse<String> beta = post(port, "/spaces/beta/alerts/evaluate");
            assertEquals(200, beta.statusCode(), beta.body());
            assertFalse(beta.body().contains("too_many_cases"),
                    "beta's data does not breach the rule, so beta must not fire it: " + beta.body());
        } finally {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    /**
     * R2-05 follow-up: the Dataset-name resolver is WIRED in the real service, not only unit-tested — the
     * fired alert's words name the Dataset by its registry description ({@code open cases}), from the
     * Space's own registry, while its {@code pipeline} stays the id.
     */
    @Test
    void aFiredMeasureRuleNamesTheDatasetByItsRegistryDescription(@TempDir Path root) throws Exception {
        space(root, "acme", 10);
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        try {
            HttpResponse<String> acme = post(api.port(), "/spaces/acme/alerts/evaluate");
            assertEquals(200, acme.statusCode(), acme.body());
            assertTrue(acme.body().contains(
                    "WARNING: Row count on open cases is 10, above the threshold of 5 (over current data)"),
                    acme.body());
            assertTrue(acme.body().contains("\"pipeline\":\"cases\""), "the id stays the scope: " + acme.body());
        } finally {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }
}
