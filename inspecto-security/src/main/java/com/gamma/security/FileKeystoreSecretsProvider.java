package com.gamma.security;

import com.gamma.acquire.SecretResolver;
import com.gamma.acquire.SecretsProvider;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

/**
 * The Standard + Enterprise {@link SecretsProvider} (SEC-07, gated 2026-09-06): the {@code ${FILE:/path}} and
 * {@code ${KEYSTORE:alias}} secret schemes. Lives in this edition module so that a Personal bundle — which does
 * not carry it — refuses those schemes with a clear message from {@link SecretResolver}, while {@code ${ENV}} /
 * {@code ${SYS}} stay in the core for every edition. Registered under {@code META-INF/services}.
 *
 * <ul>
 *   <li>{@code FILE} → the contents of a secret mounted as a file (Docker/Kubernetes secrets); one trailing
 *       newline is stripped; missing/unreadable ⇒ {@code null} (unresolved, never logged).</li>
 *   <li>{@code KEYSTORE} → a {@code SecretKeyEntry} read from the Java KeyStore named by the JVM properties
 *       {@code secrets.keystore.path} (required), {@code secrets.keystore.type} (default {@code JCEKS}) and
 *       {@code secrets.keystore.password} (itself a reference, resolved through {@link SecretResolver}).</li>
 * </ul>
 */
public final class FileKeystoreSecretsProvider implements SecretsProvider {

    @Override
    public boolean supports(String scope) {
        return "FILE".equals(scope) || "KEYSTORE".equals(scope);
    }

    @Override
    public String resolve(String scope, String key) {
        return switch (scope) {
            case "FILE"     -> readFile(key);
            case "KEYSTORE" -> readKeystore(key);
            default         -> null;
        };
    }

    /** Read a secret mounted as a file; a single trailing newline is stripped. {@code null} if absent/unreadable. */
    static String readFile(String path) {
        try {
            String s = Files.readString(Path.of(path), StandardCharsets.UTF_8);
            if (s.endsWith("\r\n")) return s.substring(0, s.length() - 2);
            if (s.endsWith("\n"))   return s.substring(0, s.length() - 1);
            return s;
        } catch (Exception e) {
            return null;                                         // missing/unreadable ⇒ unresolved (never logged)
        }
    }

    /** Read a {@code SecretKeyEntry} by alias from the keystore named by {@code secrets.keystore.*}. */
    static String readKeystore(String alias) {
        String path = System.getProperty("secrets.keystore.path");
        if (path == null || path.isBlank()) return null;
        String type = System.getProperty("secrets.keystore.type", "JCEKS");
        String pwRef = System.getProperty("secrets.keystore.password");
        char[] pw = null;
        try {
            String resolvedPw = SecretResolver.resolve(pwRef);   // the store password may itself be a reference
            if (resolvedPw != null) pw = resolvedPw.toCharArray();
            KeyStore ks = KeyStore.getInstance(type);
            try (var in = Files.newInputStream(Path.of(path))) {
                ks.load(in, pw);
            }
            var key = ks.getKey(alias, pw);
            if (!(key instanceof SecretKey secret)) return null; // absent alias or wrong entry type
            return new String(secret.getEncoded(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;                                         // any failure ⇒ unresolved (never logged)
        } finally {
            if (pw != null) java.util.Arrays.fill(pw, '\0');     // don't leave the store password in memory
        }
    }
}
