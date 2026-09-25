package com.gamma.demoauth;

import com.gamma.util.ToonHelper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Demo Users of a Space: {@code <space>/config/demo-users.toon}.
 *
 * <pre>
 * users[2]{id,displayName,title,roles,landing}:
 *   ra.analyst,Demo RA Analyst,Revenue Assurance analyst,operations,2da48ada-fbb5-57cb-af5b-acebda2f6135
 *   admin,Demo Admin,Platform administrator,admin;super,
 * </pre>
 *
 * {@code roles} is {@code ;}-separated and names roles of the Space's own role table ({@code roles.toon} over
 * the seed). {@code landing} (optional, UIE-7) is the id of a menu item in the Space's {@code nav-menus.toon} the
 * SPA opens after this Demo User signs in, ahead of the Space's own landing; blank means "the Space's landing".
 * The SPA resolves it and falls back silently when it names no menu item. A missing file means the Space has no Demo Users. Re-read on every call: the file is tiny and a
 * demo operator may edit it while the server runs.
 */
final class DemoUsers {

    static final String FILE = "demo-users.toon";

    /** One Demo User. {@code roles} are lower-cased, like the role table's keys. */
    record User(String id, String displayName, String title, Set<String> roles, String landing) {}

    private DemoUsers() {}

    /** The Demo Users under one Space config root, in file order; empty when the file is absent. */
    static List<User> load(Path configRoot) {
        if (configRoot == null) return List.of();
        Path file = configRoot.resolve(FILE);
        if (!Files.isRegularFile(file)) return List.of();
        try {
            Object rows = ToonHelper.load(file.toString()).get("users");
            if (!(rows instanceof List<?> list)) throw new IllegalArgumentException("'users' must be a table");
            List<User> out = new ArrayList<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                String id = str(m.get("id"));
                if (id == null) throw new IllegalArgumentException("every Demo User needs an id");
                Set<String> roles = new LinkedHashSet<>();
                String r = str(m.get("roles"));
                if (r != null)
                    for (String part : r.split(";")) if (!part.isBlank()) roles.add(part.trim().toLowerCase(java.util.Locale.ROOT));
                out.add(new User(id, orElse(str(m.get("displayName")), id), orElse(str(m.get("title")), ""), Set.copyOf(roles),
                        str(m.get("landing"))));
            }
            return List.copyOf(out);
        } catch (Exception e) {
            throw new IllegalStateException("unreadable " + file + ": " + e.getMessage(), e);
        }
    }

    /** One Space's Demo User by id, or {@code null}. */
    static User find(Path configRoot, String id) {
        for (User u : load(configRoot)) if (u.id().equals(id)) return u;
        return null;
    }

    /**
     * Every Space's Demo Users under {@code -Dspaces.root}, keyed by id — what the sign-in picker lists.
     * A duplicate id across Spaces is refused: one id must mean one person on the picker.
     */
    static Map<String, User> all(Path spacesRoot) {
        Map<String, User> byId = new LinkedHashMap<>();
        if (spacesRoot == null || !Files.isDirectory(spacesRoot)) return byId;
        try (var dirs = Files.list(spacesRoot)) {
            for (Path space : dirs.sorted().toList()) {
                for (User u : load(space.resolve("config"))) {
                    User prior = byId.putIfAbsent(u.id(), u);
                    if (prior != null && !prior.equals(u))
                        throw new IllegalStateException("Demo User '" + u.id() + "' is defined differently in two Spaces");
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot list " + spacesRoot + ": " + e.getMessage(), e);
        }
        return byId;
    }

    private static String str(Object o) {
        return o == null || o.toString().isBlank() ? null : o.toString().trim();
    }

    private static String orElse(String v, String d) {
        return v == null ? d : v;
    }
}
