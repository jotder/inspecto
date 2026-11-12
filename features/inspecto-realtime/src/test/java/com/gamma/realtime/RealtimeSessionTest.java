package com.gamma.realtime;

import com.gamma.alert.AlertRule;
import com.gamma.alert.AlertService;
import com.gamma.alert.RealtimeDetection;
import com.gamma.etl.ConsignmentEvent;
import com.gamma.etl.EditionFeatures;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.metrics.MetricRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.SemanticModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PA-9d: {@link RealtimeSession}, the commit hook over real Parquet - only a producer's own committed files are evaluated;
 * a Consignment committed but never evaluated is replayed at the next build (owed by PA-9b: a plain {@code load()} with
 * {@code distinctOn} buffers it and never judges it); files committed before the group was armed are history; without
 * the Add-on nothing is evaluated, and when it returns what committed meanwhile is replayed; the clock floor; the metrics.
 */
class RealtimeSessionTest {

    private static final long T0 = 1_794_000_000_000L;   // epoch ms, aligned to the hour
    private static final String PIPELINE = "CDR_STREAM";

    @TempDir Path tmp;
    private Path db, registry, state;
    private PipelineConfig pipeline;
    private int files;

    @BeforeEach
    void space() throws Exception {
        EditionFeatures.overrideForTest(Set.of(EditionFeatures.ALERT_DISPATCH, EditionFeatures.REALTIME));
        db = Files.createDirectories(tmp.resolve("db"));
        registry = tmp.resolve("config").resolve("registry");
        state = tmp.resolve("state");
        new ComponentStore(registry).write("dataset", "voice_cdr", new HashMap<>(Map.of("physicalRef", "cdr_stream")));
        pipeline = PipelineConfig.load(writePipeline(tmp.resolve("pipelines"), PIPELINE, db).toString());
    }

    @AfterEach
    void restore() {
        EditionFeatures.overrideForTest(null);
    }

    static Path writePipeline(Path dir, String name, Path database) throws Exception {
        Files.createDirectories(dir);
        Path schema = dir.resolve("schema.toon");
        Files.writeString(schema, PipelineConfigBatchTest.miniSchema());
        String d = dir.toString().replace('\\', '/');
        String toon = """
                name: %1$s
                active: true
                trigger:
                  type: stream
                  records: 500
                  max_wait: 1s
                dirs:
                  poll: %2$s/inbox
                  database: %3$s
                  backup: %2$s/backup
                  temp: %2$s/temp
                  quarantine: %2$s/quarantine
                  markers: %2$s/markers
                  status_dir: %2$s/status
                  log_dir: %2$s/logs
                output:
                  format: PARQUET
                processing:
                  threads: 1
                  batch:
                    max_files: 64
                  file_pattern: "glob:**/*.csv"
                  schema_file: "%4$s"
                  csv_settings:
                    delimiter: ","
                    has_header: true
                """.formatted(name, d, database.toString().replace('\\', '/'), schema.toString().replace('\\', '/'));
        Path p = dir.resolve(name.toLowerCase() + "_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }

    /** One CDR, read from (topic, partition, offset). */
    record Cdr(String a, String b, long tsMs, long offset) {}

    /** {@code n} calls from {@code a} to distinct B-numbers, one second apart. */
    static List<Cdr> burst(String a, int n, long startMs, long firstOffset) {
        List<Cdr> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new Cdr(a, a + "-b" + firstOffset + "-" + i, startMs + i * 1000L, firstOffset + i));
        return out;
    }

    /** A committed file in the Dataset's day folder. */
    private Path commit(List<Cdr> rows) throws Exception {
        Path dir = Files.createDirectories(db.resolve("year=2026").resolve("month=11").resolve("day=06"));
        Path f = dir.resolve("part-" + (files++) + ".parquet");
        StringBuilder values = new StringBuilder();
        for (Cdr c : rows) {
            if (values.length() > 0) values.append(", ");
            values.append("('").append(c.a).append("', '").append(c.b).append("', 1.0, make_timestamp(").append(c.tsMs * 1000)
                    .append("), 'cdr', 0, ").append(c.offset).append(")");
        }
        try (Connection d = DriverManager.getConnection("jdbc:duckdb:"); Statement st = d.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + values + ") AS t(a_number, b_number, duration_s, event_ts, topic, "
                    + "\"partition\", \"offset\")) TO '" + f.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        return f;
    }

    static ConsignmentEvent event(String pipeline, String status, boolean dry, Path... outputs) {
        List<String> out = new ArrayList<>();
        for (Path p : outputs) out.add(p.toString());
        return new ConsignmentEvent(pipeline, "c" + System.nanoTime(), status, List.of(), 1, 1, 0, null, null, 0, dry, out);
    }

    static AlertRule wangiri(String name, String severity, int threshold) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("name", name);
        raw.put("dataset", "voice_cdr");
        raw.put("measure", "countDistinct(b_number)");
        raw.put("by", List.of("a_number"));
        raw.put("comparator", "gte");
        raw.put("threshold", threshold);
        raw.put("severity", severity);
        raw.put("window", Map.of("type", "tumbling", "size", "10m", "time", "event_ts",
                "distinctOn", List.of("topic", "partition", "offset")));
        return AlertRule.fromMap(raw);
    }

    static AlertRule silentKey() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("name", "silent-a-number");
        raw.put("dataset", "voice_cdr");
        raw.put("by", List.of("a_number"));
        raw.put("severity", "WARNING");
        raw.put("window", Map.of("type", "tumbling", "size", "5m", "time", "event_ts",
                "distinctOn", List.of("topic", "partition", "offset")));
        raw.put("pattern", Map.of("type", "absence"));
        return AlertRule.fromMap(raw);
    }

    static AlertService alerts(List<AlertRule> rules) {
        ConfigSource none = new ConfigSource() {
            @Override public List<PipelineConfig> pipelines() { return List.of(); }
            @Override public List<EnrichmentConfig> enrichments() { return List.of(); }
            @Override public List<SemanticModel> semantics() { return List.of(); }
        };
        return new AlertService(rules, none, null);
    }

    private RealtimeSession session(AlertService alerts, List<RealtimeSession.Raised> raised) {
        RealtimeSession s = new RealtimeSession(new RealtimeDetection.Context("s1", alerts, () -> List.of(pipeline),
                () -> registry, state));
        s.onRaised(raised::add);
        return s;
    }

    /** Wait for the session's worker: status() runs behind every queued task. */
    private static Map<String, Object> settle(RealtimeSession s) {
        return s.status();
    }

    private static Set<String> keys(List<RealtimeSession.Raised> raised) {
        Set<String> out = new java.util.TreeSet<>();
        for (RealtimeSession.Raised r : raised) out.add(r.keyId());
        return out;
    }

    @Test
    void onlyAProducersSuccessfulCommitIsEvaluatedAndOnlyItsOwnFiles() throws Exception {
        AlertService alerts = alerts(List.of(wangiri("wangiri", "WARNING", 3)));
        List<RealtimeSession.Raised> raised = new CopyOnWriteArrayList<>();
        try (RealtimeSession s = session(alerts, raised)) {
            settle(s);
            Path burst = commit(burst("A1", 3, T0, 0));
            s.onCommit(event("OTHER_PIPELINE", "SUCCESS", false, burst));
            s.onCommit(event(PIPELINE, "FAILED", false, burst));
            s.onCommit(event(PIPELINE, "SUCCESS", true, burst));
            s.onCommit(event(PIPELINE, "SUCCESS", false, tmp.resolve("elsewhere.parquet")));   // not under the Dataset
            settle(s);
            assertTrue(raised.isEmpty(), "another Pipeline, a failed batch, a dry run, a file outside the Dataset: " + raised);
            assertTrue(alerts.openAlerts().isEmpty());

            s.onCommit(event(PIPELINE, "SUCCESS", false, burst));
            Map<String, Object> st = settle(s);
            assertEquals(Set.of("a_number=A1"), keys(raised), st.toString());
            assertFalse(raised.get(0).replay());
            assertEquals(1, alerts.openAlerts().size(), "one Alert for the rule");
            assertEquals(3L, ((Map<?, ?>) st.get("voice_cdr")).get("bufferedRows"));
        }
    }

    @Test
    void aConsignmentCommittedButNeverEvaluatedIsReplayedAtTheNextBuild() throws Exception {
        List<AlertRule> rules = List.of(wangiri("wangiri", "WARNING", 3));
        List<RealtimeSession.Raised> raised = new CopyOnWriteArrayList<>();
        try (RealtimeSession s = session(alerts(rules), raised)) {
            settle(s);   // arms the group: what follows is not history
            s.onCommit(event(PIPELINE, "SUCCESS", false, commit(burst("A1", 2, T0, 0))));
            s.onCommit(event(PIPELINE, "SUCCESS", false, commit(burst("A2", 3, T0, 100))));
            settle(s);
        }
        assertEquals(Set.of("a_number=A2"), keys(raised));
        // committed while the server was down: never evaluated. With distinctOn, a plain load() would buffer it silently.
        commit(burst("A1", 2, T0 + 60_000, 10));

        raised.clear();
        AlertService afterRestart = alerts(rules);
        try (RealtimeSession s = session(afterRestart, raised)) {
            s.onStreamIdle(PIPELINE);   // any evaluation builds the group first
            settle(s);
            assertEquals(Set.of("a_number=A1"), keys(raised), "the unevaluated Consignment is replayed; A2 does not fire twice");
            assertTrue(raised.get(0).replay());
            raised.clear();
        }
        // without the fired table, only the evaluated ledger can stop a second replay
        Files.delete(state.resolve("fired.duckdb"));
        try (RealtimeSession s = session(alerts(rules), raised)) {
            s.onStreamIdle(PIPELINE);
            settle(s);
            assertTrue(raised.isEmpty(), "a replayed file is recorded in the ledger: the next build replays nothing " + raised);
        }
    }

    @Test
    void filesCommittedBeforeTheGroupWasArmedAreHistoryNotReplayed() throws Exception {
        commit(burst("A9", 5, T0, 0));   // committed before any windowed rule existed
        Thread.sleep(20);
        List<RealtimeSession.Raised> raised = new CopyOnWriteArrayList<>();
        try (RealtimeSession s = session(alerts(List.of(wangiri("wangiri", "WARNING", 3))), raised)) {
            s.onStreamIdle(PIPELINE);
            Map<String, Object> st = settle(s);
            assertTrue(raised.isEmpty(), "history is loaded, never judged: " + raised);
            assertEquals(5L, ((Map<?, ?>) st.get("voice_cdr")).get("bufferedRows"), "but it is in the buffer");
            // and it counts toward a Window a new event touches
            s.onCommit(event(PIPELINE, "SUCCESS", false, commit(List.of(new Cdr("A9", "late-b", T0 + 9000, 50)))));
            settle(s);
            assertEquals(Set.of("a_number=A9"), keys(raised));
        }
    }

    @Test
    void withoutTheAddOnNothingIsEvaluatedAndWhenItReturnsTheMeantimeIsReplayed() throws Exception {
        AlertService alerts = alerts(List.of(wangiri("wangiri", "WARNING", 3)));
        List<RealtimeSession.Raised> raised = new CopyOnWriteArrayList<>();
        try (RealtimeSession s = session(alerts, raised)) {
            settle(s);
            s.onStreamIdle(PIPELINE);
            settle(s);
            EditionFeatures.overrideForTest(Set.of(EditionFeatures.ALERT_DISPATCH));   // the entitlement drops the Add-on
            Path meantime = commit(burst("A1", 3, T0, 0));
            s.onCommit(event(PIPELINE, "SUCCESS", false, meantime));
            settle(s);
            assertTrue(raised.isEmpty(), "inert without the Add-on");
            assertTrue(alerts.openAlerts().isEmpty());

            EditionFeatures.overrideForTest(Set.of(EditionFeatures.ALERT_DISPATCH, EditionFeatures.REALTIME));
            s.onCommit(event(PIPELINE, "SUCCESS", false, commit(burst("B7", 1, T0, 500))));
            settle(s);
            assertEquals(Set.of("a_number=A1"), keys(raised), "the rebuild replays what committed while it was inert");
        }
    }

    @Test
    void aRuleWhoseDatasetNoStreamWritesIsInertWithItsReason() throws Exception {
        new ComponentStore(registry).write("dataset", "voice_cdr", new HashMap<>(Map.of("physicalRef", "orphan")));
        try (RealtimeSession s = session(alerts(List.of(wangiri("wangiri", "WARNING", 3))), new ArrayList<>())) {
            s.onStreamIdle(PIPELINE);
            Map<?, ?> inert = (Map<?, ?>) settle(s).get("inert");
            assertEquals("no stream-triggered Pipeline of this Space writes 'orphan' (dataset 'voice_cdr')", inert.get("wangiri"));
        }
    }

    @Test
    void theClockFloorJudgesAbsenceWhileTheStreamIsDrained() throws Exception {
        List<RealtimeSession.Raised> raised = new CopyOnWriteArrayList<>();
        long now = System.currentTimeMillis();
        long t = now - now % 300_000 - 20 * 60_000;   // a 5-minute boundary, 20 minutes ago
        try (RealtimeSession s = session(alerts(List.of(silentKey())), raised)) {
            settle(s);
            s.onCommit(event(PIPELINE, "SUCCESS", false, commit(List.of(new Cdr("SW1", "x", t + 60_000, 1)))));
            settle(s);
            assertTrue(raised.isEmpty(), "the Window after the last event has not closed on event time");
            s.onStreamIdle("SOME_OTHER_PIPELINE");
            settle(s);
            assertTrue(raised.isEmpty(), "only the producer's lane is this Dataset's clock");
            s.onStreamIdle(PIPELINE);
            settle(s);
            assertEquals(Set.of("a_number=SW1"), keys(raised), "the clock closed the silent Window");
        }
    }

    @Test
    void theLatencyAndLateEventMetricsAreRecordedPerRule() throws Exception {
        AlertService alerts = alerts(List.of(wangiri("metric-rule", "WARNING", 3)));
        String latency = "inspecto_detection_latency_seconds_count{rule=\"metric-rule\",space=\"s1\"}";
        String late = "inspecto_detection_late_events_total{rule=\"metric-rule\",space=\"s1\"}";
        double before = scraped(latency), lateBefore = scraped(late);
        long now = System.currentTimeMillis();
        long t = now - now % 600_000;
        try (RealtimeSession s = session(alerts, new ArrayList<>())) {
            settle(s);
            s.onCommit(event(PIPELINE, "SUCCESS", false, commit(burst("A1", 3, t, 0))));
            settle(s);
            assertEquals(before + 1, scraped(latency), MetricRegistry.global().scrape());
            // the Watermark moves an hour on; an event in the first Window is late: stored, counted, not alerted on
            s.onCommit(event(PIPELINE, "SUCCESS", false, commit(List.of(new Cdr("Z1", "z", t + 3_600_000, 10)))));
            s.onCommit(event(PIPELINE, "SUCCESS", false, commit(List.of(new Cdr("A5", "y", t + 1000, 11)))));
            settle(s);
            assertEquals(lateBefore + 1, scraped(late), MetricRegistry.global().scrape());
        }
    }

    /** The value of one scraped series, 0 when absent. */
    private static double scraped(String series) {
        for (String line : MetricRegistry.global().scrape().split("\n"))
            if (line.startsWith(series + " ")) return Double.parseDouble(line.substring(series.length() + 1).trim());
        return 0;
    }
}
