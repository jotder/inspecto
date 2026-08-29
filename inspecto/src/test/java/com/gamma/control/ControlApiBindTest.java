package com.gamma.control;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GAP-1 (deployment-topology-plan §10): {@code -Dcontrol.bind} restricts the control plane's listen
 * address.
 *
 * <p>⚠ The load-bearing case here is {@link #defaultIsEveryInterface()}. The default is the WILDCARD
 * address by deliberate call (2026-08-29) — narrowing it would make every already-deployed Standard and
 * Enterprise install unreachable on upgrade — so a future change that quietly "hardens" the default
 * must fail this test rather than surprise an operator. {@code docs/EDITIONS.md} states the exposure
 * plainly for exactly this reason: Personal ships no authenticator, so an unrestricted bind there
 * serves an unauthenticated control plane to the whole network.
 */
class ControlApiBindTest {

    private String saved;

    private void bind(String value) {
        saved = System.getProperty("control.bind");
        if (value == null) System.clearProperty("control.bind");
        else System.setProperty("control.bind", value);
    }

    @AfterEach
    void restore() {
        // Surefire shares a JVM across classes: a leaked -Dcontrol.bind would silently re-address every
        // later test's server.
        if (saved == null) System.clearProperty("control.bind");
        else System.setProperty("control.bind", saved);
    }

    @Test
    @DisplayName("unset binds every interface — the documented default, pinned so it cannot drift quietly")
    void defaultIsEveryInterface() throws IOException {
        bind(null);
        InetSocketAddress address = ControlApi.bindAddress(8080);
        assertTrue(address.getAddress().isAnyLocalAddress(),
                "default must stay the wildcard address, got " + address);
        assertEquals(8080, address.getPort());
    }

    @Test
    @DisplayName("an explicit host restricts the listener — the single-user install's answer")
    void explicitHostRestricts() throws IOException {
        bind("127.0.0.1");
        InetSocketAddress address = ControlApi.bindAddress(8080);
        assertTrue(address.getAddress().isLoopbackAddress(), "expected loopback, got " + address);
        assertFalse(address.getAddress().isAnyLocalAddress());
        assertEquals(8080, address.getPort());
    }

    @Test
    @DisplayName("surrounding whitespace is tolerated — a -D value pasted from a runbook still binds")
    void valueIsTrimmed() throws IOException {
        bind("  127.0.0.1  ");
        assertTrue(ControlApi.bindAddress(8080).getAddress().isLoopbackAddress());
    }

    @Test
    @DisplayName("blank is treated as unset, never as a bind failure")
    void blankFallsBackToTheDefault() throws IOException {
        bind("   ");
        assertTrue(ControlApi.bindAddress(8080).getAddress().isAnyLocalAddress());
    }

    /**
     * 🔴 The direction of the failure is the point: a bind that cannot be honoured must stop the boot,
     * never fall back to the wider default. An operator who asked for loopback and got every interface
     * would have no way to notice.
     */
    @Test
    @DisplayName("an unresolvable host fails the boot rather than falling back to every interface")
    void unresolvableHostFailsClosed() {
        bind("no-such-host.invalid");
        IOException e = assertThrows(IOException.class, () -> ControlApi.bindAddress(8080));
        assertTrue(e.getMessage().contains("control.bind"), e.getMessage());
        assertTrue(e.getMessage().contains("no-such-host.invalid"), e.getMessage());
    }
}
