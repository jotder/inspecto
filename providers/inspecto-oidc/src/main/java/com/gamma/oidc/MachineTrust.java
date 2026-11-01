package com.gamma.oidc;

import com.nimbusds.jwt.JWTClaimsSet;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Which clients may call with a Bearer token, and how long-lived and reusable a machine (client-credentials)
 * token may be (OIDC-CC-CLIENT-PIN-1, OIDC-CC-LIFETIME-REPLAY-1; security review G1, G2, G4, G6).
 *
 * <p><b>Opt-in.</b> Off unless {@code -Dauth.oidc.clients} lists the machine clients that may call; then a token
 * is admitted only when its client — {@code azp}, else {@code client_id} — is one of them or the SPA client
 * ({@code auth.oidc.clientId}, default {@code inspecto-spa}, the client people sign in through). Turning it on
 * also requires {@code auth.oidc.audience} and an https (or loopback) {@code auth.oidc.jwksUri}.
 *
 * <p>The ceiling ({@code -Dauth.oidc.maxTokenLifetime}, ISO-8601 such as {@code PT5M}: {@code iat} required and
 * {@code exp - iat} at most that) and the replay refusal ({@code -Dauth.oidc.refuseReplay=true}: {@code jti}
 * required and accepted once per node until {@code exp} plus the 60 s skew) apply to machine clients only. A
 * person's token is sent on every call, so the SPA client's tokens are never checked for either.
 *
 * <p>Fail-closed, value-free messages, as {@link OidcConfig}: a lifetime or replay setting without the allowlist
 * (it would guard nothing), replay refusal without a ceiling (the cache would be unbounded), or a malformed value
 * all refuse to boot.
 */
final class MachineTrust {

    /** Nimbus's default clock skew on {@code exp} ({@code DefaultJWTClaimsVerifier}). */
    private static final Duration SKEW = Duration.ofSeconds(60);

    private final Set<String> clients;
    private final String spaClient;
    private final Duration maxLifetime;   // null = no ceiling
    private final boolean refuseReplay;
    private final Map<String, Instant> seen = new ConcurrentHashMap<>();   // jti → forget after

    MachineTrust(Set<String> clients, String spaClient, Duration maxLifetime, boolean refuseReplay) {
        this.clients = Set.copyOf(clients);
        this.spaClient = spaClient;
        this.maxLifetime = maxLifetime;
        this.refuseReplay = refuseReplay;
    }

    /** The policy {@code -Dauth.oidc.*} describes, or {@code null} when {@code auth.oidc.clients} is unset. */
    static MachineTrust fromSystemProperties() {
        Set<String> clients = Arrays.stream(System.getProperty("auth.oidc.clients", "").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        Duration max = lifetime(System.getProperty("auth.oidc.maxTokenLifetime"));
        boolean replay = flag(System.getProperty("auth.oidc.refuseReplay"), "auth.oidc.refuseReplay");
        if (clients.isEmpty()) {
            if (max != null || replay)
                throw new IllegalStateException("inspecto-oidc: -Dauth.oidc.maxTokenLifetime and "
                        + "-Dauth.oidc.refuseReplay apply to machine clients only; set -Dauth.oidc.clients too");
            return null;
        }
        String audience = System.getProperty("auth.oidc.audience");
        if (audience == null || audience.isBlank())
            throw new IllegalStateException("inspecto-oidc: -Dauth.oidc.clients requires -Dauth.oidc.audience");
        String jwks = OidcConfig.get("jwksUri");
        if (jwks != null) OidcConfig.requireHttps(jwks, "auth.oidc.jwksUri");
        if (replay && max == null)
            throw new IllegalStateException("inspecto-oidc: -Dauth.oidc.refuseReplay requires "
                    + "-Dauth.oidc.maxTokenLifetime");
        return new MachineTrust(clients, System.getProperty("auth.oidc.clientId", "inspecto-spa"), max, replay);
    }

    /** Whether a token whose signature, issuer, audience and expiry already verified may be used now. */
    boolean admits(JWTClaimsSet claims, Instant now) {
        String client = client(claims);
        if (client == null) return false;
        if (client.equals(spaClient)) return true;
        if (!clients.contains(client)) return false;
        Instant exp = claims.getExpirationTime().toInstant();
        if (maxLifetime != null) {
            if (claims.getIssueTime() == null) return false;
            if (Duration.between(claims.getIssueTime().toInstant(), exp).compareTo(maxLifetime) > 0) return false;
        }
        if (refuseReplay) {
            String jti = claims.getJWTID();
            if (jti == null || jti.isBlank()) return false;
            seen.values().removeIf(forgetAfter -> forgetAfter.isBefore(now));
            if (seen.putIfAbsent(jti, exp.plus(SKEW)) != null) return false;
        }
        return true;
    }

    /**
     * The client a machine (client-credentials) token was minted for, or {@code null} for a person's token
     * (OIDC-CC-MACHINE-ACTOR-1, review G7). A token is a machine's when it says so ({@code gty} is
     * {@code client_credentials}, or Auth0's {@code client-credentials}) or when its client is listed in
     * {@code auth.oidc.clients} ({@code policy}, may be {@code null}). Nothing else is guessed: a token naming an
     * unknown client without {@code gty} stays a person's. A token naming no client has no client to record.
     */
    static String machineClient(JWTClaimsSet claims, MachineTrust policy) {
        String client = client(claims);
        if (client == null) return null;
        if (policy != null && policy.clients.contains(client) && !client.equals(policy.spaClient)) return client;
        Object gty = claims.getClaim("gty");
        return "client_credentials".equals(gty) || "client-credentials".equals(gty) ? client : null;
    }

    /** {@code azp}, else {@code client_id}; {@code null} when neither is a non-blank string. */
    private static String client(JWTClaimsSet claims) {
        if (claims.getClaim("azp") instanceof String azp && !azp.isBlank()) return azp;
        return claims.getClaim("client_id") instanceof String id && !id.isBlank() ? id : null;
    }

    private static Duration lifetime(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            Duration d = Duration.parse(raw.trim());
            if (!d.isNegative() && !d.isZero()) return d;
        } catch (DateTimeParseException ignored) {
            // refused below
        }
        throw new IllegalStateException("inspecto-oidc: -Dauth.oidc.maxTokenLifetime must be a positive "
                + "ISO-8601 duration such as PT5M");
    }

    private static boolean flag(String raw, String key) {
        if (raw == null || raw.isBlank() || raw.trim().equalsIgnoreCase("false")) return false;
        if (raw.trim().equalsIgnoreCase("true")) return true;
        throw new IllegalStateException("inspecto-oidc: -D" + key + " must be true or false");
    }
}
