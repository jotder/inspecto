package com.gamma.la.api;

import com.gamma.la.api.IndexedRead.Fitted;
import com.gamma.la.api.IndexedRead.Outcome;
import com.gamma.la.api.IndexedRead.Reason;
import com.gamma.la.api.IndexedRead.Selection;
import com.gamma.la.storage.IndexReader;
import com.gamma.la.storage.IndexReader.Folded;
import com.gamma.la.storage.IndexReader.Side;
import com.gamma.sql.SqlSandboxPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * D-3 step 6 - {@code POST /inv/projection/neighbors} answered FROM THE INDEX (design 4.4): the one-hop neighbourhood of one
 * value is one equality statement on the {@code out} copy (the value as source) and one on the {@code in} copy (the value as
 * target), each folded {@code GROUP BY} exactly as the flat statement folds. A self-loop is in both copies and is counted once.
 * Rows come back in the flat order ({@code cnt DESC, source, target}, then kind and attributes, NULL first) and are cut at
 * {@code limit}; more rows than that sets {@code truncated}, as the flat executor does.
 *
 * <p>Servable only when the index carries every column asked for: the {@code linkKindCol}, each attribute column and every
 * field of the {@code filter} (the weight column is not an index column of record for a filter, see {@link IndexedRead}).
 */
final class IndexedNeighbors {

    record Request(String datasetId, String sourceCol, String targetCol, String kindCol, List<String> attrCols, Object filter,
                   String value, int limit) { }

    /** Rows shaped like the flat statement's: {@code source, target, [kind], [attr_i...], cnt}. */
    record Result(List<Map<String, Object>> rows, boolean truncated) { }

    private IndexedNeighbors() { }

    static Outcome<Result> attempt(Path writeRoot, Path dataRoot, String relationSql, Request rq, SqlSandboxPolicy policy) {
        List<String> extraCols = new ArrayList<>();
        Selection sel = IndexedRead.select(writeRoot, dataRoot, relationSql, rq.datasetId(), rq.sourceCol(), rq.targetCol(), (m, utc) -> {
            extraCols.clear();
            if (rq.kindCol() != null) {
                if (!rq.kindCol().equalsIgnoreCase(m.kindColumn())) return Fitted.no(Reason.column_not_indexed);
                extraCols.add("kind");
            }
            for (String a : rq.attrCols()) {
                int at = -1;
                for (int i = 0; i < m.attributeColumns().size(); i++)
                    if (a.equalsIgnoreCase(m.attributeColumns().get(i))) at = i;
                if (at < 0) return Fitted.no(Reason.column_not_indexed);
                extraCols.add("a" + at);
            }
            if (rq.filter() == null) return Fitted.ok(null);
            String sql = IndexedRead.renderFilter(rq.filter(), m, utc);
            return sql == null ? Fitted.no(Reason.filter_not_indexed) : Fitted.ok(sql);
        });
        if (!sel.usable()) return sel.flat();

        try (IndexReader reader = IndexReader.borrow(sel.dir(), sel.manifest(), policy)) {
            Set<Folded> distinct = new LinkedHashSet<>();                                    // a self-loop arrives from both copies
            distinct.addAll(reader.fold(rq.value(), Side.OUT, extraCols, null, sel.filterSql()));
            distinct.addAll(reader.fold(rq.value(), Side.IN, extraCols, null, sel.filterSql()));
            List<Folded> sorted = new ArrayList<>(distinct);
            sorted.sort((a, b) -> {
                int c = Long.compare(b.count(), a.count());
                if (c == 0) c = bytes(a.source(), b.source());
                if (c == 0) c = bytes(a.target(), b.target());
                for (int i = 0; c == 0 && i < a.extras().size(); i++) c = bytes(a.extras().get(i), b.extras().get(i));
                return c;
            });
            boolean truncated = sorted.size() > rq.limit();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Folded f : truncated ? sorted.subList(0, rq.limit()) : sorted) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("source", f.source());
                row.put("target", f.target());
                int at = 0;
                if (rq.kindCol() != null) row.put("kind", f.extras().get(at++));
                for (int i = 0; i < rq.attrCols().size(); i++) row.put("attr_" + i, f.extras().get(at++));
                row.put("cnt", f.count());
                rows.add(row);
            }
            return sel.served(new Result(rows, truncated));
        } catch (SQLException | IOException | RuntimeException unreadable) {
            return Outcome.flat(Reason.index_read_failed);
        }
    }

    /** DuckDB's VARCHAR order: unsigned bytes of the UTF-8; NULL first. */
    private static int bytes(String a, String b) {
        if (a == null || b == null) return a == null ? (b == null ? 0 : -1) : 1;
        return Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
