package com.gamma.control;

import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.auth.Subject;
import com.gamma.spi.http.ApiContext;
import com.gamma.util.ToonHelper;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A Space's <b>approver roster</b> (operator decision 2026-10-04, {@code ASSURE-ACTION-REQUESTS-RESIDUALS-1} (6b)):
 * the admin-curated user ids and IdP groups who may decide a four-eyes item — an Action Request or a Pending Change —
 * when the Authenticator has no principal directory (OIDC: it cannot say who holds {@code canApproveChanges}).
 * Persisted as {@code approvers.toon} in the Space's config root ({@code users: [...]}, {@code groups: [...]}),
 * written by {@code PUT /settings/approvers} ({@link ApproverRosterRoutes}).
 *
 * <p><b>One list per Space</b>, not per approval kind: both consumers decide on the same notion (the approve
 * capability plus "not a maker"), so a per-kind split would have nothing to key on.
 *
 * <p><b>Fail closed.</b> An absent, empty or unreadable roster admits NOBODY — never "anyone". A caller is on it when
 * their {@link Subject#id()} is listed, or any value of their {@code groups} attribute (the IdP claim, allowlisted
 * in {@code roles.toon} {@code identity.attributeClaims}) is. The roster only ever NARROWS: the route's capability and
 * the maker-can-never-approve rule still apply on top of it.
 *
 * <p><b>When it applies.</b> Only under an Authenticator that does not enumerate principals ({@link
 * Authenticator#principals} empty — OIDC). Demo sign-in enumerates its users and keeps deciding on roles alone;
 * Personal has no Subject, so deciding is already 403.
 */
public final class ApproverRoster {

    public static final String FILE = "approvers.toon";
    /** The Subject attribute carrying the caller's IdP groups. */
    public static final String GROUPS_ATTRIBUTE = "groups";

    public record Roster(List<String> users, List<String> groups) {
        public Roster {
            users = List.copyOf(users);
            groups = List.copyOf(groups);
        }

        public boolean empty() {
            return users.isEmpty() && groups.isEmpty();
        }

        /** Whether the caller is on the roster: their id, or any of their groups. */
        public boolean admits(Subject s) {
            if (s == null) return false;
            if (users.contains(s.id())) return true;
            for (String g : groupsOf(s)) if (groups.contains(g)) return true;
            return false;
        }
    }

    private ApproverRoster() {}

    /** The roster of the Space whose config root is {@code root}; absent or unreadable reads as EMPTY (fail closed). */
    public static Roster load(Path root) {
        if (root == null) return new Roster(List.of(), List.of());
        Path f = root.resolve(FILE);
        if (!Files.isRegularFile(f)) return new Roster(List.of(), List.of());
        try {
            Map<String, Object> doc = ToonHelper.load(f.toString());
            return new Roster(strings(doc.get("users")), strings(doc.get("groups")));
        } catch (RuntimeException | java.io.IOException unreadable) {
            org.slf4j.LoggerFactory.getLogger(ApproverRoster.class)
                    .warn("{} is unreadable, so nobody may approve until an administrator rewrites it: {}", f, unreadable.toString());
            return new Roster(List.of(), List.of());
        }
    }

    /** Whether the roster decides eligibility here: an Authenticator is active and it has no principal directory. */
    public static boolean applies(Path root) {
        Authenticator auth = Authenticators.active().orElse(null);
        if (auth == null) return false;
        try {
            return auth.principals(root).isEmpty();
        } catch (RuntimeException failed) {
            return true;   // cannot tell who holds the role: decide on the roster (closed when it is empty)
        }
    }

    /** 403 unless the caller may decide under the roster — a no-op where the roster does not apply. */
    public static void requireOnRoster(HttpExchange ex, Path root, String what) {
        if (!applies(root)) return;
        Subject s = ApiContext.subject(ex).orElse(null);
        Roster r = load(root);
        if (!r.admits(s))
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, (s == null ? "the caller" : "'" + s.id() + "'")
                    + " is not on this Space's approver roster, so cannot " + what
                    + (r.empty() ? " — the roster is empty, so nobody can; an administrator sets it in Settings ▸ Approvers"
                                 : " — an administrator adds approvers in Settings ▸ Approvers"));
    }

    /** The caller's IdP groups: the {@code groups} attribute as a list or a single string. */
    static List<String> groupsOf(Subject s) {
        Object v = s.attributes().get(GROUPS_ATTRIBUTE);
        if (v instanceof Collection<?> c) return c.stream().map(String::valueOf).toList();
        return v == null ? List.of() : List.of(String.valueOf(v));
    }

    /**
     * The roster reading of "could anyone approve" for {@code makers}: empty → {@code none-eligible}; a group (whose
     * members cannot be listed) or a listed user who is not a maker → {@code ok}; otherwise {@code none-eligible}.
     */
    static String check(Path root, Set<Object> makers) {
        Roster r = load(root);
        if (!r.groups().isEmpty()) return ActionRequestRoutes.OK;
        for (String u : r.users()) if (!makers.contains(u)) return ActionRequestRoutes.OK;
        return ActionRequestRoutes.NONE_ELIGIBLE;
    }

    private static List<String> strings(Object v) {
        if (!(v instanceof List<?> l)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : l) if (o != null && !String.valueOf(o).isBlank()) out.add(String.valueOf(o).trim());
        return out;
    }

    static Optional<String> invalidEntry(String s) {
        if (s.isBlank()) return Optional.of("an entry is blank");
        if (s.length() > 256) return Optional.of("an entry is longer than 256 chars");
        if (s.chars().anyMatch(Character::isISOControl)) return Optional.of("an entry contains a control character");
        return Optional.empty();
    }
}
