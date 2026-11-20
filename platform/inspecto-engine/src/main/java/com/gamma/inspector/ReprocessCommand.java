package com.gamma.inspector;

import com.gamma.consignment.ConsignmentOutput;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.etl.ConsignmentManifest;
import com.gamma.etl.ManifestStore;
import com.gamma.etl.PipelineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.util.List;

/**
 * Implements {@code POST /runs/{pipeline}/reprocess} ({@code RunRoutes}): deletes the
 * batch's output files and markers, restores its member files from backup into
 * the inbox, supersedes the manifest, and triggers a fresh poll.
 *
 * <p>Reprocessing is whole-batch only; the original audit rows remain as history.
 */
public final class ReprocessCommand {

    private static final Logger log = LoggerFactory.getLogger(ReprocessCommand.class);

    private ReprocessCommand() {}

    public static void run(String toonPath, String batchId) throws Exception {
        com.gamma.config.safety.StateGate.requireRewind("reprocess of consignment " + batchId);
        PipelineConfig cfg = PipelineConfig.load(toonPath);
        if (cfg.dirs().manifestsDir() == null)
            throw new IllegalStateException("No manifests dir configured (set dirs.status_dir).");

        ConsignmentManifest m = ManifestStore.read(cfg.dirs().manifestsDir(), batchId);
        log.info("[REPROCESS] {} — {} member(s), {} output(s)",
                batchId, m.members.size(), m.outputs.size());

        guardAgainstCompactedOutputs(batchId, m);

        // 1. delete outputs
        for (ConsignmentManifest.OutputEntry o : m.outputs) {
            if (!Files.deleteIfExists(Paths.get(o.outputFile())))
                log.warn("[REPROCESS] {} — output already absent, nothing to delete: {}. If it was merged by "
                                + "the compact job rather than removed by hand, re-ingest will DUPLICATE its "
                                + "rows. The consignment-outputs registry detects that instead of guessing and "
                                + "is on by default; this batch got past the guard, so it is either off "
                                + "(-Dconsignment.outputs.backend=none) or predates the registry.",
                        batchId, o.outputFile());
        }
        // 2. delete markers
        for (String marker : m.markers) {
            Files.deleteIfExists(Paths.get(marker));
        }
        // 3. restore members from backup into the inbox (original relative path)
        Path poll = Paths.get(cfg.dirs().poll()).toAbsolutePath();
        for (ConsignmentManifest.MemberEntry me : m.members) {
            if (me.backupPath() == null || me.backupPath().isBlank()) continue;
            Path src = Paths.get(me.backupPath());
            if (!Files.exists(src)) {
                log.warn("[REPROCESS] backup missing, cannot restore {} ({})",
                        me.filename(), src);
                continue;
            }
            Path dst = poll.resolve(me.originalRelPath());
            Files.createDirectories(dst.getParent());
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
        // 4. supersede the manifest, and the registry rows beside it (a no-op when the registry is off)
        ManifestStore.supersede(cfg.dirs().manifestsDir(), batchId);
        DbConsignmentOutputStore registry = ConsignmentOutputStores.shared();
        if (registry != null) registry.supersede(batchId);
        // 4b. D-9: retract this Consignment's windowed-dedup claims BESIDE the supersede — a reprocess
        // is a whole-Consignment supersede-and-re-ingest with no row-level retraction anywhere, so a
        // ledger still holding these keys would answer "already seen" to every re-ingested row and
        // drop the lot, silently and permanently (DbDedupLedger class doc).
        com.gamma.consignment.DbDedupLedger dedupLedger = com.gamma.consignment.DedupLedgers.shared();
        if (dedupLedger != null) {
            int retracted = dedupLedger.retract(batchId);
            if (retracted > 0)
                log.info("[REPROCESS] {} — retracted {} windowed-dedup claim(s) so re-ingest can re-admit them",
                        batchId, retracted);
        }

        // 5. re-run a normal poll on the restored set (fresh batch id)
        CollectorProcessor.run(cfg);
        log.info("[REPROCESS] {} complete.", batchId);
    }

    /**
     * Refuse to reprocess a Consignment whose output files a compaction has merged away (§11.3(a), §6.3).
     *
     * <p>This is the one case where the old behaviour was <b>silently wrong</b> rather than merely awkward:
     * step 1's {@code deleteIfExists} no-ops on a path compaction already unlinked, the members are restored,
     * and the fresh poll re-ingests rows that are still present inside the merged file — <b>duplicating them</b>,
     * with nothing in the log to say so. {@code PartitionCompactor}'s javadoc has documented that trade-off and
     * offered only "set {@code min_age_days} beyond the reprocess horizon" as mitigation.
     *
     * <p>The §11.3 registry makes it decidable, so a refusal replaces the duplication. Removing the rows safely
     * means rewriting the whole partition (§6.2), which this command does not do — so it stops rather than
     * pretending. <b>This guard is why the registry became default-on</b> (addressing D1, 2026-08-10): the fix
     * had shipped switched off in every deployment. Where it is explicitly disabled, or for Consignments that
     * predate the registry, nothing is decidable and nothing is blocked — the per-file warning in step 1 is all
     * that can honestly be said.
     *
     * <p>{@code BASIC-ELT-PROFILE-1} B4: refused the same way when a later arrival replaced rows of an output
     * ({@code REPLACED}): <i>Replace the period</i> rewrote the file without some days, or <i>Replace everything</i> set it
     * aside, and a re-ingest of the backup would bring those rows back. Thrown as {@link Refused}.
     */
    private static void guardAgainstCompactedOutputs(String batchId, ConsignmentManifest m) {
        DbConsignmentOutputStore registry = ConsignmentOutputStores.shared();
        if (registry == null) return;

        List<ConsignmentOutput> outputs = registry.outputs(batchId);
        List<String> compacted = outputs.stream()
                .filter(o -> o.state() == ConsignmentOutput.State.COMPACTED_AWAY)
                .map(ConsignmentOutput::path)
                .toList();
        // BASIC-ELT-PROFILE-1 B4: a later arrival replaced rows of these files; a re-ingest would bring them back
        List<String> replaced = outputs.stream()
                .filter(o -> o.state() == ConsignmentOutput.State.REPLACED)
                .map(ConsignmentOutput::path)
                .toList();
        if (compacted.isEmpty() && replaced.isEmpty()) return;

        String of = " of its " + m.outputs.size() + " output file(s) ";
        List<String> why = new java.util.ArrayList<>();
        if (!compacted.isEmpty())
            why.add(compacted.size() + of + "were merged away by compaction, so deleting them is impossible and"
                    + " re-ingesting would duplicate rows that still exist in the merged file(s)");
        if (!replaced.isEmpty())
            why.add(replaced.size() + of + "had rows replaced by a later arrival (Replace the period or Replace"
                    + " everything), so re-ingesting would bring back rows that were replaced");
        String reason = "Refusing to reprocess " + batchId + ": " + String.join("; ", why)
                + ". Load a corrected file instead (for example with Replace the period), or rewrite the affected"
                + " partition(s).";
        List<String> affected = new java.util.ArrayList<>(compacted);
        affected.addAll(replaced);
        String message = reason + " Affected: " + affected;
        log.warn("[REPROCESS] {}", message);
        throw new Refused(message, reason);
    }

    /**
     * A reprocess refused because the Consignment's outputs were merged away by a compaction or had rows replaced by a
     * later arrival: the control plane answers it {@code 409} with {@link #reason()} (board {@code REPROCESS-REFUSAL-500-1}).
     * The message also names the affected files, which are server paths: for the log, never for the person.
     */
    public static final class Refused extends IllegalStateException {
        private final String reason;

        Refused(String message, String reason) {
            super(message);
            this.reason = reason;
        }

        /** Why, naming no server path: how many outputs, what happened to them, what to do instead. */
        public String reason() {
            return reason;
        }
    }
}
