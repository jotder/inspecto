package com.gamma.la.api;

import com.gamma.la.api.IndexedRead.Fitted;
import com.gamma.la.api.IndexedRead.Outcome;
import com.gamma.la.api.IndexedRead.Reason;
import com.gamma.la.api.IndexedRead.Selection;
import com.gamma.la.core.DatasetProvider;
import com.gamma.la.storage.IndexReader;
import com.gamma.la.storage.IndexedTraversal;
import com.gamma.sql.SqlSandboxPolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

/**
 * D-3 step 5 - decides whether {@code POST /inv/traversal/recursive-paths} can be answered FROM THE INDEX, and if so walks it
 * (design 4.4). The choice, the staleness gate and the closed {@link Reason} are shared with the other index-backed routes
 * ({@link IndexedRead}); this class adds the traversal's own fit test and walk.
 *
 * <p>An index is chosen among the Dataset's published ones: same source and target column (case-insensitive, like the flat
 * path's column check), covering the weight column, the temporal column and every {@code filter} field the request names.
 * Kind, zone and attribute columns are not part of a traversal request, so any value of them matches. ⚠ Not servable, and
 * therefore falling back, whenever the index cannot reproduce the flat answer EXACTLY: a temporal constraint over a time
 * column read in a zone other than UTC (the flat path compares the raw wall-clock values), a {@code filter} on the weight
 * column (stored as DOUBLE, the flat path sees the original type), a depth over {@link IndexedTraversal#MAX_DEPTH}, and a
 * frontier over {@link IndexedTraversal#FRONTIER_CAP} keys (found only mid-walk, which discards the partial answer).
 */
final class IndexedRecursivePaths {

    record Request(String datasetId, String sourceCol, String targetCol, String weightCol, String tsCol, Object filter,
                   String startNode, String targetNode, boolean undirected, int maxDepth, int maxEdges, int limit,
                   boolean monotonic, Double maxHours, Double maxGapHours) { }

    private IndexedRecursivePaths() { }

    /**
     * @param beforeRead runs once the request is known to be servable and before anything is read - the caller's four-eyes
     *                   refusal, so the index path never reads what the flat path would have refused
     */
    static Outcome<IndexedTraversal.Result> attempt(Path writeRoot, Path dataRoot, String relationSql, Request rq,
                                                    SqlSandboxPolicy policy, Runnable beforeRead) {
        Selection sel = IndexedRead.select(writeRoot, dataRoot, relationSql, rq.datasetId(), rq.sourceCol(), rq.targetCol(), (m, utc) -> {
            if (rq.weightCol() != null && !rq.weightCol().equalsIgnoreCase(m.weightColumn())) return Fitted.no(Reason.column_not_indexed);
            if (rq.tsCol() != null && !rq.tsCol().equalsIgnoreCase(m.timeColumn())) return Fitted.no(Reason.column_not_indexed);
            if (rq.tsCol() != null && !utc) return Fitted.no(Reason.time_zone_not_servable);
            if (rq.filter() == null) return Fitted.ok(null);
            DatasetProvider.BoundFilter f = IndexedRead.renderFilter(rq.filter(), m, utc);
            return f == null ? Fitted.no(Reason.filter_not_indexed) : Fitted.ok(f);
        });
        if (!sel.usable()) return sel.flat();

        if (rq.maxDepth() > IndexedTraversal.MAX_DEPTH) return Outcome.flat(Reason.depth_over_index_cap);

        beforeRead.run();                                                                    // four-eyes, exactly as the flat path
        IndexedTraversal.Params p = new IndexedTraversal.Params(rq.startNode(), rq.targetNode(), rq.undirected(), rq.maxDepth(),
                rq.maxEdges(), rq.limit(), rq.tsCol() != null, rq.monotonic(), rq.maxHours(), rq.maxGapHours(), sel.filterSql(), sel.filterBinds());
        try (IndexReader reader = IndexReader.borrow(sel.dir(), sel.manifest(), policy)) {
            return sel.served(IndexedTraversal.walk(reader, p));
        } catch (IndexedTraversal.FrontierOverCap over) {
            return Outcome.flat(Reason.frontier_over_index_cap);                             // the partial walk is discarded
        } catch (SQLException | IOException | RuntimeException unreadable) {
            return Outcome.flat(Reason.index_read_failed);
        }
    }
}
