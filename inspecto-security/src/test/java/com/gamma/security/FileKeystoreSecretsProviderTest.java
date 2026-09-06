package com.gamma.security;

import com.gamma.acquire.SecretResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SEC-07: with this edition module on the classpath, {@code ${FILE}} / {@code ${KEYSTORE}} resolve through the
 * core {@link SecretResolver} exactly as they did when the arms lived in the core — discovered by ServiceLoader,
 * not wired by hand. (The Personal refusal is pinned in inspecto-acquire's {@code SecretResolverTest}.)
 */
class FileKeystoreSecretsProviderTest {

    @Test
    void theProviderIsDiscoveredByServiceLoaderThroughTheCoreResolver(@TempDir Path dir) throws Exception {
        Path secret = dir.resolve("db.pass");
        Files.writeString(secret, "swordfish\n");                 // the `echo > file` idiom
        assertEquals("swordfish", SecretResolver.resolve("${FILE:" + secret + "}"));
        assertTrue(SecretResolver.isResolvable("${FILE:" + secret + "}"));

        Files.writeString(secret, "no-newline");
        assertEquals("no-newline", SecretResolver.resolve("${FILE:" + secret + "}"));
    }

    @Test
    void missingFileReferenceResolvesToNull(@TempDir Path dir) {
        assertNull(SecretResolver.resolve("${FILE:" + dir.resolve("absent") + "}"));
        assertFalse(SecretResolver.isResolvable("${FILE:" + dir.resolve("absent") + "}"));
    }

    @Test
    void resolvesKeystoreScopeWithReferencedStorePassword(@TempDir Path dir) throws Exception {
        Path ksFile = dir.resolve("secrets.jceks");
        char[] storePw = "store-pw".toCharArray();
        KeyStore ks = KeyStore.getInstance("JCEKS");
        ks.load(null, null);
        SecretKey secret = new SecretKeySpec("hunter2".getBytes(StandardCharsets.UTF_8), "RAW");
        ks.setKeyEntry("db-pass", secret, storePw, null);
        try (OutputStream out = Files.newOutputStream(ksFile)) {
            ks.store(out, storePw);
        }

        String pwProp = "test.ks.pw." + System.nanoTime();
        System.setProperty("secrets.keystore.path", ksFile.toString());
        System.setProperty("secrets.keystore.type", "JCEKS");
        System.setProperty("secrets.keystore.password", "${SYS:" + pwProp + "}");   // store pw via a reference
        System.setProperty(pwProp, "store-pw");
        try {
            assertEquals("hunter2", SecretResolver.resolve("${KEYSTORE:db-pass}"));
            assertTrue(SecretResolver.isResolvable("${KEYSTORE:db-pass}"));
            assertNull(SecretResolver.resolve("${KEYSTORE:no-such-alias}"), "absent alias ⇒ unresolved");
        } finally {
            System.clearProperty("secrets.keystore.path");
            System.clearProperty("secrets.keystore.type");
            System.clearProperty("secrets.keystore.password");
            System.clearProperty(pwProp);
        }
        assertNull(SecretResolver.resolve("${KEYSTORE:db-pass}"), "no keystore configured ⇒ unresolved");
    }
}
