package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;

/**
 * <b>T21 — one cell of the data-plane provenance matrix.</b> A {@link PipelineExecutor.ProvenanceCollector}
 * reports one of these per <em>(node, outgoing relationship)</em> as a pipeline run walks the graph (§11.1):
 * "node {@code nodeId} emitted {@code rowCount} records on relationship {@code rel} during run {@code batchId}
 * of pipeline {@code flowId}." The structure plane (the {@link com.gamma.pipeline.PipelineGraph} edges) supplies
 * the topology; these rows are the quantities painted onto it. Keyed by {@code (flowId, batchId)} — the same
 * {@code batchId} the run publishes as its {@code BatchEvent} correlation id.
 *
 * <p>⚠ The {@code flowId} component is a DELIBERATE Flow→Pipeline keep, not an oversight: this record is
 * {@code @PublicApi}, so renaming the accessor breaks external embedders. It renames in Tier 2 alongside the
 * {@code inspecto_flow_provenance} table it keys (vocabulary plan §4) — do not "fix" it in a Tier-1 sweep.
 *
 * @param flowId   the authored pipeline id (component name kept — see above)
 * @param batchId  the run identity (the correlation key shared with the event store)
 * @param nodeId   the node that emitted the records
 * @param rel      the outgoing relationship (data / dropped / invalid / duplicate / route:&lt;key&gt;)
 * @param rowCount the number of records emitted on {@code rel}
 * @param runTs    ISO-8601 timestamp of the run
 */
@PublicApi(since = "4.3.0")
public record ProvenanceRow(String flowId, String batchId, String nodeId, String rel,
                            long rowCount, String runTs) {}
