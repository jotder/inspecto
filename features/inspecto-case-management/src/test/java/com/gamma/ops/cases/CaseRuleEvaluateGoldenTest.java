package com.gamma.ops.cases;

import com.gamma.ops.DbObjectStore;
import com.gamma.ops.InMemoryObjectStore;
import com.gamma.ops.ObjectQuery;
import com.gamma.ops.ObjectService;
import com.gamma.ops.ObjectStore;
import com.gamma.ops.OperationalObject;
import com.gamma.ops.link.LinkRelationship;
import com.gamma.ops.link.ObjectLink;
import com.gamma.ops.tag.TagRule;
import com.gamma.workflow.ObjectType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden pins for {@link CaseOperations#evaluateCaseRule} (ANALYTICS-CORE-1 S8), captured from the JVM-side walk
 * before the window and the expressible Tag Rule filter fields moved into the object store's query, and run on the
 * in-memory store and on DuckDB. Each line records a rule's evaluation outcome, the members it grouped (in the
 * newest-first order the matches are linked) and the correlation id the new Case inherited from its newest match.
 *
 * <p>Covers: the Incident status alias fold ({@code open} → IDENTIFIED, {@code in_progress} → DIAGNOSING, a padded
 * {@code " open "}), case-insensitive and padded priority / severity, the category {@code startsWith} on a trimmed
 * value (and a {@code fraud} that only appears mid-path), a free-text {@code q} that only matches across the
 * title/description join, a non-ASCII value that Java folds to ASCII (KELVIN SIGN → {@code k}), the window edges
 * (59 min in, 61 min out of a 60-min window; a negative createdAt outside an all-time window), a {@code type}
 * criterion naming another type, per-entity grouping, and re-evaluation (already-grouped Incidents are skipped).
 */
class CaseRuleEvaluateGoldenTest {

    static final String GOLDEN = String.join("\n",
            "A-open-crit-fraud matched=3 grouped=3 opened=true case=[Open critical fraud|corr-n01|[n05, n02, n01]]",
            "B-sim-box-by-msisdn matched=3 grouped=3 opened=true case=[SIM box per subscriber (msisdn #g)|corr-n08|[n08], "
                    + "SIM box per subscriber (msisdn #g)|corr-n11|[n11], SIM box per subscriber (msisdn #g)|corr-n12|[n12]]",
            "C-case-type matched=0 grouped=0 opened=false case=[]",
            "D-in-progress matched=2 grouped=2 opened=true case=[In progress|corr-n06|[n10, n06]]",
            "E-kelvin matched=1 grouped=1 opened=true case=[Kelvin key|corr-n07|[n07]]",
            "G-final-sigma matched=1 grouped=1 opened=true case=[Final sigma|corr-n13|[n13]]",
            "F-threshold-unmet matched=0 grouped=0 opened=false case=[]",
            "A-again matched=0 grouped=0 opened=false");

    private static final long MIN = 60_000L;

    private static OperationalObject inc(String id, long createdAt, String status, String priority, String severity,
                                         String title, String description, String... kv) {
        Map<String, String> attrs = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) attrs.put(kv[i], kv[i + 1]);
        return new OperationalObject(id, ObjectType.INCIDENT, title, description, status, severity, priority, null, null,
                "corr-" + id, attrs, createdAt, createdAt, 0, 0);
    }

    static void seed(ObjectStore s, long now) {
        s.create(inc("n01", now - 10 * MIN, "IDENTIFIED", "CRITICAL", "HIGH", "SIM box hit", "x", "category", "Fraud / SIM", "key.msisdn", "111"));
        s.create(inc("n02", now - 30 * MIN, " open ", " critical ", "high ", "y", "z", "category", "  FRAUDulent", "key.msisdn", "222"));
        s.create(inc("n03", now - 5 * MIN, "Identified", "Critical", "LOW", "q", "r", "category", "Billing/fraud"));
        s.create(inc("n04", now - 61 * MIN, "IDENTIFIED", "CRITICAL", "HIGH", "old", "o", "category", "fraud"));
        s.create(inc("n05", now - 59 * MIN, "IDENTIFIED", "CRITICAL", "HIGH", "edge", "e", "category", "fraud", "key.msisdn", "111"));
        s.create(inc("n06", now - 20 * MIN, "DIAGNOSING", "CRITICAL", "HIGH", "diag", "d", "category", "fraud"));
        s.create(inc("n07", now - 15 * MIN, "IDENTIFIED", "Key", "Medium", "kelvin", "k", "category", "Network"));
        s.create(inc("n08", now - 25 * MIN, "ARCHIVED", "LOW", "HIGH", "sim", "box detected", "key.msisdn", "333"));
        s.create(inc("n09", -5, "IDENTIFIED", "LOW", "HIGH", "sim box ancient", "a"));
        s.create(inc("n10", now - 40 * MIN, "ASSIGNED", "LOW", "HIGH", "assigned", "s"));
        s.create(inc("n11", now - 3 * 24 * 60 * MIN, "IN_PROGRESS", "LOW", "high", "sim box", "progress", "key.msisdn", "222"));
        s.create(inc("n12", now - 50 * MIN, "identified", "critical", "HIGH", "SIM BOX twin", "t", "key.msisdn", "111"));
        // Java folds a final capital sigma to the final form; a database lower() maps it to the medial one
        s.create(inc("n13", now - 45 * MIN, "IDENTIFIED", "\u039F\u0394\u039F\u03A3", "LOW", "sigma", "s"));
    }

    private static List<CaseRule> rules() {
        return List.of(
                new CaseRule("A-open-crit-fraud", "Open critical fraud",
                        new TagRule.Filter(null, null, "open", " critical ", null, "fraud"), 1, 60, null, null, 1),
                new CaseRule("B-sim-box-by-msisdn", "SIM box per subscriber",
                        new TagRule.Filter(null, "sim box", null, null, "HIGH", null), 1, 0, null, null, 1, "msisdn", Map.of()),
                new CaseRule("C-case-type", "Only cases", new TagRule.Filter("CASE", null, null, null, null, null), 1, 0, null, null, 1),
                new CaseRule("D-in-progress", "In progress", new TagRule.Filter(null, null, "in_progress", null, null, null), 2, 0, null, null, 1),
                new CaseRule("E-kelvin", "Kelvin key", new TagRule.Filter(null, null, null, "key", null, null), 1, 1440, null, null, 1),
                new CaseRule("G-final-sigma", "Final sigma", new TagRule.Filter(null, null, null, "\u03BF\u03B4\u03BF\u03C2", null, null), 1, 1440, null, null, 1),
                new CaseRule("F-threshold-unmet", "Unmet", new TagRule.Filter(null, null, null, null, "medium", null), 5, 1440, null, null, 1));
    }

    private static String run(Supplier<ObjectStore> backend) {
        try (ObjectStore store = backend.get()) {
            seed(store, System.currentTimeMillis());
            ObjectService svc = new ObjectService(store);
            CaseOperations ops = CaseOperations.of(svc);
            List<String> lines = new ArrayList<>();
            for (CaseRule r : rules()) ops.registerCaseRule(r);
            for (CaseRule r : rules()) {
                CaseOperations.CaseRuleEvaluation e = ops.evaluateCaseRule(r.name());
                lines.add(r.name() + " matched=" + e.matched() + " grouped=" + e.grouped() + " opened=" + e.opened()
                        + " case=" + describeCases(svc, r.name()));
            }
            CaseOperations.CaseRuleEvaluation again = ops.evaluateCaseRule("A-open-crit-fraud");
            lines.add("A-again matched=" + again.matched() + " grouped=" + again.grouped() + " opened=" + again.opened());
            return String.join("\n", lines);
        }
    }

    /** Every Case the rule raised, oldest first: its title, inherited correlation id and members in link order. */
    private static String describeCases(ObjectService svc, String rule) {
        List<String> out = new ArrayList<>();
        for (OperationalObject c : svc.allMatching(ObjectQuery.builder().objectType(ObjectType.CASE).build())) {
            if (!rule.equals(c.attributes().get(ObjectService.ATTR_RAISED_BY_RULE))) continue;
            List<String> members = svc.linksOf(c.id()).stream()
                    .filter(l -> l.fromId().equals(c.id()) && LinkRelationship.CONTAINS.equalsIgnoreCase(l.relationship()))
                    .map(ObjectLink::toId).toList();
            out.add(c.title().replaceAll("#[0-9a-f]{6}", "#g") + "|" + c.correlationId() + "|" + members);
        }
        out.sort(null);
        return out.toString();
    }

    private static ObjectStore duck() {
        try {
            return DbObjectStore.open("jdbc:duckdb:", null, null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test void inMemory() { assertEquals(GOLDEN, run(InMemoryObjectStore::new)); }

    @Test void duckDb() { assertEquals(GOLDEN, run(CaseRuleEvaluateGoldenTest::duck)); }
}
