package com.gamma.alert;

import com.gamma.audit.Event;
import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.query.IndexLagProbe;
import com.gamma.signal.DatasetWriteSignal;
import com.gamma.signal.Ref;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import com.gamma.signal.SignalType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LA-DATA-TRUST-1 S4: (a) Files waiting for the Link Analysis index longer than a limit - an Alert Rule with
 * {@code indexLag: true} on the freshness clock, fed by {@link IndexLagProbe}; (b) a refused / failed
 * {@code la.index.build} - raised from its Signal by {@link EventAlertBridge}. Time is passed in, never slept for.
 */
class IndexLagAlertTest {

    private static final long MIN = 60_000L;

    private static Event signal(String type, Map<String, Object> payload, long ts) {
        return new Signal(null, type, Instant.ofEpochMilli(ts), Severity.INFO, Ref.of("dataset", "xdr"),
                Ref.of("dataset", "xdr"), null, null, null, null, type, payload, 1).toEvent();
    }

    private static Event written(long ts) {
        return signal(DatasetWriteSignal.TYPE, Map.of("dataset", "xdr", "rows", 10L), ts);
    }

    private static Event built(String result, long ts) {
        return signal(SignalType.LA_INDEX_BUILD_COMPLETED,
                Map.of("job", "xdr-index", "dataset", "xdr", "result", result, "mode", "append"), ts);
    }

    private static Event ended(Map<String, Object> payload, long ts) {
        return signal(SignalType.LA_INDEX_BUILD_COMPLETED, payload, ts);
    }

    private static AlertRule lagRule(String maximumAge) {
        return AlertRule.fromMap(Map.of("name", "xdr-index-lag", "dataset", "xdr", "maximumAge", maximumAge,
                "indexLag", true, "severity", "warning"));
    }

    private static AlertService service(AlertStore store, AlertRule... rules) {
        ConfigSource none = new ConfigSource() {
            @Override public List<PipelineConfig> pipelines() { return List.of(); }
            @Override public List<EnrichmentConfig> enrichments() { return List.of(); }
            @Override public List<SemanticModel> semantics() { return List.of(); }
        };
        return new AlertService(List.of(rules), none, null,
                AlertRecords.of(store, Optional.empty()), AlertRecords.NO_INCIDENTS);
    }

    // ── the rule shape ───────────────────────────────────────────────────────────────

    @Test
    void anIndexLagRuleIsAFreshnessRuleThatRoundTripsAndRefusesWhatItCannotMean() {
        AlertRule r = lagRule("30m");
        assertTrue(r.isFreshnessRule(), "so the minute sweep arms for it with no new wiring");
        assertTrue(r.indexLag());
        assertEquals(true, r.toMap().get("indexLag"));
        assertFalse(AlertRule.fromMap(Map.of("name", "n", "dataset", "xdr", "maximumAge", "1h")).indexLag());
        assertThrows(IllegalArgumentException.class, () -> AlertRule.fromMap(
                Map.of("name", "n", "dataset", "xdr", "indexLag", true)), "no limit to wait against");
        assertThrows(IllegalArgumentException.class, () -> AlertRule.fromMap(
                Map.of("name", "n", "dataset", "xdr", "maximumAge", "auto", "onPipeline", "p", "indexLag", true)));
        assertThrows(IllegalArgumentException.class, () -> AlertRule.fromMap(
                Map.of("name", "n", "dataset", "xdr", "maximumAge", "1h", "indexLag", "maybe")));
    }

    // ── (a) the wait, and the Alert on it ────────────────────────────────────────────

    @Test
    void filesWaitingLongerThanTheLimitFireAndABuildClearsIt() {
        IndexLagProbe probe = new IndexLagProbe();
        Consumer<Event> bus = probe.subscriber();
        AlertService svc = service(new InMemoryAlertStore(), lagRule("30m"));
        svc.indexLagProbe(probe);

        assertTrue(svc.evaluate(null, 100 * MIN).isEmpty(), "nothing waiting: silent");
        assertEquals("fresh", svc.freshnessVerdicts("xdr", 100 * MIN).get(0).state());

        bus.accept(written(100 * MIN));                                  // a File lands, the index does not cover it
        assertTrue(svc.evaluate(null, 120 * MIN).isEmpty(), "waited 20 min < 30 min limit");
        List<Alert> fired = svc.evaluate(null, 140 * MIN);               // waited 40 min
        assertEquals(1, fired.size());
        assertEquals("index-lag", fired.get(0).metric());
        assertTrue(fired.get(0).message().contains("Files waiting for the Link Analysis index"), fired.get(0).message());
        AlertService.FreshnessVerdict v = svc.freshnessVerdicts("xdr", 140 * MIN).get(0);
        assertEquals("index-lag", v.kind());
        assertEquals("stale", v.state());
        assertEquals(2400L, v.ageSeconds());
        assertEquals(1800L, v.limitSeconds());

        bus.accept(built("BUILT", 141 * MIN));                           // the index catches up
        assertTrue(svc.evaluate(null, 142 * MIN).isEmpty());
        assertEquals("fresh", svc.freshnessVerdicts("xdr", 142 * MIN).get(0).state(), "cleared by the build");
    }

    /** A polled Collector commits Files and emits pipeline.commit, never dataset.write (found in the 2026-10-14 live drive). */
    @Test
    void aPolledCollectorCommitStartsTheWaitAndABuildAfterItEndsIt() {
        IndexLagProbe probe = new IndexLagProbe();
        Consumer<Event> bus = probe.subscriber();
        AlertRule rule = AlertRule.fromMap(Map.of("name", "xdr-index-lag", "dataset", "xdr", "onPipeline", "xdr_feed",
                "maximumAge", "30m", "indexLag", true, "severity", "warning"));
        AlertService svc = service(new InMemoryAlertStore(), rule);
        svc.indexLagProbe(probe);

        bus.accept(signal(SignalType.PIPELINE_COMMIT, Map.of("pipeline", "other_feed", "status", "SUCCESS"), 100 * MIN));
        bus.accept(signal(SignalType.PIPELINE_COMMIT, Map.of("pipeline", "xdr_feed", "status", "FAILED"), 100 * MIN));
        bus.accept(signal(SignalType.PIPELINE_COMMIT,
                Map.of("pipeline", "xdr_feed", "status", "SUCCESS", "dryRun", true), 100 * MIN));
        assertTrue(svc.evaluate(null, 200 * MIN).isEmpty(), "another Pipeline, a failure and a dry run start no wait");

        bus.accept(signal(SignalType.PIPELINE_COMMIT, Map.of("pipeline", "xdr_feed", "status", "SUCCESS"), 100 * MIN));
        assertEquals(1, svc.evaluate(null, 140 * MIN).size(), "waited 40 min > 30 min limit");
        bus.accept(built("BUILT", 141 * MIN));
        assertEquals("fresh", svc.freshnessVerdicts("xdr", 142 * MIN).get(0).state(), "a build after the commit covers it");
        bus.accept(signal(SignalType.PIPELINE_COMMIT, Map.of("pipeline", "xdr_feed", "status", "SUCCESS"), 150 * MIN));
        assertEquals(150 * MIN, probe.apply("xdr", "xdr_feed").getAsLong(), "a later commit starts a new wait");
    }

    @Test
    void aFreshnessRuleAgesTheNewerOfDatasetWriteAndPolledCommit() {
        AlertRule rule = AlertRule.fromMap(Map.of("name", "xdr-fresh", "dataset", "xdr", "onPipeline", "xdr_feed",
                "maximumAge", "30m", "severity", "warning"));
        AlertService svc = service(new InMemoryAlertStore(), rule);
        com.gamma.query.PipelineCommitProbe commits = new com.gamma.query.PipelineCommitProbe(null);
        svc.pipelineCommitProbe(commits);
        svc.freshnessProbe(d -> java.util.OptionalLong.empty());
        assertEquals("unknown", svc.freshnessVerdicts("xdr", 100 * MIN).get(0).state(), "no commit seen: never fresh");

        commits.subscriber().accept(signal(SignalType.PIPELINE_COMMIT, Map.of("pipeline", "xdr_feed", "status", "SUCCESS"), 100 * MIN));
        assertEquals("fresh", svc.freshnessVerdicts("xdr", 110 * MIN).get(0).state());
        assertEquals("stale", svc.freshnessVerdicts("xdr", 140 * MIN).get(0).state());
        assertEquals(1, svc.evaluate(null, 140 * MIN).size(), "a stopped feed fires");
    }

    @Test
    void aRefusedBuildCoversNothingAndTheWaitIsMeasuredFromTheOldestFile() {
        IndexLagProbe probe = new IndexLagProbe();
        Consumer<Event> bus = probe.subscriber();
        bus.accept(written(10 * MIN));
        bus.accept(written(20 * MIN));                                   // a feed that keeps delivering...
        bus.accept(built("REFUSED", 25 * MIN));
        assertEquals(10 * MIN, probe.apply("xdr").getAsLong(), "...must not keep resetting the clock; a refusal covers nothing");
        bus.accept(built("BUILT", 30 * MIN));
        assertTrue(probe.apply("xdr").isEmpty(), "everything up to the build is covered");
        bus.accept(written(40 * MIN));
        assertEquals(40 * MIN, probe.apply("xdr").getAsLong(), "a later File starts a new wait");
        assertTrue(probe.apply("other").isEmpty());
    }

    @Test
    void noProbeWiredIsUnknownNotFresh() {
        AlertService svc = service(new InMemoryAlertStore(), lagRule("30m"));
        assertTrue(svc.evaluate(null, 999 * MIN).isEmpty());
        assertEquals("unknown", svc.freshnessVerdicts("xdr", 999 * MIN).get(0).state());
    }

    // ── (b) a refused or failed build ────────────────────────────────────────────────

    @Test
    void aRefusedOrFailedBuildRaisesOneAlertCarryingTheCodeAndAnOkBuildResolvesIt() {
        InMemoryAlertStore store = new InMemoryAlertStore();
        EventAlertBridge bridge = new EventAlertBridge(service(store));

        for (String code : List.of("OVER_BUDGET", "BUILD_IN_PROGRESS", "FULL_NOT_ALLOWED", "BUSY"))
            bridge.onEvent(ended(Map.of("job", "xdr-index", "dataset", "xdr", "result", "REFUSED", "mode", "append",
                    "code", code, "edges", 0L, "nodes", 0L, "deltas", 0), 5 * MIN));
        List<AlertStore.Row> open = store.active("xdr-index");
        assertEquals(1, open.size(), "one Alert per index-build Job, not one per refusal");
        AlertStore.Row a = open.get(0);
        assertEquals(EventAlertBridge.INDEX_BUILD_RULE, a.attributes().get("rule"));
        assertEquals("REFUSED", a.attributes().get("result"));
        assertTrue(a.title().contains("xdr-index") && a.title().contains("refused"), a.title());

        bridge.onEvent(ended(Map.of("job", "xdr-index", "dataset", "xdr", "result", "FAILED", "code", "BUILD_FAILED"), 6 * MIN));
        assertEquals(1, store.active("xdr-index").size(), "a failure while one is open is deduplicated too");

        bridge.onEvent(built("UP_TO_DATE", 9 * MIN));
        assertTrue(store.active("xdr-index").isEmpty(), "an ok build resolves it");
        bridge.onEvent(ended(Map.of("job", "xdr-index", "result", "FAILED", "code", "X"), 10 * MIN));
        assertEquals(1, store.active("xdr-index").size(), "and a later failure raises a fresh one");
    }

    @Test
    void runningDryRunAndForeignSignalsNeitherRaiseNorResolve() {
        InMemoryAlertStore store = new InMemoryAlertStore();
        EventAlertBridge bridge = new EventAlertBridge(service(store));
        bridge.onEvent(ended(Map.of("job", "xdr-index", "result", "FAILED", "code", "X"), MIN));
        bridge.onEvent(ended(Map.of("job", "xdr-index", "result", "RUNNING"), 2 * MIN));
        bridge.onEvent(ended(Map.of("job", "xdr-index", "result", "DRY_RUN"), 3 * MIN));
        bridge.onEvent(written(4 * MIN));
        assertEquals(1, store.active("xdr-index").size(), "still the one raised by the failure");
        bridge.onEvent(ended(Map.of("result", "FAILED"), 5 * MIN));
        assertTrue(store.active("").isEmpty(), "a Signal with no job names nothing to alert on");
    }
}
