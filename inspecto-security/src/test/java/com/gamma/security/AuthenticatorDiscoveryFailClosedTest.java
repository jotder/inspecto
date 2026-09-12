package com.gamma.security;

import com.gamma.control.Authenticator;
import com.gamma.service.OptionalSpi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What SPI discovery does when this module is on the classpath but <b>misconfigured</b> — the case a
 * Standard deployment hits when someone forgets {@code -Dauth.oidc.jwksUri} or {@code .issuer}.
 *
 * <p>🔴 <b>This is a security boundary, not a config nicety.</b>
 * {@link com.gamma.control.ControlApi}'s constructor resolves the Authenticator eagerly and its comment
 * states the intent exactly: <em>"so a misconfigured Standard deployment (e.g. missing
 * -Dauth.oidc.jwksUri, which the security module's no-arg constructor rejects) fails to boot instead of
 * silently accepting traffic."</em> For the Authenticator specifically, <b>absent means unauthenticated</b>
 * — so "skip the provider that would not load" and "serve every request without auth" are the same
 * outcome. That is the one SPI where degrading to absence is the dangerous choice, not the safe one.
 *
 * <p>⚠ These tests pin the behaviour of the <b>discovery path</b>, not of {@link OidcAuthenticator}'s
 * validation (which {@code OidcAuthenticatorTest} covers offline with an in-memory JWKS). They exist
 * because that suite injects the {@code JWKSource} through the package-private constructor and therefore
 * never exercises the no-arg {@code ServiceLoader} constructor at all — the only one production uses.
 */
class AuthenticatorDiscoveryFailClosedTest {

    private static final String ISSUER = "auth.oidc.issuer";
    private static final String JWKS = "auth.oidc.jwksUri";

    private String savedIssuer;
    private String savedJwks;

    @BeforeEach
    void clearTheRequiredProperties() {
        savedIssuer = System.getProperty(ISSUER);
        savedJwks = System.getProperty(JWKS);
        System.clearProperty(ISSUER);
        System.clearProperty(JWKS);
    }

    @AfterEach
    void restore() {
        if (savedIssuer == null) System.clearProperty(ISSUER); else System.setProperty(ISSUER, savedIssuer);
        if (savedJwks == null) System.clearProperty(JWKS); else System.setProperty(JWKS, savedJwks);
    }

    /** The premise everything else rests on: the constructor really does refuse a missing property. */
    @Test
    void theNoArgConstructorRefusesAMissingRequiredProperty() {
        IllegalStateException e = assertThrows(IllegalStateException.class, OidcAuthenticator::new);
        assertTrue(e.getMessage().contains("auth.oidc"),
                "the message must name the property an operator has to set, got: " + e.getMessage());
    }

    /**
     * ⚠ And {@code ServiceLoader} does NOT surface that exception as itself — it wraps a provider
     * constructor's throw in a {@link ServiceConfigurationError}. That wrapping is the whole mechanism
     * behind the finding below: {@code ServiceConfigurationError} is an {@link Error}, and it is exactly
     * the type the discovery helper catches.
     */
    @Test
    void serviceLoaderWrapsThatRefusalInAnError() {
        var it = ServiceLoader.load(Authenticator.class).iterator();
        assertTrue(it.hasNext(), "this module's META-INF/services registration must be on the test classpath");
        ServiceConfigurationError wrapped = assertThrows(ServiceConfigurationError.class, it::next);
        assertInstanceOf(IllegalStateException.class, wrapped.getCause(),
                "the constructor's IllegalStateException should be the cause");
    }

    /**
     * 🔴 <b>The defect this class was written to prove, now FIXED (2026-09-12).</b>
     *
     * <p>{@code OptionalSpi.all} catches {@code ServiceConfigurationError} and skips the provider with a
     * warning, so a misconfigured Standard build used to resolve <b>no Authenticator at all</b> —
     * indistinguishable, at {@code ControlApi.dispatch}, from an auth-free Personal build. Its warning
     * even read <em>"the product runs without it and its routes answer 503"</em>, which is true of a
     * feature module and false of this one: there are no "its routes", only every route, unauthenticated.
     *
     * <p>⛔ The swallow is NOT a bug in {@code OptionalSpi} — it was introduced deliberately (PKG-5) so an
     * unloadable OPTIONAL component (the assistant sidecar, compiled for a newer JDK) is an absence rather
     * than a boot failure. The defect was that the Authenticator went through the same helper, where
     * absence has the opposite safety meaning. The fix is therefore scoped to THIS slot
     * ({@code new SpiSlot<>(Authenticator.class, true)}), leaving every other discovery site fail-soft.
     */
    @Test
    void aRegisteredButUnloadableAuthenticatorMustNotDegradeToAbsence() {
        // The production path, through the same helper the fail-soft slots still use.
        assertTrue(OptionalSpi.first(Authenticator.class).isEmpty(),
                "OptionalSpi itself is unchanged and still fail-soft — that is deliberate (PKG-5), and it is "
                        + "why the Authenticator had to stop going through it rather than it being changed");

        // ⚠ Strict discovery, the shape SpiSlot(failClosed=true) now uses: the refusal PROPAGATES.
        var it = ServiceLoader.load(Authenticator.class).iterator();
        assertTrue(it.hasNext(), "registered — so 'not registered' cannot be the excuse for resolving empty");
        ServiceConfigurationError propagated = assertThrows(ServiceConfigurationError.class, it::next,
                "a registered-but-unloadable Authenticator must reach the caller, so ControlApi's "
                        + "constructor fails the boot instead of serving every route unauthenticated");
        assertInstanceOf(IllegalStateException.class, propagated.getCause());
        assertTrue(String.valueOf(propagated.getCause().getMessage()).contains("auth.oidc"),
                "and the operator must be told which property is missing");
    }

    /**
     * ⚠ The other half of fail-closed, and the one easy to break while fixing the first: <b>Personal must
     * still resolve empty.</b> It registers no provider at all, which is a legitimate absence — the
     * distinction is registered-but-broken vs never-registered. A fix that threw on both would stop the
     * auth-free edition booting at all.
     */
    @Test
    void anUnregisteredSpiIsStillALegitimateAbsence() {
        assertTrue(ServiceLoader.load(NeverRegistered.class).iterator().hasNext() == false,
                "nothing registers this SPI");
        assertEquals(Optional.empty(), OptionalSpi.first(NeverRegistered.class),
                "no registration is an absence, not a failure — this is the Personal edition's path");
    }

    /** An SPI no module registers — stands in for Personal, which ships no Authenticator registration. */
    interface NeverRegistered {}
}
