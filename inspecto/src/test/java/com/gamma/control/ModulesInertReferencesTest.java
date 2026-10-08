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
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code MODULE-REORG-1 P4e} (P4-1) - {@code GET /modules} lists what the current Space holds that no installed module
 * accounts for: {@code modules.toon} ids, registry files of a kind an absent module owns, and Jobs of a type an absent
 * module declares. Reads only: the files are byte-identical afterwards. The processor's test class path installs neither
 * scoring nor reconciliation, so both are known-but-absent.
 */
class ModulesInertReferencesTest {

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void theSpacesInertReferencesAreListedNamingTheMissingModule(@TempDir Path root) throws Exception {
        try (SpaceManager spaces = SpaceManager.discover(root)) {
            ControlApi api = new ControlApi(spaces, 0);
            api.start();
            try {
                assertEquals(200, send(api.port(), "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
                assertEquals(200, send(api.port(), "POST", "/spaces", "{\"id\":\"beta\"}").statusCode());
            } finally {
                api.close();
            }
        }
        Path cfg = root.resolve("acme/config");
        Files.createDirectories(cfg.resolve("registry/risk-scores"));
        Files.writeString(cfg.resolve("registry/risk-scores/r1.toon"), "name: r1\n");
        Files.writeString(cfg.resolve("registry/risk-scores/r2.toon"), "name: r2\n");
        Files.writeString(cfg.resolve("modules.toon"), "disabled[1]: zz-gone\n");
        Files.createDirectories(cfg.resolve("jobs"));
        Files.writeString(cfg.resolve("jobs/recon_job.toon"),
                "job:\n  name: nightly-recon\n  type: recon.run\n  enabled: true\n  cron: \"0 3 * * *\"\n");
        byte[] modulesToon = Files.readAllBytes(cfg.resolve("modules.toon"));

        try (SpaceManager spaces = SpaceManager.discover(root)) {
            ControlApi api = new ControlApi(spaces, 0);
            api.start();
            try {
                JsonNode inert = V1Body.of(send(api.port(), "GET", "/spaces/acme/modules", null).body()).get("inert");
                assertEquals("zz-gone", inert.get("modulesToon").get(0).asText(), inert.toString());
                assertEquals(1, inert.get("configKinds").size(), "only a kind the Space holds files of: " + inert);
                JsonNode kind = inert.get("configKinds").get(0);
                assertEquals("scoring", kind.get("module").asText());
                assertEquals("risk-score", kind.get("kind").asText());
                assertEquals(2, kind.get("files").asInt());
                assertEquals(1, inert.get("jobs").size(), inert.toString());
                assertEquals("reconciliation", inert.get("jobs").get(0).get("module").asText());
                assertEquals("nightly-recon", inert.get("jobs").get(0).get("name").asText());

                JsonNode other = V1Body.of(send(api.port(), "GET", "/spaces/beta/modules", null).body()).get("inert");
                assertTrue(other.get("modulesToon").isEmpty() && other.get("configKinds").isEmpty() && other.get("jobs").isEmpty(),
                        "another Space holds nothing inert: " + other);
            } finally {
                api.close();
            }
        }
        assertEquals(new String(modulesToon), Files.readString(cfg.resolve("modules.toon")), "the listing never rewrites a file");
        MetricRegistry.global().reset();
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
