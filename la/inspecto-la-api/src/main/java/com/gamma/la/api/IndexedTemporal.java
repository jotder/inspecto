package com.gamma.la.api;

import com.gamma.la.api.IndexedRead.Fitted;
import com.gamma.la.api.IndexedRead.Outcome;
import com.gamma.la.api.IndexedRead.Reason;
import com.gamma.la.api.IndexedRead.Selection;
import com.gamma.la.core.DatasetProvider;
import com.gamma.la.storage.IndexReader;
import com.gamma.la.storage.IndexReader.LinkTime;
import com.gamma.sql.SqlSandboxPolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

/**
 * {@code POST /inv/pattern/temporal} answered FROM THE INDEX (LA-INVESTIGATION-OPS-DEFERRED-1): the edge rows of the {@code out} copy,
 * ordered as the flat statement orders them, handed to the SAME detection the flat read feeds - so the findings are the same either way.
 * Servable only when the request's time column IS the index's time column and the index read it in UTC (the flat read casts the column
 * to a TIMESTAMP with no zone) and every field of the {@code filter} is an index column.
 */
final class IndexedTemporal {

    record Request(String datasetId, String sourceCol, String targetCol, String timeCol, Object filter, int maxRows) { }

    /** At most {@code maxRows} rows; {@code truncated} when the index held more. */
    record Result(List<LinkTime> rows, boolean truncated) { }

    private IndexedTemporal() { }

    static Outcome<Result> attempt(Path writeRoot, Path dataRoot, String relationSql, Request rq, SqlSandboxPolicy policy) {
        Selection sel = IndexedRead.select(writeRoot, dataRoot, relationSql, rq.datasetId(), rq.sourceCol(), rq.targetCol(), (m, utc) -> {
            if (!rq.timeCol().equalsIgnoreCase(m.timeColumn())) return Fitted.no(Reason.column_not_indexed);
            if (!utc) return Fitted.no(Reason.time_zone_not_servable);
            if (rq.filter() == null) return Fitted.ok(null);
            DatasetProvider.BoundFilter f = IndexedRead.renderFilter(rq.filter(), m, true);
            return f == null ? Fitted.no(Reason.filter_not_indexed) : Fitted.ok(f);
        });
        if (!sel.usable()) return sel.flat();
        try (IndexReader reader = IndexReader.borrow(sel.dir(), sel.manifest(), policy)) {
            List<LinkTime> rows = reader.scanLinkTimes(sel.filterSql(), sel.filterBinds(), rq.maxRows() + 1);
            boolean truncated = rows.size() > rq.maxRows();
            return sel.served(new Result(truncated ? rows.subList(0, rq.maxRows()) : rows, truncated));
        } catch (SQLException | IOException | RuntimeException unreadable) {
            return Outcome.flat(Reason.index_read_failed);
        }
    }
}
