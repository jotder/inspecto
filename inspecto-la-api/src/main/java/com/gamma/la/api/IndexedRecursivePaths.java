package com.gamma.la.api;

import com.gamma.control.LinkAnalysisSettings;
import com.gamma.la.core.DatasetProviders;
import com.gamma.la.core.InputFingerprint;
import com.gamma.la.storage.BucketFunction;
import com.gamma.la.storage.IndexBuilder;
import com.gamma.la.storage.IndexManifest;
import com.gamma.la.storage.IndexMapping;
import com.gamma.la.storage.IndexReader;
import com.gamma.la.storage.IndexStore;
import com.gamma.la.storage.IndexedTraversal;
import com.gamma.sql.SqlSandboxPolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * D-3 step 5 - decides whether {@code POST /inv/traversal/recursive-paths} can be answered FROM THE INDEX, and if so walks it
 * (design 4.4). Every decision is a closed {@link Reason} so the response can say why the flat Dataset answered instead.
 *
 * <p>The caller has ALREADY passed the base-Dataset view gate ({@link InvRoutes#relationFor}) and hands over the relation SQL
 * it returned - the gate is never skipped, so a Dataset shared away is the same 404 whether or not an index exists.
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

    /** Why the flat Dataset answered. The wire value is {@link #name()}. */
    enum Reason {
        index_disabled, no_index, mapping_not_indexed, column_not_indexed, time_zone_not_servable, filter_not_indexed,
        index_stale_refused, depth_over_index_cap, frontier_over_index_cap, index_read_failed
    }

    record Request(String datasetId, String sourceCol, String targetCol, String weightCol, String tsCol, Object filter,
                   String startNode, String targetNode, boolean undirected, int maxDepth, int maxEdges, int limit,
                   boolean monotonic, Double maxHours) { }

    /** Either the index answered ({@code result} set) or the flat path must ({@code reason} set). */
    record Outcome(IndexedTraversal.Result result, long version, boolean stale, String staleReason, Reason reason,
                   List<String> staleCodes, boolean fingerprintKnown, String details) {
        static Outcome flat(Reason r) {
            return new Outcome(null, 0, false, null, r, List.of(), true, null);
        }

        static Outcome refused(List<String> codes, String details) {
            return new Outcome(null, 0, false, null, Reason.index_stale_refused, codes, true, details);
        }

        boolean served() {
            return result != null;
        }

        /** The {@code source} object of the response. */
        Map<String, Object> source() {
            Map<String, Object> s = new LinkedHashMap<>();
            if (served()) {
                s.put("kind", "index");
                s.put("version", version);
                s.put("stale", stale);
                if (staleReason != null) s.put("staleReason", staleReason);
                s.put("fingerprint", fingerprintKnown ? "known" : "unknown");
            } else {
                s.put("kind", "dataset");
                s.put("reason", reason.name());
                if (details != null) s.put("details", details);
            }
            return s;
        }
    }

    private IndexedRecursivePaths() { }

    /**
     * @param beforeRead runs once the request is known to be servable and before anything is read - the caller's four-eyes
     *                   refusal, so the index path never reads what the flat path would have refused
     */
    static Outcome attempt(Path writeRoot, Path dataRoot, String relationSql, Request rq, SqlSandboxPolicy policy, Runnable beforeRead) {
        if (!LinkAnalysisSettings.forRoot(writeRoot).effectiveIndex().enabledInForce()) return Outcome.flat(Reason.index_disabled);

        Path root = writeRoot.resolve(IndexRoutes.INDEX_DIR);
        List<String> hashes;
        try {
            hashes = IndexStore.mappingHashes(root, rq.datasetId());
        } catch (IllegalArgumentException cannotNameADirectory) {
            return Outcome.flat(Reason.no_index);
        }
        record Candidate(Path dir, IndexManifest manifest, String hash) { }
        List<Candidate> published = new ArrayList<>();
        for (String hash : hashes) {
            Optional<Path> current = new IndexStore(root, rq.datasetId(), hash).current();   // CURRENT is read ONCE: this is the pinned version
            if (current.isEmpty()) continue;
            try {
                IndexManifest m = IndexManifest.read(current.get());
                if (rq.datasetId().equals(m.dataset())) published.add(new Candidate(current.get(), m, hash));
            } catch (IOException | IllegalArgumentException unreadable) {
                // an unreadable manifest is not an index
            }
        }
        if (published.isEmpty()) return Outcome.flat(Reason.no_index);

        Candidate chosen = null;
        Reason firstFailure = null;
        String filterSql = null;
        for (Candidate c : published) {
            IndexMapping m = c.manifest().mapping();
            if (!m.srcColumn().equalsIgnoreCase(rq.sourceCol()) || !m.dstColumn().equalsIgnoreCase(rq.targetCol())) {
                if (firstFailure == null) firstFailure = Reason.mapping_not_indexed;
                continue;
            }
            Reason fail = null;
            boolean utc = "UTC".equals(c.manifest().timeColZone());
            if (rq.weightCol() != null && !rq.weightCol().equalsIgnoreCase(m.weightColumn())) fail = Reason.column_not_indexed;
            else if (rq.tsCol() != null && !rq.tsCol().equalsIgnoreCase(m.timeColumn())) fail = Reason.column_not_indexed;
            else if (rq.tsCol() != null && !utc) fail = Reason.time_zone_not_servable;
            String sql = null;
            if (fail == null && rq.filter() != null) {
                sql = renderFilter(rq.filter(), m, utc);
                if (sql == null) fail = Reason.filter_not_indexed;
            }
            if (fail == null) {
                chosen = c;
                filterSql = sql;
                break;
            }
            if (firstFailure == null || firstFailure == Reason.mapping_not_indexed) firstFailure = fail;
        }
        if (chosen == null) return Outcome.flat(firstFailure == null ? Reason.mapping_not_indexed : firstFailure);

        IndexManifest manifest = chosen.manifest();
        // Staleness (decision 6a, ONE definition with GET /inv/index: IndexStaleness). Refuse when removed rows could be exposed:
        // a removed / replaced input file, a changed relation SQL, a different bucket function (Java would read the wrong
        // bucket) or unapplied deltas. Serve flagged when only files were ADDED (the index misses the new rows) or DuckDB differs.
        String duck = null;
        try {
            duck = IndexBuilder.duckdbVersion();
        } catch (RuntimeException unknown) {
            // the server's own version could not be read: say nothing rather than guess
        }
        String mappingHash = chosen.hash();
        InputFingerprint input = InputFingerprintCache.get(writeRoot, rq.datasetId(), mappingHash,
                () -> IndexRoutes.currentInput(dataRoot, writeRoot, rq.datasetId()));
        IndexStaleness.Result st = IndexStaleness.compute(manifest, IndexBuilder.relationSqlHash(relationSql), input, BucketFunction.NAME, duck);
        boolean refuse = st.removedInput() || !manifest.deltas().isEmpty()
                || st.reasons().stream().anyMatch(r -> !r.equals(IndexStaleness.INPUT_FILES_CHANGED) && !r.equals(IndexStaleness.DUCKDB_VERSION_CHANGED));
        if (refuse) return Outcome.refused(st.reasons(), st.details().isEmpty() ? "the index has unapplied changes" : String.join("; ", st.details()));
        List<String> reasonText = new ArrayList<>();
        for (int i = 0; i < st.reasons().size(); i++) {
            String code = st.reasons().get(i);
            if (code.equals(IndexStaleness.INPUT_FILES_CHANGED)) {
                int recorded = manifest.inputFiles() == null ? 0 : manifest.inputFiles().size();
                reasonText.add(code + ": " + Math.max(0, input.files().size() - recorded) + " files added since the build");
            } else {
                reasonText.add(st.details().get(i));
            }
        }
        String staleReason = reasonText.isEmpty() ? null : String.join("; ", reasonText);

        if (rq.maxDepth() > IndexedTraversal.MAX_DEPTH) return Outcome.flat(Reason.depth_over_index_cap);

        beforeRead.run();                                                                    // four-eyes, exactly as the flat path
        IndexedTraversal.Params p = new IndexedTraversal.Params(rq.startNode(), rq.targetNode(), rq.undirected(), rq.maxDepth(),
                rq.maxEdges(), rq.limit(), rq.tsCol() != null, rq.monotonic(), rq.maxHours(), filterSql);
        try (IndexReader reader = IndexReader.open(chosen.dir(), manifest, policy)) {
            IndexedTraversal.Result r = IndexedTraversal.walk(reader, p);
            return new Outcome(r, manifest.version(), staleReason != null, staleReason, null, st.reasons(), st.fingerprintKnown(), null);
        } catch (IndexedTraversal.FrontierOverCap over) {
            return Outcome.flat(Reason.frontier_over_index_cap);                             // the partial walk is discarded
        } catch (SQLException | IOException | RuntimeException unreadable) {
            return Outcome.flat(Reason.index_read_failed);
        }
    }

    // ── the filter: the request's condition tree rewritten onto the index's column names ─────────────────────────

    /** The rendered predicate over the index columns, or null when a leaf names a column the index cannot answer exactly. */
    private static String renderFilter(Object filter, IndexMapping m, boolean utc) {
        Object rewritten = rewrite(filter, m, utc);
        if (rewritten == null) return null;
        try {
            return DatasetProviders.require().predicate(rewritten);
        } catch (IllegalArgumentException notAGroup) {
            return null;                                                                     // the flat path answers that with its 422
        }
    }

    /** A deep copy of the tree with every leaf {@code field} replaced by its index column; null when one cannot be. */
    private static Object rewrite(Object node, IndexMapping m, boolean utc) {
        if (!(node instanceof Map<?, ?> src)) return node;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : src.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        boolean group = "group".equals(src.get("kind")) || (!"condition".equals(src.get("kind")) && (src.containsKey("items") || src.containsKey("conditions")));
        if (group) {
            for (String key : new String[] {"items", "conditions"}) {
                if (out.get(key) instanceof List<?> items) {
                    List<Object> copy = new ArrayList<>();
                    for (Object it : items) {
                        Object r = rewrite(it, m, utc);
                        if (r == null) return null;
                        copy.add(r);
                    }
                    out.put(key, copy);
                }
            }
            return out;
        }
        Object raw = src.get("field");
        String field = raw == null ? "" : String.valueOf(raw);
        if (field.isEmpty()) return out;                                                     // an incomplete leaf renders nothing
        String column = indexColumn(field, m, utc);
        if (column == null) return null;
        out.put("field", column);
        return out;
    }

    private static String indexColumn(String field, IndexMapping m, boolean utc) {
        if (field.equalsIgnoreCase(m.srcColumn())) return "src";
        if (field.equalsIgnoreCase(m.dstColumn())) return "dst";
        if (m.kindColumn() != null && field.equalsIgnoreCase(m.kindColumn())) return "kind";
        if (m.timeColumn() != null && field.equalsIgnoreCase(m.timeColumn())) return utc ? "ts" : null;
        for (int i = 0; i < m.attributeColumns().size(); i++)
            if (field.equalsIgnoreCase(m.attributeColumns().get(i))) return "a" + i;
        return null;                                                                         // incl. the weight column: stored as DOUBLE, not the original type
    }
}
