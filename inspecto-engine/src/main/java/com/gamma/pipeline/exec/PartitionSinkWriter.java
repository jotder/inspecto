package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.ConsignmentOutputs;
import com.gamma.consignment.EventTimeBounds;
import com.gamma.etl.PartitionOutput;
import com.gamma.etl.PartitionWriter;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineStores;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.gamma.util.Values.str;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>T32 Phase A — the real sink write for a flow job.</b> A {@link PipelineExecutor.SinkWriter} that
 * persists each committed sink branch's relation to its declared {@code store} under
 * {@code <dataDir>/<store>}, reusing the production {@link PartitionWriter} (the same idempotent,
 * {@code OVERWRITE_OR_IGNORE} Hive-partitioned write the legacy engine and the enrichment engine use).
 *
 * <p>A sink declares its {@code store}, {@code format} ({@code PARQUET}/{@code CSV}, default Parquet),
 * optional {@code compression}, and optional {@code partitions}. Both modes delegate to
 * {@link PartitionWriter} (E1): declared partitions write Hive-partitioned, none write a single
 * unpartitioned file — one writer owns staging + atomic reveal for both, on every lane.
 *
 * <p>{@code sink.view} subtypes ({@link PipelineStores.Produced#restsOnDisk() non-resting}) write no bytes —
 * this writer skips the byte write; the flow job registers the view's durable definition instead
 * ({@code com.gamma.job.PipelineJobRunner.registerViews} → {@link com.gamma.pipeline.ViewStore}, T32 Phase C).
 */
@PublicApi(since = "4.0.0")
public final class PartitionSinkWriter implements PipelineExecutor.SinkWriter {

    private static final Logger log = LoggerFactory.getLogger(PartitionSinkWriter.class);

    private final Connection conn;
    private final String dataDir;
    private final String baseName;
    private final String consignmentId;
    private final String producer;
    private final List<PartitionOutput> outputs = new ArrayList<>();
    private final Map<String, Long> rowsByStore = new LinkedHashMap<>();
    private long totalRows = 0L;

    /**
     * @param conn          the DuckDB connection holding each sink branch's input relation
     * @param dataDir       the data root under which each store is written as a sub-directory
     * @param baseName      the output file stem ({@code <baseName>_out.<ext>}); typically the pipeline/job id
     * @param consignmentId the unit of work these files belong to, recorded in the §11.3 output registry. This
     *                      writer registers its own files (it has the per-partition counts and the target store
     *                      name in scope); {@code BatchProcessor.finalizeSource} deliberately does not, so the
     *                      graph path does not double-count. May be {@code null} — nothing is registered then.
     */
    public PartitionSinkWriter(Connection conn, String dataDir, String baseName, String consignmentId) {
        this(conn, dataDir, baseName, consignmentId, null);
    }

    /**
     * As above, with the {@code producer} recorded on every registry row this writer creates — the pipeline
     * identity the §3.6 per-stream watermark needs. Without it a table written by this path has an
     * <em>unattributed</em> producer, which suppresses its watermark entirely (and correctly: a stream
     * receiving writes nobody owns cannot report completeness).
     */
    public PartitionSinkWriter(Connection conn, String dataDir, String baseName, String consignmentId,
                               String producer) {
        this.conn = conn;
        this.dataDir = dataDir;
        this.baseName = baseName;
        this.consignmentId = consignmentId;
        this.producer = producer;
    }

    @Override
    public void write(PipelineNode sink, String inputTable) throws Exception {
        if (sink.type().endsWith(".view")) {     // logical store — no bytes; PipelineJobRunner registers its definition
            log.info("[PIPELINEJOB] sink '{}' ({}) is a logical view — no bytes (definition registered by the pipeline job)",
                    sink.id(), sink.type());
            return;
        }
        Object storeCfg = sink.cfg(PipelineStores.CONFIG_STORE);
        if (storeCfg == null || storeCfg.toString().isBlank())
            throw new IllegalStateException("sink '" + sink.id() + "' declares no '"
                    + PipelineStores.CONFIG_STORE + "' to write to");
        String store = storeCfg.toString();
        String format = upper(sink.cfg("format"), "PARQUET");
        String compression = str(sink.cfg("compression"));
        // A partition entry with no 'column' names no directory segment, so there is nothing to write it under.
        // Refused here rather than skipped: dropping it would silently change the store's layout — in the
        // single-entry case, from partitioned to a lone unpartitioned file no downstream glob expects.
        List<String> badParts = SinkPartitions.entriesWithoutColumn(sink.cfg("partitions"));
        if (!badParts.isEmpty())
            throw new IllegalStateException("sink '" + sink.id() + "' has a partition entry declaring no "
                    + "'column': " + badParts);
        List<String> partCols = SinkPartitions.columns(sink.cfg("partitions"));
        String dir = dataDir.replace("\\", "/") + "/" + store;

        // E1: one writer owns BOTH modes — an empty partCols list is the unpartitioned single-file
        // write, with the same staging + atomic reveal the partitioned path always had.
        List<PartitionOutput> outs =
                PartitionWriter.write(conn, inputTable, dir, format, compression, baseName, partCols, List.of());
        outputs.addAll(outs);
        // Per-partition counts serve both purposes: they give every registry row a real row_count (§11.3 slice 2)
        // and they sum to the branch total this used to get from a separate COUNT(*) over the whole relation.
        Map<String, Long> rowsByPartition = ConsignmentOutputs.countByPartition(conn, inputTable, partCols);
        long branchRows = rowsByPartition.values().stream().mapToLong(Long::longValue).sum();
        totalRows += branchRows;
        // Per store, not per branch: two sinks may target one store, and a Run Artifact naming that store has
        // to report what the store received rather than what one branch contributed.
        rowsByStore.merge(store, branchRows, Long::sum);
        if (consignmentId != null)
            ConsignmentOutputStores.record(ConsignmentOutputs.fromPartitionCounts(
                    consignmentId, null, store, outs, rowsByPartition,
                    boundsFor(sink, inputTable, partCols), producer));
        log.info("[PIPELINEJOB] sink '{}' → store '{}': {} file(s){}",
                sink.id(), store, outs.size(), partCols.isEmpty() ? " (unpartitioned)" : " partitioned by " + partCols);
    }

    /** Partition files written across every sink branch (one entry per file). */
    public List<PartitionOutput> outputs() { return List.copyOf(outputs); }

    /** Total rows written across every sink branch. */
    public long totalRows() { return totalRows; }

    /** Rows written per target store, in first-write order — what a per-store Run Artifact reports (§5-B). */
    public Map<String, Long> rowsByStore() { return Map.copyOf(rowsByStore); }

    // ── helpers ──────────────────────────────────────────────────────────────────

    // E1: writeUnpartitioned/copyOptions were merged down into PartitionWriter — one class owns
    // partitioned COPY … PARTITION_BY and the unpartitioned single-file COPY, staging + atomic
    // reveal for both, so the two lanes cannot drift on write semantics.

    /**
     * Event-time bounds per partition for this sink branch, or an empty map when the sink declares nothing that
     * identifies event time (addressing §3.1, extended to the Pipeline-sink path 2026-08-10).
     *
     * <p><b>The declaration is the {@code source} of a {@code partitions[]} entry</b> — the raw column a
     * partition was derived from, exactly the word and meaning {@code PartitionDef.source} already carries on
     * the ingest schema. One concept, one word; no new config key, since {@code partitions[]} has always
     * accepted map entries. When entries are bare strings, or carry no {@code source}, there is nothing here to
     * aggregate and bounds stay absent. {@link SinkPartitions#eventTimeSource} owns that rule, so
     * {@link ComponentPreview} warns on exactly the declarations this records nothing for.
     *
     * <p><b>Nothing is inferred.</b> A relation's only {@code TIMESTAMP} column is not evidence that it is event
     * time, and neither is a partition column that happens to be a date — that one merely restates the partition
     * key, at exactly the resolution {@code record_day} already has. Bounds that are confidently wrong are worse
     * than bounds that are absent, because a selector prunes on them and a watermark closes windows on them,
     * whereas absent reads as "unknown" everywhere by construction (D3).
     *
     * <p>{@code TRY_CAST} rather than {@code CAST}: a {@code VARCHAR} source that does not parse yields NULL,
     * which {@code min}/{@code max} skip, and a partition where every row failed contributes no entry at all —
     * the same "no honest bound" rule {@link ConsignmentOutputs#boundsByPartition} already applies.
     */
    private Map<String, EventTimeBounds> boundsFor(PipelineNode sink, String inputTable, List<String> partCols) {
        String source = SinkPartitions.eventTimeSource(sink.cfg("partitions"));
        if (source == null) return Map.of();
        try {
            return ConsignmentOutputs.boundsByPartition(conn, inputTable, partCols,
                    "TRY_CAST(\"" + source + "\" AS TIMESTAMP)");
        } catch (Exception e) {
            // Best-effort, like every other addressing column: a store that cannot be measured records no
            // bounds rather than failing a write whose bytes have already landed.
            log.warn("[PIPELINEJOB] could not measure event-time bounds over '{}' for sink '{}': {}",
                    source, sink.id(), e.getMessage());
            return Map.of();
        }
    }


    private static String upper(Object o, String fallback) {
        return o == null || o.toString().isBlank() ? fallback : o.toString().toUpperCase();
    }
}
