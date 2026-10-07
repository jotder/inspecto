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
}
