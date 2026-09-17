package com.gamma.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The ELT §6 step-1 one-shot converter behind {@code inspecto migrate-configs}.
 *
 * <p>The properties that matter are the ones the amendment asks for by name: deterministic, dry-run-first,
 * and refusing rather than losing.
 */
class ConfigMigratorTest {

    @Test
    void aDryRunPlansEveryLegacyFileAndWritesNothing(@TempDir Path dir) throws Exception {
        Path config = legacySpace(dir);
        Path out = dir.resolve("registry");

        ConfigMigrator.Plan plan = ConfigMigrator.migrate(config, out, false);

        assertTrue(plan.ok(), "a clean space plans without refusals");
        assertFalse(plan.applied(), "a dry run is not an apply");
        assertEquals(2, plan.conversions().size(), "one pipeline + one schema");
        assertFalse(Files.exists(out), "a dry run writes NOTHING - not even the output root");
        assertTrue(Files.exists(config.resolve("orders_pipeline.toon")), "and leaves the originals in place");
    }

    @Test
    void anApplyWritesTheComponentsAndArchivesTheOriginals(@TempDir Path dir) throws Exception {
        Path config = legacySpace(dir);
        Path out = dir.resolve("registry");

        ConfigMigrator.Plan plan = ConfigMigrator.migrate(config, out, true);

        assertTrue(plan.applied() && plan.ok());
        assertTrue(Files.exists(out.resolve("pipelines/orders.toon")), "the recipe");
        assertTrue(Files.exists(out.resolve("schemas/orders.toon")), "the schema structure - TOON, not CSV");
        assertTrue(Files.exists(out.resolve("mappings/orders.csv")), "the Mapping component - CSV");
        assertFalse(Files.exists(config.resolve("orders_pipeline.toon")), "the original moved");
        assertTrue(Files.exists(config.resolve("archived-config/orders_pipeline.toon")),
                "originals go to archived-config/, untouched");
        assertFalse(Files.readString(out.resolve("schemas/orders.toon")).contains("rules"),
                "the mapping rules moved OUT of the schema: " + Files.readString(out.resolve("schemas/orders.toon")));
        assertTrue(Files.readString(out.resolve("mappings/orders.csv")).contains("ORDER_ID"),
                "and into the CSV");
    }

    /**
     * An enrichment config is OUT OF SCOPE, not a refusal (operator, 2026-09-16 — the amendment's clause is
     * struck: {@code enrich} is a shipped Job type and the 2026-08-06 reversal cancelled the file migration
     * too). It must not block the space around it.
     */
    @Test
    void anEnrichConfigIsPassedOverAndDoesNotBlockTheSpace(@TempDir Path dir) throws Exception {
        Path config = legacySpace(dir);
        Files.writeString(config.resolve("orders_daily_enrich.toon"), "name: ORDERS_DAILY\n");
        Path out = dir.resolve("registry");

        ConfigMigrator.Plan plan = ConfigMigrator.migrate(config, out, true);

        assertTrue(plan.ok(), "an enrich config is someone else's work, not a lossy case");
        assertTrue(plan.applied());
        assertEquals(2, plan.conversions().size(), "the pipeline and the schema still convert");
        assertTrue(Files.exists(out.resolve("pipelines/orders.toon")));
        assertTrue(Files.exists(config.resolve("orders_daily_enrich.toon")),
                "and the enrich config is left exactly where the Job expects it - NOT archived");
    }

    /**
     * ⛔ A refusal still fails the WHOLE migration before anything is written. A half-migrated space - some
     * pipelines on recipes, some still legacy - is the one outcome worse than an unmigrated one.
     */
    @Test
    void aRefusalStopsTheWholeMigrationBeforeAnythingIsWritten(@TempDir Path dir) throws Exception {
        Path config = legacySpace(dir);
        Files.writeString(config.resolve("schemaless_pipeline.toon"), """
                name: SCHEMALESS
                active: true
                dirs:
                  poll: /tmp/in
                  database: /tmp/db
                output:
                  format: CSV
                processing:
                  threads: 1
                """);
        Path out = dir.resolve("registry");

        ConfigMigrator.Plan plan = ConfigMigrator.migrate(config, out, true);

        assertFalse(plan.ok());
        assertFalse(Files.exists(out), "the pipeline that COULD convert was not written either");
        assertTrue(Files.exists(config.resolve("orders_pipeline.toon")), "nor was any original moved");
    }

    @Test
    void aTargetThatAlreadyExistsIsRefusedRatherThanOverwritten(@TempDir Path dir) throws Exception {
        Path config = legacySpace(dir);
        Path out = dir.resolve("registry");
        Files.createDirectories(out.resolve("pipelines"));
        Files.writeString(out.resolve("pipelines/orders.toon"), "name: SOMEONE_ELSE\n");

        ConfigMigrator.Plan plan = ConfigMigrator.migrate(config, out, true);

        assertFalse(plan.ok());
        assertEquals("name: SOMEONE_ELSE\n", Files.readString(out.resolve("pipelines/orders.toon")),
                "the existing component is untouched");
    }

    /** Deterministic: the walk is sorted, so two runs over one tree plan identical output. */
    @Test
    void theWalkIsSortedSoTwoRunsPlanTheSame(@TempDir Path dir) throws Exception {
        Path config = legacySpace(dir);
        Files.writeString(config.resolve("alpha_pipeline.toon"), pipelineToon("ALPHA"));
        Files.writeString(config.resolve("zulu_pipeline.toon"), pipelineToon("ZULU"));

        List<Path> first = ConfigMigrator.legacyFiles(config);
        List<Path> second = ConfigMigrator.legacyFiles(config);

        assertEquals(first, second);
        assertEquals(first.stream().sorted(java.util.Comparator.comparing(Path::toString)).toList(), first,
                "sorted by path");
    }

    /**
     * The point of the conversion is output the RECIPE PATH can read - writing files is not the contract.
     * Driven end-to-end on a copy of the repo's own demo space while this was built: 9 conversions applied,
     * all 5 recipes compiled, 0 failures.
     */
    @Test
    void theConvertedRecipeCompilesThroughTheRecipePath(@TempDir Path dir) throws Exception {
        Path config = legacySpace(dir);
        Path out = dir.resolve("registry");

        ConfigMigrator.migrate(config, out, true);

        var recipe = com.gamma.config.io.ConfigCodec.toMap(
                Files.readString(out.resolve("pipelines/orders.toon")));
        assertDoesNotThrow(() -> RecipeCompiler.compile(recipe),
                "a recipe the compiler refuses is a migration that silently bricked the pipeline");
    }

    /**
     * 🔴 The case that made the compile check part of planning: a legacy pipeline whose parse step names no
     * Grammar / {@code schema_file} / {@code segments} projects to a recipe the compiler REFUSES. Converting
     * it would archive a working legacy config and leave an unrunnable recipe behind.
     */
    @Test
    void aPipelineWhoseRecipeWouldNotCompileIsRefused(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config");
        Files.createDirectories(config);
        Files.writeString(config.resolve("schemaless_pipeline.toon"), """
                name: SCHEMALESS
                active: true
                dirs:
                  poll: /tmp/in
                  database: /tmp/db
                output:
                  format: CSV
                processing:
                  threads: 1
                """);

        ConfigMigrator.Plan plan = ConfigMigrator.migrate(config, dir.resolve("registry"), true);

        assertFalse(plan.ok(), "a recipe that cannot compile is a lossy conversion");
        assertTrue(plan.refusals().get(0).reason().contains("would not compile"),
                plan.refusals().get(0).reason());
        assertTrue(plan.refusals().get(0).reason().contains("PARSER_NO_SCHEMA"),
                "and carries the compiler's own reason: " + plan.refusals().get(0).reason());
    }

    /**
     * 🔴 <b>CONFIG-MIGRATOR-LOSES-MAPPINGS-1 (a) — the mapping block must survive the split, and this is
     * pinned against the COMMITTED corpus, not a fixture.</b>
     *
     * <p>The bug: {@code writeSchema} removed {@code mapping} unconditionally and wrote the Mapping CSV only
     * for a non-empty {@code mapping.rules[]}. Every committed schema is on the CURRENT spelling
     * {@code mapping.fields[]} ({@code MappingMigrator} migrated the corpus off {@code rules[]}), so on a live
     * drive the whole mapping was DELETED — exit 0, no refusal, original already archived.
     *
     * <p>⚠ The reason this shipped is that {@link #legacySpace} is the ONLY corpus in the repo still using
     * {@code rules[]}: {@code anApplyWritesTheComponentsAndArchivesTheOriginals} agreed with its own fixture
     * and not with any space. So this test reads {@code ../spaces/**&#47;*_schema.toon} off disk and asserts,
     * for every one of them, that the mapping block that went in comes back out — either in the written
     * schema, or (for a legacy {@code rules[]} block) in the Mapping CSV.
     */
    @Test
    void everyCommittedSchemaKeepsItsMappingBlock(@TempDir Path dir) throws Exception {
        List<Path> corpus = committedSchemas();
        assertFalse(corpus.isEmpty(), "the committed corpus must be readable from " + spacesRoot()
                + " - a probe that cannot find its subject reports 'no failures' and proves nothing");

        List<String> losses = new ArrayList<>();
        for (int i = 0; i < corpus.size(); i++) {
            Path src = corpus.get(i);
            Map<String, Object> before = mappingOf(com.gamma.config.io.ConfigCodec.toMap(
                    Files.readString(src, java.nio.charset.StandardCharsets.UTF_8)));
            if (before == null) continue;                       // no mapping block: nothing to lose

            // One tree per schema, keyed by index: two spaces DO ship the same schema name
            // (demo and _templates/orders-starter both have orders_schema.toon), and sharing a
            // registry root makes the second a "target already exists" refusal.
            Path config = dir.resolve("case-" + i).resolve("config");
            Files.createDirectories(config);
            Path copy = config.resolve(src.getFileName());
            Files.copy(src, copy);
            Path out = config.getParent().resolve("registry");

            ConfigMigrator.Plan plan = ConfigMigrator.migrate(config, out, true);
            if (!plan.ok()) { losses.add(src + ": REFUSED " + plan.refusals()); continue; }

            String name = src.getFileName().toString().replace("_schema.toon", "");
            Map<String, Object> after = mappingOf(com.gamma.config.io.ConfigCodec.toMap(
                    Files.readString(out.resolve("schemas/" + name + ".toon"),
                            java.nio.charset.StandardCharsets.UTF_8)));
            Map<String, Object> recovered = after == null ? new java.util.LinkedHashMap<>()
                    : new java.util.LinkedHashMap<>(after);
            Path csv = out.resolve("mappings/" + name + ".csv");
            if (Files.exists(csv))
                recovered.put("rules", com.gamma.util.MappingCsv.parse(
                        Files.readString(csv, java.nio.charset.StandardCharsets.UTF_8), csv.toString()));

            for (String key : before.keySet())
                if (!recovered.containsKey(key))
                    losses.add(src + ": mapping." + key + " was LOST (kept: " + recovered.keySet() + ")");
            if (before.get("fields") instanceof List<?> f && !f.equals(recovered.get("fields")))
                losses.add(src + ": mapping.fields[] changed across the split");
        }
        assertTrue(losses.isEmpty(), "the migration lost mapping content from committed schemas:\n"
                + String.join("\n", losses));
    }

    /**
     * The sharp, readable case behind the sweep above: {@code spaces/demo orders_schema.toon} carries 8
     * {@code mapping.fields[]} rows, two of them {@code custom} SQL expressions. Before the fix the written
     * schema had no {@code mapping} at all and {@code registry/mappings/} was never created.
     */
    @Test
    void theDemoOrdersSchemaKeepsAllEightFieldMappingsIncludingTheSqlExpressions(@TempDir Path dir)
            throws Exception {
        Path src = spacesRoot().resolve("demo/config/orders/orders_schema.toon");
        assumeTrue(Files.isRegularFile(src), "committed fixture must exist: " + src);

        Path config = dir.resolve("config");
        Files.createDirectories(config);
        Files.copy(src, config.resolve("orders_schema.toon"));

        Path out = dir.resolve("registry");
        assertTrue(ConfigMigrator.migrate(config, out, true).ok());

        String written = Files.readString(out.resolve("schemas/orders.toon"),
                java.nio.charset.StandardCharsets.UTF_8);
        Map<String, Object> mapping = mappingOf(com.gamma.config.io.ConfigCodec.toMap(written));
        assertNotNull(mapping, "the mapping block must survive: " + written);
        assertEquals(8, ((List<?>) mapping.get("fields")).size(), "all 8 field mappings: " + written);
        assertTrue(written.contains("UPPER(TRIM(REGION))"), "the REGION expression survives: " + written);
        assertTrue(written.contains("TRY_CAST(QUANTITY AS DOUBLE)"), "the GROSS expression survives: " + written);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mappingOf(Map<String, Object> schema) {
        return schema.get("mapping") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    /** The repo's committed spaces, as {@code LiftLowerFixtureSweepTest} reaches them. */
    private static Path spacesRoot() {
        return Path.of("..", "spaces").toAbsolutePath().normalize();
    }

    private static List<Path> committedSchemas() throws Exception {
        Path root = spacesRoot();
        if (!Files.isDirectory(root)) return List.of();
        try (var all = Files.walk(root)) {
            return all.filter(p -> p.getFileName().toString().endsWith("_schema.toon")).sorted().toList();
        }
    }

    // ── fixture ───────────────────────────────────────────────────────────────

    private static Path legacySpace(Path dir) throws Exception {
        Path config = dir.resolve("config");
        Files.createDirectories(config);
        Files.writeString(config.resolve("orders_pipeline.toon"), pipelineToon("ORDERS"));
        Files.writeString(config.resolve("orders_schema.toon"), """
                partitions[1]{column,source,type}:
                  day,ORDER_DATE,DATE_DAY
                raw:
                  name: orders
                  format: CSV
                  fields[2]{name,selector,type}:
                    ORDER_ID,"0",VARCHAR
                    ORDER_DATE,"1",DATE
                mapping:
                  canonicalName: orders
                  rawName: orders
                  rules[2]{targetColumn,sourceExpression,transformType}:
                    ORDER_ID,ORDER_ID,DIRECT
                    ORDER_DATE,ORDER_DATE,DIRECT
                """);
        return config;
    }

    private static String pipelineToon(String name) {
        return """
                name: %s
                active: true
                dirs:
                  poll: /tmp/in
                  database: /tmp/db
                output:
                  format: CSV
                processing:
                  threads: 1
                  schema_file: schemas/orders
                """.formatted(name);
    }
}
