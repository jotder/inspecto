package com.gamma.ops.tag;

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
 * The inspecto-ops half of the committed-Space-config sweep: the three authored kinds whose real loaders
 * live in THIS module ({@code *_tag}, {@code *_tagrule}, {@code *_caserule}) must load with
 * {@link Tag#load}, {@link TagRule#load} and {@link CaseRule#load} — the same calls the boot scan makes.
 *
 * <p>🔴 <b>Why this is a separate class from {@code RepoSpacesConfigValidationTest}.</b> That sweep covers
 * all ~142 authored TOONs and used to live here, in {@code inspecto-ops} — a module the root POM gates
 * behind {@code -Pedition-standard}/{@code -Pedition-enterprise}, so it never ran in a plain
 * {@code mvn -o clean test}. It moved to {@code inspecto} (always built) on 2026-09-17. These three kinds
 * could not follow it: their loaders are edition-module classes the core cannot reference. The core sweep
 * still decodes these files at the syntax layer; this class is the only place their real {@code load()}s
 * run, and it runs wherever {@code inspecto-ops} is built (CI builds {@code -Pedition-enterprise}).
 *
 * <p>⛔ The empty sweep is an ASSERTION, not an assumption — same rule as the core sweep. The corpus is
 * committed, so finding nothing means the walk broke, not that there is nothing to gate.
 */
class RepoSpacesOpsConfigLoadTest {

    @Test
    void everyAuthoredOpsConfigLoadsWithItsRealLoader() throws IOException {
        // surefire's CWD is the inspecto-ops/ module dir; the spaces tree is a repo-root sibling.
        Path root = Path.of("..", "spaces").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(root),
                "found NO spaces/ tree at " + root + " — the walk-up is broken, not the corpus");

        List<String> failures = new ArrayList<>();
        List<Path> swept = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path f : walk.filter(Files::isRegularFile)
                              .filter(p -> { String n = p.getFileName().toString();
                                             return n.endsWith("_tag.toon") || n.endsWith("_tagrule.toon")
                                                     || n.endsWith("_caserule.toon"); })
                              // uat is generated, _shared is runtime state — only authored trees are guarded
                              .filter(p -> { String s = root.relativize(p).toString().replace('\\', '/');
                                             return !s.startsWith("uat/") && !s.startsWith("_shared/"); })
                              .toList()) {
                swept.add(f);
                String name = f.getFileName().toString();
                try {
                    if (name.endsWith("_tag.toon"))          assertNotNull(Tag.load(f));
                    else if (name.endsWith("_tagrule.toon")) assertNotNull(TagRule.load(f));
                    else                                     assertNotNull(CaseRule.load(f));
                } catch (Exception e) {
                    failures.add(root.relativize(f) + " -> " + e.getMessage());
                }
            }
        }
        assertFalse(swept.isEmpty(), "swept NO ops configs under " + root + " — an empty sweep proves nothing");
        assertTrue(failures.isEmpty(), "unloadable ops space configs:\n  " + String.join("\n  ", failures));
    }
}
