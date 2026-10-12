package com.gamma.demoauth;

import com.gamma.control.ControlApi;
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

/** Two demo failure modes the sign-in page must explain, over real HTTP: a roster conflict, and no Space attached. */
class DemoRosterErrorsHttpTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private String bind, spacesRoot;

    @BeforeEach
    void save() {
        bind = System.getProperty("control.bind");
        spacesRoot = System.getProperty("spaces.root");
    }

    @AfterEach
    void restore() {
        if (bind == null) System.clearProperty("control.bind"); else System.setProperty("control.bind", bind);
        if (spacesRoot == null) System.clearProperty("spaces.root"); else System.setProperty("spaces.root", spacesRoot);
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private static void space(Path root, String name, String roles) throws Exception {
        Path c = Files.createDirectories(root.resolve(name).resolve("config"));
        Files.writeString(c.resolve(DemoUsers.FILE), "users[1]{id,roles}:\n  demo.manager," + roles + "\n");
    }

    @Test
    void aConflictingRosterAnswersATypedNon500OnBootstrapAndSignIn(@TempDir Path root) throws Exception {
        space(root, "cricket-analytics", "admin");
        space(root, "telco-assurance", "operations");
        System.setProperty("control.bind", "127.0.0.1");
        System.setProperty("spaces.root", root.toString());
        // startup: logged, and the relay still loads (keeps serving)
        assertDoesNotThrow(DemoTokenRelay::warnIfRosterConflicts);
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        try {
            HttpResponse<String> boot = send(api.port(), "GET", "/bootstrap", null);
            assertEquals(409, boot.statusCode(), boot.body());
            assertTrue(boot.body().contains("DEMO_ROSTER_CONFLICT"), boot.body());
            assertTrue(boot.body().contains("demo.manager") && boot.body().contains("cricket-analytics")
                    && boot.body().contains("telco-assurance"), boot.body());
            assertFalse(boot.body().contains(root.toString().replace("\\", "\\\\")), "no filesystem path leaks: " + boot.body());
            HttpResponse<String> ex = send(api.port(), "POST", "/auth/exchange",
                    "{\"code\":\"demo:demo.manager\",\"codeVerifier\":\"x\",\"redirectUri\":\"x\"}");
            assertEquals(409, ex.statusCode(), ex.body());
            assertTrue(ex.body().contains("DEMO_ROSTER_CONFLICT"), ex.body());
        } finally {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    @Test
    void noSpaceAttachedIsStatedExplicitlyOnBootstrap(@TempDir Path root) throws Exception {
        System.setProperty("control.bind", "127.0.0.1");
        System.setProperty("spaces.root", root.toString());
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        try {
            HttpResponse<String> boot = send(api.port(), "GET", "/bootstrap", null);
            assertEquals(200, boot.statusCode(), boot.body());
            assertTrue(boot.body().contains("\"mock\":true") && boot.body().contains("\"spacesAttached\":0"), boot.body());
        } finally {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }
}
