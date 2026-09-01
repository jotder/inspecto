package com.gamma.inspector;

import com.gamma.consignment.EventTimeBounds;
import com.gamma.etl.*;
import com.gamma.util.DuckDbUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.gamma.inspector.ConsignmentIngestStrategy.configure;
import static com.gamma.inspector.ConsignmentIngestStrategy.consolidatedBaseName;
import static com.gamma.inspector.ConsignmentIngestStrategy.databaseDir;
import static com.gamma.inspector.ConsignmentIngestStrategy.dropTable;
import static com.gamma.inspector.ConsignmentIngestStrategy.msg;
import static com.gamma.inspector.ConsignmentIngestStrategy.openTempDb;
import static com.gamma.inspector.ConsignmentIngestStrategy.partitionColumns;
import static com.gamma.inspector.ConsignmentIngestStrategy.writeAndTrace;

/**
 * Built-in CSV ingest path. Tags every accepted row with {@code __src_id}, transforms once, writes
 * consolidated partition output, and computes the lineage matrix; rejected members are quarantined
 * and their rows never reach the transform.
 *
 * <p>How rows reach the {@code transformed} table depends on the parse engine:
 * <ul>
 *   <li><b>Native {@code read_csv} engine</b> — fully streaming, no intermediate data copies; a
 *       single-member batch streams {@code read_csv → transform → COPY} in one pass (chunked for huge
 *       files), a multi-member batch {@code UNION ALL}s a lazy {@code read_csv} view per member into
 *       one transform, materialising the data exactly once. Handled by {@link NativeCsvStreamingEngine}.</li>
 *   <li><b>Java parse engine</b> — each member is parsed into a per-file temp table and its accepted
 *       rows inserted into a shared {@code raw_input} table before the single transform (the loop in
 *       {@link #ingest}), since the line-by-line parser cannot stream through a view.</li>
 * </ul>
 *
 * <p>Behaviour-identical to the former {@code ConsignmentIngestor.processCsv} — only the
 * commit/audit tail was lifted out into {@link ConsignmentIngestor}.
 */
final class CsvIngestStrategy implements ConsignmentIngestStrategy {

    private static final Logger log = LoggerFactory.getLogger(CsvIngestStrategy.class);

    @Override
    public IngestOutcome ingest(Consignment batch, PipelineConfig cfg) {
        LocalDateTime batchStart = LocalDateTime.now();
        String batchStatus = "SUCCESS";
        String batchError  = "";

        Map<Integer, String> srcIdToFile  = new LinkedHashMap<>();
        List<Consignment.Member>   survivors    = new ArrayList<>();
        List<MemberAudit>    memberAudits = new ArrayList<>();
        long totalInputRows = 0;
        List<PartitionOutput> outputs = List.of();
        List<LineageRow>      lineage = List.of();
        Map<String, EventTimeBounds> bounds = Map.of();
        long castFailures = -1;   // -1 = not measured (no transform ran); never 0, which claims clean

        File tempDb = null;
        try {
            tempDb = openTempDb(cfg, "duckdb_batch_");
            try (Connection conn = DuckDbUtil.openConnection(tempDb)) {
                configure(conn, cfg);

                // Native (read_csv) batches stream with NO per-member raw_f/raw_input table copies:
                //   • single member → one streaming pass (read_csv → transform → COPY), chunked if
                //     the file exceeds the chunking threshold so peak scratch stays bounded;
                //   • many members  → each member becomes a lazy read_csv view, the views are
                //     UNION ALL-ed into one raw_input view, and a single transform pulls them
                //     through — the data is materialised exactly once (the transformed table)
                //     instead of the read_csv → raw_f<id> → raw_input → transformed triple-copy.
                // The Java parse engine keeps the per-member materialise→raw_input path below.
                if (DuckDbCsvIngester.decideNative(batch, cfg)) {
                    // Multi-destination fan-out (sinks:>1) writes through the shared writeAndTrace, which the
                    // single-member streaming/chunked paths bypass (they stream read_csv → COPY straight to
                    // one dir). So a fan-out batch materialises the `transformed` table via the union path —
                    // which writeAndTrace then fans out to every destination. (A huge single file thus
                    // materialises instead of chunking; multi-destination + a chunking-sized file is rare.)
                    boolean fanOut = cfg.sinks().size() > 1;
                    if (batch.members().size() == 1 && !fanOut) {
                        Consignment.Member only = batch.members().get(0);
                        return cfg.chunking().appliesTo(only.file().length())
                                ? NativeCsvStreamingEngine.chunkedIngest(batch, only, cfg, conn, batchStart)
                                : NativeCsvStreamingEngine.streamingIngest(batch, only, cfg, conn, batchStart);
                    }
                    return NativeCsvStreamingEngine.unionStreamingIngest(batch, cfg, conn, batchStart);
                }

                boolean rawCreated = false;
                int memberIdx = 0;
                StepProgress.track(cfg.identity().pipelineName(), batch.batchId(), "parse", 1, 3);
                for (Consignment.Member m : batch.members()) {
                    IngestProgress.track(cfg.identity().pipelineName(), batch.batchId(),
                            m.file().getName(), ++memberIdx, batch.members().size());
                    LocalDateTime mStart = LocalDateTime.now();
                    String tempTable = "raw_f" + m.srcId();
                    IngestResult ing;
                    try {
                        // Reached only when decideNative() chose the Java path for this batch.
                        ing = CsvIngester.ingest(m.file(), conn, m.selection().schema(), cfg, tempTable);
                    } catch (IOException e) {
                        QuarantineManager.quarantine(m.file(), "unreadable", false, cfg);
                        memberAudits.add(MemberAudit.rejected(m, MemberStatus.QUARANTINED_UNREADABLE, msg(e), mStart));
                        dropTable(conn, tempTable);
                        continue;
                    }

                    if (ing.parsedRows() == 0
                            && (ing.errorRows() > 0 || ing.junkCandidateRows() > 0)) {
                        QuarantineManager.quarantine(m.file(), "field_mismatch",
                                ing.errorRows() > 0, cfg);
                        String reason = ing.errorRows() > 0
                                ? String.format("0 valid rows; %d row(s) rejected (field mismatch)", ing.errorRows())
                                : String.format("0 valid rows; %d content line(s) failed column-count", ing.junkCandidateRows());
                        memberAudits.add(MemberAudit.rejected(m, MemberStatus.QUARANTINED_MISMATCH, reason, mStart));
                        dropTable(conn, tempTable);
                        continue;
                    }

                    if (ing.parsedRows() == 0) {
                        // Readable but zero ingestable rows (empty / header-only): quarantine under
                        // `empty` so the file leaves the inbox. An EMPTY batch never backs up or marks,
                        // so leaving it would have the poll loop rediscover and reprocess it forever.
                        QuarantineManager.quarantine(m.file(), QuarantineManager.REASON_EMPTY, false, cfg);
                        memberAudits.add(MemberAudit.rejected(m, MemberStatus.QUARANTINED_EMPTY,
                                "0 valid rows (empty/header-only file)", mStart));
                        dropTable(conn, tempTable);
                        continue;
                    }

                    try (Statement st = conn.createStatement()) {
                        if (!rawCreated) {
                            st.execute("CREATE TABLE raw_input AS SELECT *, CAST(" + m.srcId()
                                    + " AS INTEGER) AS __src_id FROM \"" + tempTable + "\" WHERE false");
                            rawCreated = true;
                        }
                        st.execute("INSERT INTO raw_input SELECT *, " + m.srcId()
                                + " FROM \"" + tempTable + "\"");
                    }
                    dropTable(conn, tempTable);

                    // lineageName: the ENTRY name for an unpack-expanded archive member (never the
                    // index-prefixed temp name — that is workspace bookkeeping, this is DATA), the
                    // plain filename for everything else.
                    srcIdToFile.put(m.srcId(), com.gamma.etl.unpack.UnpackOrigins.lineageName(m.file()));
                    survivors.add(m);
                    totalInputRows += ing.parsedRows();
                    memberAudits.add(MemberAudit.accepted(m, ing.parsedRows(), ing.errorRows(), mStart));
                }

                if (!rawCreated) {
                    batchStatus = "EMPTY";
                } else {
                    Map<String, Object> schema = batch.members().get(0).selection().schema();
                    StepProgress.track(cfg.identity().pipelineName(), batch.batchId(), "transform", 2, 3);
                    DataTransformer.materialize(conn, schema, cfg);
                    // A failed coercion nulls its value and KEEPS the row, so count what was lost —
                    // parse rejects have _errors.csv, this stage had no audit trail at all.
                    castFailures = DataTransformer.countCastFailures(conn, schema, cfg, "raw_input");

                    StepProgress.track(cfg.identity().pipelineName(), batch.batchId(), "sink", 3, 3);
                    var written = writeAndTrace(conn, "transformed", partitionColumns(schema),
                            cfg, databaseDir(batch, cfg), consolidatedBaseName(survivors, batch),
                            batch.batchId(), srcIdToFile, "");   // the batch's ONE write — no scope needed
                    outputs = written.outputs();
                    lineage = written.lineage();
                    bounds  = written.bounds();
                }
            }
        } catch (Exception e) {
            batchStatus = "FAILED";
            batchError  = msg(e);
            log.error("Consignment {} failed during CSV processing", batch.batchId(), e);
        } finally {
            if (tempDb != null) DuckDbUtil.deleteTempDb(tempDb);
        }

        return new IngestOutcome(batchStart, batchStatus, batchError, survivors, memberAudits,
                outputs, lineage, totalInputRows, batch.schemaName(), bounds, castFailures);
    }

}
