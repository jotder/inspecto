package com.gamma.geolink;

import com.gamma.la.core.LinkEventTypes;
import com.gamma.la.api.InvRoutes;
import com.gamma.la.api.InvestigationRoutes;
import com.gamma.la.api.StandingDetection;
import com.gamma.la.api.ValueMeasureRoutes;
import com.gamma.la.api.ValueMeasures;
import com.gamma.la.api.WorkingSetRoutes;
import com.gamma.la.core.InvestigationEvaluator;
import com.gamma.la.core.SnapshotStore;
import com.gamma.control.HostContext;
import com.gamma.alert.AlertRule;
import com.gamma.alert.AlertService;
import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.ErrorCodes;
import com.gamma.control.RouteModule;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.query.DatasetRead;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * <b>Measure over the Working Set → Alert Rule</b> (LA-23, {@code docs/archived-documents/plans-archive/link-analysis-backlog-plan.md}
 * §2.7: {@code Template → Measure over the relation → Alert Rule → Alert → Incident}).
 *
 * <ul>
 *   <li>{@code GET /inv/investigations/{id}/measures?relation=&measure=} — the declared Measures over the
 *       Investigation's Working Set (entity, link, event and excluded counts, the deepest hop, links by kind), and
 *       optionally one more in the Measure shorthand — exactly what an Alert Rule bound below would compute.</li>
 *   <li>{@code POST /inv/investigations/{id}/alert-rules} — bind an Alert Rule to one of those Measures. The rule
 *       is an ordinary {@code alert-rule} component, armed in the running {@link AlertService}, and fires through
 *       its existing path: {@code ALERT_FIRED} + the {@code alert-rule.fired} Signal, the ALERT object, and — at
 *       CRITICAL — the Incident, deduped by rule within the Investigation's scope.</li>
 * </ul>
 *
 * <p><b>Access.</b> Both open through {@link InvestigationRoutes#open}: owner-only, the R3 Dataset gate and the
 * Enterprise PDP (D-E7) — so a non-owner's binding is refused as a 404, before any capability question is asked
 * of the body. The binding route is additionally gated on {@code canAuthorAlertRules}, as {@code POST /alerts/rules}
 * is. A sweep carries no caller, so the gate is carried to evaluation time by a BINDING recorded beside the
 * Investigation (the rule's canonical hash and the owner): {@link WorkingSetMeasures#value} evaluates nothing else.
 * ⚠ What that does NOT carry (sealed-Working-Set rules): a later PDP DENY, or the owner losing sight of the Dataset —
 * those rules read no Dataset. A VALUE-measure rule does read the live Dataset, so it additionally needs
 * {@code POST …/standing-detection} (LA-LIVE-DETECTION-1): the owner's authority is recorded and RE-DECIDED at every
 * sweep ({@link StandingDetection}), and without it the rule is never evaluated.
 *
 * <p>⚠ Once a rule fires, its Alert (and any Incident) is visible to whoever can read Alerts and Incidents — the
 * Investigation id, the relation, the measure, its value and the threshold, never an entity id. Binding a rule is
 * the owner's decision to disclose that much; the message says so in the response.
 */
public final class InvestigationMeasureRoutes implements RouteModule {

    private static final Set<String> RULE_FIELDS = Set.of("name", "relation", "measure", "comparator", "threshold",
            "severity");
    /** LA-18: a value-measure rule's body — the comparator and threshold are fixed (count of breaching entities ≥ 1). */
    private static final Set<String> VALUE_RULE_FIELDS = Set.of("name", "valueMeasure", "severity");

    @Override
    public void register(ApiContext api) {
        api.get("/inv/investigations/([^/]+)/measures", (e, m) -> measures(api, e, m.group(1)));
        // ⚠ A String LITERAL on purpose — CapabilityManifestTest's scanner matches only a literal argument.
        api.post("/inv/investigations/([^/]+)/alert-rules", ApiContext.withCapability("canAuthorAlertRules",
                (e, m) -> bind(api, e, m.group(1), api.body(e))));
        // LA-LIVE-DETECTION-1 (LD-2). Same String-literal rule; same capability as binding the rule it arms.
        api.post("/inv/investigations/([^/]+)/standing-detection", ApiContext.withCapability("canAuthorAlertRules",
                (e, m) -> enableStanding(api, e, m.group(1), api.body(e))));
        // LD-5. Same literal rule and capability: editing a bound rule in place and turning standing detection off.
        api.put("/inv/investigations/([^/]+)/alert-rules/([^/]+)", ApiContext.withCapability("canAuthorAlertRules",
                (e, m) -> edit(api, e, m.group(1), m.group(2), api.body(e))));
        api.delete("/inv/investigations/([^/]+)/standing-detection/([^/]+)", ApiContext.withCapability("canAuthorAlertRules",
                (e, m) -> disableStanding(api, e, m.group(1), m.group(2))));
    }

    /** {@code GET …/measures} — gates: {@link InvestigationRoutes#open} (503 · 422 · 403 · 404) → a bad measure 422. */
    private Object measures(ApiContext api, HttpExchange ex, String id) throws IOException {
        InvestigationRoutes.Inv inv = InvestigationRoutes.openForRead(api, ex, id);
        String relation = ApiContext.query(ex, "relation");
        String measure = ApiContext.query(ex, "measure");
        boolean[] cached = {false};
        WorkingSetRoutes.Relation rel = WorkingSetRoutes.relation(inv, cached);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("head", Map.of("step", rel.headStep(), "workingSetHash", rel.workingSetHash()));
        out.putAll(WorkingSetMeasures.declared(rel));
        if (measure != null && !measure.isBlank()) {
            String r = relation == null || relation.isBlank() ? "entities" : relation.trim();
            OptionalDouble v = compute(rel, r, measure.trim());
            Map<String, Object> asked = new LinkedHashMap<>();
            asked.put("relation", r);
            asked.put("measure", measure.trim());
            asked.put("value", v.isPresent() ? v.getAsDouble() : null);
            out.put("measure", asked);
        } else if (relation != null && !relation.isBlank()) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'relation' names the relation a 'measure' is computed over — give both");
        }
        out.put("key", rel.key());
        out.put("cached", cached[0]);
        emit(ex, LinkEventTypes.LINK_INVESTIGATION_MEASURED, "link.investigation.measured",
                "link.investigation.measured — " + id,
                b -> b.attr("investigationId", id).attr("key", rel.key()).attr("cached", cached[0])
                        .attr("measure", measure));
        return out;
    }

    /**
     * {@code POST …/alert-rules} — body {@code {name, relation?, measure, comparator, threshold, severity}}. Gates:
     * {@code canAuthorAlertRules} 403 → {@link InvestigationRoutes#open} (503 · 422 · 403 · 404 non-owner) → a field
     * outside that shape, an invalid rule, or a measure the relation cannot compute 422 → no alert engine 503 → the
     * name taken 409 → write the component, record the binding, arm the rule.
     */
    private Object bind(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        InvestigationRoutes.Inv inv = InvestigationRoutes.open(api, ex, id);
        Parsed parsed = parse(api, ex, inv, id, body);
        AlertRule rule = parsed.rule();
        ValueMeasures.Spec spec = parsed.spec();
        boolean valueRule = spec != null;
        OptionalDouble current = parsed.current();
        ValueMeasures.Result valued = parsed.valued();

        AlertService alerts = HostContext.of(api).service().alertService()
                .orElseThrow(() -> new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "alert engine unavailable"));
        var store = DatasetRead.registry(inv.writeRoot());
        if (store.get("alert-rule", rule.name()).isPresent())
            throw new ApiException(409, ErrorCodes.CONFLICT, "alert rule '" + rule.name() + "' already exists");
        // Maker-checker (ASSURE-MAKER-CHECKER-1): an approver could not replay this bind — the Investigation is
        // owner-only — so under a policy for Alert Rules it is refused rather than written around the policy.
        com.gamma.control.PendingChanges.holdRefusing(api, java.util.List.of("alert-rule"),
                "an Investigation Alert Rule is bound inside an owner-only Investigation, where no approver can apply it");
        Map<String, Object> written;
        try {
            written = store.write("alert-rule", rule.name(), rule.toMap()).content();
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
        }
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("rule", rule.name());
        binding.put("ruleHash", WorkingSetMeasures.ruleHash(rule));
        binding.put("investigation", id);
        binding.put("owner", inv.header().get("owner"));
        binding.put("boundBy", ApiContext.actor(ex));
        binding.put("boundAt", Instant.now().toString());
        inv.store().bindAlertRule(id, rule.name(), InvestigationEvaluator.canonical(binding));
        alerts.upsert(rule);   // armed last: a rule is never live without the binding that lets it evaluate

        emit(ex, LinkEventTypes.LINK_INVESTIGATION_ALERT_RULE_BOUND, "link.investigation.alert_rule.bound",
                "link.investigation.alert_rule.bound — " + rule.name() + " on " + id,
                b -> b.attr("rule", rule.name()).attr("investigationId", id).attr("relation", rule.relation())
                        .attr("measure", rule.measure()).attr("threshold", rule.threshold())
                        .attr("valueMeasure", rule.isValueMeasureRule() ? rule.valueMeasure().get("name") : null));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rule", written);
        out.put("current", current.isPresent() ? current.getAsDouble() : null);
        out.put("wouldFire", current.isPresent() && rule.breached(current.getAsDouble()));
        if (valued != null) out.putAll(ValueMeasureRoutes.answer(spec, valued));   // the entities it would name
        if (valueRule) out.put("standingDetection", "not enabled: a sweep reads the live Dataset only after the owner enables it "
                + "(POST /inv/investigations/" + id + "/standing-detection {rule}); until then the rule is not evaluated");
        out.put("disclosure", "when it fires, the Alert (and at CRITICAL the Incident) shows this Investigation's id, "
                + "the measure, its value and the threshold to everyone who can read Alerts and Incidents");
        return out;
    }

    /** A parsed, validated rule body and what it evaluates to now. */
    private record Parsed(AlertRule rule, ValueMeasures.Spec spec, OptionalDouble current, ValueMeasures.Result valued) {}

    /**
     * The body of a bind or an edit, validated: a field outside the shape, an invalid rule, an unsafe name, or a measure
     * the relation cannot compute is a 422, and a value-measure rule's whole Dataset is read R3-gated for this caller now.
     */
    private Parsed parse(ApiContext api, HttpExchange ex, InvestigationRoutes.Inv inv, String id, Map<String, Object> body)
            throws IOException {
        boolean valueRule = body.containsKey("valueMeasure");
        for (String k : body.keySet())
            if (valueRule && !VALUE_RULE_FIELDS.contains(k))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + k + "' is not a field of a value-measure Alert Rule "
                        + VALUE_RULE_FIELDS + " — it fires when at least one entity breaches the Measure's own thresholds");
            else if (!valueRule && !RULE_FIELDS.contains(k))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + k + "' is not a field of an Investigation Alert Rule " + RULE_FIELDS
                        + " — the Investigation is the path's, and its owner is recorded, not given");
        Map<String, Object> content = new LinkedHashMap<>(body);
        content.put("investigation", id);
        ValueMeasures.Spec spec = null;
        if (valueRule) {
            try {
                spec = ValueMeasures.parse(body.get("valueMeasure") instanceof Map<?, ?> vm
                        ? castMap(vm) : Map.of(), true);
            } catch (IllegalArgumentException e) {
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
            }
            content.put("valueMeasure", spec.toMap());   // every threshold spelled out: visible, and hashed
            content.put("comparator", "gte");
            content.put("threshold", 1);
        }
        AlertRule rule;
        try {
            rule = AlertRule.fromMap(content);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
        }
        if (!SnapshotStore.SAFE_ID.matcher(rule.name()).matches())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "alert rule name must match " + SnapshotStore.SAFE_ID.pattern());
        OptionalDouble current;
        ValueMeasures.Result valued = null;
        if (spec != null) {
            // The WHOLE Dataset the Investigation is bound to, R3-gated for this caller now (a sweep has none).
            String ds = inv.dataset();
            String relationSql = InvRoutes.relationFor(api, ex, inv.writeRoot(), ds);
            valued = evaluate(ds, relationSql, inv.header(), spec, ValueMeasures.agents(inv.writeRoot(), spec));
            current = OptionalDouble.of(valued.entities().size());
        } else {
            WorkingSetRoutes.Relation rel = WorkingSetRoutes.relation(inv, new boolean[1]);
            current = compute(rel, rule.relation(), rule.measure());   // 422 if the relation cannot
        }
        return new Parsed(rule, spec, current, valued);
    }

    /**
     * {@code POST …/standing-detection} — body {@code {rule}}: let a BOUND value-measure Alert Rule keep reading the live
     * Dataset on every Alert sweep (LA-LIVE-DETECTION-1, D-LD1 option A). The sweep acts as {@code sweep:<id>}, which
     * holds no capability; this records the owner's authority beside the rule's binding and {@link StandingDetection}
     * re-decides it at every sweep. Gates: {@code canAuthorAlertRules} 403 → {@link InvestigationRoutes#open} (503 · 422 ·
     * 403 · 404) → a body field other than {@code rule}, or an unsafe name 422 → a caller who is not the owner 403 → no
     * such bound value-measure rule 404 / 422 → the rule edited since binding 409 → the authority refused (a role-only
     * Dataset, an unshared Dataset, a masked column the basis cannot trace, a PDP DENY…) 422 → write the binding.
     * Enabling again re-snapshots the authority (the way back after a refusal).
     */
    private Object enableStanding(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        InvestigationRoutes.Inv inv = InvestigationRoutes.open(api, ex, id);
        for (String k : body.keySet())
            if (!"rule".equals(k))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + k + "' is not a field of this request — only 'rule' is");
        String ruleName = ApiContext.str(body, "rule");
        if (ruleName == null || !SnapshotStore.SAFE_ID.matcher(ruleName).matches())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'rule' must name a bound Alert Rule matching " + SnapshotStore.SAFE_ID.pattern());
        String actor = ApiContext.actor(ex);
        Object owner = inv.header().get("owner");
        if (owner == null || !actor.equals(String.valueOf(owner)))
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "only the Investigation's owner enables standing detection: "
                    + "the sweep acts with the owner's authority, which the owner alone may lend it");
        AlertService alerts = HostContext.of(api).service().alertService()
                .orElseThrow(() -> new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "alert engine unavailable"));
        // The rule AS ARMED — the exact one a sweep evaluates and whose hash the binding recorded (a stored copy
        // round-trips numbers through TOON and would hash differently).
        Map<String, Object> armed = alerts.rules().stream().filter(r -> ruleName.equals(r.get("name"))).findFirst()
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no armed alert rule '" + ruleName + "'"));
        AlertRule rule;
        Map<String, Object> binding;
        try {
            rule = AlertRule.fromMap(armed);
            String raw = inv.store().alertRuleBinding(id, ruleName).orElse(null);
            binding = raw == null ? null : new LinkedHashMap<>(castMap(ApiContext.JSON.readValue(raw, Map.class)));
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
        }
        if (binding == null || !id.equals(rule.investigation()))
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "alert rule '" + ruleName + "' is not bound to investigation '" + id + "'");
        if (!rule.isValueMeasureRule())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "only a value-measure Alert Rule watches the live Dataset; '"
                    + ruleName + "' watches the sealed Working Set, which does not move when the Dataset grows");
        if (!WorkingSetMeasures.ruleHash(rule).equals(binding.get("ruleHash")))
            throw new ApiException(409, ErrorCodes.CONFLICT, "alert rule '" + ruleName + "' was edited after it was bound — delete it and bind it again");
        Map<String, Object> masking = StandingDetection.masking(inv);
        if (masking == null)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "standing detection refused [" + StandingDetection.UNDECIDABLE
                    + "]: the masking basis of Dataset '" + inv.dataset() + "' could not be determined");
        var subject = ApiContext.subject(ex);
        StandingDetection.Authority authority = new StandingDetection.Authority("sweep:" + id, String.valueOf(owner),
                subject.map(com.gamma.control.Subject::capabilities).orElse(Set.of()),
                subject.map(com.gamma.control.Subject::dataScopes).orElse(null),
                subject.map(com.gamma.control.Subject::attributes).orElse(Map.of()), inv.dataset(), masking, actor,
                Instant.now().toString());
        StandingDetection.Verdict verdict = StandingDetection.check(inv.writeRoot(), inv.store(), id, inv.header(), authority);
        if (!verdict.allowed())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "standing detection refused [" + verdict.code() + "]: " + verdict.reason());
        com.gamma.control.PendingChanges.holdRefusing(api, java.util.List.of("alert-rule"),
                "standing detection lends an owner's authority to a caller-less sweep inside an owner-only Investigation, where no approver can apply it");
        boolean replaced = binding.containsKey(StandingDetection.KEY);
        binding.put(StandingDetection.KEY, authority.toMap());
        inv.store().bindAlertRule(id, ruleName, InvestigationEvaluator.canonical(binding));
        emit(ex, LinkEventTypes.LINK_STANDING_DETECTION_ENABLED, "link.standing_detection.enabled",
                "link.standing_detection.enabled — " + ruleName + " on " + id,
                b -> b.attr("rule", ruleName).attr("investigationId", id).attr("principal", authority.principal())
                        .attr("dataset", inv.dataset()).attr("replaced", replaced));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rule", ruleName);
        out.put("investigation", id);
        out.put("principal", authority.principal());
        out.put("dataset", inv.dataset());
        out.put("masking", masking);
        out.put("enabledAt", authority.enabledAt());
        out.put("replaced", replaced);
        out.put("authority", "each sweep reads Dataset '" + inv.dataset() + "' as " + owner + " and re-checks that they still "
                + "may, by user id, before reading; it computes and discloses aggregates only, and stops (recorded) when that "
                + "access, their lead role, an access policy or the masking basis changes");
        return out;
    }

    /** The binding of a rule to this Investigation, or a 404 when the rule is not bound here. */
    private static Map<String, Object> binding(InvestigationRoutes.Inv inv, String id, String ruleName) throws IOException {
        if (!SnapshotStore.SAFE_ID.matcher(ruleName).matches())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the rule name must match " + SnapshotStore.SAFE_ID.pattern());
        String raw = inv.store().alertRuleBinding(id, ruleName).orElse(null);
        if (raw == null)
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "alert rule '" + ruleName + "' is not bound to investigation '" + id + "'");
        return new LinkedHashMap<>(castMap(ApiContext.JSON.readValue(raw, Map.class)));
    }

    /**
     * {@code DELETE …/standing-detection/{rule}} — turn standing detection OFF for a bound rule (LD-5, D-LD15). The rule stays
     * bound and armed; its binding loses the recorded authority, so every sweep refuses {@code NOT_ENABLED} (a value-measure
     * rule reads no Dataset). It only narrows, so any caller who may author alert rules and open the Investigation may do it,
     * and it is idempotent. Gates: {@code canAuthorAlertRules} 403 → {@link InvestigationRoutes#open} (503 · 422 · 403 · 404)
     * → unsafe name 422 → rule not bound here 404 → drop the authority.
     */
    private Object disableStanding(ApiContext api, HttpExchange ex, String id, String ruleName) throws IOException {
        InvestigationRoutes.Inv inv = InvestigationRoutes.open(api, ex, id);
        Map<String, Object> binding = binding(inv, id, ruleName);
        boolean wasEnabled = binding.containsKey(StandingDetection.KEY);
        if (wasEnabled) {
            binding.remove(StandingDetection.KEY);
            inv.store().bindAlertRule(id, ruleName, InvestigationEvaluator.canonical(binding));
        }
        emit(ex, LinkEventTypes.LINK_STANDING_DETECTION_DISABLED, "link.standing_detection.disabled",
                "link.standing_detection.disabled — " + ruleName + " on " + id,
                b -> b.attr("rule", ruleName).attr("investigationId", id).attr("wasEnabled", wasEnabled));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rule", ruleName);
        out.put("investigation", id);
        out.put("enabled", false);
        out.put("wasEnabled", wasEnabled);
        return out;
    }

    /**
     * {@code PUT …/alert-rules/{rule}} — EDIT a bound rule in place (LD-5, D-LD16): the body is {@code POST …/alert-rules}'s
     * ({@code name}, when given, must equal the path's). The binding takes the new rule's hash and LOSES its standing-detection
     * authority — the owner granted it for the old rule — so the owner re-enables (re-snapshots) afterwards. Gates:
     * {@code canAuthorAlertRules} 403 → {@link InvestigationRoutes#open} (503 · 422 · 403 · 404) → a different {@code name} or a
     * body outside the shape 422 → no alert engine 503 → no such armed rule 404 → not bound to this Investigation 404 → armed
     * rule edited out of band since binding 409 → an invalid rule or unevaluable measure 422 → maker-checker policy refusal →
     * write the component, then the binding, then re-arm (so at every instant a sweep sees a hash mismatch and refuses).
     */
    private Object edit(ApiContext api, HttpExchange ex, String id, String ruleName, Map<String, Object> body) throws IOException {
        InvestigationRoutes.Inv inv = InvestigationRoutes.open(api, ex, id);
        Object named = body.get("name");
        if (named != null && !ruleName.equals(String.valueOf(named)))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'name' cannot change: the rule is '" + ruleName
                    + "' (delete it and bind a new one to rename it)");
        AlertService alerts = HostContext.of(api).service().alertService()
                .orElseThrow(() -> new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "alert engine unavailable"));
        Map<String, Object> binding = binding(inv, id, ruleName);
        Map<String, Object> armed = alerts.rules().stream().filter(r -> ruleName.equals(r.get("name"))).findFirst()
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no armed alert rule '" + ruleName + "'"));
        AlertRule old;
        try {
            old = AlertRule.fromMap(armed);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
        }
        if (!id.equals(old.investigation()))
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "alert rule '" + ruleName + "' is not bound to investigation '" + id + "'");
        if (!WorkingSetMeasures.ruleHash(old).equals(binding.get("ruleHash")))
            throw new ApiException(409, ErrorCodes.CONFLICT, "alert rule '" + ruleName + "' was edited after it was bound — delete it and bind it again");
        Map<String, Object> next = new LinkedHashMap<>(body);
        next.put("name", ruleName);
        Parsed parsed = parse(api, ex, inv, id, next);
        AlertRule rule = parsed.rule();
        com.gamma.control.PendingChanges.holdRefusing(api, java.util.List.of("alert-rule"),
                "an Investigation Alert Rule is edited inside an owner-only Investigation, where no approver can apply it");
        try {
            DatasetRead.registry(inv.writeRoot()).write("alert-rule", rule.name(), rule.toMap());
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
        }
        boolean dropped = binding.remove(StandingDetection.KEY) != null;
        binding.put("ruleHash", WorkingSetMeasures.ruleHash(rule));
        binding.put("boundBy", ApiContext.actor(ex));
        binding.put("boundAt", Instant.now().toString());
        inv.store().bindAlertRule(id, rule.name(), InvestigationEvaluator.canonical(binding));
        alerts.upsert(rule);   // re-armed last, as in bind
        emit(ex, LinkEventTypes.LINK_INVESTIGATION_ALERT_RULE_EDITED, "link.investigation.alert_rule.edited",
                "link.investigation.alert_rule.edited — " + rule.name() + " on " + id,
                b -> b.attr("rule", rule.name()).attr("investigationId", id).attr("standingDetectionDropped", dropped));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rule", rule.toMap());
        out.put("current", parsed.current().isPresent() ? parsed.current().getAsDouble() : null);
        out.put("wouldFire", parsed.current().isPresent() && rule.breached(parsed.current().getAsDouble()));
        if (parsed.valued() != null) out.putAll(ValueMeasureRoutes.answer(parsed.spec(), parsed.valued()));
        out.put("replaced", true);
        if (parsed.spec() != null) out.put("standingDetection", "disabled: the rule was edited, so the owner must enable it again "
                + "(POST /inv/investigations/" + id + "/standing-detection {rule}); until then it is not evaluated");
        out.put("disclosure", "when it fires, the Alert (and at CRITICAL the Incident) shows this Investigation's id, "
                + "the measure, its value and the threshold to everyone who can read Alerts and Incidents");
        return out;
    }

    /** A value Measure over the whole Dataset, its failures as 422s. */
    static ValueMeasures.Result evaluate(String dataset, String relationSql, Map<String, Object> header,
                                         ValueMeasures.Spec spec, ValueMeasures.Agents agents) {
        try {
            return ValueMeasures.forInvestigation(dataset, relationSql, header, spec, agents);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
        } catch (java.sql.SQLException | IOException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "value measure failed: "
                    + com.gamma.util.DuckDbUtil.withoutPendingQueryPreamble(e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static OptionalDouble compute(WorkingSetRoutes.Relation rel, String relation, String measure) {
        try {
            if (!rel.tables().containsKey(relation))
                throw new IllegalArgumentException("relation must be one of " + WorkingSetRoutes.COLUMNS.keySet()
                        + ", got '" + relation + "'");
            return WorkingSetMeasures.compute(rel.tables().get(relation), relation, measure);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
        }
    }

    /** Best-effort audit (LA-04 pattern), emitted only AFTER the act succeeded so the trail never over-claims. */
    private static void emit(HttpExchange ex, String type, String action, String message,
                             UnaryOperator<Event.Builder> attrs) {
        try {
            Event.Builder b = Event.builder(type).source("inv").message(message)
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action(action).actionCategory("analysis");
            EventLog.current().emit(attrs.apply(b));
        } catch (RuntimeException ignored) {
            // best effort — the act already succeeded
        }
    }
}
