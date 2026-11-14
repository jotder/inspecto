package com.gamma.la.api;

import com.gamma.la.api.IndexedRead.Fitted;
import com.gamma.la.api.IndexedRead.Outcome;
import com.gamma.la.api.IndexedRead.Reason;
import com.gamma.la.api.IndexedRead.Selection;
import com.gamma.la.core.DatasetProvider;
import com.gamma.la.core.TemporalCut;
import com.gamma.la.storage.IndexReader;
import com.gamma.sql.SqlSandboxPolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

/**
 * {@code POST /inv/pattern/temporal} answered FROM THE INDEX (LA-INVESTIGATION-OPS-DEFERRED-1): the edge rows of the {@code out} copy,
 * ordered and capped as the flat statement orders and caps them, cut by the SAME {@link TemporalCut} statement and handed to the SAME
 * detection the flat read feeds - so the findings are the same either way.
 * Servable only when the request's time column IS the index's time column and the index read it in UTC (the flat read casts the column
 * to a TIMESTAMP with no zone) and every field of the {@code filter} is an index column.
 */
final class IndexedTemporal {

    record Request(String datasetId, String sourceCol, String targetCol, String timeCol, Object filter, int maxRows,
                   TemporalCut.Spec spec) { }

    private IndexedTemporal() { }

    static Outcome<TemporalCut.Result> attempt(Path writeRoot, Path dataRoot, String relationSql, Request rq, SqlSandboxPolicy policy) {
        Selection sel = IndexedRead.select(writeRoot, dataRoot, relationSql, rq.datasetId(), rq.sourceCol(), rq.targetCol(), (m, utc) -> {
            if (!rq.timeCol().equalsIgnoreCase(m.timeColumn())) return Fitted.no(Reason.column_not_indexed);
            if (!utc) return Fitted.no(Reason.time_zone_not_servable);
            if (rq.filter() == null) return Fitted.ok(null);
            DatasetProvider.BoundFilter f = IndexedRead.renderFilter(rq.filter(), m, true);
            return f == null ? Fitted.no(Reason.filter_not_indexed) : Fitted.ok(f);
        });
        if (!sel.usable()) return sel.flat();
        try (IndexReader reader = IndexReader.borrow(sel.dir(), sel.manifest(), policy)) {
            return sel.served(reader.scanTemporalCut(sel.filterSql(), sel.filterBinds(), rq.maxRows(), rq.spec()));
        } catch (SQLException | IOException | RuntimeException unreadable) {
            return Outcome.flat(Reason.index_read_failed);
        }
    }
}
