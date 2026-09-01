package com.gamma.job;

import com.gamma.config.io.ConfigCodec;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.enrich.EnrichmentEngine;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.RecipeCompiler;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Phase-3 parity gate (S4), execution half — deferred 2026-08-06 ("lands with S3's executor
 * machinery") and unblocked by the A5 at-rest path: {@link PipelineJobRunner} executes recipe-compiled
 * {@code transform.join} / {@code summarize} steps over a landed store for real, so "identical
 * outputs" is now testable (P3 S3d). Each leg feeds the SAME input rows to the legacy runtime and to
 * a recipe compiled by {@link RecipeCompiler}, serialized through the real codec, loaded off disk and
 * run — then asserts the same values come out.
 *
 * <p>Scope honesty: parity is per VERB, against the runtime each verb claims compatibility with —
 * {@code transform.join} vs {@link EnrichmentEngine}'s reference LEFT JOIN, {@code summarize} vs
 * {@code MaterializeTask}'s measure rollup (the shared {@code MeasureCompiler} column naming
 * included). A real {@code *_enrich.toon}'s hand-authored {@code transform} SQL (custom column
 * names, {@code ROUND}, …) is NOT byte-reproducible by the closed verb set and was never the claim —
 * {@code RecipeVerbParityTest} is the representation half of this gate.
 */
class RecipeExecutionParityTest {

    @TempDir Path tmp;

    // ── leg 1: transform.join vs EnrichmentEngine's reference LEFT JOIN ─────────

    @Test
    void aRecipeCompiledJoinProducesTheSameEnrichedRowsAsTheEnrichmentEngine() throws Exception {
        // one dimension file, shared verbatim by both arms; 'XX' has no dimension row (the LEFT JOIN probe)
        Path dim = tmp.resolve("region_dim.parquet");
        copy("(SELECT * FROM (VALUES ('EU','EMEA'),('US','NA')) t(region,zone)) TO '"
                + sql(dim) + "' (FORMAT PARQUET)");
        String inputValues = "('2020',1,'EU'),('2020',2,'US'),('2021',3,'XX')";

        // legacy arm: EnrichmentEngine over a partitioned Stage-1 tree, transform = the reference LEFT JOIN
        Path in = tmp.resolve("enrich_in"), out = tmp.resolve("enrich_out");
        copy("(SELECT * FROM (VALUES " + inputValues + ") t(year,id,region)) TO '" + sql(in)
                + "' (FORMAT PARQUET, PARTITION_BY (year), OVERWRITE_OR_IGNORE 1)");
        EnrichmentConfig legacy = new EnrichmentConfig("JOIN_PARITY",
                new EnrichmentConfig.Input(sql(in), "PARQUET", List.of("year")),
                List.of(new EnrichmentConfig.Reference("region_dim", sql(dim), "PARQUET")),
                new EnrichmentConfig.Output(sql(out), "PARQUET", "snappy", List.of("year")),
                "SELECT i.year, i.id, i.region, r.zone FROM input i"
                        + " LEFT JOIN region_dim r ON i.region = r.region");
        EnrichmentEngine.run(legacy);
        List<String> legacyRows = joined("read_parquet('" + sql(out) + "/**/*.parquet', hive_partitioning=true)");

        // recipe arm: the SAME rows landed as a store, the SAME dimension file as the join reference
        String dataDir = tmp.resolve("jdata").toString();
        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("name", "join_parity_etl");
        recipe.put("active", false);
        recipe.put("steps", List.of(
                step("collect", new LinkedHashMap<>(Map.of("poll", "in"))),
                step("parse", new LinkedHashMap<>()),
                step("transform", new LinkedHashMap<>(Map.of("join", sql(dim), "on", "region"))),
                step("sink", new LinkedHashMap<>(Map.of("database", "out", "format", "PARQUET")))));
        runAtRest(recipe, "joined", dataDir,
                "(SELECT * FROM (VALUES " + inputValues + ") t(year,id,region))");
        List<String> recipeRows = joined("read_parquet('" + sql(Path.of(dataDir, "joined")) + "/*.parquet')");

        assertEquals(legacyRows, recipeRows, "the recipe-compiled join must enrich identically");
        assertEquals("3|XX|null", recipeRows.get(2), "LEFT JOIN semantics: the unmatched key carries NULL");
    }

    // ── leg 2: summarize vs the materialize maintenance task ────────────────────

    @Test
    void aRecipeCompiledSummarizeProducesTheSameRollupAsTheMaterializeTask() throws Exception {
        String values = "('EU',10.0),('EU',30.0),('US',5.0)";

        // legacy arm: the materialize task over a view-backed dataset of the same rows
        Path writeRoot = tmp.resolve("wr");
        Path mxData = tmp.resolve("mxdata");
        new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("sales_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES " + values + ") AS t(region,amount)", "2026-08-28T00:00:00Z"));
        new ComponentStore(writeRoot.resolve("registry")).write("dataset", "sales_ds", Map.of("view", "sales_view"));
        System.setProperty("assist.write.root", writeRoot.toString());
        List<String> legacyRows;
        try {
            JobConfig mx = new JobConfig("mx", JobType.MAINTENANCE, null, null, true, false,
                    Map.of("task", "materialize", "dataset", "sales_ds", "target", "sales_by_region",
                            "measures", "sum(amount),count", "group_by", "region"));
            JobResult res = new MaintenanceJob(mx, mxData.toString()).run();
            assertTrue(res.success(), res.message());
            legacyRows = rollup("read_parquet('" + sql(mxData.resolve("sales_by_region")) + "/*.parquet')");
        } finally {
            System.clearProperty("assist.write.root");
        }

        // recipe arm: the SAME rows landed as a store, the SAME measures through the summarize verb
        String dataDir = tmp.resolve("sdata").toString();
        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("name", "rollup_parity_etl");
        recipe.put("active", false);
        recipe.put("steps", List.of(
                step("collect", new LinkedHashMap<>(Map.of("poll", "in"))),
                step("parse", new LinkedHashMap<>()),
                step("summarize", new LinkedHashMap<>(Map.of(
                        "group_by", List.of("region"), "measures", List.of("sum(amount)", "count")))),
                step("sink", new LinkedHashMap<>(Map.of("database", "out", "format", "PARQUET")))));
        runAtRest(recipe, "sales_rollup", dataDir, "(SELECT * FROM (VALUES " + values + ") t(region,amount))");
        List<String> recipeRows = rollup("read_parquet('" + sql(Path.of(dataDir, "sales_rollup")) + "/*.parquet')");

        assertEquals(legacyRows, recipeRows,
                "same rollup values under the same MeasureCompiler column names (sum_amount / count)");
        assertEquals(List.of("EU|40.0|2", "US|5.0|1"), recipeRows);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Compile {@code recipe}, author the at-rest knob, serialize through the real codec, land, run. */
    private void runAtRest(Map<String, Object> recipe, String outputStore, String dataDir,
                           String landedSelect) throws Exception {
        Map<String, Object> compiled = RecipeCompiler.compile(recipe, Map.of(), false);
        compiled.put("output_store", outputStore);   // the Stage-2 arming knob, authored as on any at-rest config
        Path flat = tmp.resolve(recipe.get("name") + "_pipeline.toon");
        Files.writeString(flat, ConfigCodec.toToon(compiled));
        // the landed store's name is the lift's canonical-name fallback — read it off the loaded config
        String landed = com.gamma.etl.PipelineConfig.load(flat.toString()).identity().pipelineName();
        Path store = Path.of(dataDir, landed);
        Files.createDirectories(store);
        copy(landedSelect + " TO '" + sql(store.resolve("seed.parquet")) + "' (FORMAT PARQUET)");

        JobConfig job = new JobConfig((String) recipe.get("name"), JobType.PIPELINE, null, null, true, false,
                Map.of("pipeline_config", flat.toString(), "data_dir", dataDir));
        JobResult res = new PipelineJobRunner(job, new ConsignmentEventBus(), null, dataDir,
                tmp.resolve("audit").toString()).run();
        assertTrue(res.success(), res.message());
    }

    /** A path as DuckDB SQL wants it — forward slashes, whatever the host separator. */
    private static String sql(Path p) {
        return p.toString().replace("\\", "/");
    }

    /** Run {@code COPY <body>} on a scratch DuckDB — the seeding idiom of the sibling runner tests. */
    private static void copy(String body) throws Exception {
        File db = DuckDbUtil.tempDbFile("seed_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("COPY " + body);
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** Read {@code (id, region, zone)} rows off {@code from}, ascending by id, as {@code id|region|zone}. */
    private static List<String> joined(String from) throws Exception {
        return rows("SELECT id, region, zone FROM " + from + " ORDER BY id");
    }

    /** Read {@code (region, sum_amount, count)} rows off {@code from} — the names are part of the gate. */
    private static List<String> rollup(String from) throws Exception {
        return rows("SELECT region, sum_amount, \"count\" FROM " + from + " ORDER BY region");
    }

    private static List<String> rows(String sql) throws Exception {
        File db = DuckDbUtil.tempDbFile("vfy_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                int cols = rs.getMetaData().getColumnCount();
                for (int i = 1; i <= cols; i++) {
                    if (i > 1) row.append('|');
                    Object v = rs.getObject(i);
                    row.append(v instanceof java.math.BigDecimal d
                            ? String.valueOf(d.doubleValue()) : String.valueOf(v));
                }
                out.add(row.toString());
            }
            return out;
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    private static Map<String, Object> step(String verb, Map<String, Object> cfg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(verb, cfg);
        return m;
    }
}
