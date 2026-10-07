package com.gamma.control.testkit;

import com.gamma.control.TokenRelay;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The platform's TCK for a {@link TokenRelay} (MODULE-REORG-1 P5b): the fail-closed rules for the sign-in exchange.
 * A relay that talks to an IAM is built by its subclass against an endpoint that refuses at once (or against none), so
 * the contract needs no network.
 *
 * <p>Defects each test catches:
 * <ul>
 *   <li>{@link #garbageCodesYieldNoTokens} - an authorization code that is null, blank or forged and still yields
 *       Tokens (sign-in without a login), or throws (a 500 instead of the 401 the caller maps empty to).</li>
 *   <li>{@link #garbageRefreshTokensYieldNoTokens} - the same for refresh: a forged refresh token that mints an access
 *       token is a permanent backdoor.</li>
 *   <li>{@link #revokeNeverThrows} - logout must not fail on a token the relay does not know (it is called on
 *       sign-out paths that have no other recourse).</li>
 *   <li>{@link #bootstrapAuthIsNonNullAndSecretFree} - the map is served to the browser BEFORE sign-in; a key that
 *       names a secret or password is a disclosure.</li>
 *   <li>{@link #concurrentGarbageStaysFailClosed} - shared mutable state that lets one request's result leak into
 *       another's, found by 8 threads x 100 calls.</li>
 * </ul>
 * A positive check (a valid code yields Tokens) stays in each module's own tests: minting a valid code is
 * implementation-specific.
 */
public abstract class TokenRelayContract {

    /** The relay under test. */
    protected abstract TokenRelay relay();

    /** Codes and refresh tokens a correct implementation must refuse. */
    protected List<String> garbage() {
        return Arrays.asList(null, "", " ", "garbage", "demo:", "demo:no-such-user-tck", "a.b.c", "\u0000", "x".repeat(100_000));
    }

    @Test
    void garbageCodesYieldNoTokens() {
        TokenRelay r = relay();
        for (String code : garbage()) {
            Optional<TokenRelay.Tokens> t = call(() -> r.exchangeCode(code, code, "http://localhost/cb"), "exchangeCode", code);
            assertTrue(t.isEmpty(), "exchangeCode('" + abbreviate(code) + "') must not mint tokens");
        }
        assertTrue(call(() -> r.exchangeCode("garbage", null, null), "exchangeCode", "null verifier").isEmpty(),
                "a missing verifier/redirect must not mint tokens");
    }

    @Test
    void garbageRefreshTokensYieldNoTokens() {
        TokenRelay r = relay();
        for (String token : garbage()) {
            Optional<TokenRelay.Tokens> t = call(() -> r.refresh(token), "refresh", token);
            assertTrue(t.isEmpty(), "refresh('" + abbreviate(token) + "') must not mint tokens");
        }
    }

    @Test
    void revokeNeverThrows() {
        TokenRelay r = relay();
        for (String token : garbage()) {
            try {
                r.revoke(token);
            } catch (Throwable t) {
                fail("revoke('" + abbreviate(token) + "') threw " + t, t);
            }
        }
    }

    @Test
    void bootstrapAuthIsNonNullAndSecretFree() {
        Map<String, Object> m = relay().bootstrapAuth();
        assertNotNull(m, "bootstrapAuth() must not be null");
        assertSecretFree(m);
    }

    @Test
    void concurrentGarbageStaysFailClosed() throws Exception {
        TokenRelay r = relay();
        List<String> g = garbage();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int t = 0; t < 8; t++)
                results.add(pool.submit(() -> {
                    for (int i = 0; i < 100; i++) {
                        String s = g.get(i % g.size());
                        if (r.exchangeCode(s, s, "http://localhost/cb").isPresent() || r.refresh(s).isPresent()) return false;
                    }
                    return true;
                }));
            for (Future<Boolean> f : results)
                assertTrue(f.get(60, TimeUnit.SECONDS), "a garbage code or refresh token minted tokens under concurrency");
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertSecretFree(Object node) {
        if (node instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey()).toLowerCase();
                assertFalse(k.contains("secret") || k.contains("password"), "bootstrapAuth() exposes a key named '" + e.getKey() + "'");
                assertSecretFree(e.getValue());
            }
        } else if (node instanceof Iterable<?> it) {
            for (Object o : it) assertSecretFree(o);
        }
    }

    private static <T> T call(java.util.concurrent.Callable<T> c, String what, String arg) {
        try {
            return c.call();
        } catch (Throwable t) {
            fail(what + "('" + abbreviate(arg) + "') threw " + t + " - it must answer empty", t);
            return null;
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "null";
        return s.length() > 40 ? s.substring(0, 40) + "...(" + s.length() + " chars)" : s;
    }
}
