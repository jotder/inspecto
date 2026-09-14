package com.gamma.etl;

import com.gamma.util.ToonHelper;
import com.gamma.util.Topology;
import org.junit.jupiter.api.AfterEach;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
     *
     * ⚠ UNCHANGED by D10 (2026-09-14). D10 makes a failure FATAL when partitioned, and the tests below
     * cover that DECISION directly via `onRegistrationFailure`. They do not, and cannot, discharge the
     * owed half above: driving a real driver failure still costs the same network INSTALL. What is
     * tested here is the branch this repo wrote; what stays owed is the driver's behaviour.
     */

    // -- 3. D10: a failure is FATAL in a partitioned topology, unchanged in a single one -----------------

    @AfterEach
    void clearTopology() {
        System.clearProperty(Topology.PROPERTY);
    }

    @Test
    void aRegistrationFailureIsFatalWhenPartitioned() {
        System.setProperty(Topology.PROPERTY, "partitioned");

        IllegalStateException boom = assertThrows(IllegalStateException.class,
                () -> DuckLakeRegistrar.onRegistrationFailure(new java.sql.SQLException("catalog unreachable"),
                        "postgres://lake/catalog"),
                "D10: a pod that cannot reach the shared catalog must fail the batch, not log and continue");

        assertTrue(boom.getMessage().contains("catalog unreachable"),
                "the operator needs the underlying cause, not just 'registration failed': " + boom.getMessage());
        assertTrue(boom.getMessage().contains("postgres://lake/catalog"),
                "the message must name WHICH catalog was unreachable: " + boom.getMessage());
        assertTrue(boom.getMessage().contains(Topology.PROPERTY),
                "the message must name the property that made this fatal, so it can be turned off: "
                        + boom.getMessage());
    }

    // The falsification arm. Without it the test above would pass against a method that ALWAYS threw,
    // which would turn every Personal/Standard DuckLake hiccup into a failed batch.
    @Test
    void theSameFailureIsToleratedOnASingleNode() {
        System.setProperty(Topology.PROPERTY, "single");

        assertDoesNotThrow(() -> DuckLakeRegistrar.onRegistrationFailure(
                        new java.sql.SQLException("catalog unreachable"), "postgres://lake/catalog"),
                "on one node the DuckLake step stays the optional sidecar its javadoc promises");
    }

    @Test
    void anUnsetTopologyIsSingleAndSoStaysNonFatal() {
        System.clearProperty(Topology.PROPERTY);

        assertDoesNotThrow(() -> DuckLakeRegistrar.onRegistrationFailure(
                        new java.sql.SQLException("catalog unreachable"), "postgres://lake/catalog"),
                "the default topology is single — D10 must not change behaviour for anyone who never set "
                        + "the flag, which is every Personal and Standard install");
    }

    // -- 4. D4/phase C: a partitioned pod must not get a PRIVATE catalog ---------------------------------
    //
    // The gap these cover is not "the catalog is unreachable" (that is D10, above) but "the catalog is
    // reached, successfully, and is this pod's alone". Measured 2026-09-14 against duckdb_jdbc 1.5.2.1: a
    // catalog_url with no backend prefix does not raise — DuckLake creates a local DuckDB file named after
    // the whole string. On N pods that is N private catalogs with every batch green, which is the exact
    // split-brain D10 exists to prevent, arriving as SUCCESS instead of as failure. So it has to be refused
    // on the URL's shape, before the ATTACH: there is no later moment at which it looks wrong.

    @Test
    void aFileCatalogIsRefusedWhenPartitioned() {
        System.setProperty(Topology.PROPERTY, "partitioned");

        IllegalStateException boom = assertThrows(IllegalStateException.class,
                () -> DuckLakeRegistrar.requireSharedCatalog("/var/lib/inspecto/lake.ducklake"),
                "a file catalog on N pods is N private catalogs, and nothing would ever report it");

        assertTrue(boom.getMessage().contains("postgres:dbname="),
                "refusing is not enough — the message must carry the spelling measured WORKING, or the "
                        + "operator's next guess is the postgresql:// URL that also fails: " + boom.getMessage());
        assertTrue(boom.getMessage().contains(Topology.PROPERTY),
                "the message must name the property that made this fatal: " + boom.getMessage());
    }

    // The spelling okf/backend/integrations.md shipped as THE example. It reads like a shared catalog and
    // is not one: no recognised backend prefix, so DuckLake reads it as a path too (AIRGAP-DUCKLAKE-PG-1,
    // re-measured 2026-09-14). Refusing it is deliberate, not a mis-classification.
    @Test
    void theDocumentedPostgresqlUrlIsAlsoRefusedWhenPartitioned() {
        System.setProperty(Topology.PROPERTY, "partitioned");

        assertThrows(IllegalStateException.class,
                () -> DuckLakeRegistrar.requireSharedCatalog(
                        "postgresql://etl_user:pw@localhost:5432/ducklake_db"),
                "a postgresql:// URL carries no backend prefix and is read as a file path, so it must not "
                        + "pass a guard whose whole subject is 'will this be a private file catalog'");
    }

    @Test
    void aServerBackedCatalogIsAcceptedWhenPartitioned() {
        System.setProperty(Topology.PROPERTY, "partitioned");

        assertDoesNotThrow(() -> DuckLakeRegistrar.requireSharedCatalog(
                        "postgres:dbname=lake host=db port=5432 user=u password=p"),
                "the measured-working spelling must pass, or the guard forbids the only correct answer");
        assertDoesNotThrow(() -> DuckLakeRegistrar.requireSharedCatalog("mysql:host=db database=lake"),
                "mysql is a shared server catalog too; the guard's subject is file-vs-server, not postgres");
    }

    // The falsification arm. Without it the three above would pass against a method that ALWAYS threw,
    // which would break every Personal and single-node Standard install — where a file catalog is the
    // correct and documented choice.
    @Test
    void aFileCatalogIsPerfectlyFineOnASingleNode() {
        System.setProperty(Topology.PROPERTY, "single");

        assertDoesNotThrow(() -> DuckLakeRegistrar.requireSharedCatalog("/var/lib/inspecto/lake.ducklake"),
                "one process owning its own lakehouse is the documented single-node shape");
    }

    @Test
    void anUnsetTopologyDoesNotConstrainTheCatalog() {
        System.clearProperty(Topology.PROPERTY);

        assertDoesNotThrow(() -> DuckLakeRegistrar.requireSharedCatalog("lake.ducklake"),
                "the default topology is single — this guard must not change behaviour for anyone who "
                        + "never set the flag");
    }

    // -- 5. D4/phase C: small writes must not inline into the CATALOG DATABASE ---------------------------
    //
    // Measured 2026-09-14 with a control pair against a live Postgres catalog: WITHOUT the option a small
    // table produced 0 Parquet files and its rows appeared in the catalog's ducklake_inlined_data_* table;
    // WITH it the same write produced 1 Parquet file in the data path. Inlining makes the catalog database
    // a DATA path, which breaks the object-store model D4 signed and leaves an external Parquet reader
    // (D13) reading an incomplete lake.

    @Test
    void inliningIsDisabledWhenPartitioned() {
        System.setProperty(Topology.PROPERTY, "partitioned");

        assertTrue(DuckLakeRegistrar.attachOptions().contains("DATA_INLINING_ROW_LIMIT 0"),
                "without this the catalog database silently becomes a data path: "
                        + DuckLakeRegistrar.attachOptions());
    }

    // The falsification arm. Without it the test above would pass against a method that ALWAYS returned the
    // option, which would turn off a genuine single-node optimisation for every Personal install.
    @Test
    void inliningIsLeftAloneOnASingleNode() {
        System.setProperty(Topology.PROPERTY, "single");

        assertEquals("", DuckLakeRegistrar.attachOptions(),
                "on one node nothing reads the Parquet behind the catalog's back, so inlining stays");
    }

    @Test
    void anUnsetTopologyLeavesInliningAlone() {
        System.clearProperty(Topology.PROPERTY);

        assertEquals("", DuckLakeRegistrar.attachOptions(),
                "the default topology is single — this must not change behaviour for anyone who never set "
                        + "the flag");
    }

    // -- 6. §5.4 bullet 4: registration is MANDATORY when partitioned, not an opt-in sidecar -------------
    //
    // D10 closed the path where registration FAILS. It left open the path where registration is never
    // ATTEMPTED: `enabled: false`, or no ducklake block at all, is a silent no-op on every topology. Across
    // nodes that produces exactly what D10 refuses — Parquet registered nowhere, visible to no other node —
    // reached by not trying rather than by failing. Operator decision 2026-09-14.
    //
    // 🔴 Only HALF the invariant, deliberately recorded as such: the single call site is the FLAT ingest
    // lane, and the graph lane registers nothing on any topology. These tests pin the flat lane's rule;
    // they must not be read as pinning a system-wide one.

    private static Map<String, Object> enabledLake() {
        Map<String, Object> lake = new LinkedHashMap<>();
        lake.put("enabled", true);
        return lake;
    }

    private static Map<String, Object> disabledLake() {
        Map<String, Object> lake = new LinkedHashMap<>();
        lake.put("enabled", false);
        return lake;
    }

    @Test
    void anAbsentDuckLakeBlockIsRefusedWhenPartitioned() {
        System.setProperty(Topology.PROPERTY, "partitioned");

        IllegalStateException boom = assertThrows(IllegalStateException.class,
                () -> DuckLakeRegistrar.requireRegistrationConfigured(null),
                "writing Parquet no other node can see is what D10 refuses; not attempting registration "
                        + "reaches the same place as failing it");

        assertTrue(boom.getMessage().contains("no output.ducklake block"),
                "the message must distinguish 'no block' from 'disabled' — they are different mistakes: "
                        + boom.getMessage());
        assertTrue(boom.getMessage().contains(Topology.PROPERTY),
                "the message must name the flag that made this fatal, so it can be turned off: "
                        + boom.getMessage());
    }

    @Test
    void anExplicitlyDisabledDuckLakeBlockIsAlsoRefusedWhenPartitioned() {
        System.setProperty(Topology.PROPERTY, "partitioned");

        IllegalStateException boom = assertThrows(IllegalStateException.class,
                () -> DuckLakeRegistrar.requireRegistrationConfigured(disabledLake()),
                "opting out is exactly the thing a partitioned deployment may not do");

        assertTrue(boom.getMessage().contains("disabled or unset"),
                "a present-but-disabled block is a different diagnosis from an absent one: "
                        + boom.getMessage());
    }

    @Test
    void anEnabledDuckLakeBlockPassesWhenPartitioned() {
        System.setProperty(Topology.PROPERTY, "partitioned");

        assertDoesNotThrow(() -> DuckLakeRegistrar.requireRegistrationConfigured(enabledLake()),
                "a correctly configured partitioned pipeline must pass, or the check forbids the only "
                        + "correct answer");
    }

    // The falsification arms. Without these the three above would pass against a method that ALWAYS threw,
    // which would break every Personal and single-node Standard install that has no DuckLake block at all —
    // i.e. almost all of them, since the lakehouse is an optional sidecar there.
    @Test
    void anAbsentDuckLakeBlockIsPerfectlyFineOnASingleNode() {
        System.setProperty(Topology.PROPERTY, "single");

        assertDoesNotThrow(() -> DuckLakeRegistrar.requireRegistrationConfigured(null),
                "on one node the DuckLake step is the optional sidecar its javadoc promises");
        assertDoesNotThrow(() -> DuckLakeRegistrar.requireRegistrationConfigured(disabledLake()),
                "opting out on one node stays legitimate");
    }

    @Test
    void anUnsetTopologyDoesNotMakeRegistrationMandatory() {
        System.clearProperty(Topology.PROPERTY);

        assertDoesNotThrow(() -> DuckLakeRegistrar.requireRegistrationConfigured(null),
                "the default topology is single — this must not change behaviour for anyone who never set "
                        + "the flag, which is every Personal and Standard install");
    }
}
