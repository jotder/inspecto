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
 * Implements {@code ura reprocess <pipeline.toon> <batch_id>}: deletes the
 * batch's output files and markers, restores its member files from backup into
 * the inbox, supersedes the manifest, and triggers a fresh poll.
 *
 * <p>Reprocessing is whole-batch only; the original audit rows remain as history.
 */
public final class ReprocessCommand {

    private static final Logger log = LoggerFactory.getLogger(ReprocessCommand.class);

    private ReprocessCommand() {}

    public static void run(String toonPath, String batchId) throws Exception {
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
     */
    private static void guardAgainstCompactedOutputs(String batchId, ConsignmentManifest m) {
        DbConsignmentOutputStore registry = ConsignmentOutputStores.shared();
        if (registry == null) return;

        List<String> compacted = registry.outputs(batchId).stream()
                .filter(o -> o.state() == ConsignmentOutput.State.COMPACTED_AWAY)
                .map(ConsignmentOutput::path)
                .toList();
        if (compacted.isEmpty()) return;

        throw new IllegalStateException("Refusing to reprocess " + batchId + ": " + compacted.size()
                + " of its " + m.outputs.size() + " output file(s) were merged away by compaction, so deleting"
                + " them is impossible and re-ingesting would duplicate rows that still exist in the merged"
                + " file(s). Affected: " + compacted
                + ". Rewrite the affected partition(s) instead, or restore from backup.");
    }
}
