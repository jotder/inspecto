package com.gamma.inspector;

import com.gamma.consignment.EventTimeBounds;
import com.gamma.etl.Consignment;
import com.gamma.etl.LineageRow;
import com.gamma.etl.PartitionOutput;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * The result of a {@link ConsignmentIngestStrategy#ingest} pass — everything
 * {@link ConsignmentIngestor} needs to commit and audit one batch, independent of which
 * ingest path produced it.
 *
 * @param batchStart     when ingest began (audit start timestamp)
 * @param status         {@code "SUCCESS"}, {@code "EMPTY"}, or {@code "FAILED"}
 * @param error          failure message ({@code ""} when not failed)
 * @param survivors      members that contributed accepted rows (drive commit)
 * @param memberAudits   per-input-file audit rows (drive the file-level audit)
 * @param outputs        partition files written
 * @param lineage        input→output row-count matrix
 * @param totalInputRows total accepted input rows across all members
 * @param schemaLabel    audit schema label — {@code batch.schemaName()} for CSV, or the
 *                       comma-joined segment keys for the plugin path
 * @param bounds         §3.1 event-time range per output file path, for the output registry. Empty — never
 *                       null — when the path wrote nothing, materialised no event time, or the schema
 *                       declares no date partition.
 * @param schemaByOutput <b>output file path → the schema/segment key that wrote it</b>, for the output
 *                       registry. Empty — never null — on a single-schema path, where every row already
 *                       belongs to {@code batch.table()}.
 *                       <p>🔴 Why this exists ({@code batches` vs a per-schema row set}, decided 2026-09-15):
 *                       a segmented ingest writes one output set PER SCHEMA, but {@code outputs} pools them
 *                       into one flat list and the audit row collapses them to a comma-joined
 *                       {@link #schemaLabel} plus the batch's single table. The segment identity then
 *                       survived only inside each file's PATH, so "which schemas did this ingest write, and
 *                       how much to each" was unanswerable without parsing paths. This map keeps the
 *                       attribution the write already knew.
 *                       <p>⚠ Keyed by output FILE, deliberately mirroring {@code bounds} — a partition key
 *                       is not unique across segments, and the two maps are built in the same loop.
 * @param castFailures   values a declared type coercion silently nulled while the row was KEPT
 *                       ({@link com.gamma.etl.DataTransformer#countCastFailures}). <b>{@code -1} means
 *                       NOT MEASURED</b> — the only two states are "measured" ({@code >= 0}) and
 *                       "unknown"; a path that cannot measure must never report {@code 0}, which would
 *                       claim a clean batch. The audit ledger writes unknown as a blank cell.
 */
record IngestOutcome(LocalDateTime batchStart,
                     String status,
                     String error,
                     List<Consignment.Member> survivors,
                     List<MemberAudit> memberAudits,
                     List<PartitionOutput> outputs,
                     List<LineageRow> lineage,
                     long totalInputRows,
                     String schemaLabel,
                     Map<String, EventTimeBounds> bounds,
                     Map<String, String> schemaByOutput,
                     long castFailures) {

    /** Never null — a path that tracked no per-schema attribution reads as "all one schema", not as absent. */
    IngestOutcome {
        schemaByOutput = schemaByOutput == null ? Map.of() : schemaByOutput;
    }

    /** Unmeasured form — the coercion count defaults to {@code -1} ("not measured"), never {@code 0}. */
    IngestOutcome(LocalDateTime batchStart, String status, String error, List<Consignment.Member> survivors,
                  List<MemberAudit> memberAudits, List<PartitionOutput> outputs, List<LineageRow> lineage,
                  long totalInputRows, String schemaLabel, Map<String, EventTimeBounds> bounds) {
        this(batchStart, status, error, survivors, memberAudits, outputs, lineage, totalInputRows,
                schemaLabel, bounds, Map.of(), -1);
    }

    /**
     * Segmented form — per-output-file event-time bounds AND the per-output schema attribution, the two maps
     * a multi-schema write builds in the same loop.
     */
    IngestOutcome(LocalDateTime batchStart, String status, String error, List<Consignment.Member> survivors,
                  List<MemberAudit> memberAudits, List<PartitionOutput> outputs, List<LineageRow> lineage,
                  long totalInputRows, String schemaLabel, Map<String, EventTimeBounds> bounds,
                  Map<String, String> schemaByOutput) {
        this(batchStart, status, error, survivors, memberAudits, outputs, lineage, totalInputRows,
                schemaLabel, bounds, schemaByOutput, -1);
    }

    /**
     * Measured single-schema form — bounds and a coercion count, no per-output attribution needed because
     * every output on these paths belongs to the batch's one table.
     */
    IngestOutcome(LocalDateTime batchStart, String status, String error, List<Consignment.Member> survivors,
                  List<MemberAudit> memberAudits, List<PartitionOutput> outputs, List<LineageRow> lineage,
                  long totalInputRows, String schemaLabel, Map<String, EventTimeBounds> bounds,
                  long castFailures) {
        this(batchStart, status, error, survivors, memberAudits, outputs, lineage, totalInputRows,
                schemaLabel, bounds, Map.of(), castFailures);
    }

    /** No-bounds form — {@code EMPTY}/{@code FAILED} outcomes and any path that wrote no output files. */
    IngestOutcome(LocalDateTime batchStart, String status, String error, List<Consignment.Member> survivors,
                  List<MemberAudit> memberAudits, List<PartitionOutput> outputs, List<LineageRow> lineage,
                  long totalInputRows, String schemaLabel) {
        this(batchStart, status, error, survivors, memberAudits, outputs, lineage, totalInputRows,
                schemaLabel, Map.of(), Map.of(), -1);
    }
}
