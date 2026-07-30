package com.gamma.parse;

import com.gamma.api.PublicApi;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.regex.Pattern;

/**
 * Registry of known {@link ParserPlugin}s: the four {@link BuiltinParsers built-ins} (the engine's
 * own DuckDB-native frontends) plus any contributed via {@link ServiceLoader}
 * ({@code META-INF/services/com.gamma.parse.ParserPlugin}). Built once at class-load, immutable
 * thereafter — the source for {@code GET /parsers} and the preview dispatch.
 *
 * <p><b>Duplicate ids fail loudly</b> — unlike {@link com.gamma.pipeline.PipelineNodeTypes}, a
 * provider may NOT override a built-in: the built-ins' preview delegates to the exact DuckDB reads
 * the ingest engine runs, so an override would let a preview diverge from the engine that ingests
 * the real files. A provider colliding with another provider is equally a deployment error.
 */
@PublicApi(since = "5.3.0")
public final class Parsers {

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_]*");

    private static final Map<String, ParserPlugin> REGISTRY = load();

    private Parsers() {}

    private static Map<String, ParserPlugin> load() {
        Map<String, ParserPlugin> m = new LinkedHashMap<>();
        for (ParserPlugin p : BuiltinParsers.all()) m.put(p.id(), p);
        for (ParserPlugin p : ServiceLoader.load(ParserPlugin.class)) {
            String id = p.id();
            if (id == null || !ID.matcher(id).matches()) {
                throw new IllegalStateException("parser plugin " + p.getClass().getName()
                        + " declares an invalid id '" + id + "' (need [a-z0-9][a-z0-9_]*)");
            }
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

    /** The parser registered under {@code id}, if any. */
    public static Optional<ParserPlugin> get(String id) {
        return Optional.ofNullable(REGISTRY.get(id));
    }

    /** All registered parsers (built-ins first, then providers), in registration order. */
    public static Collection<ParserPlugin> catalog() {
        return REGISTRY.values();
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
