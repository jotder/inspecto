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
 * Processes one {@link Consignment} in a single pass.
 *
 * <p>This class is now a thin coordinator: it selects a {@link ConsignmentIngestStrategy}
 * (CSV vs. plugin) based on config, runs it to produce an {@link IngestOutcome}, then
 * drives the shared, path-agnostic tail — {@link #commit} and {@link #writeAudit}.
 *
 * <h3>CSV path (default)</h3>
 * {@link CsvIngestStrategy} ingests each member into a per-file temp table, inserts accepted
 * rows into a shared {@code raw_input} tagged with {@code __src_id}, transforms once, writes
 * consolidated partition output, and computes the lineage matrix. Rejected members are
 * quarantined; their rows never reach {@code raw_input}.
 *
 * <h3>Plugin-ingester path</h3>
 * When {@link PipelineConfig.Schemas#ingesterClass()} is set, {@link StreamingPluginIngestStrategy}
 * runs the configured {@link StreamingFileIngester} and picks, per batch by file size, between
 * union mode (many small files → one transform/write) and generation mode (huge single files →
 * bounded scratch). All segment outputs aggregate into one batch audit entry.
 */
public final class ConsignmentIngestor {

    private static final Logger log = LoggerFactory.getLogger(ConsignmentIngestor.class);

    private ConsignmentIngestor() {}

    // ── entry point ───────────────────────────────────────────────────────────

    public static void process(Consignment batch, PipelineConfig cfg, ConsignmentAuditWriter audit) {
        ConsignmentIngestStrategy strategy = (cfg.schemas().ingesterClass() == null)
                ? new CsvIngestStrategy()
                : new StreamingPluginIngestStrategy();

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
            // Phase 4 S4b: the graph lane parked one or more disabled branch sinks — the batch is
            // deliberately UNCOMMITTED. Parked finalisation (manifest + park-home move, nothing
            // else) replaces the commit tail; the drain (S4c) completes the normal sequence later.
            Map<String, java.nio.file.Path> parked = ParkedBranches.drain(batch.batchId());
            if (!parked.isEmpty()) {
                try {
                    parkSource(batch, cfg, outcome.survivors(), parked,
                            outcome.outputs(), outcome.lineage(), outcome.bounds());
                    status = "PARKED";
                } catch (Exception e) {
                    status = "FAILED";
                    error  = "park failed: " + ConsignmentIngestStrategy.msg(e);
                    log.error("Consignment {} failed during park", batch.batchId(), e);
                }
            } else {
                try {
                    commit(batch, cfg, outcome.survivors(), outcome.outputs(), outcome.lineage(),
                            outcome.bounds(), outcome.memberAudits());
                } catch (Exception e) {
                    // Output was written, but a side effect (backup/manifest/markers) failed. Demote
                    // to FAILED so the batch stays visible to audit/lineage/recovery instead of
                    // vanishing — a silently un-audited batch is the worst outcome for reprocessing.
                    status = "FAILED";
                    error  = "commit failed: " + ConsignmentIngestStrategy.msg(e);
                    log.error("Consignment {} failed during commit", batch.batchId(), e);
                }
            }
        }
        try {   // audit is always written — even when commit failed above
            writeAudit(batch, cfg, audit, outcome, status, error);
        } catch (Exception e) {
            log.error("Consignment {} failed during audit", batch.batchId(), e);
        }
        recordProvenance(cfg.identity().pipelineName(), batch, outcome, status);
        // X1: a FAILED Consignment's files stay in the inbox and re-encounter next cycle — that retry is
        // now BOUNDED (attempt record, backoff, exhaustion → quarantine + CRITICAL Signal). A committed
        // or parked one has spent its record. After the audit, so the attempt is on the record first.
        if ("FAILED".equals(status)) CommitRetry.recordFailure(batch, cfg, error);
        else CommitRetry.clear(batch, cfg);
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
    static void recordProvenance(String pipeline, Consignment batch, IngestOutcome outcome, String status) {
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

    private static void commit(Consignment batch, PipelineConfig cfg, List<Consignment.Member> survivors,
                               List<PartitionOutput> outputs, List<LineageRow> lineage,
                               Map<String, EventTimeBounds> bounds, List<MemberAudit> audits)
            throws IOException {
        // lineage is persisted by writeAudit (from the outcome); it is passed on here too because it is the
        // only place a per-output-file row count exists (§11.3). The durable side effects —
        // register → manifest → backup → markers LAST → ledger / watermark — live in finalizeSource, which
        // the branch-aware graph path (ConsignmentGraphRunner's SourceFinalizer) reuses once every sink branch is
        // committed (Stage A), so both drivers share this one crash-ordered sequence.
        finalizeSource(batch, cfg, survivors, outputs, lineage, bounds, audits);

        // S4-pre (elt-s4-park-drain-plan): the per-batch branch commit log has served its purpose once
        // the source is finalised — a fully committed batch never replays. A FAILED batch keeps its
        // log: it IS the durable partial-commit record BranchCommitCoordinator resumes from. Absent
        // for flat-lane batches, so deleteIfExists is the right verb.
        java.nio.file.Files.deleteIfExists(
                ConsignmentIngestStrategy.branchCommitLogPath(cfg, batch.batchId()));
    }

    /**
     * Phase 4 S4b — parked finalisation, D-13's "disable → park durably at the boundary → inspect".
     * Deliberately does ALMOST NOTHING of {@link #finalizeSource}'s sequence: the Consignment is
     * <b>uncommitted</b>, so no DuckLake register, no §11.3 output registration (the committed
     * branches' files are durable but register only when the drain completes the batch — registering
     * half a batch would double-register at drain), no markers, no fingerprint stash, no watermark —
     * and the branch commit log is KEPT (the drain's resume record). What it does:
     * <ol>
     *   <li>writes the manifest with {@code parkedAt} + {@code parkedTables} and every member
     *       {@code PARKED} — the inspectable record of where and why the Consignment stopped;</li>
     *   <li>moves each plain member's ORIGINAL to the park home ({@code dirs.backup()/parked/…},
     *       mirrored by poll-relative path exactly like {@link #backupFile}) so the next poll cycle
     *       does not re-ingest it. ⚠ An unpack EXPANSION product's original stays in the inbox —
     *       {@code batch.max_files: 1} splits an archive across batches, so moving the shared
     *       original would strand its sibling batches; that original re-expands next cycle, which is
     *       the crash posture (idempotent, wasteful, honest).</li>
     *   <li><b>S4c</b> — writes the {@link com.gamma.etl.ParkedCommit} sidecar: the already-committed
     *       branches' outputs / lineage / event-time bounds. They exist only in this JVM's memory
     *       (the branch commit log records branch ids alone), and the drain needs them to run the
     *       real {@code finalizeSource} for the WHOLE batch. Without this the register, the
     *       manifest's outputs and the §11.3 registration would silently lose every branch that
     *       committed before the park.</li>
     * </ol>
     */
    private static void parkSource(Consignment batch, PipelineConfig cfg, List<Consignment.Member> survivors,
                                   Map<String, java.nio.file.Path> parked,
                                   List<PartitionOutput> outputs, List<LineageRow> lineage,
                                   Map<String, EventTimeBounds> bounds) throws IOException {
        Path poll = Paths.get(cfg.dirs().poll()).toAbsolutePath().normalize();
        Path parkHome = Paths.get(cfg.dirs().backup(), "parked");
        Files.createDirectories(parkHome);

        List<ConsignmentManifest.MemberEntry> members = new ArrayList<>();
        for (Consignment.Member m : survivors) {
            File original = com.gamma.etl.unpack.UnpackOrigins.originalOr(m.file());
            Path op = original.toPath().toAbsolutePath().normalize();
            String rel = (op.startsWith(poll) ? poll.relativize(op).toString() : original.getName())
                    .replace('\\', '/');
            boolean expanded = com.gamma.etl.unpack.UnpackOrigins.isExpanded(m.file());
            String parkedPath = "";
            if (!expanded && Files.exists(op)) {
                Path dst = parkHome.resolve(rel);
                Files.createDirectories(dst.getParent());
                Files.move(op, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                parkedPath = dst.toString();
            }
            members.add(new ConsignmentManifest.MemberEntry(m.file().getName(), m.srcId(), rel,
                    parkedPath, com.gamma.etl.MemberStatus.PARKED.name()));
        }

        if (cfg.dirs().manifestsDir() != null) {
            ConsignmentManifest manifest = new ConsignmentManifest();
            manifest.batchId     = batch.batchId();
            manifest.pipeline    = cfg.identity().pipelineName();
            manifest.schemaName  = batch.schemaName();
            manifest.outputTable = batch.table();
            manifest.createdAt   = LocalDateTime.now().format(DuckDbUtil.DT_FMT);
            manifest.schemaFingerprint = schemaFingerprintFor(cfg, batch.schemaName());
            manifest.members     = members;
            manifest.outputs     = List.of();
            manifest.markers     = List.of();
            manifest.parkedAt    = new ArrayList<>(parked.keySet());
            Map<String, String> tables = new LinkedHashMap<>();
            parked.forEach((nodeId, path) -> tables.put(nodeId, path.toString()));
            manifest.parkedTables = tables;
            ManifestStore.write(cfg.dirs().manifestsDir(), manifest);
        }
        Map<String, com.gamma.etl.ParkedCommit.Bounds> wireBounds = new LinkedHashMap<>();
        bounds.forEach((file, b) -> wireBounds.put(file,
                new com.gamma.etl.ParkedCommit.Bounds(b.min(), b.max(), b.spreadMs())));
        com.gamma.etl.ParkedCommit.write(parkHome,
                new com.gamma.etl.ParkedCommit(batch.batchId(), outputs, lineage, wireBounds));

        recordStages(cfg.collector().id(), batch.batchId(), survivors, cfg,
                com.gamma.consignment.FileStage.PARKED);
        log.info("Consignment {} PARKED at {} — park tables under {}, originals in the park home; "
                + "re-enable the step and drain to complete", batch.batchId(), parked.keySet(), parkHome);
    }

    /**
     * The batch's durable source finalisation, in crash-safe order: DuckLake register → manifest → backup →
     * <b>markers LAST</b> → fingerprint ledger / DB-export watermark. Extracted from {@link #commit} so the
     * branch-aware {@link com.gamma.pipeline.exec.ConsignmentGraphRunner} can drive the identical sequence as its
     * {@code SourceFinalizer} — run once, only after every sink branch is durable (T11 commit-split). The
     * legacy single-output path calls it through {@code commit}; both share this one ordering invariant.
     *
     * @param lineage {@code LineageCollector}'s count matrix for {@code outputs}, used only to give the §11.3
     *                output registry a per-file row count. <b>Empty means "these outputs are not mine to
     *                register"</b> — not "zero rows": the Pipeline-sink path counts per partition instead and
     *                registers its own files, so registering here as well would double-count them.
     */
    static void finalizeSource(Consignment batch, PipelineConfig cfg, List<Consignment.Member> survivors,
                               List<PartitionOutput> outputs, List<LineageRow> lineage) throws IOException {
        finalizeSource(batch, cfg, survivors, outputs, lineage, Map.of());
    }

    /** {@link #finalizeSource(Consignment, PipelineConfig, List, List, List)} with §3.1's per-output-file
     *  event-time bounds for the output registry. */
    static void finalizeSource(Consignment batch, PipelineConfig cfg, List<Consignment.Member> survivors,
                               List<PartitionOutput> outputs, List<LineageRow> lineage,
                               Map<String, EventTimeBounds> bounds) throws IOException {
        finalizeSource(batch, cfg, survivors, outputs, lineage, bounds, List.of());
    }

    /**
     * As above, plus the per-member audits so the manifest can record the batch's FAILED members
     * alongside its survivors (unpack-stage plan Phase 4 — "the end status must be against each
     * source file", operator 2026-08-23).
     *
     * <p>Before this, only survivors became {@code MemberEntry} rows: a file that could not be parsed
     * existed in the audit CSV and the quarantine tree but was <b>absent from the manifest</b>, which
     * is the authoritative per-Consignment record. The branch-aware graph path
     * ({@code ConsignmentGraphRunner.SourceFinalizer}) uses the no-audits overload and is unchanged.
     *
     * <p>⚠ The crash-safe ORDER is untouched — register → manifest → backup → markers LAST. A failed
     * member gets a manifest row and no backup/marker (it was moved to quarantine by the strategy,
     * and its file must not be marked processed), so nothing about recovery changes.
     */
    static void finalizeSource(Consignment batch, PipelineConfig cfg, List<Consignment.Member> survivors,
                               List<PartitionOutput> outputs, List<LineageRow> lineage,
                               Map<String, EventTimeBounds> bounds,
                               List<MemberAudit> audits) throws IOException {

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

        List<ConsignmentManifest.MemberEntry> memberEntries = new ArrayList<>();
        List<String> markerPaths = new ArrayList<>();
        for (Consignment.Member m : survivors) {
            // ⚠ Every poll-relative computation below runs against the ORIGINAL inbox file
            // (unpack-stage plan §2.0): an unpack-expanded member lives in dirs.temp, and
            // poll.relativize on it walks out of the root (../..) — a marker outside the markers
            // tree and a nonsense backup path. The manifest keeps BOTH names: `filename` is the
            // actual parsed file, `originalRelPath`/backup/marker are the original's.
            File srcFile  = com.gamma.etl.unpack.UnpackOrigins.originalOr(m.file());
            Path filePath = srcFile.toPath().toAbsolutePath().normalize();
            String rel    = poll.relativize(filePath).toString().replace('\\', '/');
            // An ARCHIVE member is addressed JAR-style, archive!entry, so it stays traceable to both
            // its container and itself with no manifest schema change. A 1→1 stream expansion keeps
            // the plain original path — there is only one file to name.
            if (com.gamma.etl.unpack.UnpackOrigins.totalFor(srcFile) > 1)
                rel = rel + "!" + m.file().getName();
            String backupPath = backup != null
                    ? backup.resolve(poll.relativize(filePath)).toString() : "";
            if (writeMarkers)
                markerPaths.add(MarkerManager.getMarkerPath(srcFile, cfg).toString());
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
                    // Compression-involved files record under their LOGICAL key (§2.3) so a
                    // re-delivery in another compression form finds them; plain files keep the
                    // verbatim key — a plain-only inbox is byte-for-byte unchanged. `name` keeps the
                    // actual spelling either way, which is what the alias-hit log line reports.
                    String ledgerKey = com.gamma.etl.unpack.LogicalNames.involvesCompression(srcFile.getName())
                            ? com.gamma.etl.unpack.LogicalNames.logicalName(rel, cfg) : rel;
                    ledgerEntries.add(new LedgerEntry(sourceId, ledgerKey, srcFile.getName(),
                            Files.size(filePath), checksum,
                            listing != null ? listing.etag() : null, listing != null ? listing.version() : null,
                            Files.getLastModifiedTime(filePath).toMillis(),
                            System.currentTimeMillis(), LedgerEntry.PROCESSED));
                } catch (IOException ignore) { /* vanished pre-backup — skip recording this member */ }
            }
            memberEntries.add(new ConsignmentManifest.MemberEntry(
                    m.file().getName(), m.srcId(), rel, backupPath,
                    com.gamma.etl.MemberStatus.SUCCESS.name()));
        }

        // Phase 4 — the batch's FAILED members join its survivors in the manifest, so the
        // authoritative per-Consignment record answers "what happened to this source file?" for every
        // file, not only the ones that worked. Keyed off the audits (the strategies' own verdicts), and
        // matched to members by srcId so the entry carries the same identity the survivors' rows do.
        // No backup and no marker for these: the strategy already moved the file to quarantine, and
        // marking it processed would hide a file that never landed.
        java.util.Set<Integer> survivorIds = new java.util.HashSet<>();
        for (Consignment.Member m : survivors) survivorIds.add(m.srcId());
        for (MemberAudit a : audits) {
            if (a.status() == com.gamma.etl.MemberStatus.SUCCESS || survivorIds.contains(a.srcId())) continue;
            Consignment.Member failed = batch.members().stream()
                    .filter(m -> m.srcId() == a.srcId()).findFirst().orElse(null);
            String rel = a.filename();
            if (failed != null) {
                File src = com.gamma.etl.unpack.UnpackOrigins.originalOr(failed.file());
                Path fp  = src.toPath().toAbsolutePath().normalize();
                if (fp.startsWith(poll)) {
                    rel = poll.relativize(fp).toString().replace('\\', '/');
                    if (com.gamma.etl.unpack.UnpackOrigins.totalFor(src) > 1)
                        rel = rel + "!" + failed.file().getName();
                }
            }
            memberEntries.add(new ConsignmentManifest.MemberEntry(
                    a.filename(), a.srcId(), rel, "", a.status().name()));
        }

        // Unpack open item (4), honesty half (2026-08-26) — entries an archive's expansion had to
        // SKIP (encrypted / unsupported method: readable metadata, no readable bytes) join the
        // manifest too, so a partial expansion never reads as a clean success. They were never
        // planned, so they carry no srcId (-1), no backup and no marker; the archive-LEVEL status
        // vocabulary stays the unpack plan's §6 Q1. Drained once per original (takeSkipped is
        // atomic), by the first of its members' batches to finalize — and only when a manifest will
        // actually be written, so a manifests-off run keeps the record for the WARN log alone.
        if (cfg.dirs().manifestsDir() != null) {
            java.util.Set<File> originals = new java.util.LinkedHashSet<>();
            for (Consignment.Member m : batch.members())
                originals.add(com.gamma.etl.unpack.UnpackOrigins.originalOr(m.file()));
            for (File original : originals) {
                List<String> skipped = com.gamma.etl.unpack.UnpackOrigins.takeSkipped(original);
                if (skipped.isEmpty()) continue;
                Path fp = original.toPath().toAbsolutePath().normalize();
                String archiveRel = fp.startsWith(poll)
                        ? poll.relativize(fp).toString().replace('\\', '/') : original.getName();
                for (String entry : skipped)
                    memberEntries.add(new ConsignmentManifest.MemberEntry(
                            entry, -1, archiveRel + "!" + entry, "",
                            com.gamma.etl.MemberStatus.SKIPPED_UNREADABLE.name()));
            }
        }

        // §3.4.3 — the schema fingerprint that wrote this Consignment, pinned in the manifest and the output
        // registry (data carries its schema identity). Null for schema-less drafts; readers never require it.
        String schemaFingerprint = schemaFingerprintFor(cfg, batch.schemaName());

        if (cfg.dirs().manifestsDir() != null) {
            ConsignmentManifest manifest = new ConsignmentManifest();
            manifest.batchId     = batch.batchId();
            manifest.pipeline    = cfg.identity().pipelineName();
            manifest.schemaName  = batch.schemaName();
            manifest.outputTable = batch.table();
            manifest.createdAt   = LocalDateTime.now().format(DuckDbUtil.DT_FMT);
            manifest.schemaFingerprint = schemaFingerprint;
            manifest.members     = memberEntries;
            manifest.outputs     = outputs.stream()
                    .map(o -> new ConsignmentManifest.OutputEntry(o.partition(), o.outputFile())).toList();
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
        // ⚠ An EXPANDED member is skipped here and handled in the deferred block at the end of this
        // method: its original may still have members in other batches, and backing it up (or
        // marking it) now would strand them.
        if (backup != null) {
            for (Consignment.Member m : survivors) {
                if (com.gamma.etl.unpack.UnpackOrigins.isExpanded(m.file())) continue;
                try {
                    backupFile(m.file(), cfg);
                } catch (NoSuchFileException vanished) {
                    // A member that disappeared from the inbox between ingest and backup degrades the same way
                    // the fingerprint-ledger loop above it already does. Letting it propagate here demotes the
                    // batch to FAILED *after* the DuckLake register, the manifest and the §11.3 registration are
                    // all durable — an audit that says FAILED over a manifest that says the outputs landed, and
                    // nothing will ever re-drive a file that is no longer there. Only the vanished case is
                    // tolerated: a genuine backup failure (unwritable destination, full disk) leaves the file in
                    // the inbox, where a FAILED batch is the honest answer and the rerun is idempotent.
                    log.warn("Consignment {} member {} vanished before backup; skipping its backup",
                            batch.batchId(), m.file().getName());
                }
            }
            recordStages(stageSourceId, batchIdForStages, survivors, cfg, FileStage.BACKED_UP);
        }

        // Markers LAST — created only after every other side-effect is durable (PATH-mode dedup only;
        // content-based mode uses the ledger below — see writeMarkers above).
        if (writeMarkers) {
            for (Consignment.Member m : survivors) {
                if (com.gamma.etl.unpack.UnpackOrigins.isExpanded(m.file())) continue;   // deferred, below
                MarkerManager.createMarkerFile(m.file(), cfg);
            }
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
        List<Consignment.Member> watermarked = new ArrayList<>();
        for (Consignment.Member m : survivors) {
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

        // Unpack scratch LAST of all — the expanded temp copies (and their origin mappings) are only
        // released once every side effect above is durable; a crash before this line just leaves temp
        // files the stage's 24h sweep reclaims.
        //
        // cleanup() returns the ORIGINAL only for the LAST of its expansions (atomically, so exactly
        // one batch wins even across threads) — that is when the original's own backup and marker
        // run, in the same backup→marker order as above. Until then the original stays in the inbox:
        // if a sibling member's batch fails, no marker exists and the next cycle re-expands it whole,
        // which the OVERWRITE_OR_IGNORE outputs make idempotent.
        for (Consignment.Member m : survivors) {
            File original = com.gamma.etl.unpack.UnpackStage.cleanup(m.file());
            if (original == null) continue;
            if (backup != null) {
                try {
                    backupFile(original, cfg);
                } catch (NoSuchFileException vanished) {
                    log.warn("Consignment {} unpack source {} vanished before backup; skipping its backup",
                            batch.batchId(), original.getName());
                }
            }
            if (writeMarkers) MarkerManager.createMarkerFile(original, cfg);
            log.info("[UNPACK] source complete: {} ({} member(s) ingested)",
                    original.getName(), survivors.size());
        }
    }

    /**
     * Record {@code stage} for every survivor in the calling space's {@link FileStages} registry —
     * a no-op when none is registered (Phase 4 Slice 2, §2.4). Best-effort, like every other Stage-C
     * side effect: a file's real path is relativized against {@code dirs.poll}, the same key
     * {@code AcquisitionLedger} uses, so a stage row and a ledger row for the same file always agree.
     */
    private static void recordStages(String sourceId, String batchId, List<Consignment.Member> survivors,
                                      PipelineConfig cfg, FileStage stage) {
        if (FileStages.shared() == null) return;
        Path poll = Paths.get(cfg.dirs().poll()).toAbsolutePath().normalize();
        String now = LocalDateTime.now().format(DuckDbUtil.DT_FMT);
        List<FileStageRecord> records = new ArrayList<>();
        for (Consignment.Member m : survivors) {
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

    /**
     * The {@code logical_name} audit column (unpack plan step 4d): the extension-insensitive identity
     * of the INBOX file this row's data arrived in.
     *
     * <p>⚠ Resolved from {@link MemberAudit#originPath()}, captured at INGEST time — never by asking
     * {@code UnpackOrigins} here. {@code writeAudit} runs AFTER {@code commit}, whose
     * {@code UnpackStage.cleanup} consumes the origin mapping, so a late lookup reads blank for every
     * expanded file: the same trap {@code MemberAudit.origin} already documents.
     *
     * <p>⚠ For an expansion product this is the ARCHIVE's identity, deliberately shared by all of its
     * entries: one delivery, one identity, and it is what the duplicate check ledgers on
     * ({@code ledgerKey} in {@code finalizeSource}). It is NOT the entry's own name — that is what
     * lineage records, through {@code UnpackOrigins.lineageName}.
     *
     * <p>Blank rather than a guess when the file lies outside the poll root (no relative path to take
     * an identity from) — a blank groups with nothing, an invented key groups with the wrong thing.
     */
    private static String logicalNameOf(MemberAudit ma, Map<Integer, File> memberFiles, PipelineConfig cfg) {
        File inbox = ma.originPath() != null ? ma.originPath() : memberFiles.get(ma.srcId());
        if (inbox == null) return "";
        Path poll = Paths.get(cfg.dirs().poll()).toAbsolutePath().normalize();
        Path file = inbox.toPath().toAbsolutePath().normalize();
        if (!file.startsWith(poll)) return "";
        String rel = poll.relativize(file).toString().replace('\\', '/');
        return com.gamma.etl.unpack.LogicalNames.logicalName(rel, cfg);
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
    private static void writeAudit(Consignment batch, PipelineConfig cfg, ConsignmentAuditWriter audit,
                                   IngestOutcome outcome, String status, String error) {
        if (audit == null) return;
        LocalDateTime end = LocalDateTime.now();
        List<LineageRow>      lineage = outcome.lineage();
        List<PartitionOutput> outputs = outcome.outputs();

        Map<Integer, LinkedHashSet<String>> outBySrc = new HashMap<>();
        for (LineageRow r : lineage)
            outBySrc.computeIfAbsent(r.srcId(), k -> new LinkedHashSet<>()).add(r.outputFile());

        // Per-output byte counts for the FILE ledger (LEDGER-OUTPUT-BYTES-1). The join is exact by
        // construction: LineageCollector derives each row's outputFile from THIS same list
        // (partToFile.put(o.partition(), o.outputFile())), so the two strings are the same object's value.
        // ⚠ A miss maps to -1 = "not measured", never 0 — 0 asserts the output was EMPTY, and this column
        // is an audit record (see the failure-audit convention).
        Map<String, Long> bytesByOutput = new HashMap<>();
        for (PartitionOutput o : outputs) bytesByOutput.put(o.outputFile(), o.bytes());

        // srcId -> the member's own file, for the rows that are NOT expansion products. Pure path
        // arithmetic below, so it does not matter that commit has already moved these to backup.
        Map<Integer, File> memberFiles = new HashMap<>();
        for (Consignment.Member m : batch.members()) memberFiles.put(m.srcId(), m.file());

        List<ConsignmentAuditWriter.FileRow> fileRows = new ArrayList<>();
        int rejected = 0;
        for (MemberAudit ma : outcome.memberAudits()) {
            if (ma.status() != com.gamma.etl.MemberStatus.SUCCESS) rejected++;
            // Roll this entry's outcome up to the ARCHIVE it came out of, for the run-level unpack
            // ledger (§2.2). Keyed on the origin PATH captured at ingest time, never the basename —
            // two same-named archives in different inbox subdirectories are two archives.
            if (ma.originPath() != null)
                com.gamma.etl.unpack.UnpackLedger.entryOutcome(
                        cfg.identity().runTimestamp(), ma.originPath(), batch.batchId(),
                        ma.status() == com.gamma.etl.MemberStatus.SUCCESS);
            List<String> paths = new ArrayList<>(
                    outBySrc.getOrDefault(ma.srcId(), new LinkedHashSet<>()));
            fileRows.add(new ConsignmentAuditWriter.FileRow(
                    ma.start().format(DuckDbUtil.DT_FMT), end.format(DuckDbUtil.DT_FMT),
                    ma.filename(), ma.status().name(), ma.parsedRows(), ma.errorRows(),
                    paths, paths.stream().map(pth -> bytesByOutput.getOrDefault(pth, -1L)).toList(),
                    Duration.between(ma.start(), end).toMillis(), ma.error(), batch.batchId(),
                    ma.origin(), logicalNameOf(ma, memberFiles, cfg)));
        }

        long totalOutputRows  = lineage.stream().mapToLong(LineageRow::rowCount).sum();
        long totalOutputBytes = outputs.stream().mapToLong(PartitionOutput::bytes).sum();

        ConsignmentAuditWriter.ConsignmentRow batchRow = new ConsignmentAuditWriter.ConsignmentRow(
                batch.batchId(), cfg.identity().pipelineName(), outcome.schemaLabel(), batch.table(),
                outcome.batchStart().format(DuckDbUtil.DT_FMT), end.format(DuckDbUtil.DT_FMT), status,
                batch.members().size(), rejected, outcome.totalInputRows(), totalOutputRows,
                outputs.size(), totalOutputBytes,
                Duration.between(outcome.batchStart(), end).toMillis(), error,
                outcome.castFailures());

        audit.flush(batchRow, fileRows, lineage);
    }
}
