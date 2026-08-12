package com.gamma.inspector;

import com.gamma.acquire.AcquisitionLedger;
import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.acquire.LedgerEntry;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.ConsignmentOutputs;
import com.gamma.consignment.EventTimeBounds;
import com.gamma.consignment.FileStage;
import com.gamma.consignment.FileStageRecord;
import com.gamma.consignment.FileStages;
import com.gamma.etl.*;
import com.gamma.util.DuckDbUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Processes one {@link Batch} in a single pass.
 *
 * <p>This class is now a thin coordinator: it selects a {@link BatchIngestStrategy}
 * (CSV vs. plugin) based on config, runs it to produce an {@link IngestOutcome}, then
 * drives the shared, path-agnostic tail — {@link #commit} and {@link #writeAudit}.
 *
 * <h3>CSV path (default)</h3>
 * {@link CsvBatchStrategy} ingests each member into a per-file temp table, inserts accepted
 * rows into a shared {@code raw_input} tagged with {@code __src_id}, transforms once, writes
 * consolidated partition output, and computes the lineage matrix. Rejected members are
 * quarantined; their rows never reach {@code raw_input}.
 *
 * <h3>Plugin-ingester path</h3>
 * When {@link PipelineConfig.Schemas#ingesterClass()} is set, {@link StreamingPluginBatchStrategy}
 * runs the configured {@link StreamingFileIngester} and picks, per batch by file size, between
 * union mode (many small files → one transform/write) and generation mode (huge single files →
 * bounded scratch). All segment outputs aggregate into one batch audit entry.
 */
public final class BatchProcessor {

    private static final Logger log = LoggerFactory.getLogger(BatchProcessor.class);

    private BatchProcessor() {}

    // ── entry point ───────────────────────────────────────────────────────────

    public static void process(Batch batch, PipelineConfig cfg, BatchAuditWriter audit) {
        BatchIngestStrategy strategy = (cfg.schemas().ingesterClass() == null)
                ? new CsvBatchStrategy()
                : new StreamingPluginBatchStrategy();

        IngestOutcome outcome;
        try {
            outcome = strategy.ingest(batch, cfg);
        } finally {
            // The strategies report per-member/per-step progress; a snapshot must never outlive the batch.
            IngestProgress.clear(cfg.identity().pipelineName());
            StepProgress.clear(cfg.identity().pipelineName());
        }

        String status = outcome.status();
        String error  = outcome.error();

        if ("SUCCESS".equals(status)) {
            try {
                commit(batch, cfg, outcome.survivors(), outcome.outputs(), outcome.lineage(),
                        outcome.bounds());
            } catch (Exception e) {
                // Output was written, but a side effect (backup/manifest/markers) failed. Demote
                // to FAILED so the batch stays visible to audit/lineage/recovery instead of
                // vanishing — a silently un-audited batch is the worst outcome for reprocessing.
                status = "FAILED";
                error  = "commit failed: " + BatchIngestStrategy.msg(e);
                log.error("Batch {} failed during commit", batch.batchId(), e);
            }
        }
        try {   // audit is always written — even when commit failed above
            writeAudit(batch, cfg, audit, outcome, status, error);
        } catch (Exception e) {
            log.error("Batch {} failed during audit", batch.batchId(), e);
        }
        recordProvenance(cfg.identity().pipelineName(), batch, outcome, status);
    }

    // ── data-plane provenance (T21 — consignment-chain-plan.md S3) ─────────────

    /**
     * Project the batch's step counts into the space's provenance store — the same
     * {@code inspecto_pipeline_provenance} matrix the job lane's {@code PipelineExecutor} records, so
     * the editor's per-edge weights work for ingest pipelines too. Node ids match the editable lift
     * ({@code parse}/{@code sink}); a row's {@code (nodeId, rel)} paints the node's outgoing
     * {@code data} edge. Default-off (no store registered ⇒ a map lookup) and best-effort like every
     * registry on this path. SUCCESS only — a failed batch wrote nothing durable to count.
     */
    static void recordProvenance(String pipeline, Batch batch, IngestOutcome outcome, String status) {
        if (!"SUCCESS".equals(status) || com.gamma.pipeline.exec.ProvenanceStores.shared() == null) return;
        String ts = java.time.Instant.now().toString();
        long written = outcome.lineage().stream().mapToLong(LineageRow::rowCount).sum();
        com.gamma.pipeline.exec.ProvenanceStores.record(List.of(
                new com.gamma.pipeline.exec.ProvenanceRow(
                        pipeline, batch.batchId(), "parse", "data", outcome.totalInputRows(), ts),
                new com.gamma.pipeline.exec.ProvenanceRow(
                        pipeline, batch.batchId(), "sink", "data", written, ts)));
    }

    // ── commit: register, manifest, markers, backup ────────────────────────────

    private static void commit(Batch batch, PipelineConfig cfg, List<Batch.Member> survivors,
                               List<PartitionOutput> outputs, List<LineageRow> lineage,
                               Map<String, EventTimeBounds> bounds)
            throws IOException {
        // lineage is persisted by writeAudit (from the outcome); it is passed on here too because it is the
        // only place a per-output-file row count exists (§11.3). The durable side effects —
        // register → manifest → backup → markers LAST → ledger / watermark — live in finalizeSource, which
        // the branch-aware graph path (BatchGraphRunner's SourceFinalizer) reuses once every sink branch is
        // committed (Stage A), so both drivers share this one crash-ordered sequence.
        finalizeSource(batch, cfg, survivors, outputs, lineage, bounds);
    }

    /**
     * The batch's durable source finalisation, in crash-safe order: DuckLake register → manifest → backup →
     * <b>markers LAST</b> → fingerprint ledger / DB-export watermark. Extracted from {@link #commit} so the
     * branch-aware {@link com.gamma.pipeline.exec.BatchGraphRunner} can drive the identical sequence as its
     * {@code SourceFinalizer} — run once, only after every sink branch is durable (T11 commit-split). The
     * legacy single-output path calls it through {@code commit}; both share this one ordering invariant.
     *
     * @param lineage {@code LineageCollector}'s count matrix for {@code outputs}, used only to give the §11.3
     *                output registry a per-file row count. <b>Empty means "these outputs are not mine to
     *                register"</b> — not "zero rows": the Pipeline-sink path counts per partition instead and
     *                registers its own files, so registering here as well would double-count them.
     */
    static void finalizeSource(Batch batch, PipelineConfig cfg, List<Batch.Member> survivors,
                               List<PartitionOutput> outputs, List<LineageRow> lineage) throws IOException {
        finalizeSource(batch, cfg, survivors, outputs, lineage, Map.of());
    }

    /** {@link #finalizeSource(Batch, PipelineConfig, List, List, List)} with §3.1's per-output-file
     *  event-time bounds for the output registry. */
    static void finalizeSource(Batch batch, PipelineConfig cfg, List<Batch.Member> survivors,
                               List<PartitionOutput> outputs, List<LineageRow> lineage,
                               Map<String, EventTimeBounds> bounds) throws IOException {

        // ── ordering rationale ────────────────────────────────────────────────
        // Markers signal "already processed; skip on next poll." If a crash leaves
        // markers without a corresponding backup, the input file is stranded in the
        // inbox forever (skipped by poll, never moved). So markers go LAST, after
        // every other side effect is durable. Sequence:
        //   1. DuckLake register (optional, non-fatal — log & continue)
        //   2. Manifest write   (required: reprocess reads from here)
        //   3. Backup originals (moves files out of the inbox)
        //   4. Marker files     (last — only created when 1-3 all succeeded)
        // A crash at any point before step 4 is idempotent on rerun: outputs use
        // OVERWRITE_OR_IGNORE, the manifest is rewritten, and absent markers mean
        // the (still-present-in-inbox) files are picked up again.

        DuckLakeRegistrar.register(outputs.stream().map(PartitionOutput::outputFile).toList(),
                batch.table(), cfg);

        String stageSourceId = cfg.collector().id();
        String batchIdForStages = batch.batchId();
        recordStages(stageSourceId, batchIdForStages, survivors, cfg, FileStage.REGISTERED);

        Path poll   = Paths.get(cfg.dirs().poll()).toAbsolutePath().normalize();
        Path backup = (cfg.dirs().backup() != null && !cfg.dirs().backup().isBlank())
                ? Paths.get(cfg.dirs().backup()).toAbsolutePath() : null;

        // Content-based dedup (Phase C): capture each survivor's fingerprint now, while the file is still at
        // its inbox path (the backup step below moves it), and record it to the ledger LAST — alongside the
        // markers, after every other side-effect is durable — so a crash mid-commit doesn't leave a stranded
        // "already processed" fingerprint. PATH mode records nothing (it uses marker sentinels).
        boolean ledgerRecord = cfg.processing().duplicateCheckEnabled() && cfg.collector().duplicate().contentBased();
        boolean checksumMode = ledgerRecord && "checksum".equals(cfg.collector().duplicate().mode());
        // Path-marker sentinels are the dedup mechanism only in PATH mode. In content-based mode
        // (checksum/metadata) the fingerprint ledger is the source of truth and the marker is dead
        // weight that actively breaks reprocessing: a CHANGED file re-ingested at a known path would
        // hit the prior commit's marker (FileAlreadyExistsException). So skip markers when ledgerRecord.
        boolean writeMarkers = cfg.dirs().markers() != null && !ledgerRecord;
        String dupAlgorithm = cfg.collector().duplicate().algorithm();
        String sourceId = cfg.collector().id();
        List<LedgerEntry> ledgerEntries = ledgerRecord ? new ArrayList<>() : null;

        List<BatchManifest.MemberEntry> memberEntries = new ArrayList<>();
        List<String> markerPaths = new ArrayList<>();
        for (Batch.Member m : survivors) {
            Path filePath = m.file().toPath().toAbsolutePath().normalize();
            String rel    = poll.relativize(filePath).toString().replace('\\', '/');
            String backupPath = backup != null
                    ? backup.resolve(poll.relativize(filePath)).toString() : "";
            if (writeMarkers)
                markerPaths.add(MarkerManager.getMarkerPath(m.file(), cfg).toString());
            if (ledgerRecord) {
                try {
                    // CHECKSUM mode: reuse the hash computed during the run-path dedup (stashed by
                    // CollectorProcessor), or compute it now from the still-in-inbox file if absent.
                    String checksum = null;
                    if (checksumMode) {
                        checksum = AcquisitionLedgers.takeChecksum(filePath);
                        if (checksum == null) checksum = com.gamma.acquire.Checksums.of(filePath, dupAlgorithm);
                    }
                    // Listing identity (ACQ-7): the connector's etag/version, stashed at dedup time — recorded
                    // whenever the listing carried them, whatever the mode, so a later switch to etag dedup
                    // starts from a populated ledger.
                    AcquisitionLedgers.Listing listing = AcquisitionLedgers.takeListing(filePath);
                    ledgerEntries.add(new LedgerEntry(sourceId, rel, m.file().getName(),
                            Files.size(filePath), checksum,
                            listing != null ? listing.etag() : null, listing != null ? listing.version() : null,
                            Files.getLastModifiedTime(filePath).toMillis(),
                            System.currentTimeMillis(), LedgerEntry.PROCESSED));
                } catch (IOException ignore) { /* vanished pre-backup — skip recording this member */ }
            }
            memberEntries.add(new BatchManifest.MemberEntry(
                    m.file().getName(), m.srcId(), rel, backupPath, "SUCCESS"));
        }

        // §3.4.3 — the schema fingerprint that wrote this Consignment, pinned in the manifest and the output
        // registry (data carries its schema identity). Null for schema-less drafts; readers never require it.
        String schemaFingerprint = schemaFingerprintFor(cfg, batch.schemaName());

        if (cfg.dirs().manifestsDir() != null) {
            BatchManifest manifest = new BatchManifest();
            manifest.batchId     = batch.batchId();
            manifest.pipeline    = cfg.identity().pipelineName();
            manifest.schemaName  = batch.schemaName();
            manifest.outputTable = batch.table();
            manifest.createdAt   = LocalDateTime.now().format(DuckDbUtil.DT_FMT);
            manifest.schemaFingerprint = schemaFingerprint;
            manifest.members     = memberEntries;
            manifest.outputs     = outputs.stream()
                    .map(o -> new BatchManifest.OutputEntry(o.partition(), o.outputFile())).toList();
            manifest.markers     = markerPaths;
            ManifestStore.write(cfg.dirs().manifestsDir(), manifest);
            recordStages(stageSourceId, batchIdForStages, survivors, cfg, FileStage.MANIFESTED);
        }

        // §11.3 — index the output files, AFTER the manifest and never before it. The manifest is authoritative
        // for a file's existence, this registry only for its state, so a crash between the two must lose the
        // index and never the record that the files exist. Best-effort and default-off: with no registry
        // registered for this space, record() is a no-op and nothing about this sequence changes.
        if (lineage != null && !lineage.isEmpty()) {
            ConsignmentOutputStores.record(ConsignmentOutputs.fromLineage(
                    batch.batchId(), null, batch.table(), outputs, lineage, schemaFingerprint,
                    bounds, cfg.identity().pipelineName()));
            recordStages(stageSourceId, batchIdForStages, survivors, cfg, FileStage.OUTPUT_REGISTERED);
        }

        // Backup BEFORE markers — see ordering rationale at top of method.
        if (backup != null) {
            for (Batch.Member m : survivors) backupFile(m.file(), cfg);
            recordStages(stageSourceId, batchIdForStages, survivors, cfg, FileStage.BACKED_UP);
        }

        // Markers LAST — created only after every other side-effect is durable (PATH-mode dedup only;
        // content-based mode uses the ledger below — see writeMarkers above).
        if (writeMarkers) {
            for (Batch.Member m : survivors) MarkerManager.createMarkerFile(m.file(), cfg);
            recordStages(stageSourceId, batchIdForStages, survivors, cfg, FileStage.MARKED);
        }

        // Fingerprint ledger LAST too (content-based dedup; same stranding-safety reason as markers).
        if (ledgerRecord) {
            AcquisitionLedger ledger = AcquisitionLedgers.shared();
            for (LedgerEntry e : ledgerEntries) ledger.record(e);
            recordStages(stageSourceId, batchIdForStages, survivors, cfg, FileStage.MARKED);
        }

        // Row-level DB-export watermark LAST too, and independently of dedup mode: a DB-export connector stashes the
        // new max watermark during fetchTo; advance it only now that the batch is durable (resumable). Source-type-
        // agnostic — takeDbWatermark is empty for any file no connector stashed.
        AcquisitionLedger wmLedger = null;
        List<Batch.Member> watermarked = new ArrayList<>();
        for (Batch.Member m : survivors) {
            Path filePath = m.file().toPath().toAbsolutePath().normalize();
            var wm = AcquisitionLedgers.takeDbWatermark(filePath);
            if (wm.isPresent()) {
                if (wmLedger == null) wmLedger = AcquisitionLedgers.shared();
                wmLedger.recordDbWatermark(wm.get().key(), wm.get().value());
                watermarked.add(m);
            }
        }
        if (!watermarked.isEmpty())
            recordStages(stageSourceId, batchIdForStages, watermarked, cfg, FileStage.WATERMARK_ADVANCED);
    }

    /**
     * Record {@code stage} for every survivor in the calling space's {@link FileStages} registry —
     * a no-op when none is registered (Phase 4 Slice 2, §2.4). Best-effort, like every other Stage-C
     * side effect: a file's real path is relativized against {@code dirs.poll}, the same key
     * {@code AcquisitionLedger} uses, so a stage row and a ledger row for the same file always agree.
     */
    private static void recordStages(String sourceId, String batchId, List<Batch.Member> survivors,
                                      PipelineConfig cfg, FileStage stage) {
        if (FileStages.shared() == null) return;
        Path poll = Paths.get(cfg.dirs().poll()).toAbsolutePath().normalize();
        String now = LocalDateTime.now().format(DuckDbUtil.DT_FMT);
        List<FileStageRecord> records = new ArrayList<>();
        for (Batch.Member m : survivors) {
            String rel = poll.relativize(m.file().toPath().toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
            records.add(new FileStageRecord(sourceId, rel, batchId, stage, now));
        }
        FileStages.record(records);
    }

    /**
     * The §3.4.3 schema fingerprint: SHA-256 ({@link com.gamma.util.CanonicalHash}) of the resolved schema
     * map — mapping rules included, since the parser merged them at load — that wrote this Consignment.
     * {@code null} when no schema resolves (schema-less draft, or a multi-schema name that no longer matches).
     */
    static String schemaFingerprintFor(PipelineConfig cfg, String schemaName) {
        Map<String, Object> schema = resolvedSchema(cfg, schemaName);
        return schema == null ? null : com.gamma.util.CanonicalHash.sha256(schema);
    }

    /** The schema map the batch was ingested with, located by {@code batch.schemaName()} ({@code raw.name}). */
    private static Map<String, Object> resolvedSchema(PipelineConfig cfg, String schemaName) {
        PipelineConfig.Schemas s = cfg.schemas();
        if (s == null) return null;
        if (s.single() != null) return s.single();
        if (s.selector() != null)
            for (SchemaSelector.Descriptor d : s.selector().descriptors())
                if (schemaName != null && schemaName.equals(rawName(d.schema()))) return d.schema();
        if (s.segments() != null && schemaName != null) {
            Map<String, Object> seg = s.segments().get(schemaName);
            if (seg != null) return seg;
            for (Map<String, Object> m : s.segments().values())
                if (schemaName.equals(rawName(m))) return m;
        }
        return null;
    }

    private static String rawName(Map<String, Object> schema) {
        Object raw = schema == null ? null : schema.get("raw");
        if (raw instanceof Map<?, ?> m && m.get("name") != null) return m.get("name").toString();
        return null;
    }

    private static void backupFile(File inputFile, PipelineConfig cfg) throws IOException {
        Path poll = Paths.get(cfg.dirs().poll()).toAbsolutePath().normalize();
        Path file = inputFile.toPath().toAbsolutePath().normalize();
        Path dst  = Paths.get(cfg.dirs().backup()).resolve(poll.relativize(file));
        Files.createDirectories(dst.getParent());
        Files.move(file, dst, StandardCopyOption.REPLACE_EXISTING);
    }

    // ── audit assembly ──────────────────────────────────────────────────────────

    /**
     * Assemble and flush the batch + file + lineage audit rows. Path-agnostic: the
     * CSV/plugin difference is carried by {@link IngestOutcome#schemaLabel()}. The
     * {@code status}/{@code error} args are the <em>final</em> values (a post-write
     * commit failure demotes a SUCCESS outcome to FAILED).
     */
    private static void writeAudit(Batch batch, PipelineConfig cfg, BatchAuditWriter audit,
                                   IngestOutcome outcome, String status, String error) {
        if (audit == null) return;
        LocalDateTime end = LocalDateTime.now();
        List<LineageRow>      lineage = outcome.lineage();
        List<PartitionOutput> outputs = outcome.outputs();

        Map<Integer, LinkedHashSet<String>> outBySrc = new HashMap<>();
        for (LineageRow r : lineage)
            outBySrc.computeIfAbsent(r.srcId(), k -> new LinkedHashSet<>()).add(r.outputFile());

        List<BatchAuditWriter.FileRow> fileRows = new ArrayList<>();
        int rejected = 0;
        for (MemberAudit ma : outcome.memberAudits()) {
            if (!ma.status().equals("SUCCESS")) rejected++;
            List<String> paths = new ArrayList<>(
                    outBySrc.getOrDefault(ma.srcId(), new LinkedHashSet<>()));
            fileRows.add(new BatchAuditWriter.FileRow(
                    ma.start().format(DuckDbUtil.DT_FMT), end.format(DuckDbUtil.DT_FMT),
                    ma.filename(), ma.status(), ma.parsedRows(), ma.errorRows(),
                    paths, Collections.nCopies(paths.size(), 0L),
                    Duration.between(ma.start(), end).toMillis(), ma.error(), batch.batchId()));
        }

        long totalOutputRows  = lineage.stream().mapToLong(LineageRow::rowCount).sum();
        long totalOutputBytes = outputs.stream().mapToLong(PartitionOutput::bytes).sum();

        BatchAuditWriter.BatchRow batchRow = new BatchAuditWriter.BatchRow(
                batch.batchId(), cfg.identity().pipelineName(), outcome.schemaLabel(), batch.table(),
                outcome.batchStart().format(DuckDbUtil.DT_FMT), end.format(DuckDbUtil.DT_FMT), status,
                batch.members().size(), rejected, outcome.totalInputRows(), totalOutputRows,
                outputs.size(), totalOutputBytes,
                Duration.between(outcome.batchStart(), end).toMillis(), error,
                outcome.castFailures());

        audit.flush(batchRow, fileRows, lineage);
    }
}
