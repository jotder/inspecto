package com.gamma.pipeline;

import com.gamma.config.io.ConfigCodec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link RecipeConverter} — the Phase-2 parity gate (§6 step 2): for every authored
 * {@code *_pipeline.toon} fixture in the repo's {@code spaces/} tree,
 * {@code compile(toRecipe(cfg), cfg, lenient)} must reproduce the original decoded map exactly
 * (modulo the always-written {@code active} default). Lenient compile is the converter's contract —
 * sections whose owning verb the recipe cannot speak yet (markers, gap watch) must survive untouched.
 */
class RecipeConverterTest {

    /** Repo spaces/ tree, resolved from the module dir; absent in a bare-module checkout ⇒ skip. */
    private static Path spacesRoot() {
        return Path.of("..", "spaces").toAbsolutePath().normalize();
    }

    private static List<Path> pipelineFixtures() throws IOException {
        Path root = spacesRoot();
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> all = Files.walk(root)) {
            return all.filter(p -> p.getFileName().toString().endsWith("_pipeline.toon"))
                    .sorted().toList();
        }
    }

    @Test
    void everyRepoFixtureRoundTripsThroughTheRecipeProjection() throws Exception {
        List<Path> fixtures = pipelineFixtures();
        assumeTrue(!fixtures.isEmpty(), "no spaces/ tree next to the module — nothing to gate");

        List<String> failures = new ArrayList<>();
        for (Path f : fixtures) {
            Map<String, Object> original = ConfigCodec.toMap(Files.readString(f, StandardCharsets.UTF_8));
            try {
                Map<String, Object> recipe = RecipeConverter.toRecipe(original);
                Map<String, Object> back = RecipeCompiler.compile(recipe, original, false);

                Map<String, Object> expected = new LinkedHashMap<>(original);
                expected.putIfAbsent("active", back.get("active"));   // lower always writes the default
                if (!expected.equals(back))
                    failures.add(f + ":\n  expected " + expected + "\n  got      " + back);
            } catch (RuntimeException e) {
                failures.add(f + ": " + e.getMessage());
            }
        }
        assertTrue(failures.isEmpty(),
                failures.size() + " of " + fixtures.size() + " fixtures failed the round trip:\n"
                        + String.join("\n", failures));
    }

    /** The new sections (route/dedup/summarize/join lowering) round-trip like every fixture: convert → compile-over-original. */
    @Test
    void aConfigWithDedupRouteSummarizeAndJoinRoundTrips() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("name", "orders");
        cfg.put("active", false);   // route: is authoring-only — an armed config could never exist on disk
        cfg.put("collector", new LinkedHashMap<>(Map.of(
                "duplicate", new LinkedHashMap<>(Map.of("mode", "checksum", "algorithm", "SHA256")),
                "gap_detection", new LinkedHashMap<>(Map.of("enabled", true, "sequence", "ORD_{yyyyMMdd}")))));
        cfg.put("dirs", new LinkedHashMap<>(Map.of(
                "poll", "/in", "database", "/data/emea",
                "markers", "/data/markers", "quarantine", "/data/quarantine")));
        cfg.put("parsing", new LinkedHashMap<>(Map.of("grammar", "grammar/pipe")));
        cfg.put("processing", new LinkedHashMap<>(Map.of(
                "file_pattern", "glob:**/*.csv",
                "retention_days", 14,
                "duplicate_check", new LinkedHashMap<>(Map.of(
                        "enabled", true, "marker_extension", ".done")),
                "dedup", new LinkedHashMap<>(Map.of(
                        "keys", List.of("ORDER_ID"), "order_by", "EVENT_TS DESC")),
                "join", new LinkedHashMap<>(Map.of(
                        "reference", "reference/region_dim", "on", List.of("REGION"))),
                "summarize", new LinkedHashMap<>(Map.of(
                        "group_by", List.of("REGION"), "measures", List.of("count", "sum(AMT)"))))));
        cfg.put("output", new LinkedHashMap<>(Map.of("format", "PARQUET")));
        cfg.put("route", new LinkedHashMap<>(Map.of(
                "mode", "case",
                "branches", List.of(
                        new LinkedHashMap<>(Map.of("key", "emea",
                                "where", "region IN ('DE','FR')", "database", "/data/emea")),
                        new LinkedHashMap<>(Map.of("key", "other", "database", "/data/other"))),
                "default", "other")));
        cfg.put("sinks", List.of(
                new LinkedHashMap<>(Map.of("database", "/data/emea", "format", "PARQUET")),
                new LinkedHashMap<>(Map.of("database", "/data/other", "format", "PARQUET"))));

        Map<String, Object> recipe = RecipeConverter.toRecipe(cfg);
        Map<String, Object> back = RecipeCompiler.compile(recipe, cfg, false);
        assertEquals(cfg, back);
    }

    /** The Phase-4 Guarantees fold (§2.4): housekeeping projects under its declared names, never on steps. */
    @Test
    @SuppressWarnings("unchecked")
    void projectsHousekeepingAsGuarantees() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("name", "orders");
        cfg.put("collector", new LinkedHashMap<>(Map.of(
                "duplicate", new LinkedHashMap<>(Map.of("mode", "checksum")),
                "gap_detection", new LinkedHashMap<>(Map.of("enabled", true, "sequence", "F_{yyyyMMdd}")))));
        cfg.put("dirs", new LinkedHashMap<>(Map.of(
                "poll", "/in", "database", "/db",
                "markers", "/db/markers", "quarantine", "/db/quarantine")));
        cfg.put("parsing", new LinkedHashMap<>(Map.of("grammar", "grammar/pipe")));
        cfg.put("processing", new LinkedHashMap<>(Map.of(
                "retention_days", 7,
                "duplicate_check", new LinkedHashMap<>(Map.of("enabled", true)))));
        cfg.put("output", new LinkedHashMap<>(Map.of("format", "PARQUET")));

        Map<String, Object> recipe = RecipeConverter.toRecipe(cfg);
        Map<String, Object> g = (Map<String, Object>) recipe.get("guarantees");
        assertEquals(Map.of("mode", "checksum"), g.get("file_dedup"));
        assertEquals(Map.of("enabled", true, "sequence", "F_{yyyyMMdd}"), g.get("gap_watch"));
        assertEquals(Map.of("dir", "/db/markers"), g.get("markers"));
        assertEquals("/db/quarantine", g.get("quarantine"));
        assertEquals(7, g.get("retention"));

        List<Map<String, Object>> steps = (List<Map<String, Object>>) recipe.get("steps");
        Map<String, Object> collect = (Map<String, Object>) steps.get(0).get("collect");
        assertFalse(collect.containsKey("duplicate"), "file dedup is a Guarantee, not a collect key");
    }

    @Test
    @SuppressWarnings("unchecked")
    void projectsTheLinearVocabulary() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("name", "orders");
        cfg.put("trigger", Map.of("poll", "60s"));
        cfg.put("collector", new LinkedHashMap<>(Map.of("connection", "sftp_prod")));
        cfg.put("dirs", new LinkedHashMap<>(Map.of("poll", "/in", "database", "/db")));
        cfg.put("parsing", new LinkedHashMap<>(Map.of("grammar", "grammar/pipe")));
        cfg.put("processing", new LinkedHashMap<>(Map.of(
                "file_pattern", "glob:**/*.csv",
                "schema_file", "schema/orders_v1",
                "mapping_file", "mapping/orders_std",
                "csv_settings", new LinkedHashMap<>(Map.of("where", "AMT > 0")))));
        cfg.put("output", new LinkedHashMap<>(Map.of("format", "PARQUET")));

        Map<String, Object> recipe = RecipeConverter.toRecipe(cfg);
        List<Map<String, Object>> steps = (List<Map<String, Object>>) recipe.get("steps");
        List<String> verbs = steps.stream().map(s -> s.keySet().iterator().next()).toList();
        assertEquals(List.of("collect", "parse", "map", "transform", "sink"), verbs);

        Map<String, Object> collect = (Map<String, Object>) steps.get(0).get("collect");
        assertEquals("connections/sftp_prod", collect.get("connection"), "recipe speaks the plural spelling");
        assertEquals("glob:**/*.csv", collect.get("files"));

        assertEquals("grammars/pipe", ((Map<String, Object>) steps.get(1).get("parse")).get("grammar"));
        Map<String, Object> map = (Map<String, Object>) steps.get(2).get("map");
        assertEquals("schemas/orders_v1", map.get("schema"));
        assertEquals("mappings/orders_std", map.get("mapping"));
        assertEquals("AMT > 0", ((Map<String, Object>) steps.get(3).get("transform")).get("filter"),
                "the row predicate projects as its own transform step");
        assertEquals("PARQUET", ((Map<String, Object>) steps.get(4).get("sink")).get("format"));
    }
}
