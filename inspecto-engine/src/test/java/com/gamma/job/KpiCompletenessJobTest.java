package com.gamma.job;

import com.gamma.consignment.ConsignmentOutput;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.consignment.DbFileStageStore;
import com.gamma.consignment.FileStage;
import com.gamma.consignment.FileStageRecord;
import com.gamma.consignment.FileStages;
import com.gamma.etl.PipelineConfig;
import com.gamma.event.EventLog;
import com.gamma.objects.IncidentAccess;
import com.gamma.util.Scheduler;
import com.gamma.signal.SignalType;
import com.gamma.util.StoreHealth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

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

    /** The volume-half tests opt out of K2 explicitly ({@code check_files} defaults to true). */
    static KpiCompletenessJob job(Map<String, String> params) {
        params.putIfAbsent("check_files", "false");
        return new KpiCompletenessJob(new JobConfig("cdr_completeness", KpiCompletenessJob.TYPE, null, null,
                true, false, params, null, null), null);
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

    /** K3: a day at half its rolling baseline breaches; the same day with too little history is unknown. */
    @Test
    void assessesTheDayAgainstItsRollingBaseline() {
        for (int d = 1; d <= 7; d++) db.record(List.of(out("cdr", "2026-08-0" + d, "/w/" + d + ".parquet", 100)));
        db.record(List.of(out("cdr", "2026-08-08", "/w/8.parquet", 50)));
        Map<String, Object> s = runAndGetSignal(params("pipeline", "cdr", "record_day", "2026-08-08"),
                SignalType.KPI_COMPLETENESS_EVALUATED);
        assertEquals("BREACH", s.get("status"));
        assertEquals(100L, s.get("baselineRows"));
        assertEquals(-0.5, (Double) s.get("deviation"), 1e-9);
        assertEquals(7, s.get("baselineDays"));

        Map<String, Object> steady = runAndGetSignal(params("pipeline", "cdr", "record_day", "2026-08-08",
                "tolerance", "0.6"), SignalType.KPI_COMPLETENESS_EVALUATED);
        assertEquals("STEADY", steady.get("status"), "the tolerance parameter reaches assess");

        Map<String, Object> young = runAndGetSignal(params("pipeline", "cdr", "record_day", "2026-08-08",
                "baseline_window", "3"), SignalType.KPI_COMPLETENESS_EVALUATED);
        assertEquals("NO_BASELINE", young.get("status"), "3 prior days < the default minimum of 7");
        assertFalse(young.containsKey("baselineRows"), "no baseline ⇒ the key is absent, never -1 or 0");
        assertTrue(young.containsKey("deviation") && young.get("deviation") == null);
    }

    @Test
    void aDayWithNothingRegisteredIsNoObservationNotABreach() {
        for (int d = 1; d <= 7; d++) db.record(List.of(out("cdr", "2026-08-0" + d, "/w/" + d + ".parquet", 100)));
        Map<String, Object> s = runAndGetSignal(params("pipeline", "cdr", "record_day", "2026-08-08"),
                SignalType.KPI_COMPLETENESS_EVALUATED);
        assertEquals("NO_OBSERVATION", s.get("status"));
        assertFalse(s.containsKey("rows"));
    }

    /** K4: a breach emits .breached and opens exactly one Incident across three runs (dedupe on "pipeline"). */
    @Test
    void aBreachOpensExactlyOneIncidentAcrossRepeatedRuns() {
        for (int d = 1; d <= 7; d++) db.record(List.of(out("cdr", "2026-08-0" + d, "/w/" + d + ".parquet", 100)));
        db.record(List.of(out("cdr", "2026-08-08", "/w/8.parquet", 50)));
        List<Map<String, String>> open = new java.util.ArrayList<>();
        IncidentAccess incidents = (title, msg, sev, scope, attrs, key) -> {
            // IncidentAccess.over's contract: no dedupe value means no dedupe.
            String v = attrs.get(key);
            if (v != null && open.stream().anyMatch(a -> v.equals(a.get(key)))) return java.util.Optional.empty();
            open.add(attrs);
            return java.util.Optional.of("inc-" + open.size());
        };
        for (int i = 0; i < 3; i++) {
            ServicesContext ctx = new ServicesContext(incidents);
            job(params("pipeline", "cdr", "record_day", "2026-08-08")).run(ctx);
            assertTrue(ctx.inner.signals.stream()
                    .anyMatch(x -> SignalType.KPI_COMPLETENESS_BREACHED.equals(x.get("__type"))));
        }
        assertEquals(1, open.size(), "one open Incident per Pipeline-day, not one per run");
        assertEquals("cdr@2026-08-08", open.get(0).get("pipelineDay"));

        // A different day is a different Incident (dedupe is pipeline + day).
        db.record(List.of(out("cdr", "2026-08-09", "/w/9.parquet", 40)));
        job(params("pipeline", "cdr", "record_day", "2026-08-09")).run(new ServicesContext(incidents));
        assertEquals(2, open.size(), "the next day's breach opens its own Incident");
    }

    /** ⛔ Unknown is not breached: NO_BASELINE emits no .breached and opens no Incident. */
    @Test
    void anUnknownDayOpensNoIncident() {
        db.record(List.of(out("cdr", "2026-08-07", "/w/7.parquet", 100), out("cdr", "2026-08-08", "/w/8.parquet", 1)));
        List<String> opened = new java.util.ArrayList<>();
        ServicesContext ctx = new ServicesContext((t, m, sev, sc, at, k) -> {
            opened.add(t);
            return java.util.Optional.of("x");
        });
        job(params("pipeline", "cdr", "record_day", "2026-08-08")).run(ctx);
        assertTrue(opened.isEmpty());
        assertTrue(ctx.inner.signals.stream()
                .noneMatch(x -> SignalType.KPI_COMPLETENESS_BREACHED.equals(x.get("__type"))));
    }

    @Test
    void declaresTheIncidentsGrantAndBothSignals() throws Exception {
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(), new ConsignmentEventBus(), s, null,
                     "audit", null, null, "data")) {
            JobTypeDescriptor d = js.jobType(KpiCompletenessJob.TYPE).orElseThrow();
            assertEquals(List.of("incidents"), d.requires());
            assertTrue(d.emits().contains(SignalType.KPI_COMPLETENESS_BREACHED));
        }
    }

    /** A {@link CapturingJobContext} that also grants {@link IncidentAccess}. */
    private static final class ServicesContext implements JobContext {
        final CapturingJobContext inner = new CapturingJobContext();
        private final IncidentAccess incidents;

        ServicesContext(IncidentAccess incidents) { this.incidents = incidents; }

        @Override public String runId() { return inner.runId(); }
        @Override public String spaceId() { return inner.spaceId(); }
        @Override public TriggerInfo trigger() { return null; }
        @Override public Map<String, String> config() { return Map.of(); }
        @Override public Map<String, String> params() { return Map.of(); }
        @Override public ArtifactRecorder artifacts() { return null; }
        @Override public com.gamma.util.RunLog log() { return inner.log(); }
        @Override public com.gamma.signal.SignalEmitter signals() { return inner.signals(); }

        @Override public PlatformServices services() {
            return new PlatformServices() {
                @Override public <T> java.util.Optional<T> find(Class<T> type) {
                    return type == IncidentAccess.class ? java.util.Optional.of(type.cast(incidents))
                            : java.util.Optional.empty();
                }
                @Override public java.util.Set<Class<?>> granted() { return java.util.Set.of(IncidentAccess.class); }
            };
        }
    }

    // ── K2: missing files against the Collector's {seq} template (operator, 2026-10-06) ──

    private static final String TEMPLATE = "CDR_{yyyyMMddHH}_{seq}.csv";

    /** A real parsed pipeline whose Collector carries {@code gapBlock} under {@code gap_detection:}. */
    static PipelineConfig pipelineWith(Path dir, String gapBlock) throws Exception {
        Path p = com.gamma.etl.PipelineConfigBatchTest.writePipeline(dir, "");
        Files.writeString(p, Files.readString(p) + "\ncollector:\n  gap_detection:\n" + gapBlock);
        return PipelineConfig.load(p.toString());
    }

    private DbFileStageStore stagesWith(String sourceId, String... names) throws Exception {
        DbFileStageStore st = DbFileStageStore.open("jdbc:duckdb:");
        List<FileStageRecord> rows = new java.util.ArrayList<>();
        for (String n : names) rows.add(new FileStageRecord(sourceId, "in/" + n, "b1", FileStage.REGISTERED,
                "2026-08-04 01:00:00"));
        st.record(rows);
        FileStages.use(st);
        StoreHealth.record(space, KpiCompletenessJob.FILE_FAMILY, StoreHealth.Status.UP, "jdbc:duckdb:", "open");
        return st;
    }

    private KpiCompletenessJob fileJob(PipelineConfig pc, String... extra) {
        Map<String, String> p = params(extra);
        p.put("pipeline", "cdr");
        p.put("record_day", "2026-08-04");
        p.put("check_files", "true");
        return new KpiCompletenessJob(new JobConfig("cdr_completeness", KpiCompletenessJob.TYPE, null, null,
                true, false, p, null, null), name -> "cdr".equals(name) ? Optional.of(pc) : Optional.empty());
    }

    private static Map<String, Object> evaluated(CapturingJobContext ctx) {
        return ctx.signals.stream().filter(s -> SignalType.KPI_COMPLETENESS_EVALUATED.equals(s.get("__type")))
                .findFirst().orElseThrow();
    }

    @Test
    void theCollectorsFileTemplateCountsAnInteriorHole(@TempDir Path dir) throws Exception {
        PipelineConfig pc = pipelineWith(dir, "    file_template: \"" + TEMPLATE + "\"\n    seq_scope: PER_BUCKET\n");
        try (DbFileStageStore st = stagesWith(pc.collector().id(),
                "CDR_2026080400_1.csv", "CDR_2026080400_2.csv", "CDR_2026080400_4.csv")) {
            CapturingJobContext ctx = new CapturingJobContext();
            fileJob(pc).run(ctx);
            Map<String, Object> s = evaluated(ctx);
            assertEquals(TEMPLATE, s.get("fileTemplate"));
            assertEquals(1L, s.get("missingFiles"), "seq 3 is the one interior hole");
            assertEquals(23, s.get("emptyBuckets"), "hours 01..23 are empty buckets, not a file count");
            assertEquals(0L, s.get("unmatchedFiles"));
        } finally {
            FileStages.use(null);
        }
    }

    @Test
    void theJobParameterOverrideWinsOverTheCollector(@TempDir Path dir) throws Exception {
        PipelineConfig pc = pipelineWith(dir, "    file_template: \"WRONG_{yyyyMMddHH}_{seq}.csv\"\n    seq_scope: CONTINUOUS\n");
        try (DbFileStageStore st = stagesWith(pc.collector().id(),
                "CDR_2026080400_1.csv", "CDR_2026080400_2.csv", "CDR_2026080400_4.csv")) {
            CapturingJobContext ctx = new CapturingJobContext();
            fileJob(pc, "sequence_template", TEMPLATE, "seq_scope", "PER_BUCKET").run(ctx);
            Map<String, Object> s = evaluated(ctx);
            assertEquals(TEMPLATE, s.get("fileTemplate"));
            assertEquals("PER_BUCKET", s.get("seqScope"));
            assertEquals(1L, s.get("missingFiles"));
            assertEquals(0L, s.get("unmatchedFiles"), "the Collector's WRONG_ template was not used");
        } finally {
            FileStages.use(null);
        }
    }

    /** gap_detection.sequence (the live detector's one-token template) is NOT a file template. */
    @Test
    void noFileTemplateAnywhereRefusesNamingTheSetting(@TempDir Path dir) throws Exception {
        PipelineConfig pc = pipelineWith(dir, "    enabled: true\n    sequence: \"CDR_{yyyyMMddHH}\"\n");
        try (DbFileStageStore st = stagesWith(pc.collector().id(), "CDR_2026080400_1.csv")) {
            CapturingJobContext ctx = new CapturingJobContext();
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> fileJob(pc).run(ctx));
            assertTrue(e.getMessage().contains("collector.gap_detection.file_template"), e.getMessage());
            assertTrue(e.getMessage().contains("check_files: false"), "names the opt-out: " + e.getMessage());
            assertTrue(ctx.signals.isEmpty(), "a refusal emits no number");
        } finally {
            FileStages.use(null);
        }
    }

    @Test
    void fileStagesOffRefusesNamingTheToggle(@TempDir Path dir) throws Exception {
        PipelineConfig pc = pipelineWith(dir, "    file_template: \"" + TEMPLATE + "\"\n    seq_scope: PER_BUCKET\n");
        // A WORKING store is installed, so only the StoreHealth refusal can stop the run (negative probe).
        try (DbFileStageStore st = stagesWith(pc.collector().id(), "CDR_2026080400_1.csv")) {
            StoreHealth.record(space, KpiCompletenessJob.FILE_FAMILY, StoreHealth.Status.NOT_CONFIGURED, "none", "default off");
            CapturingJobContext ctx = new CapturingJobContext();
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> fileJob(pc).run(ctx));
            assertTrue(e.getMessage().contains("-Dfile.stages.backend"), e.getMessage());
            assertTrue(ctx.signals.isEmpty());
        } finally {
            FileStages.use(null);
        }
    }

    @Test
    void anUnknownPipelineRefusesTheFileHalf() {
        CapturingJobContext ctx = new CapturingJobContext();
        assertThrows(IllegalStateException.class, () -> new KpiCompletenessJob(new JobConfig("x",
                KpiCompletenessJob.TYPE, null, null, true, false,
                params("pipeline", "ghost", "record_day", "2026-08-04", "check_files", "true"), null, null),
                n -> Optional.empty()).run(ctx));
    }

    @Test
    void theParserRefusesAFileTemplateWithoutSeqScope(@TempDir Path dir) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> pipelineWith(dir, "    file_template: \"" + TEMPLATE + "\"\n"));
        assertTrue(e.getMessage().contains("seq_scope"), e.getMessage());
    }

    /** §7-g: a file gap alone (volume STEADY) opens the Pipeline-day Incident, carrying the file finding. */
    @Test
    void aFileGapAloneOpensTheIncident(@TempDir Path dir) throws Exception {
        for (int d = 1; d <= 4; d++) db.record(List.of(out("cdr", "2026-08-0" + d, "/w/" + d + ".parquet", 100)));
        PipelineConfig pc = pipelineWith(dir, "    file_template: \"" + TEMPLATE + "\"\n    seq_scope: CONTINUOUS\n");
        List<Map<String, String>> opened = new java.util.ArrayList<>();
        try (DbFileStageStore st = stagesWith(pc.collector().id(),
                "CDR_2026080400_1.csv", "CDR_2026080401_2.csv", "CDR_2026080402_4.csv")) {
            ServicesContext ctx = new ServicesContext((t, m, sev, sc, at, k) -> {
                opened.add(at);
                return Optional.of("x");
            });
            KpiCompletenessJob j = fileJob(pc, "min_baseline_days", "3");
            j.run(ctx);
            assertEquals("STEADY", ctx.inner.signals.get(0).get("status"));
        } finally {
            FileStages.use(null);
        }
        assertEquals(1, opened.size(), "a file gap with fine volume still opens the Incident");
        assertEquals("1", opened.get(0).get("missingFiles"));
        assertEquals("cdr@2026-08-04", opened.get(0).get("pipelineDay"));
    }

    /** §7-h: three consecutive days with nothing registered raise a WARN signal and open no Incident. */
    @Test
    void threeUnknownDaysRaiseAStreakWarning() {
        db.record(List.of(out("cdr", "2026-08-01", "/w/1.parquet", 100)));
        List<String> opened = new java.util.ArrayList<>();
        ServicesContext ctx = new ServicesContext((t, m, sev, sc, at, k) -> {
            opened.add(t);
            return Optional.of("x");
        });
        job(params("pipeline", "cdr", "record_day", "2026-08-04")).run(ctx);
        assertTrue(ctx.inner.signals.stream()
                .anyMatch(x -> SignalType.KPI_COMPLETENESS_UNKNOWN_STREAK.equals(x.get("__type"))));
        assertTrue(opened.isEmpty(), "an unknown streak is a WARN, never an Incident");

        ServicesContext two = new ServicesContext((t, m, sev, sc, at, k) -> Optional.of("x"));
        job(params("pipeline", "cdr", "record_day", "2026-08-03")).run(two);
        assertTrue(two.inner.signals.stream()
                .noneMatch(x -> SignalType.KPI_COMPLETENESS_UNKNOWN_STREAK.equals(x.get("__type"))),
                "two unknown days are below the threshold");
    }
}
