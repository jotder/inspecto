package com.gamma.consignment;

import com.gamma.api.PublicApi;

/**
 * One <b>derived table</b> a {@link ConsignmentProcessor} asks the framework to materialise: a name, the
 * SQL that produces it, and optionally the column to partition by.
 *
 * <p><b>Why a request object rather than a writer call.</b> The processor declares <em>what</em> it wants
 * materialised; the framework decides <em>when</em> and <em>where</em>, and registers the result. That is
 * the same split {@link SummaryRow} + {@code SummaryWriter} already use, and it is what keeps the
 * ordering rule — register only after the data is revealed — in one place instead of in every plugin.
 *
 * @param name        the table's name. ⚠ Becomes a <b>directory name</b>, so it is held to
 *                    {@code [A-Za-z_][A-Za-z0-9_]{0,127}} — the path-jail rule applied at the seam where a
 *                    third-party name enters, exactly as the summary target is.
 * @param sql         the {@code SELECT} producing the rows, run against this Consignment's own readable
 *                    relations. Read-only: it is executed inside the same sandbox {@link ConsignmentReader}
 *                    uses, so it can reach nothing else.
 * @param partitionBy the column to partition the output by, or {@code null} for a single flat file.
 *                    🔴 <b>Not cosmetic.</b> An unpartitioned derived table gives the {@code compact}
 *                    maintenance task no key to merge on, so its small files accumulate for ever; declare
 *                    one whenever the table grows per Consignment.
 */
@PublicApi(since = "4.0.0")
public record DerivedTable(String name, String sql, String partitionBy) {

    /** A flat (single-file) derived table. */
    public DerivedTable(String name, String sql) {
        this(name, sql, null);
    }
}
