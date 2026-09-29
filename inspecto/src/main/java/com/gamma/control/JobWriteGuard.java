package com.gamma.control;

import com.gamma.job.AttachApprovals;
import com.gamma.job.JobTemplate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * The ONE write-time gate for a report Job that mails its artifact as an ATTACHMENT (ASSURE-XLSX-ATTACHMENTS-1).
 * The approval itself is pinned to CONTENT by {@link AttachApprovals}; this class wires it into the writers.
 *
 * <ul>
 *   <li>{@link #mandatory} — called by {@link PendingChanges#hold} for {@code kind = job}, so every holding route
 *       ({@code /jobs} POST/PUT/enable/disable/reschedule, {@code /config/write}, {@code /config/patch}) gets it.
 *       A write of an attaching Job whose template-EXPANDED fingerprint is not the approved one is held, whatever
 *       the policy; {@link #fingerprint} is stored on the Pending Change, and approve checks and records THAT.</li>
 *   <li>{@link #revokeOnDelete} — a delete through any holding route revokes the approval.</li>
 *   <li>{@link #refuseImport} — called by {@code ImportCapabilityGuard} for every job an import carries, AFTER
 *       template expansion: N items cannot be one Pending Change, so an attaching report Job is refused (403).</li>
 * </ul>
 * {@code JobWritersTest} pins that every job writer reaches one of these.
 */
final class JobWriteGuard {

    private JobWriteGuard() {}

    /** The attachment fingerprint a job write proposes (template-expanded), or {@code null} when it does not attach. */
    static String fingerprint(String kind, Map<String, Object> proposed, Path root, ApiContext api) {
        if (!JobRoutes.KIND.equals(kind) || proposed == null) return null;
        Map<String, Object> expanded = AttachApprovals.expand(proposed, AttachApprovals.templates(root));
        if (!AttachApprovals.attaches(expanded)) return null;
        try {
            // The data root is read only HERE, for an attaching report Job — never on every hold (see dataDir).
            return AttachApprovals.fingerprint(expanded, root, dataDir(api));
        } catch (IllegalArgumentException notApprovable) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, notApprovable.getMessage());
        }
    }

    /**
     * The data root the report Job reads with (so hold, approve and run hash the same relation).
     *
     * <p>🔴 {@code jobService()}, never {@code jobServiceOrCreate()}: this is reached from {@code PendingChanges.hold},
     * which every config write passes, and creating a JobService as a side effect of a component write started one
     * whose run ledger defaults to {@code jobs_audit/} in the process working directory (caught by
     * {@code CwdJobsAuditLeakDetector} on {@code ControlApiAsyncV1Test}). A write that reaches here is a job write,
     * whose route already made the JobService; with none, the report Job's own default (no data root) is used.
     */
    static String dataDir(ApiContext api) {
        return api.service().jobService().map(com.gamma.job.JobService::dataDir).orElse(null);
    }

    /** The mandatory four-eyes rule when {@code fingerprint} is not the approved version of Job {@code proposed}. */
    static ApprovalPolicy.Rule mandatory(Map<String, Object> proposed, String fingerprint, Path root) {
        if (fingerprint == null || AttachApprovals.approved(root, name(proposed), fingerprint)) return null;
        return new ApprovalPolicy.Rule(true, ApprovalPolicy.DEFAULT_APPROVER, true);
    }

    /** A job delete that is going ahead revokes that Job's attachment approval. */
    static void revokeOnDelete(String kind, Map<String, Object> proposed, Map<String, Object> current, Path root)
            throws IOException {
        if (JobRoutes.KIND.equals(kind) && proposed == null && current != null)
            AttachApprovals.revoke(root, name(current));
    }

    /** An import carrying an attaching report Job (after expanding its template): refused outright (403). */
    static void refuseImport(Map<?, ?> job, Map<String, JobTemplate> templates) {
        if (AttachApprovals.attaches(AttachApprovals.expand(job, templates)))
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED,
                    "a report job that attaches data needs approval; create it through /jobs");
    }

    /** The ONE predicate, shared with {@code ReportJob}'s run-time lock. */
    static boolean attaches(Map<?, ?> job) {
        return AttachApprovals.attaches(job);
    }

    /** The Job's own name (what a run looks its approval up by), whatever file a config write names. */
    static String name(Map<?, ?> job) {
        Map<?, ?> j = job.get("job") instanceof Map<?, ?> inner ? inner : job;
        return j.get("name") == null ? null : String.valueOf(j.get("name")).trim();
    }
}
