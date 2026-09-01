package com.gamma.inspector;

import com.gamma.etl.*;
import com.gamma.util.DuckDbUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.gamma.inspector.ConsignmentIngestStrategy.*;

/**
 * Generation-mode execution for the streaming plugin-ingester path. Each batch member is streamed
 * into a {@link DuckDbRecordSink} that flushes bounded generations to partitioned output as it goes,
 * so a single huge file is processed with bounded heap and scratch. No cross-member union is
 * performed — each member writes its own per-generation output files.
 *
 * <p>Selected by {@link StreamingPluginIngestStrategy} when the largest batch member meets or
 * exceeds {@code processing.streaming.large_file_bytes}.
 */
final class GenerationModeIngester {

    private static final Logger log = LoggerFactory.getLogger(StreamingPluginIngestStrategy.class);

    private GenerationModeIngester() {}

    static IngestOutcome run(Consignment batch, PipelineConfig cfg, long flushRows) {
        LocalDateTime batchStart = LocalDateTime.now();
        String batchStatus = "SUCCESS";
        String batchError  = "";

        StreamingFileIngester ingester = instantiate(cfg);

        List<Consignment.Member> survivors    = new ArrayList<>();
        List<MemberAudit>  memberAudits = new ArrayList<>();
        List<PartitionOutput> allOutputs = new ArrayList<>();
        List<LineageRow>      allLineage = new ArrayList<>();
        long totalInputRows = 0;

        File tempDb = null;
        try {
            tempDb = openTempDb(cfg, "duckdb_stream_");
            try (var conn = DuckDbUtil.openConnection(tempDb)) {
                configure(conn, cfg);

                int memberIdx = 0;
                for (Consignment.Member m : batch.members()) {
                    IngestProgress.track(cfg.identity().pipelineName(), batch.batchId(),
                            m.file().getName(), ++memberIdx, batch.members().size());
                    LocalDateTime mStart = LocalDateTime.now();
                    String stem = CsvIngester.stripExtensions(m.file().getName());
                    // lineageName: the ENTRY name for an unpack-expanded archive member, the plain
                    // filename otherwise — the temp name is workspace bookkeeping, never DATA.
                    try (DuckDbRecordSink sink = new DuckDbRecordSink(
                            conn, m.srcId(), cfg, batch.batchId(), stem,
                            com.gamma.etl.unpack.UnpackOrigins.lineageName(m.file()), flushRows)) {
                        try {
                            ingester.ingest(m.file(), sink, m.srcId(), cfg);
                            sink.finish();
                        } catch (SinkFlushException e) {
                            throw e;   // framework/schema fault → fail the batch (don't quarantine)
                        } catch (Exception e) {
                            discardRevealed(sink, m);
                            QuarantineManager.quarantine(m.file(), "unreadable", false, cfg);
                            memberAudits.add(MemberAudit.rejected(m, MemberStatus.QUARANTINED_UNREADABLE, msg(e), mStart));
                            continue;
                        }

                        long memberParsed = sink.parsedRows();
                        long memberErrors = sink.errorRows();
                        if (memberParsed == 0) {
                            discardRevealed(sink, m);
                            QuarantineManager.quarantine(m.file(), "field_mismatch", memberErrors > 0, cfg);
                            memberAudits.add(MemberAudit.rejected(m, MemberStatus.QUARANTINED_MISMATCH,
                                    "0 valid rows across all segments", mStart));
                            continue;
                        }

                        survivors.add(m);
                        totalInputRows += memberParsed;
                        allOutputs.addAll(sink.outputs());
                        allLineage.addAll(sink.lineage());
                        memberAudits.add(MemberAudit.accepted(m, memberParsed, memberErrors, mStart));
                        log.info("[INGEST] [{}] streamed {} row(s) → {} output file(s){}",
                                m.file().getName(), String.format("%,d", memberParsed),
                                sink.outputs().size(),
                                memberErrors > 0 ? "  rejected=" + memberErrors : "");
                    }
                }

                if (survivors.isEmpty()) batchStatus = "EMPTY";
            }
        } catch (Exception e) {
            batchStatus = "FAILED";
            batchError  = msg(e);
            log.error("Consignment {} failed during streaming (generation) processing", batch.batchId(), e);
        } finally {
            if (tempDb != null) DuckDbUtil.deleteTempDb(tempDb);
        }

        String schemaNames = String.join(",", cfg.schemas().segments().keySet());
        return new IngestOutcome(batchStart, batchStatus, batchError, survivors, memberAudits,
                allOutputs, allLineage, totalInputRows, schemaNames);
    }

    /**
     * Delete the generations this member already revealed into the database dir, before quarantining it.
     *
     * <p>Generation mode reveals real output on every {@code flushRows} rows, so a member that fails
     * partway through has already published complete files under {@code <stem>_gNNNNN_out.*}. Quarantining
     * without deleting them orphans that output: it never reaches {@code sink.outputs()}, so no manifest
     * and no §11.3 registry row names it — and per D3 an unregistered file reads as <em>unknown, not
     * excluded</em>, so a glob-based read counts those rows. Because the basename derives from the file
     * stem and not the batch id, a reprocess with a different generation count would leave the first run's
     * files behind permanently. A quarantined member contributed nothing, and that is what the audit says,
     * so the revealed generations go too.
     *
     * <p>A delete that fails fails the <em>batch</em> ({@link SinkFlushException} propagates to
     * {@link #run}'s outer catch): an orphan we know about is worse than a batch an operator retries.
     */
    private static void discardRevealed(DuckDbRecordSink sink, Consignment.Member m) {
        List<PartitionOutput> revealed = sink.outputs();
        if (revealed.isEmpty()) return;
        for (PartitionOutput o : revealed) {
            try {
                Files.deleteIfExists(Paths.get(o.outputFile()));
            } catch (IOException e) {
                throw new SinkFlushException("cannot discard revealed generation " + o.outputFile()
                        + " for quarantined member " + m.file().getName(), e);
            }
        }
        log.warn("[INGEST] [{}] quarantined mid-file — discarded {} already-revealed generation file(s)",
                m.file().getName(), revealed.size());
    }

    private static StreamingFileIngester instantiate(PipelineConfig cfg) {
        try {
            return (StreamingFileIngester) Class.forName(cfg.schemas().ingesterClass())
                    .getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Cannot instantiate streaming ingester: "
                    + cfg.schemas().ingesterClass(), e);
        }
    }
}
