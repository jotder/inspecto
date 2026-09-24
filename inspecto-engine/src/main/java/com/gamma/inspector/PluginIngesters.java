package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import com.gamma.etl.StreamingFileIngester;
import com.gamma.parse.ParserPlugin;
import com.gamma.parse.Parsers;
import com.gamma.pipeline.exec.PackRunLeases;

/**
 * The one resolver for a Pipeline's {@code parsing.plugin.ingester} class (parser-plugins-trust-design.md
 * slice P3). A class a Job Pack contributed lives in that pack's own loader, which the engine's
 * {@code Class.forName(name)} cannot see — so a registered parser naming the class is looked up first and
 * the class is loaded through <em>its</em> loader, with the pack pinned ({@link PackRunLeases}) for as long
 * as the returned {@link Leased} is open. An unload during the ingest then only defers the loader close;
 * the ingest finishes on the pack code it started with. A class no registered parser names resolves on the
 * engine loader, as before.
 */
final class PluginIngesters {

    private PluginIngesters() {}

    /** An instantiated ingester plus the pack pin that keeps its loader open; close in a {@code finally}. */
    record Leased(StreamingFileIngester ingester, PackRunLeases.Lease lease) implements AutoCloseable {
        @Override public void close() { lease.close(); }
    }

    /**
     * Resolve and instantiate {@code cfg}'s ingester.
     *
     * @throws IllegalStateException naming the class when nothing loaded provides it (a pack that was
     *         removed, revoked or refused) — never a bare {@link ClassNotFoundException}
     */
    static Leased open(PipelineConfig cfg) {
        String fqcn = cfg.schemas().ingesterClass();
        ParserPlugin parser = Parsers.forIngester(fqcn).orElse(null);
        String owner = parser == null ? null : Parsers.ownerOf(parser.id()).orElse(null);
        if (owner == null) return new Leased(instantiate(fqcn, PluginIngesters.class.getClassLoader()), () -> {});

        PackRunLeases.Lease lease = PackRunLeases.acquire(owner);
        try {
            // Re-check under the pin: an unload that deregistered the parser before the pin was taken may
            // already have closed its loader. One that deregisters after this check sees the pin and defers.
            if (Parsers.get(parser.id()).orElse(null) != parser)
                throw notLoaded(fqcn);
            return new Leased(instantiate(fqcn, parser.getClass().getClassLoader()), lease);
        } catch (RuntimeException e) {
            lease.close();
            throw e;
        }
    }

    private static StreamingFileIngester instantiate(String fqcn, ClassLoader loader) {
        Class<?> cls;
        try {
            cls = Class.forName(fqcn, true, loader);
        } catch (ClassNotFoundException e) {
            throw notLoaded(fqcn);
        }
        try {
            return (StreamingFileIngester) cls.getDeclaredConstructor().newInstance();
        } catch (Exception | LinkageError e) {
            throw new RuntimeException("Cannot instantiate streaming ingester: " + fqcn, e);
        }
    }

    private static IllegalStateException notLoaded(String fqcn) {
        return new IllegalStateException("streaming ingester " + fqcn + " is not on the classpath and no loaded "
                + "parser names it; if a Job Pack provided it, that pack is not loaded (removed, revoked or "
                + "refused, see GET /jobs/packs)");
    }
}
