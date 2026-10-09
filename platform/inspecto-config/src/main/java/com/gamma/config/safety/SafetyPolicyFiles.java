package com.gamma.config.safety;

import com.gamma.util.ToonHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads the Safety Policy tier files — {@code safety-policy.toon} in the server config directory and in a
 * Space's {@code config/} — and resolves a Space's effective policy ({@code policy-narrowing-design.md} §5).
 *
 * <p>The parse is <b>strict and fail-closed</b>: TOON damage, an IO/stat failure, an unknown key, a bad value,
 * or {@code mode} in a Space file all throw {@link SafetyPolicyUnreadableException}. The server file may name
 * Spaces whose policy is <em>required</em> ({@code require_spaces}, D7): a named Space with no file is
 * unreadable too, because naming is what makes a deletion detectable. An un-named absent Space file narrows
 * nothing. Parsed files are cached by (mtime, size), the {@code AccessPolicyStore.load} shape.
 */
public final class SafetyPolicyFiles {

    private SafetyPolicyFiles() {}

    public static final String FILE = "safety-policy.toon";

    private static final Set<String> TOP = Set.of("mode", "permit", "allow", "deny", "caps", "require_spaces");
    private static final Set<String> PERMIT = Set.of("network", "install_extensions", "advance_state", "rewind_state");
    private static final Set<String> ALLOW = Set.of("roots", "hosts", "connectors", "extensions", "formats");
    private static final Set<String> DENY = Set.of("hosts", "roots");
    private static final Set<String> CAPS = Set.of("max_threads", "max_batch_files", "max_batch_bytes");

    /** One parsed file: the tier plus (server file only) the Spaces whose policy file is required. */
    public record Loaded(SafetyPolicyTier tier, Set<String> requiredSpaces) {}

    private record Stamp(long mtime, long size) {}
    private record Entry(Stamp stamp, Loaded loaded, SafetyPolicyUnreadableException failure) {}

    private static final Map<Path, Entry> CACHE = new ConcurrentHashMap<>();
    /** Coarsest common mtime granularity (FAT 2 s) — a stamp younger than this is not trusted. */
    private static final long RACY_MS = 2_000;

    /**
     * The effective policy of {@code spaceId}: the server tier (the file in {@code serverDir}, or none when
     * {@code serverDir} is null or has no file) folded with the Space tier ({@code <spaceBase>/config/}).
     * {@code defaultRoots} stand in for the server's {@code allow.roots} when the server file does not state
     * them (D3/D4: the operator's declared roots plus the Space's own base).
     */
    public static SafetyPolicyTier effective(Path serverDir, Path spaceBase, String spaceId, List<Path> defaultRoots) {
        return tiers(serverDir, spaceBase, spaceId, defaultRoots).effective();
    }

    /**
     * What {@link #effective} folded, kept apart so the policy can be explained (S7): the server file's tier
     * as stated (before {@code defaultRoots} stand in), the Space file's tier as stated, and the fold.
     * {@code serverFile}/{@code spaceFile} are the paths consulted (null when no directory/base was given);
     * {@code *Present} says whether the file exists.
     */
    public record Tiers(Path serverFile, boolean serverPresent, SafetyPolicyTier server,
                        Path spaceFile, boolean spacePresent, SafetyPolicyTier space, SafetyPolicyTier effective) {}

    /** {@link #effective} with its inputs: the same strict loading, the same refusals. */
    public static Tiers tiers(Path serverDir, Path spaceBase, String spaceId, List<Path> defaultRoots) {
        Loaded server = new Loaded(SafetyPolicyTier.NONE, Set.of());
        Path serverFile = serverDir == null ? null : serverDir.resolve(FILE);
        boolean serverPresent = false;
        if (serverFile != null) {
            Loaded l = load(serverFile, false);
            if (l != null) { server = l; serverPresent = true; }
        }
        SafetyPolicyTier st = server.tier();
        if (st.allowRoots() == null) st = withAllowRoots(st, defaultRoots);

        SafetyPolicyTier space = SafetyPolicyTier.NONE;
        Path spaceFile = spaceBase == null ? null : spaceBase.resolve("config").resolve(FILE);
        boolean spacePresent = false;
        if (spaceFile != null) {
            Loaded l = load(spaceFile, true);
            if (l != null) { space = l.tier(); spacePresent = true; }
            else if (spaceId != null && server.requiredSpaces().contains(spaceId))
                throw new SafetyPolicyUnreadableException(spaceFile.toString(),
                        "the server Safety Policy requires a policy file for Space '" + spaceId + "' and there is none", null);
        }
        return new Tiers(serverFile, serverPresent, server.tier(), spaceFile, spacePresent, space,
                SafetyPolicyTier.fold(st, space));
    }

    /** Loads one file; {@code null} when it does not exist, throws when it exists and is not a valid policy. */
    static Loaded load(Path file, boolean spaceTier) {
        Stamp stamp;
        try {
            if (!Files.exists(file)) { CACHE.remove(file); return null; }
            BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
            stamp = new Stamp(a.lastModifiedTime().toMillis(), a.size());
        } catch (IOException | RuntimeException e) {
            throw new SafetyPolicyUnreadableException(file.toString(), "cannot stat: " + e, e);
        }
        Entry cached = CACHE.get(file);
        // A file modified within RACY_MS may be rewritten again inside the same mtime tick at the same
        // size (filesystems keep mtime to 1-2 s), so an equal stamp proves nothing yet: re-parse it.
        boolean racy = System.currentTimeMillis() - stamp.mtime() < RACY_MS;
        if (cached != null && !racy && cached.stamp().equals(stamp)) {
            if (cached.failure() != null) throw cached.failure();
            return cached.loaded();
        }
        try {
            Loaded l = parse(file, Files.readString(file, StandardCharsets.UTF_8), spaceTier);
            CACHE.put(file, new Entry(stamp, l, null));
            return l;
        } catch (SafetyPolicyUnreadableException e) {
            CACHE.put(file, new Entry(stamp, null, e));
            throw e;
        } catch (IOException | RuntimeException e) {
            var ex = new SafetyPolicyUnreadableException(file.toString(), String.valueOf(e.getMessage()), e);
            CACHE.put(file, new Entry(stamp, null, ex));
            throw ex;
        }
    }

    /** Strict parse of policy text. */
    static Loaded parse(Path file, String text, boolean spaceTier) {
        String f = file.toString();
        Map<String, Object> m = ToonHelper.decode(text);
        unknown(f, "", m, TOP);
        SafetyPolicyTier.Mode mode = null;
        if (m.containsKey("mode")) {
            if (spaceTier) throw bad(f, "'mode' is legal in the server file only");
            String v = String.valueOf(m.get("mode")).trim().toLowerCase(Locale.ROOT);
            mode = switch (v) {
                case "enforce" -> SafetyPolicyTier.Mode.ENFORCE;
                case "audit" -> SafetyPolicyTier.Mode.AUDIT;
                default -> throw bad(f, "mode must be enforce or audit, not '" + v + "'");
            };
        }
        Set<String> required = Set.of();
        if (m.containsKey("require_spaces")) {
            if (spaceTier) throw bad(f, "'require_spaces' is legal in the server file only");
            required = new LinkedHashSet<>(strings(f, "require_spaces", m.get("require_spaces")));
        }
        Map<String, Object> permit = section(f, m, "permit", PERMIT);
        Map<String, Object> allow = section(f, m, "allow", ALLOW);
        Map<String, Object> deny = section(f, m, "deny", DENY);
        Map<String, Object> caps = section(f, m, "caps", CAPS);

        SafetyPolicyTier tier = new SafetyPolicyTier(mode,
                bool(f, "permit.network", permit.get("network")),
                bool(f, "permit.install_extensions", permit.get("install_extensions")),
                bool(f, "permit.advance_state", permit.get("advance_state")),
                bool(f, "permit.rewind_state", permit.get("rewind_state")),
                paths(f, "allow.roots", allow),
                hosts(f, "allow.hosts", allow),
                strSet(f, "allow.connectors", allow, "connectors"),
                strSet(f, "allow.extensions", allow, "extensions"),
                strSet(f, "allow.formats", allow, "formats"),
                paths(f, "deny.roots", deny),
                hosts(f, "deny.hosts", deny),
                posInt(f, "caps.max_threads", caps.get("max_threads")),
                posInt(f, "caps.max_batch_files", caps.get("max_batch_files")),
                posLong(f, "caps.max_batch_bytes", caps.get("max_batch_bytes")));
        return new Loaded(tier, Set.copyOf(required));
    }

    // ---- strict readers -------------------------------------------------------------------------------

    private static SafetyPolicyUnreadableException bad(String file, String reason) {
        return new SafetyPolicyUnreadableException(file, reason, null);
    }

    private static void unknown(String f, String where, Map<String, Object> m, Set<String> known) {
        for (String k : m.keySet())
            if (!known.contains(k)) throw bad(f, "unknown key '" + where + k + "' (known: " + new TreeSet<>(known) + ")");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(String f, Map<String, Object> m, String key, Set<String> known) {
        Object v = m.get(key);
        if (v == null) return Map.of();
        if (!(v instanceof Map<?, ?> sec)) throw bad(f, "'" + key + "' must be a block");
        unknown(f, key + ".", (Map<String, Object>) sec, known);
        return (Map<String, Object>) sec;
    }

    private static Boolean bool(String f, String key, Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        throw bad(f, key + " must be true or false");
    }

    private static List<String> strings(String f, String key, Object v) {
        if (!(v instanceof List<?> l)) throw bad(f, key + " must be a list (e.g. hosts[2]: a,b)");
        List<String> out = new ArrayList<>();
        for (Object o : l) {
            if (o == null || o instanceof Map || o instanceof List || String.valueOf(o).isBlank())
                throw bad(f, key + " holds a non-text or blank entry");
            out.add(String.valueOf(o).trim());
        }
        return out;
    }

    private static Set<String> strSet(String f, String key, Map<String, Object> sec, String name) {
        return sec.containsKey(name) ? new LinkedHashSet<>(strings(f, key, sec.get(name))) : null;
    }

    private static List<Path> paths(String f, String key, Map<String, Object> sec) {
        String name = key.substring(key.indexOf('.') + 1);
        if (!sec.containsKey(name)) return null;
        List<Path> out = new ArrayList<>();
        for (String s : strings(f, key, sec.get(name))) {
            Path p = Path.of(s);
            if (!p.isAbsolute()) throw bad(f, key + " entry '" + s + "' is not an absolute path");
            out.add(p);
        }
        return out;
    }

    private static List<HostPattern> hosts(String f, String key, Map<String, Object> sec) {
        String name = key.substring(key.indexOf('.') + 1);
        if (!sec.containsKey(name)) return null;
        List<HostPattern> out = new ArrayList<>();
        for (String s : strings(f, key, sec.get(name))) {
            try {
                out.add(HostPattern.parse(s));
            } catch (IllegalArgumentException e) {
                throw bad(f, key + ": " + e.getMessage());
            }
        }
        return out;
    }

    private static Integer posInt(String f, String key, Object v) {
        Long l = posLong(f, key, v);
        if (l == null) return null;
        if (l > Integer.MAX_VALUE) throw bad(f, key + " is too large");
        return l.intValue();
    }

    private static Long posLong(String f, String key, Object v) {
        if (v == null) return null;
        if (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()) && n.longValue() > 0) return n.longValue();
        throw bad(f, key + " must be a positive whole number");
    }

    private static SafetyPolicyTier withAllowRoots(SafetyPolicyTier t, List<Path> roots) {
        return new SafetyPolicyTier(t.mode(), t.network(), t.installExtensions(), t.advanceState(), t.rewindState(),
                roots, t.allowHosts(), t.allowConnectors(), t.allowExtensions(), t.allowFormats(),
                t.denyRoots(), t.denyHosts(), t.maxThreads(), t.maxBatchFiles(), t.maxBatchBytes());
    }
}
