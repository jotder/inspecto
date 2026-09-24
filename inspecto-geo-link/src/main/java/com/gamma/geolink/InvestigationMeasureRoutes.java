package com.gamma.geolink;

import com.gamma.alert.AlertRule;
import com.gamma.alert.AlertService;
import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.ErrorCodes;
import com.gamma.control.RouteModule;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.pipeline.ComponentStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * <b>Measure over the Working Set → Alert Rule</b> (LA-23, {@code docs/superpower/link-analysis-backlog-plan.md}
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
 * ⚠ What that does NOT carry: a later PDP DENY, or the owner losing sight of the Dataset. The PDP judges a request
 * Subject, and a sweep has none — both are checked when the rule is bound, not on every sweep.
 *
 * <p>⚠ Once a rule fires, its Alert (and any Incident) is visible to whoever can read Alerts and Incidents — the
 * Investigation id, the relation, the measure, its value and the threshold, never an entity id. Binding a rule is
 * the owner's decision to disclose that much; the message says so in the response.
 */
public final class InvestigationMeasureRoutes implements RouteModule {

    private static final Set<String> RULE_FIELDS = Set.of("name", "relation", "measure", "comparator", "threshold",
            "severity");

    @Override
    public void register(ApiContext api) {
        api.get("/inv/investigations/([^/]+)/measures", (e, m) -> measures(api, e, m.group(1)));
        // ⚠ A String LITERAL on purpose — CapabilityManifestTest's scanner matches only a literal argument.
        api.post("/inv/investigations/([^/]+)/alert-rules", ApiContext.withCapability("canAuthorAlertRules",
                (e, m) -> bind(api, e, m.group(1), api.body(e))));
    }

    /** {@code GET …/measures} — gates: {@link InvestigationRoutes#open} (503 · 422 · 403 · 404) → a bad measure 422. */
    private Object measures(ApiContext api, HttpExchange ex, String id) throws IOException {
        InvestigationRoutes.Inv inv = InvestigationRoutes.open(api, ex, id);
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
        emit(ex, EventType.LINK_INVESTIGATION_MEASURED, "link.investigation.measured",
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
        for (String k : body.keySet())
            if (!RULE_FIELDS.contains(k))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + k + "' is not a field of an Investigation Alert Rule " + RULE_FIELDS
                        + " — the Investigation is the path's, and its owner is recorded, not given");
        Map<String, Object> content = new LinkedHashMap<>(body);
        content.put("investigation", id);
        AlertRule rule;
        try {
            rule = AlertRule.fromMap(content);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
        }
        if (!SnapshotStore.SAFE_ID.matcher(rule.name()).matches())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "alert rule name must match " + SnapshotStore.SAFE_ID.pattern());
        WorkingSetRoutes.Relation rel = WorkingSetRoutes.relation(inv, new boolean[1]);
        OptionalDouble current = compute(rel, rule.relation(), rule.measure());   // 422 if the relation cannot

        AlertService alerts = api.service().alertService()
                .orElseThrow(() -> new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "alert engine unavailable"));
        ComponentStore store = new ComponentStore(inv.writeRoot().resolve("registry"));
        if (store.get("alert-rule", rule.name()).isPresent())
            throw new ApiException(409, ErrorCodes.CONFLICT, "alert rule '" + rule.name() + "' already exists");
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

        emit(ex, EventType.LINK_INVESTIGATION_ALERT_RULE_BOUND, "link.investigation.alert_rule.bound",
                "link.investigation.alert_rule.bound — " + rule.name() + " on " + id,
                b -> b.attr("rule", rule.name()).attr("investigationId", id).attr("relation", rule.relation())
                        .attr("measure", rule.measure()).attr("threshold", rule.threshold()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rule", written);
        out.put("current", current.isPresent() ? current.getAsDouble() : null);
        out.put("wouldFire", current.isPresent() && rule.breached(current.getAsDouble()));
        out.put("disclosure", "when it fires, the Alert (and at CRITICAL the Incident) shows this Investigation's id, "
                + "the measure, its value and the threshold to everyone who can read Alerts and Incidents");
        return out;
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
