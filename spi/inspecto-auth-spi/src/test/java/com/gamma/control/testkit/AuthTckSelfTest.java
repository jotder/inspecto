package com.gamma.control.testkit;

import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Subject;
import com.gamma.spi.auth.TokenRelay;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves the Authenticator and TokenRelay TCKs can fail (MODULE-REORG-1 P5b): each assertion runs against a deliberately
 * broken in-test implementer and must go red; a fail-closed one must stay green.
 */
class AuthTckSelfTest {

    private static final Subject ANYONE = new Subject("intruder", Set.of(), Set.of(), Map.of());

    private static AuthenticatorContract contract(Function<HttpExchange, Optional<Subject>> f) {
        Authenticator a = f::apply;
        return new AuthenticatorContract() {
            @Override protected Authenticator authenticator() { return a; }
        };
    }

    private static Optional<Subject> failClosed(HttpExchange ex) {
        return Optional.empty();
    }

    @Test
    void aFailClosedAuthenticatorPasses() {
        AuthenticatorContract c = contract(AuthTckSelfTest::failClosed);
        assertDoesNotThrow(() -> {
            c.noCredentialIsNoSubject();
            c.garbageCredentialsAreNoSubject();
            c.aRefusalIsOfConstantShape();
            c.concurrentGarbageStaysFailClosed();
        });
    }

    @Test
    void anOpenDoorIsCaught() {
        assertThrows(AssertionFailedError.class, () -> contract(ex -> Optional.of(ANYONE)).noCredentialIsNoSubject());
    }

    @Test
    void failingOpenOnGarbageIsCaught() {
        AuthenticatorContract c = contract(ex -> {
            String h = ex.getRequestHeaders().getFirst("Authorization");
            return h != null && h.startsWith("Basic") ? Optional.of(ANYONE) : Optional.empty();
        });
        assertThrows(AssertionFailedError.class, c::garbageCredentialsAreNoSubject);
    }

    @Test
    void throwingOnGarbageIsCaught() {
        AuthenticatorContract c = contract(ex -> {
            String h = ex.getRequestHeaders().getFirst("Authorization");
            if (h != null && h.length() > 50_000) throw new IllegalStateException("token too long: " + h.substring(0, 20));
            return Optional.empty();
        });
        assertThrows(AssertionFailedError.class, c::garbageCredentialsAreNoSubject);
    }

    @Test
    void aVaryingAnswerIsCaught() {
        AtomicInteger calls = new AtomicInteger();
        AuthenticatorContract c = contract(ex -> calls.incrementAndGet() % 2 == 0 ? Optional.of(ANYONE) : Optional.empty());
        assertThrows(AssertionFailedError.class, c::aRefusalIsOfConstantShape);
    }

    @Test
    void sharedStateThatLetsAGarbageRequestInUnderConcurrencyIsCaught() {
        AtomicInteger calls = new AtomicInteger();
        AuthenticatorContract c = contract(ex -> calls.incrementAndGet() > 1_000 ? Optional.of(ANYONE) : Optional.empty());
        assertThrows(AssertionFailedError.class, c::concurrentGarbageStaysFailClosed);
    }

    // ------------------------------------------------------------ token relay

    private static final TokenRelay.Tokens TOKENS = new TokenRelay.Tokens("a", 60, "r", 600L);

    /** Behaviours a test can break. */
    private record Relay(Function<String, Optional<TokenRelay.Tokens>> exchange,
                         Function<String, Optional<TokenRelay.Tokens>> refresh,
                         Runnable revoke, Map<String, Object> bootstrap) implements TokenRelay {
        @Override public Optional<Tokens> exchangeCode(String code, String v, String r) { return exchange.apply(code); }
        @Override public Optional<Tokens> refresh(String token) { return refresh.apply(token); }
        @Override public void revoke(String token) { revoke.run(); }
        @Override public Map<String, Object> bootstrapAuth() { return bootstrap; }
    }

    private static final Function<String, Optional<TokenRelay.Tokens>> NONE = s -> Optional.empty();

    private static TokenRelayContract relay(Relay r) {
        return new TokenRelayContract() {
            @Override protected TokenRelay relay() { return r; }
        };
    }

    @Test
    void aFailClosedRelayPasses() {
        TokenRelayContract c = relay(new Relay(NONE, NONE, () -> { }, Map.of("mock", true)));
        assertDoesNotThrow(() -> {
            c.garbageCodesYieldNoTokens();
            c.garbageRefreshTokensYieldNoTokens();
            c.revokeNeverThrows();
            c.bootstrapAuthIsNonNullAndSecretFree();
            c.concurrentGarbageStaysFailClosed();
        });
    }

    @Test
    void aRelayThatMintsForAForgedCodeIsCaught() {
        TokenRelayContract c = relay(new Relay(s -> s != null && s.startsWith("demo:") ? Optional.of(TOKENS) : Optional.empty(),
                NONE, () -> { }, Map.of()));
        assertThrows(AssertionFailedError.class, c::garbageCodesYieldNoTokens);
    }

    @Test
    void aRelayThatThrowsOnANullCodeIsCaught() {
        TokenRelayContract c = relay(new Relay(s -> Optional.of(s.length()).flatMap(n -> Optional.<TokenRelay.Tokens>empty()),
                NONE, () -> { }, Map.of()));
        assertThrows(AssertionFailedError.class, c::garbageCodesYieldNoTokens);
    }

    @Test
    void aRelayThatRefreshesAForgedTokenIsCaught() {
        TokenRelayContract c = relay(new Relay(NONE, s -> Optional.of(TOKENS), () -> { }, Map.of()));
        assertThrows(AssertionFailedError.class, c::garbageRefreshTokensYieldNoTokens);
    }

    @Test
    void aRevokeThatThrowsIsCaught() {
        TokenRelayContract c = relay(new Relay(NONE, NONE, () -> { throw new IllegalStateException("unknown token"); }, Map.of()));
        assertThrows(AssertionFailedError.class, c::revokeNeverThrows);
    }

    @Test
    void aBootstrapThatLeaksASecretOrIsNullIsCaught() {
        assertThrows(AssertionFailedError.class,
                () -> relay(new Relay(NONE, NONE, () -> { }, Map.of("clientSecret", "x"))).bootstrapAuthIsNonNullAndSecretFree());
        assertThrows(AssertionFailedError.class,
                () -> relay(new Relay(NONE, NONE, () -> { }, Map.of("nested", Map.of("db_password", "x")))).bootstrapAuthIsNonNullAndSecretFree());
        assertThrows(AssertionFailedError.class,
                () -> relay(new Relay(NONE, NONE, () -> { }, null)).bootstrapAuthIsNonNullAndSecretFree());
    }

    @Test
    void sharedStateThatMintsUnderConcurrencyIsCaught() {
        AtomicInteger calls = new AtomicInteger();
        TokenRelayContract c = relay(new Relay(s -> calls.incrementAndGet() > 500 ? Optional.of(TOKENS) : Optional.empty(),
                NONE, () -> { }, Map.of()));
        assertThrows(AssertionFailedError.class, c::concurrentGarbageStaysFailClosed);
    }
}
