package com.gamma.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SE-2a — hand-rename detection in the Space lock ({@code spec-space-lock.md} §SE-2a): the same {@code uid} under a
 * new id is reported as a rename with every config that still names the old id; a {@code uid} gone is a delete with
 * its dependents. Report-only by default: no file is changed.
 */
class SpaceLockRenameTest {

    @AfterEach
    void restore() {
        System.clearProperty(SpaceLock.RENAME_REWRITE_PROPERTY);
    }

    private static Path space(Path tmp) throws IOException {
        Path config = Files.createDirectories(tmp.resolve("alpha/config"));
        write(config.resolve("orders/orders_pipeline.toon"), "id: orders\nname: Orders\n");
        write(config.resolve("orders/orders_daily_enrich.toon"), "name: ORDERS_DAILY\ntriggers:\n  on_pipeline: orders\n");
        write(config.resolve("jobs/nightly_job.toon"), "job:\n  name: nightly\n  type: noop\n  on_pipeline: orders\n");
        return config;
    }

    private static void write(Path p, String text) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(SpaceLock.Report r, String list) {
        return (List<Map<String, Object>>) ((Map<String, Object>) r.body().get(list)).get("items");
    }

    @SuppressWarnings("unchecked")
    private static List<String> paths(Map<String, Object> entry, String list) {
        return ((List<Map<String, Object>>) entry.get(list)).stream().map(m -> String.valueOf(m.get("path"))).sorted().toList();
    }

    @Test
    void aHandRenameIsReportedWithEveryFileStillUsingTheOldName(@TempDir Path tmp) throws Exception {
        Path config = space(tmp);
        SpaceLock.compute("alpha", config, List.of());
        String enrich = Files.readString(config.resolve("orders/orders_daily_enrich.toon"));
        String job = Files.readString(config.resolve("jobs/nightly_job.toon"));

        write(config.resolve("orders/orders_pipeline.toon"), "id: orders_v2\nname: Orders\n");   // renamed in an editor
        SpaceLock.Report r = SpaceLock.compute("alpha", config, List.of());

        List<Map<String, Object>> renamed = items(r, "renamed");
        assertEquals(1, renamed.size(), r.body().toString());
        Map<String, Object> rn = renamed.get(0);
        assertEquals("pipeline", rn.get("kind"));
        assertEquals("orders", rn.get("from"));
        assertEquals("orders_v2", rn.get("to"));
        assertEquals("orders/orders_pipeline.toon", rn.get("path"));
        assertEquals(List.of("jobs/nightly_job.toon", "orders/orders_daily_enrich.toon"), paths(rn, "referrers"));
        assertEquals("report", r.body().get("renameRewrite"), "off by default");
        assertEquals(enrich, Files.readString(config.resolve("orders/orders_daily_enrich.toon")), "report-only");
        assertEquals(job, Files.readString(config.resolve("jobs/nightly_job.toon")), "report-only");

        // still reported on the next computation while the referrers still say the old name …
        assertEquals(1, items(SpaceLock.compute("alpha", config, List.of()), "renamed").size());
        // … and gone once they are fixed
        write(config.resolve("orders/orders_daily_enrich.toon"), "name: ORDERS_DAILY\ntriggers:\n  on_pipeline: orders_v2\n");
        write(config.resolve("jobs/nightly_job.toon"), "job:\n  name: nightly\n  type: noop\n  on_pipeline: orders_v2\n");
        assertEquals(0, items(SpaceLock.compute("alpha", config, List.of()), "renamed").size());
    }

    @Test
    void anEditThatKeepsTheIdIsNoRename(@TempDir Path tmp) throws Exception {
        Path config = space(tmp);
        SpaceLock.compute("alpha", config, List.of());
        write(config.resolve("orders/orders_pipeline.toon"), "id: orders\nname: Orders, edited\n");
        SpaceLock.Report r = SpaceLock.compute("alpha", config, List.of());
        assertEquals(0, items(r, "renamed").size(), r.body().toString());
        assertEquals(0, items(r, "deleted").size(), r.body().toString());
    }

    @Test
    void aDeletedConfigIsReportedWithItsDependents(@TempDir Path tmp) throws Exception {
        Path config = space(tmp);
        SpaceLock.compute("alpha", config, List.of());
        Files.delete(config.resolve("orders/orders_pipeline.toon"));
        SpaceLock.Report r = SpaceLock.compute("alpha", config, List.of());

        List<Map<String, Object>> deleted = items(r, "deleted");
        assertEquals(1, deleted.size(), r.body().toString());
        assertEquals("orders", deleted.get(0).get("id"));
        assertEquals(List.of("jobs/nightly_job.toon", "orders/orders_daily_enrich.toon"), paths(deleted.get(0), "dependents"));
        assertEquals(0, items(r, "renamed").size());

        // a new config taking the id again ends the report
        write(config.resolve("orders/orders_pipeline.toon"), "id: orders\nname: Orders again\n");
        assertEquals(0, items(SpaceLock.compute("alpha", config, List.of()), "deleted").size());
    }

    @Test
    void theOptInIsReadFromSpaceToonAndTheKillSwitchWins(@TempDir Path tmp) throws Exception {
        Path config = space(tmp);
        assertEquals("report", SpaceLock.renameRewriteMode(config), "no space.toon");
        write(tmp.resolve("alpha/space.toon"), "display_name: Alpha\nrename_rewrite: true\n");
        assertEquals("rewrite", SpaceLock.renameRewriteMode(config));
        System.setProperty(SpaceLock.RENAME_REWRITE_PROPERTY, "off");
        assertEquals("disabled", SpaceLock.renameRewriteMode(config));
        System.clearProperty(SpaceLock.RENAME_REWRITE_PROPERTY);
        write(tmp.resolve("alpha/space.toon"), "display_name: Alpha\nrename_rewrite: false\n");
        assertEquals("report", SpaceLock.renameRewriteMode(config));
    }

    /** Renaming a Space's display name rewrites {@code space.toon}; its opt-in (a key the manifest does not own) stays. */
    @Test
    void rewritingTheManifestKeepsTheOptIn(@TempDir Path tmp) throws Exception {
        Path manifest = tmp.resolve("space.toon");
        write(manifest, "display_name: Alpha\nrename_rewrite: true\nmin_product_version: 4.0.0\ndata_root: /x\n");
        new SpaceContext.SpaceManifest("Beta", "", "2026-11-13").write(manifest);
        Map<String, Object> m = com.gamma.util.ToonHelper.load(manifest.toString());
        assertEquals("Beta", m.get("display_name"));
        assertEquals("true", String.valueOf(m.get("rename_rewrite")));
        assertEquals("4.0.0", String.valueOf(m.get("min_product_version")));
        assertFalse(m.containsKey("data_root"), "data_root stays owned by the manifest record");
    }
}
