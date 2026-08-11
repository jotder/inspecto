package com.gamma.enrich;

import com.gamma.api.PublicApi;
import com.gamma.etl.PipelineConfig;
import com.gamma.sql.SqlViews;

import java.util.List;
import java.util.Locale;

/**
 * <b>The one way to read a Reference Dataset.</b> Resolves an {@link EnrichmentConfig.Reference} to the
 * DuckDB read expression that yields its rows — a direct {@code path:} file, or a by-name {@code ref:}
 * binding to a pipeline declaring {@code produces: reference}, whose Hive-partitioned output is read as
 * its current (or as-of) view.
 *
 * <p><b>Why this is shared and not inlined.</b> A versioned reference store ({@code load: upsert|scd2})
 * is append-only — one version row per key per batch — so reading it correctly means picking the winning
 * version per key, dropping delete tombstones and stripping the system columns. That is a subtle
 * correctness rule, and there are now two callers: the Stage-2 {@link EnrichmentEngine} and the
 * {@code transform.join} executor's resolver ({@code PipelineJobRunner}). A second implementation would
 * be a second thing to keep right — the same reason a {@code summarize} node compiles through
 * {@code MeasureCompiler} rather than parsing measures again.
 *
 * <p>The returned string is a read <em>expression</em> (a table function or an aliased subquery), meant to
 * be spliced after {@code SELECT * FROM } — not an identifier. Callers that need a named relation register
 * a view over it.
 */
@PublicApi(since = "4.9.0")
public final class ReferenceReader {

    private ReferenceReader() {}

    /** The {@code reference/<name>} prefix a {@code transform.join} node's {@code reference} key uses. */
    private static final String REF_PREFIX = "reference/";

    /**
     * The read expression for {@code r}. {@code pipelines} is the loaded-pipeline context a by-name
     * binding resolves against; {@code null}/empty is fine for a {@code path:} reference and refuses for
     * a by-name one (naming what is missing, because that is a wiring mistake, not a config error).
     */
    public static String sqlFor(EnrichmentConfig.Reference r, List<PipelineConfig> pipelines) {
        if (!r.byName()) return SqlViews.reader(r.format(), r.path(), false);   // as_of rejected at parse
        PipelineConfig p = (pipelines == null ? List.<PipelineConfig>of() : pipelines).stream()
                .filter(c -> c.identity().pipelineName().equals(r.ref()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "reference '" + r.name() + "' binds ref: '" + r.ref()
                                + "' but no such pipeline is loaded (by-name references need the "
                                + "service's pipeline context; use path: for a plain file)"));
        if (!p.producesReference())
            throw new IllegalArgumentException("reference '" + r.name() + "' binds ref: '" + r.ref()
                    + "' but that pipeline does not declare 'produces: reference'");
        String format = (p.output() == null || p.output().format() == null)
                ? "CSV" : p.output().format().toUpperCase(Locale.ROOT);
        String glob = p.dirs().database() + "/**/*." + SqlViews.ext(format);
        String reader = SqlViews.reader(format, glob, true);
        // Reference Phase-2 P1/P2: an `upsert`/`scd2` reference store is append-only (a version row per
        // key per batch); derive the view at read time — latest __valid_from wins per key, delete
        // tombstones dropped, system columns stripped. `scd2` additionally serves history: an `as_of`
        // binding sees the version that was valid at that instant. A `replace` store is read verbatim.
        if (!p.reference().load().versionedStore()) {
            if (r.hasAsOf())
                throw new IllegalArgumentException("reference '" + r.name() + "' declares as_of but pipeline '"
                        + r.ref() + "' has reference.load: replace — only 'scd2' keeps version history");
            return reader;
        }
        if (!r.hasAsOf()) return versionedView(reader, null);
        if (p.reference().load() != PipelineConfig.Load.SCD2)
            throw new IllegalArgumentException("reference '" + r.name() + "' declares as_of but pipeline '"
                    + r.ref() + "' has reference.load: upsert — as-of history needs 'scd2' (an upsert store's "
                    + "superseded versions are compaction fodder, not a queryable surface)");
        return versionedView(reader, r.asOf());
    }

    /**
     * The {@link EnrichmentConfig.Reference} a {@code transform.join} node's {@code reference} config value
     * denotes: {@code reference/<pipeline>} binds by name (the spelling {@code RecipeCompiler} normalises
     * to and {@code PipelineLift} carries), anything else is a direct path whose format comes from its
     * extension. Splitting this out means the join node and an {@code *_enrich.toon} reference reach
     * {@link #sqlFor} as the same thing, so versioned/as-of semantics cannot diverge between them.
     */
    public static EnrichmentConfig.Reference parse(String reference) {
        if (reference == null || reference.isBlank())
            throw new IllegalArgumentException("a reference is required (reference/<pipeline> or a path)");
        String s = reference.trim();
        if (s.startsWith(REF_PREFIX)) {
            String ref = s.substring(REF_PREFIX.length()).trim();
            if (ref.isEmpty())
                throw new IllegalArgumentException("reference '" + s + "' names no pipeline after '" + REF_PREFIX + "'");
            return new EnrichmentConfig.Reference(ref, null, null, ref);
        }
        String format = s.toLowerCase(Locale.ROOT).endsWith(".parquet") ? "PARQUET" : "CSV";
        return new EnrichmentConfig.Reference(s, s, format);
    }

    /** The system columns a versioned reference store carries (§2.1) — stripped from every derived view. */
    private static final String REF_SYSTEM_COLUMNS =
            "__key_hash, __row_hash, __valid_from, __op, __batch_id";

    /**
     * The read over an append-only versioned reference store: pick the winning version per
     * {@code __key_hash} ({@code QUALIFY row_number() ORDER BY __valid_from DESC = 1}), drop keys whose
     * winning version is a {@code delete} tombstone, and strip the system columns — so downstream
     * transforms see plain dimension rows. With {@code asOf == null} the winner is the latest version
     * (the <b>current view</b>); with an {@code asOf} literal the candidate set is first cut to versions
     * valid at that instant (the <b>as-of view</b>), so the result is the state the dimension had then —
     * including a key that did not exist yet being absent, and a key deleted later still being present.
     * Returned as an aliased subquery the caller splices after {@code SELECT * FROM }.
     */
    private static String versionedView(String reader, String asOf) {
        String candidates = "SELECT * FROM " + reader;
        if (asOf != null) candidates += " WHERE __valid_from <= TIMESTAMP '" + asOf + "'";
        return "(SELECT * EXCLUDE (" + REF_SYSTEM_COLUMNS + ") FROM ("
                + candidates
                + " QUALIFY row_number() OVER (PARTITION BY __key_hash ORDER BY __valid_from DESC) = 1"
                + ") WHERE __op != 'delete') AS _ref_current";
    }
}
