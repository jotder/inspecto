package com.gamma.acquire;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Connection profile parse from {@code *_connection.toon}, secret masking, and the test endpoint. */
class ConnectionProfileTest {

    @Test
    void loadsAllFieldsIncludingTunnelAndOptions(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("cdr_connection.toon");
        Files.writeString(p, """
            connection:
              id: CDR_SFTP_PROD
              connector: SFTP
              host: sftp.example.com
              port: 22
              base_path: /cdr/outbox
              username: cdruser
              password: "${ENV:CDR_PW}"
              options:
                auth_method: key
                region: eu-west-1
              tunnel:
                host: bastion.example.com
                port: 2222
                username: jump
                password: "${SYS:bastion.pw}"
            """);
        ConnectionProfile c = ConnectionProfile.load(p);
        assertEquals("CDR_SFTP_PROD", c.id());
        assertEquals("sftp", c.connector(), "scheme is lower-cased");
        assertEquals("sftp.example.com", c.host());
        assertEquals(22, c.port());
        assertEquals("/cdr/outbox", c.basePath());
        assertEquals("cdruser", c.username());
        assertEquals("${ENV:CDR_PW}", c.password(), "the raw reference is stored, never a value");
        assertEquals("key", c.options().get("auth_method"));
        assertNotNull(c.tunnel());
        assertEquals("bastion.example.com:2222", c.tunnel().endpoint());
        assertTrue(c.isRemote());
        assertEquals("bastion.example.com:2222", c.testEndpoint(), "the tunnel hop is what a test probes");
    }

    @Test
    void testEndpointIsTheTargetWhenNoTunnel() {
        ConnectionProfile c = ConnectionProfile.fromMap(Map.of(
                "id", "S3FEED", "connector", "sftp", "host", "feeds.example.com", "port", "2200"));
        assertEquals("feeds.example.com:2200", c.testEndpoint());
        assertNull(c.tunnel());
    }

    @Test
    void toMapMasksSecretsButShowsReferences() {
        ConnectionProfile ref = ConnectionProfile.fromMap(Map.of(
                "id", "A", "connector", "sftp", "host", "h", "port", "22", "password", "${ENV:PW}"));
        assertEquals("${ENV:PW}", ref.toMap().get("password"), "a ${…} ref is shown (it is not itself secret)");

        ConnectionProfile inline = ConnectionProfile.fromMap(Map.of(
                "id", "B", "connector", "sftp", "host", "h", "port", "22", "password", "hunter2"));
        assertEquals("***", inline.toMap().get("password"), "an inlined literal secret is masked");
        assertFalse(inline.toMap().toString().contains("hunter2"), "the value never appears in the API view");
    }

    /**
     * The regression guard whose absence let a key-name asymmetry through: {@code toMap()} emitted the API's
     * camelCase {@code basePath} while {@code fromMap} read only {@code base_path}, so a round-trip silently
     * dropped the path. Asserted on the whole record — a record's {@code equals} covers <em>every</em>
     * component, so any future key added to one side and misspelled on the other fails here.
     *
     * <p>The secrets are {@code ${…}} references on purpose: masking a <em>literal</em> is deliberate loss, so
     * a literal could never round-trip and asserting it would encode the wrong contract.
     */
    @Test
    void everyFieldSurvivesAMapRoundTrip() {
        ConnectionProfile p = ConnectionProfile.fromMap(Map.ofEntries(
                Map.entry("id", "RT"), Map.entry("connector", "sftp"), Map.entry("host", "h"),
                Map.entry("port", "22"), Map.entry("database", "db"), Map.entry("base_path", "/cdr/outbox"),
                Map.entry("username", "u"), Map.entry("password", "${ENV:PW}"),
                Map.entry("options", Map.of("auth_method", "key", "api_token", "${ENV:TOK}")),
                Map.entry("tunnel", Map.of("host", "bastion", "port", "2222", "username", "jump",
                        "password", "${SYS:bastion.pw}"))));

        assertEquals(p, ConnectionProfile.fromMap(p.toMap()), "toMap → fromMap loses nothing");
    }

    @Test
    void fromMapAcceptsBothBasePathSpellings() {
        assertEquals("/in", ConnectionProfile.fromMap(Map.of(
                "id", "S", "connector", "local", "base_path", "/in")).basePath(),
                "the on-disk spelling every *_connection.toon already uses");
        assertEquals("/in", ConnectionProfile.fromMap(Map.of(
                "id", "S", "connector", "local", "basePath", "/in")).basePath(),
                "the API spelling the SPA sends");
        assertEquals("/in", ConnectionProfile.fromMap(Map.of("id", "S", "connector", "local",
                "base_path", "/in", "basePath", "/other")).basePath(), "on-disk spelling wins when both appear");
    }

    @Test
    void localProfileIsNotRemote() {
        ConnectionProfile c = ConnectionProfile.fromMap(Map.of("id", "L", "connector", "local"));
        assertFalse(c.isRemote());
    }

    @Test
    void idAndConnectorAreRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> ConnectionProfile.fromMap(Map.of("connector", "sftp")));
        assertThrows(IllegalArgumentException.class,
                () -> ConnectionProfile.fromMap(Map.of("id", "X")));
    }
}
