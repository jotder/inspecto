package com.gamma.control;

import com.gamma.metrics.MetricRegistry;
import com.gamma.objects.ObjectAccess;
import com.gamma.service.SpaceId;
import com.gamma.service.SpaceManager;
import com.gamma.workflow.ObjectType;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LEGAL-HOLD-SCOPE-1 over real HTTP: {@code DELETE /spaces/{id}?purge=true} removes the Space's whole tree, held
 * evidence with it, so it answers 409 naming the hold while anything in the Space is held; deregistering without
 * {@code ?purge} stays allowed (the files are kept), and once the hold is released the purge goes through.
 */
class ControlApiLegalHoldSpacePurgeTest {

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void aSpaceHoldingHeldEvidenceIsNotPurgedUntilTheHoldIsReleased(@TempDir Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        try {
            int port = api.port();
            assertEquals(200, send(port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            assertEquals(200, send(port, "POST", "/spaces", "{\"id\":\"beta\"}").statusCode());
            ObjectAccess objects = spaces.space(SpaceId.of("beta")).orElseThrow().service().objects()
                    .orElseThrow(() -> new IllegalStateException("inspecto-ops is on the classpath: the Space must have objects"));
            String inc = objects.open(ObjectType.INCIDENT, "fraud", "d", "HIGH", null, Map.of());
            assertTrue(objects.saveAttributes(inc, Map.of("legalHold", "LIT-2026-11"), "op", "legal-hold"));

            HttpResponse<String> r = send(port, "DELETE", "/spaces/beta?purge=true", null);
            assertEquals(409, r.statusCode(), r.body());
            assertTrue(r.body().contains("under legal hold") && r.body().contains(inc), r.body());
            assertTrue(Files.isDirectory(root.resolve("beta")), "nothing was removed");

            assertTrue(objects.saveAttributes(inc, Map.of("legalHold", "false"), "op", "legal-hold"));
            r = send(port, "DELETE", "/spaces/beta?purge=true", null);
            assertEquals(200, r.statusCode(), r.body());
            assertFalse(Files.exists(root.resolve("beta")), "released: the purge removes the tree");
        } finally {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
