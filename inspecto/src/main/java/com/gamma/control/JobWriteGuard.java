package com.gamma.control;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The ONE gate every writer of a {@code job} passes for a report Job that mails its artifact as an ATTACHMENT
 * (ASSURE-XLSX-ATTACHMENTS-1, operator 2026-09-29).
 *
 * <ul>
 *   <li>{@link #mandatory} — called by {@link PendingChanges#hold} itself for {@code kind = job}, so every route
 *       that holds a job write ({@code /jobs} POST/PUT/enable/disable/reschedule, {@code /config/write},
 *       {@code /config/patch}) gets the mandatory four-eyes rule with no call site to forget.</li>
 *   <li>{@link #refuseImport} — called by {@code ImportCapabilityGuard} for every job an import carries (the
 *       {@code /bundle/import} job items, a pipeline closure's and the {@code /import} zip's {@code *_job.toon},
 *       a new Space's bundle): N items cannot be one Pending Change, so an attaching report Job is refused (403)
 *       before anything is written, whatever the policy.</li>
 * </ul>
 * {@code JobWritersTest} pins that every job writer reaches one of the two.
 */
final class JobWriteGuard {

    private JobWriteGuard() {}

    /** Params whose change on an ALREADY-approved attach Job re-opens the mandatory hold: between them they decide
     *  what data leaves and to whom (re-verification finding 4). */
    static final List<String> SENSITIVE = List.of("type", "attach", "recipients", "dataset", "scope", "measures",
            "group_by", "format", "out_dir", "limit", "connection", "use");

    /**
     * The mandatory rule for a job write, or {@code null}. Held when the proposed Job is a report with
     * {@code attach: true} and either the current one is not, or any {@link #SENSITIVE} value differs.
     * {@code proposed}/{@code current} may be the {@code job:} section or a whole {@code {job: …}} document.
     */
    static ApprovalPolicy.Rule mandatory(String kind, Map<String, Object> proposed, Map<String, Object> current) {
        if (!"job".equals(kind)) return null;
        Map<?, ?> next = section(proposed);
        if (!attaches(next)) return null;
        Map<?, ?> prev = section(current);
        if (attaches(prev) && SENSITIVE.stream().allMatch(k -> Objects.equals(str(next.get(k)), str(prev.get(k)))))
            return null;
        return new ApprovalPolicy.Rule(true, ApprovalPolicy.DEFAULT_APPROVER, true);
    }

    /** An import carrying an attaching report Job: refused outright (403) — it must be proposed through {@code /jobs}. */
    static void refuseImport(Map<?, ?> job) {
        if (attaches(section(job)))
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED,
                    "a report job that attaches data needs approval; create it through /jobs");
    }

    /** The ONE predicate, shared with {@code ReportJob}'s run-time lock. */
    static boolean attaches(Map<?, ?> job) {
        return com.gamma.job.AttachApprovals.attaches(job);
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> section(Map<?, ?> m) {
        if (m == null) return Map.of();
        return m.get("job") instanceof Map<?, ?> j ? j : m;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }
}
