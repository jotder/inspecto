package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.job.JobConfig;
import com.gamma.job.JobRun;
import com.gamma.job.JobService;
import com.gamma.job.PostgresPublishJobType;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.SpaceConfigRoot;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code kpi.snapshot} (`BI-INSIGHTS-1` P0, I-3) run by a real JobService: it is registered through ServiceLoader,
 * stores the history, registers a system Dataset carrying the source's envelope, and refuses a Measure that reveals a
 * masked column and a Dataset its author may not read.
 */
class KpiSnapshotJobTest {

    private static final String SPACE = "default";

    @TempDir Path tmp;
    private String priorAuditDir;
    private String priorDataDir;
    private Path priorConfigRoot;
    private Path configRoot;
    private Path data;
    private ComponentStore store;

    @BeforeEach
    void space() throws Exception {
        priorAuditDir = System.getProperty("jobs.audit.dir");
        priorDataDir = System.getProperty("data.dir");
        data = tmp.resolve("data");
        configRoot = tmp.resolve("config-root");
        System.setProperty("jobs.audit.dir", tmp.resolve("jobs_audit").toString());
        System.setProperty("data.dir", data.toString());
        priorConfigRoot = SpaceConfigRoot.forSpace(SPACE);
        SpaceConfigRoot.register(SPACE, configRoot);

        Files.createDirectories(data.resolve("orders"));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES ('+491', DATE '2026-05-20', 10.0), ('+492', DATE '2026-06-03', 20.0)) "
                    + "AS t(msisdn, order_date, amount)) TO '" + data.resolve("orders/part-0.parquet").toString().replace('\\', '/')
                    + "' (FORMAT PARQUET)");
        }
        Files.createDirectories(configRoot);
        Files.writeString(configRoot.resolve("link-analysis.toon"), "masking_mode: typed\n");
        store = new ComponentStore(configRoot.resolve("registry"));
        store.write("dataset", "orders", Map.of("physicalRef", "orders", "columns", List.of(
                Map.of("name", "msisdn", "classification", "MSISDN"), Map.of("name", "order_date"), Map.of("name", "amount"))));
        kpi("revenue", "sum(amount)");
    }

    @AfterEach
    void restore() {
        PostgresPublishJobType.installAuthority(null);
        restore("jobs.audit.dir", priorAuditDir);
        restore("data.dir", priorDataDir);
        if (priorConfigRoot != null) SpaceConfigRoot.register(SPACE, priorConfigRoot);
        else SpaceConfigRoot.forget(SPACE);
    }

    private static void restore(String key, String prior) {
        if (prior != null) System.setProperty(key, prior);
        else System.clearProperty(key);
    }

    private void kpi(String id, String measure) throws Exception {
        store.write("kpi", id, Map.of("dataset", "orders", "measure", measure, "timeField", "order_date", "grain", "month"));
    }

    private JobRun snapshot(String kpi) throws Exception {
        return snapshot(kpi, Map.of());
    }

    /** Runs a Job saved for {@code kpi}, triggered with run-time {@code args}. */
    private JobRun snapshot(String kpi, Map<String, String> args) throws Exception {
        Path toon = TestConfigs.csv(tmp.resolve("cfg"), PipelineConfigBatchTest.miniSchema()).write();
        try (CollectorService svc = new CollectorService(List.of(toon), 3600, 1)) {
            JobService js = svc.jobServiceOrCreate();
            js.upsertJob(new JobConfig("history", KpiSnapshotJobType.TYPE, null, null, true, false,
                    Map.of("kpi", kpi), null, null));
            String runId = js.triggerRun("history", "t", args).orElseThrow();
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                JobRun r = js.runById(runId).orElse(null);
                if (r != null && r.endTime() != null && !r.endTime().isBlank()) return r;
                Thread.sleep(50);
            }
            return fail("run " + runId + " did not finish");
        }
    }

    private Map<String, Object> dataset(String id) {
        return store.get("dataset", id).map(ComponentRegistry.Component::content).orElse(null);
    }

    @Test
    void storesTheHistoryAndRegistersASystemDataset() throws Exception {
        JobRun run = snapshot("revenue");

        assertEquals("SUCCESS", run.status(), run.message());
        try (var files = Files.list(data.resolve("kpi_history_revenue"))) {
            assertEquals(1, files.filter(p -> p.getFileName().toString().endsWith(".parquet")).count());
        }
        Map<String, Object> ds = dataset("kpi_history_revenue");
        assertEquals("kpi_history_revenue", ds.get("physicalRef"));
        assertEquals("system", ds.get("origin"));
    }

    @Test
    void aMeasureThatRevealsAMaskedColumnIsRefused() throws Exception {
        kpi("top_number", "max(msisdn)");

        JobRun run = snapshot("top_number");

        assertNotEquals("SUCCESS", run.status(), run.message());
        assertFalse(Files.exists(data.resolve("kpi_history_top_number")), "nothing stored");
        assertEquals(null, dataset("kpi_history_top_number"));
    }

    @Test
    void aDatasetTheAuthorMayNotReadIsRefused() throws Exception {
        store.write("dataset", "orders", Map.of("physicalRef", "orders", "owner", "alice", "shares", List.of()));
        PostgresPublishJobType.installAuthority(cfg -> new PostgresPublishJobType.Author("bob", Set.of(), Set.of(), false));

        JobRun run = snapshot("revenue");

        assertNotEquals("SUCCESS", run.status(), run.message());
        assertFalse(Files.exists(data.resolve("kpi_history_revenue")), "nothing stored");
    }

    @Test
    void aKpiTheAuthorMayNotViewIsRefused() throws Exception {
        store.write("kpi", "revenue", Map.of("dataset", "orders", "measure", "sum(amount)", "timeField", "order_date",
                "grain", "month", "owner", "alice", "shares", List.of()));
        PostgresPublishJobType.installAuthority(cfg -> new PostgresPublishJobType.Author("bob", Set.of(), Set.of(), false));

        JobRun run = snapshot("revenue");

        assertNotEquals("SUCCESS", run.status(), run.message());
        assertFalse(Files.exists(data.resolve("kpi_history_revenue")), "nothing stored");
    }

    @Test
    void aRunMayNotPointTheJobAtAnotherKpi() throws Exception {
        kpi("other", "count(*)");

        JobRun run = snapshot("revenue", Map.of("kpi", "other"));

        assertNotEquals("SUCCESS", run.status(), run.message());
        assertFalse(Files.exists(data.resolve("kpi_history_other")), "nothing stored for the KPI the run asked for");
        assertFalse(Files.exists(data.resolve("kpi_history_revenue")), "nor for the saved one");
    }

    @Test
    void aStoreAnotherOwnerMadeRefusesBeforeAnythingIsRegistered() throws Exception {
        Files.createDirectories(data.resolve("kpi_history_revenue"));
        Files.writeString(data.resolve("kpi_history_revenue/foreign.parquet"), "not ours");

        JobRun run = snapshot("revenue");

        assertNotEquals("SUCCESS", run.status(), run.message());
        assertEquals(null, dataset("kpi_history_revenue"), "no Dataset registered over the foreign store");
        assertEquals("not ours", Files.readString(data.resolve("kpi_history_revenue/foreign.parquet")), "left untouched");
    }

    @Test
    void theHistoryIsReadableOnlyByWhoMayReadBothTheKpiAndItsDataset() {
        Map<String, Object> open = Map.of("physicalRef", "orders");
        Map<String, Object> finance = Map.of("owner", "alice", "shares", List.of(Map.of("subjectType", "role", "subjectId", "finance")));
        Map<String, Object> sales = Map.of("owner", "alice", "shares", List.of(Map.of("subjectType", "role", "subjectId", "sales")));

        assertEquals(finance, KpiSnapshotJobType.envelope(store, finance, open), "a shared KPI over an open Dataset");
        assertEquals(finance, KpiSnapshotJobType.envelope(store, Map.of(), finance), "an open KPI over a shared Dataset");
        assertEquals(finance, KpiSnapshotJobType.envelope(store, finance, finance), "shared alike");
        assertEquals(Map.of(), KpiSnapshotJobType.envelope(store, Map.of(), open), "both open");
        assertThrows(SecurityException.class, () -> KpiSnapshotJobType.envelope(store, finance, sales), "shared differently");
    }

    /** BI-INSIGHTS-1 loose ends A: a Mapping's base (recursively) joins the envelope. */
    @Test
    void aMappingsBaseChainJoinsTheHistoryEnvelope() throws Exception {
        Map<String, Object> finance = Map.of("owner", "alice", "shares", List.of(Map.of("subjectType", "role", "subjectId", "finance")));
        Map<String, Object> sales = Map.of("owner", "alice", "shares", List.of(Map.of("subjectType", "role", "subjectId", "sales")));
        store.write("dataset", "base_sales", Map.of("physicalRef", "orders", "owner", "alice", "shares", sales.get("shares")));
        store.write("dataset", "base_finance", Map.of("physicalRef", "orders", "owner", "alice", "shares", finance.get("shares")));
        store.write("dataset", "mid", Map.of("origin", "mapping", "basedOn", "base_sales", "physicalRef", "orders"));
        Map<String, Object> overSales = Map.of("origin", "mapping", "basedOn", "mid", "physicalRef", "orders");
        Map<String, Object> overFinance = Map.of("origin", "mapping", "basedOn", "base_finance", "physicalRef", "orders");

        assertThrows(SecurityException.class, () -> KpiSnapshotJobType.envelope(store, finance, overSales),
                "an open Mapping over a Mapping over a base shared to other readers");
        assertEquals(finance, KpiSnapshotJobType.envelope(store, finance, overFinance), "base shared alike");
        assertEquals(sales, KpiSnapshotJobType.envelope(store, Map.of(), overSales), "the base's envelope, two hops down");
        assertThrows(SecurityException.class, () -> KpiSnapshotJobType.envelope(store, Map.of(),
                Map.of("origin", "mapping", "basedOn", "missing")), "an unresolvable base refuses");
    }

    @Test
    void aSnapshotOverAMappingWhoseBaseIsSharedDifferentlyIsRefused() throws Exception {
        Map<String, Object> orders = dataset("orders");
        store.write("dataset", "orders_base", new java.util.LinkedHashMap<>(orders) {{
            put("owner", "alice"); put("shares", List.of(Map.of("subjectType", "role", "subjectId", "sales")));
        }});
        store.write("dataset", "orders_map", new java.util.LinkedHashMap<>(orders) {{
            put("origin", "mapping"); put("basedOn", "orders_base");
        }});
        Map<String, Object> kpi = new java.util.LinkedHashMap<>(store.get("kpi", "revenue").orElseThrow().content());
        kpi.put("dataset", "orders_map");
        kpi.put("owner", "alice");
        kpi.put("shares", List.of(Map.of("subjectType", "role", "subjectId", "finance")));
        store.write("kpi", "revenue", kpi);

        JobRun run = snapshot("revenue");

        assertNotEquals("SUCCESS", run.status(), run.message());
        assertEquals(null, dataset("kpi_history_revenue"), "nothing registered");
    }

    @Test
    void theHistoryDatasetFollowsTheSourceEnvelope() throws Exception {
        Map<String, Object> shared = Map.of("physicalRef", "orders", "owner", "alice",
                "shares", List.of(Map.of("subjectType", "role", "subjectId", "finance")));
        KpiSnapshotJobType.register(store, "revenue", shared);
        assertEquals("alice", dataset("kpi_history_revenue").get("owner"));

        KpiSnapshotJobType.register(store, "revenue", Map.of("physicalRef", "orders"));
        assertFalse(dataset("kpi_history_revenue").containsKey("shares"), "an unshared source unshares the history");
    }

    @Test
    void aForeignDatasetUnderTheHistoryIdIsNeverOverwritten() throws Exception {
        store.write("dataset", "kpi_history_revenue", Map.of("physicalRef", "orders"));
        assertThrows(IllegalStateException.class, () -> KpiSnapshotJobType.register(store, "revenue", Map.of()));
        assertEquals("orders", dataset("kpi_history_revenue").get("physicalRef"));
    }

    @Test
    void theHistoryStorePrefixIsReserved() {
        KpiSnapshotJobType.requireNotReserved(configRoot, "dataset", "kpi_history_revenue",
                Map.of("physicalRef", "kpi_history_revenue"));   // the history Dataset over a saved KPI
        assertThrows(IllegalArgumentException.class, () -> KpiSnapshotJobType.requireNotReserved(configRoot, "dataset",
                "kpi_history_nope", Map.of("physicalRef", "kpi_history_nope")));
        assertThrows(IllegalArgumentException.class, () -> KpiSnapshotJobType.requireNotReserved(configRoot, "sink",
                "out", Map.of("path", "kpi_history_revenue/x")));
        assertThrows(IllegalArgumentException.class, () -> KpiSnapshotJobType.requireNotReserved(configRoot, "dataset",
                "copy", Map.of("physicalRef", "kpi_history_revenue")));
        KpiSnapshotJobType.requireNotReserved(configRoot, "dataset", "orders", Map.of("physicalRef", "orders"));
    }
}
