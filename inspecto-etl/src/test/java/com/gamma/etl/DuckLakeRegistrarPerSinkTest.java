package com.gamma.etl;

import com.gamma.util.Topology;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code sinks[]} entry's own {@code ducklake} block is HONOURED at registration (operator decision
 * 2026-09-23, {@code SINK-DUCKLAKE-IGNORED-1}).
 *
 * <p>🔴 <b>The defect.</b> The parser, {@code PipelineConfig.resolveSinks} (entry → {@code output.*} →
 * none) and {@code PipelineLift} all carried a per-sink lake, but {@link DuckLakeRegistrar#register} read
 * only {@code cfg.output().duckLake()} and pooled every written file into it. So a sink's own lake was
 * silently dropped, and a two-sink pipeline registered BOTH sinks' files into the output lake (or into
 * nothing, when only the sinks declared one).
 *
 * <p>These pin the DECISION — which files each lake receives — through {@link DuckLakeRegistrar#plan},
 * for the reason {@link DuckLakeRegistrarTest} records: a real ATTACH needs the {@code ducklake} extension,
 * whose network {@code INSTALL} this offline reactor cannot make, and a test that SKIPS without it proves
 * nothing. Attribution is by the sink's {@code database} root, the same key every write site re-roots
 * under ({@code IngestSinkWriter.write}, {@code ConsignmentIngestStrategy.flatWriteAndTrace}).
 */
class DuckLakeRegistrarPerSinkTest {

    /** DuckLake registration is Professional+ (G9); this class tests the Professional behaviour. */
    @BeforeEach
    void professionalBuild() {
        EditionFeatures.overrideForTest(java.util.Set.of(EditionFeatures.SINK_DUCKLAKE));
    }

    @AfterEach
    void clearTopology() {
        System.clearProperty(Topology.PROPERTY);
        EditionFeatures.overrideForTest(null);
    }

    private static Map<String, Object> lake(Path dir, String name) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("enabled", true);
        l.put("catalog_url", dir.resolve(name + ".ducklake").toString());
        l.put("data_path", dir.resolve(name + "-data").toString());
        return l;
    }

    /** Two destinations under {@code dir}; {@code aLake} on sink A only, {@code outLake} on {@code output:}. */
    private static PipelineConfig twoSinks(Path dir, Map<String, Object> aLake, Map<String, Object> outLake) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "SINK_LAKE_ETL");
        m.put("dirs", Map.of("poll", dir.resolve("in").toString(), "database", dir.resolve("out").toString()));
        m.put("processing", Map.of("threads", 1));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("format", "parquet");
        if (outLake != null) output.put("ducklake", outLake);
        m.put("output", output);
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("database", dir.resolve("out_a").toString());
        if (aLake != null) a.put("ducklake", aLake);
        m.put("sinks", List.of(a, Map.of("database", dir.resolve("out_b").toString())));
        return PipelineConfig.fromMap(m);
    }

    private static String file(Path dir, String sinkDir) {
        return dir.resolve(sinkDir).resolve("orders").resolve("year=2026").resolve("part-0.parquet").toString();
    }

    @Test
    void eachSinkRegistersItsOwnFilesIntoItsOwnEffectiveLake(@TempDir Path dir) throws Exception {
        Map<String, Object> aLake = lake(dir, "lake_a");
        Map<String, Object> outLake = lake(dir, "lake_out");
        PipelineConfig cfg = twoSinks(dir, aLake, outLake);
        String fa = file(dir, "out_a");
        String fb = file(dir, "out_b");

        List<DuckLakeRegistrar.Registration> plan = DuckLakeRegistrar.plan(List.of(fa, fb), cfg);

        assertEquals(2, plan.size(), "one registration per destination: " + plan);
        assertEquals(aLake, plan.get(0).duckLake(), "sink A declared its own lake — it must win over output:");
        assertEquals(List.of(fa), plan.get(0).files(), "lake A holds sink A's files and nothing else");
        assertEquals(outLake, plan.get(1).duckLake(), "sink B omits ducklake, so it inherits output.ducklake");
        assertEquals(List.of(fb), plan.get(1).files(),
                "the output lake holds sink B's files only — never sink A's, which went to lake A");
    }

    @Test
    void aSinkWithNoEffectiveLakeRegistersNothingAndDoesNotStealTheOtherSinksFiles(@TempDir Path dir) throws Exception {
        Map<String, Object> aLake = lake(dir, "lake_a");
        PipelineConfig cfg = twoSinks(dir, aLake, null);
        String fa = file(dir, "out_a");
        String fb = file(dir, "out_b");

        List<DuckLakeRegistrar.Registration> plan = DuckLakeRegistrar.plan(List.of(fa, fb), cfg);

        assertEquals(aLake, plan.get(0).duckLake());
        assertEquals(List.of(fa), plan.get(0).files(), "lake A must not receive sink B's files");
        assertNull(plan.get(1).duckLake(), "sink B has no lake anywhere in its resolution chain");
        assertEquals(List.of(fb), plan.get(1).files());
    }

    /** The single-destination shorthand is unchanged: every file goes to output.ducklake, as before. */
    @Test
    void theSingleSinkShorthandRegistersEveryFileIntoTheOutputLake(@TempDir Path dir) throws Exception {
        Map<String, Object> outLake = lake(dir, "lake_out");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "ONE_SINK_ETL");
        m.put("processing", Map.of("threads", 1));
        m.put("dirs", Map.of("poll", dir.resolve("in").toString(), "database", dir.resolve("out").toString()));
        m.put("output", Map.of("format", "parquet", "ducklake", outLake));
        PipelineConfig cfg = PipelineConfig.fromMap(m);
        String inside = file(dir, "out");
        String elsewhere = file(dir, "somewhere_else");   // e.g. a Decision Rule's own output

        List<DuckLakeRegistrar.Registration> plan = DuckLakeRegistrar.plan(List.of(inside, elsewhere), cfg);

        assertEquals(1, plan.size());
        assertEquals(outLake, plan.get(0).duckLake());
        assertEquals(List.of(inside, elsewhere), plan.get(0).files(),
                "one destination means no attribution: every written file registers, byte-for-byte as before");
    }

    @Test
    void nestedDatabasesAttributeToTheDeepestRoot(@TempDir Path dir) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "NESTED_ETL");
        m.put("processing", Map.of("threads", 1));
        m.put("dirs", Map.of("poll", dir.resolve("in").toString(), "database", dir.resolve("out").toString()));
        m.put("output", Map.of("format", "parquet"));
        m.put("sinks", List.of(Map.of("database", dir.resolve("out").toString()),
                Map.of("database", dir.resolve("out").resolve("cold").toString())));
        PipelineConfig cfg = PipelineConfig.fromMap(m);
        String hot = file(dir, "out");
        String cold = dir.resolve("out").resolve("cold").resolve("orders").resolve("p.parquet").toString();

        List<DuckLakeRegistrar.Registration> plan = DuckLakeRegistrar.plan(List.of(hot, cold), cfg);

        assertEquals(List.of(hot), plan.get(0).files(), "the outer root must not claim the inner sink's file");
        assertEquals(List.of(cold), plan.get(1).files());
    }

    @Test
    void aFileUnderNoSinkIsRefusedRatherThanRegisteredSomewhereArbitrary(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = twoSinks(dir, lake(dir, "lake_a"), lake(dir, "lake_out"));

        IllegalStateException boom = assertThrows(IllegalStateException.class,
                () -> DuckLakeRegistrar.plan(List.of(file(dir, "stray")), cfg));
        assertTrue(boom.getMessage().contains("stray"), "the message must name the file: " + boom.getMessage());
    }

    /**
     * Partitioned: EVERY sink that wrote must have an enabled lake — checked for all sinks before any
     * ATTACH, so sink A is never registered while sink B's output stays invisible.
     */
    @Test
    void partitionedRefusesASinkWithoutALakeBeforeRegisteringAnything(@TempDir Path dir) throws Exception {
        System.setProperty(Topology.PROPERTY, "partitioned");
        PipelineConfig cfg = twoSinks(dir, lake(dir, "lake_a"), null);

        IllegalStateException boom = assertThrows(IllegalStateException.class,
                () -> DuckLakeRegistrar.register(List.of(file(dir, "out_a"), file(dir, "out_b")), "orders", cfg));
        assertTrue(boom.getMessage().contains("out_b"),
                "the refusal must name WHICH destination has no lake: " + boom.getMessage());
    }

    /** Single topology: a sink with no lake stays the optional-sidecar no-op; only the configured one registers. */
    @Test
    void singleTopologyToleratesASinkWithoutALake(@TempDir Path dir) throws Exception {
        Map<String, Object> disabled = lake(dir, "lake_a");
        disabled.put("enabled", false);
        PipelineConfig cfg = twoSinks(dir, disabled, null);

        assertDoesNotThrow(() -> DuckLakeRegistrar.register(
                List.of(file(dir, "out_a"), file(dir, "out_b")), "orders", cfg));
    }
}
