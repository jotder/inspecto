package com.gamma.control;

import com.gamma.config.safety.PathJail;
import com.gamma.job.JobResult;
import com.gamma.job.MaintenanceTaskContext;
import com.gamma.job.MaintenanceTaskProvider;
import com.gamma.pipeline.SpaceConfigRoot;

import java.nio.file.Path;
import java.util.Set;

/**
 * The {@code audit_anchor_export} maintenance task (ASSURE-AUDIT-CHAIN-RESIDUALS-1 (1)): schedule it as an ordinary
 * {@code maintenance} Job with {@code out_dir} naming the operator-owned directory. {@code out_dir} resolves under
 * the Space config root and goes through {@link PathJail} like every other Job path (no escape, never a
 * {@code *.secrets} directory). See {@link AuditAnchorExport} for the fail-closed, append-only contract.
 */
public final class AuditAnchorExportProvider implements MaintenanceTaskProvider {

    public static final String TASK = "audit_anchor_export";

    @Override
    public Set<String> tasks() {
        return Set.of(TASK);
    }

    @Override
    public JobResult run(String task, MaintenanceTaskContext ctx) throws Exception {
        long t0 = System.nanoTime();
        Path root = SpaceConfigRoot.current();
        if (root == null) throw new IllegalStateException(TASK + ": this Space has no config root, so no audit anchors");
        Path dir = PathJail.requireJobPathUnderAny(PathJail.allowedRoots(), root, ctx.cfg().require("out_dir"), "out_dir");
        Path dest = dir.resolve(AuditAnchorExport.DEST_FILE);
        if (ctx.dryRun())
            return JobResult.ok(TASK + "[dry-run]: would append the signed audit anchors to " + dest, 0L);
        int n = AuditAnchorExport.export(root, dest);
        return JobResult.ok(TASK + ": appended " + n + " anchor(s) to " + dest, (System.nanoTime() - t0) / 1_000_000L);
    }
}
