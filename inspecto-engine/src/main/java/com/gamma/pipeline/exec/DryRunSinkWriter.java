package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;
import com.gamma.consignment.ConsignmentOutput;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.pipeline.BuiltinNodeType;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineStores;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

/**
 * <b>PIPELINE-DRYRUN-1 gate 2/4 — the no-op {@link PipelineExecutor.SinkWriter} for a whole-pipeline dry
 * run.</b> Writes no bytes ({@link com.gamma.etl.PartitionWriter} is never called), but still records a
 * {@code ConsignmentOutput} row with {@link ConsignmentOutput.State#SIMULATED} — a best-effort preview row
 * count over the sink's already-materialised input relation, and a placeholder path — so the run is visible
 * in the output registry (and {@code GET /provenance}) instead of invisible, per the operator-confirmed
 * design (docs/archived-documents/plans-archive/pipeline-dryrun-design.md).
 *
 * <p>{@code sink.view} nodes are skipped exactly as {@link PartitionSinkWriter} skips them (no bytes either
 * way). The row count query is best-effort: a failure is logged and the row is still recorded, with
 * {@code rows = -1} to mark "count unavailable" rather than a false zero.
 */
@PublicApi(since = "4.0.0")
public final class DryRunSinkWriter implements PipelineExecutor.SinkWriter {

    private static final Logger log = LoggerFactory.getLogger(DryRunSinkWriter.class);

    private final Connection conn;
    private final String consignmentId;
    private final String runId;
    private final String producer;

    public DryRunSinkWriter(Connection conn, String consignmentId, String runId, String producer) {
        this.conn = conn;
        this.consignmentId = consignmentId;
        this.runId = runId;
        this.producer = producer;
    }

    @Override
    public void write(PipelineNode sink, String inputTable) throws Exception {
        // sink.webhook: resolve everything a real send would (config, edition transport, Connection, token)
        // so the dry run refuses exactly what the run would — then send NOTHING. No registry row: a webhook
        // produces no store for a SIMULATED output to stand in for.
        if (BuiltinNodeType.SINK_WEBHOOK.type().equals(sink.type())) {
            WebhookSink.Target t = WebhookSink.plan(sink);
            long rows = previewCount(inputTable);
            log.info("[PIPELINEJOB] dry run: sink '{}' would POST {} row(s) to webhook Connection '{}' — nothing sent",
                    sink.id(), rows < 0 ? "an unknown number of" : String.valueOf(rows), t.webhook().connection());
            return;
        }
        if (sink.type().endsWith(".view")) {
            log.info("[PIPELINEJOB] dry run: sink '{}' ({}) is a logical view — nothing to simulate",
                    sink.id(), sink.type());
            return;
        }
        Object storeCfg = sink.cfg(PipelineStores.CONFIG_STORE);
        String store = storeCfg == null ? sink.id() : storeCfg.toString();
        long rows = previewCount(inputTable);
        log.info("[PIPELINEJOB] dry run: sink '{}' → store '{}' would write {} row(s) — nothing written",
                sink.id(), store, rows < 0 ? "an unknown number of" : String.valueOf(rows));

        if (consignmentId == null) return;   // nothing to register against
        String now = Instant.now().toString();
        ConsignmentOutputStores.record(List.of(new ConsignmentOutput(
                consignmentId, runId, store, null, null,
                "(dry run — not written)", Math.max(rows, 0), 0L, now, 0,
                ConsignmentOutput.State.SIMULATED, null, null, producer)));
    }

    /** {@code count(*)} over the sink's live input relation; {@code -1} when it cannot be determined. */
    private long previewCount(String inputTable) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM \"" + inputTable + "\"")) {
            return rs.next() ? rs.getLong(1) : -1;
        } catch (Exception e) {
            log.warn("[PIPELINEJOB] dry run: could not preview row count for '{}': {}", inputTable, e.getMessage());
            return -1;
        }
    }
}
