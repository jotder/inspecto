package com.gamma.demoauth;

import com.gamma.control.ControlApi;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.CollectorService;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DEMO-AUTH-ROSTER-REACH-1, over real HTTP: Demo Users come from the Spaces the core actually booted, not from a
 * second reading of the Spaces root — a {@code <id>.ref} Space kept elsewhere signs its users in, and a name-valid
 * folder the core refused (here: its data root overlaps another Space) is neither read nor counted. In legacy
 * {@code -Dassist.write.root} mode {@code /bootstrap} says the roster belongs in the write root, without a path.
 */
class DemoRosterReachHttpTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private String bind, spacesRoot, writeRoot;

    @BeforeEach
    void save() {
        bind = System.getProperty("control.bind");
        spacesRoot = System.getProperty("spaces.root");
        writeRoot = System.getProperty("assist.write.root");
    }

    @AfterEach
    void restore() {
        set("control.bind", bind);
        set("spaces.root", spacesRoot);
        set("assist.write.root", writeRoot);
    }

    private static void set(String k, String v) {
        if (v == null) System.clearProperty(k); else System.setProperty(k, v);
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> exchange(int port, String userId) throws Exception {
        return send(port, "POST", "/auth/exchange", "{\"code\":\"demo:" + userId + "\",\"codeVerifier\":\"x\",\"redirectUri\":\"x\"}");
    }

    private static Path space(Path folder, String... rows) throws Exception {
        Path config = Files.createDirectories(folder.resolve("config"));
        if (rows.length > 0)
            Files.writeString(config.resolve(DemoUsers.FILE), "users[" + rows.length + "]{id,roles}:\n  " + String.join("\n  ", rows) + "\n");
        return folder;
    }

    @Test
    void aSpaceAttachedThroughARefSignsItsRosterIn(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("spaces"));
        Path far = space(tmp.resolve("elsewhere").resolve("far"), "far.analyst,admin");
        Files.writeString(root.resolve("far.ref"), far.toAbsolutePath().toString());
        System.setProperty("control.bind", "127.0.0.1");
        System.setProperty("spaces.root", root.toString());
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        try {
            assertTrue(spaces.space(com.gamma.service.SpaceId.of("far")).isPresent(), "the core boots the referenced Space");
            HttpResponse<String> boot = send(api.port(), "GET", "/bootstrap", null);
            assertEquals(200, boot.statusCode(), boot.body());
            assertTrue(boot.body().contains("\"id\":\"far.analyst\"") && boot.body().contains("\"spacesAttached\":1")
                    && boot.body().contains("\"rosterLocation\":\"space\""), boot.body());
            assertEquals(200, exchange(api.port(), "far.analyst").statusCode(), "the .ref Space's Demo User signs in");
        } finally {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    @Test
    void aFolderRefusedForOverlapIsNeitherReadNorCounted(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("spaces"));
        Path acme = space(root.resolve("acme"), "admin,admin");
        // A name-valid Space folder whose data root reaches into acme: the core refuses it (SPACE_OVERLAP), not by name.
        Path beta = space(root.resolve("beta"), "ghost,admin;super", "admin,operations");
        Files.writeString(beta.resolve("space.toon"), "data_root: " + acme.resolve("config").resolve("x").toString().replace('\\', '/') + "\n");
        System.setProperty("control.bind", "127.0.0.1");
        System.setProperty("spaces.root", root.toString());
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        try {
            assertTrue(spaces.skipped().stream().anyMatch(s -> "beta".equals(s.space()) && "SPACE_OVERLAP".equals(s.code())),
                    "the core's verdict this test compares against: " + spaces.skipped());
            HttpResponse<String> boot = send(api.port(), "GET", "/bootstrap", null);
            assertEquals(200, boot.statusCode(), "beta's differing 'admin' raises no conflict: " + boot.body());
            assertTrue(boot.body().contains("\"spacesAttached\":1") && !boot.body().contains("ghost"), boot.body());
            assertEquals(401, exchange(api.port(), "ghost").statusCode(), "the refused folder's roster signs no one in");
            assertEquals(200, exchange(api.port(), "admin").statusCode());
            assertEquals(java.util.Set.of("admin"), DemoTokenRelay.known().get("admin").roles(), "acme's row, not beta's");
        } finally {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    @Test
    void legacyWriteRootModeSaysWhereTheRosterBelongsWithoutAPath(@TempDir Path writeRoot) throws Exception {
        System.setProperty("control.bind", "127.0.0.1");
        System.clearProperty("spaces.root");
        System.setProperty("assist.write.root", writeRoot.toString());
        CollectorService svc = new CollectorService(List.of(), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        try {
            HttpResponse<String> boot = send(api.port(), "GET", "/bootstrap", null);
            assertEquals(200, boot.statusCode(), boot.body());
            assertTrue(boot.body().contains("\"demoUsers\":[]") && boot.body().contains("\"spacesAttached\":1")
                    && boot.body().contains("\"rosterLocation\":\"write-root\""), boot.body());
            assertFalse(boot.body().contains(writeRoot.getFileName().toString()), "no filesystem path leaks: " + boot.body());
            // the file sits in the write root itself, not under config/
            Files.writeString(writeRoot.resolve(DemoUsers.FILE), "users[1]{id,roles}:\n  admin,admin\n");
            assertEquals(200, exchange(api.port(), "admin").statusCode());
        } finally {
            api.close();
            svc.close();
            MetricRegistry.global().reset();
        }
    }
}
