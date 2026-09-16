package com.gamma.service;

import com.gamma.alert.AlertRule;
import com.gamma.config.io.ConfigCodec;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.job.JobConfig;
import com.gamma.objects.RcaTemplate;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards the repo's committed sample spaces ({@code ../spaces/<id>/config} + {@code ../spaces/_templates}):
 * every authored TOON must parse with the same loaders the boot scan uses — a silently-mangled config
 * (a stray {@code #} comment, a bad list count, a wrong block key) fails here instead of being dropped
 * at serve time. Pipelines/registry components are validated at the syntax layer (decode + key presence);
 * the suffix-scanned alert/enrich/job/rca kinds run through their real {@code load()}s.
 *
 * <p>🔴 <b>Lives HERE, in an always-built module, deliberately.</b> This sweep sat in
 * {@code inspecto-ops} until 2026-09-17, and that module is <em>not</em> in the root POM's default
 * {@code <modules>} — it is added only by {@code -Pedition-standard} / {@code -Pedition-enterprise}. So the
 * repo's only sweep over every committed Space config <b>never ran in a plain {@code mvn -o clean test}</b>:
 * measured on {@code 819e597b}, a verified-green 23-module default reactor (4373 tests) produced <b>no</b>
 * surefire report for this class, while the sibling corpus guard {@code ShippedCatalogSamplesTest} produced
 * one. ⛔ Do not move it back into an edition module — a guard the routine local build cannot run is a
 * guard that finds breakage only on CI, or on nobody.
 *
 * <p>⚠ The three {@code inspecto-ops}-owned kinds ({@code *_tag}, {@code *_tagrule}, {@code *_caserule})
 * cannot run their real {@code load()}s from here — those classes live in the gated module. They are still
 * swept at the syntax layer by the generic decode below, and their real loaders are driven by
 * {@code RepoSpacesOpsConfigLoadTest} in {@code inspecto-ops}. {@code RcaTemplate} is core
 * ({@code inspecto-engine}), so {@code *_rca} keeps its real loader here.
 */
class RepoSpacesConfigValidationTest {

    private static Path spacesRoot() {
        // surefire's CWD is the inspecto/ module dir; the spaces tree is a repo-root sibling.
        Path p = Path.of("..", "spaces").toAbsolutePath().normalize();
        return Files.isDirectory(p) ? p : null;
    }

    @Test
    void everyAuthoredSpaceConfigParses() throws IOException {
        Path root = spacesRoot();
        // ASSERTION, not an assumption (2026-09-07). The corpus is COMMITTED — `spaces/**` is in the
        // repo — so an empty sweep never means "nothing to gate", it means the walk stopped finding it:
        // a module move, a surefire CWD change, a renamed directory. `assumeTrue` turned that into a
        // silent green and the gate would be off with nobody told.
        assertNotNull(root, "found NO spaces/ tree — the walk-up is broken, not the corpus; this sweep would prove nothing");
        List<String> failures = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path f : walk.filter(Files::isRegularFile)
                              .filter(p -> p.getFileName().toString().endsWith(".toon"))
                              // uat is generated, _shared is runtime state — only authored trees are guarded
                              .filter(p -> { String s = root.relativize(p).toString().replace('\\', '/');
                                             return !s.startsWith("uat/") && !s.startsWith("_shared/"); })
                              .toList()) {
                String name = f.getFileName().toString();
                try {
                    validate(f, name);
                } catch (Exception e) {
                    failures.add(root.relativize(f) + " -> " + e.getMessage());
                }
            }
        }
        assertTrue(failures.isEmpty(), "unparseable space configs:\n  " + String.join("\n  ", failures));
    }

    private void validate(Path f, String name) throws IOException {
        if (name.endsWith("_enrich.toon"))          { EnrichmentConfig.load(f.toString()); return; }
        if (name.endsWith("_alert.toon"))           { AlertRule.load(f); return; }
        if (name.endsWith("_rca.toon"))             { RcaTemplate.load(f); return; }
        if (name.endsWith("_job.toon")) {
            Map<String, Object> raw = ConfigCodec.toMap(Files.readString(f));
            Object job = raw.get("job");
            // template-instantiated jobs are expanded by the boot scan — standalone load can't resolve them
            if (job instanceof Map<?, ?> j && j.get("template") == null) JobConfig.load(f.toString());
            return;
        }
        // pipelines, schemas, grammars, meta, registry components, manifests, templates:
        // decode must succeed (the parser mangles '#' comments and bad list counts into junk/failures)
        Map<String, Object> raw = ConfigCodec.toMap(Files.readString(f));
        assertFalse(raw.isEmpty(), "decoded to an empty map");
        if (name.endsWith("_pipeline.toon")) {
            assertTrue(raw.containsKey("name") && raw.containsKey("dirs") && raw.containsKey("processing"),
                    "pipeline missing name/dirs/processing");
        }
    }
}
