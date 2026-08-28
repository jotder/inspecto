package com.gamma.query;

import com.gamma.api.PublicApi;
import com.gamma.consignment.ConsignmentSelector;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.sql.SqlViews;

/**
 * Renders a {@link ViewDefinition}'s derived SQL for <b>execution</b> (consignment addressing §7-A, the
 * {@code deriveViewSql} residual): a definition whose SQL carries the {@link #READER_TOKEN} placeholder has
 * its source read rebuilt through the Consignment Selector at every read, so a full recompute's superseded
 * snapshot files are subtracted at the moment the view is queried — exactly what {@code DatasetRelation}'s
 * {@code physicalRef} branch already does — rather than frozen at the moment the view was written.
 *
 * <p>Why a template and not the two obvious alternatives: a persisted <em>raw glob</em> double-counts
 * silently after a recompute leaves the old revision on disk, and a persisted <em>file list</em> breaks
 * loudly when retirement later deletes those files. Rendering at read time is the only shape that stays
 * correct through both.
 *
 * <p>Definitions with plain SQL — hand-authored views, pre-template runner output — render verbatim: the
 * template is opt-in by the writer, never inferred. The token itself is invalid SQL on purpose, so an
 * executor that forgets to render fails loudly on the token instead of silently reading unfiltered.
 */
@PublicApi(since = "4.0.0")
public final class ViewReaderSql {

    /** The placeholder {@code PipelineJobRunner.deriveViewSql} embeds where the source read belongs. */
    public static final String READER_TOKEN = "{{reader}}";

    private ViewReaderSql() {}

    /**
     * {@code def}'s SQL ready to execute: a templated reader rendered fresh, plain SQL verbatim,
     * {@code null} passed through (the caller's no-derived-SQL handling stays its own).
     *
     * @throws IllegalArgumentException if the SQL templates its reader but the definition records no
     *                                  {@code reader_root}/{@code reader_format} — a torn definition;
     *                                  re-running the producing pipeline rewrites it whole
     */
    public static String rendered(ViewDefinition def) {
        String sql = def.derivedSql();
        if (sql == null || !sql.contains(READER_TOKEN)) return sql;
        if (isBlank(def.readerRoot()) || isBlank(def.readerFormat()))
            throw new IllegalArgumentException("view '" + def.store() + "' templates its reader but records no"
                    + " reader_root/reader_format — the definition is torn; re-run pipeline '" + def.flow()
                    + "' to rewrite it");
        String source = ConsignmentSelector.sourceLiteral(def.readerRoot(), SqlViews.ext(def.readerFormat()));
        // hive stays ON: the pre-template runner SQL always read with hive_partitioning, and this render
        // must be byte-compatible with it whenever the catalog has nothing to exclude.
        return sql.replace(READER_TOKEN, SqlViews.readerOverLiteral(def.readerFormat(), source, true));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
