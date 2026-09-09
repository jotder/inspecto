package com.gamma.etl;

import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link DuckLakeRegistrar} had no test at all — one of the six Musts filed as {@code SPEC-NOPROOF-1}.
 *
 * <p><b>What this pins, and why each half matters.</b>
 *
 * <p><b>1. That the feature is REACHABLE.</b> The registrar reads {@code cfg.output().duckLake()}, and the
 * only thing that populates it is one line in a different class —
 * {@code PipelineConfigParser:860}, {@code b.duckLakeCfg = castMapAt(out, "ducklake")}. Nothing asserted
 * that link. 🔴 Grounding this test I grepped {@code PipelineConfig.java} for the assignment, found the
 * field declared and consumed but never written, and briefly concluded the whole DuckLake path was dead
 * code. It is not — the assignment lives in the parser. That near-miss is exactly why the wiring deserves
 * an assertion: a one-line change in the parser would silently make every DuckLake block a no-op, and no
 * test would have noticed.
 *
 * <p><b>2. That its GUARDS hold.</b> Empty outputs, an absent block, {@code enabled: false} and an absent
 * {@code enabled} flag must each be a no-op — the class javadoc's "the method is a no-op otherwise".
 *
 * <p>⛔ Deliberately NOT tested here: a successful registration, and the NON-FATAL promise. Both need
 * {@code INSTALL ducklake FROM core}, a network fetch this offline reactor cannot make — measured, it
 * costs ~131 s per attempt before failing. See the note at the foot of this class; the non-fatal half is
 * still owed under {@code SPEC-NOPROOF-1}.
 */
class DuckLakeRegistrarTest {

    /** A parsed config whose `output` block carries `extra` as its `ducklake` map (absent when null). */
    private static PipelineConfig configWithDuckLake(Path dir, Map<String, Object> lake) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(dir, "");
        Map<String, Object> raw = new LinkedHashMap<>(ToonHelper.load(pipe.toString()));
        Map<String, Object> out = new LinkedHashMap<>(castMap(raw.get("output")));
        if (lake != null) out.put("ducklake", lake);
        raw.put("output", out);
        return PipelineConfig.fromMap(raw);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    /** Enabled, well-formed, and pointing nowhere reachable — the shape the non-fatal promise is about. */
    private static Map<String, Object> unreachableLake(Path dir) {
        Map<String, Object> lake = new LinkedHashMap<>();
        lake.put("enabled", true);
        lake.put("catalog_url", dir.resolve("no-such-catalog.ducklake").toString());
        lake.put("data_path", dir.resolve("lake-data").toString());
        lake.put("table", "mini");
        return lake;
    }

    // ── 1. the wiring: output.ducklake -> cfg.output().duckLake() ─────────────────────────────────

    @Test
    void theDuckLakeBlockReachesTheOutputAccessor(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = configWithDuckLake(dir, unreachableLake(dir));

        Map<String, Object> read = cfg.output().duckLake();
        assertNotNull(read,
                "output.ducklake must reach cfg.output().duckLake() — the only line that populates it is "
                        + "PipelineConfigParser's `b.duckLakeCfg = castMapAt(out, \"ducklake\")`, and if it "
                        + "ever goes away every DuckLake block becomes a silent no-op");
        assertEquals("mini", read.get("table"));
        assertEquals(true, read.get("enabled"));
        assertNotNull(read.get("catalog_url"), "catalog_url must survive the parse");
        assertNotNull(read.get("data_path"), "data_path must survive the parse");
    }

    @Test
    void anAbsentBlockLeavesTheAccessorNull(@TempDir Path dir) throws Exception {
        assertNull(configWithDuckLake(dir, null).output().duckLake(),
                "no ducklake block must read as null, not an empty map — register() keys its no-op on null");
    }

    /**
     * The single-destination shorthand builds one {@code Sink} from the `output` block, and it must carry
     * the DuckLake map through. ({@code PipelineConfigSinksTest} already covers the null case; the
     * populated one was uncovered.)
     */
    @Test
    void theSingleDestinationSinkInheritsTheBlock(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = configWithDuckLake(dir, unreachableLake(dir));

        assertEquals(1, cfg.sinks().size(), "the mini fixture declares one destination");
        assertEquals("mini", cfg.sinks().get(0).duckLake().get("table"),
                "a shorthand sink must inherit output.ducklake — otherwise a single-destination pipeline "
                        + "would register nothing while its config says it should");
    }

    // ── 2. the guards — every shape the javadoc calls a no-op ──────────────────────────────────────────────────

    @Test
    void registerIsANoOpOnEmptyOutputs(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = configWithDuckLake(dir, unreachableLake(dir));
        assertDoesNotThrow(() -> DuckLakeRegistrar.register(List.of(), "mini", cfg),
                "no written files means nothing to register — it must return before touching the catalog");
    }

    @Test
    void registerIsANoOpWithoutABlockOrWhenDisabled(@TempDir Path dir) throws Exception {
        List<String> outputs = List.of(dir.resolve("part-0.parquet").toString());

        assertDoesNotThrow(() -> DuckLakeRegistrar.register(outputs, "mini", configWithDuckLake(dir, null)),
                "no ducklake block: the class javadoc says the method is a no-op otherwise");

        Map<String, Object> disabled = unreachableLake(dir);
        disabled.put("enabled", false);
        assertDoesNotThrow(() -> DuckLakeRegistrar.register(outputs, "mini", configWithDuckLake(dir, disabled)),
                "enabled:false must be a no-op — activation requires output.ducklake.enabled: true");

        Map<String, Object> noFlag = unreachableLake(dir);
        noFlag.remove("enabled");
        assertDoesNotThrow(() -> DuckLakeRegistrar.register(outputs, "mini", configWithDuckLake(dir, noFlag)),
                "an absent enabled flag defaults to false, so it must be a no-op too");
    }

    /*
     * 🔴 THE NON-FATAL PROMISE IS NOT PROVABLE IN THIS REACTOR, and the two tests that tried are
     * deliberately removed rather than left in or tagged. Measured 2026-09-09:
     *
     *   the five tests above ................................. 0.396 s
     *   plus two that drove an ENABLED block ................ 262.0 s
     *
     * The cost is `INSTALL ducklake FROM core` — a NETWORK fetch that runs before any failure the promise
     * covers, so every route to "enabled, then broken" waits for that to time out. Adding ~4.4 minutes to
     * an offline-first build to assert one property is a defect, not a test; and a @Tag-excluded test that
     * never runs is not a guard either (this repo has already recorded that a guard nobody runs proves
     * nothing). `DuckDbUtil.jdbcUrl` offers no settings hook, so the fetch cannot be made to fail fast.
     *
     * ⚠ So this class proves the REACHABILITY half and the GUARDS, and the non-fatal half stays owed —
     * filed under `SPEC-NOPROOF-1`. It needs a reachable DuckLake catalog, which means a live deployment,
     * which puts it in the same bucket as the SCR-4/5/7 deployment scripts rather than the reactor.
     * ⛔ Do not "fix" this by asserting it with a mock: the promise is about a real driver's real failure.
     */
}
