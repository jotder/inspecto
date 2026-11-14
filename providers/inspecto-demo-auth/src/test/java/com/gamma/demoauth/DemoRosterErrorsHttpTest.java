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

/**
 * Demo failure modes the sign-in page must explain, over real HTTP: a roster conflict, no Space attached, and
 * Spaces attached with no roster (DEMO-AUTH-NO-ROSTER-1).
 */
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

    private HttpResponse<String> exchange(int port, String code) throws Exception {
        return send(port, "POST", "/auth/exchange", "{\"code\":\"" + code + "\",\"codeVerifier\":\"x\",\"redirectUri\":\"x\"}");
    }

    /**
     * DEMO-AUTH-NO-ROSTER-1, as met on the 2026-10-27 bundle: {@code spaces/} holding only {@code _templates} (whose
     * templates DO carry rosters). The reserved folder is not a Space, so the sign-in page must hear "no Space
     * attached", and no template's Demo User, nor the SSO panel's mock code, signs in.
     */
    @Test
    void aSpacesRootHoldingOnlyTemplatesIsNoSpaceAttachedAndSignsNoOneIn(@TempDir Path root) throws Exception {
        Path tpl = Files.createDirectories(root.resolve("_templates").resolve("la-showcase").resolve("config"));
        Files.writeString(tpl.resolve(DemoUsers.FILE), "users[1]{id,roles}:\n  admin,admin;super\n");
        System.setProperty("control.bind", "127.0.0.1");
        System.setProperty("spaces.root", root.toString());
        String warning = DemoTokenRelay.noDemoUsersWarning();
        assertNotNull(warning, "the boot log must say sign-in has no users");
        assertTrue(warning.contains("No Space is attached") && warning.contains("config/demo-users.toon")
                && warning.contains("restart"), warning);
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        try {
            HttpResponse<String> boot = send(api.port(), "GET", "/bootstrap", null);
            assertEquals(200, boot.statusCode(), boot.body());
            assertTrue(boot.body().contains("\"mock\":true") && boot.body().contains("\"demoUsers\":[]")
                    && boot.body().contains("\"spacesAttached\":0"), boot.body());
            assertEquals(401, exchange(api.port(), "demo:admin").statusCode(), "a template's roster grants nothing");
            assertEquals(401, exchange(api.port(), "mock-code").statusCode(), "fail closed: no anonymous session");
        } finally {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    /**
     * DEMO-AUTH-NO-ROSTER-1, the general case: a Space is attached but none carries {@code config/demo-users.toon}. The
     * bootstrap reports Spaces and no Demo User (the page's cue), nobody signs in, the boot warning names the file;
     * and adding the roster while the server runs makes the warning go and the user sign in (no restart).
     */
    @Test
    void aSpaceWithoutARosterSignsNoOneInUntilOneIsAddedWithoutARestart(@TempDir Path root) throws Exception {
        Path config = Files.createDirectories(root.resolve("acme").resolve("config"));
        System.setProperty("control.bind", "127.0.0.1");
        System.setProperty("spaces.root", root.toString());
        String warning = DemoTokenRelay.noDemoUsersWarning();
        assertNotNull(warning, "the boot log must say sign-in has no users");
        assertTrue(warning.contains("No attached Space carries config/demo-users.toon") && warning.contains("no restart"), warning);
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        try {
            HttpResponse<String> boot = send(api.port(), "GET", "/bootstrap", null);
            assertEquals(200, boot.statusCode(), boot.body());
            assertTrue(boot.body().contains("\"mock\":true") && boot.body().contains("\"demoUsers\":[]")
                    && boot.body().contains("\"spacesAttached\":1"), boot.body());
            assertEquals(401, exchange(api.port(), "demo:admin").statusCode());
            assertEquals(401, exchange(api.port(), "mock-code").statusCode(), "fail closed: no anonymous session");

            Files.writeString(config.resolve(DemoUsers.FILE), "users[1]{id,roles}:\n  admin,admin\n");
            assertNull(DemoTokenRelay.noDemoUsersWarning(), "a build WITH a roster logs no warning");
            assertTrue(send(api.port(), "GET", "/bootstrap", null).body().contains("\"id\":\"admin\""));
            assertEquals(200, exchange(api.port(), "demo:admin").statusCode());
        } finally {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    /** The boot log: ControlApi resolves the Authenticator at boot, so constructing it is what the operator's log shows. */
    @Test
    void theAuthenticatorWarnsAtBootWhenNobodyCanSignInAndNotWithARoster(@TempDir Path root) throws Exception {
        Path config = Files.createDirectories(root.resolve("acme").resolve("config"));
        System.setProperty("control.bind", "127.0.0.1");
        System.setProperty("spaces.root", root.toString());
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DemoAuthenticator.class);
        logger.addAppender(logs);
        try {
            new DemoAuthenticator();
            assertEquals(1, logs.list.size(), "one warning block");
            assertEquals(ch.qos.logback.classic.Level.WARN, logs.list.get(0).getLevel());
            assertTrue(logs.list.get(0).getFormattedMessage().contains("DEMO SIGN-IN HAS NO USERS"), logs.list.get(0).getFormattedMessage());
            logs.list.clear();
            Files.writeString(config.resolve(DemoUsers.FILE), "users[1]{id,roles}:\n  admin,admin\n");
            new DemoAuthenticator();
            assertTrue(logs.list.isEmpty(), "a build WITH a roster boots silently: " + logs.list);
            // a roster that cannot be read is a warning at boot, never a server that does not start
            Files.writeString(config.resolve(DemoUsers.FILE), "users: not-a-table\n");
            new DemoAuthenticator();
            assertEquals(1, logs.list.size(), "one warning for the unreadable roster: " + logs.list);
            assertTrue(logs.list.get(0).getFormattedMessage().contains("could not be read"), logs.list.get(0).getFormattedMessage());
        } finally {
            logger.detachAppender(logs);
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
