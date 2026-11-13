package com.gamma.control;

import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceId;
import com.gamma.service.SpaceManager;
import com.gamma.service.SpaceOrigin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SE-5b — {@link SpaceTemplateUpgrade#propose}, the three-way classification the HTTP proof does not reach: a file the Space
 * deleted ({@code kept} / {@code conflict}), a file the template adds ({@code add}) or adds where the Space has its own
 * ({@code conflict}), a file the Space changed while the template did not ({@code kept}), and a seed-stamped Job that only
 * differs from the template by the server's stamps (left out: decoded-TOON equality, {@link InstalledApps#sameConfig}).
 */
class SpaceTemplateUpgradeTest {

    @AfterEach
    void reset() {
        MetricRegistry.global().reset();
    }

    private static Path tpl(Path root) {
        return root.resolve("_templates").resolve("starter");
    }

    private static void put(Path dir, String rel, String text) throws Exception {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    /** Version 1 of {@code starter}, a Space {@code acme} made from it, then version 2. */
    private static Path space(Path root) throws Exception {
        Path config = tpl(root).resolve("config");
        Files.createDirectories(config);
        Files.writeString(tpl(root).resolve("template.toon"), "name: Starter\nversion: \"1\"\n");
        put(config, "kept_pipeline.toon", "name: kept\n");
        put(config, "deleted_pipeline.toon", "name: deleted\n");
        put(config, "gone_pipeline.toon", "name: gone\n");
        put(config, "daily_job.toon", "name: daily\ntype: noop\n");
        try (SpaceManager spaces = SpaceManager.discover(root)) {
            spaces.createFromTemplate(SpaceId.of("acme"), null, null, "starter");
        }
        Path base = root.resolve("acme");
        Path mine = base.resolve("config");
        Files.writeString(mine.resolve("kept_pipeline.toon"), "name: kept\nnotes: mine\n");   // Space edit, template unchanged
        Files.delete(mine.resolve("deleted_pipeline.toon"));                               // Space deleted it
        Files.writeString(mine.resolve("own_pipeline.toon"), "name: own\n");                // the Space's own, unrecorded
        // the server stamps a seeded Job at creation: the recorded hash is of the stamped file
        Files.writeString(mine.resolve("daily_job.toon"), "name: daily\ntype: noop\ncreatedBy: admin-1\n");
        SpaceOrigin.recordUpgrade(base, "1", Map.of("daily_job.toon", SpaceOrigin.sha256(Files.readAllBytes(mine.resolve("daily_job.toon")))));

        Files.writeString(tpl(root).resolve("template.toon"), "name: Starter\nversion: \"2\"\n");
        put(config, "deleted_pipeline.toon", "name: deleted\nnotes: v2\n");   // changed where the Space deleted it
        put(config, "own_pipeline.toon", "name: own\nnotes: v2\n");           // added where the Space has its own
        put(config, "new_pipeline.toon", "name: new\n");                       // added
        Files.delete(config.resolve("gone_pipeline.toon"));                    // removed by the template
        return base;
    }

    @Test
    void eachFileIsClassifiedThreeWays(@TempDir Path root) throws Exception {
        Path base = space(root);
        SpaceTemplateUpgrade.Proposal p = SpaceTemplateUpgrade.propose(SpaceOrigin.read(base).orElseThrow(),
                base.resolve("config").toAbsolutePath().normalize(), tpl(root), "acme");
        assertEquals("1", p.fromVersion());
        assertEquals("2", p.toVersion());
        assertEquals("kept", p.entry("kept_pipeline.toon").change());
        assertEquals("conflict", p.entry("deleted_pipeline.toon").change(), "Space deleted, template changed");
        assertNull(p.entry("deleted_pipeline.toon").space());
        assertEquals("conflict", p.entry("own_pipeline.toon").change(), "the Space's own file where the template now adds one");
        assertEquals("add", p.entry("new_pipeline.toon").change());
        assertEquals("template-removed", p.entry("gone_pipeline.toon").change());
        assertNull(p.entry("daily_job.toon"), "a Job that differs only by the server's stamps is not a change");
        assertEquals(java.util.List.of("new_pipeline.toon"), p.applicable());
    }

    @Test
    void theProposalIdMovesWhenTheSpaceMoves(@TempDir Path root) throws Exception {
        Path base = space(root);
        Path config = base.resolve("config").toAbsolutePath().normalize();
        String before = SpaceTemplateUpgrade.propose(SpaceOrigin.read(base).orElseThrow(), config, tpl(root), "acme").id();
        assertEquals(before, SpaceTemplateUpgrade.propose(SpaceOrigin.read(base).orElseThrow(), config, tpl(root), "acme").id());
        Files.writeString(config.resolve("kept_pipeline.toon"), "name: kept\nnotes: mine again\n");
        assertNotEquals(before, SpaceTemplateUpgrade.propose(SpaceOrigin.read(base).orElseThrow(), config, tpl(root), "acme").id());
    }
}
