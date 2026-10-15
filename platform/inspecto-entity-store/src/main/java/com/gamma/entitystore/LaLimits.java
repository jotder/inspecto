package com.gamma.entitystore;

import com.gamma.util.AtomicFiles;
import com.gamma.util.ToonHelper;
import dev.toonformat.jtoon.JToon;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads and writes the {@link LaLimit} catalogue ({@code LA-CONFIG-LIMITS-1}, plan §6.1).
 *
 * <p><b>Storage.</b> {@link LaLimit.Layer#SPACE} items live in the Space's {@code link-analysis.toon}
 * ({@link LinkAnalysisSettings#FILE}) at their dotted {@link LaLimit#path}; {@link LaLimit.Layer#INSTALL} items in
 * {@link #INSTALL_FILE} in the server-wide document home, whose path the host installs at boot with
 * {@link #installDocPath(Path)} (the {@code DuckDbUtil.installMemoryLimit} pattern; resolved like
 * {@code SchedulerRoutes}' server-wide document: {@code -Dsystem.config.dir}, then the spaces container root, then the
 * sole Space's write root).
 *
 * <p><b>Reading</b> ({@link #forSpace}): the stated value, else the shipped default; a stored value the item does not
 * {@link LaLimit#accepts accept} reads as the default, so a hand edit cannot pass the range. A missing or unreadable
 * document reads as all defaults (settings never fail a read). The answer is cached on the documents' CONTENT, not
 * their mtime: a write within the filesystem's timestamp resolution is never missed.
 *
 * <p><b>Writing</b> is read-modify-write over the raw TOON map: only the named keys change, every other key the
 * document holds (an Entity Type list, a key a newer build wrote) is kept. {@link LinkAnalysisSettings#write} does
 * the same for the keys it models; both hold {@link #WRITE_LOCK}, so two writers in one process cannot interleave.
 */
public final class LaLimits {

    /** The server-wide document of the {@link LaLimit.Layer#INSTALL} items. */
    public static final String INSTALL_FILE = "la-limits.toon";

    /** Held by every writer of {@code link-analysis.toon} and {@link #INSTALL_FILE} for its read-modify-write. */
    static final Object WRITE_LOCK = new Object();

    private static volatile Path installDocPath;

    private record Cached(byte[] space, Path install, byte[] installBytes, LaLimitValues values) {}

    private static final int CACHE_MAX = 256;
    private static final Map<Path, Cached> CACHE = new ConcurrentHashMap<>();
    private static final Path NO_ROOT = Path.of("");

    private LaLimits() {}

    /** Install the server-wide document path (the host does this at boot); {@code null} = no home, every INSTALL item at its default. */
    public static void installDocPath(Path path) {
        installDocPath = path;
    }

    /** The installed server-wide document path, or {@code null}. */
    public static Path installDocPath() {
        return installDocPath;
    }

    /** The limits in force for the Space whose config root is {@code writeRoot} ({@code null} = no Space document: SPACE items at their defaults). */
    public static LaLimitValues forSpace(Path writeRoot) {
        Path spaceDoc = writeRoot == null ? null : writeRoot.resolve(LinkAnalysisSettings.FILE);
        Path install = installDocPath;
        byte[] space = bytes(spaceDoc);
        byte[] inst = bytes(install);
        Path key = writeRoot == null ? NO_ROOT : writeRoot.toAbsolutePath().normalize();
        Cached c = CACHE.get(key);
        if (c != null && Arrays.equals(c.space, space) && java.util.Objects.equals(c.install, install)
                && Arrays.equals(c.installBytes, inst))
            return c.values;
        LaLimitValues v = read(decodeOrEmpty(space), decodeOrEmpty(inst));
        if (CACHE.size() >= CACHE_MAX) CACHE.clear();
        CACHE.put(key, new Cached(space, install, inst, v));
        return v;
    }

    /** The limits stated by a Space document and a server-wide document (raw TOON maps); each item reads from its own layer's map. */
    public static LaLimitValues read(Map<String, Object> spaceRaw, Map<String, Object> installRaw) {
        EnumMap<LaLimit, Number> stated = stated(spaceRaw, installRaw);
        // The Draft lifecycle rule LinkAnalysisSettings.read applies: an expiry not longer than the hibernation reads as
        // NO drafts block at all (a Draft must sleep before it can die), so the three items read as their defaults.
        if (!draftsOrdered(new LaLimitValues(stated))) {
            stated.remove(LaLimit.DRAFTS_MAX_OPEN);
            stated.remove(LaLimit.DRAFTS_HIBERNATE_AFTER_MINUTES);
            stated.remove(LaLimit.DRAFTS_EXPIRE_AFTER_DAYS);
        }
        return new LaLimitValues(stated);
    }

    /**
     * Why a Space document breaks a rule BETWEEN items that the settings route also enforces, or {@code null}: the
     * Draft expiry must be longer than the hibernation, and the default embed lifetime must not exceed the longest one.
     * A write route checks the document it would leave behind with this before writing.
     */
    public static String consistencyProblem(Map<String, Object> spaceRaw) {
        LaLimitValues v = new LaLimitValues(stated(spaceRaw, Map.of()));
        if (!draftsOrdered(v))
            return "'drafts.expire_after_days' (" + v.get(LaLimit.DRAFTS_EXPIRE_AFTER_DAYS) + " days in force) must be longer than "
                    + "'drafts.hibernate_after_minutes' (" + v.get(LaLimit.DRAFTS_HIBERNATE_AFTER_MINUTES) + " minutes in force)";
        if (v.stated(LaLimit.EMBED_DEFAULT_TTL_HOURS) != null && v.get(LaLimit.EMBED_DEFAULT_TTL_HOURS) > v.get(LaLimit.EMBED_MAX_TTL_HOURS))
            return "'embed.default_ttl_hours' (" + v.get(LaLimit.EMBED_DEFAULT_TTL_HOURS) + ") must not exceed 'embed.max_ttl_hours' in force ("
                    + v.get(LaLimit.EMBED_MAX_TTL_HOURS) + ")";
        return null;
    }

    private static boolean draftsOrdered(LaLimitValues v) {
        return v.get(LaLimit.DRAFTS_EXPIRE_AFTER_DAYS) * 24 * 60 > v.get(LaLimit.DRAFTS_HIBERNATE_AFTER_MINUTES);
    }

    private static EnumMap<LaLimit, Number> stated(Map<String, Object> spaceRaw, Map<String, Object> installRaw) {
        EnumMap<LaLimit, Number> stated = new EnumMap<>(LaLimit.class);
        for (LaLimit l : LaLimit.values()) {
            Number v = number(at(l.layer() == LaLimit.Layer.SPACE ? spaceRaw : installRaw, l.path()));
            if (v != null && l.accepts(v.doubleValue())) stated.put(l, l.decimal() ? (Number) v.doubleValue() : (Number) v.longValue());
        }
        return stated;
    }

    /**
     * Apply {@code changes} to the Space document under {@code writeRoot} (read-modify-write; a {@code null} value
     * removes the key = inherit). Values must already be {@link LaLimit#parse checked}.
     */
    public static void writeSpace(Path writeRoot, Map<LaLimit, Number> changes) throws IOException {
        write(writeRoot.resolve(LinkAnalysisSettings.FILE), changes, ".link-analysis-");
    }

    /** {@link #writeSpace} for the server-wide document at {@code doc}. */
    public static void writeInstall(Path doc, Map<LaLimit, Number> changes) throws IOException {
        Path parent = doc.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        write(doc, changes, ".la-limits-");
    }

    private static void write(Path doc, Map<LaLimit, Number> changes, String tempPrefix) throws IOException {
        synchronized (WRITE_LOCK) {
            Map<String, Object> raw = loadRaw(doc);
            AtomicFiles.write(doc, JToon.encode(apply(raw, changes)).getBytes(StandardCharsets.UTF_8), tempPrefix);
        }
    }

    /**
     * The raw TOON map of {@code doc}, deep-copied and mutable; a missing document is an empty map.
     *
     * @throws IOException when the document exists but does not decode: a write must never silently replace a hand
     *                     edit it cannot read
     */
    public static Map<String, Object> loadRaw(Path doc) throws IOException {
        try {
            return copy(ToonHelper.decode(Files.readString(doc, StandardCharsets.UTF_8)));
        } catch (NoSuchFileException e) {
            return new LinkedHashMap<>();
        } catch (RuntimeException e) {   // a refused decode, or a document whose top level is not a map
            throw new IOException(doc.getFileName() + " does not parse: " + e.getMessage(), e);
        }
    }

    /** {@code raw} with {@code changes} applied (a copy): a value is set at its dotted path, {@code null} removes it and any parent left empty. */
    public static Map<String, Object> apply(Map<String, Object> raw, Map<LaLimit, Number> changes) {
        Map<String, Object> out = copy(raw);
        for (Map.Entry<LaLimit, Number> c : changes.entrySet()) {
            List<String> parts = List.of(c.getKey().path().split("\\."));
            if (c.getValue() == null) remove(out, parts);
            else put(out, parts, c.getKey().decimal() ? (Object) c.getValue().doubleValue() : (Object) c.getValue().longValue());
        }
        return out;
    }

    /** The raw value at a dotted path, or {@code null}. */
    static Object at(Map<String, Object> raw, String path) {
        Object cur = raw;
        for (String p : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(p);
        }
        return cur;
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> m, List<String> parts, Object v) {
        Map<String, Object> cur = m;
        for (String p : parts.subList(0, parts.size() - 1)) {
            Object next = cur.get(p);
            if (!(next instanceof Map<?, ?>)) {   // absent, or an empty key that decoded as a scalar: becomes a block
                next = new LinkedHashMap<String, Object>();
                cur.put(p, next);
            }
            cur = (Map<String, Object>) next;
        }
        cur.put(parts.get(parts.size() - 1), v);
    }

    @SuppressWarnings("unchecked")
    private static void remove(Map<String, Object> m, List<String> parts) {
        if (parts.size() == 1) {
            m.remove(parts.get(0));
            return;
        }
        if (!(m.get(parts.get(0)) instanceof Map<?, ?> child)) return;
        remove((Map<String, Object>) child, parts.subList(1, parts.size()));
        if (child.isEmpty()) m.remove(parts.get(0));
    }

    /** A mutable deep copy (nested maps copied; lists and scalars shared, never mutated here). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> copy(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) return out;
        for (Map.Entry<String, Object> e : raw.entrySet())
            out.put(e.getKey(), e.getValue() instanceof Map<?, ?> m ? copy((Map<String, Object>) m) : e.getValue());
        return out;
    }

    /** A stored number: a TOON number, or a string that parses as one; anything else is {@code null}. */
    private static Number number(Object raw) {
        if (raw instanceof Number n) return n;
        if (!(raw instanceof String s) || s.isBlank()) return null;
        try {
            return new BigDecimal(s.trim()).doubleValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static byte[] bytes(Path p) {
        if (p == null) return null;
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            return null;
        }
    }

    private static Map<String, Object> decodeOrEmpty(byte[] b) {
        if (b == null) return Map.of();
        try {
            return ToonHelper.decode(new String(b, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return Map.of();
        }
    }
}
