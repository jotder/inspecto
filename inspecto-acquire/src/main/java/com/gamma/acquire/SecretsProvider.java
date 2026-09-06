package com.gamma.acquire;

/**
 * An edition-provided secret scheme for {@link SecretResolver} (SEC-07, 2026-09-06). The core resolves
 * {@code ${ENV:…}} and {@code ${SYS:…}} in every edition; the {@code FILE} and {@code KEYSTORE} schemes are
 * Standard + Enterprise and arrive through this SPI from the {@code inspecto-security} module (ServiceLoader,
 * {@code META-INF/services/com.gamma.acquire.SecretsProvider}). A scheme no bundled provider supports is
 * REFUSED by the resolver with a message naming the edition — never silently unresolved.
 *
 * <p>Implementations must never log a resolved value and return {@code null} for an absent/unreadable secret.
 */
public interface SecretsProvider {

    /** Whether this provider serves the upper-cased scheme ({@code "FILE"}, {@code "KEYSTORE"}, …). */
    boolean supports(String scope);

    /** Resolve {@code ${scope:key}}; {@code null} when the source is absent or unreadable. */
    String resolve(String scope, String key);
}
