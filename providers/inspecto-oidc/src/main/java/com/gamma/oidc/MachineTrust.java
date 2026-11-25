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
    /** {@code -Dauth.oidc.agentMachineClients} (AGENT-KIT-1 AK-2c): the machine clients that are agents. */
    private final Set<String> agentMachines;

    MachineTrust(Set<String> clients, String spaClient, Duration maxLifetime, boolean refuseReplay) {
        this(clients, spaClient, maxLifetime, refuseReplay, Set.of());
    }

    MachineTrust(Set<String> clients, String spaClient, Duration maxLifetime, boolean refuseReplay,
                 Set<String> agentMachines) {
        if (!clients.containsAll(agentMachines) || agentMachines.contains(spaClient))
            throw new IllegalStateException("inspecto-oidc: every -Dauth.oidc.agentMachineClients entry must be a machine "
                    + "client listed in -Dauth.oidc.clients (and not the SPA client)");
        this.agentMachines = Set.copyOf(agentMachines);
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
        Set<String> agentMachines = csv(System.getProperty("auth.oidc.agentMachineClients", ""));
        if (clients.isEmpty()) {
            if (max != null || replay || !agentMachines.isEmpty())
                throw new IllegalStateException("inspecto-oidc: -Dauth.oidc.maxTokenLifetime, "
                        + "-Dauth.oidc.refuseReplay and -Dauth.oidc.agentMachineClients apply to machine clients only; "
                        + "set -Dauth.oidc.clients too");
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
        return new MachineTrust(clients, System.getProperty("auth.oidc.clientId", "inspecto-spa"), max, replay,
                agentMachines);
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

    /**
     * The machine agent a machine token came from (AGENT-KIT-1 AK-2c), or {@code null}: {@code machine} (from
     * {@link #machineClient}) is listed in {@code -Dauth.oidc.agentMachineClients}. Such a Subject is both a machine and
     * an agent: it keeps the agent machine capability set ({@code Roles.AGENT_MACHINE_CAPABILITIES}), every config write
     * it makes is held as a Pending Change, every action as a Pending Action, and it never approves.
     */
    static String agentMachineClient(String machine, MachineTrust policy) {
        return machine != null && policy != null && policy.agentMachines.contains(machine) ? machine : null;
    }

    /**
     * The allowlisted agent client a PERSON's token came from (AGENT-KIT-1 AK-2a, {@code -Dauth.oidc.agentClients}), or
     * {@code null}. A client-credentials token ({@code gty}) is never an agent's person token: it stays a machine's, judged
     * by {@link #machineClient} as before. {@code agents} never shares a client with {@code auth.oidc.clients} (a boot
     * refusal), so a listed agent client cannot also be classed a machine through the pin.
     */
    static String agentClient(JWTClaimsSet claims, Set<String> agents) {
        if (agents.isEmpty()) return null;
        String client = client(claims);
        if (client == null || !agents.contains(client)) return null;
        Object gty = claims.getClaim("gty");
        return "client_credentials".equals(gty) || "client-credentials".equals(gty) ? null : client;
    }

    /**
     * {@code -Dauth.oidc.agentClients} (comma list), checked at boot: it needs {@code auth.oidc.audience} (an agent token
     * must be minted for this install, as a machine's must), and no client may be both an agent client and a machine
     * client ({@code auth.oidc.clients}) or be the SPA client ({@code auth.oidc.clientId}) — each would make one token two
     * kinds of caller.
     */
    static Set<String> agentClientsFromSystemProperties() {
        Set<String> agents = csv(System.getProperty("auth.oidc.agentClients", ""));
        if (agents.isEmpty()) return Set.of();
        String audience = System.getProperty("auth.oidc.audience");
        if (audience == null || audience.isBlank())
            throw new IllegalStateException("inspecto-oidc: -Dauth.oidc.agentClients requires -Dauth.oidc.audience");
        Set<String> both = new java.util.TreeSet<>(agents);
        both.retainAll(csv(System.getProperty("auth.oidc.clients", "")));
        if (!both.isEmpty())
            throw new IllegalStateException("inspecto-oidc: " + both + " listed in both -Dauth.oidc.clients (machines) and "
                    + "-Dauth.oidc.agentClients (agents acting as a person) — a client is one or the other");
        String spa = System.getProperty("auth.oidc.clientId", "inspecto-spa");
        if (agents.contains(spa))
            throw new IllegalStateException("inspecto-oidc: the SPA client '" + spa + "' cannot be an agent client "
                    + "(-Dauth.oidc.agentClients) — register a separate client for agents");
        return Set.copyOf(agents);
    }

    private static Set<String> csv(String raw) {
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
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
