package com.gamma.parse;

import com.gamma.api.PublicApi;
import com.gamma.config.spec.FieldSpec;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The self-describing Parser SPI — the wrapper that unifies the two transparent parse engines
 * (DuckDB-native reads and custom Java decoders) behind one contract, so a new file format can be
 * <em>deployed and configured as a plugin</em> without the UI or control plane learning anything
 * about it. A Parser consumes a <b>Grammar</b> — the authored options for one file format (the
 * nested {@code parsing:}-block-shaped map, exactly what the guided editor persists) — and its
 * helper methods answer the authoring questions in order:
 *
 * <ul>
 *   <li>{@link #id()} / {@link #label()} — what am I called ({@code GET /parsers} catalog)?</li>
 *   <li>{@link #grammarSchema()} — which options do I take (drives the generic schema-form)?</li>
 *   <li>{@link #suggest(byte[])} — clues: grammar values sniffed from a sample (never auto-applied).</li>
 *   <li>{@link #preview(byte[], Map)} — parse part of the contents so the builder sees THEIR data:
 *       a flat {@link ParseResult.Table} for tabular formats, a record {@link ParseResult.Tree} for
 *       hierarchical ones (XML, ASN.1, …).</li>
 * </ul>
 *
 * <h3>Loading to Tables</h3>
 * Preview and ingest are deliberately separate capabilities. The four built-in parsers load through
 * the engine's own DuckDB path; a custom parser loads only when it names a
 * {@link com.gamma.etl.StreamingFileIngester} via {@link #ingesterClass()} (the existing
 * {@code parsing.plugin} machinery — segments, union/generation modes — unchanged). A hierarchical
 * parser without an ingester is <b>preview-only</b>: its tree cannot honestly land in Tables until
 * the flatten configuration exists, and the catalog says so ({@code ingestable: false}).
 *
 * <h3>Registration</h3>
 * {@code META-INF/services/com.gamma.parse.ParserPlugin} with a public no-arg constructor on the
 * server classpath — see {@link Parsers}. Ids are {@code [a-z0-9][a-z0-9_]*}; colliding with a
 * built-in fails startup loudly (a preview must never diverge from the engine that will ingest).
 *
 * @see Parsers
 * @see ParseResult
 */
@PublicApi(since = "4.0.0")
public interface ParserPlugin {

    /** Stable catalog id ({@code [a-z0-9][a-z0-9_]*}), e.g. {@code delimited}, {@code xml}. */
    String id();

    /** Human catalog label, e.g. {@code "XML — XML file format"}. */
    String label();

    /** Whether decoded records are tree-shaped (previewed as a tree, needs flattening to load). */
    boolean hierarchical();

    /**
     * The options ("grammar") this parser takes, as declarative {@link FieldSpec}s with paths
     * relative to the grammar map (e.g. {@code delimited.delimiter}) — the served schema a generic
     * form renders, the same vocabulary {@code GET /config/spec/&#123;type&#125;} already serves.
     */
    List<FieldSpec> grammarSchema();

    /**
     * Parse {@code sample} with {@code grammar} and return a bounded preview. Throw
     * {@link IllegalArgumentException} for a bad grammar or an unparseable sample (the control
     * plane maps it to a 422 with the message — a caller problem, never a server error).
     *
     * @param sample  raw sample bytes (binary-safe; text formats decode per their grammar's
     *                {@code encoding}, defaulting to UTF-8)
     * @param grammar the nested options map (never {@code null}; may be empty for all-default)
     */
    ParseResult preview(byte[] sample, Map<String, Object> grammar) throws Exception;

    /**
     * Optional clues: grammar values sniffed from {@code sample} (e.g. a proposed record element).
     * Suggestions assist, they never constrain — the UI offers them as one-click chips, never
     * auto-applies. Default: no clues.
     */
    default Map<String, Object> suggest(byte[] sample) {
        return Map.of();
    }

    /**
     * The FQCN of this parser's {@link com.gamma.etl.StreamingFileIngester} when the format can
     * load to Tables today (referenced by the existing {@code parsing.plugin} config shape, not
     * instantiated here). Empty for preview-only parsers awaiting the flatten configuration.
     */
    default Optional<String> ingesterClass() {
        return Optional.empty();
    }
}
