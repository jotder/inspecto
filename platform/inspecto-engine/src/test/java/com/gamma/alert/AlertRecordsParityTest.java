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
 * MODULE-REORG-P7-INCIDENTS slices 1-2 — the characterisation of {@link AlertService} over its {@link AlertRecords}
 * port. Every scenario runs on the two wirings (Personal: Alert store, no operational objects; with ops: the same
 * store PLUS the Incident half over a {@link FakeObjectAccess}) and on both stores (heap {@link InMemoryAlertStore},
 * durable {@link DbAlertStore}), so the Alert behaviour is pinned to be the same on every edition and backend.
 *
 * <p>Slice 2 is the operator decision of 2026-10-07: Personal keeps Alert history and restart-safe de-duplication.
 * The intended behaviour change versus slice 1 is pinned here: a new {@link AlertService} over the SAME store does
 * NOT re-fire an Alert that is still open (it used to, once, events-only and for ledger / freshness rules), and its
 * {@code recent()} history is re-seeded from the store. ALERT objects are never written any more
 * ({@link FakeObjectAccess#opened} holds INCIDENTs only) — the negative twin of every "no ALERT object" claim is the
 * Incident count, which the very same scenario DOES write with ops.
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

    /** The edition wiring: Personal has the Alert store only; with ops the Incident half rides on objects. */
    private enum Wiring { PERSONAL, WITH_OPS }

    /** The Alert store backend under test. */
    private enum Backend { MEMORY, DURABLE }

    /** One scenario's world: the store (shared across a simulated restart), the fake ops, and the open stores to close. */
    private final class World implements AutoCloseable {
        final Wiring wiring;
        final AlertStore alerts;
        final FakeObjectAccess objects = new FakeObjectAccess();

        World(Wiring wiring, Backend backend, Path dir) throws Exception {
            this.wiring = wiring;
            this.alerts = backend == Backend.MEMORY ? new InMemoryAlertStore()
                    : new DbAlertStore("jdbc:duckdb:" + dir.resolve("alerts.db"), null, null);
        }

        Optional<com.gamma.objects.ObjectAccess> ops() {
            return wiring == Wiring.WITH_OPS ? Optional.of(objects) : Optional.empty();
        }

        /** A new AlertService over the same store — "the process restarted". */
        AlertService service(PipelineConfig cfg, StatusStore status, AlertRule... rules) {
            return new AlertService(List.of(rules), configs(cfg), status, AlertRecords.of(alerts, ops()),
                    AlertRecords.incidentsOf(ops()));
        }

        @Override public void close() { alerts.close(); }
    }

    private interface Scenario {
        void run(World w, String label) throws Exception;
    }

    /** Run {@code body} on every wiring x backend, each in its own directory and its own World. */
    private void everywhere(Path dir, Scenario body) throws Exception {
        int n = 0;
        for (Wiring wiring : Wiring.values())
            for (Backend backend : Backend.values()) {
                Path d = java.nio.file.Files.createDirectories(dir.resolve("w" + n++));
                seen.clear();
                try (World w = new World(wiring, backend, d)) {
                    body.run(w, wiring + "/" + backend);
                }
            }
    }

    private long events(String type, String rule) {
        return seen.stream().filter(e -> type.equals(e.type()) && rule.equals(e.attributes().get("rule"))).count();
    }

    private static long count(FakeObjectAccess o, ObjectType kind) {
        return o.opened.stream().filter(x -> x.kind() == kind).count();
    }

    private static PipelineConfig cfg(Path dir) throws Exception {
        return PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
    }

    // ── wiring selection ─────────────────────────────────────────────────────────────

    @Test
    void theRecordsAreAlwaysTheStoreBackedOnesAndOnlyTheIncidentHalfDependsOnOps() {
        assertInstanceOf(StoredAlertRecords.class, AlertRecords.of(new InMemoryAlertStore(), Optional.empty()));
        assertInstanceOf(StoredAlertRecords.class,
                AlertRecords.of(new InMemoryAlertStore(), Optional.of(new FakeObjectAccess())));
        assertTrue(AlertRecords.incidentsOf(Optional.empty())
                .openIncident("t", "m", "critical", "s", Map.of("rule", "x"), "rule").isEmpty());
        // Personal: no ops, the Incident reads answer empty and re-open / link are no-ops — but the Alert half works.
        AlertRecords personal = AlertRecords.of(new InMemoryAlertStore(), Optional.empty());
        String id = personal.openAlert(null, "t", "m", "warning", "scope", Map.of("rule", "r"));
        assertTrue(personal.hasActiveAlert("scope", "r"));
        assertTrue(personal.activeIncidentIndex("scope", "rule").isEmpty());
        assertFalse(personal.reopenIncident("nope", "a"));
        personal.linkEscalation("inc-1", id, "a");
        assertTrue(personal.resolveAlert(id, "a"));
        assertFalse(personal.hasActiveAlert("scope", "r"));
    }

    // ── ledger rule: same key twice, critical, restart ───────────────────────────────

    @Test
    void aLedgerRuleFiredTwiceIsOneFiredEntryOneRecordAndOneEventEverywhere(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        everywhere(dir, (w, label) -> {
            AlertService svc = w.service(cfg, store(failedLedger()), ledgerRule("WARNING"));
            assertEquals(1, svc.evaluateAll().size(), label + ": first sweep fires");
            assertEquals(0, svc.evaluateAll().size(), label + ": the cooldown suppresses the same key");
            assertEquals(1, svc.recent(10).size(), label + ": one fired entry");
            assertEquals(1, events(EventType.ALERT_FIRED, "r-failed"), label + ": ALERT_FIRED emitted once");
            assertTrue(seen.stream().anyMatch(e -> EventType.SIGNAL.equals(e.type())
                    && "alert-rule.fired".equals(e.attributes().get(com.gamma.signal.Signal.ATTR_TYPE))), label + ": Signal");
            assertEquals(1, w.alerts.size(), label + ": the Alert is recorded in the Alert store on EVERY edition");
            assertEquals(0, count(w.objects, ObjectType.ALERT), label + ": and is never an operational object");
            assertEquals(0, count(w.objects, ObjectType.INCIDENT), label + ": a WARNING raises no Incident");
        });
    }

    @Test
    void criticalRaisesAnIncidentOnlyWithOpsAndLinksItToTheAlertByKindAndId(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        everywhere(dir, (w, label) -> {
            assertEquals(1, w.service(cfg, store(failedLedger()), ledgerRule("critical")).evaluateAll().size(), label);
            assertEquals(1, w.alerts.size(), label);
            AlertStore.Row row = w.alerts.allActive().get(0);
            if (w.wiring == Wiring.PERSONAL) {
                assertTrue(w.objects.opened.isEmpty() && w.objects.linked.isEmpty() && w.objects.subjectLinked.isEmpty(),
                        label + ": Personal has Alerts and NO Incidents - nothing touched ops");
                assertNull(row.incidentId(), label);
            } else {
                assertEquals(1, count(w.objects, ObjectType.INCIDENT), label + ": the probe is live: with ops a critical opens an Incident");
                assertEquals(1, w.objects.subjectLinked.size(), label);
                FakeObjectAccess.SubjectLinked l = w.objects.subjectLinked.get(0);
                assertEquals("ESCALATED_FROM", l.relationship());
                assertEquals(ObjectType.ALERT, l.subjectKind(), label + ": a cross-store reference: kind ALERT ...");
                assertEquals(row.id(), l.subjectId(), label + ": ... + the Alert's own id");
                assertEquals(l.fromId(), row.incidentId(), label + ": and the Alert row remembers its Incident");
                assertTrue(w.objects.linked.isEmpty(), label + ": no object-to-object link: the Alert is not an object");
            }
        });
    }

    @Test
    void aRestartDoesNotReFireAnOpenAlertAndTheHistorySurvivesIt(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        everywhere(dir, (w, label) -> {
            assertEquals(1, w.service(cfg, store(failedLedger()), ledgerRule("WARNING")).evaluateAll().size());
            // "restart": a brand-new AlertService over the same store. Slice 2: the open Alert is remembered, so the
            // breach is NOT announced again (it used to re-fire once, events-only and object-backed alike).
            AlertService restarted = w.service(cfg, store(failedLedger()), ledgerRule("WARNING"));
            assertEquals(1, restarted.recent(10).size(), label + ": GET /alerts history survives the restart");
            assertEquals(0, restarted.evaluateAll().size(), label + ": an open Alert is not re-fired after a restart");
            assertEquals(0, restarted.evaluateAll().size(), label);
            assertEquals(1, events(EventType.ALERT_FIRED, "r-failed"), label + ": still ONE ALERT_FIRED in total");
            assertEquals(1, w.alerts.size(), label + ": and one record");
            // The negative twin: a store that does NOT hold the Alert (the memory opt-out after a restart) re-fires.
            AlertService amnesiac = new AlertService(List.of(ledgerRule("WARNING")), configs(cfg), store(failedLedger()),
                    AlertRecords.of(new InMemoryAlertStore(), w.ops()), AlertRecords.incidentsOf(w.ops()));
            assertEquals(1, amnesiac.evaluateAll().size(), label + ": the probe is live - without the store it fires again");
        });
    }

    // ── scalar Measure rule: heal, relapse ───────────────────────────────────────────

    @Test
    void aScalarMeasureRuleHealsAndRelapsesIdenticallyEverywhere(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        everywhere(dir, (w, label) -> {
            AlertService svc = w.service(cfg, store(List.of()), measureRule("WARNING"));
            AtomicReference<OptionalDouble> value = new AtomicReference<>(OptionalDouble.of(750.0));
            svc.measureProbe((d, m) -> value.get());

            assertEquals(1, svc.evaluate(null, 0).size(), label + ": breach");
            assertEquals(1, w.alerts.allActive().size(), label);
            value.set(OptionalDouble.of(1500.0));
            assertTrue(svc.evaluate(null, 1000).isEmpty(), label + ": heal is not a fired Alert");
            assertEquals(1, events(EventType.ALERT_CLEARED, "low-revenue"), label + ": one all-clear");
            assertTrue(svc.evaluate(null, 2000).isEmpty());
            assertEquals(1, events(EventType.ALERT_CLEARED, "low-revenue"), label + ": edge only");
            assertTrue(w.alerts.allActive().isEmpty(), label + ": the heal RESOLVED the stored Alert on every edition");
            value.set(OptionalDouble.of(10.0));
            assertEquals(1, svc.evaluate(null, 3000).size(), label + ": the relapse fires at once");
            assertEquals(2, w.alerts.size(), label + ": as a second record");
        });
    }

    @Test
    void aRestartedScalarRuleKnowsItsOpenAlertFromTheStoreOnEveryEdition(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        everywhere(dir, (w, label) -> {
            AlertService first = w.service(cfg, store(List.of()), measureRule("WARNING"));
            first.measureProbe((d, m) -> OptionalDouble.of(750.0));
            assertEquals(1, first.evaluate(null, 0).size());

            // New instance, the value has recovered: the heal edge exists only if the open Alert is remembered —
            // and Personal now remembers it too (it did not in slice 1: events-only pinned "no open-Alert memory").
            AlertService second = w.service(cfg, store(List.of()), measureRule("WARNING"));
            second.measureProbe((d, m) -> OptionalDouble.of(1500.0));
            second.evaluate(null, 1000);
            assertEquals(1, events(EventType.ALERT_CLEARED, "low-revenue"), label);
            assertTrue(w.alerts.allActive().isEmpty(), label);
        });
    }

    // ── by (per-entity) rule: open-key state ─────────────────────────────────────────

    @Test
    void aByRuleKeepsItsOpenKeysAndHealsThemAndSurvivesARestartEverywhere(@TempDir Path dir) throws Exception {
        everywhere(dir, (w, label) -> {
            AlertService svc = w.service(null, store(List.of()), byRule("critical"));
            AtomicReference<DatasetMeasureProbe.Breaches> now = new AtomicReference<>(breaching("m1", "m2"));
            svc.groupedMeasureProbe(r -> Optional.of(now.get()));

            assertEquals(2, svc.evaluate(null, 0).size(), label + ": one Alert per breaching key");
            assertEquals(0, svc.evaluate(null, 1000).size(), label + ": an open key raises nothing new");
            assertEquals(2, events(EventType.ALERT_FIRED, "high-spend"), label);

            now.set(breaching("m1"));                                     // m2 heals
            assertEquals(0, svc.evaluate(null, 2000).size());
            assertEquals(1, events(EventType.ALERT_CLEARED, "high-spend"), label + ": m2's all-clear");
            now.set(breaching("m1", "m2"));                               // m2 relapses
            assertEquals(1, svc.evaluate(null, 3000).size(), label + ": the healed key fires again");

            assertEquals(3, w.alerts.size(), label + ": three Alert records");
            assertEquals(2, w.alerts.allActive().size(), label + ": m2's first one was resolved by its heal");
            assertEquals(0, count(w.objects, ObjectType.ALERT), label);
            if (w.wiring == Wiring.WITH_OPS) {
                assertEquals(2, count(w.objects, ObjectType.INCIDENT), label + ": m2's relapse meets its still-active Incident");
                assertEquals(1, w.objects.transitioned.stream().filter(t -> "reopen".equals(t.action())).count(), label);
                assertTrue(w.objects.transitioned.stream().noneMatch(t -> "resolve".equals(t.action())),
                        label + ": a machine heal NEVER resolves the Incident");
            } else {
                assertTrue(w.objects.opened.isEmpty() && w.objects.transitioned.isEmpty(), label);
            }

            // restart: the still-open keys are seeded from the stored Alerts on every edition, so none re-fires.
            AlertService restarted = w.service(null, store(List.of()), byRule("critical"));
            restarted.groupedMeasureProbe(r -> Optional.of(breaching("m1", "m2")));
            assertEquals(0, restarted.evaluate(null, 4000).size(), label + ": open keys survive a restart in the Alert store");
        });
    }

    // ── Dataset freshness ────────────────────────────────────────────────────────────

    @Test
    void aFreshnessAlertFiresClearsAndSurvivesARestartEverywhere(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        everywhere(dir, (w, label) -> {
            AlertService svc = w.service(cfg, store(List.of()), freshnessRule("critical"));
            svc.freshnessProbe(d -> OptionalLong.of(0L));

            assertEquals(1, svc.evaluate(null, 3 * HOUR).size(), label + ": goes stale");
            assertEquals(0, svc.evaluate(null, 3 * HOUR + 1000).size(), label + ": cooldown");
            assertEquals(1, events(EventType.ALERT_FIRED, "sales-stale"), label);
            assertEquals(1, w.alerts.size(), label);
            assertEquals(w.wiring == Wiring.WITH_OPS ? 1 : 0, count(w.objects, ObjectType.INCIDENT), label);

            AlertService restarted = w.service(cfg, store(List.of()), freshnessRule("critical"));
            restarted.freshnessProbe(d -> OptionalLong.of(0L));
            assertEquals(0, restarted.evaluate(null, 3 * HOUR).size(), label + ": a restart does NOT re-fire the open Alert");
            assertEquals(1, w.alerts.size(), label + ": and the record is not duplicated");

            // The dataset recovers while the new process is up: it never saw it go stale, the store did - the
            // all-clear still fires and RESOLVES the stored Alert, so a later stale episode announces itself again.
            restarted.freshnessProbe(d -> OptionalLong.of(3 * HOUR));
            restarted.evaluate(null, 3 * HOUR + 2000);
            assertEquals(1, events(EventType.ALERT_CLEARED, "sales-stale"), label);
            assertTrue(w.alerts.allActive().isEmpty(), label + ": the all-clear resolved the freshness Alert");
        });
    }

    // ── durability, for real ─────────────────────────────────────────────────────────

    @Test
    void theDurableStoreSurvivesClosingAndReopeningTheFile(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        String url = "jdbc:duckdb:" + dir.resolve("space-alerts.db");
        AlertStore first = new DbAlertStore(url, null, null);
        AlertService svc = new AlertService(List.of(ledgerRule("WARNING")), configs(cfg), store(failedLedger()),
                AlertRecords.of(first, Optional.empty()), AlertRecords.incidentsOf(Optional.empty()));
        assertEquals(1, svc.evaluateAll().size());
        Map<String, Object> before = svc.recent(10).get(0);
        first.close();

        AlertStore reopened = new DbAlertStore(url, null, null);
        try {
            assertEquals(1, reopened.size(), "the Alert is on disk");
            AlertService after = new AlertService(List.of(ledgerRule("WARNING")), configs(cfg), store(failedLedger()),
                    AlertRecords.of(reopened, Optional.empty()), AlertRecords.incidentsOf(Optional.empty()));
            assertEquals(before, after.recent(10).get(0), "GET /alerts history is byte-compatible across the restart");
            assertEquals(0, after.evaluateAll().size(), "and the open Alert is not re-fired");
        } finally {
            reopened.close();
        }
    }

    // ── the adoption of pre-slice-2 ALERT objects ────────────────────────────────────

    @Test
    void theOneShotMigrationAdoptsActiveAlertObjectsOnceAndLeavesTheObjectsAlone() {
        FakeObjectAccess ops = new FakeObjectAccess();
        String keep = ops.open(ObjectType.ALERT, "Failed batches on P", "msg", "warning", "P", Map.of("rule", "r-failed"));
        String done = ops.open(ObjectType.ALERT, "old", "msg", "warning", "P", Map.of("rule", "r-old"));
        ops.close(done);                                                       // RESOLVED: history, not state
        ops.open(ObjectType.ALERT, "gap", "msg", "high", "P", Map.of("rule", "sequence_gap"));   // the Event bridge's
        ops.open(ObjectType.INCIDENT, "inc", "msg", "critical", "P", Map.of("rule", "r-failed")); // not an Alert

        AlertStore store = new InMemoryAlertStore();
        assertEquals(1, AlertMigration.adopt(ops, store), "only the still-active, Alert-Rule-fired ALERT is adopted");
        assertTrue(store.hasActive("P", "r-failed"), "it carries the de-duplication state");
        assertEquals(keep, store.activeIndex("P", "rule").get("r-failed"), "under its OWN id, so existing Incident links still resolve");
        assertEquals(4, ops.opened.size(), "the object rows are untouched - nothing deleted, nothing added");

        assertEquals(0, AlertMigration.adopt(ops, store), "idempotent: a non-empty store is never touched again");
        assertEquals(1, store.size());
        // ... and a rule that was adopted does not re-fire after the upgrade
        assertEquals(0, AlertMigration.adopt(null, store));
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
        @Override public String openAlert(Alert f, String t, String m, String sev, String s, Map<String, String> a) { calls++; return inner.openAlert(f, t, m, sev, s, a); }
        @Override public boolean resolveAlert(String id, String actor) { calls++; return inner.resolveAlert(id, actor); }
        @Override public boolean acknowledgeAlert(String id, String actor) { calls++; return inner.acknowledgeAlert(id, actor); }
        @Override public Optional<AlertStore.Row> findAlert(String id) { calls++; return inner.findAlert(id); }
        @Override public List<AlertStore.Row> recentAlertRows(int limit) { calls++; return inner.recentAlertRows(limit); }
        @Override public boolean reopenIncident(String id, String actor) { calls++; return inner.reopenIncident(id, actor); }
        @Override public void linkEscalation(String i, String a, String actor) { calls++; inner.linkEscalation(i, a, actor); }
        @Override public List<Alert> recentFired(int limit) { calls++; return inner.recentFired(limit); }
    }

    @Test
    void theExplicitPortConstructorReachesItsRecordsOnlyThroughThePort(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        FakeObjectAccess objects = new FakeObjectAccess();
        Optional<com.gamma.objects.ObjectAccess> access = Optional.of(objects);
        CountingRecords counting = new CountingRecords(AlertRecords.of(new InMemoryAlertStore(), access));
        AlertService svc = new AlertService(List.of(ledgerRule("critical")), configs(cfg), store(failedLedger()),
                counting, IncidentAccess.over(() -> objects));
        assertEquals(1, svc.evaluateAll().size());
        assertTrue(counting.calls > 0, "the service reaches its records only through the port");
        assertEquals(0, count(objects, ObjectType.ALERT));
        assertEquals(1, count(objects, ObjectType.INCIDENT));

        CountingRecords personal = new CountingRecords(AlertRecords.of(new InMemoryAlertStore(), Optional.empty()));
        AlertService quiet = new AlertService(List.of(ledgerRule("critical")), configs(cfg), store(failedLedger()),
                personal, AlertRecords.NO_INCIDENTS);
        assertEquals(1, quiet.evaluateAll().size(), "Personal still fires");
        assertTrue(personal.calls > 0, "...and records the Alert through the port");
    }
}
