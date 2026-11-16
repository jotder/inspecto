package com.gamma.la.api;

import com.gamma.la.api.IndexedRead.Fitted;
import com.gamma.la.api.IndexedRead.Outcome;
import com.gamma.la.api.IndexedRead.Reason;
import com.gamma.la.api.IndexedRead.Selection;
import com.gamma.entitystore.EntityTypes;
import com.gamma.la.core.DatasetProvider;
import com.gamma.la.storage.IndexManifest;
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
 * field of the {@code filter} (the weight column is not an index column of record for a filter, see {@link IndexedRead}),
 * and the {@code measureCol} a link's total sums (LA-COLUMN-ROLES-2), which must be one of its attribute columns.
 */
final class IndexedNeighbors {

    /**
     * {@code sourceType} / {@code targetType}: the Entity Type each end column is typed as now, null when untyped
     * (LA-INDEX-SPELLINGS-1: a typed end reads every raw spelling of the value's key, an untyped end the value exactly).
     */
    record Request(String datasetId, String sourceCol, String targetCol, String kindCol, List<String> attrCols, Object filter,
                   String value, int limit, String measureCol, EntityTypes.EntityType sourceType, EntityTypes.EntityType targetType) {
        Request(String datasetId, String sourceCol, String targetCol, String kindCol, List<String> attrCols, Object filter,
                String value, int limit) {
            this(datasetId, sourceCol, targetCol, kindCol, attrCols, filter, value, limit, null, null, null);
        }
    }

    /** One end's raw ids to look up ({@code values}), or why the index cannot say which they are ({@code refusal}). */
    private record Spellings(List<String> values, boolean complete, String refusal) { }

    /** Rows shaped like the flat statement's: {@code source, target, [kind], [attr_i...], [total], cnt}. */
    record Result(List<Map<String, Object>> rows, boolean truncated) { }

    private IndexedNeighbors() { }

    static Outcome<Result> attempt(Path writeRoot, Path dataRoot, String relationSql, Request rq, SqlSandboxPolicy policy) {
        List<String> extraCols = new ArrayList<>();
        String[] totalCol = {null};
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
            totalCol[0] = null;
            if (rq.measureCol() != null) {
                for (int i = 0; i < m.attributeColumns().size(); i++)
                    if (rq.measureCol().equalsIgnoreCase(m.attributeColumns().get(i))) totalCol[0] = "a" + i;
                if (totalCol[0] == null) return Fitted.no(Reason.column_not_indexed);
            }
            if (rq.filter() == null) return Fitted.ok(null);
            DatasetProvider.BoundFilter f = IndexedRead.renderFilter(rq.filter(), m, utc);
            return f == null ? Fitted.no(Reason.filter_not_indexed) : Fitted.ok(f);
        });
        if (!sel.usable()) return sel.flat();
        Spellings out = spellings(sel.manifest(), true, rq.sourceType(), rq.value());
        Spellings in = spellings(sel.manifest(), false, rq.targetType(), rq.value());
        for (Spellings s : List.of(out, in))
            if (s.refusal() != null) return Outcome.flat(Reason.index_stale_refused, s.refusal());

        try (IndexReader reader = IndexReader.borrow(sel.dir(), sel.manifest(), policy)) {
            Set<Folded> distinct = new LinkedHashSet<>();                                    // a self-loop arrives from both copies
            // one type at both ends: its spellings are pooled over both columns, so a raw id at either end settles a key
            boolean pooled = rq.sourceType() != null && rq.targetType() != null && rq.sourceType().id().equals(rq.targetType().id());
            for (Side side : List.of(Side.OUT, Side.IN)) {
                Spellings s = side == Side.OUT ? out : in;
                List<Folded> found = new ArrayList<>();
                for (String v : s.values())
                    found.addAll(reader.fold(v, side, extraCols, null, sel.filterSql(), sel.filterBinds(), false, totalCol[0]));
                // A typed value that is no raw id of this end may still share its key with the one raw id that spells it there
                // (a key spelt once is not listed, e.g. 'acc-1004' for ACC-1004): only the Dataset can say which.
                if (found.isEmpty() && !s.complete()
                        && (pooled ? reader.degree(rq.value()) : reader.rows(rq.value(), side)) == 0)
                    return Outcome.flat(Reason.rung_not_indexable, "the value is not one the link index holds; the Dataset reads it by its Entity Type key");
                distinct.addAll(found);
            }
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
                if (rq.measureCol() != null) row.put("total", f.total());
                row.put("cnt", f.count());
                rows.add(row);
            }
            return sel.served(new Result(rows, truncated));
        } catch (SQLException | IOException | RuntimeException unreadable) {
            return Outcome.flat(Reason.index_read_failed);
        }
    }

    /**
     * LA-INDEX-SPELLINGS-1: the raw ids one end looks {@code value} up by. An untyped end, a value whose key is empty, or an index
     * built before spellings were recorded: the value exactly ({@code complete}). A typed end: every raw id the manifest records
     * for the value's key under the end's type ({@code complete}), else the value alone - not complete, as one other spelling the
     * manifest does not list (a key it holds once) may be the only one. Refused when the index keyed this end under another
     * Entity Type rule, or recorded too many spellings to list them.
     */
    private static Spellings spellings(IndexManifest m, boolean source, EntityTypes.EntityType type, String value) {
        List<String> exact = List.of(value);
        String key = type == null ? "" : EntityTypes.normalise(type.normaliser(), value);
        if (key.isEmpty() || m.spellings() == null) return new Spellings(exact, true, null);
        IndexManifest.EndpointType built = m.endpointTypes() == null ? null
                : source ? m.endpointTypes().source() : m.endpointTypes().target();
        String end = source ? "source" : "target";
        if (built == null || !type.id().equals(built.type()) || !type.normaliser().equals(built.normaliser()))
            return new Spellings(null, false, "the " + end + " column is typed " + type.id() + " (" + type.normaliser()
                    + ") now, and the link index was built " + (built == null ? "with it untyped" : "with it typed " + built.type()
                    + " (" + built.normaliser() + ")") + "; rebuild it");
        if (!m.spellings().containsKey(type.id()) || m.spellings().get(type.id()) == null)
            return new Spellings(null, false, "the link index records no spellings of " + type.id() + " (more than "
                    + IndexManifest.MAX_SPELLINGS + ")");
        List<String> all = m.spellings().get(type.id()).get(key);
        return all == null ? new Spellings(exact, false, null) : new Spellings(all, true, null);
    }

    /** DuckDB's VARCHAR order: unsigned bytes of the UTF-8; NULL first. */
    private static int bytes(String a, String b) {
        if (a == null || b == null) return a == null ? (b == null ? 0 : -1) : 1;
        return Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
