package com.gamma.control;

import com.gamma.service.BundleImporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SE-6b — {@link SpacePromotion#compute} without HTTP: connections and secrets, placeholders against secret
 * references, server stamps, and the files a promotion never reads.
 */
class SpacePromotionTest {

    private static final String CONN = "connections/db_connection.toon";

    private static BundleImporter.Bundle bundle(Map<String, String> files) {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        files.forEach((k, v) -> entries.put(k, v.getBytes(StandardCharsets.UTF_8)));
        return new BundleImporter.Bundle("space", Map.of("kind", "space"), entries, null);
    }

    private static void write(Path root, String rel, String text) throws Exception {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }

    private static String action(SpacePromotion.ChangeSet cs, String path) {
        return cs.changes().stream().filter(c -> c.path().equals(path)).map(SpacePromotion.Change::action)
                .findFirst().orElse(null);
    }

    @Test
    void aBareSecretReferenceIsNotAPlaceholderButAHostPlaceholderIsBound(@TempDir Path target) throws Exception {
        String src = "connection:\n  id: db\n  connector: postgres\n  host: \"${DB_HOST}\"\n  password: \"${DB_PASS}\"\n";
        SpacePromotion.ChangeSet cs = SpacePromotion.compute(target, bundle(Map.of(CONN, src)),
                Map.of("DB_HOST", "prod-db"), "prod");
        SpacePromotion.Change c = cs.changes().getFirst();
        assertEquals("add", c.action());
        String bound = new String(c.incoming(), StandardCharsets.UTF_8);
        assertTrue(bound.contains("prod-db") && bound.contains("${DB_PASS}"), bound);
        assertEquals(List.of("DB_HOST"), cs.parametersUsed());

        SpacePromotion.MissingParameters m = assertThrows(SpacePromotion.MissingParameters.class,
                () -> SpacePromotion.compute(target, bundle(Map.of(CONN, src)), Map.of(), "prod"));
        assertEquals(List.of("DB_HOST"), m.names, "the secret reference is never asked for");
    }

    @Test
    void aTargetConnectionWithALiteralSecretIsNeverChangedOrRemoved(@TempDir Path target) throws Exception {
        write(target, CONN, "connection:\n  id: db\n  connector: postgres\n  host: old\n  password: hunter2\n");
        write(target, "connections/other_connection.toon",
                "connection:\n  id: other\n  connector: postgres\n  host: h\n  password: literal\n");
        String src = "connection:\n  id: db\n  connector: postgres\n  host: new\n";
        SpacePromotion.ChangeSet cs = SpacePromotion.compute(target, bundle(Map.of(CONN, src)), Map.of(), "prod");
        assertTrue(cs.changes().isEmpty(), cs.changes().toString());
        assertEquals(List.of(CONN, "connections/other_connection.toon"),
                cs.excluded().stream().map(SpacePromotion.Excluded::path).toList());
    }

    @Test
    void aSecretOnlyByReferenceComparesInItsExportedForm(@TempDir Path target) throws Exception {
        write(target, CONN, "connection:\n  id: db\n  connector: postgres\n  host: h\n  password: \"${ENV:P}\"\n");
        String src = "connection:\n  id: db\n  connector: postgres\n  host: h\n  password: \"${ENV:P}\"\n";
        assertEquals("same", action(SpacePromotion.compute(target, bundle(Map.of(CONN, src)), Map.of(), "p"), CONN));
        String moved = src.replace("host: h", "host: h2");
        assertEquals("change", action(SpacePromotion.compute(target, bundle(Map.of(CONN, moved)), Map.of(), "p"), CONN));
    }

    @Test
    void serverStampsAndFormattingAreNotAChange(@TempDir Path target) throws Exception {
        write(target, "registry/datasets/d.toon", "physicalRef: d/database\nupdatedAt: \"2026-01-01\"\n");
        write(target, "jobs/r_job.toon", "job:\n  name: r\n  type: sql\n  createdBy: alice\n  updatedBy: bob\n");
        SpacePromotion.ChangeSet cs = SpacePromotion.compute(target, bundle(Map.of(
                "registry/datasets/d.toon", "physicalRef: \"d/database\"\n",
                "jobs/r_job.toon", "job:\n  name: r\n  type: sql\n  createdBy: carol\n")), Map.of(), "p");
        assertEquals("same", action(cs, "registry/datasets/d.toon"));
        assertEquals("same", action(cs, "jobs/r_job.toon"));
    }

    @Test
    void reservedAndDotFilesOfTheTargetAreNeverReadOrRemoved(@TempDir Path target) throws Exception {
        write(target, "roles.toon", "roles: {}\n");
        write(target, "pending-changes/pc-1.json", "{}");
        write(target, ".history/pipelines/x/v1.toon", "a: b\n");
        write(target, "audit/x.toon", "a: b\n");
        write(target, "ops/hot_tagrule.toon", "name: hot\n");
        SpacePromotion.ChangeSet cs = SpacePromotion.compute(target, bundle(Map.of(
                "roles.toon", "roles: {}\n", ".history/pipelines/x/v2.toon", "a: c\n")), Map.of(), "p");
        assertTrue(cs.changes().isEmpty(), cs.changes().toString());
        assertEquals(List.of(".history/pipelines/x/v2.toon", "roles.toon"),
                cs.excluded().stream().map(SpacePromotion.Excluded::path).toList());
    }

    @Test
    void aBundleConnectionWithALiteralSecretIsNeverWritten(@TempDir Path target) throws Exception {
        String src = "connection:\n  id: db\n  connector: postgres\n  host: h\n  password: hunter2\n";
        SpacePromotion.ChangeSet cs = SpacePromotion.compute(target, bundle(Map.of(CONN, src)), Map.of(), "p");
        assertTrue(cs.changes().isEmpty(), cs.changes().toString());
        assertEquals(List.of(CONN), cs.excluded().stream().map(SpacePromotion.Excluded::path).toList());
    }

    @Test
    void aValueThatBreaksAnArrayRowRefusesTheChangeSet(@TempDir Path target) throws Exception {
        String row = "physicalRef: d/database\ntags[2]: a,${TAG}\n";
        assertEquals("add", action(SpacePromotion.compute(target, bundle(Map.of("registry/datasets/d.toon", row)),
                Map.of("TAG", "b"), "p"), "registry/datasets/d.toon"));
        SpacePromotion.BrokenByBinding broken = assertThrows(SpacePromotion.BrokenByBinding.class,
                () -> SpacePromotion.compute(target, bundle(Map.of("registry/datasets/d.toon", row)),
                        Map.of("TAG", "b,c"), "p"));
        assertEquals(List.of("registry/datasets/d.toon"), broken.paths);
    }

    @Test
    void decisionRuleStampsAreNotAChange(@TempDir Path target) throws Exception {
        write(target, "registry/decision-rules/r.toon", "name: r\ncreatedBy: alice\nupdatedBy: bob\nmakers[1]: alice\n");
        SpacePromotion.ChangeSet cs = SpacePromotion.compute(target, bundle(Map.of(
                "registry/decision-rules/r.toon", "name: r\ncreatedBy: carol\n")), Map.of(), "p");
        assertEquals("same", action(cs, "registry/decision-rules/r.toon"));
    }

    @Test
    void theHashMovesWithEitherSide(@TempDir Path target) throws Exception {
        write(target, "registry/datasets/d.toon", "physicalRef: d/database\n");
        BundleImporter.Bundle b = bundle(Map.of("registry/datasets/d.toon", "physicalRef: d/database\n"));
        String h1 = SpacePromotion.compute(target, b, Map.of(), "p").hash();
        assertEquals(h1, SpacePromotion.compute(target, b, Map.of(), "p").hash(), "deterministic");
        write(target, "registry/datasets/d.toon", "physicalRef: e/database\n");
        assertNotEquals(h1, SpacePromotion.compute(target, b, Map.of(), "p").hash());
    }
}
