package com.gamma.pipeline;

import com.gamma.config.io.ConfigCodec;
import com.gamma.etl.PipelineConfig;
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
 * {@link RecipeCompiler} (ELT amendment Phase 2 S3): the linear recipe compiles through
 * {@link PipelineEditable#lower} onto the canonical {@code *_pipeline.toon} shape — existing gates
 * apply, nothing is silently dropped, and the compiled config is genuinely executable
 * ({@code PipelineConfig.load} accepts it, the "onto existing primitives" proof).
 */
class RecipeCompilerTest {

    private static Map<String, Object> step(String verb, Map<String, Object> cfg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(verb, cfg);
        return m;
    }

    private static Map<String, Object> linearRecipe(String database) {
        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("name", "orders");
        recipe.put("trigger", Map.of("poll", "60s"));
        recipe.put("steps", new java.util.ArrayList<>(List.of(
                step("collect", new LinkedHashMap<>(Map.of(
                        "connection", "connections/sftp_prod", "files", "glob:**/*.csv"))),
                step("parse", new LinkedHashMap<>(Map.of("grammar", "grammars/delimited_pipe"))),
                step("map", new LinkedHashMap<>(Map.of(
                        "schema", "schemas/orders_v1", "mapping", "mappings/orders_std"))),
                step("transform", new LinkedHashMap<>(Map.of("filter", "AMT > 0"))),
                step("sink", new LinkedHashMap<>(Map.of(
                        "table", "orders", "format", "PARQUET", "database", database))))));
        return recipe;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aLinearRecipeCompilesToTheCanonicalShape() {
        Map<String, Object> out = RecipeCompiler.compile(linearRecipe("/data/db"));

        assertEquals("orders", out.get("name"));
        assertEquals(Boolean.TRUE, out.get("active"), "active defaults to true");
        assertEquals(Map.of("poll", "60s"), out.get("trigger"), "the entry Step carries the schedule");

        Map<String, Object> collector = (Map<String, Object>) out.get("collector");
        assertEquals("sftp_prod", collector.get("connection"),
                "connections/sftp_prod normalises to the connection binding");

        Map<String, Object> processing = (Map<String, Object>) out.get("processing");
        assertEquals("glob:**/*.csv", processing.get("file_pattern"), "files: is the file pattern");
        assertEquals("schema/orders_v1", processing.get("schema_file"),
                "map.schema compiles to the id-addressed registry ref (slice-3 wiring)");
        assertEquals("mapping/orders_std", processing.get("mapping_file"));
        assertEquals("AMT > 0", ((Map<String, Object>) processing.get("csv_settings")).get("where"),
                "transform.filter lands as the post-parse row predicate");

        assertEquals("grammar/delimited_pipe", ((Map<String, Object>) out.get("parsing")).get("grammar"));
        assertEquals("PARQUET", ((Map<String, Object>) out.get("output")).get("format"));
        assertEquals("/data/db", ((Map<String, Object>) out.get("dirs")).get("database"));
    }

    /** The "onto existing primitives" proof: the compiled config round-trips through the real loader. */
    @Test
    void theCompiledConfigIsExecutableByPipelineConfigLoad(@TempDir Path dir) throws Exception {
        String d = dir.toString().replace('\\', '/');
        Path schemas = Files.createDirectories(dir.resolve("registry/schemas"));
        Files.writeString(schemas.resolve("orders_v1.toon"), """
                partitionKey: EVENT_DATE
                raw:
                  name: orders
                  format: CSV
                  fields[2]{name,selector,type}:
                    ACCOUNT_NUMBER,0,VARCHAR
                    EVENT_DATE,1,DATE
                mapping:
                  canonicalName: orders
                  rawName: orders
                  rules[2]{targetColumn,sourceExpression,transformType}:
                    ACCOUNT_NUMBER,ACCOUNT_NUMBER,DIRECT
                    EVENT_DATE,EVENT_DATE,DIRECT
                """, StandardCharsets.UTF_8);

        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("name", "orders");
        recipe.put("steps", List.of(
                step("collect", new LinkedHashMap<>(Map.of("poll", d + "/inbox", "files", "glob:**/*.csv"))),
                step("parse", new LinkedHashMap<>()),
                step("map", new LinkedHashMap<>(Map.of("schema", "schemas/orders_v1"))),
                step("sink", new LinkedHashMap<>(Map.of(
                        "format", "PARQUET", "database", d + "/db",
                        "backup", d + "/backup", "temp", d + "/temp")))));
        // inactive draft: parse carries no grammar/parsing keys — lenient compile, like a new draft save
        recipe.put("active", false);

        Map<String, Object> compiled = RecipeCompiler.compile(recipe);
        compiled.put("version", 1);
        Map<String, Object> dirs = getOrNew(compiled, "dirs");
        dirs.putIfAbsent("errors", d + "/errors");
        dirs.putIfAbsent("quarantine", d + "/quarantine");
        dirs.putIfAbsent("status_dir", d + "/status");

        Path toon = dir.resolve("orders_pipeline.toon");
        Files.writeString(toon, ConfigCodec.toToon(compiled), StandardCharsets.UTF_8);

        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        assertEquals("orders", cfg.schemas().single().get("raw") instanceof Map<?, ?> raw
                ? raw.get("name") : null, "the schema/<id> registry ref resolved and loaded");
    }

    @Test
    void notYetCompilableVerbsRefuseWithNamedCodesNeverSilently() {
        Map<String, Object> recipe = linearRecipe("/data/db");
        ((List<Map<String, Object>>) (List<?>) recipe.get("steps")).add(
                step("transform", new LinkedHashMap<>(Map.of("derive", Map.of("total", "a + b")))));
        recipe.put("guarantees", Map.of("self_heal", true));   // not a §2.4 guarantee — must refuse

        PipelineCompileException e = assertThrows(PipelineCompileException.class,
                () -> RecipeCompiler.compile(recipe));
        List<String> codes = e.refusals().stream().map(PipelineCompileException.Refusal::code).toList();
        assertTrue(codes.contains(RecipeCompiler.UNSUPPORTED_STEP), codes.toString());
        assertTrue(codes.contains(RecipeCompiler.GUARANTEES_NOT_LOWERABLE), codes.toString());
    }

    /** The Phase-4 Guarantees fold (§2.4): every declared guarantee lands on its live flat home. */
    @Test
    @SuppressWarnings("unchecked")
    void guaranteesCompileOntoTheirHousekeepingHomes() {
        Map<String, Object> recipe = linearRecipe("/data/db");
        Map<String, Object> guarantees = new LinkedHashMap<>();
        guarantees.put("file_dedup", "fingerprint");
        guarantees.put("gap_watch", new LinkedHashMap<>(Map.of(
                "enabled", true, "sequence", "CDR_{yyyyMMddHH}")));
        guarantees.put("markers", new LinkedHashMap<>(Map.of(
                "dir", "/data/markers", "marker_extension", ".done")));
        guarantees.put("quarantine", "/data/quarantine");
        guarantees.put("retention", 30);
        recipe.put("guarantees", guarantees);

        Map<String, Object> out = RecipeCompiler.compile(recipe);
        Map<String, Object> collector = (Map<String, Object>) out.get("collector");
        assertEquals(Map.of("mode", "checksum"), collector.get("duplicate"),
                "file_dedup: fingerprint is the recipe spelling of duplicate mode checksum");
        assertEquals(Map.of("enabled", true, "sequence", "CDR_{yyyyMMddHH}"),
                collector.get("gap_detection"));
        Map<String, Object> processing = (Map<String, Object>) out.get("processing");
        assertEquals(Map.of("enabled", true, "marker_extension", ".done"),
                processing.get("duplicate_check"));
        assertEquals(30, processing.get("retention_days"));
        Map<String, Object> dirs = (Map<String, Object>) out.get("dirs");
        assertEquals("/data/markers", dirs.get("markers"));
        assertEquals("/data/quarantine", dirs.get("quarantine"));
    }

    @Test
    void guaranteeBackupRefusesTowardTheSinkStep() {
        Map<String, Object> recipe = linearRecipe("/data/db");
        recipe.put("guarantees", Map.of("backup", "/data/backup"));
        PipelineCompileException e = assertThrows(PipelineCompileException.class,
                () -> RecipeCompiler.compile(recipe));
        assertTrue(e.refusals().stream().anyMatch(r ->
                RecipeCompiler.GUARANTEES_NOT_LOWERABLE.equals(r.code())
                        && r.message().contains("sink step")), e.getMessage());
    }

    @Test
    void fileDedupMarkerRefusesTowardTheMarkersGuarantee() {
        Map<String, Object> recipe = linearRecipe("/data/db");
        recipe.put("guarantees", Map.of("file_dedup", "marker"));
        PipelineCompileException e = assertThrows(PipelineCompileException.class,
                () -> RecipeCompiler.compile(recipe));
        assertTrue(e.refusals().stream().anyMatch(r -> r.message().contains("markers:")),
                e.getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void dedupCompilesToProcessingDedup() {
        Map<String, Object> recipe = linearRecipe("/data/db");
        List<Map<String, Object>> steps = (List<Map<String, Object>>) (List<?>) recipe.get("steps");
        steps.add(steps.size() - 1,   // between transform and sink
                step("dedup", new LinkedHashMap<>(Map.of(
                        "key", List.of("ORDER_ID"), "keep", "first", "order_by", "EVENT_TS DESC"))));

        Map<String, Object> out = RecipeCompiler.compile(recipe);
        Map<String, Object> dd = (Map<String, Object>)
                ((Map<String, Object>) out.get("processing")).get("dedup");
        assertEquals(List.of("ORDER_ID"), dd.get("keys"));
        assertEquals("EVENT_TS DESC", dd.get("order_by"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void summarizeCompilesToProcessingSummarize() {
        Map<String, Object> recipe = linearRecipe("/data/db");
        List<Map<String, Object>> steps = (List<Map<String, Object>>) (List<?>) recipe.get("steps");
        steps.add(steps.size() - 1,   // between transform and sink
                step("summarize", new LinkedHashMap<>(Map.of(
                        "group_by", List.of("REGION"), "measures", List.of("count", "sum(AMT)")))));

        Map<String, Object> out = RecipeCompiler.compile(recipe);
        Map<String, Object> sm = (Map<String, Object>)
                ((Map<String, Object>) out.get("processing")).get("summarize");
        assertEquals(List.of("REGION"), sm.get("group_by"));
        assertEquals(List.of("count", "sum(AMT)"), sm.get("measures"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void transformJoinCompilesToProcessingJoin() {
        Map<String, Object> recipe = linearRecipe("/data/db");
        List<Map<String, Object>> steps = (List<Map<String, Object>>) (List<?>) recipe.get("steps");
        steps.add(steps.size() - 1,   // between transform and sink
                step("transform", new LinkedHashMap<>(Map.of(
                        "join", "references/region_dim", "on", "ACCOUNT_NUMBER"))));

        Map<String, Object> out = RecipeCompiler.compile(recipe);
        Map<String, Object> jn = (Map<String, Object>)
                ((Map<String, Object>) out.get("processing")).get("join");
        assertEquals("reference/region_dim", jn.get("reference"),
                "the recipe's plural references/ spelling normalises to the registry's singular");
        assertEquals(List.of("ACCOUNT_NUMBER"), jn.get("on"), "on: k is the single-key shorthand");
    }

    @Test
    void transformJoinWithoutOnRefuses() {
        Map<String, Object> recipe = linearRecipe("/data/db");
        ((List<Map<String, Object>>) (List<?>) recipe.get("steps")).add(
                step("transform", new LinkedHashMap<>(Map.of("join", "references/region_dim"))));
        PipelineCompileException e = assertThrows(PipelineCompileException.class,
                () -> RecipeCompiler.compile(recipe));
        assertTrue(e.refusals().stream().anyMatch(r -> r.message().contains("on:")), e.getMessage());
    }

    @Test
    void summarizeWithoutMeasuresRefuses() {
        Map<String, Object> recipe = linearRecipe("/data/db");
        ((List<Map<String, Object>>) (List<?>) recipe.get("steps")).add(
                step("summarize", new LinkedHashMap<>(Map.of("group_by", List.of("REGION")))));
        PipelineCompileException e = assertThrows(PipelineCompileException.class,
                () -> RecipeCompiler.compile(recipe));
        assertTrue(e.refusals().stream().anyMatch(r -> r.message().contains("measures")), e.getMessage());
    }

    @Test
    void dedupKeepOtherThanFirstRefuses() {
        Map<String, Object> recipe = linearRecipe("/data/db");
        ((List<Map<String, Object>>) (List<?>) recipe.get("steps")).add(
                step("dedup", new LinkedHashMap<>(Map.of("key", List.of("ID"), "keep", "last"))));
        PipelineCompileException e = assertThrows(PipelineCompileException.class,
                () -> RecipeCompiler.compile(recipe));
        assertTrue(e.refusals().stream().anyMatch(r -> r.message().contains("order_by")), e.getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void routeCompilesToARouteSectionWithBranchStampedDestinations() {
        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("name", "orders");
        recipe.put("steps", List.of(
                step("collect", new LinkedHashMap<>(Map.of("files", "glob:**/*.csv"))),
                step("parse", new LinkedHashMap<>(Map.of("grammar", "grammars/pipe"))),
                step("route", new LinkedHashMap<>(Map.of(
                        "mode", "case",
                        "branches", new LinkedHashMap<>(Map.of(
                                "emea", new LinkedHashMap<>(Map.of(
                                        "when", "region IN ('DE','FR')",
                                        "steps", List.of(step("sink", new LinkedHashMap<>(Map.of(
                                                "database", "/data/emea", "format", "PARQUET")))))),
                                "other", new LinkedHashMap<>(Map.of(
                                        "default", true,
                                        "steps", List.of(step("sink", new LinkedHashMap<>(Map.of(
                                                "database", "/data/other", "format", "PARQUET")))))))))))));

        Map<String, Object> out = RecipeCompiler.compile(recipe);
        Map<String, Object> route = (Map<String, Object>) out.get("route");
        assertEquals("case", route.get("mode"));
        List<Map<String, Object>> branches = (List<Map<String, Object>>) route.get("branches");
        assertEquals(2, branches.size());
        Map<String, Object> emea = branches.stream()
                .filter(b -> "emea".equals(b.get("key"))).findFirst().orElseThrow();
        assertEquals("region IN ('DE','FR')", emea.get("where"));
        assertEquals("/data/emea", emea.get("database"),
                "the branch↔sink pairing survives into the flat file as the stamped database");
        assertEquals("other", route.get("default"), "per-branch default: true → the top-level default key");
        assertNotNull(out.get("sinks"), "two destinations ⇒ the plural sinks: block");
    }

    @Test
    void aRouteBranchWithMoreThanASinkRefuses() {
        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("name", "orders");
        recipe.put("active", false);
        recipe.put("steps", List.of(
                step("collect", new LinkedHashMap<>()),
                step("parse", new LinkedHashMap<>()),
                step("route", new LinkedHashMap<>(Map.of("branches", new LinkedHashMap<>(Map.of(
                        "emea", new LinkedHashMap<>(Map.of("steps", List.of(
                                step("transform", new LinkedHashMap<>(Map.of("filter", "x > 0"))),
                                step("sink", new LinkedHashMap<>())))))))))));
        PipelineCompileException e = assertThrows(PipelineCompileException.class,
                () -> RecipeCompiler.compile(recipe));
        assertTrue(e.refusals().stream().anyMatch(r ->
                RecipeCompiler.UNSUPPORTED_STEP.equals(r.code()) && r.nodeId().contains("emea")),
                e.getMessage());
    }

    @Test
    void stepsAfterRouteRefuse() {
        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("name", "orders");
        recipe.put("active", false);
        recipe.put("steps", List.of(
                step("collect", new LinkedHashMap<>()),
                step("route", new LinkedHashMap<>(Map.of("branches", new LinkedHashMap<>(Map.of(
                        "a", new LinkedHashMap<>(Map.of("steps", List.of(step("sink", new LinkedHashMap<>()))))))))),
                step("sink", new LinkedHashMap<>())));
        PipelineCompileException e = assertThrows(PipelineCompileException.class,
                () -> RecipeCompiler.compile(recipe));
        assertTrue(e.refusals().stream().anyMatch(r -> r.message().contains("route ends the trunk")),
                e.getMessage());
    }

    @Test
    void mapWithoutParseRefuses() {
        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("name", "orders");
        recipe.put("active", false);
        recipe.put("steps", List.of(
                step("collect", new LinkedHashMap<>()),
                step("map", new LinkedHashMap<>(Map.of("schema", "schemas/orders_v1")))));
        PipelineCompileException e = assertThrows(PipelineCompileException.class,
                () -> RecipeCompiler.compile(recipe));
        assertTrue(e.refusals().stream().anyMatch(r -> RecipeCompiler.MAP_WITHOUT_PARSE.equals(r.code())));
    }

    @Test
    void anActiveRecipeInheritsTheCompletenessGates() {
        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("name", "orders");
        recipe.put("steps", List.of(step("collect", new LinkedHashMap<>())));
        PipelineCompileException e = assertThrows(PipelineCompileException.class,
                () -> RecipeCompiler.compile(recipe));
        List<String> codes = e.refusals().stream().map(PipelineCompileException.Refusal::code).toList();
        assertTrue(codes.contains(PipelineEditable.NO_PARSER), "PipelineEditable's own gates apply: " + codes);
        assertTrue(codes.contains(PipelineEditable.NO_PERSISTENT_SINK), codes.toString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getOrNew(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.computeIfAbsent(key, k -> new LinkedHashMap<String, Object>());
    }
}
