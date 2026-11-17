package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;

/**
 * <b>T21 — one cell of the data-plane provenance matrix.</b> A {@link PipelineExecutor.ProvenanceCollector}
 * reports one of these per <em>(node, outgoing relationship)</em> as a pipeline run walks the graph (§11.1):
 * "node {@code nodeId} emitted {@code rowCount} records on relationship {@code rel} during run {@code consignmentId}
 * of pipeline {@code pipelineId}." The structure plane (the {@link com.gamma.pipeline.PipelineGraph} edges) supplies
 * the topology; these rows are the quantities painted onto it. Keyed by {@code (pipelineId, consignmentId)} — the same
 * {@code consignmentId} the run publishes as its {@code ConsignmentEvent} correlation id.
 *
 * <p>⚠ {@code flowId} → {@code pipelineId} is a Tier 2 (breaking, internal) rename — this record is  vocab-allow: cites the pre-rename name
 * {@code @PublicApi}, which is why it did NOT move in the Tier 1 sweep despite being an obvious Flow→Pipeline
 * hit. It renames together with the {@code inspecto_pipeline_provenance} table it keys (vocabulary plan §4),
 * since both are the same coherent unit.
 *
 * <p>The Consignment component was {@code batchId} until 4.0.0 (MoSCoW S4, 2026-11-17), renamed before the tag
 * with the {@code consignment_id} column it is stored in. {@code GET /provenance/batches} still sends a
 * deprecated {@code batchId} alias on each row (removed in 5.0.0); that alias comes from the store's SQL, not
 * from this record.
 *
 * @param pipelineId    the authored pipeline id
 * @param consignmentId the run identity (the correlation key shared with the event store)
 * @param nodeId     the node that emitted the records
 * @param rel        the outgoing relationship (data / dropped / invalid / duplicate / route:&lt;key&gt;)
 * @param rowCount   the number of records emitted on {@code rel}
 * @param runTs      ISO-8601 timestamp of the run
 * @param simulated  PIPELINE-DRYRUN-1: {@code true} when this row came from a dry run — no bytes were
 *                   actually written for the batch it names, and {@code GET /provenance} renders it
 *                   distinctly rather than as a real run's counts.
 */
@PublicApi(since = "4.0.0")
public record ProvenanceRow(String pipelineId, String consignmentId, String nodeId, String rel,
                            long rowCount, String runTs, boolean simulated) {

    /** Pre-PIPELINE-DRYRUN-1 form — {@code simulated = false}. */
    public ProvenanceRow(String pipelineId, String consignmentId, String nodeId, String rel,
                         long rowCount, String runTs) {
        this(pipelineId, consignmentId, nodeId, rel, rowCount, runTs, false);
    }
}
