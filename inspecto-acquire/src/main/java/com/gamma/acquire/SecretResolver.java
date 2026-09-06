package com.gamma.acquire;

import java.util.List;
import java.util.ServiceLoader;

/**
 * Resolves credential <em>references</em> to their runtime values (Data Acquisition — connection profiles).
 *
 * <p>Per the connection-profile security decision, an {@code *_connection.toon} never stores a secret — it
 * stores a reference that this resolver expands at the moment a connector needs it:
 * <ul>
 *   <li>{@code ${ENV:NAME}} → the {@code NAME} environment variable</li>
 *   <li>{@code ${SYS:prop}} → the {@code prop} JVM system property</li>
 *   <li>{@code ${FILE:/path}} and {@code ${KEYSTORE:alias}} → <b>Standard + Enterprise only (SEC-07,
 *       2026-09-06)</b>: served by a {@link SecretsProvider} the {@code inspecto-security} edition module
 *       registers via ServiceLoader (mounted-file secrets; a {@code SecretKeyEntry} from the keystore named by
 *       {@code secrets.keystore.path/type/password}). A bundle without that module — Personal — REFUSES the
 *       scheme with an {@link IllegalStateException} naming the edition, never a silent {@code null}. A
 *       Vault/KMS scope is the Enterprise follow-on, gated on a client policy.</li>
 *   <li>{@code ${NAME}} → environment {@code NAME}, falling back to system property {@code NAME}</li>
 *   <li>anything else → returned unchanged (a literal — discouraged, but tolerated)</li>
 * </ul>
 *
 * <p><b>Never logs a resolved value.</b> {@link #resolve} returns {@code null} when a reference cannot be
 * resolved (the source is absent or unreadable); {@link #isResolvable} answers the same question for a
 * connection test without exposing the value.
 */
public final class SecretResolver {

    private SecretResolver() {}

    /** Whether {@code s} is a {@code ${…}} reference (vs. a literal value). */
    public static boolean isReference(String s) {
        return s != null && s.startsWith("${") && s.endsWith("}") && s.length() > 3;
    }

    /** Resolve a reference (or pass a literal through); {@code null} when an env/property reference is absent. */
    public static String resolve(String ref) {
        if (ref == null) return null;
        if (!isReference(ref)) return ref;                       // literal value
        String body = ref.substring(2, ref.length() - 1).trim();
        int colon = body.indexOf(':');
        String scope = colon < 0 ? "" : body.substring(0, colon).trim().toUpperCase();
        String key   = colon < 0 ? body : body.substring(colon + 1).trim();
        if (key.isEmpty()) return null;
        return switch (scope) {
            case "ENV"      -> System.getenv(key);
            case "SYS"      -> System.getProperty(key);
            case "FILE", "KEYSTORE" -> provided(scope, key);
            default         -> {                                 // bare ${NAME}: env first, then system property
                String env = System.getenv(body);
                yield env != null ? env : System.getProperty(body);
            }
        };
    }

    // ── edition-provided schemes (SEC-07) ────────────────────────────────────

    private static volatile List<SecretsProvider> providers;

    private static List<SecretsProvider> providers() {
        List<SecretsProvider> p = providers;
        if (p == null) {
            synchronized (SecretResolver.class) {
                p = providers;
                if (p == null) {
                    p = ServiceLoader.load(SecretsProvider.class).stream().map(ServiceLoader.Provider::get).toList();
                    providers = p;
                }
            }
        }
        return p;
    }

    /** Test seam: pin the provider list ({@code null} ⇒ rediscover via ServiceLoader on next use). */
    static void useProviders(List<SecretsProvider> fixed) { providers = fixed; }

    /** Resolve through the first bundled provider that serves {@code scope}; refuse, naming the edition, if none does. */
    private static String provided(String scope, String key) {
        for (SecretsProvider p : providers()) if (p.supports(scope)) return p.resolve(scope, key);
        throw new IllegalStateException(refusal(scope));
    }

    /** The Personal-edition refusal for a scheme no bundled provider serves — what an operator reads in a connection test. */
    static String refusal(String scope) {
        return "secret scheme ${" + scope + ":…} is not available in this edition: mounted-file and keystore secrets"
                + " are Standard and Enterprise features (inspecto-security module) — use ${ENV:NAME} or ${SYS:prop} here";
    }

    /** Whether {@code ref} resolves to a non-blank value in this environment — for a connection test;
     *  a literal is trivially resolvable, a {@code ${…}} ref is resolvable only if its source is present. */
    public static boolean isResolvable(String ref) {
        if (ref == null || ref.isBlank()) return false;
        String v = resolve(ref);
        return v != null && !v.isBlank();
    }
}
