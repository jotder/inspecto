package com.gamma.alert;

import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.StatusStore;
import com.gamma.objects.FakeObjectAccess;
import com.gamma.objects.ObjectType;
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
 * REAL DuckDB data (the production {@link DatasetMeasureProbe}) and raises one Alert + Incident per breaching
 * key, edge-triggered: a re-fire raises nothing, a healed key resolves its own objects, and above the storm cap
 * one storm Alert stands for them all.
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

    private static AlertService service(Path root, AlertRule rule, FakeObjectAccess objects) {
        AlertService svc = new AlertService(List.of(rule), noPipelines(), emptyStore(), objects);
        DatasetMeasureProbe probe = new DatasetMeasureProbe(() -> root.resolve("config"), () -> root.resolve("data"));
        svc.groupedMeasureProbe(r -> probe.breaches(r.dataset(), r.measure(), r.by(), r.comparator(),
                r.threshold(), r.stormCap()));
        return svc;
    }

    private static List<FakeObjectAccess.Opened> opened(FakeObjectAccess objects, ObjectType kind) {
        return objects.opened.stream().filter(o -> o.kind() == kind).toList();
    }

    @Test
    void fortyBreachingKeysRaiseFortyIncidentsEachNamingItsOffender(@TempDir Path root) throws Exception {
        plantUsage(root, OFFENDERS, Set.of());
        FakeObjectAccess objects = new FakeObjectAccess();
        AlertService svc = service(root, byRule(100), objects);

        assertEquals(OFFENDERS, svc.evaluateRules().size(), "one Alert per breaching key");
        List<FakeObjectAccess.Opened> incidents = opened(objects, ObjectType.INCIDENT);
        assertEquals(OFFENDERS, incidents.size(), "one Incident per breaching key");
        assertEquals(OFFENDERS, opened(objects, ObjectType.ALERT).size());

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
        assertEquals(OFFENDERS, objects.linked.size(), "each Incident ESCALATED_FROM its own Alert");
    }

    @Test
    void anImmediateReEvaluationRaisesNothingNew(@TempDir Path root) throws Exception {
        plantUsage(root, OFFENDERS, Set.of());
        FakeObjectAccess objects = new FakeObjectAccess();
        AlertService svc = service(root, byRule(100), objects);
        assertEquals(OFFENDERS, svc.evaluateRules().size());
        int before = objects.opened.size();
        assertEquals(2 * OFFENDERS, before, "an Alert and an Incident per key");

        assertEquals(0, svc.evaluateRules().size(), "an open key re-firing raises no Alert");
        assertEquals(before, objects.opened.size(), "and no object");

        // A restart forgets nothing: a fresh service over the same objects seeds its open keys from them.
        assertEquals(0, service(root, byRule(100), objects).evaluateRules().size(),
                "the still-active ALERT objects are the open keys after a restart");
        assertEquals(before, objects.opened.size());
    }

    @Test
    void aHealedKeyResolvesItsOwnAlertAndIncidentAndNoOther(@TempDir Path root) throws Exception {
        plantUsage(root, OFFENDERS, Set.of());
        FakeObjectAccess objects = new FakeObjectAccess();
        AlertService svc = service(root, byRule(100), objects);
        svc.evaluateRules();

        plantUsage(root, OFFENDERS, Set.of(7));
        assertEquals(0, svc.evaluateRules().size(), "healing raises no Alert");

        String healedKey = "high-spend|msisdn=m7,region=EU";
        List<String> resolved = objects.transitioned.stream()
                .filter(t -> "resolve".equals(t.action())).map(FakeObjectAccess.Transitioned::objectId).toList();
        assertEquals(2, resolved.size(), "exactly the healed key's Alert and Incident resolve");
        for (ObjectType kind : List.of(ObjectType.ALERT, ObjectType.INCIDENT)) {
            String id = opened(objects, kind).stream()
                    .filter(o -> healedKey.equals(o.attributes().get(AlertService.ALERT_KEY)))
                    .findFirst().orElseThrow().id();
            assertTrue(resolved.contains(id), kind + " of the healed key resolved");
        }
        assertTrue(objects.transitioned.stream().allMatch(t -> "alert-rule:high-spend".equals(t.actor())));
        assertEquals(OFFENDERS - 1, objects.activeAttributeIndex(ObjectType.INCIDENT, "usage",
                AlertService.ALERT_KEY).size(), "the other 39 Incidents stay open");
    }

    @Test
    void moreBreachingKeysThanTheStormCapRaiseExactlyOneStormAlert(@TempDir Path root) throws Exception {
        plantUsage(root, OFFENDERS, Set.of());
        FakeObjectAccess objects = new FakeObjectAccess();
        AlertService svc = service(root, byRule(10), objects);

        List<Alert> fired = svc.evaluateRules();
        assertEquals(1, fired.size(), "one storm Alert, not 40");
        assertEquals(OFFENDERS, fired.get(0).value(), 1e-9, "the storm reports how many keys breached");
        assertTrue(fired.get(0).message().contains("storm — 40 keys (by msisdn, region)"), fired.get(0).message());
        assertEquals(1, opened(objects, ObjectType.ALERT).size());
        FakeObjectAccess.Opened incident = opened(objects, ObjectType.INCIDENT).get(0);
        assertEquals(1, opened(objects, ObjectType.INCIDENT).size());
        assertEquals("40", incident.attributes().get("breachedKeys"));
        assertTrue(incident.title().startsWith("Storm: 40 keys breach"), incident.title());

        assertEquals(0, svc.evaluateRules().size(), "a storm still raging raises nothing new");

        // Back under the cap: the storm heals and the (now few) offenders are raised one by one.
        plantUsage(root, 3, Set.of());
        assertEquals(3, svc.evaluateRules().size());
        assertEquals(2, objects.transitioned.stream().filter(t -> "resolve".equals(t.action())).count(),
                "the storm's Alert and Incident resolve");
    }

    @Test
    void anUnreadableDatasetNeitherFiresNorHeals(@TempDir Path root) throws Exception {
        plantUsage(root, OFFENDERS, Set.of());
        FakeObjectAccess objects = new FakeObjectAccess();
        AlertService svc = service(root, byRule(100), objects);
        assertEquals(OFFENDERS, svc.evaluateRules().size());
        Files.delete(root.resolve("data").resolve("usage").resolve("usage.parquet"));

        assertEquals(0, svc.evaluateRules().size());
        assertTrue(objects.transitioned.isEmpty(), "UNKNOWN is not healed — nothing resolves");
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

    private static AlertService stubbed(AlertRule rule, FakeObjectAccess objects,
                                        java.util.function.Function<AlertRule, List<Map<String, Object>>> keys) {
        AlertService svc = new AlertService(List.of(rule), noPipelines(), emptyStore(), objects);
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
        assertEquals(7, stubbed(rule("usage"), objects, r -> keys).evaluateRules().size(),
                "seven keys, seven Alerts — none collapsed into another");
        assertEquals(7, objects.activeAttributeIndex(ObjectType.INCIDENT, "usage", AlertService.ALERT_KEY).size(),
                "seven distinct dedupe keys");
        assertTrue(opened(objects, ObjectType.ALERT).stream().anyMatch(o -> "NULL".equals(o.attributes().get("key.a"))));

        assertEquals(0, stubbed(rule("usage"), objects, r -> keys).evaluateRules().size(),
                "a restart seeds every encoded key back — nothing re-fires");
        assertTrue(objects.transitioned.isEmpty(), "and nothing is healed by a mis-decoded key");
    }

    @Test
    void reSavingOverAnotherDatasetRetiresTheOldKeysInsteadOfHealingThemAgainstTheNewOne() {
        FakeObjectAccess objects = new FakeObjectAccess();
        List<Map<String, Object>> old = List.of(key("a", "1", "b", "1"), key("a", "2", "b", "2"));
        AlertService svc = stubbed(rule("usage"), objects, r -> "usage".equals(r.dataset()) ? old : List.of());
        assertEquals(2, svc.evaluateRules().size());

        svc.upsert(rule("usage_v2"));
        List<String> oldAlerts = opened(objects, ObjectType.ALERT).stream().map(FakeObjectAccess.Opened::id).toList();
        assertEquals(oldAlerts, objects.transitioned.stream().map(FakeObjectAccess.Transitioned::objectId).toList(),
                "exactly the old rule's per-key Alerts are resolved");
        assertTrue(objects.transitioned.stream().allMatch(t -> "alert-rule:high-spend:rule-changed".equals(t.actor())));
        assertEquals(2, objects.activeAttributeIndex(ObjectType.INCIDENT, "usage", AlertService.ALERT_KEY).size(),
                "the Incidents stay with triage");

        assertEquals(0, svc.evaluateRules().size());
        assertEquals(2, objects.transitioned.size(), "the sweep over the new Dataset heals nothing old");

        // A threshold-only edit keeps the open keys: nothing retired, nothing re-raised.
        FakeObjectAccess objects2 = new FakeObjectAccess();
        AlertService svc2 = stubbed(rule("usage"), objects2, r -> old);
        svc2.evaluateRules();
        svc2.upsert(AlertRule.fromMap(Map.of("name", "high-spend", "dataset", "usage", "measure", "sum(amount)",
                "by", List.of("a", "b"), "comparator", "gt", "threshold", 600, "severity", "CRITICAL")));
        assertTrue(objects2.transitioned.isEmpty());
        assertEquals(0, svc2.evaluateRules().size());
    }

    @Test
    void removingAndReAddingTheRuleRetiresItsKeysAndStartsClean() {
        FakeObjectAccess objects = new FakeObjectAccess();
        List<Map<String, Object>> keys = List.of(key("a", "1", "b", "1"));
        AlertService svc = stubbed(rule("usage"), objects, r -> keys);
        svc.evaluateRules();

        assertTrue(svc.remove("high-spend"));
        assertEquals(1, objects.transitioned.size(), "the removed rule's Alert is resolved");
        assertEquals(opened(objects, ObjectType.ALERT).get(0).id(), objects.transitioned.get(0).objectId(),
                "the Alert, not the Incident");

        svc.upsert(rule("usage"));
        assertEquals(1, svc.evaluateRules().size(), "re-added, the still-breaching key is a fresh breach");
        assertEquals(1, opened(objects, ObjectType.INCIDENT).size(), "its open Incident is not duplicated");
        String incident = opened(objects, ObjectType.INCIDENT).get(0).id();
        String newAlert = opened(objects, ObjectType.ALERT).get(1).id();
        assertTrue(objects.linked.stream().anyMatch(l -> l.fromId().equals(incident) && l.toId().equals(newAlert)),
                "the new Alert is linked to the Incident still being worked");
    }
}
