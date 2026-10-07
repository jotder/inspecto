package com.gamma.parse;

import com.gamma.api.PublicApi;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.regex.Pattern;

/**
 * Registry of known {@link ParserPlugin}s: the six {@link BuiltinParsers built-ins} (the engine's
 * own DuckDB-native frontends) plus any contributed via {@link ServiceLoader}
 * ({@code META-INF/services/com.gamma.parse.ParserPlugin}), built once at class-load — plus an
 * owner-keyed overlay of parsers contributed by loaded Job Packs ({@link #register}/{@link #deregister},
 * parser-plugins-trust-design.md slice P2, operator D1 2026-09-25). The source for {@code GET /parsers}
 * and the preview dispatch.
 *
 * <p>A pack parser may never replace a built-in or classpath parser, and the first pack to claim an id (or
 * an ingester class name) keeps it — the same rules as {@link com.gamma.pipeline.PipelineNodeTypes}' pack
 * overlay, so load order cannot silently change a deployment's meaning.
 *
 * <p><b>Duplicate ids fail loudly</b> — unlike {@link com.gamma.pipeline.PipelineNodeTypes}, a
 * provider may NOT override a built-in: the built-ins' preview delegates to the exact DuckDB reads
 * the ingest engine runs, so an override would let a preview diverge from the engine that ingests
 * the real files. A provider colliding with another provider is equally a deployment error.
 */
@PublicApi(since = "4.0.0")
public final class Parsers {

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_]*");

    /** Built-ins + classpath providers, fixed at class-load. */
    private static final Map<String, ParserPlugin> BASE = load();

    /** Pack-contributed parsers by id, and the owning pack (jar filename) per id. */
    private static final Map<String, ParserPlugin> PACKED = new LinkedHashMap<>();
    private static final Map<String, String> OWNERS = new LinkedHashMap<>();

    /** BASE + PACKED in catalog order, rebuilt on every overlay change so readers never synchronise. */
    private static volatile Map<String, ParserPlugin> effective = BASE;

    private Parsers() {}

    private static Map<String, ParserPlugin> load() {
        Map<String, ParserPlugin> m = new LinkedHashMap<>();
        for (ParserPlugin p : BuiltinParsers.all()) m.put(p.id(), p);
        for (ParserPlugin p : ServiceLoader.load(ParserPlugin.class)) {
            String id = requireValidId(p);
            ParserPlugin prev = m.putIfAbsent(id, p);
            if (prev != null) {
                throw new IllegalStateException("duplicate parser id '" + id + "': "
                        + p.getClass().getName() + " collides with " + prev.getClass().getName());
            }
        }
        // NOT Map.copyOf — that discards iteration order, and the catalog order is part of the
        // contract (built-ins first, then providers in discovery order).
        return java.util.Collections.unmodifiableMap(m);
    }

    private static String requireValidId(ParserPlugin p) {
        String id = p.id();
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalStateException("parser plugin " + p.getClass().getName()
                    + " declares an invalid id '" + id + "' (need [a-z0-9][a-z0-9_]*)");
        }
        return id;
    }

    /**
     * Contribute a Job Pack's parser under {@code owner} (the pack's jar filename). Refuses an invalid id, an
     * id a built-in or classpath parser holds, an id another pack owns, and an ingester class name another
     * registered parser already names (which pack's loader resolves it would otherwise depend on load order).
     * Re-registering under the SAME owner replaces — every Space's pack manager watches the one packs dir.
     *
     * @throws IllegalStateException on any refusal; the registry is then unchanged
     */
    public static synchronized void register(ParserPlugin parser, String owner) {
        String id = requireValidId(parser);
        if (BASE.containsKey(id))
            throw new IllegalStateException("parser id '" + id + "' is a built-in or classpath parser and cannot "
                    + "be replaced by a pack");
        String existing = OWNERS.get(id);
        if (existing != null && !existing.equals(owner))
            throw new IllegalStateException("parser id '" + id + "' is already contributed by pack '" + existing + "'");
        Optional<String> fqcn = parser.ingesterClass();
        if (fqcn.isPresent()) {
            for (ParserPlugin other : effective.values()) {
                if (other.id().equals(id) || !other.ingesterClass().equals(fqcn)) continue;
                throw new IllegalStateException("parser '" + id + "' names ingester " + fqcn.get()
                        + ", which parser '" + other.id() + "' already names");
            }
        }
        PACKED.put(id, parser);
        OWNERS.put(id, owner);
        effective = snapshot();
    }

    /** Take back every parser {@code owner} contributed. A no-op for an owner that registered none. */
    public static synchronized void deregister(String owner) {
        if (owner == null || !OWNERS.containsValue(owner)) return;
        OWNERS.entrySet().removeIf(e -> {
            if (!owner.equals(e.getValue())) return false;
            PACKED.remove(e.getKey());
            return true;
        });
        effective = snapshot();
    }

    private static Map<String, ParserPlugin> snapshot() {
        if (PACKED.isEmpty()) return BASE;
        Map<String, ParserPlugin> m = new LinkedHashMap<>(BASE);
        m.putAll(PACKED);
        return java.util.Collections.unmodifiableMap(m);   // NOT Map.copyOf — catalog order is the contract
    }

    /** The pack (jar filename) that contributed parser {@code id}; empty for a built-in/classpath parser. */
    public static Optional<String> ownerOf(String id) {
        return Optional.ofNullable(OWNERS.get(id));
    }

    /** Where parser {@code id} came from — {@code builtin} | {@code classpath} | {@code pack:<owner>}, the
     *  provenance vocabulary Job Types already report. */
    public static String sourceOf(String id) {
        String owner = OWNERS.get(id);
        if (owner != null) return "pack:" + owner;
        return BuiltinParsers.isBuiltin(id) ? "builtin" : "classpath";
    }

    /** The registered parser naming {@code ingesterClass} as its ingester, if any. */
    public static Optional<ParserPlugin> forIngester(String ingesterClass) {
        if (ingesterClass == null) return Optional.empty();
        for (ParserPlugin p : effective.values())
            if (p.ingesterClass().filter(ingesterClass::equals).isPresent()) return Optional.of(p);
        return Optional.empty();
    }

    /** The parser registered under {@code id}, if any. */
    public static Optional<ParserPlugin> get(String id) {
        return Optional.ofNullable(effective.get(id));
    }

    /** All registered parsers (built-ins first, then classpath providers, then packs), in registration order. */
    public static Collection<ParserPlugin> catalog() {
        return effective.values();
    }

    /**
     * Whether {@code parser} can load to Tables today: the built-ins ingest through the engine's
     * own DuckDB path; a provider ingests only when it names a
     * {@link com.gamma.etl.StreamingFileIngester} via {@link ParserPlugin#ingesterClass()}.
     */
    public static boolean ingestable(ParserPlugin parser) {
        return BuiltinParsers.isBuiltin(parser.id()) || parser.ingesterClass().isPresent();
    }
}
