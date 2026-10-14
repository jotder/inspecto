package com.gamma.alert;

import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.StatusStore;
import com.gamma.objects.FakeObjectAccess;
import com.gamma.workflow.ObjectType;
import com.gamma.query.DatasetMeasureProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ASSURE-PER-ENTITY-ALERTS-1 — a Dataset measure Alert Rule with {@code by} evaluates the Measure per key over
 * REAL DuckDB data (the production {@link DatasetMeasureProbe}) and raises ONE Alert per rule (a count of breached
 * keys, no entity named) and one Incident per breaching key (ALERT-PER-RULE-1), edge-triggered: a re-fire raises
 * nothing, a healed key leaves the Alert's count, the Alert resolves when the last key heals, and above the storm
 * cap one storm Incident stands for the keys.
 */
class PerEntityAlertTest {

    private static final int OFFENDERS = 40;

    private static ConfigSource noPipelines() {
        return new ConfigSource() {
            @Override public List<PipelineConfig> pipelines() { return List.of(); }
            @Override public List<EnrichmentConfig> enrichments() { return List.of(); }
            @Override public List<SemanticModel> semantics() { return List.of(); }
        };
    }

    private static StatusStore emptyStore() {
        return new StatusStore() {
            @Override public Set<String> committedBatches(PipelineConfig cfg) { return Set.of(); }
            @Override public List<Map<String, String>> batches(PipelineConfig cfg) { return List.of(); }
            @Override public List<Map<String, String>> files(PipelineConfig cfg) { return List.of(); }
            @Override public List<Map<String, String>> lineage(PipelineConfig cfg, String b) { return List.of(); }
            @Override public List<Map<String, String>> quarantine(PipelineConfig cfg) { return List.of(); }
        };
    }

    /**
     * The {@code usage} Dataset: {@code offenders} msisdns spending 1000 each (two rows of 500) and 10 quiet
     * ones spending 100; {@code healed} msisdns among the offenders drop to 100. Rewritten in place.
     */
    private static void plantUsage(Path root, int offenders, Set<Integer> healed) throws Exception {
        Path reg = Files.createDirectories(root.resolve("config").resolve("registry").resolve("datasets"));
        Files.writeString(reg.resolve("usage.toon"), "name: usage\nphysicalRef: usage\n");
        Path dir = Files.createDirectories(root.resolve("data").resolve("usage"));
        StringBuilder values = new StringBuilder();
        for (int i = 1; i <= offenders + 10; i++) {
            int amount = i > offenders || healed.contains(i) ? 50 : 500;
            if (!values.isEmpty()) values.append(", ");
            values.append("('m").append(i).append("', 'EU', ").append(amount).append("), ('m").append(i)
                    .append("', 'EU', ").append(amount).append(")");
        }
        String file = dir.resolve("usage.parquet").toString().replace('\\', '/');
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + values + ") AS t(msisdn, region, amount)) TO '"
                    + file + "' (FORMAT PARQUET)");
        }
    }

    private static AlertRule byRule(int stormCap) {
        return AlertRule.fromMap(Map.of("name", "high-spend", "dataset", "usage", "measure", "sum(amount)",
                "by", List.of("msisdn", "region"), "stormCap", stormCap, "comparator", "gt", "threshold", 500,
                "severity", "CRITICAL", "description", "High spend"));
    }

    /** The Alert records of the scenario (slice 2: an Alert is a store record, never an object); shared by a simulated restart. */
    private final RecordingAlertStore alerts = new RecordingAlertStore();

    private static AlertService over(AlertStore store, AlertRule rule, FakeObjectAccess objects) {
        Optional<com.gamma.objects.ObjectAccess> ops = Optional.of(objects);
        return new AlertService(List.of(rule), noPipelines(), emptyStore(), AlertRecords.of(store, ops),
                AlertRecords.incidentsOf(ops));
    }

    private AlertService service(Path root, AlertRule rule, FakeObjectAccess objects) {
        AlertService svc = over(alerts, rule, objects);
        DatasetMeasureProbe probe = new DatasetMeasureProbe(() -> root.resolve("config"), () -> root.resolve("data"));
        svc.groupedMeasureProbe(r -> probe.breaches(r.dataset(), r.measure(), r.by(), r.comparator(),
                r.threshold(), r.stormCap()));
        return svc;
    }

    private static List<FakeObjectAccess.Opened> opened(FakeObjectAccess objects, ObjectType kind) {
        return objects.opened.stream().filter(o -> o.kind() == kind).toList();
    }

    @Test
    void fortyBreachingKeysRaiseOneAlertWithACountAndFortyIncidentsEachNamingItsOffender(@TempDir Path root)
            throws Exception {
        plantUsage(root, OFFENDERS, Set.of());
        FakeObjectAccess objects = new FakeObjectAccess();
        AlertService svc = service(root, byRule(100), objects);

        List<Alert> raised = svc.evaluateRules();
        assertEquals(1, raised.size(), "ONE Alert for the rule, not one per breaching key");
        assertEquals(OFFENDERS, raised.get(0).value(), 1e-9, "its value is the count of breached keys");
        assertEquals("CRITICAL: 40 keys (by msisdn, region) breach: High spend — usage", raised.get(0).message());
        assertFalse(raised.get(0).message().contains("m7"), "no entity is named in the Alert");
        List<FakeObjectAccess.Opened> incidents = opened(objects, ObjectType.INCIDENT);
        assertEquals(OFFENDERS, incidents.size(), "one Incident per breaching key");
        assertEquals(1, alerts.opened.size(), "one stored Alert");
        AlertStore.Row row = alerts.opened.get(0);
        assertEquals("high-spend|*", row.attributes().get(AlertService.ALERT_KEY));
        assertEquals("40", row.attributes().get("breachedKeys"));
        assertEquals("High spend — usage", row.title(), "no entity in the title");
        assertTrue(row.attributes().keySet().stream().noneMatch(k -> k.equals("key") || k.startsWith("key.")),
                "no key attribute on the rule's Alert");
        assertEquals(OFFENDERS, row.attributes().get(AlertService.DIGESTS).split(",").length,
                "the breached keys are held as digests, one per key");
        assertFalse(row.attributes().get(AlertService.DIGESTS).contains("m7"), "digests, never the entity");
        assertTrue(row.attributes().get("firstBreachAt") != null && row.attributes().get("lastBreachAt") != null);

        FakeObjectAccess.Opened m7 = incidents.stream()
                .filter(o -> "m7".equals(o.attributes().get("key.msisdn"))).findFirst().orElseThrow();
        assertEquals("High spend — usage for msisdn=m7, region=EU", m7.title(), "the title names the key");
        assertEquals("EU", m7.attributes().get("key.region"), "every key column is an attribute");
        assertEquals("1000.0", m7.attributes().get("value"), "the Measure value rides along");
        assertEquals("high-spend|msisdn=m7,region=EU", m7.attributes().get(AlertService.ALERT_KEY));
        assertEquals("usage", m7.scope());
        assertTrue(incidents.stream().allMatch(o ->
                        Integer.parseInt(o.attributes().get("key.msisdn").substring(1)) <= OFFENDERS),
                "the quiet keys raise nothing");
        assertEquals(OFFENDERS, objects.subjectLinked.size(), "each Incident ESCALATED_FROM the rule's Alert (kind + id)");
        assertTrue(objects.subjectLinked.stream().allMatch(l -> l.subjectId().equals(row.id())),
                "all forty point at the ONE Alert");
        assertTrue(objects.linked.isEmpty(), "never an object-to-object link: the Alert is not an object");
    }

    /** Runbook (operator 2026-10-10): the Incident a linked rule raises names the Runbook its page shows. */
    @Test
    void theIncidentOfALinkedRuleNamesItsRunbookAndAnUnlinkedOneDoesNot(@TempDir Path root) throws Exception {
        plantUsage(root, 2, Set.of());
        Map<String, Object> body = new java.util.LinkedHashMap<>(byRule(100).toMap());
        body.put("runbook", "spend_runbook");
        FakeObjectAccess objects = new FakeObjectAccess();
        service(root, AlertRule.fromMap(body), objects).evaluateRules();
        List<FakeObjectAccess.Opened> incidents = opened(objects, ObjectType.INCIDENT);
        assertEquals(2, incidents.size());
        assertTrue(incidents.stream().allMatch(o -> "spend_runbook".equals(o.attributes().get("runbook"))));
        assertTrue(alerts.opened.stream().allMatch(o -> "spend_runbook".equals(o.attributes().get("runbook"))));

        FakeObjectAccess plain = new FakeObjectAccess();
        service(root, byRule(100), plain).evaluateRules();
        assertTrue(opened(plain, ObjectType.INCIDENT).stream().noneMatch(o -> o.attributes().containsKey("runbook")));
    }

    @Test
    void anImmediateReEvaluationRaisesNothingNew(@TempDir Path root) throws Exception {
        plantUsage(root, OFFENDERS, Set.of());
        FakeObjectAccess objects = new FakeObjectAccess();
        AlertService svc = service(root, byRule(100), objects);
        assertEquals(1, svc.evaluateRules().size());
        int before = objects.opened.size();
        assertEquals(OFFENDERS, before, "an Incident per key");
        assertEquals(1, alerts.opened.size(), "and ONE Alert record");
        int links = objects.subjectLinked.size();

        assertEquals(0, svc.evaluateRules().size(), "an open key re-firing raises no Alert");
        assertEquals(before, objects.opened.size(), "and no object");
        assertEquals(1, alerts.opened.size(), "and no new Alert record");

        // A restart forgets nothing: a fresh service seeds its open keys from the Alert's digests. A key it failed
        // to seed would be a fresh breach and re-link its Incident, so the link count is the probe.
        assertEquals(0, service(root, byRule(100), objects).evaluateRules().size(),
                "the still-active Alert's digests are the open keys after a restart");
        assertEquals(before, objects.opened.size());
        assertEquals(1, alerts.opened.size());
        assertEquals(links, objects.subjectLinked.size(), "no key was mistaken for a fresh breach");
    }

    @Test
    void aHealedKeyLeavesTheCountTheAlertResolvesWithItsLastKeyAndNoIncidentIsEverResolved(@TempDir Path root)
            throws Exception {
        plantUsage(root, OFFENDERS, Set.of());
        FakeObjectAccess objects = new FakeObjectAccess();
        AlertService svc = service(root, byRule(100), objects);
        svc.evaluateRules();
        String alertId = alerts.opened.get(0).id();

        plantUsage(root, OFFENDERS, Set.of(7));
        assertEquals(0, svc.evaluateRules().size(), "healing raises no Alert");
        assertTrue(alerts.resolvedIds().isEmpty(), "39 keys still breach: the rule's Alert stays open");
        AlertStore.Row row = alerts.get(alertId).orElseThrow();
        assertEquals("39", row.attributes().get("breachedKeys"), "the healed key left the count");
        assertEquals(39, row.attributes().get(AlertService.DIGESTS).split(",").length);
        assertTrue(row.message().contains("39 keys"), row.message());
        assertEquals(39.0, row.fired().value(), 1e-9, "the stored fired Alert follows the count");

        // ⛔ a machine heal never resolves an Incident — its Disposition is a human decision (WS-10)
        assertTrue(objects.transitioned.isEmpty(), "the heal does not even attempt an Incident");

        plantUsage(root, 0, Set.of());                                   // every key healed
        assertEquals(0, svc.evaluateRules().size(), "the all-clear raises no Alert");
        assertEquals(List.of(alertId), alerts.resolvedIds(), "the last key's heal resolves the rule's one Alert");
        assertTrue(alerts.moved.stream().allMatch(t -> "alert-rule:high-spend".equals(t.actor())));
        assertTrue(objects.transitioned.isEmpty(), "still no Incident transition");
        assertEquals(OFFENDERS, objects.activeAttributeIndex(ObjectType.INCIDENT, "usage",
                AlertService.ALERT_KEY).size(), "all 40 Incidents stay open, the healed ones included");

        plantUsage(root, 2, Set.of());                                   // a relapse is a NEW Alert
        assertEquals(1, svc.evaluateRules().size(), "a relapse after the all-clear opens a fresh rule Alert");
        assertEquals(2, alerts.opened.size());
    }

    @Test
    void moreBreachingKeysThanTheStormCapKeepTheOneAlertAndRaiseOneStormIncident(@TempDir Path root) throws Exception {
        plantUsage(root, OFFENDERS, Set.of());
        FakeObjectAccess objects = new FakeObjectAccess();
        AlertService svc = service(root, byRule(10), objects);

        List<Alert> fired = svc.evaluateRules();
        assertEquals(1, fired.size(), "one Alert, not 40");
        assertEquals(OFFENDERS, fired.get(0).value(), 1e-9, "it reports how many keys breached");
        assertTrue(fired.get(0).message().contains("40 keys (by msisdn, region) breach"), fired.get(0).message());
        assertTrue(fired.get(0).message().contains("above the storm cap of 10"), fired.get(0).message());
        assertEquals(1, alerts.opened.size());
        assertEquals("true", alerts.opened.get(0).attributes().get("storm"));
        FakeObjectAccess.Opened incident = opened(objects, ObjectType.INCIDENT).get(0);
        assertEquals(1, opened(objects, ObjectType.INCIDENT).size(), "one storm Incident, no per-key Incidents");
        assertEquals("40", incident.attributes().get("breachedKeys"));
        assertTrue(incident.title().startsWith("Storm: 40 keys breach"), incident.title());

        assertEquals(0, svc.evaluateRules().size(), "a storm still raging raises nothing new");

        // Back under the cap: the storm ends, the same Alert now counts 3 keys, and each gets its own Incident.
        plantUsage(root, 3, Set.of());
        assertEquals(0, svc.evaluateRules().size(), "the rule's Alert is updated in place, not re-raised");
        assertEquals(4, opened(objects, ObjectType.INCIDENT).size(), "the storm Incident + three offenders");
        AlertStore.Row row = alerts.get(alerts.opened.get(0).id()).orElseThrow();
        assertEquals("false", row.attributes().get("storm"));
        assertEquals("3", row.attributes().get("breachedKeys"));
        assertTrue(alerts.resolvedIds().isEmpty(), "three keys still breach");

        plantUsage(root, 0, Set.of());
        svc.evaluateRules();
        assertEquals(1, alerts.resolvedIds().size(), "the Alert resolves; its Incidents stay for a human (WS-10)");
    }

    @Test
    void anUnreadableDatasetNeitherFiresNorHeals(@TempDir Path root) throws Exception {
        plantUsage(root, OFFENDERS, Set.of());
        FakeObjectAccess objects = new FakeObjectAccess();
        AlertService svc = service(root, byRule(100), objects);
        assertEquals(1, svc.evaluateRules().size());
        Files.delete(root.resolve("data").resolve("usage").resolve("usage.parquet"));

        assertEquals(0, svc.evaluateRules().size());
        assertTrue(alerts.moved.isEmpty(), "UNKNOWN is not healed — nothing resolves");
    }

    @Test
    void aRuleWithoutByIsUnchangedAndNeverAsksTheGroupedProbe(@TempDir Path root) throws Exception {
        AlertRule scalar = AlertRule.fromMap(Map.of("name", "low-revenue", "dataset", "usage",
                "measure", "sum(amount)", "comparator", "lt", "threshold", 1000, "severity", "WARNING"));
        assertFalse(scalar.isGrouped());
        assertFalse(scalar.toMap().containsKey("by"), "the stored shape of a no-by rule is unchanged");
        assertFalse(scalar.toMap().containsKey("stormCap"));
        assertEquals(new AlertRule("low-revenue", null, "lt", 1000, null, "WARNING", null, "usage", "sum(amount)"),
                scalar, "equal to the historic BI-5 constructor's rule");

        AlertService svc = new AlertService(List.of(scalar), noPipelines(), emptyStore());
        AtomicInteger grouped = new AtomicInteger();
        svc.groupedMeasureProbe(r -> { grouped.incrementAndGet(); return Optional.empty(); });
        svc.measureProbe((d, m) -> OptionalDouble.of(750.0));

        List<Alert> fired = svc.evaluateRules();
        assertEquals(1, fired.size());
        assertEquals("WARNING: Sum of amount on usage is 750, below the threshold of 1,000 (over current data)",
                fired.get(0).message());
        assertEquals(0, svc.evaluateRules().size(), "the cooldown still governs a scalar rule");
        assertEquals(0, grouped.get(), "a no-by rule never reaches the per-key path");
    }

    @Test
    void byIsValidatedWhereItCanApply() {
        Map<String, Object> ok = Map.of("name", "x", "dataset", "ds", "measure", "count", "by", "msisdn, region",
                "comparator", "gt", "threshold", 5);
        AlertRule rule = AlertRule.fromMap(ok);
        assertEquals(List.of("msisdn", "region"), rule.by(), "a comma-separated scalar is a list");
        assertEquals(AlertRule.DEFAULT_STORM_CAP, rule.stormCap());
        assertEquals(rule, AlertRule.fromMap(rule.toMap()), "toMap round-trips");

        assertThrows(IllegalArgumentException.class, () -> AlertRule.fromMap(Map.of("name", "x",
                "metric", "error_rate", "window", "1h", "threshold", 0.1, "by", List.of("status"))),
                "a ledger rule cannot be grouped");
        assertThrows(IllegalArgumentException.class, () -> AlertRule.fromMap(Map.of("name", "x", "dataset", "ds",
                "measure", "count", "threshold", 5, "by", List.of("a\"; DROP"))), "a key column is a plain name");
        assertThrows(IllegalArgumentException.class, () -> AlertRule.fromMap(Map.of("name", "x", "dataset", "ds",
                "measure", "count", "threshold", 5, "stormCap", 10)), "stormCap needs by");
        assertThrows(IllegalArgumentException.class, () -> AlertRule.fromMap(Map.of("name", "x", "dataset", "ds",
                "measure", "count", "threshold", 5, "by", List.of("a"), "stormCap", 0)), "stormCap is positive");
    }

    // ── stub-probe cases: the key identity and the rule lifecycle ─────────────────────────────────

    private static Map<String, Object> key(String a, Object aValue, String b, Object bValue) {
        Map<String, Object> k = new java.util.LinkedHashMap<>();
        k.put(a, aValue);
        k.put(b, bValue);
        return k;
    }

    private static AlertRule rule(String dataset) {
        return AlertRule.fromMap(Map.of("name", "high-spend", "dataset", dataset, "measure", "sum(amount)",
                "by", List.of("a", "b"), "comparator", "gt", "threshold", 500, "severity", "CRITICAL"));
    }

    private AlertService stubbed(AlertRule rule, FakeObjectAccess objects,
                                 java.util.function.Function<AlertRule, List<Map<String, Object>>> keys) {
        return stubbed(alerts, rule, objects, keys);
    }

    private static AlertService stubbed(AlertStore store, AlertRule rule, FakeObjectAccess objects,
                                        java.util.function.Function<AlertRule, List<Map<String, Object>>> keys) {
        AlertService svc = over(store, rule, objects);
        svc.groupedMeasureProbe(r -> {
            List<DatasetMeasureProbe.Breach> b = keys.apply(r).stream()
                    .map(k -> new DatasetMeasureProbe.Breach(k, 1000)).toList();
            return Optional.of(new DatasetMeasureProbe.Breaches(b, b.size()));
        });
        return svc;
    }

    @Test
    void keysThatReadAlikeAndNullVersusTheStringNullAreDistinctKeysAndSurviveARestart() {
        List<Map<String, Object>> keys = List.of(
                key("a", "x, b=y", "b", "z"), key("a", "x", "b", "y, b=z"),   // same readable label
                key("a", "x,b=y", "b", "z"), key("a", "x", "b", "y,b=z"),     // same key id if `,`/`=` went unescaped
                key("a", null, "b", "1"), key("a", "null", "b", "1"),          // NULL vs 'null'
                key("a", "p|q\\", "b", "="));                                  // every escaped character
        FakeObjectAccess objects = new FakeObjectAccess();
        assertEquals(1, stubbed(rule("usage"), objects, r -> keys).evaluateRules().size(), "one Alert for the rule");
        assertEquals("7", alerts.opened.get(0).attributes().get("breachedKeys"));
        assertEquals(7, java.util.Set.of(alerts.opened.get(0).attributes().get(AlertService.DIGESTS).split(",")).size(),
                "seven distinct key digests — none collapsed into another");
        assertEquals(7, objects.activeAttributeIndex(ObjectType.INCIDENT, "usage", AlertService.ALERT_KEY).size(),
                "seven distinct Incident dedupe keys");
        assertTrue(opened(objects, ObjectType.INCIDENT).stream().anyMatch(o -> "NULL".equals(o.attributes().get("key.a"))));
        int links = objects.subjectLinked.size();

        assertEquals(0, stubbed(rule("usage"), objects, r -> keys).evaluateRules().size(),
                "a restart seeds every key back from the digests — nothing re-fires");
        assertTrue(alerts.moved.isEmpty(), "and nothing is healed by a mis-seeded key");
        assertEquals(links, objects.subjectLinked.size(), "no key was taken for a fresh breach");
    }

    @Test
    void reSavingOverAnotherDatasetRetiresTheOldKeysInsteadOfHealingThemAgainstTheNewOne() {
        FakeObjectAccess objects = new FakeObjectAccess();
        List<Map<String, Object>> old = List.of(key("a", "1", "b", "1"), key("a", "2", "b", "2"));
        AlertService svc = stubbed(rule("usage"), objects, r -> "usage".equals(r.dataset()) ? old : List.of());
        assertEquals(1, svc.evaluateRules().size());

        svc.upsert(rule("usage_v2"));
        List<String> oldAlerts = alerts.opened.stream().map(AlertStore.Row::id).toList();
        assertEquals(oldAlerts.stream().sorted().toList(),
                alerts.moved.stream().map(RecordingAlertStore.Moved::alertId).sorted().toList(),
                "exactly the old rule's Alert is resolved");
        assertTrue(alerts.moved.stream().allMatch(t -> "alert-rule:high-spend:rule-changed".equals(t.actor())));
        assertEquals(2, objects.activeAttributeIndex(ObjectType.INCIDENT, "usage", AlertService.ALERT_KEY).size(),
                "the Incidents stay with triage");

        assertEquals(0, svc.evaluateRules().size());
        assertEquals(1, alerts.moved.size(), "the sweep over the new Dataset heals nothing old");

        // A threshold-only edit keeps the open keys: nothing retired, nothing re-raised.
        FakeObjectAccess objects2 = new FakeObjectAccess();
        RecordingAlertStore alerts2 = new RecordingAlertStore();
        AlertService svc2 = stubbed(alerts2, rule("usage"), objects2, r -> old);
        svc2.evaluateRules();
        svc2.upsert(AlertRule.fromMap(Map.of("name", "high-spend", "dataset", "usage", "measure", "sum(amount)",
                "by", List.of("a", "b"), "comparator", "gt", "threshold", 600, "severity", "CRITICAL")));
        assertTrue(alerts2.moved.isEmpty());
        assertEquals(0, svc2.evaluateRules().size());
    }

    @Test
    void removingAndReAddingTheRuleRetiresItsKeysAndStartsClean() {
        FakeObjectAccess objects = new FakeObjectAccess();
        List<Map<String, Object>> keys = List.of(key("a", "1", "b", "1"));
        AlertService svc = stubbed(rule("usage"), objects, r -> keys);
        svc.evaluateRules();

        assertTrue(svc.remove("high-spend"));
        assertEquals(1, alerts.moved.size(), "the removed rule's Alert is resolved");
        assertEquals(alerts.opened.get(0).id(), alerts.moved.get(0).alertId(),
                "the Alert, not the Incident");

        svc.upsert(rule("usage"));
        assertEquals(1, svc.evaluateRules().size(), "re-added, the still-breaching key is a fresh breach");
        assertEquals(1, opened(objects, ObjectType.INCIDENT).size(), "its open Incident is not duplicated");
        String incident = opened(objects, ObjectType.INCIDENT).get(0).id();
        String newAlert = alerts.opened.get(1).id();
        assertTrue(objects.subjectLinked.stream().anyMatch(l -> l.fromId().equals(incident) && l.subjectId().equals(newAlert)),
                "the new Alert is linked to the Incident still being worked");
    }


    // ── heal hysteresis — ALERT-HEAL-FLAP-1 ───────────────────────────────────────────────

    @Test
    void aFlappingKeyIsHealedOnlyAfterNConsecutiveHealthySweepsAndRaisesNoFreshAlert(@TempDir Path root)
            throws Exception {
        AlertRule rule = AlertRule.fromMap(Map.of("name", "high-spend", "dataset", "usage", "measure", "sum(amount)",
                "by", List.of("msisdn", "region"), "comparator", "gt", "threshold", 500, "severity", "CRITICAL",
                "healAfterSweeps", 2));
        plantUsage(root, OFFENDERS, Set.of());
        FakeObjectAccess objects = new FakeObjectAccess();
        AlertService svc = service(root, rule, objects);
        assertEquals(1, svc.evaluateRules().size());

        int alertCount = alerts.opened.size();
        for (int i = 0; i < 4; i++) {                                    // m7 flaps: healthy, breaching, healthy, ...
            plantUsage(root, OFFENDERS, i % 2 == 0 ? Set.of(7) : Set.of());
            assertEquals(0, svc.evaluateRules().size(), "a relapse of an open key raises nothing");
        }
        assertEquals(alertCount, alerts.opened.size(), "no fresh Alert per relapse");
        assertTrue(alerts.resolvedIds().isEmpty(),
                "the alternating key never reached 2 healthy sweeps in a row");

        plantUsage(root, OFFENDERS, Set.of(7));                          // healthy twice running
        svc.evaluateRules();
        assertTrue(alerts.resolvedIds().isEmpty(), "one healthy sweep");
        svc.evaluateRules();
        assertTrue(alerts.resolvedIds().isEmpty(), "39 keys still breach: the rule's Alert is not resolved by m7");
        assertEquals("39", alerts.get(alerts.opened.get(0).id()).orElseThrow().attributes().get("breachedKeys"),
                "the second consecutive healthy sweep removes exactly m7 from the count");
    }

    // ── ALERT-PER-RULE-1: two rules, the migration, a rule-level count ─────────────────────────────

    @Test
    void twoRulesOverTheSameKeyRaiseTwoAlerts() {
        FakeObjectAccess objects = new FakeObjectAccess();
        AlertRule other = AlertRule.fromMap(Map.of("name", "high-spend-b", "dataset", "usage", "measure", "sum(amount)",
                "by", List.of("a", "b"), "comparator", "gt", "threshold", 400, "severity", "CRITICAL"));
        Optional<com.gamma.objects.ObjectAccess> ops = Optional.of(objects);
        AlertService svc = new AlertService(List.of(rule("usage"), other), noPipelines(), emptyStore(),
                AlertRecords.of(alerts, ops), AlertRecords.incidentsOf(ops));
        List<Map<String, Object>> keys = List.of(key("a", "1", "b", "1"), key("a", "2", "b", "2"));
        svc.groupedMeasureProbe(r -> Optional.of(new DatasetMeasureProbe.Breaches(
                keys.stream().map(k -> new DatasetMeasureProbe.Breach(k, 1000)).toList(), keys.size())));

        assertEquals(2, svc.evaluateRules().size(), "one Alert per rule, whatever the key overlap");
        assertEquals(java.util.Set.of("high-spend|*", "high-spend-b|*"), alerts.opened.stream()
                .map(r -> r.attributes().get(AlertService.ALERT_KEY)).collect(java.util.stream.Collectors.toSet()));
        assertEquals(4, opened(objects, ObjectType.INCIDENT).size(), "and one Incident per (rule, key)");
    }

    /** A Space stored before the fold holds one Alert per key: the first sweep folds them into the rule's one Alert. */
    @Test
    void perKeyAlertsStoredBeforeTheFoldAreFoldedIntoTheRulesOneAlert() {
        FakeObjectAccess objects = new FakeObjectAccess();
        List<Map<String, Object>> keys = List.of(key("a", "1", "b", "1"), key("a", "2", "b", "2"), key("a", "3", "b", "3"));
        List<String> legacyIds = new java.util.ArrayList<>();
        for (Map<String, Object> k : keys) {
            Map<String, String> attrs = new java.util.LinkedHashMap<>();
            attrs.put("rule", "high-spend");
            attrs.put(AlertService.ALERT_KEY, "high-spend|" + AlertService.keyId(k));
            attrs.put("key", AlertService.keyLabel(k));
            legacyIds.add(alerts.open("High spend — usage for " + AlertService.keyLabel(k), "m", "CRITICAL", "usage", attrs, null));
        }
        AlertService svc = stubbed(rule("usage"), objects, r -> keys);

        assertEquals(0, svc.evaluateRules().size(), "the three keys were already announced: nothing is re-raised");
        assertEquals(new java.util.TreeSet<>(legacyIds), new java.util.TreeSet<>(alerts.resolvedIds()),
                "every legacy per-key Alert is resolved");
        assertTrue(alerts.moved.stream().allMatch(t -> "alert-rule:high-spend:per-rule-fold".equals(t.actor())));
        List<AlertStore.Row> active = alerts.active("usage");
        assertEquals(1, active.size(), "exactly one active Alert is left: the rule's");
        assertEquals("high-spend|*", active.get(0).attributes().get(AlertService.ALERT_KEY));
        assertEquals("3", active.get(0).attributes().get("breachedKeys"));
        assertEquals(3, active.get(0).attributes().get(AlertService.DIGESTS).split(",").length);
        assertTrue(opened(objects, ObjectType.INCIDENT).isEmpty(), "the migration opens no Incident");

        // and the folded ledger heals like a native one: all three keys healing resolves the rule's Alert
        AlertService again = stubbed(rule("usage"), objects, r -> List.of());
        assertEquals(0, again.evaluateRules().size());
        assertTrue(alerts.resolvedIds().contains(active.get(0).id()), "the folded Alert resolves when its keys heal");
    }

    @Test
    void aSixtyFourOffenderRuleKeepsOneAlertAndSixtyFourDistinctKeys() {
        FakeObjectAccess objects = new FakeObjectAccess();
        List<Map<String, Object>> keys = new java.util.ArrayList<>();
        for (int i = 0; i < 64; i++) keys.add(key("a", "m" + i, "b", "EU"));
        List<Alert> raised = stubbed(rule("usage"), objects, r -> keys).evaluateRules();
        assertEquals(1, raised.size());
        assertEquals(64, raised.get(0).value(), 1e-9);
        assertEquals(64, opened(objects, ObjectType.INCIDENT).size());
        assertEquals(64, java.util.Set.of(alerts.opened.get(0).attributes().get(AlertService.DIGESTS).split(",")).size());
    }
}
