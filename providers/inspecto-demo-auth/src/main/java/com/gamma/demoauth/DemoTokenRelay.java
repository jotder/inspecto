package com.gamma.demoauth;

import com.gamma.control.TokenRelay;

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
