package com.gamma.la.api;

import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.la.core.DatasetProviders;
import com.gamma.la.core.InputFingerprint;
import com.gamma.la.storage.BucketFunction;
import com.gamma.la.storage.IndexBuilder;
import com.gamma.la.storage.IndexManifest;
import com.gamma.la.storage.IndexMapping;
import com.gamma.la.storage.IndexStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * D-3 steps 5 and 6 - the part of "can the edge index answer this read exactly?" that every index-backed route shares
 * (design 4.4): the setting, the choice among the Dataset's published indexes, the staleness gate (ONE definition with
 * {@code GET /inv/index}: {@link IndexStaleness}) and the closed {@link Reason} the response gives when the flat Dataset
 * answered instead. A route adds only its own fit test and its own read ({@link IndexedRecursivePaths},
 * {@link IndexedNeighbors}, {@link IndexedExpand}).
 *
 * <p>The caller has ALREADY passed the base-Dataset view gate ({@link InvRoutes#relationFor}) and hands over the relation SQL
 * it returned - the gate is never skipped, so a Dataset shared away is the same 404 whether or not an index exists.
 */
final class IndexedRead {

    /** Why the flat Dataset answered. The wire value is {@link #name()}. */
    enum Reason {
        index_disabled, no_index, mapping_not_indexed, column_not_indexed, time_zone_not_servable, filter_not_indexed,
        index_stale_refused, depth_over_index_cap, frontier_over_index_cap, index_read_failed, rung_not_indexable
    }

    /** Either the index answered ({@code result} set) or the flat path must ({@code reason} set). */
    record Outcome<R>(R result, long version, boolean stale, String staleReason, Reason reason,
                      List<String> staleCodes, boolean fingerprintKnown, String details) {
        static <R> Outcome<R> flat(Reason r) {
            return flat(r, null);
        }

        static <R> Outcome<R> flat(Reason r, String details) {
            return new Outcome<>(null, 0, false, null, r, List.of(), true, details);
        }

        static <R> Outcome<R> refused(List<String> codes, String details) {
            return new Outcome<>(null, 0, false, null, Reason.index_stale_refused, codes, true, details);
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

        /** The {@code read.index} object of an Investigation expand: what moves with the index, never part of the fingerprint. */
        Map<String, Object> readIndex() {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("version", version);
            s.put("stale", stale);
            s.put("fingerprint", fingerprintKnown ? "known" : "unknown");
            return s;
        }

        /** The {@code read.fallback} object of an Investigation expand the flat Dataset answered: the closed {@link Reason}, never part of the fingerprint. */
        Map<String, Object> readFallback() {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("reason", reason.name());
            if (details != null) s.put("details", details);
            return s;
        }
    }

    /** A route's verdict on one candidate index: {@code failure} null = it fits, with {@code filterSql} the rendered request filter (or null). */
    record Fitted(Reason failure, String filterSql) {
        static Fitted ok(String filterSql) {
            return new Fitted(null, filterSql);
        }

        static Fitted no(Reason failure) {
            return new Fitted(failure, null);
        }
    }

    /** The index chosen and its staleness verdict; {@code declined} (non-null) means the flat path answers. */
    record Selection(Outcome<?> declined, Path dir, IndexManifest manifest, String filterSql, long version, boolean stale,
                     String staleReason, List<String> staleCodes, boolean fingerprintKnown) {
        boolean usable() {
            return declined == null;
        }

        @SuppressWarnings("unchecked")
        <R> Outcome<R> flat() {
            return (Outcome<R>) declined;                                                    // its result is null: the cast carries no value
        }

        <R> Outcome<R> served(R result) {
            return new Outcome<>(result, version, stale, staleReason, null, staleCodes, fingerprintKnown, null);
        }
    }

    private IndexedRead() { }

    private static Selection declined(Outcome<?> o) {
        return new Selection(o, null, null, null, 0, false, null, List.of(), true);
    }

    /**
     * @param fit the route's own test of a candidate whose source and target columns already match: its remaining columns, the
     *            time zone, its {@code filter}; given the mapping and whether the index's time column was read in UTC
     */
    static Selection select(Path writeRoot, Path dataRoot, String relationSql, String datasetId, String sourceCol, String targetCol,
                            BiFunction<IndexMapping, Boolean, Fitted> fit) {
        return select(writeRoot, dataRoot, relationSql, datasetId, sourceCol, targetCol, fit, null);
    }

    /**
     * D7-5 - the version-addressable read: {@code pinned} maps a mapping hash to the version a Draft pinned; an index named there is
     * read at THAT version (a version no longer published reads as unpublished, so the flat Dataset answers), every other index at CURRENT.
     */
    static Selection select(Path writeRoot, Path dataRoot, String relationSql, String datasetId, String sourceCol, String targetCol,
                            BiFunction<IndexMapping, Boolean, Fitted> fit, Map<String, Long> pinned) {
        if (!LinkAnalysisSettings.forRoot(writeRoot).effectiveIndex().enabledInForce()) return declined(Outcome.flat(Reason.index_disabled));

        Path root = writeRoot.resolve(IndexRoutes.INDEX_DIR);
        List<String> hashes;
        try {
            hashes = IndexStore.mappingHashes(root, datasetId);
        } catch (IllegalArgumentException cannotNameADirectory) {
            return declined(Outcome.flat(Reason.no_index));
        }
        record Candidate(Path dir, IndexManifest manifest, String hash) { }
        List<Candidate> published = new ArrayList<>();
        for (String hash : hashes) {
            IndexStore st0 = new IndexStore(root, datasetId, hash);
            Optional<Path> current = pinned != null && pinned.containsKey(hash) ? st0.version(pinned.get(hash)) : st0.current();   // CURRENT is read ONCE
            if (current.isEmpty()) continue;
            try {
                IndexManifest m = IndexManifest.read(current.get());
                if (datasetId.equals(m.dataset())) published.add(new Candidate(current.get(), m, hash));
            } catch (IOException | IllegalArgumentException unreadable) {
                // an unreadable manifest is not an index
            }
        }
        if (published.isEmpty()) return declined(Outcome.flat(Reason.no_index));

        Candidate chosen = null;
        Reason firstFailure = null;
        String filterSql = null;
        for (Candidate c : published) {
            IndexMapping m = c.manifest().mapping();
            if (!m.srcColumn().equalsIgnoreCase(sourceCol) || !m.dstColumn().equalsIgnoreCase(targetCol)) {
                if (firstFailure == null) firstFailure = Reason.mapping_not_indexed;
                continue;
            }
            Fitted f = fit.apply(m, "UTC".equals(c.manifest().timeColZone()));
            if (f.failure() == null) {
                chosen = c;
                filterSql = f.filterSql();
                break;
            }
            if (firstFailure == null || firstFailure == Reason.mapping_not_indexed) firstFailure = f.failure();
        }
        if (chosen == null) return declined(Outcome.flat(firstFailure == null ? Reason.mapping_not_indexed : firstFailure));

        IndexManifest manifest = chosen.manifest();
        // Staleness (decision 6a, ONE definition with GET /inv/index: IndexStaleness). Refuse when removed rows could be exposed:
        // a removed / replaced input file, a changed relation SQL, a different bucket function (Java would read the wrong
        // bucket). A delta of an appended version is read as part of it (step 8), so deltas are never a reason to refuse. Serve flagged when only files were ADDED (the index misses the new rows) or DuckDB differs.
        String duck = null;
        try {
            duck = IndexBuilder.duckdbVersion();
        } catch (RuntimeException unknown) {
            // the server's own version could not be read: say nothing rather than guess
        }
        String mappingHash = chosen.hash();
        InputFingerprint input = InputFingerprintCache.get(writeRoot, datasetId, mappingHash,
                () -> IndexRoutes.currentInput(dataRoot, writeRoot, datasetId));
        IndexStaleness.Result st = IndexStaleness.compute(manifest, IndexBuilder.relationSqlHash(relationSql), input, BucketFunction.NAME, duck);
        boolean refuse = st.removedInput()
                || st.reasons().stream().anyMatch(r -> !r.equals(IndexStaleness.INPUT_FILES_CHANGED) && !r.equals(IndexStaleness.DUCKDB_VERSION_CHANGED));
        if (refuse)
            return declined(Outcome.refused(st.reasons(), st.details().isEmpty() ? "the index has unapplied changes" : String.join("; ", st.details())));
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
        return new Selection(null, chosen.dir(), manifest, filterSql, manifest.version(), staleReason != null, staleReason,
                st.reasons(), st.fingerprintKnown());
    }

    // ── the filter: the request's condition tree rewritten onto the index's column names ─────────────────────────

    /** The rendered predicate over the index columns, or null when a leaf names a column the index cannot answer exactly. */
    static String renderFilter(Object filter, IndexMapping m, boolean utc) {
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
