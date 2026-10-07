package com.gamma.alert;

import com.gamma.audit.Event;
import com.gamma.audit.EventLog;
import com.gamma.audit.EventType;
import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.StatusStore;
import com.gamma.objects.FakeObjectAccess;
import com.gamma.objects.IncidentAccess;
import com.gamma.query.DatasetMeasureProbe;
import com.gamma.workflow.ObjectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MODULE-REORG-P7-INCIDENTS slice 1 — the Personal <b>events-only</b> characterisation of {@link AlertService}
 * (what it does with no operational-object module: in-memory fired ring, ALERT_FIRED event, Signal, no ALERT /
 * INCIDENT record, dedupe by in-process state that a restart forgets) pinned FIRST, then run against the
 * object-backed wiring to prove the {@link AlertRecords} split changed nothing a caller can observe.
 *
 * <p>Negative-test discipline: every "no object was touched" claim in the events-only half has a twin in the
 * object-backed half where the very same scenario DOES write objects ({@link FakeObjectAccess#opened}) — the
 * probe would otherwise succeed, so a removed null-object / re-introduced write is caught on one side or the other.
 */
class AlertRecordsParityTest {

    private static final long HOUR = 3_600_000L;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final List<Consumer<Event>> subscribed = new ArrayList<>();
    private final List<Event> seen = new CopyOnWriteArrayList<>();

    @org.junit.jupiter.api.BeforeEach
    void listen() {
        Consumer<Event> s = seen::add;
        subscribed.add(s);
        EventLog.current().addSubscriber(s);
    }

    @AfterEach
    void unlisten() {
        subscribed.forEach(EventLog.current()::removeSubscriber);
        subscribed.clear();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────

    private static ConfigSource configs(PipelineConfig cfg) {
        return new ConfigSource() {
            @Override public List<PipelineConfig> pipelines() { return cfg == null ? List.of() : List.of(cfg); }
            @Override public List<EnrichmentConfig> enrichments() { return List.of(); }
            @Override public List<SemanticModel> semantics() { return List.of(); }
        };
    }

    private static StatusStore store(List<Map<String, String>> batches) {
        return new StatusStore() {
            @Override public Set<String> committedBatches(PipelineConfig cfg) { return Set.of(); }
            @Override public List<Map<String, String>> batches(PipelineConfig cfg) { return batches; }
            @Override public List<Map<String, String>> files(PipelineConfig cfg) { return List.of(); }
            @Override public List<Map<String, String>> lineage(PipelineConfig cfg, String b) { return List.of(); }
            @Override public List<Map<String, String>> quarantine(PipelineConfig cfg) { return List.of(); }
        };
    }

    private static List<Map<String, String>> failedLedger() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("status", "FAILED");
        m.put("total_input_rows", "10");
        m.put("total_output_rows", "0");
        m.put("rejected_count", "0");
        m.put("duration_ms", "100");
        m.put("end_time", TS.format(LocalDateTime.now().minusMinutes(5)));
        return List.of(m);
    }

    private static AlertRule ledgerRule(String severity) {
        return new AlertRule("r-failed", "failed_batches", "gte", 1, "1h", severity, "MINI_ETL");
    }

    private static AlertRule measureRule(String severity) {
        return new AlertRule("low-revenue", null, "lt", 1000, null, severity, null, "sales_ds", "sum(amount)");
    }

    private static AlertRule freshnessRule(String severity) {
        return new AlertRule("sales-stale", null, null, 0, null, severity, null, "sales_ds", null, null, "1h");
    }

    private static AlertRule byRule(String severity) {
        return AlertRule.fromMap(Map.of("name", "high-spend", "dataset", "usage", "measure", "sum(amount)",
                "by", List.of("msisdn"), "stormCap", 100, "comparator", "gt", "threshold", 500,
                "severity", severity));
    }

    private static DatasetMeasureProbe.Breaches breaching(String... msisdns) {
        List<DatasetMeasureProbe.Breach> keys = new ArrayList<>();
        for (String m : msisdns) keys.add(new DatasetMeasureProbe.Breach(Map.of("msisdn", m), 1000.0));
        return new DatasetMeasureProbe.Breaches(keys, keys.size());
    }

    /** The two wirings under test. */
    private enum Wiring { EVENTS_ONLY, OBJECT_BACKED }

    private static AlertService service(Wiring w, FakeObjectAccess objects, PipelineConfig cfg,
                                        StatusStore status, AlertRule... rules) {
        return w == Wiring.EVENTS_ONLY
                ? new AlertService(List.of(rules), configs(cfg), status, (com.gamma.objects.ObjectAccess) null)
                : new AlertService(List.of(rules), configs(cfg), status, objects);
    }

    private long events(String type, String rule) {
        return seen.stream().filter(e -> type.equals(e.type()) && rule.equals(e.attributes().get("rule"))).count();
    }

    private static long count(FakeObjectAccess o, ObjectType kind) {
        return o.opened.stream().filter(x -> x.kind() == kind).count();
    }

    // ── wiring selection ─────────────────────────────────────────────────────────────

    @Test
    void noObjectModuleSelectsTheEventsOnlyRecordsAndAnOpsModuleTheObjectBackedOnes() {
        assertSame(NoAlertRecords.INSTANCE, AlertRecords.of(Optional.empty()));
        assertInstanceOf(ObjectBackedAlertRecords.class, AlertRecords.of(Optional.of(new FakeObjectAccess())));
        assertTrue(AlertRecords.incidentsOf(Optional.empty())
                .openIncident("t", "m", "critical", "s", Map.of("rule", "x"), "rule").isEmpty());
    }

    // ── ledger rule: same key twice, critical, restart ───────────────────────────────

    @Test
    void aLedgerRuleFiredTwiceIsOneFiredEntryWithOneEventOnBothWirings(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        for (Wiring w : Wiring.values()) {
            seen.clear();
            FakeObjectAccess objects = new FakeObjectAccess();
            AlertService svc = service(w, objects, cfg, store(failedLedger()), ledgerRule("WARNING"));

            assertEquals(1, svc.evaluateAll().size(), w + ": first sweep fires");
            assertEquals(0, svc.evaluateAll().size(), w + ": the cooldown suppresses the same key");
            assertEquals(1, svc.recent(10).size(), w + ": one fired entry");
            assertEquals(1, events(EventType.ALERT_FIRED, "r-failed"), w + ": ALERT_FIRED emitted once");
            assertTrue(seen.stream().anyMatch(e -> EventType.SIGNAL.equals(e.type())
                    && "alert-rule.fired".equals(e.attributes().get(com.gamma.signal.Signal.ATTR_TYPE))), w + ": Signal");
            // The probe that would otherwise succeed: the object-backed twin DOES persist an ALERT.
            assertEquals(w == Wiring.OBJECT_BACKED ? 1 : 0, count(objects, ObjectType.ALERT), w.toString());
            assertEquals(0, count(objects, ObjectType.INCIDENT), w + ": a WARNING raises no Incident");
        }
    }

    @Test
    void criticalRaisesNoIncidentWithoutAnObjectModuleButOneWithIt(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        FakeObjectAccess none = new FakeObjectAccess();
        AlertService eventsOnly = service(Wiring.EVENTS_ONLY, none, cfg, store(failedLedger()), ledgerRule("critical"));
        assertEquals(1, eventsOnly.evaluateAll().size());
        assertTrue(none.opened.isEmpty() && none.linked.isEmpty(), "events-only: no ALERT, no INCIDENT, no link");

        FakeObjectAccess backed = new FakeObjectAccess();
        AlertService withOps = service(Wiring.OBJECT_BACKED, backed, cfg, store(failedLedger()), ledgerRule("critical"));
        assertEquals(1, withOps.evaluateAll().size());
        assertEquals(1, count(backed, ObjectType.ALERT));
        assertEquals(1, count(backed, ObjectType.INCIDENT), "the probe is live: with objects a critical opens an Incident");
        assertEquals(1, backed.linked.size());
        assertEquals("ESCALATED_FROM", backed.linked.get(0).relationship());
    }

    @Test
    void aRestartForgetsInProcessStateSoEventsOnlyReFiresOnceAndObjectBackedDoesNot(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        for (Wiring w : Wiring.values()) {
            FakeObjectAccess objects = new FakeObjectAccess();
            assertEquals(1, service(w, objects, cfg, store(failedLedger()), ledgerRule("WARNING")).evaluateAll().size());
            // "restart": a brand-new AlertService (cooldown state is in-process). Both wirings re-FIRE the
            // in-memory Alert once; only the object-backed one dedupes the persisted ALERT across it.
            AlertService restarted = service(w, objects, cfg, store(failedLedger()), ledgerRule("WARNING"));
            assertEquals(1, restarted.evaluateAll().size(), w + ": a restart re-fires once");
            assertEquals(0, restarted.evaluateAll().size(), w + ": and only once");
            assertEquals(w == Wiring.OBJECT_BACKED ? 1 : 0, count(objects, ObjectType.ALERT),
                    w + ": the persisted ALERT is deduped across the restart, the in-memory ring is not");
        }
    }

    // ── scalar Measure rule: heal, relapse ───────────────────────────────────────────

    @Test
    void aScalarMeasureRuleHealsAndRelapsesIdenticallyOnBothWirings(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        for (Wiring w : Wiring.values()) {
            seen.clear();
            FakeObjectAccess objects = new FakeObjectAccess();
            AlertService svc = service(w, objects, cfg, store(List.of()), measureRule("WARNING"));
            AtomicReference<OptionalDouble> value = new AtomicReference<>(OptionalDouble.of(750.0));
            svc.measureProbe((d, m) -> value.get());

            assertEquals(1, svc.evaluate(null, 0).size(), w + ": breach");
            value.set(OptionalDouble.of(1500.0));
            assertTrue(svc.evaluate(null, 1000).isEmpty(), w + ": heal is not a fired Alert");
            assertEquals(1, events(EventType.ALERT_CLEARED, "low-revenue"), w + ": one all-clear");
            assertTrue(svc.evaluate(null, 2000).isEmpty());
            assertEquals(1, events(EventType.ALERT_CLEARED, "low-revenue"), w + ": edge only");
            value.set(OptionalDouble.of(10.0));
            assertEquals(1, svc.evaluate(null, 3000).size(), w + ": the relapse fires at once");

            // The heal resolved the persisted ALERT only on the object-backed wiring.
            long resolves = objects.transitioned.stream().filter(t -> "resolve".equals(t.action())).count();
            assertEquals(w == Wiring.OBJECT_BACKED ? 1 : 0, resolves, w.toString());
        }
    }

    @Test
    void aRestartedScalarRuleOnlyKnowsItsOpenAlertFromObjects(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        for (Wiring w : Wiring.values()) {
            seen.clear();
            FakeObjectAccess objects = new FakeObjectAccess();
            AlertService first = service(w, objects, cfg, store(List.of()), measureRule("WARNING"));
            first.measureProbe((d, m) -> OptionalDouble.of(750.0));
            assertEquals(1, first.evaluate(null, 0).size());

            // New instance, the value has recovered: the heal edge exists only if the open Alert is remembered.
            AlertService second = service(w, objects, cfg, store(List.of()), measureRule("WARNING"));
            second.measureProbe((d, m) -> OptionalDouble.of(1500.0));
            second.evaluate(null, 1000);
            assertEquals(w == Wiring.OBJECT_BACKED ? 1 : 0, events(EventType.ALERT_CLEARED, "low-revenue"),
                    w + ": events-only has no open-Alert memory across a restart (pinned, today's behaviour)");
        }
    }

    // ── by (per-entity) rule: open-key state ─────────────────────────────────────────

    @Test
    void aByRuleKeepsItsOpenKeysInProcessAndHealsThem(@TempDir Path dir) throws Exception {
        for (Wiring w : Wiring.values()) {
            seen.clear();
            FakeObjectAccess objects = new FakeObjectAccess();
            AlertService svc = service(w, objects, null, store(List.of()), byRule("critical"));
            AtomicReference<DatasetMeasureProbe.Breaches> now = new AtomicReference<>(breaching("m1", "m2"));
            svc.groupedMeasureProbe(r -> Optional.of(now.get()));

            assertEquals(2, svc.evaluate(null, 0).size(), w + ": one Alert per breaching key");
            assertEquals(0, svc.evaluate(null, 1000).size(), w + ": an open key raises nothing new");
            assertEquals(2, events(EventType.ALERT_FIRED, "high-spend"), w.toString());

            now.set(breaching("m1"));                                     // m2 heals
            assertEquals(0, svc.evaluate(null, 2000).size());
            assertEquals(1, events(EventType.ALERT_CLEARED, "high-spend"), w + ": m2's all-clear");
            now.set(breaching("m1", "m2"));                               // m2 relapses
            assertEquals(1, svc.evaluate(null, 3000).size(), w + ": the healed key fires again");

            if (w == Wiring.OBJECT_BACKED) {
                assertEquals(3, count(objects, ObjectType.ALERT));
                assertEquals(2, count(objects, ObjectType.INCIDENT), "m2's relapse meets its still-active Incident");
                assertEquals(1, objects.transitioned.stream().filter(t -> "resolve".equals(t.action())).count());
            } else {
                assertTrue(objects.opened.isEmpty() && objects.transitioned.isEmpty());
            }

            // restart: events-only has no seeded open keys, so the still-breaching keys fire again, once each.
            AlertService restarted = service(w, objects, null, store(List.of()), byRule("critical"));
            restarted.groupedMeasureProbe(r -> Optional.of(breaching("m1", "m2")));
            assertEquals(w == Wiring.OBJECT_BACKED ? 0 : 2, restarted.evaluate(null, 4000).size(),
                    w + ": open keys survive a restart only through the persisted ALERTs");
        }
    }

    // ── Dataset freshness ────────────────────────────────────────────────────────────

    @Test
    void aFreshnessAlertFiresClearsAndPromotesOnlyWithObjects(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        for (Wiring w : Wiring.values()) {
            seen.clear();
            FakeObjectAccess objects = new FakeObjectAccess();
            AlertService svc = service(w, objects, cfg, store(List.of()), freshnessRule("critical"));
            svc.freshnessProbe(d -> OptionalLong.of(0L));

            assertEquals(1, svc.evaluate(null, 3 * HOUR).size(), w + ": goes stale");
            assertEquals(0, svc.evaluate(null, 3 * HOUR + 1000).size(), w + ": cooldown");
            assertEquals(1, events(EventType.ALERT_FIRED, "sales-stale"), w.toString());
            assertEquals(w == Wiring.OBJECT_BACKED ? 1 : 0, count(objects, ObjectType.ALERT), w.toString());
            assertEquals(w == Wiring.OBJECT_BACKED ? 1 : 0, count(objects, ObjectType.INCIDENT), w.toString());

            AlertService restarted = service(w, objects, cfg, store(List.of()), freshnessRule("critical"));
            restarted.freshnessProbe(d -> OptionalLong.of(0L));
            assertEquals(1, restarted.evaluate(null, 3 * HOUR).size(), w + ": a restart re-fires the in-memory Alert");
            assertEquals(w == Wiring.OBJECT_BACKED ? 1 : 0, count(objects, ObjectType.ALERT),
                    w + ": the persisted ALERT is not duplicated");
        }
    }

    // ── the explicit port wiring ─────────────────────────────────────────────────────

    /** Counts every call through the port — the probe that makes "nothing was touched" falsifiable. */
    private static final class CountingRecords implements AlertRecords {
        final AlertRecords inner;
        int calls;
        CountingRecords(AlertRecords inner) { this.inner = inner; }
        @Override public boolean hasActiveAlert(String s, String r) { calls++; return inner.hasActiveAlert(s, r); }
        @Override public Map<String, String> activeAlertIndex(String s, String a) { calls++; return inner.activeAlertIndex(s, a); }
        @Override public Map<String, String> activeIncidentIndex(String s, String a) { calls++; return inner.activeIncidentIndex(s, a); }
        @Override public String openAlert(String t, String m, String sev, String s, Map<String, String> a) { calls++; return inner.openAlert(t, m, sev, s, a); }
        @Override public boolean resolveAlert(String id, String actor) { calls++; return inner.resolveAlert(id, actor); }
        @Override public boolean reopenIncident(String id, String actor) { calls++; return inner.reopenIncident(id, actor); }
        @Override public void linkEscalation(String i, String a, String actor) { calls++; inner.linkEscalation(i, a, actor); }
    }

    @Test
    void theExplicitPortConstructorBehavesLikeTheObjectAccessOne(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        FakeObjectAccess objects = new FakeObjectAccess();
        Optional<com.gamma.objects.ObjectAccess> access = Optional.of(objects);
        CountingRecords counting = new CountingRecords(AlertRecords.of(access));
        AlertService svc = new AlertService(List.of(ledgerRule("critical")), configs(cfg), store(failedLedger()),
                counting, IncidentAccess.over(() -> objects));
        assertEquals(1, svc.evaluateAll().size());
        assertTrue(counting.calls > 0, "the service reaches its records only through the port");
        assertEquals(1, count(objects, ObjectType.ALERT));
        assertEquals(1, count(objects, ObjectType.INCIDENT));

        CountingRecords none = new CountingRecords(NoAlertRecords.INSTANCE);
        AlertService quiet = new AlertService(List.of(ledgerRule("critical")), configs(cfg), store(failedLedger()),
                none, NoAlertRecords.NO_INCIDENTS);
        assertEquals(1, quiet.evaluateAll().size(), "events-only still fires");
        assertTrue(none.calls > 0, "…through the port, which answers empty (no null guard left in the service)");
    }
}
