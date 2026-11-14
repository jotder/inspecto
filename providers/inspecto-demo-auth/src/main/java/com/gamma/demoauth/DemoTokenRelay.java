package com.gamma.demoauth;

import com.gamma.spi.auth.TokenRelay;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The demo session broker behind {@code /auth/exchange}, {@code /auth/refresh} and {@code /auth/logout}: the SPA's
 * mock sign-in sends {@code code = "demo:<Demo User id>"}, and this relay mints a short access token plus a
 * refresh token for that Demo User ({@link DemoTokens}). No IAM, no network, no password (decision D-4). It
 * refuses to load unless the control plane is bound to loopback ({@link LoopbackOnly}).
 */
public final class DemoTokenRelay implements TokenRelay {

    static final String CODE_PREFIX = "demo:";

    public DemoTokenRelay() {
        LoopbackOnly.require();
        warnIfRosterConflicts();
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DemoTokenRelay.class);

    /**
     * Startup: a roster conflict is logged as one unmissable block, and the server KEEPS SERVING (a demo is
     * a dev/eval build; every sign-in surface answers the same typed 409 until the operator fixes it).
     */
    static void warnIfRosterConflicts() {
        try {
            known();
        } catch (com.gamma.spi.auth.ApiException e) {
            if (DemoUsers.ROSTER_CONFLICT.equals(e.errorCode))
                LOG.error("\n==================== DEMO SIGN-IN IS DISABLED ====================\n{}\n"
                        + "No Demo User can sign in until this is fixed. The server is still running.\n"
                        + "=================================================================", e.getMessage());
            else throw e;
        }
    }

    /**
     * DEMO-AUTH-NO-ROSTER-1: the startup block for a demo that nobody can sign into, or {@code null} when some Demo User
     * exists (or a roster conflict, already logged, is the reason). The server keeps serving: the sign-in page says
     * the same, and a roster added to an attached Space is read on the next call.
     */
    static String noDemoUsersWarning() {
        try {
            if (!known().isEmpty()) return null;
        } catch (com.gamma.spi.auth.ApiException conflict) {
            return null;
        }
        String spaces = System.getProperty("spaces.root"), wr = System.getProperty("assist.write.root");
        String why = spaces == null || spaces.isBlank()
                ? wr == null || wr.isBlank()
                        ? "Neither -Dspaces.root nor -Dassist.write.root is set, so there is no roster to read. Start with serve-demo."
                        : "No Demo User is defined in " + wr.trim() + "/demo-users.toon. Add one there; it is read on every"
                                + " sign-in, no restart is needed."
                : DemoUsers.spacesAttached() == 0
                ? "No Space is attached under " + spaces.trim() + " (_templates holds templates, not Spaces). Copy a Space"
                        + " folder carrying config/demo-users.toon into it, then restart."
                : "No attached Space carries config/demo-users.toon under " + spaces.trim() + ". Add one to a Space,"
                        + " e.g.\n  users[1]{id,displayName,title,roles,landing}:\n    analyst,Demo Analyst,Analyst,operations,\n"
                        + "It is read on every sign-in: no restart is needed.";
        return "\n==================== DEMO SIGN-IN HAS NO USERS ====================\n" + why
                + "\nUntil then nobody can sign in. The server is still running.\n"
                + "=================================================================";
    }

    @Override
    public Optional<Tokens> exchangeCode(String code, String codeVerifier, String redirectUri) {
        if (code == null || !code.startsWith(CODE_PREFIX)) return Optional.empty();
        String id = code.substring(CODE_PREFIX.length());
        if (!known().containsKey(id)) return Optional.empty();
        return Optional.of(tokens(id));
    }

    @Override
    public Optional<Tokens> refresh(String refreshToken) {
        long now = System.currentTimeMillis() / 1000;
        return DemoTokens.verify('r', refreshToken, now).filter(known()::containsKey).map(this::tokens);
    }

    /** The picker: every Demo User's id, name, title and optional landing menu item (UIE-7) — never roles or
     *  capabilities (pre-sign-in). */
    @Override
    public Map<String, Object> bootstrapAuth() {
        List<Map<String, Object>> users = new ArrayList<>();
        for (DemoUsers.User u : known().values()) {
            Map<String, Object> entry = new LinkedHashMap<>(Map.of("id", u.id(), "displayName", u.displayName(), "title", u.title()));
            if (u.landing() != null) entry.put("landing", u.landing());
            users.add(entry);
        }
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("mock", true);
        auth.put("demoUsers", users);
        // An empty picker with zero Spaces attached is a dead end the sign-in page must explain.
        auth.put("spacesAttached", DemoUsers.spacesAttached());
        return auth;
    }

    private Tokens tokens(String id) {
        long now = System.currentTimeMillis() / 1000;
        return new Tokens(DemoTokens.mint('a', id, now), DemoTokens.ACCESS_SECONDS,
                DemoTokens.mint('r', id, now), DemoTokens.REFRESH_SECONDS);
    }

    /** Every hosted Space's Demo Users (multi-Space mode), or the single write root's (legacy mode). */
    static Map<String, DemoUsers.User> known() {
        String spaces = System.getProperty("spaces.root");
        if (spaces != null && !spaces.isBlank()) return DemoUsers.all(Path.of(spaces.trim()));
        String wr = System.getProperty("assist.write.root");
        Map<String, DemoUsers.User> byId = new LinkedHashMap<>();
        if (wr != null && !wr.isBlank())
            for (DemoUsers.User u : DemoUsers.load(Path.of(wr.trim()))) byId.put(u.id(), u);
        return byId;
    }
}
