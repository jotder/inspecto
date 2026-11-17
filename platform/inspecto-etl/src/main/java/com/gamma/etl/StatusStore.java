package com.gamma.etl;

import com.gamma.api.PublicApi;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Abstraction over the service's view of run state. Introduced (M1) with a
 * file-backed implementation reading the existing audit artifacts; M5 swaps in a
 * database-backed implementation behind this same seam, so the API/observability
 * layers built on it don't change.
 *
 * <p>The read surface grew in M3 to back the Control API's query endpoints. Rows
 * are returned as ordered {@code header → value} maps rather than typed records:
 * the audit CSVs carry list-valued columns (output paths/sizes) that don't round-trip
 * cleanly into the write-side record types, and a map serialises directly to JSON.
 */
@PublicApi(since = "4.0.0")
public interface StatusStore {

    /**
     * The set of batch ids previously committed (status {@code SUCCESS}) for
     * {@code cfg}'s pipeline, from the durable commit log. Empty when status is
     * disabled or nothing has run yet.
     */
    Set<String> committedBatches(PipelineConfig cfg);

    /** Consignment audit rows across all runs of the pipeline (newest run last). */
    List<Map<String, String>> batches(PipelineConfig cfg);

    /** Per-member-file audit rows across all runs of the pipeline. */
    List<Map<String, String>> files(PipelineConfig cfg);

    /**
     * Lineage rows (input→output partition counts) across all runs.
     *
     * @param batchId when non-null/blank, restrict to that batch
     */
    List<Map<String, String>> lineage(PipelineConfig cfg, String batchId);

    /** Quarantined inputs: one entry per file under the quarantine tree, with its reason. */
    List<Map<String, String>> quarantine(PipelineConfig cfg);

    /**
     * Unpack ledger rows — one per ARCHIVE per RUN ({@code <pipeline>_unpack_<ts>.csv}), across all
     * runs of the pipeline. The column contract is {@link com.gamma.etl.unpack.UnpackLedger#COLUMNS},
     * declared once there and never restated.
     *
     * <p>{@code default} rather than abstract: a store with no unpack ledger (and every pre-existing
     * third-party implementation of this {@code @PublicApi} seam) truthfully has no rows.
     */
    default List<Map<String, String>> unpack(PipelineConfig cfg) {
        return List.of();
    }

    /**
     * One Consignment's ledger row in {@code cfg}'s pipeline — the NEWEST one — or empty (X5
     * {@code GET /consignments/{id}}). A Consignment can own several rows: a drain appends its SUCCESS after the
     * PARKED row, so the latest row is its state. {@code default} takes the last match of {@link #batches} (oldest
     * first); a database-backed store answers by a query instead of loading the pipeline's whole ledger.
     */
    default java.util.Optional<Map<String, String>> consignment(PipelineConfig cfg, String consignmentId) {
        if (consignmentId == null) return java.util.Optional.empty();
        Map<String, String> last = null;
        for (Map<String, String> r : batches(cfg)) if (consignmentId.equals(r.get("consignment_id"))) last = r;
        return java.util.Optional.ofNullable(last);
    }

    /**
     * One Consignment's member files: the newest row of each file (a file retried within the Consignment has a later
     * row), at most {@code limit}, in ledger order. {@code default} filters {@link #files}; a database-backed store
     * answers by a query.
     */
    default List<Map<String, String>> consignmentFiles(PipelineConfig cfg, String consignmentId, int limit) {
        if (consignmentId == null) return List.of();
        List<Map<String, String>> newestFirst = new java.util.ArrayList<>(
                files(cfg).stream().filter(r -> consignmentId.equals(r.get("consignment_id"))).toList());
        java.util.Collections.reverse(newestFirst);
        return latestPerFile(newestFirst, limit);
    }

    /**
     * Member-file rows given NEWEST FIRST → the first (newest) row of each {@code filename}, at most {@code limit},
     * returned in ledger (oldest-first) order. A row with no filename is kept as it stands.
     */
    static List<Map<String, String>> latestPerFile(List<Map<String, String>> newestFirst, int limit) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<Map<String, String>> out = new java.util.ArrayList<>();
        for (Map<String, String> r : newestFirst) {
            if (out.size() >= Math.max(0, limit)) break;
            String file = r.get("filename");
            if (file == null || seen.add(file)) out.add(r);
        }
        java.util.Collections.reverse(out);
        return out;
    }
}
