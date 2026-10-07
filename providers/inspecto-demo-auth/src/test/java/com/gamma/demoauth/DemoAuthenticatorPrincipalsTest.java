package com.gamma.demoauth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code principals} lists exactly the identities {@code authenticate} accepts: the bound Space's table plus the relay's union. */
class DemoAuthenticatorPrincipalsTest {

    private String bind, spacesRoot;

    @BeforeEach
    void save() {
        bind = System.getProperty("control.bind");
        spacesRoot = System.getProperty("spaces.root");
        System.setProperty("control.bind", "127.0.0.1");
    }

    @AfterEach
    void restore() {
        set("control.bind", bind);
        set("spaces.root", spacesRoot);
    }

    private static void set(String k, String v) {
        if (v == null) System.clearProperty(k); else System.setProperty(k, v);
    }

    @Test
    void aDemoUserDefinedInAnotherSpaceIsListedToo(@TempDir Path root) throws Exception {
        Path acme = Files.createDirectories(root.resolve("acme").resolve("config"));
        Path other = Files.createDirectories(root.resolve("other").resolve("config"));
        Files.writeString(acme.resolve("demo-users.toon"), "users[1]{id,roles}:\n  maker,admin\n");
        Files.writeString(other.resolve("demo-users.toon"), "users[1]{id,roles}:\n  checker,admin\n");
        System.setProperty("spaces.root", root.toString());
        Map<String, List<String>> who = new DemoAuthenticator().principals(acme).orElseThrow();
        assertEquals(Map.of("maker", List.of("admin"), "checker", List.of("admin")), who,
                "checker authenticates against acme via the relay, so it must be listed");
    }
}
