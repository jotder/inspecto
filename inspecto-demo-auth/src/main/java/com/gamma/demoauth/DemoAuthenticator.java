package com.gamma.demoauth;

import com.gamma.control.AccessGrants;
import com.gamma.control.Authenticator;
import com.gamma.control.ComponentAccess;
import com.gamma.control.Roles;
import com.gamma.control.Subject;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a demo access token into a {@link Subject}, the same way the OIDC authenticator turns a JWT into one:
 * the Demo User's roles resolve against the <b>bound Space's</b> role table ({@link Roles#effective}, stamped on
 * the exchange before authentication), Access Profile denials are removed ({@link AccessGrants}), and the held
 * role names are published for role-subject component sharing ({@link ComponentAccess#heldRoles}). So a demo
 * sees exactly the access production would give the same roles. A Demo User unknown to the bound Space gets no
 * Subject (401), as does an expired or forged token.
 */
public final class DemoAuthenticator implements Authenticator {

    public DemoAuthenticator() {
        LoopbackOnly.require();
    }

    @Override
    public Optional<Subject> authenticate(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null || header.length() < 8 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return Optional.empty();
        Optional<String> id = DemoTokens.verify('a', header.substring(7).trim(), System.currentTimeMillis() / 1000);
        if (id.isEmpty()) return Optional.empty();
        Path bound = Roles.configRoot(ex);
        Path configRoot = bound != null ? bound : legacyRoot();
        DemoUsers.User user = DemoUsers.find(configRoot, id.get());
        if (user == null) return Optional.empty();

        Map<String, Roles.Def> defs = Roles.effective(ex);
        List<String> held = user.roles().stream().filter(defs::containsKey).toList();
        Set<String> capabilities = new LinkedHashSet<>();
        Set<String> scopes = new HashSet<>();
        boolean unscoped = false;
        for (String r : held) {
            Roles.Def d = defs.get(r);
            capabilities.addAll(d.capabilities());
            if (d.dataScopes() == null) unscoped = true;
            else scopes.addAll(d.dataScopes());
        }
        capabilities.removeAll(AccessGrants.deniedCapabilities(ex, held));
        ComponentAccess.heldRoles(ex, Set.copyOf(held));
        return Optional.of(new Subject(user.id(), Set.copyOf(capabilities), held.isEmpty() ? Set.of() : unscoped ? null : Set.copyOf(scopes),
                Map.of("displayName", user.displayName(), "demoUser", true)));
    }

    private static Path legacyRoot() {
        String wr = System.getProperty("assist.write.root");
        return wr == null || wr.isBlank() ? null : Path.of(wr.trim());
    }
}
