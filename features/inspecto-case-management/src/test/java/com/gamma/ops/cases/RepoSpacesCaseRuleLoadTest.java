package com.gamma.ops.cases;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The inspecto-case-management half of the committed-Space-config sweep: every authored {@code *_caserule.toon}
 * must load with {@link CaseRule#load} — the same call the boot scan makes (moved from RepoSpacesOpsConfigLoadTest, MODULE-REORG-P7).
 * 
 * <p>⛔ The empty sweep is an ASSERTION, not an assumption — same rule as the core sweep. The corpus is
 * committed, so finding nothing means the walk broke, not that there is nothing to gate.
 */
class RepoSpacesCaseRuleLoadTest {

    @Test
    void everyAuthoredCaseRuleLoadsWithItsRealLoader() throws IOException {
        // surefire's CWD is the features/inspecto-case-management/ module dir; the spaces tree is a repo-root sibling.
        Path root = Path.of("..", "..", "spaces").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(root),
                "found NO spaces/ tree at " + root + " — the walk-up is broken, not the corpus");

        List<String> failures = new ArrayList<>();
        List<Path> swept = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path f : walk.filter(Files::isRegularFile)
                              .filter(p -> { String n = p.getFileName().toString();
                                             return n.endsWith("_caserule.toon"); })
                              // uat is generated, _shared is runtime state — only authored trees are guarded
                              .filter(p -> { String s = root.relativize(p).toString().replace('\\', '/');
                                             return !s.startsWith("uat/") && !s.startsWith("_shared/"); })
                              .toList()) {
                swept.add(f);
                String name = f.getFileName().toString();
                try {
                    assertNotNull(CaseRule.load(f));
                } catch (Exception e) {
                    failures.add(root.relativize(f) + " -> " + e.getMessage());
                }
            }
        }
        assertFalse(swept.isEmpty(), "swept NO case rule configs under " + root + " — an empty sweep proves nothing");
        assertTrue(failures.isEmpty(), "unloadable case rule configs:\n  " + String.join("\n  ", failures));
    }
}
