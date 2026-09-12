package com.gamma.control;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.ServiceConfigurationError;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SpiSlot}'s two discovery postures, exercised through real {@code ServiceLoader} registrations
 * (see {@code src/test/resources/META-INF/services/}) rather than a mock — the wrapping of a provider
 * constructor's throw into a {@link ServiceConfigurationError} is the entire mechanism under test, and a
 * hand-built fake would not reproduce it.
 *
 * <p>🔴 <b>Why the fail-closed posture exists.</b> Until 2026-09-12 every slot was fail-soft, so a
 * registered-but-unloadable provider resolved to EMPTY. For {@link Authenticator} that is a security
 * downgrade, not a graceful degradation: empty means {@link ControlApi#dispatch} skips authentication, so
 * a Standard deployment with a mistyped {@code -Dauth.oidc.jwksUri} booted <b>wide open</b> while
 * {@link ControlApi}'s constructor comment promised it would "fail to boot instead of silently accepting
 * traffic". The companion {@code AuthenticatorDiscoveryFailClosedTest} pins the same contract against the
 * real {@code inspecto-security} provider.
 *
 * <p>⛔ Fail-soft remains the default and is not a bug: PKG-5 introduced it so an optional component
 * compiled for a newer JDK (the assistant sidecar) is an absence rather than a boot failure. The two
 * postures must both keep working, which is what these tests hold in place.
 */
class SpiSlotFailClosedTest {

    /** An SPI whose only registered provider throws from its constructor. */
    public interface Broken {}

    /** An SPI with a provider that constructs normally. */
    public interface Working {}

    /** An SPI nothing registers — stands in for Personal, which ships no Authenticator registration. */
    public interface Unregistered {}

    public static final class BrokenProvider implements Broken {
        public BrokenProvider() {
            throw new IllegalStateException("inspecto-test requires -Dauth.oidc.jwksUri");
        }
    }

    public static final class WorkingProvider implements Working {}

    // ── fail-soft (the default, every slot but the Authenticator) ──────────────────────────────

    @Test
    void failSoftDegradesAnUnloadableProviderToAbsence() {
        assertTrue(new SpiSlot<>(Broken.class).active().isEmpty(),
                "PKG-5's contract: an optional module that cannot load is an ABSENCE, so the server still "
                        + "boots and only that module's own routes answer 503");
    }

    // ── fail-closed (the Authenticator) ───────────────────────────────────────────────────────

    /** 🔴 The fix: registered-but-unloadable must reach the caller, so the boot fails instead of opening up. */
    @Test
    void failClosedPropagatesAnUnloadableProvider() {
        SpiSlot<Broken> slot = new SpiSlot<>(Broken.class, true);
        ServiceConfigurationError e = assertThrows(ServiceConfigurationError.class, slot::active,
                "a provider that announced itself and then refused to construct must NOT resolve empty");
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertTrue(String.valueOf(e.getCause().getMessage()).contains("auth.oidc"),
                "and the operator must still be told which property is missing");
    }

    /**
     * ⚠ The half that is easy to break while fixing the other: <b>never-registered must stay empty.</b>
     * Personal ships no {@code META-INF/services} entry, and that absence is legitimate — a fix that threw
     * on both cases would stop the auth-free edition booting at all. Registered-but-broken and
     * never-registered are different facts and the posture must tell them apart.
     */
    @Test
    void failClosedStillTreatsAnUnregisteredSpiAsALegitimateAbsence() {
        assertEquals(Optional.empty(), new SpiSlot<>(Unregistered.class, true).active(),
                "no registration at all is the Personal edition's path and must resolve empty");
    }

    @Test
    void failClosedResolvesAWorkingProviderNormally() {
        Optional<Working> resolved = new SpiSlot<>(Working.class, true).active();
        assertTrue(resolved.isPresent());
        assertInstanceOf(WorkingProvider.class, resolved.get());
    }

    // ── caching, which must not be lost by either posture ─────────────────────────────────────

    @Test
    void aResolvedProviderIsCached() {
        SpiSlot<Working> slot = new SpiSlot<>(Working.class, true);
        Working first = slot.active().orElseThrow();
        assertSame(first, slot.active().orElseThrow(), "the classpath does not change at runtime");
    }

    /**
     * ⚠ The test seam still wins over a fail-closed scan. Without this, a fail-closed slot whose provider
     * is broken could not be overridden in a test at all, because {@code active()} would throw before the
     * forced value was ever consulted — {@code forTest} writes the cache directly, which is why it works.
     */
    @Test
    void theTestSeamStillOverridesAFailClosedSlot() {
        SpiSlot<Broken> slot = new SpiSlot<>(Broken.class, true);
        Broken stub = new Broken() {};
        slot.forTest(stub);
        assertSame(stub, slot.active().orElseThrow());
        slot.forTest(null);
        assertNotNull(assertThrows(ServiceConfigurationError.class, slot::active),
                "clearing the seam restores the scanned behaviour for later test classes");
    }
}
