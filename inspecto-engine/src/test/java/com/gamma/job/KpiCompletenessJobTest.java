package com.gamma.job;

import com.gamma.consignment.ConsignmentOutput;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.event.EventLog;
import com.gamma.util.Scheduler;
import com.gamma.signal.SignalType;
import com.gamma.util.StoreHealth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code kpi.completeness} Job Type (completeness KPI K1/K4): refuse first, read second, and carry
 * {@code KPI-UNKNOWN-1} — unknown is absent, never zero — into the Signal payload.
 */
class KpiCompletenessJobTest {

    private final String space = EventLog.currentSpaceId();
    private DbConsignmentOutputStore db;

    @BeforeEach
    void open() throws Exception {
        db = DbConsignmentOutputStore.open("jdbc:duckdb:");
        ConsignmentOutputStores.use(db);
        StoreHealth.record(space, KpiCompletenessJob.FAMILY, StoreHealth.Status.UP, "jdbc:duckdb:", "open");
    }

    @AfterEach
    void close() {
        ConsignmentOutputStores.use(null);
        db.close();
        StoreHealth.clear(space);
    }

    static ConsignmentOutput out(String producer, String day, String path, long rows) {
        return new ConsignmentOutput("c-" + path, "run-1", "cdr", day == null ? null : "dt=" + day, day, path,
                rows, rows * 100, "2026-08-04T10:00:00Z", ConsignmentOutput.State.LIVE, null, null, producer);
    }

    static KpiCompletenessJob job(Map<String, String> params) {
        return new KpiCompletenessJob(new JobConfig("cdr_completeness", KpiCompletenessJob.TYPE, null, null,
                true, false, params, null, null));
    }

    static Map<String, String> params(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private Map<String, Object> runAndGetSignal(Map<String, String> p, String type) {
        CapturingJobContext ctx = new CapturingJobContext();
        JobResult r = job(p).run(ctx);
        assertEquals("SUCCESS", r.status(), r.message());
        return ctx.signals.stream().filter(s -> type.equals(s.get("__type"))).findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " in " + ctx.signals));
    }

    @Test
    void readsTheDaysVolumeForOnePipeline() {
        db.record(List.of(out("cdr", "2026-08-04", "/w/a.parquet", 70), out("cdr", "2026-08-04", "/w/b.parquet", 30),
                out("other", "2026-08-04", "/w/o.parquet", 999), out("cdr", "2026-08-03", "/w/y.parquet", 5)));
        Map<String, Object> s = runAndGetSignal(params("pipeline", "cdr", "record_day", "2026-08-04"),
                SignalType.KPI_COMPLETENESS_EVALUATED);
        assertEquals(100L, s.get("rows"), "this pipeline, this day only");
        assertEquals(2L, s.get("files"));
        assertFalse(s.containsKey("unknownDayRows"), "no unknown bucket in the store ⇒ no key");
    }

    /** KPI-UNKNOWN-1: a null-bounds sink's rows arrive as their own bucket, and a day with nothing has NO rows key. */
    @Test
    void unknownIsAbsentNeverZero() {
        db.record(List.of(out("cdr", null, "/w/enrich.parquet", 7)));
        Map<String, Object> s = runAndGetSignal(params("pipeline", "cdr", "record_day", "2026-08-04"),
                SignalType.KPI_COMPLETENESS_EVALUATED);
        assertEquals(7L, s.get("unknownDayRows"));
        assertEquals(1L, s.get("unknownDayFiles"));
        assertFalse(s.containsKey("rows"), "nothing is known for the day — absent, not rows: 0. Got " + s);
        assertFalse(s.containsKey("files"));
    }

    @Test
    void refusesWhenTheStoreIsNotConfiguredAndNamesTheToggle() {
        db.record(List.of(out("cdr", "2026-08-04", "/w/a.parquet", 70)));
        StoreHealth.record(space, KpiCompletenessJob.FAMILY, StoreHealth.Status.NOT_CONFIGURED, "none", "-D off");
        CapturingJobContext ctx = new CapturingJobContext();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> job(params("pipeline", "cdr", "record_day", "2026-08-04")).run(ctx));
        assertTrue(e.getMessage().contains("-Dconsignment.outputs.backend"), e.getMessage());
        assertTrue(ctx.signals.isEmpty(), "no kpi.completeness.* signal and no number on a refusal");
    }

    /** Absence of a StoreHealth entry is NOT_CONFIGURED, never UP — even though a working store is installed. */
    @Test
    void refusesWhenTheFamilyWasNeverOpened() {
        StoreHealth.clear(space);
        assertThrows(IllegalStateException.class,
                () -> job(params("pipeline", "cdr", "record_day", "2026-08-04")).run(new CapturingJobContext()));
    }

    @Test
    void refusesEvenOnADryRun() {
        StoreHealth.clear(space);
        assertThrows(IllegalStateException.class, () -> job(params("pipeline", "cdr", "record_day", "2026-08-04"))
                .run(new CapturingJobContext(Map.of(), true)));
    }

    @Test
    void requiresAPipelineAndAWellFormedDay() {
        assertThrows(IllegalArgumentException.class, () -> job(params()).run(new CapturingJobContext()));
        assertThrows(RuntimeException.class, () -> job(params("pipeline", "cdr", "record_day", "08/04/2026"))
                .run(new CapturingJobContext()));
    }

    @Test
    void isRegisteredAsABuiltInWithItsDeclaredSignals() throws Exception {
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(), new ConsignmentEventBus(), s, null,
                     "audit", null, null, "data")) {
            JobTypeDescriptor d = js.jobType(KpiCompletenessJob.TYPE).orElseThrow();
            assertTrue(d.emits().contains(SignalType.KPI_COMPLETENESS_EVALUATED), d.emits().toString());
        }
    }
}
