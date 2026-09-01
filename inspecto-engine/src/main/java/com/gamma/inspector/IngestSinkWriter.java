package com.gamma.inspector;

import com.gamma.consignment.ConsignmentOutputs;
import com.gamma.consignment.EventTimeBounds;
import com.gamma.etl.LineageCollector;
import com.gamma.etl.LineageRow;
import com.gamma.etl.PartitionOutput;
import com.gamma.etl.PartitionWriter;
import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.exec.PipelineExecutor;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The INGEST path's sink writer for a branch-aware (route:) batch — arming plan S2. One route branch
 * commits here per call, and each branch writes to the {@code sinks[]} destination its route key was
 * paired with at lift time (the branch's declared {@code database}), with that destination's own
 * format / compression / {@code filename_column} — exactly the per-destination write
 * {@link ConsignmentIngestStrategy#writeAndTrace} does for the flat path, minus the fan-out (a route branch
 * is one destination by construction).
 *
 * <p><b>Why not {@code PartitionSinkWriter}:</b> that writer is flow-job-shaped — it writes every
 * store under one {@code dataDir} root, ignores the sink node's {@code database}, passes no
 * {@code srcIdToFile} (so {@code filename_column} translation is lost), collects no lineage, and
 * registers §11.3 rows itself. The ingest path needs the opposite on every count: destination-rooted
 * writes, lineage per branch (the Run Detail input→output matrix), and <b>no registration here</b> —
 * {@code ConsignmentIngestor.finalizeSource} registers from the returned lineage, the same code the flat
 * path runs, so the graph path cannot drift from it (and cannot double-count).
 *
 * <p>Accumulates outputs / lineage / event-time bounds across branches; the caller drains them into
 * the flat {@code Written} shape so {@code commit}/{@code writeAudit} run UNCHANGED.
 */
final class IngestSinkWriter implements PipelineExecutor.SinkWriter {

    private final Connection conn;
    private final PipelineConfig cfg;
    private final List<String> partCols;
    private final String dbDir;
    private final String baseName;
    private final String batchId;
    private final Map<Integer, String> srcIdToFile;

    private final List<PartitionOutput> outputs = new ArrayList<>();
    private final List<LineageRow> lineage = new ArrayList<>();
    private final Map<String, EventTimeBounds> bounds = new HashMap<>();

    IngestSinkWriter(Connection conn, PipelineConfig cfg, List<String> partCols, String dbDir,
                     String baseName, String batchId, Map<Integer, String> srcIdToFile) {
        this.conn = conn;
        this.cfg = cfg;
        this.partCols = partCols;
        this.dbDir = dbDir;
        this.baseName = baseName;
        this.batchId = batchId;
        this.srcIdToFile = srcIdToFile;
    }

    @Override
    public void write(PipelineNode sink, String inputTable) throws Exception {
        PipelineConfig.Sink dest = destinationOf(sink);
        // The same re-rooting rule as writeAndTrace's fan-out: dbDir's suffix beyond dirs.database
        // (e.g. the table subdir) is preserved under the destination's own database root.
        java.nio.file.Path rel = java.nio.file.Paths.get(cfg.dirs().database())
                .relativize(java.nio.file.Paths.get(dbDir));
        String destDir = java.nio.file.Paths.get(dest.database()).resolve(rel).toString();

        List<PartitionOutput> outs = PartitionWriter.write(conn, inputTable, destDir,
                dest.format(), dest.compression(), baseName, partCols,
                dest.filenameColumn(), srcIdToFile);
        outputs.addAll(outs);
        lineage.addAll(LineageCollector.collect(conn, inputTable, batchId, srcIdToFile, outs, partCols));
        // §3.1 bounds are per BRANCH relation here (each branch writes different rows), unlike the
        // flat fan-out's compute-once-before-the-loop (where every destination writes identical rows).
        Map<String, EventTimeBounds> byPartition =
                ConsignmentOutputs.boundsByPartition(conn, inputTable, partCols);
        for (PartitionOutput o : outs) {
            EventTimeBounds b = byPartition.get(o.partition());
            if (b != null) bounds.put(o.outputFile(), b);
        }
    }

    /**
     * The {@code sinks[]} destination this sink node was paired with at lift time — matched by the
     * node's {@code database}, the SAME join key {@code PipelineLift.branchKeyForDatabase} pairs
     * route edges with (referenced, never restated: a second matching rule would drift). A sink node
     * without a database (never emitted by the lift for a sinks[] config) fails loudly.
     */
    private PipelineConfig.Sink destinationOf(PipelineNode sink) {
        Object db = sink.cfg("database");
        if (db != null) {
            for (PipelineConfig.Sink d : cfg.sinks())
                if (db.toString().equals(d.database())) return d;
        }
        throw new IllegalStateException("sink node '" + sink.id() + "' names database '" + db
                + "', which matches no sinks[] destination — the branch↔sink pairing is by database");
    }

    List<PartitionOutput> outputs() { return outputs; }
    List<LineageRow> lineage() { return lineage; }
    Map<String, EventTimeBounds> bounds() { return new LinkedHashMap<>(bounds); }
}
