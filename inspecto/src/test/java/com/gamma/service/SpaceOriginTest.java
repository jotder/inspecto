package com.gamma.service;

import com.gamma.metrics.MetricRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SE-5a — {@link SpaceOrigin}: a Space made from a template records template, version and per-file hashes; a Space without
 * a record reads as none; adopt matches by hash (full, partial, none); the report splits the Space's own changes from what
 * a newer template version changed. Spec: {@code docs/okf/product/module/config/spec-space-origin.md}.
 */
class SpaceOriginTest {

    @AfterEach
    void reset() {
        MetricRegistry.global().reset();
    }

    private static Path template(Path root, String version) throws Exception {
        Path tpl = root.resolve("_templates").resolve("starter");
        Files.createDirectories(tpl.resolve("config").resolve("registry").resolve("datasets"));
        Files.writeString(tpl.resolve("template.toon"), "name: Starter\n" + (version == null ? "" : "version: \"" + version + "\"\n"));
        Files.writeString(tpl.resolve("config").resolve("registry").resolve("datasets").resolve("d.toon"),
                "physicalRef: d/database\n");
        Files.writeString(tpl.resolve("config").resolve("notes.toon"), "space: ${SPACE}\n");
        return tpl;
    }

    @Test
    void createFromTemplateRecordsTemplateVersionAndHashes(@TempDir Path root) throws Exception {
        template(root, "1.0");
        try (SpaceManager spaces = SpaceManager.discover(root)) {
            spaces.createFromTemplate(SpaceId.of("acme"), null, null, "starter");
        }
        SpaceOrigin.Origin o = SpaceOrigin.read(root.resolve("acme")).orElseThrow();
        assertEquals("starter", o.template());
        assertEquals("1.0", o.templateVersion());
        assertEquals("created", o.origin());
        assertTrue(o.hashes().containsKey("registry/datasets/d.toon"), o.hashes().toString());
        assertTrue(o.hashes().containsKey("notes.toon"));
        assertEquals(o.hashes(), SpaceOrigin.hashTree(root.resolve("acme").resolve("config")),
                "the hashes are the Space's config exactly as created");
        assertFalse(Files.readString(root.resolve("acme").resolve(SpaceOrigin.RECORD)).contains("app:"),
                "a shipped template writes no App fields");
    }

    @Test
    void aTemplateWithoutVersionReadsAsUnversioned(@TempDir Path root) throws Exception {
        template(root, null);
        try (SpaceManager spaces = SpaceManager.discover(root)) {
            spaces.createFromTemplate(SpaceId.of("acme"), null, null, "starter");
            assertEquals("unversioned", spaces.templates().get(0).get("version"));
        }
        assertEquals("unversioned", SpaceOrigin.read(root.resolve("acme")).orElseThrow().templateVersion());
    }

    @Test
    void aSpaceWithoutARecordHasNoOrigin(@TempDir Path root) throws Exception {
        try (SpaceManager spaces = SpaceManager.discover(root)) {
            spaces.create(SpaceId.of("plain"), "Plain", "");
        }
        assertTrue(SpaceOrigin.read(root.resolve("plain")).isEmpty());
        assertFalse(Files.exists(root.resolve("plain").resolve(SpaceOrigin.RECORD)));
    }

    // ── adopt ──

    private static Path handMade(Path root, String datasetBody, boolean extra) throws Exception {
        Path base = root.resolve("old");
        Files.createDirectories(base.resolve("config").resolve("registry").resolve("datasets"));
        Files.writeString(base.resolve("config").resolve("registry").resolve("datasets").resolve("d.toon"), datasetBody);
        if (extra) Files.writeString(base.resolve("config").resolve("mine.toon"), "x: 1\n");
        return base;
    }

    @Test
    void adoptFullMatchRecordsTheOrigin(@TempDir Path root) throws Exception {
        Path tpl = template(root, "2");
        Path base = handMade(root, "physicalRef: d/database\n", false);
        Files.writeString(base.resolve("config").resolve("notes.toon"), "space: old\n");   // ${SPACE} rendered as the Space id
        SpaceOrigin.Adoption a = SpaceOrigin.adopt(tpl, base, "starter", "old");
        assertEquals("full", a.view().get("match"));
        assertEquals(List.of("notes.toon", "registry/datasets/d.toon"), a.matching());
        assertTrue(a.recorded());
        SpaceOrigin.Origin o = SpaceOrigin.read(base).orElseThrow();
        assertEquals("adopted", o.origin());
        assertEquals("2", o.templateVersion());
    }

    @Test
    void adoptPartialReportsEachSideAndTheReportShowsTheSpaceChange(@TempDir Path root) throws Exception {
        Path tpl = template(root, "2");
        Path base = handMade(root, "physicalRef: d/database\n", true);
        SpaceOrigin.Adoption a = SpaceOrigin.adopt(tpl, base, "starter", "old");
        assertEquals("partial", a.view().get("match"));
        assertEquals(List.of("registry/datasets/d.toon"), a.matching());
        assertEquals(List.of("notes.toon"), a.onlyInTemplate());
        assertEquals(List.of("mine.toon"), a.onlyInSpace());
        assertTrue(a.differing().isEmpty());
        assertTrue(a.recorded());

        Map<String, Object> r = SpaceOrigin.report(SpaceOrigin.read(base).orElseThrow(), base, tpl, "old");
        Map<?, ?> space = (Map<?, ?>) r.get("space");
        assertEquals(List.of("registry/datasets/d.toon"), space.get("unchanged"));
        assertEquals(List.of("notes.toon"), space.get("removed"), "the template's file the Space lacks");
        assertEquals(List.of("mine.toon"), space.get("added"));
    }

    @Test
    void adoptWithNothingInCommonRecordsNothing(@TempDir Path root) throws Exception {
        Path tpl = template(root, "2");
        Path base = handMade(root, "physicalRef: other/database\n", false);
        SpaceOrigin.Adoption a = SpaceOrigin.adopt(tpl, base, "starter", "old");
        assertEquals("none", a.view().get("match"));
        assertEquals(List.of("registry/datasets/d.toon"), a.differing());
        assertFalse(a.recorded());
        assertTrue(SpaceOrigin.read(base).isEmpty());
        assertFalse(Files.exists(base.resolve(SpaceOrigin.RECORD)));
    }

    // ── report ──

    @Test
    void reportSplitsSpaceChangesFromWhatANewerTemplateChanged(@TempDir Path root) throws Exception {
        Path tpl = template(root, "1.0");
        try (SpaceManager spaces = SpaceManager.discover(root)) {
            spaces.createFromTemplate(SpaceId.of("acme"), null, null, "starter");
        }
        Path base = root.resolve("acme");
        Map<String, Object> same = SpaceOrigin.report(SpaceOrigin.read(base).orElseThrow(), base, tpl, "acme");
        assertEquals("same-version", ((Map<?, ?>) same.get("templateSince")).get("status"));
        // egress.toon: the server writes it into every new Space before the record, so it is part of the created state
        assertEquals(List.of("egress.toon", "notes.toon", "registry/datasets/d.toon"), ((Map<?, ?>) same.get("space")).get("unchanged"));

        // the Space edits one file; the template moves to 1.1, changing the other and adding a third
        Files.writeString(base.resolve("config").resolve("registry").resolve("datasets").resolve("d.toon"), "physicalRef: e/database\n");
        Files.writeString(tpl.resolve("template.toon"), "name: Starter\nversion: \"1.1\"\n");
        Files.writeString(tpl.resolve("config").resolve("notes.toon"), "space: ${SPACE}\nmore: yes\n");
        Files.writeString(tpl.resolve("config").resolve("new.toon"), "n: 1\n");

        Map<String, Object> r = SpaceOrigin.report(SpaceOrigin.read(base).orElseThrow(), base, tpl, "acme");
        assertEquals("recorded", r.get("status"));
        Map<?, ?> space = (Map<?, ?>) r.get("space");
        assertEquals(List.of("egress.toon", "notes.toon"), space.get("unchanged"));
        assertEquals(List.of("registry/datasets/d.toon"), space.get("changed"));
        Map<?, ?> since = (Map<?, ?>) r.get("templateSince");
        assertEquals("newer-version", since.get("status"));
        assertEquals("1.1", since.get("currentVersion"));
        assertEquals(List.of("new.toon"), since.get("added"));
        assertEquals(List.of("notes.toon"), since.get("changed"));
        assertFalse(since.containsKey("removed"), "the record cannot tell a dropped template file from a server-written one");

        assertEquals("missing", ((Map<?, ?>) SpaceOrigin.report(SpaceOrigin.read(base).orElseThrow(), base, null, "acme")
                .get("templateSince")).get("status"));
    }
}
