package com.gamma.control;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ASSURE-XLSX-ATTACHMENTS-1 re-verification finding 2: <b>every route that can write a {@code job} passes
 * {@link JobWriteGuard}</b>. The enumeration is {@link ConfigWriteFunnelTest}'s route inventory (every mutating
 * registration whose handler writes TOON or a component). A route passes when its handler reaches the real
 * {@code PendingChanges.hold} (which runs {@code JobWriteGuard.mandatory} itself — asserted below), or calls
 * {@code JobWriteGuard.} / {@code ImportCapabilityGuard.check} (the import gate), or sits on
 * {@link #NOT_A_JOB_WRITER} with the reason it cannot write a job. A NEW writer fails here until it does one.
 */
class JobWritersTest {

    private static final String FIXED = "writes a fixed kind other than job";

    /** Routes (as {@code "METHOD pattern"}) that write config but cannot write a job, each with its reason. */
    static final Map<String, String> NOT_A_JOB_WRITER = new TreeMap<>(Map.ofEntries(
            Map.entry("POST /tags", FIXED + " (tag catalog)"),
            Map.entry("POST /tags/([^/]+)/rename", FIXED + " (tag catalog)"),
            Map.entry("POST /tags/rules", FIXED + " (tag rule)"),
            Map.entry("POST /cases/rules", FIXED + " (case rule)"),
            Map.entry("POST /connections", FIXED + " (connection)"),
            Map.entry("PUT /connections/([^/]+)", FIXED + " (connection)"),
            Map.entry("POST /notifications/channels", FIXED + " (channel)"),
            Map.entry("PUT /notifications/channels/([^/]+)", FIXED + " (channel)"),
            Map.entry("DELETE /notifications/channels/([^/]+)", FIXED + " (channel)"),
            Map.entry("POST /notifications/rules", FIXED + " (notification rule)"),
            Map.entry("PUT /notifications/rules/([^/]+)", FIXED + " (notification rule)"),
            Map.entry("DELETE /notifications/rules/([^/]+)", FIXED + " (notification rule)"),
            Map.entry("POST /requirements", FIXED + " (requirement)"),
            Map.entry("POST /requirements/([^/]+)/decision", FIXED + " (requirement)"),
            Map.entry("POST /requirements/([^/]+)/deliver", FIXED + " (requirement)"),
            Map.entry("POST /decision-rules/([^/]+)/simulate", FIXED + " (a decision rule's result stamp)"),
            Map.entry("POST /expectations/evaluate", FIXED + " (an expectation's result stamp)"),
            Map.entry("POST /expectations/([^/]+)/evaluate", FIXED + " (an expectation's result stamp)"),
            Map.entry("POST /inv/investigations/([^/]+)/alert-rules", FIXED + " (alert-rule, inspecto-geo-link)"),
            Map.entry("PUT /settings/egress", FIXED + " (egress.toon)"),
            Map.entry("PUT /settings/mail-attachments", FIXED + " (mail-attachments.toon)"),
            Map.entry("POST /pipelines/rename/resume", "finishes an already-admitted rename; rewrites only on_pipeline "
                    + "references in existing jobs, never their params")
    ));

    @Test
    void everyRouteThatCanWriteAJobRunsJobWriteGuard() throws IOException {
        String hold = ConfigWriteFunnelTest.methodBodies(ConfigWriteFunnelTest.withoutComments(Files.readString(
                Path.of("src/main/java/com/gamma/control/PendingChanges.java")))).get("hold");
        assertTrue(hold != null && hold.contains("JobWriteGuard.mandatory("),
                "PendingChanges.hold must run JobWriteGuard.mandatory — every holding route relies on it");

        String imports = ConfigWriteFunnelTest.methodBodies(ConfigWriteFunnelTest.withoutComments(Files.readString(
                Path.of("src/main/java/com/gamma/control/ImportCapabilityGuard.java")))).get("requireIfAdministerOnly");
        assertTrue(imports != null && imports.contains("JobWriteGuard.refuseImport("),
                "every import of a job (items and files) funnels through requireIfAdministerOnly, which must refuse "
                        + "an attaching report Job");

        Set<String> writers = new LinkedHashSet<>();
        Set<String> open = new LinkedHashSet<>();
        for (ConfigWriteFunnelTest.Verdict v : ConfigWriteFunnelTest.scan()) {
            if (!v.toon() && !v.component()) continue;
            writers.add(v.route());
            if (v.holdsItself() || v.closure().contains("JobWriteGuard.")
                    || v.closure().contains("ImportCapabilityGuard.check")
                    || NOT_A_JOB_WRITER.containsKey(v.route())) continue;
            open.add(v.route() + "  [" + v.file().getFileName() + "]");
        }
        for (String pinned : new String[] {"POST /jobs", "PUT /jobs/([^/]+)", "POST /config/write", "POST /config/patch"})
            assertTrue(writers.contains(pinned), "the writer scan went blind to " + pinned + ": " + writers);
        assertTrue(open.isEmpty(), () -> "routes that could write a job without JobWriteGuard — hold through "
                + "PendingChanges.hold, call JobWriteGuard / ImportCapabilityGuard, or list the route with its "
                + "reason:\n  " + String.join("\n  ", open));
        Set<String> stale = new LinkedHashSet<>(NOT_A_JOB_WRITER.keySet());
        stale.removeAll(writers);
        assertTrue(stale.isEmpty(), () -> "NOT_A_JOB_WRITER rows naming no writer route: " + stale);
    }

    /** Round 3 (a), defence in depth: an import carrying an attaching report TEMPLATE is refused before any write. */
    @Test
    void anImportedJobTemplateThatAttachesIsRefused() {
        String attaching = "job_template:\n  name: mailer\n  job:\n    type: report\n    attach: \"${a}\"\n";
        ApiException e = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class, () -> ImportCapabilityGuard.checkFiles(
                null, Map.of("jobs/mailer_job_template.toon", attaching.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertTrue(e.getMessage().contains("attaches data needs approval"), e.getMessage());
        ImportCapabilityGuard.checkFiles(null, Map.of("jobs/plain_job_template.toon",
                attaching.replace("\"${a}\"", "false").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
