package com.gamma.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** ASSURE-XLSX-ATTACHMENTS-1 round 5 item 3: ONE template discovery for the loader, the hold and the approve. */
class JobTemplateDiscoveryTest {

    private static String template(String name, String recipients) {
        return "job_template:\n  name: " + name + "\n  job:\n    type: report\n    recipients: " + recipients + "\n";
    }

    @Test
    void discoveryWalksEveryDepthSortedAndKeepsTheFirstOfADuplicate(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("a/b/c/d/e"));
        Files.writeString(root.resolve("a/b/c/d/e/deep_job_template.toon"), template("deep", "x@example.com"));
        Files.writeString(root.resolve("a/dup_job_template.toon"), template("dup", "first@example.com"));
        Files.createDirectories(root.resolve("b"));
        Files.writeString(root.resolve("b/dup_job_template.toon"), template("dup", "second@example.com"));
        Files.writeString(root.resolve("bad_job_template.toon"), "not: [a template");

        Map<String, JobTemplate> found = JobTemplate.discover(List.of(root));
        assertEquals(java.util.Set.of("deep", "dup"), found.keySet(), "depth 5 found; the bad one skipped");
        assertEquals("first@example.com", found.get("dup").jobBlock().get("recipients"), "sorted, first kept");

        // the approval side IS the loader's function — same result, not a re-implementation
        assertEquals(found.keySet(), AttachApprovals.templates(root).keySet());
        assertEquals("first@example.com", AttachApprovals.templates(root).get("dup").jobBlock().get("recipients"));
    }
}
