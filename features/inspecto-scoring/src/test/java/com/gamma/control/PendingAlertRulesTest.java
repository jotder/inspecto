package com.gamma.control;

import com.gamma.audit.Event;
import com.gamma.event.EventLog;
import com.gamma.audit.EventType;
import com.gamma.pipeline.ComponentStore;
import com.gamma.risk.RiskScoreEvaluator;
import com.gamma.risk.RiskScoreModel;
import com.gamma.risk.RiskScorer;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TEMPLATE-RISK-SCORE-ALERT-RULE-1 (operator 2026-10-06, deferred seed): a pending Alert Rule over a Risk Score's
 * {@code _latest} output stays pending until that output exists, is then created through the normal save gate
 * ({@link AlertRoutes#parse}, the {@code by} Schema check), exactly once; a refusal stays pending and is audited.
 */
class PendingAlertRulesTest {

    private static final String MODEL = "acct";
    private static final String RULE = "high_risk_acct";

    @TempDir Path space;
    private Path config;
    private Path data;
    private final List<Event> audit = new ArrayList<>();
    private EventLog events;

    @BeforeEach
    void setUp() throws Exception {
        com.gamma.etl.EditionFeatures.overrideForTest(Set.of(com.gamma.etl.EditionFeatures.ALERT_DISPATCH));
        DuckDbUtil.loadDriver();
        config = Files.createDirectories(space.resolve("config"));
        data = Files.createDirectories(space.resolve("data"));
        ComponentStore store = new ComponentStore(config.resolve("registry"));
        store.write("risk-score", MODEL, model());
        Path pending = Files.createDirectories(config.resolve(PendingAlertRules.DIR));
        Files.writeString(pending.resolve(RULE + ".toon"), """
                afterScore:
                  kind: risk-score
                  model: acct
                dataset: risk_scores_acct_latest
                measure: "max(score)"
                by[2]: model, entity_key
                comparator: gte
                threshold: 60
                severity: CRITICAL
                """);
        events = EventLog.create();
        events.addSubscriber(e -> { if (EventType.AUDIT.equals(e.type())) audit.add(e); });
    }

    @AfterEach
    void tearDown() {
        com.gamma.etl.EditionFeatures.overrideForTest(null);
    }

    private static Map<String, Object> model() {
        return Map.of("entityType", "account", "highThreshold", 60, "factors", List.of(Map.of("id", "f", "label", "f",
                "dataset", "feat", "key", "account_id", "measure", "max(x)", "weight", 10, "cap", 100)));
    }

    private List<String> produced() {
        return PendingAlertRules.onRiskScoreProduced(config, () -> data, "risk-score", MODEL, null, events);
    }

    private void runRiskScore() throws Exception {
        Map<String, Object> content = new ComponentStore(config.resolve("registry")).get("risk-score", MODEL)
                .orElseThrow().content();
        RiskScoreEvaluator.write(data, RiskScoreModel.fromMap(MODEL, content), "v1", "run-1", Instant.now(),
                List.of(new RiskScorer.Scored("a1", 70, true, List.of())));
    }

    private boolean ruleExists() {
        return new ComponentStore(config.resolve("registry")).exists("alert-rule", RULE);
    }

    private List<String> actions() {
        return audit.stream().map(e -> String.valueOf(e.attributes().get(com.gamma.audit.AuditAttrs.ACTION))).toList();
    }

    @Test
    void pendingBeforeTheFirstRunAndARefusalIsAuditedAndStaysPending() {
        assertEquals(List.of(RULE), PendingAlertRules.list(config).stream().map(m -> m.get("name")).toList());
        // The output store has no Schema yet: the normal gate refuses, nothing is forced.
        assertEquals(List.of(), produced());
        assertFalse(ruleExists());
        assertEquals(1, PendingAlertRules.list(config).size(), "a refusal stays pending");
        assertEquals(List.of("alert-rule.pending.refused"), actions());
        assertTrue(String.valueOf(audit.get(0).attributes().get("reason")).contains("has not written"),
                "the refusal names the gate's reason: " + audit.get(0).attributes());
    }

    @Test
    void createdThroughTheGateAfterTheRunExactlyOnce() throws Exception {
        produced();                       // refused, before the run
        runRiskScore();
        assertEquals(List.of(RULE), produced(), "retried on the next run and created");
        assertTrue(ruleExists());
        Map<String, Object> stored = new ComponentStore(config.resolve("registry")).get("alert-rule", RULE)
                .orElseThrow().content();
        assertNull(stored.get(PendingAlertRules.AFTER), "the pending marker is not part of the rule");
        assertEquals("risk_scores_acct_latest", stored.get("dataset"));
        assertTrue(new ComponentStore(config.resolve("registry")).exists("dataset", "risk_scores_acct_latest"),
                "the Dataset over the output is registered with the rule");
        assertEquals(List.of(), PendingAlertRules.list(config));
        assertEquals(List.of("alert-rule.pending.refused", "alert-rule.pending.created"), actions());
        // Never twice: the next run finds nothing pending.
        assertEquals(List.of(), produced());
        assertEquals(2, actions().size());
    }

    @Test
    void anotherModelsRunDoesNotReleaseIt() throws Exception {
        runRiskScore();
        assertEquals(List.of(), PendingAlertRules.onRiskScoreProduced(config, () -> data, "risk-score", "other", null, events));
        assertEquals(List.of(), PendingAlertRules.onRiskScoreProduced(config, () -> data, "anomaly-model", MODEL, null, events),
                "an Anomaly Model of the same id is another score kind (D-AD7)");
        assertFalse(ruleExists());
        assertEquals(1, PendingAlertRules.list(config).size());
    }

    @Test
    void anExistingRuleIsNeverOverwritten() throws Exception {
        runRiskScore();
        ComponentStore store = new ComponentStore(config.resolve("registry"));
        store.write("alert-rule", RULE, Map.of("dataset", "risk_scores_acct_latest", "measure", "max(score)",
                "comparator", "gte", "threshold", 90, "severity", "WARNING"));
        assertEquals(List.of(), produced());
        assertEquals("90", String.valueOf(store.get("alert-rule", RULE).orElseThrow().content().get("threshold")));
        assertEquals(List.of(), PendingAlertRules.list(config));
        assertEquals(List.of("alert-rule.pending.dropped"), actions());
    }

    @Test
    void anApprovalPolicyOnAlertRulesKeepsItPendingEvenWithTheOutputPresent() throws Exception {
        runRiskScore();
        Files.writeString(config.resolve("approval.toon"), """
                approval:
                  alert-rule:
                    required: true
                """);
        assertEquals(List.of(), produced());
        assertFalse(ruleExists());
        assertEquals(1, PendingAlertRules.list(config).size());
        assertEquals(List.of("alert-rule.pending.refused"), actions());
    }

    @Test
    void aDecisionRuleGuardRefusalKeepsItPendingAndAudited() throws Exception {
        runRiskScore();
        Files.writeString(config.resolve(PendingAlertRules.DIR).resolve(RULE + ".toon"), """
                afterScore:
                  kind: risk-score
                  model: acct
                dataset: risk_scores_acct_latest
                measure: "max(score)"
                comparator: gte
                threshold: 60
                severity: CRITICAL
                consequences[1]{action}:
                  invoke-api
                """);
        assertEquals(List.of(), produced());
        assertFalse(ruleExists());
        assertFalse(new ComponentStore(config.resolve("registry")).exists("dataset", "risk_scores_acct_latest"),
                "the guard runs before any write");
        assertEquals(1, PendingAlertRules.list(config).size(), "a guard refusal stays pending");
        assertEquals(List.of("alert-rule.pending.refused"), actions());
        assertTrue(String.valueOf(audit.get(0).attributes().get("reason")).contains("background writer"),
                "the refusal names the guard's reason: " + audit.get(0).attributes());
    }

    @Test
    void theTemplateCheckRefusesARuleThatDoesNotReadItsModelsOutput() {
        Map<String, Object> ok = Map.of("afterScore", Map.of("kind", "risk-score", "model", MODEL), "dataset", "risk_scores_acct_latest",
                "measure", "max(score)", "comparator", "gte", "threshold", 60, "severity", "CRITICAL");
        PendingAlertRules.requireDeclarable(config, ok, RULE);
        Map<String, Object> other = new java.util.HashMap<>(ok);
        other.put("dataset", "pf_daily_summary");
        assertThrows(IllegalArgumentException.class, () -> PendingAlertRules.requireDeclarable(config, other, RULE));
        Map<String, Object> unknown = new java.util.HashMap<>(ok);
        unknown.put("afterScore", Map.of("kind", "risk-score", "model", "nope"));
        assertThrows(IllegalArgumentException.class, () -> PendingAlertRules.requireDeclarable(config, unknown, RULE));
        // D-AD7: the trigger names its score kind; the old flat afterRiskScore form and an unknown kind are refused.
        Map<String, Object> flat = new java.util.HashMap<>(ok);
        flat.put("afterScore", MODEL);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> PendingAlertRules.requireDeclarable(config, flat, RULE))
                .getMessage().contains("must be {kind, model}"));
        Map<String, Object> kind = new java.util.HashMap<>(ok);
        kind.put("afterScore", Map.of("kind", "kpi", "model", MODEL));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> PendingAlertRules.requireDeclarable(config, kind, RULE))
                .getMessage().contains("afterScore.kind must be one of [anomaly-model, risk-score]"));
        Map<String, Object> wrongKind = new java.util.HashMap<>(ok);
        wrongKind.put("afterScore", Map.of("kind", "anomaly-model", "model", MODEL));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> PendingAlertRules.requireDeclarable(config, wrongKind, RULE))
                .getMessage().contains("names unknown anomaly-model 'acct'"), "a risk-score of that id is not an anomaly-model");
    }

    // PENDING-REFUSAL-REASON (operator 2026-10-06): the latest refusal is served on the pending entry.

    @SuppressWarnings("unchecked")
    private Map<String, Object> lastRefusal() {
        return (Map<String, Object>) PendingAlertRules.list(config).get(0).get("lastRefusal");
    }

    @Test
    void noLastRefusalBeforeAnyRunThenTheReasonAndTimeAfterARefusal() throws Exception {
        String before = Files.readString(config.resolve(PendingAlertRules.DIR).resolve(RULE + ".toon"));
        assertFalse(PendingAlertRules.list(config).get(0).containsKey("lastRefusal"), "no run, no refusal");
        Instant t0 = Instant.now().minusSeconds(1);
        produced();
        Map<String, Object> r = lastRefusal();
        assertNotNull(r, "a refusal is served on the entry");
        assertTrue(String.valueOf(r.get("reason")).contains("has not written"), r.toString());
        assertFalse(Instant.parse(String.valueOf(r.get("at"))).isBefore(t0), r.toString());
        assertEquals(before, Files.readString(config.resolve(PendingAlertRules.DIR).resolve(RULE + ".toon")),
                "the rule body is not altered");
        assertEquals(List.of(RULE), PendingAlertRules.list(config).stream().map(m -> m.get("name")).toList(),
                "the sidecar is never listed as a pending rule");
        assertEquals(List.of("alert-rule.pending.refused"), actions(), "the AUDIT event is kept");
        // No host path leaks: neither the Space's absolute location nor the temp root appears.
        String reason = String.valueOf(r.get("reason"));
        assertFalse(reason.contains(space.toAbsolutePath().toString()), reason);
        assertFalse(reason.contains(space.getParent().toAbsolutePath().toString()), reason);
    }

    @Test
    void aLaterSuccessRemovesTheEntryAndItsRefusal() throws Exception {
        produced();
        assertNotNull(lastRefusal());
        runRiskScore();
        assertEquals(List.of(RULE), produced());
        assertEquals(List.of(), PendingAlertRules.list(config));
        try (var s = Files.list(config.resolve(PendingAlertRules.DIR))) {
            assertEquals(List.of(), s.toList(), "no sidecar or temp file is left behind");
        }
    }

    @Test
    void scrubRemovesTheSpacesAbsoluteLocation() {
        String abs = space.toAbsolutePath().resolve("data").resolve("x.parquet").toString();
        String scrubbed = PendingAlertRules.scrub(config, "cannot read " + abs);
        assertFalse(scrubbed.contains(space.toAbsolutePath().toString()), scrubbed);
        assertTrue(scrubbed.contains("x.parquet"), scrubbed);
    }
}
