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
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DEMO-AUTH-STALE-ROSTER-1, over real HTTP: Demo Users come only from folders the core boots as Spaces. A hidden,
 * reserved or backup/snapshot/scratch-named folder under {@code -Dspaces.root} carries {@code config/} just like a
 * Space (it is often a byte-for-byte copy), so its stale {@code demo-users.toon} must neither sign anyone in nor raise
 * a roster conflict, and it must not count as an attached Space.
 */
class DemoRosterSpaceFoldersHttpTest {

    /** Folders that hold {@code config/} but that {@code SpaceManager.discover} refuses, one per name rule. */
    private static final List<String> REFUSED = List.of(".acme", "_acme", "backup-acme", "acme-backup-20261026",
            "backups_acme", "acme.bak", "acme-snapshot", "scratch-acme");
    /** Names the core boots: "old", "copy" and "temp" are Space words, and "acmebak" is one token, not "bak". */
    private static final List<String> ACCEPTED = List.of("acme", "old-acme", "acme-copy", "temp", "acmebak");

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

    private HttpResponse<String> exchange(int port, String userId) throws Exception {
        return send(port, "POST", "/auth/exchange", "{\"code\":\"demo:" + userId + "\",\"codeVerifier\":\"x\",\"redirectUri\":\"x\"}");
    }

    private static Path config(Path root, String folder) throws Exception {
        return Files.createDirectories(root.resolve(folder).resolve("config"));
    }

    private static void roster(Path root, String folder, String row) throws Exception {
        Files.writeString(config(root, folder).resolve(DemoUsers.FILE), "users[1]{id,roles}:\n  " + row + "\n");
    }

    private static Set<String> booted(SpaceManager spaces) {
        Set<String> out = new TreeSet<>();
        spaces.all().forEach(c -> out.add(c.id().value()));
        return out;
    }

    @Test
    void aRosterInAFolderTheCoreRefusesAsASpaceSignsNoOneIn(@TempDir Path root) throws Exception {
        config(root, "acme");   // the one real Space, with no roster
        for (int i = 0; i < REFUSED.size(); i++) roster(root, REFUSED.get(i), "ghost-" + i + ",admin;super");
        System.setProperty("control.bind", "127.0.0.1");
        System.setProperty("spaces.root", root.toString());
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        try {
            assertEquals(Set.of("acme"), booted(spaces), "the core boots only the real Space");
            HttpResponse<String> boot = send(api.port(), "GET", "/bootstrap", null);
            assertEquals(200, boot.statusCode(), boot.body());
            assertTrue(boot.body().contains("\"demoUsers\":[]") && boot.body().contains("\"spacesAttached\":1"), boot.body());
            for (int i = 0; i < REFUSED.size(); i++)
                assertEquals(401, exchange(api.port(), "ghost-" + i).statusCode(),
                        "the roster in '" + REFUSED.get(i) + "' must sign no one in");
        } finally {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    @Test
    void aStaleBackupRosterRaisesNoConflictAndTheRealSpacesUserSignsIn(@TempDir Path root) throws Exception {
        roster(root, "acme", "admin,admin");
        roster(root, "backup-acme", "admin,operations");   // the same id, defined differently in a stale copy
        System.setProperty("control.bind", "127.0.0.1");
        System.setProperty("spaces.root", root.toString());
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        try {
            HttpResponse<String> boot = send(api.port(), "GET", "/bootstrap", null);
            assertEquals(200, boot.statusCode(), boot.body());
            assertTrue(boot.body().contains("\"id\":\"admin\"") && boot.body().contains("\"spacesAttached\":1"), boot.body());
            HttpResponse<String> ex = exchange(api.port(), "admin");
            assertEquals(200, ex.statusCode(), ex.body());
            assertEquals(Set.of("admin"), DemoTokenRelay.known().get("admin").roles(), "the real Space's row, not the copy's");
        } finally {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    /** Each folder's roster lists one user named after the folder, so the picker shows exactly which folders were read. */
    @Test
    void rostersAndSpacesAttachedFollowTheCoresVerdictForEveryNamePattern(@TempDir Path root) throws Exception {
        for (String f : ACCEPTED) roster(root, f, f + ",admin");
        for (String f : REFUSED) roster(root, f, f + ",admin");
        System.setProperty("control.bind", "127.0.0.1");
        System.setProperty("spaces.root", root.toString());
        try (SpaceManager spaces = SpaceManager.discover(root)) {
            Set<String> core = booted(spaces);
            assertEquals(new TreeSet<>(ACCEPTED), core, "the core's verdict this test compares against");
            assertEquals(core, new TreeSet<>(DemoTokenRelay.known().keySet()), "rosters read = Spaces booted");
            assertEquals(core.size(), DemoUsers.spacesAttached(), "spacesAttached = Spaces booted");
        } finally {
            MetricRegistry.global().reset();
        }
    }
}
