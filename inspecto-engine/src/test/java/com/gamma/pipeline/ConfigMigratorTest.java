package com.gamma.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
