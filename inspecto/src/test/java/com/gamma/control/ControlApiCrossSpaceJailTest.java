package com.gamma.control;

import com.gamma.config.safety.DiscoveredRoots;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code CROSS-SPACE-JAIL-1} — <b>a Space's config may not name another Space's directory.</b>
 *
 * <p>Until 2026-09-24 {@link com.gamma.config.safety.SafetyPolicy#defaultPolicy()} returned the
 * <em>union</em> of every hosted Space base, so a job saved in Space {@code acme} with an absolute
 * {@code backup_dir} inside Space {@code beta}'s base passed the 422 gate: {@code beta} was an allowed
 * root for everyone. The allowed roots of a Space are now its own base plus the operator-declared
 * {@code -Dassist.safety.roots}; another Space's base is not among them.
 *
 * <p>⚠ The inherited surefire roots include {@code java.io.tmpdir}, which contains every {@code @TempDir},
 * so the probe replaces them with an operator root that holds neither Space — otherwise the cross-Space
 * path is allowed by the test sandbox and the probe passes for the wrong reason.
 */
class ControlApiCrossSpaceJailTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private String inheritedRoots;

    @BeforeEach
    void isolate() {
        inheritedRoots = System.getProperty("assist.safety.roots");
        DiscoveredRoots.clear();
    }

    @AfterEach
    void restore() {
        if (inheritedRoots == null) System.clearProperty("assist.safety.roots");
        else System.setProperty("assist.safety.roots", inheritedRoots);
        DiscoveredRoots.clear();
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        b.header("Content-Type", "application/json");
        return client.send(b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body)).build(),
                BodyHandlers.ofString());
    }

    private static String job(String name, Path backupDir) {
        return """
                {"name":"%s","type":"maintenance","task":"cleanup","retention_days":"30","backup_dir":"%s"}"""
                .formatted(name, backupDir.toAbsolutePath().normalize().toString().replace("\\", "\\\\"));
    }

    @Test
    void aJobInOneSpaceNamingAnotherSpacesDirectoryIsRefused(@TempDir Path root, @TempDir Path operator)
            throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        try {
            int port = api.port();
            assertEquals(200, send(port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            assertEquals(200, send(port, "POST", "/spaces", "{\"id\":\"beta\"}").statusCode());
            Path betaData = Files.createDirectories(root.resolve("beta").resolve("data"));

            // Operator roots that hold NEITHER Space: only the Space registry can grant a Space base.
            System.setProperty("assist.safety.roots", operator.toString());

            // Positive control: acme's own base is still an allowed root for acme.
            HttpResponse<String> own = send(port, "POST", "/spaces/acme/jobs",
                    job("own", root.resolve("acme").resolve("data").resolve("backups")));
            assertEquals(200, own.statusCode(), "a Space's own base must stay allowed: " + own.body());

            // An operator-declared root stays allowed for every Space.
            HttpResponse<String> declared = send(port, "POST", "/spaces/acme/jobs",
                    job("declared", operator.resolve("backups")));
            assertEquals(200, declared.statusCode(), "an operator-declared root must stay allowed: " + declared.body());

            // The probe: acme names beta's data directory by absolute path.
            HttpResponse<String> cross = send(port, "POST", "/spaces/acme/jobs", job("cross", betaData));
            assertEquals(422, cross.statusCode(),
                    "Space acme must not be able to address Space beta's directory: " + cross.body());
            assertTrue(cross.body().contains("backup_dir"), cross.body());

            // And symmetrically, beta still reaches its own directory.
            HttpResponse<String> betaOwn = send(port, "POST", "/spaces/beta/jobs", job("mine", betaData));
            assertEquals(200, betaOwn.statusCode(), "beta's own base: " + betaOwn.body());
        } finally {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }
}
