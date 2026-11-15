package com.gamma.demoauth;

import com.gamma.spi.auth.ApiException;
import com.gamma.util.SpaceFolders;
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

    /** Machine-readable code of the 409 a conflicting roster answers (bootstrap, sign-in, every API call). */
    static final String ROSTER_CONFLICT = "DEMO_ROSTER_CONFLICT";

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
     * Every attached Space's Demo Users ({@code spaces}: Space id to folder, {@link #attached}), keyed by id — what
     * the sign-in picker lists. A duplicate id across Spaces that differs is refused with a typed 409
     * {@link #ROSTER_CONFLICT}: one id must mean one person on the picker. The message names the user and both Space
     * ids, never a path.
     */
    static Map<String, User> all(Map<String, Path> spaces) {
        Map<String, User> byId = new LinkedHashMap<>();
        Map<String, String> spaceOf = new LinkedHashMap<>();
        for (Map.Entry<String, Path> space : new java.util.TreeMap<>(spaces).entrySet()) {
            String name = space.getKey();
            for (User u : load(space.getValue().resolve("config"))) {
                User prior = byId.putIfAbsent(u.id(), u);
                spaceOf.putIfAbsent(u.id(), name);
                if (prior != null && !prior.equals(u))
                    throw new ApiException(409, ROSTER_CONFLICT, "Demo User '" + u.id()
                            + "' is defined differently in Spaces '" + spaceOf.get(u.id()) + "' and '" + name
                            + "'. Make that user's row in config/demo-users.toon identical in both Spaces, "
                            + "or attach only one of them to this demo, then restart.");
            }
        }
        return byId;
    }

    /**
     * The attached Spaces, id to folder, or {@code null} when Demo Users come from the legacy {@code -Dassist.write.root}
     * (or nowhere). With a core running, exactly the Spaces it booted ({@link SpaceFolders#hosted}): a {@code <id>.ref}
     * Space kept outside the Spaces root is read, and a folder refused for overlap, another pod's or one that failed to
     * boot is not (DEMO-AUTH-ROSTER-REACH-1). Without one (a unit test), the folders under {@code -Dspaces.root} that
     * {@link SpaceFolders#notASpaceReason} accepts: a hidden, reserved or backup-named copy's stale roster neither
     * signs anyone in nor conflicts (DEMO-AUTH-STALE-ROSTER-1).
     */
    static Map<String, Path> attached() {
        var booted = SpaceFolders.hosted();
        if (booted.isPresent()) return booted.get();
        String spaces = System.getProperty("spaces.root");
        if (spaces == null || spaces.isBlank()) return null;
        Path root = Path.of(spaces.trim());
        Map<String, Path> out = new LinkedHashMap<>();
        if (!Files.isDirectory(root)) return out;
        try (var dirs = Files.list(root)) {
            dirs.filter(d -> SpaceFolders.notASpaceReason(d) == null).sorted()
                    .forEach(d -> out.put(d.getFileName().toString(), d));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot list " + root + ": " + e.getMessage(), e);
        }
        return out;
    }

    private static String str(Object o) {
        return o == null || o.toString().isBlank() ? null : o.toString().trim();
    }

    private static String orElse(String v, String d) {
        return v == null ? d : v;
    }

    /**
     * How many Spaces are attached ({@link #attached}; 1 for a legacy write root, else 0). DEMO-AUTH-NO-ROSTER-1:
     * counting {@code _templates} made a bundle with no Space say it had one; DEMO-AUTH-STALE-ROSTER-1: so did a
     * backup copy; DEMO-AUTH-ROSTER-REACH-1: so did a folder refused for overlap, and a {@code .ref} Space did not count.
     */
    static int spacesAttached() {
        Map<String, Path> spaces;
        try {
            spaces = attached();
        } catch (IllegalStateException unlistable) {
            return 0;
        }
        if (spaces != null) return spaces.size();
        return legacyRoot() != null ? 1 : 0;
    }

    /** The legacy single-Space roster folder ({@code -Dassist.write.root}), or {@code null}. */
    static Path legacyRoot() {
        String wr = System.getProperty("assist.write.root");
        return wr == null || wr.isBlank() ? null : Path.of(wr.trim());
    }
}
