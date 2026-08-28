package com.gamma.inspector;

import com.gamma.consignment.EventTimeBounds;
import com.gamma.etl.Batch;
import com.gamma.etl.BatchAuditWriter;
import com.gamma.etl.BatchManifest;
import com.gamma.etl.LineageRow;
import com.gamma.etl.ManifestStore;
import com.gamma.etl.ParkedCommit;
import com.gamma.etl.PartitionOutput;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.SchemaSelector;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineLift;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.exec.BranchCommitCoordinator;
import com.gamma.pipeline.exec.BranchCommitLog;
import com.gamma.util.DuckDbUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>Phase 4 S4c — the drain, D-13's resume half.</b> Reached through
 * {@code POST /runs/{name}/drain}, the structural sibling of {@code reprocess}. Completes a
 * Consignment that {@link BatchProcessor} PARKED at a disabled route-branch sink: it seeds the parked
 * branch's rows back from its durable park table, writes them to that branch's destination through the
 * ordinary {@link IngestSinkWriter}, and then runs the ordinary {@code finalizeSource} for the WHOLE
 * batch — register → manifest → backup → markers LAST → ledger / watermark.
 *
 * <p><b>Not a re-ingest, and not a graph re-walk.</b> The park boundary IS the materialisation boundary
 * (§2.7): a route branch's table is complete when its sink is reached, so nothing upstream needs
 * re-running and there is no transform left between the park table and the write. Drain is therefore the
 * batch's own commit tail, resumed — driven by the same {@link BranchCommitCoordinator} over the same
 * {@link BranchCommitLog} the park run deliberately kept, so the branches that committed BEFORE the park
 * are skipped idempotently and the source is finalised exactly once.
 *
 * <p>The outputs / lineage / event-time bounds of those already-committed branches come from the
 * {@link ParkedCommit} sidecar — they existed only in the ingest JVM's memory, and {@code finalizeSource}
 * must see the whole batch's set or the register, {@code manifest.outputs} and the §11.3 registry would
 * silently record half a batch.
 *
 * <p><b>Refusals</b> (loud, never a silent partial — the {@code guardAgainstCompactedOutputs} posture):
 * the batch is not parked · a parked step is still disabled in config (config is the truth, not the
 * manifest) · a parked step no longer names a node in the lifted graph · a park table or the sidecar is
 * missing · the batch carries unpack-expansion members (see {@link #refuseExpansionMembers}).
 *
 * <p><b>The restore window.</b> Park moved each member's original OUT of the inbox into the park home,
 * so drain moves it back to its inbox path immediately before {@code finalizeSource} runs — which is what
 * lets every poll-relative computation in that method (manifest {@code rel}, backup destination, marker
 * path, ledger key, stage rows) stay verbatim correct with no drain-specific mirror of them. The file is
 * therefore back in the inbox for the duration of the commit tail, which then moves it to backup and
 * marks it. A concurrent poll landing inside that window, or a crash inside it, re-ingests the file — the
 * same idempotent {@code OVERWRITE_OR_IGNORE} posture a crash mid-commit has always had.
 */
public final class DrainCommand {

    private static final Logger log = LoggerFactory.getLogger(DrainCommand.class);

    private DrainCommand() {}

    /** What a drain did, for the caller (CLI log line / route body / tests). */
    public record Result(String batchId, List<String> drainedBranches, int outputFiles, long rows) {}

    public static Result run(String toonPath, String batchId) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(toonPath);
        if (cfg.dirs().manifestsDir() == null)
            throw new IllegalStateException("No manifests dir configured (set dirs.status_dir).");

        BatchManifest m = ManifestStore.read(cfg.dirs().manifestsDir(), batchId);
        if (m.parkedAt == null || m.parkedAt.isEmpty())
            throw new IllegalStateException("Refusing to drain " + batchId
                    + ": it is not a PARKED Consignment (its manifest records no parked step).");

        // Config is the truth about "is this step enabled", never the manifest: the operator re-enables
        // by editing processing.disabled_steps, and PipelineConfig.load has just re-armed it.
        List<String> stillDisabled = m.parkedAt.stream().filter(cfg.disabledSteps()::contains).toList();
        if (!stillDisabled.isEmpty())
            throw new IllegalStateException("Refusing to drain " + batchId + ": step(s) " + stillDisabled
                    + " are still listed in processing.disabled_steps. Re-enable them first — draining"
                    + " into a step the config says is off would write rows the author asked not to write.");

        PipelineGraph lifted = PipelineLift.lift(cfg);
        Map<String, PipelineNode> byId = lifted.byId();
        for (String nodeId : m.parkedAt)
            if (!byId.containsKey(nodeId))
                throw new IllegalStateException("Refusing to drain " + batchId + ": parked step '" + nodeId
                        + "' no longer names a step in this pipeline. Its rows are still in the park table"
                        + " — restore the step, or discard the Consignment deliberately.");

        Path parkHome = Paths.get(cfg.dirs().backup(), "parked");
        Map<String, Path> parkTables = new LinkedHashMap<>();
        for (String nodeId : m.parkedAt) {
            String p = m.parkedTables == null ? null : m.parkedTables.get(nodeId);
            Path table = (p == null || p.isBlank()) ? null : Paths.get(p);
            if (table == null || !Files.exists(table))
                throw new IllegalStateException("Refusing to drain " + batchId + ": the park table for step '"
                        + nodeId + "' is missing (" + p + "). Its rows are gone — there is nothing to drain,"
                        + " and writing the rest of the batch would commit it as if the branch had run.");
            parkTables.put(nodeId, table);
        }
        ParkedCommit pending;
        try {
            pending = ParkedCommit.read(parkHome, batchId);
        } catch (java.io.IOException missing) {
            // A refusal, not a lookup failure: the park tables are here but the already-committed
            // branches' outputs are not, so finalising would register half a batch.
            throw new IllegalStateException("Refusing to drain " + batchId + ": its parked commit state ("
                    + ParkedCommit.pathFor(parkHome, batchId) + ") is missing, so the branches that"
                    + " committed before the park cannot be registered. " + missing.getMessage());
        }
        refuseExpansionMembers(batchId, m);

        log.info("[DRAIN] {} — {} parked branch(es) {}, {} member(s), {} already-committed output(s)",
                batchId, parkTables.size(), parkTables.keySet(), m.members.size(), pending.outputs().size());

        // ── rebuild the batch's identity from the manifest (the ingest JVM is long gone) ──
        SchemaSelector.Selection selection = soleSchema(cfg, batchId, m);
        Path poll = Paths.get(cfg.dirs().poll()).toAbsolutePath().normalize();

        List<Batch.Member> survivors = new ArrayList<>();
        Map<Integer, String> srcIdToFile = new LinkedHashMap<>();
        for (BatchManifest.MemberEntry me : m.members) {
            File inbox = poll.resolve(me.originalRelPath()).toFile();
            survivors.add(new Batch.Member(inbox, me.srcId(), inbox.length(), selection));
            srcIdToFile.put(me.srcId(), me.filename());
        }
        Batch batch = new Batch(batchId, m.schemaName, m.outputTable, List.copyOf(survivors));

        List<String> partCols = BatchIngestStrategy.partitionColumns(selection.schema());
        String dbDir    = BatchIngestStrategy.databaseDir(batch, cfg);
        String baseName = BatchIngestStrategy.consolidatedBaseName(survivors, batch);

        LocalDateTime start = LocalDateTime.now();
        IngestSinkWriter writer;
        BranchCommitCoordinator.Result committed;
        File tempDb = BatchIngestStrategy.openTempDb(cfg, "drain_");
        try (Connection conn = DuckDbUtil.openConnection(tempDb)) {
            BatchIngestStrategy.configure(conn, cfg);

            Map<String, String> seeded = new LinkedHashMap<>();
            try (Statement st = conn.createStatement()) {
                for (Map.Entry<String, Path> e : parkTables.entrySet()) {
                    String table = "parked_" + e.getKey().replaceAll("[^A-Za-z0-9_]", "_");
                    st.execute("CREATE TABLE \"" + table + "\" AS SELECT * FROM read_parquet('"
                            + e.getValue().toString().replace('\\', '/').replace("'", "''") + "')");
                    seeded.put(e.getKey(), table);
                }
            }

            writer = new IngestSinkWriter(conn, cfg, partCols, dbDir, baseName, batchId, srcIdToFile);
            BranchCommitLog commitLog = new BranchCommitLog(
                    BatchIngestStrategy.branchCommitLogPath(cfg, batchId).toString());
            // Every branch this batch owes: the ones the park run already committed (durable in the log)
            // plus the parked ones. The coordinator skips the former and gates finalisation on both.
            Set<String> expected = new LinkedHashSet<>(commitLog.committedBranches(batchId));
            expected.addAll(m.parkedAt);

            IngestSinkWriter w = writer;
            // ⚠ The coordinator's SOURCE phase is NOT this lane's finalisation signal: on the ingest
            // path BatchGraphRunner's SourceFinalizer is a deliberate no-op (finalisation belongs to
            // BatchProcessor.commit, once the batch outcome exists), so the park run already recorded
            // a SOURCE row having finalised nothing. Drain therefore uses the coordinator for the part
            // that IS meaningful — the durable, idempotent per-BRANCH skip — and runs the real commit
            // tail itself right after, exactly as BatchProcessor.commit does when the runner returns.
            committed = new BranchCommitCoordinator(commitLog).commit(batchId, expected,
                    branch -> w.write(byId.get(branch), seeded.get(branch)), () -> { });

            Set<String> nowCommitted = commitLog.committedBranches(batchId);
            if (!nowCommitted.containsAll(expected))
                throw new IllegalStateException("Drain of " + batchId + " did not commit every branch — "
                        + "expected " + expected + ", durable " + nowCommitted
                        + ". Nothing was lost; the park tables are untouched.");
            restoreOriginalsToInbox(m, poll);
            finalizeWholeBatch(batch, cfg, survivors, pending, w);
        } finally {
            DuckDbUtil.deleteTempDb(tempDb);
        }

        // The batch is whole: the park artefacts and the resume record have served their purpose.
        for (Path table : parkTables.values()) Files.deleteIfExists(table);
        ParkedCommit.delete(parkHome, batchId);
        Files.deleteIfExists(BatchIngestStrategy.branchCommitLogPath(cfg, batchId));

        writeDrainAudit(batch, cfg, m, writer, start);
        log.info("[DRAIN] {} complete — {} branch(es) drained, {} output file(s)",
                batchId, committed.committedBranches().size(), writer.outputs().size());
        return new Result(batchId, committed.committedBranches(), writer.outputs().size(),
                writer.lineage().stream().mapToLong(LineageRow::rowCount).sum());
    }

    /**
     * The one schema this batch was written with. An armed {@code route:} pipeline is single-schema by
     * construction (multi-schema × route is a standing refusal), so there is exactly one — from the
     * {@code schemas[]} selector or the legacy {@code schema_file}. A plugin ({@code segments}) path
     * never reaches the branch-aware lane, so it is refused rather than guessed at.
     */
    private static SchemaSelector.Selection soleSchema(PipelineConfig cfg, String batchId, BatchManifest m) {
        PipelineConfig.Schemas schemas = cfg.schemas();
        if (schemas.selector() != null && schemas.selector().hasSchemas()) {
            List<SchemaSelector.Selection> entries = schemas.selector().entries();
            if (entries.size() != 1)
                throw new IllegalStateException("Refusing to drain " + batchId + ": this pipeline declares "
                        + entries.size() + " schemas, and an armed route: pipeline is single-schema by"
                        + " construction — this batch was not parked by a shape this command can complete.");
            return entries.get(0);
        }
        if (schemas.single() != null) return new SchemaSelector.Selection(schemas.single(), m.outputTable);
        throw new IllegalStateException("Refusing to drain " + batchId
                + ": this pipeline resolves no CSV schema (plugin or schema-less draft).");
    }

    /**
     * An unpack EXPANSION product's original deliberately stays in the inbox on park (moving a shared
     * archive would strand its sibling batches — {@code BatchProcessor.parkSource}'s ⚠), so it re-expands
     * on the next poll cycle and re-parks as a NEW batch. Completing the old one would commit rows the
     * new batch is about to write again. Refused by name rather than half-handled.
     */
    private static void refuseExpansionMembers(String batchId, BatchManifest m) {
        List<String> expansion = m.members.stream()
                .filter(me -> me.backupPath() == null || me.backupPath().isBlank()
                        || me.originalRelPath().contains("!"))
                .map(BatchManifest.MemberEntry::filename)
                .toList();
        if (!expansion.isEmpty())
            throw new IllegalStateException("Refusing to drain " + batchId + ": member(s) " + expansion
                    + " came out of an unpack expansion, whose original stayed in the inbox and re-expands"
                    + " each cycle. Re-enable the step and let the next poll park (and drain) the fresh"
                    + " batch instead.");
    }

    /** Move each member's original back from the park home to its inbox path — see the class note. */
    private static void restoreOriginalsToInbox(BatchManifest m, Path poll) throws java.io.IOException {
        for (BatchManifest.MemberEntry me : m.members) {
            Path parked = Paths.get(me.backupPath());
            if (!Files.exists(parked)) continue;          // already restored by an interrupted drain
            Path dst = poll.resolve(me.originalRelPath());
            Files.createDirectories(dst.getParent());
            Files.move(parked, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * The batch's real {@code finalizeSource}, over the union of the park-time commit state and what this
     * drain just wrote — one register, one manifest, one §11.3 registration for the whole Consignment.
     */
    private static void finalizeWholeBatch(Batch batch, PipelineConfig cfg, List<Batch.Member> survivors,
                                           ParkedCommit pending, IngestSinkWriter writer)
            throws java.io.IOException {
        List<PartitionOutput> outputs = new ArrayList<>(pending.outputs());
        outputs.addAll(writer.outputs());
        List<LineageRow> lineage = new ArrayList<>(pending.lineage());
        lineage.addAll(writer.lineage());
        Map<String, EventTimeBounds> bounds = new LinkedHashMap<>();
        pending.bounds().forEach((file, b) ->
                bounds.put(file, new EventTimeBounds(b.min(), b.max(), b.spreadMs())));
        bounds.putAll(writer.bounds());
        BatchProcessor.finalizeSource(batch, cfg, survivors, outputs, lineage, bounds);
    }

    /**
     * The drain's own terminal audit row, so a batch that shows PARKED in the ledger gains its SUCCESS
     * row (and the {@code pipeline.batch.committed} Signal that rides on it). Per-MEMBER rows are NOT
     * rewritten: the park run already wrote them, and their parse-time counts are unchanged by a drain.
     * Only the lineage this drain produced is appended — the park run already recorded the rest.
     */
    private static void writeDrainAudit(Batch batch, PipelineConfig cfg, BatchManifest m,
                                        IngestSinkWriter writer, LocalDateTime start) {
        BatchAuditWriter audit = new BatchAuditWriter(
                cfg.dirs().statusFilePath(), cfg.dirs().batchesFilePath(), cfg.dirs().lineageFilePath(),
                cfg.dirs().commitLogPath());
        audit.setTerminalBatchSink(com.gamma.signal.PipelineBatchSignal::emit);
        LocalDateTime end = LocalDateTime.now();
        long rows  = writer.lineage().stream().mapToLong(LineageRow::rowCount).sum();
        long bytes = writer.outputs().stream().mapToLong(PartitionOutput::bytes).sum();
        audit.flush(new BatchAuditWriter.BatchRow(
                batch.batchId(), cfg.identity().pipelineName(), m.schemaName, m.outputTable,
                start.format(DuckDbUtil.DT_FMT), end.format(DuckDbUtil.DT_FMT), "SUCCESS",
                m.members.size(), 0, 0L, rows, writer.outputs().size(), bytes,
                java.time.Duration.between(start, end).toMillis(), null),
                List.of(), writer.lineage());
    }
}
