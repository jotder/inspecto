package com.gamma.acquire;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Connection-profile secrets are references, never values: resolution by scope + literal passthrough. */
class SecretResolverTest {

    @Test
    void recognisesReferences() {
        assertTrue(SecretResolver.isReference("${ENV:X}"));
        assertTrue(SecretResolver.isReference("${plain}"));
        assertFalse(SecretResolver.isReference("plain"));
        assertFalse(SecretResolver.isReference("${}"));
        assertFalse(SecretResolver.isReference(null));
    }

    @Test
    void resolvesSystemPropertyScope() {
        String key = "test.secret." + System.nanoTime();
        System.setProperty(key, "swordfish");
        try {
            assertEquals("swordfish", SecretResolver.resolve("${SYS:" + key + "}"));
            assertEquals("swordfish", SecretResolver.resolve("${" + key + "}"));   // bare ⇒ env then sys prop
            assertTrue(SecretResolver.isResolvable("${SYS:" + key + "}"));
        } finally {
            System.clearProperty(key);
        }
        assertNull(SecretResolver.resolve("${SYS:" + key + "}"), "cleared ⇒ unresolved");
        assertFalse(SecretResolver.isResolvable("${SYS:" + key + "}"));
    }

    @Test
    void missingEnvReferenceResolvesToNull() {
        assertNull(SecretResolver.resolve("${ENV:DEFINITELY_NOT_SET_" + System.nanoTime() + "}"));
    }

    @Test
    void literalIsPassedThrough() {
        assertEquals("literal-value", SecretResolver.resolve("literal-value"));
        assertTrue(SecretResolver.isResolvable("literal-value"), "a literal is trivially resolvable");
        assertNull(SecretResolver.resolve(null));
    }

    // ── SEC-07 (2026-09-06): FILE / KEYSTORE are edition-provided, not core ─────────────────────

    @Test
    void fileAndKeystoreSchemesAreRefusedNamingTheEditionWhenNoProviderIsBundled(@TempDir Path dir) throws Exception {
        Path secret = dir.resolve("db.pass");
        Files.writeString(secret, "swordfish\n");
        SecretResolver.useProviders(List.of());                  // a Personal bundle: no inspecto-security on the classpath
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> SecretResolver.resolve("${FILE:" + secret + "}"),
                    "a readable file must NOT resolve without the provider — that is the whole gate");
            assertTrue(e.getMessage().contains("FILE") && e.getMessage().contains("Standard and Enterprise")
                    && e.getMessage().contains("${ENV:NAME}"), e.getMessage());
            assertThrows(IllegalStateException.class, () -> SecretResolver.isResolvable("${KEYSTORE:db-pass}"),
                    "a connection test surfaces the refusal rather than a bland 'unresolved'");
            assertEquals("literal", SecretResolver.resolve("literal"), "ENV/SYS/literal are untouched by the gate");
        } finally {
            SecretResolver.useProviders(null);
        }
    }

    @Test
    void aBundledProviderServesTheSchemesItSupports(@TempDir Path dir) throws Exception {
        SecretsProvider stub = new SecretsProvider() {
            @Override public boolean supports(String scope) { return "FILE".equals(scope); }
            @Override public String resolve(String scope, String key) { return "from-provider:" + key; }
        };
        SecretResolver.useProviders(List.of(stub));
        try {
            assertEquals("from-provider:/x", SecretResolver.resolve("${FILE:/x}"));
            assertThrows(IllegalStateException.class, () -> SecretResolver.resolve("${KEYSTORE:a}"),
                    "a provider gates only the schemes it declares");
        } finally {
            SecretResolver.useProviders(null);
        }
    }
}
