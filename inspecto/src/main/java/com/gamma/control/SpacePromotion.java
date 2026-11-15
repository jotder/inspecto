package com.gamma.control;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.auth.secrets.SecretResolver;
import com.gamma.config.io.ConfigCodec;
import com.gamma.job.JobConfig;
import com.gamma.service.BundleExporter;
import com.gamma.service.BundleImporter;
import com.gamma.service.ImportPaths;
import com.gamma.service.ReservedConfigPaths;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SE-6b — promotion of a whole-Space bundle into an EXISTING Space as a previewed change set
 * ({@code docs/okf/product/module/config/spec-space-promotion.md}). Pure computation: what the bundle, with its
 * per-environment parameters bound, would add, change or remove in the target's {@code config/} tree. The routes
 * ({@link SpacePromotionRoutes}) gate it, hold it and write it.
 *
 * <p><b>What can be promoted</b> is exactly what an import may write ({@link ImportPaths#refusal}, the ONE import
 * judge: segment rules, shape allowlist, {@link ReservedConfigPaths}, real path), less the kinds written only through
 * their own route ({@link ImportCapabilityGuard#DEDICATED_ONLY}, {@link ImportCapabilityGuard#GOVERNANCE_ONLY}).
 * Everything else the bundle carries is listed as excluded with its reason and never compared or written, so
 * server-written state, identity and settings documents, {@code .history/} and {@code audit/} never move. A target
 * file outside that set is not a promotion object at all (never removed, never listed).
 *
 * <p><b>Equality</b> — a {@code .toon} file compares by its decoded content less the stamps the server writes
 * ({@code createdAt}, {@code updatedAt}, {@code lastResult}, {@code lastSimulation}, and a Job's author keys);
 * anything else by bytes. A connection is compared in its exported form on both sides
 * ({@link BundleExporter#exportableBytes}), so a secret is never a difference; a target connection holding a literal
 * secret is never changed or removed (excluded, "change it through /connections").
 *
 * <p><b>Parameters</b> — every {@code ${NAME}} ({@code NAME} upper snake case) in a promoted file is a placeholder;
 * {@code SPACE} is always the target Space's id. A connection's secret reference (the whole value of a secret field,
 * resolved at run time by {@link SecretResolver}) is not a placeholder. A placeholder with no value refuses the
 * whole change set ({@link MissingParameters}), as does a value that makes a {@code .toon} file unreadable
 * ({@link BrokenByBinding}) or changes its shape ({@link ReshapedByBinding}).
 */
public final class SpacePromotion {

    /** The kill switch: {@code -Dspace.promotion=off} answers both routes 503. Default on (an explicit operator act). */
    public static final String PROPERTY = "space.promotion";
    /** The Pending Change kind of a held promotion; its name is the target Space's id. */
    static final String KIND = "space-promotion";
    static final String SPACE_PARAM = "SPACE";
    static final Pattern PARAM_NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    static final int MAX_PARAM_VALUE = 1024;
    private static final Pattern TEXT = Pattern.compile("(?i)\\.(toon|csv|asn|asn1|sql)$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]{0,63})}");
    /** Top-level keys a server stamps at write or evaluation time ({@code PendingChanges.WRITE_TIME_STAMPS}). */
    private static final Set<String> SERVER_STAMPS = Set.of("createdAt", "updatedAt", "lastResult", "lastSimulation");
    /** What {@code DecisionRuleGuard.prepare} stamps on every Decision Rule a write lands. */
    private static final Set<String> DECISION_RULE_STAMPS = Set.of("createdBy", "updatedBy",
            DecisionRuleGuard.MAKERS, DecisionRuleGuard.RESTORED_MAKERS);

    private SpacePromotion() {}

    static boolean enabled() {
        return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on").trim());
    }

    /** One promotable file: {@code incoming} is the bound bundle content (null for a remove), {@code current} the
     *  target's in its compared form (null for an add). */
    record Change(String path, String kind, String action, byte[] incoming, byte[] current) {}

    /** A path that is not promoted, and why. */
    record Excluded(String path, String reason) {}

    /** The change set, sorted by path, with the hash an apply must quote. */
    record ChangeSet(List<Change> changes, List<Excluded> excluded, List<String> parametersUsed, String hash) {
        List<Change> of(String action) {
            return changes.stream().filter(c -> c.action().equals(action)).toList();
        }
    }

    /** Placeholders the parameters leave without a value. */
    static final class MissingParameters extends RuntimeException {
        final List<String> names;

        MissingParameters(List<String> names) {
            super("no value for parameter(s) " + names, null, false, false);
            this.names = names;
        }
    }

    /** {@code .toon} files that parsed before their parameters were bound and do not after (a value with a comma in
     *  an array row, say). */
    static final class BrokenByBinding extends RuntimeException {
        final List<String> paths;

        BrokenByBinding(List<String> paths) {
            super("binding the parameters breaks " + paths, null, false, false);
            this.paths = paths;
        }
    }

    /** {@code .toon} files whose bound values change the kind of a value the source states (text into a list or an
     *  object, a list item into an object) or rename a key it names, with the parameters that do it (SPACE-PROMOTION-
     *  PARAM-TYPES-1): a value fills a value, it never adds structure. */
    static final class ReshapedByBinding extends RuntimeException {
        final Map<String, List<String>> byPath;

        ReshapedByBinding(Map<String, List<String>> byPath) {
            super("binding the parameters changes the shape of " + byPath.keySet(), null, false, false);
            this.byPath = byPath;
        }
    }

    /** Bundle paths that name, as the filesystem resolves them, a target file or another bundle path spelled otherwise
     *  (SPACE-PROMOTION-CASE-PAIRING-1): on a case-insensitive filesystem both are one file. */
    static final class CaseCollisions extends RuntimeException {
        final Map<String, String> byPath;

        CaseCollisions(Map<String, String> byPath) {
            super("bundle paths spelled in another case than " + byPath, null, false, false);
            this.byPath = byPath;
        }
    }

    /**
     * The change set of promoting {@code bundle} (a whole-Space bundle) into the Space whose config root is
     * {@code configRoot}, with {@code params} bound and {@code SPACE} = {@code spaceId}. Writes nothing.
     *
     * @throws MissingParameters when a promoted file names a placeholder with no value
     */
    static ChangeSet compute(Path configRoot, BundleImporter.Bundle bundle, Map<String, String> params, String spaceId)
            throws IOException {
        Path root = configRoot.toAbsolutePath().normalize();
        Map<String, String> values = new LinkedHashMap<>(params);
        values.put(SPACE_PARAM, spaceId);

        Map<String, byte[]> incoming = new TreeMap<>();
        Map<String, Set<String>> missingBy = new LinkedHashMap<>();
        Map<String, Set<String>> usedBy = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : bundle.configEntries().entrySet()) {
            String path = e.getKey().replace('\\', '/');
            Set<String> missing = new TreeSet<>(), used = new TreeSet<>();
            incoming.put(path, bind(path, e.getValue(), values, missing, used));
            missingBy.put(path, missing);
            usedBy.put(path, used);
        }
        Map<String, byte[]> target = targetFiles(root);
        Set<String> referenced = new TreeSet<>(ImportPaths.referencedEntries(incoming));
        referenced.addAll(ImportPaths.referencedEntries(target));
        Map<String, String> collisions = caseCollisions(root, incoming.keySet(), target.keySet(), referenced);
        if (!collisions.isEmpty()) throw new CaseCollisions(collisions);

        List<Change> changes = new ArrayList<>();
        List<Excluded> excluded = new ArrayList<>();
        Set<String> missing = new TreeSet<>(), used = new TreeSet<>(), broken = new TreeSet<>();
        Map<String, List<String>> reshaped = new TreeMap<>();
        for (Map.Entry<String, byte[]> e : incoming.entrySet()) {
            String path = e.getKey();
            String why = refusal(root, path, referenced);
            if (why != null) {
                excluded.add(new Excluded(path, why));
                continue;
            }
            missing.addAll(missingBy.get(path));
            used.addAll(usedBy.get(path));
            if (missingBy.get(path).isEmpty() && brokenByBinding(path, bundle.configEntries(), e.getValue()))
                broken.add(path);
            else if (missingBy.get(path).isEmpty()) {
                List<String> by = reshapedBy(path, bundle.configEntries().get(path), e.getValue(), values, usedBy.get(path));
                if (!by.isEmpty()) reshaped.put(path, by);
            }
            if (isConnection(path) && holdsLiteralSecret(e.getValue())) {
                excluded.add(new Excluded(path, "the bundle's connection carries a literal secret (or does not parse), "
                        + "which a promotion never writes — export it with secrets as references"));
                continue;
            }
            byte[] cur = target.get(path);
            if (cur != null && isConnection(path)) {
                if (holdsLiteralSecret(cur)) {
                    excluded.add(new Excluded(path, "this Space's connection holds a literal secret, which a promotion "
                            + "never touches — change it through /connections"));
                    continue;
                }
                cur = exported(root.resolve(path));
                if (cur == null) {
                    excluded.add(new Excluded(path, "this Space's connection does not parse, so its secrets cannot be "
                            + "proven absent — fix it through /connections"));
                    continue;
                }
            }
            String action = cur == null ? "add" : equivalent(path, cur, e.getValue()) ? "same" : "change";
            changes.add(new Change(path, kindOf(path), action, e.getValue(), cur));
        }
        for (Map.Entry<String, byte[]> t : target.entrySet()) {
            String path = t.getKey();
            if (incoming.containsKey(path) || refusal(root, path, referenced) != null) continue;
            byte[] cur = t.getValue();
            if (isConnection(path)) {
                cur = holdsLiteralSecret(cur) ? null : exported(root.resolve(path));
                if (cur == null) {
                    excluded.add(new Excluded(path, "this Space's connection holds a literal secret (or does not "
                            + "parse), which a promotion never removes — remove it through /connections"));
                    continue;
                }
            }
            changes.add(new Change(path, kindOf(path), "remove", null, cur));
        }
        if (!missing.isEmpty()) throw new MissingParameters(List.copyOf(missing));
        if (!broken.isEmpty()) throw new BrokenByBinding(List.copyOf(broken));
        if (!reshaped.isEmpty()) throw new ReshapedByBinding(reshaped);
        changes.sort((a, b) -> a.path().compareTo(b.path()));
        return new ChangeSet(List.copyOf(changes), List.copyOf(excluded), List.copyOf(used), hash(changes));
    }

    /**
     * Each promotable bundle path whose resolved form ({@link #resolved}) is that of a target file or an earlier bundle
     * path spelled otherwise, with that path. The change set pairs files by spelling, so such a pair would be an add and a
     * remove of one file on a case-insensitive filesystem (the remove deleting what the add wrote), and two files on a
     * case-sensitive one: refused rather than paired, since which spelling should win is the operator's call.
     */
    private static Map<String, String> caseCollisions(Path root, Set<String> incoming, Set<String> target,
                                                      Set<String> referenced) {
        Map<String, String> seen = new HashMap<>();
        for (String t : target) seen.putIfAbsent(resolved(t), t);
        Map<String, String> out = new TreeMap<>();
        for (String p : incoming) {
            if (refusal(root, p, referenced) != null) continue;
            String other = seen.putIfAbsent(resolved(p), p);
            if (other != null && !other.equals(p)) out.put(p, other);
        }
        return out;
    }

    /** Why {@code path} is not a promotion object, or {@code null} when it is. */
    private static String refusal(Path root, String path, Set<String> referenced) {
        String why = ImportPaths.refusal(root, path, referenced);
        if (why != null) return why;
        String kind = PendingChanges.kindOfConfigPath(resolved(path));
        if (kind != null && (ImportCapabilityGuard.DEDICATED_ONLY.containsKey(kind)
                || ImportCapabilityGuard.GOVERNANCE_ONLY.containsKey(kind)))
            return "a '" + kind + "' is written through its own route only";
        return null;
    }

    /**
     * Every file of the target's config tree a promotion could compare, keyed by its {@code /}-separated relative
     * path: reserved paths ({@link ReservedConfigPaths}) and names the import segment rules refuse (a leading dot,
     * so {@code .history/} and key files) are never read.
     */
    private static Map<String, byte[]> targetFiles(Path root) throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        if (!Files.isDirectory(root)) return out;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                if (dir.equals(root)) return FileVisitResult.CONTINUE;
                String rel = rel(root, dir);
                return ImportPaths.segmentRefusal(rel) != null || ReservedConfigPaths.reserved(rel + "/")
                        || rel.endsWith(PendingChanges.SECRETS_SUFFIX) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException {
                String rel = rel(root, file);
                // only the extensions an import may ever write (ImportPaths): nothing else can be a promotion object
                if (a.isRegularFile() && TEXT.matcher(rel).find() && ImportPaths.segmentRefusal(rel) == null
                        && !ReservedConfigPaths.reserved(rel))
                    out.put(rel, Files.readAllBytes(file));
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }

    private static String rel(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    /** {@code bytes} with every placeholder bound; unbound ones stay and land in {@code missing}. */
    static byte[] bind(String path, byte[] bytes, Map<String, String> values, Set<String> missing, Set<String> used) {
        String text = utf8(bytes);
        if (text == null || !text.contains("${")) return bytes;
        Set<String> secretRefs = isConnection(path) ? secretReferences(text) : Set.of();
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        boolean changed = false;
        while (m.find()) {
            String token = m.group(), name = m.group(1);
            String v = secretRefs.contains(token) ? null : values.get(name);
            if (v == null && !secretRefs.contains(token)) missing.add(name);
            if (v != null) {
                used.add(name);
                changed = true;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? token : v));
        }
        m.appendTail(sb);
        return changed ? sb.toString().getBytes(StandardCharsets.UTF_8) : bytes;
    }

    /** Whether binding turned a parseable {@code .toon} entry into one that no longer parses. */
    private static boolean brokenByBinding(String path, Map<String, byte[]> raw, byte[] bound) {
        if (!isToon(path)) return false;
        byte[] before = raw.get(path);
        if (before == null || Arrays.equals(before, bound) || !parses(before)) return false;
        return !parses(bound);
    }

    private static boolean parses(byte[] toon) {
        return decoded(toon) != null;
    }

    private static Map<String, Object> decoded(byte[] toon) {
        try {
            return ConfigCodec.toMap(new String(toon, StandardCharsets.UTF_8));
        } catch (RuntimeException unparseable) {
            return null;
        }
    }

    /**
     * The parameters whose values change the shape of {@code path} (a {@code .toon} file that parses before and after
     * binding): each is bound alone to find it, and when only their combination does it, every parameter it binds.
     * Empty when the shape is kept.
     */
    private static List<String> reshapedBy(String path, byte[] before, byte[] bound, Map<String, String> values,
                                           Set<String> used) {
        if (!isToon(path) || before == null || Arrays.equals(before, bound)) return List.of();
        Map<String, Object> source = decoded(before), result = decoded(bound);
        if (source == null || result == null || sameShape(source, result)) return List.of();
        List<String> by = new ArrayList<>();
        for (String name : used) {
            Map<String, Object> one = decoded(bind(path, before, Map.of(name, values.get(name)), new TreeSet<>(), new TreeSet<>()));
            if (one != null && !sameShape(source, one)) by.add(name);
        }
        return by.isEmpty() ? List.copyOf(used) : by;
    }

    /**
     * Whether {@code bound} keeps the shape of {@code source}: an object where it has an object, with the same keys
     * (a key holding a placeholder may be named by its value), a list where it has a list, as long, and a scalar where it
     * has a scalar — of any type, so {@code 30} fills text as a number.
     */
    static boolean sameShape(Object source, Object bound) {
        if (source instanceof Map<?, ?> s) {
            if (!(bound instanceof Map<?, ?> b) || s.size() != b.size()) return false;
            Iterator<? extends Map.Entry<?, ?>> bi = b.entrySet().iterator();
            for (Map.Entry<?, ?> se : s.entrySet()) {
                Map.Entry<?, ?> be = bi.next();
                String key = String.valueOf(se.getKey());
                if (!key.contains("${") && !key.equals(String.valueOf(be.getKey()))) return false;
                if (!sameShape(se.getValue(), be.getValue())) return false;
            }
            return true;
        }
        if (source instanceof List<?> s) {
            if (!(bound instanceof List<?> b) || s.size() != b.size()) return false;
            for (int i = 0; i < s.size(); i++) if (!sameShape(s.get(i), b.get(i))) return false;
            return true;
        }
        return !(bound instanceof Map<?, ?>) && !(bound instanceof List<?>);
    }

    /** {@code bytes} as strict UTF-8, or {@code null} when they are not text. */
    private static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException binary) {
            return null;
        }
    }

    /**
     * {@code path} as the filesystem resolves it ({@link ImportCapabilityGuard#normalizedPath}: lower case, no trailing
     * dots or spaces), which is how the import judges a file's shape and kind — so every kind test here reads
     * {@code connections/x_connection.TOON} or {@code Registry/connections/x.toon} as the connection it is
     * (SPACE-PROMOTION-EXT-CASE-1). The path itself is kept as written.
     */
    private static String resolved(String path) {
        return ImportCapabilityGuard.normalizedPath(path);
    }

    private static boolean isToon(String path) {
        return resolved(path).endsWith(".toon");
    }

    static boolean isConnection(String path) {
        String p = resolved(path);
        return p.endsWith("_connection.toon") || p.startsWith("registry/connections/");
    }

    /**
     * Whether a loader reads {@code path} as a connection, so writing or removing it changes the live registry. The loaders
     * match a name as spelled ({@code ServiceBootstrap.resolveBySuffix}), so {@code x_connection.TOON} is judged as a
     * connection ({@link #isConnection}) but never goes live (SPACE-PROMOTION-CASE-PAIRING-1).
     */
    static boolean liveConnection(String path) {
        return path.endsWith("_connection.toon") || path.startsWith("registry/connections/");
    }

    /** Whether a loader reads {@code path} as a Job ({@code *_job.toon} as spelled, outside {@code registry/}), so
     *  removing it stops one. */
    static boolean liveJob(String path) {
        return path.endsWith("_job.toon") && !path.startsWith("registry/");
    }

    /** Whether {@code path} is a {@code *_pipeline.toon}, read as {@link #resolved} reads it. */
    static boolean isPipelineFile(String path) {
        return resolved(path).endsWith("_pipeline.toon");
    }

    /** The decoded profile of a connection file (wrapped in {@code connection:} or flat), or {@code null}. */
    @SuppressWarnings("unchecked")
    private static ConnectionProfile profile(byte[] bytes) {
        try {
            Map<String, Object> doc = ConfigCodec.toMap(new String(bytes, StandardCharsets.UTF_8));
            Map<String, Object> block = doc.get("connection") instanceof Map<?, ?> c ? (Map<String, Object>) c : doc;
            return ConnectionProfile.fromMap(block);
        } catch (RuntimeException unparseable) {
            return null;
        }
    }

    /** The {@code ${…}} references among a connection's secret fields — run-time secrets, never placeholders. */
    private static Set<String> secretReferences(String text) {
        ConnectionProfile p = profile(text.getBytes(StandardCharsets.UTF_8));
        Set<String> out = new TreeSet<>();
        if (p == null) return out;
        for (String s : secrets(p)) if (SecretResolver.isReference(s)) out.add(s);
        return out;
    }

    private static List<String> secrets(ConnectionProfile p) {
        List<String> out = new ArrayList<>();
        out.add(p.password());
        if (p.tunnel() != null) out.add(p.tunnel().password());
        if (p.proxy() != null) out.add(p.proxy().password());
        if (p.options() != null)
            p.options().forEach((k, v) -> { if (ConnectionProfile.isSecretKey(k)) out.add(v); });
        out.removeIf(s -> s == null || s.isEmpty());
        return out;
    }

    /** The bundle's connection files that carry a literal secret (or do not parse), wherever they sit: never written,
     *  but a held apply would store them in its Pending Change's recorded request. */
    static List<String> literalSecretConnections(BundleImporter.Bundle bundle) {
        List<String> out = new ArrayList<>();
        bundle.configEntries().forEach((p, bytes) -> {
            String path = p.replace('\\', '/');
            if (isConnection(path) && holdsLiteralSecret(bytes)) out.add(path);
        });
        return out;
    }

    /** Whether a connection file holds a secret that is not a {@code ${…}} reference; an unreadable one fails closed. */
    static boolean holdsLiteralSecret(byte[] bytes) {
        ConnectionProfile p = profile(bytes);
        return p == null || secrets(p).stream().anyMatch(s -> !SecretResolver.isReference(s));
    }

    private static byte[] exported(Path file) {
        try {
            return BundleExporter.exportableBytes(file);
        } catch (IOException unparseable) {
            return null;
        }
    }

    /** Equal bytes, or for {@code .toon} equal decoded content less the server's stamps. */
    static boolean equivalent(String path, byte[] a, byte[] b) {
        if (Arrays.equals(a, b)) return true;
        if (!isToon(path)) return false;
        try {
            String kind = PendingChanges.kindOfConfigPath(resolved(path));
            return unstamped(ConfigCodec.toMap(new String(a, StandardCharsets.UTF_8)), kind)
                    .equals(unstamped(ConfigCodec.toMap(new String(b, StandardCharsets.UTF_8)), kind));
        } catch (RuntimeException unparseable) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> unstamped(Map<String, Object> doc, String kind) {
        Map<String, Object> out = new LinkedHashMap<>(doc);
        SERVER_STAMPS.forEach(out::remove);
        if (DecisionRuleGuard.TYPE.equals(kind)) DECISION_RULE_STAMPS.forEach(out::remove);
        if ("job".equals(kind)) {
            JobConfig.AUTHOR_KEYS.forEach(out::remove);
            if (out.get("job") instanceof Map<?, ?> j) {
                Map<String, Object> section = new LinkedHashMap<>((Map<String, Object>) j);
                JobConfig.AUTHOR_KEYS.forEach(section::remove);
                out.put("job", section);
            }
        }
        return out;
    }

    /** The kind a promoted path is, for the report. */
    static String kindOf(String path) {
        if (isConnection(path)) return "connection";
        String p = resolved(path);
        String k = PendingChanges.kindOfConfigPath(p);
        if (k != null) return k;
        if (p.endsWith("_schema.toon")) return "schema";
        if (p.endsWith("_view.toon")) return "view";
        return "config";
    }

    /** SHA-256 over every entry (path, action, both contents): any change to either side changes it. */
    private static String hash(List<Change> changes) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            for (Change c : changes) {
                d.update((c.path() + "\n" + c.action() + "\n" + sha(c.incoming()) + "\n" + sha(c.current()) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(d.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String sha(byte[] b) throws NoSuchAlgorithmException {
        return b == null ? "-" : HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    /** The Pending Change content of the files an apply writes: path → {action, content}; text, so a reviewer reads it. */
    static Map<String, Object> proposed(String space, ChangeSet cs, List<Change> applied) {
        Map<String, Object> files = new TreeMap<>();
        for (Change c : applied) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("action", c.action());
            f.put("content", c.incoming() == null ? null : new String(c.incoming(), StandardCharsets.UTF_8));
            files.put(c.path(), f);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("space", space);
        m.put("changeSetHash", cs.hash());
        m.put("files", files);
        return m;
    }

    /** What those files hold now (an added file is absent). */
    static Map<String, Object> current(String space, List<Change> applied) {
        Map<String, Object> files = new TreeMap<>();
        for (Change c : applied)
            if (c.current() != null) files.put(c.path(), new String(c.current(), StandardCharsets.UTF_8));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("space", space);
        m.put("files", files);
        return m;
    }
}
