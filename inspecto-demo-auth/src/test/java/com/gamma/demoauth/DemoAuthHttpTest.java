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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code DEMO-AUTH-1} over real HTTP, with the module's own ServiceLoader registrations on the classpath: the picker,
 * the code exchange, a Subject per Demo User resolved through the Space's role table, a capability gate that
 * refuses one Demo User and admits another, and the loopback-only refusal.
 */
class DemoAuthHttpTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private String bind, spacesRoot;

    @BeforeEach
    void save() {
        bind = System.getProperty("control.bind");
        spacesRoot = System.getProperty("spaces.root");
        com.gamma.etl.EditionFeatures.overrideForTest(java.util.Set.of(com.gamma.etl.EditionFeatures.ALERT_DISPATCH));
    }

    @AfterEach
    void restore() {
        com.gamma.etl.EditionFeatures.overrideForTest(null);
        set("control.bind", bind);
        set("spaces.root", spacesRoot);
    }

    private static void set(String k, String v) {
        if (v == null) System.clearProperty(k); else System.setProperty(k, v);
    }

    private HttpResponse<String> send(int port, String method, String path, String bearer, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return client.send(b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body)).build(),
                BodyHandlers.ofString());
    }

    private String signIn(int port, String user) throws Exception {
        HttpResponse<String> r = send(port, "POST", "/auth/exchange", null,
                "{\"code\":\"demo:" + user + "\",\"codeVerifier\":\"unused\",\"redirectUri\":\"unused\"}");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.headers().allValues("Set-Cookie").stream().anyMatch(c -> c.startsWith("inspecto_rt=") && c.contains("HttpOnly")),
                "the refresh token rides in the httpOnly cookie");
        Matcher m = Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"").matcher(r.body());
        assertTrue(m.find(), r.body());
        return m.group(1);
    }

    @Test
    void demoUsersSignInWithTheirSpacesRolesAndAGateSeparatesThem(@TempDir Path root) throws Exception {
        Path config = Files.createDirectories(root.resolve("acme").resolve("config"));
        Files.writeString(config.resolve("demo-users.toon"), """
                users[2]{id,displayName,title,roles}:
                  ra.analyst,Demo RA Analyst,Revenue Assurance analyst,business
                  builder,Demo Builder,Rule author,developer
                """);
        System.setProperty("control.bind", "127.0.0.1");
        System.setProperty("spaces.root", root.toString());
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        try {
            int port = api.port();
            HttpResponse<String> boot = send(port, "GET", "/bootstrap", null, null);
            assertEquals(200, boot.statusCode(), boot.body());
            assertTrue(boot.body().contains("\"mock\":true") && boot.body().contains("\"ra.analyst\"") && boot.body().contains("Demo Builder"),
                    "the anonymous bootstrap lists the Demo Users for the picker: " + boot.body());
            assertFalse(boot.body().contains("\"roles\""), "the pre-sign-in picker never carries roles");

            assertEquals(401, send(port, "POST", "/auth/exchange", null,
                    "{\"code\":\"demo:nobody\",\"codeVerifier\":\"x\",\"redirectUri\":\"x\"}").statusCode(),
                    "an unknown Demo User is refused");

            String analyst = signIn(port, "ra.analyst");
            String builder = signIn(port, "builder");
            HttpResponse<String> probe = send(port, "GET", "/spaces/acme/alerts/rules", analyst, null);
            assertEquals(200, probe.statusCode(), "a signed-in Demo User reaches an authenticated read: " + probe.body());
            HttpResponse<String> who = send(port, "GET", "/spaces/acme/bootstrap", analyst, null);
            assertTrue(who.body().contains("\"actor\":\"ra.analyst\""), "the actor is the Demo User: " + who.body());

            String rule = "{\"name\":\"r1\",\"dataset\":\"d\",\"measure\":\"count\",\"comparator\":\"gt\",\"threshold\":1,\"severity\":\"WARNING\"}";
            assertEquals(403, send(port, "POST", "/spaces/acme/alerts/rules", analyst, rule).statusCode(),
                    "the business role lacks canAuthorAlertRules");
            int allowed = send(port, "POST", "/spaces/acme/alerts/rules", builder, rule).statusCode();
            assertNotEquals(403, allowed, "the developer role holds canAuthorAlertRules");
            assertNotEquals(401, allowed);

            assertEquals(401, send(port, "GET", "/spaces/acme/alerts/rules", "forged.token", null).statusCode(),
                    "a forged Bearer is unauthenticated");
        } finally {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    @Test
    void demoAuthRefusesToLoadUnlessBoundToLoopback() {
        System.clearProperty("control.bind");
        IllegalStateException e = assertThrows(IllegalStateException.class, DemoAuthenticator::new);
        assertTrue(e.getMessage().contains("control.bind"), e.getMessage());
        System.setProperty("control.bind", "0.0.0.0");
        assertThrows(IllegalStateException.class, DemoTokenRelay::new);
        System.setProperty("control.bind", "127.0.0.1");
        assertDoesNotThrow(DemoTokenRelay::new);
    }

    @Test
    void tokensAreKindBoundAndExpire() {
        long now = 1_000_000;
        String access = DemoTokens.mint('a', "u1", now);
        assertEquals("u1", DemoTokens.verify('a', access, now).orElseThrow());
        assertTrue(DemoTokens.verify('r', access, now).isEmpty(), "an access token is not a refresh token");
        assertTrue(DemoTokens.verify('a', access, now + DemoTokens.ACCESS_SECONDS).isEmpty(), "expired");
        assertTrue(DemoTokens.verify('a', access.substring(0, access.length() - 2) + "xx", now).isEmpty(), "tampered");
    }
}
