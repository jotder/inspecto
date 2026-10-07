package com.gamma.control;

import com.gamma.event.EventLog;
import com.gamma.job.JobService;
import com.gamma.workflow.ObjectType;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.query.ConditionTree;
import com.gamma.signal.Ref;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.gamma.access.WriteGates;

/**
 * Decision Rule routes ({@code /decision-rules*}) — the business-logic/routing third of the Rules
 * triad (distinct from Expectation = data-quality and Alert Rule = alerting; {@code docs/GLOSSARY.md}
 * §Decision Rule). Authored objects (full CRUD), persisted as {@code decision-rule} components under
 * {@code <write-root>/registry} exactly like {@link ExpectationRoutes} — same store, same fail-closed
 * gates (503 no write root, 422 bad body, 409 duplicate create, 404 unknown).
 *
 * <p><b>Scope of this cut.</b> CRUD + {@code apply} mirror the mock reference implementation
 * ({@code decision-rules.handler.ts} / {@code decision.ts}, which this replaces). {@code simulate}
 * goes beyond the mock (which still returns canned demo counts): it evaluates the rule's {@code when}
 * condition tree over a caller-supplied {@code sampleRows} batch via
 * {@link com.gamma.query.ConditionTree} (the same semantics the authoring UI previews offline) and
 * returns the real {@code matched}/{@code total} counts. A request with no {@code sampleRows} yields
 * {@code 0/0} — there is no ambient record source for a rule whose target is a pipeline/job, so the
 * sample is the row source (see {@code docs/okf/backend/control-plane/decision-rules.md}).
 * {@code apply} executes each consequence against the platform primitive that already exists —
 * {@code emit-signal} onto this space's Signal Ledger, {@code start-job} via {@link JobService},
 * {@code trigger-pipeline} via {@code CollectorService#triggerRunAsync}, {@code create-incident} by
 * opening a managed Incident (the author-selectable, any-severity generalization of the
 * {@code create-alert} high-severity auto-promotion) — and emits a descriptive stub
 * signal for the remaining platform actions ({@code render-widget},
 * {@code generate-report}), matching the mock's own scope — {@code invoke-api} proposes a pending
 * Action Request on the rule's Incident ({@link ActionRequestRoutes#propose}), never a direct call; the routing actions
 * ({@code route}/{@code tag}/{@code quarantine}/{@code drop}) are record-level — {@code simulate}
 * counts the rows they would affect, and they take effect during live pipeline runs via
 * {@link com.gamma.query.DecisionRuleApplier} (every batch applies the target pipeline's enabled rules
 * between transform and write), so {@code apply} has nothing to execute for them on demand.
 */
final class DecisionRoutes implements RouteModule {

    private static final String TYPE = "decision-rule";

    @Override
    public void register(ApiContext api) {
        api.get("/decision-rules", (e, m) -> list(api));
        api.post("/decision-rules", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> create(api, e, api.body(e))));
        api.put("/decision-rules/([^/]+)", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> update(api, e, ApiContext.name(m), api.body(e))));
        api.delete("/decision-rules/([^/]+)", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> delete(api, e, ApiContext.name(m))));
        api.post("/decision-rules/([^/]+)/simulate", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> simulate(api, ApiContext.name(m), api.body(e))));
        api.post("/decision-rules/([^/]+)/apply", ApiContext.withCapability("canOperateRuns",
                (e, m) -> apply(api, e, ApiContext.name(m))));
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────────

    private Object list(ApiContext api) {
        Path root = api.writeRoot() == null ? null : api.writeRoot().resolve("registry");
        if (root == null) return List.of();
        return new ComponentStore(root).list(TYPE).stream()
                .map(ComponentRegistry.Component::content)
                .sorted(Comparator.comparingInt(DecisionRoutes::priorityOf)
                        .thenComparing(c -> String.valueOf(c.get("name"))))
                .toList();
    }

    private Object create(ApiContext api, com.sun.net.httpserver.HttpExchange e, Map<String, Object> body) throws IOException {
        ComponentStore store = store(api);
        Map<String, Object> rule = normalize(body);
        String name = requireName(rule);
        if (RouteErrors.exists(store, TYPE, name))
            throw new ApiException(409, ErrorCodes.CONFLICT, "decision rule '" + name + "' already exists (use PUT to update)");
        long now = System.currentTimeMillis();
        rule.put("lastSimulation", null);
        rule.put("createdAt", now);
        rule.put("updatedAt", now);
        rule = DecisionRuleGuard.prepare(e, rule, null, store, name);   // invoke-api gate + server-stamped makers
        PendingChanges.hold(api, e, TYPE, name, rule, null);   // maker-checker
        return write(store, name, rule);
    }

    private Object update(ApiContext api, com.sun.net.httpserver.HttpExchange e, String name, Map<String, Object> body) throws IOException {
        ComponentStore store = store(api);
        Map<String, Object> prev = RouteErrors.existing(store, TYPE, "decision rule", name);
        Map<String, Object> rule = normalize(body);
        rule.put("name", name);
        rule.put("lastSimulation", prev.get("lastSimulation"));
        rule.put("createdAt", prev.getOrDefault("createdAt", System.currentTimeMillis()));
        rule.put("updatedAt", System.currentTimeMillis());
        rule = DecisionRuleGuard.prepare(e, rule, prev, store, name);   // invoke-api gate + server-stamped makers
        PendingChanges.hold(api, e, TYPE, name, rule, prev);   // maker-checker
        return write(store, name, rule);
    }

    private Object delete(ApiContext api, com.sun.net.httpserver.HttpExchange e, String name) throws IOException {
        ComponentStore store = store(api);
        Map<String, Object> current = RouteErrors.existing(store, TYPE, "decision rule", name);   // 404 if absent
        PendingChanges.hold(api, e, TYPE, name, null, current);   // maker-checker
        store.delete(TYPE, name);
        return Map.of("deleted", name);
    }

    // ── simulate / apply ─────────────────────────────────────────────────────────

    /** Dry-run preview (see class doc): evaluate the rule's {@code when} tree over the request's
     *  {@code sampleRows} via {@link ConditionTree} and stamp the real {@code matched}/{@code total}.
     *  No sample ⇒ {@code 0/0}. Not an authoring edit (MET-5 parity — no version archived). */
    private Object simulate(ApiContext api, String name, Map<String, Object> body) throws IOException {
        ComponentStore store = store(api);
        Map<String, Object> rule = RouteErrors.existing(store, TYPE, "decision rule", name);
        List<Map<String, Object>> sample = ApiContext.sampleRows(body);
        Map<String, Object> sim = new LinkedHashMap<>();
        try {
            sim.put("matched", ConditionTree.matched(rule.get("when"), sample));
        } catch (IllegalArgumentException e) {   // a rule stored before the save guard — never "matches all"
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "decision rule 'when': " + e.getMessage());
        }
        sim.put("total", sample.size());
        sim.put("checkedAt", System.currentTimeMillis());
        Map<String, Object> next = new LinkedHashMap<>(rule);
        next.put("lastSimulation", sim);
        next.put("updatedAt", System.currentTimeMillis());
        store.write(TYPE, name, next, false);   // a simulation stamp isn't an authoring edit (MET-5 parity)
        return next;
    }

    /** {@code POST /decision-rules/{name}/apply} — a PERSON applying the rule ({@code automatic=false}),
     *  attributed to the request's actor. */
    private Object apply(ApiContext api, com.sun.net.httpserver.HttpExchange e, String name) throws IOException {
        Map<String, Object> rule = RouteErrors.existing(store(api), TYPE, "decision rule", name);
        refuseTargetSpace(rule);
        refuseExchangeNamespace(rule);
        validateEmitSignals(rule);   // a rule stored before the save guard is refused, never half-run
        Map<String, Object> record = applyRecord(api.body(e));
        refuseUnmappableOrUngrantedEmits(e, rule, record);
        return applyConsequences(api, name, rule, false, ApiContext.actor(e), record);
    }

    /**
     * The one apply seam: execute every consequence through whichever real platform primitive exists
     * (see class doc for per-action mapping); never side-effects the rule's stored content.
     *
     * <p>{@code automatic} states HOW the rule is being applied, and every caller passes it explicitly — it
     * is never inferred from the calling thread (operator 2026-09-25). {@code false} = a person applied it
     * (the {@code /apply} route, the SPA's Apply action): a {@code start-job} on a DISABLED job runs it, like
     * the job's own Run now, attributed to {@code actor}. {@code true} = the engine fired it (a signal / event /
     * schedule evaluation): a disabled job is "not scheduled", so it is skipped. ⚠ No automatic caller exists
     * yet — platform consequences are inert in live runs ({@link com.gamma.query.DecisionRuleApplier}); a
     * future one must pass {@code true}.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> applyConsequences(ApiContext api, String name, Map<String, Object> rule,
                                                 boolean automatic, String actor) {
        return applyConsequences(api, name, rule, automatic, actor, Map.of());
    }

    /** {@link #applyConsequences} with the matched {@code record} an {@code emit-signal}'s {@code payload}
     *  mapping reads its values from. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> applyConsequences(ApiContext api, String name, Map<String, Object> rule,
                                                 boolean automatic, String actor, Map<String, Object> record) {
        List<Map<String, Object>> consequences = (List<Map<String, Object>>) (List<?>)
                (rule.get("consequences") instanceof List<?> l ? l : List.of());
        List<Map<String, Object>> executed = consequences.stream()
                .map(c -> executeOne(api, name, rule, c, automatic, actor, record)).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rule", name);
        result.put("executed", executed);
        return result;
    }

    private static Map<String, Object> executeOne(ApiContext api, String ruleName, Map<String, Object> rule,
                                                  Map<String, Object> c, boolean automatic, String actor,
                                                  Map<String, Object> record) {
        String action = String.valueOf(c.get("action"));
        String status = "skipped";
        String detail;
        String runId = null;
        String actionRequestId = null;
        switch (action) {
            case "emit-signal" -> {
                String type = paramStr(c, "type", "decision-rule." + ruleName);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("rule", ruleName);
                payloadMap(c).forEach((key, field) -> payload.put(key, record.get(String.valueOf(field))));
                String offerTo = params(c).get(OFFER_TO) instanceof String to ? to : null;
                emitSignal(actor, type, "decision-rule:" + ruleName, payload,
                        offerTo == null ? Map.of() : Map.of(SignalOfferGrants.ATTR_OFFER_TO, offerTo));
                status = "executed";
                detail = "emitted signal '" + type + "'" + (offerTo == null ? "" : " offered to '" + offerTo + "'");
            }
            case "create-alert" -> {
                String alertName = paramStr(c, "rule", ruleName);
                String severity = paramStr(c, "severity", "warning");
                // Always record the decision on the ledger.
                emitSignal(actor, "decision-rule.create-alert", "decision-rule:" + ruleName,
                        Map.of("alert", alertName, "severity", severity));
                status = "executed";
                // Author a real Alert Rule (S6) through the exact same validation/persistence path as the
                // human-facing POST /alerts/rules, when the consequence's params carry enough of an
                // alert-rule body to be valid (metric+comparator+threshold+window, or dataset+measure —
                // see AlertRule's constructor). A consequence that (like the pre-S6 stub shape) only
                // carries {rule, severity} — not enough to author a real rule — stays ledger-signal-only,
                // exactly as before; this is a deliberate, conservative scope cut (see event-signal-backbone
                // plan S6 report) rather than inventing defaults for fields with no sane default
                // (a threshold, a window).
                String authoredDetail = null;
                if (looksLikeAlertRuleBody(c)) {
                    Map<String, Object> alertBody = new LinkedHashMap<>(params(c));
                    alertBody.putIfAbsent("name", alertName);
                    alertBody.putIfAbsent("severity", severity.toUpperCase(java.util.Locale.ROOT));
                    try {
                        AlertRoutes.authorFromConsequence(api, alertBody);
                        authoredDetail = "authored Alert Rule '" + alertName + "'";
                    } catch (ApiException | IOException authoringFailure) {
                        authoredDetail = "could not author Alert Rule '" + alertName + "': " + authoringFailure.getMessage();
                    }
                }
                // High-severity (critical/error) decisions also open a managed Incident, deduped to one
                // open Incident per rule (correlationId = the rule), so they enter triage — the same
                // signal→Incident wiring the alert/recon paths use. Lower severities stay a ledger signal
                // (+ the authored rule, when authored) only.
                String corr = "decision-rule:" + ruleName;
                String incidentDetail = null;
                // ⚠ Through the seam since EDG-01 cell 7, and empty on a bundle without inspecto-ops —
                // in which case no Incident is raised and the decision still executes and audits.
                com.gamma.objects.ObjectAccess objects = HostContext.of(api).service().objects().orElse(null);
                if (isHighSeverity(severity)) {
                    // ⛔ Three outcomes, not two. An absent module must NOT be reported as "already open":
                    // that is a different fact, and an operator reading it would believe an Incident exists.
                    if (objects == null) {
                        incidentDetail = "decision '" + alertName + "' — no Incident opened, operational "
                                + "objects are not installed in this bundle";
                    } else if (!objects.hasActive(ObjectType.INCIDENT, corr)) {
                        objects.open(ObjectType.INCIDENT, "Decision Rule " + alertName,
                                "Raised by Decision Rule '" + ruleName + "'", severity, corr,
                                Map.of("rule", ruleName, "decisionRule", ruleName, "severity", severity));
                        incidentDetail = "opened Incident for '" + alertName + "' (" + severity + ")";
                    } else {
                        incidentDetail = "decision '" + alertName + "' — Incident already open";
                    }
                }
                detail = java.util.stream.Stream.of(authoredDetail, incidentDetail)
                        .filter(java.util.Objects::nonNull)
                        .reduce((a, b) -> a + "; " + b)
                        .orElse("recorded create-alert signal for '" + alertName + "' (" + severity + ")");
            }
            case "start-job" -> {
                String jobId = targetId(c);
                JobService svc = HostContext.of(api).service().jobService().orElse(null);
                // A disabled job is "not scheduled", not "not runnable" (operator 2026-09-25): an AUTOMATIC
                // application skips it; a PERSON's apply runs it like Run now. JobService builds disabled
                // jobs (so /jobs/{name}/trigger works), which is why the automatic skip is gated here.
                boolean disabled = jobId != null && svc != null
                        && svc.jobConfig(jobId).map(j -> !j.enabled()).orElse(false);
                if (disabled && automatic) {
                    detail = "job '" + jobId + "' is disabled — not started";
                } else if (jobId != null && svc != null
                        && (runId = svc.triggerRun(jobId, automatic ? "decision-rule:" + ruleName : actor,
                                Map.of()).orElse(null)) != null) {
                    status = "executed";
                    detail = "triggered job '" + jobId + "'" + (disabled ? " (disabled — run on a manual apply)" : "");
                } else {
                    detail = "no such job '" + jobId + "'";
                }
            }
            case "trigger-pipeline" -> {
                String pipelineId = targetId(c);
                if (pipelineId != null && HostContext.of(api).service().triggerRunAsync(pipelineId).isPresent()) {
                    status = "executed";
                    detail = "triggered pipeline '" + pipelineId + "'";
                } else {
                    detail = "no such pipeline '" + pipelineId + "'";
                }
            }
            case "create-incident" -> {
                // Explicit, author-selected Incident consequence — the generalized form of the
                // create-alert high-severity auto-promotion above, usable at any severity and
                // without also authoring an Alert Rule. Deduped to one open Incident per rule
                // (correlationId = the rule), the same signal→Incident wiring the alert/recon
                // paths use; a matching Incident already being open is a successful no-op.
                String corr = "decision-rule:" + ruleName;
                String title = paramStr(c, "title", "Decision Rule " + ruleName);
                String severity = paramStr(c, "severity", "error");
                status = "executed";
                com.gamma.objects.ObjectAccess objs = HostContext.of(api).service().objects().orElse(null);
                // ⛔ Same three-way split as create-alert above: "not installed" is not "already open".
                if (objs == null) {
                    detail = "no Incident opened for rule '" + ruleName + "' — operational objects are not "
                            + "installed in this bundle";
                } else if (!objs.hasActive(ObjectType.INCIDENT, corr)) {
                    objs.open(ObjectType.INCIDENT, title,
                            "Raised by Decision Rule '" + ruleName + "'", severity, corr,
                            Map.of("rule", ruleName, "decisionRule", ruleName, "severity", severity));
                    detail = "opened Incident '" + title + "' (" + severity + ")";
                } else {
                    detail = "Incident already open for rule '" + ruleName + "'";
                }
            }
            case "invoke-api" -> {
                // ASSURE-ACTION-REQUESTS-1: never a direct call — a PENDING Action Request on the rule's Incident,
                // which a second person approves before ActionDispatcher sends it.
                String[] made = proposeActionRequest(api, ruleName, rule, c, automatic, actor);
                status = made[0];
                detail = made[1];
                actionRequestId = made[2];
            }
            case "render-widget", "generate-report" -> {
                emitSignal(actor, "decision-rule." + action, "decision-rule:" + ruleName, Map.of("action", action));
                status = "executed";
                detail = "recorded " + action + " stub signal (execution engine not built yet)";
            }
            case "route", "tag", "quarantine", "drop" ->
                    detail = "routing action — applied to matching records during the target pipeline's runs";
            default -> detail = "unknown action '" + action + "' — no installed module provides it; the rule is kept unchanged";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", action);
        out.put("status", status);
        out.put("detail", detail);
        if (runId != null) out.put("runId", runId);
        if (actionRequestId != null) out.put("actionRequestId", actionRequestId);
        return out;
    }

    /**
     * The {@code invoke-api} consequence: propose a {@code pending} Action Request linked to the rule's open
     * Incident (correlation {@code decision-rule:<rule>}; opened here when none is open, as {@code create-incident}
     * would), with the consequence's {@code params} — {@code connection}, {@code method} (default POST) and
     * {@code payload}, a JSON object whose string leaves may use {@code {{incident.id}}} / {@code {{context.rule}}}.
     * Deduped: while one this rule proposed on that Incident is still pending, another is not. The author is the
     * person applying the rule, or {@code decision-rule:<rule>} when the engine did — never the approver.
     *
     * @return {status, detail, actionRequestId-or-null}
     */
    /** The payload an {@code invoke-api} consequence sends when it names none: which Incident, which rule. */
    static final Map<String, Object> DEFAULT_INVOKE_PAYLOAD = Map.of("incident", "{{incident.id}}", "rule", "{{context.rule}}");

    /** One WARN audit per skip when the history cannot name the makers (names the rule only — no payload, no values). */
    private static void auditUnknownMakers(String ruleName, String actor, boolean automatic) {
        try {
            EventLog log = EventLog.current();
            if (log == null) return;
            log.emit(com.gamma.audit.Event.builder(com.gamma.audit.EventType.AUDIT).source("audit")
                    .level(com.gamma.audit.EventLevel.WARN)
                    .message("Decision Rule '" + ruleName + "' raised no Action Request: the version history cannot name "
                            + "the makers of its invoke-api consequence (unstamped version or history pruned) — failed closed")
                    .actor(automatic ? "decision-rule:" + ruleName : actor).actorType(automatic ? "system" : "user")
                    .action("action-request.skipped-unknown-makers").actionCategory("operation")
                    .attr("decisionRule", ruleName));
        } catch (RuntimeException auditFailure) {
            // an audit gap must never turn a fail-closed skip into a 500
        }
    }

    private static String[] proposeActionRequest(ApiContext api, String ruleName, Map<String, Object> rule,
                                                 Map<String, Object> c, boolean automatic, String actor) {
        com.gamma.objects.ObjectAccess objects = HostContext.of(api).service().objects().orElse(null);
        if (objects == null)
            return new String[] {"skipped", "no Action Request — operational objects are not installed in this "
                    + "bundle, so there is no Incident to raise it on", null};
        Path root = api.writeRoot();
        if (root == null)
            return new String[] {"skipped", "no Action Request — set -Dassist.write.root to enable", null};
        // Round-2 finding 1b: the makers are every editor, from the VERSION HISTORY, since the invoke-api
        // consequence last changed — all co-authors, none may approve. Unknown provenance fails closed.
        List<String> coAuthors = DecisionRuleGuard.makers(new ComponentStore(root.resolve("registry")), ruleName, rule);
        if (coAuthors == null) auditUnknownMakers(ruleName, actor, automatic);   // ASSURE-ACTION-REQUESTS-RESIDUALS-1 (3)
        if (coAuthors == null || coAuthors.isEmpty())
            return new String[] {"skipped", "no Action Request — the version history of Decision Rule '" + ruleName
                    + "' has no recorded editor for its invoke-api consequence (a version saved before editors were "
                    + "recorded, or history pruned past the change), so four-eyes cannot exclude its makers; save the "
                    + "rule again", null};
        String corr = "decision-rule:" + ruleName;
        String incident = objects.activeAttributeIndex(ObjectType.INCIDENT, corr, "decisionRule").get(ruleName);
        if (incident == null) {
            String severity = paramStr(c, "severity", "warning");
            incident = objects.open(ObjectType.INCIDENT, "Decision Rule " + ruleName,
                    "Raised by Decision Rule '" + ruleName + "' for an invoke-api action", severity, corr,
                    Map.of("rule", ruleName, "decisionRule", ruleName, "severity", severity));
        }
        Map<String, Object> p = params(c);
        try {
            synchronized (ActionRequests.lock()) {
                for (Map<String, Object> r : ActionRequests.list(root))
                    if (ActionRequests.PENDING.equals(r.get("status")) && corr.equals(r.get("origin"))
                            && incident.equals(r.get("incidentId")))
                        return new String[] {"executed", "Action Request " + r.get("id") + " is already pending "
                                + "approval on Incident " + incident, String.valueOf(r.get("id"))};
            }
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("connection", p.get("connection"));
            spec.put("method", p.getOrDefault("method", "POST"));
            spec.put("payloadTemplate", p.containsKey("payload") ? p.get("payload") : DEFAULT_INVOKE_PAYLOAD);
            spec.put("incidentId", incident);
            spec.put("context", Map.of("rule", ruleName));
            Map<String, Object> rec = ActionRequestRoutes.propose(api, root, spec,
                    automatic ? corr : actor, automatic ? "system" : "user", corr, coAuthors);
            return new String[] {"executed", "proposed Action Request " + rec.get("id") + " on Incident " + incident
                    + " — pending approval, nothing sent yet", String.valueOf(rec.get("id"))};
        } catch (ApiException | IOException refused) {
            return new String[] {"skipped", "no Action Request: " + refused.getMessage(), null};
        }
    }

    private static String targetId(Map<String, Object> c) {
        return c.get("target") instanceof Map<?, ?> t && t.get("id") != null ? String.valueOf(t.get("id")) : null;
    }

    /** Whether a decision severity warrants a managed Incident (critical / error) rather than a ledger signal. */
    private static boolean isHighSeverity(String severity) {
        return severity != null
                && (severity.equalsIgnoreCase("critical") || severity.equalsIgnoreCase("error"));
    }

    @SuppressWarnings("unchecked")
    private static String paramStr(Map<String, Object> c, String key, String fallback) {
        if (c.get("params") instanceof Map<?, ?> p && p.get(key) != null) return String.valueOf(p.get(key));
        return fallback;
    }

    /** A consequence's {@code params} block as a map (empty if absent/malformed). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> params(Map<String, Object> c) {
        return c.get("params") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
    }

    /** Whether a {@code create-alert} consequence's {@code params} carry enough of an alert-rule body
     *  to attempt real authoring (see {@link com.gamma.alert.AlertRule}'s constructor): either a ledger
     *  metric rule ({@code comparator}+{@code threshold}+{@code metric}+{@code window}) or a measure rule
     *  ({@code comparator}+{@code threshold}+{@code dataset}+{@code measure}). The pre-S6 stub shape
     *  ({@code rule}, {@code severity} only) does not, and stays ledger-signal-only. */
    private static boolean looksLikeAlertRuleBody(Map<String, Object> c) {
        Map<String, Object> p = params(c);
        boolean hasComparatorAndThreshold = p.get("comparator") != null && p.get("threshold") != null;
        boolean ledgerMetric = p.get("metric") != null && p.get("window") != null;
        boolean measureRule = p.get("dataset") != null && p.get("measure") != null;
        return hasComparatorAndThreshold && (ledgerMetric || measureRule);
    }

    /** A Decision Rule's Signal, stamped with the Space whose ledger records it and the person who applied
     *  the rule (null when the engine applied it) — cross-Space consequence slice 1: an Exchange forwarder's
     *  {@code originActor} is meaningless while the origin leaves both null. */
    private static void emitSignal(String actor, String type, String source, Map<String, Object> payload) {
        emitSignal(actor, type, source, payload, Map.of());
    }

    private static void emitSignal(String actor, String type, String source, Map<String, Object> payload,
                                   Map<String, String> extraAttrs) {
        EventLog el = EventLog.current();
        if (el == null) return;
        Ref who = actor == null || actor.isBlank() ? null : Ref.of("user", actor);
        com.gamma.audit.Event ev = new Signal(null, type, Instant.now(), Severity.INFO, Ref.parseCompact(source), null,
                null, null, EventLog.currentSpaceId(), who, type, payload, 1).toEvent();
        if (!extraAttrs.isEmpty()) {
            Map<String, String> attrs = new LinkedHashMap<>(ev.attributes());
            attrs.putAll(extraAttrs);
            ev = new com.gamma.audit.Event(ev.eventId(), ev.ts(), ev.level(), ev.type(), ev.source(), ev.pipeline(),
                    ev.correlationId(), ev.message(), attrs, ev.payload());
        }
        el.emit(ev);
    }

    // -- emit-signal: payload mapping + cross-Space offer (slice 5) --------------

    /** The {@code emit-signal} param naming the ONE Space the Signal is offered to (cross-Space consequence
     *  slice 5, operator 2026-09-28). Not {@code targetSpace}: the rule acts only in its own Space (D1); the
     *  Signal crosses only under that Space's ACTIVE Exchange grant. */
    static final String OFFER_TO = "offerTo";
    /** Params an {@code emit-signal} carrying {@code offerTo} may hold; anything else is refused. */
    static final java.util.Set<String> OFFER_PARAMS = java.util.Set.of("type", OFFER_TO, "payload");
    /** Payload keys the rule itself or the loop cut writes; a mapping may not set them. */
    static final java.util.Set<String> RESERVED_PAYLOAD_KEYS = java.util.Set.of("rule", "chainDepth");
    /** Same charsets as the Exchange's signal offer ({@code ExchangeRoutes.SIGNAL_TYPE} / {@code PAYLOAD_KEY}). */
    static final String SIGNAL_TYPE = "[a-z0-9][a-z0-9_-]*(\\.[a-z0-9_-]+)*";
    static final String PAYLOAD_KEY = "[A-Za-z_][A-Za-z0-9_-]{0,63}";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payloadMap(Map<String, Object> c) {
        return params(c).get("payload") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static ApiException invalid(String msg) {
        return new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "emit-signal: " + msg);
    }

    /** Fail-closed shape check of every {@code emit-signal}'s {@code payload} mapping and {@code offerTo}, on save
     *  AND on apply: a mapping is {@code {signalKey: recordField}}; an offer names a valid Space id and an
     *  explicit Signal type and nothing else. */
    @SuppressWarnings("unchecked")
    static void validateEmitSignals(Map<String, Object> rule) {
        if (!(rule.get("consequences") instanceof List<?> l)) return;
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> raw) || !"emit-signal".equals(String.valueOf(raw.get("action")))) continue;
            Map<String, Object> p = params((Map<String, Object>) raw);
            if (p.containsKey("payload")) {
                if (!(p.get("payload") instanceof Map<?, ?> m) || m.isEmpty())
                    throw invalid("'payload' must be a non-empty map of signal key to record field");
                for (Map.Entry<?, ?> en : m.entrySet()) {
                    String key = String.valueOf(en.getKey());
                    if (!key.matches(PAYLOAD_KEY)) throw invalid("invalid payload key '" + key + "'");
                    if (RESERVED_PAYLOAD_KEYS.contains(key))
                        throw invalid("payload key '" + key + "' is written by the platform, not mapped");
                    if (!(en.getValue() instanceof String f) || f.isBlank())
                        throw invalid("payload key '" + key + "' must map to a record field name");
                }
            }
            if (!p.containsKey(OFFER_TO)) continue;
            if (!(p.get(OFFER_TO) instanceof String to) || !com.gamma.service.SpaceId.isValid(to))
                throw invalid("'offerTo' must be a valid space id");
            for (String k : p.keySet())
                if (!OFFER_PARAMS.contains(k)) throw invalid("unknown param '" + k + "' on an offered Signal");
            if (!(p.get("type") instanceof String type) || !type.matches(SIGNAL_TYPE))
                throw invalid("an offered Signal needs an explicit dotted 'type' (e.g. fraud.alert)");
        }
    }

    /** The apply body's {@code record}: the matched row an {@code emit-signal} payload mapping reads. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> applyRecord(Map<String, Object> body) {
        Object r = body.get("record");
        if (r == null) return Map.of();
        if (!(r instanceof Map<?, ?> m))
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "'record' must be a JSON object");
        return (Map<String, Object>) m;
    }

    /**
     * Before ANY consequence runs: every mapped field must be on the record (422), and an {@code offerTo} passes
     * the SAME gate as a manual {@code POST /exchange/signal-offers} ({@code canOfferSignals}, here in the origin
     * = bound Space, 403) and needs an ACTIVE Exchange signal grant for that type to that Space (422, one answer
     * for "no such Space" and "no grant", D12). Delivery, its audit ({@code exchange.signal.delivered} /
     * {@code undeliverable} on this ledger) and the chain-depth loop cut stay the Exchange forwarder's.
     */
    @SuppressWarnings("unchecked")
    private static void refuseUnmappableOrUngrantedEmits(com.sun.net.httpserver.HttpExchange e,
                                                         Map<String, Object> rule, Map<String, Object> record) {
        if (!(rule.get("consequences") instanceof List<?> l)) return;
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> raw) || !"emit-signal".equals(String.valueOf(raw.get("action")))) continue;
            Map<String, Object> c = (Map<String, Object>) raw;
            for (Map.Entry<String, Object> en : payloadMap(c).entrySet())
                if (!record.containsKey(String.valueOf(en.getValue())))
                    throw invalid("payload key '" + en.getKey() + "' maps record field '" + en.getValue()
                            + "', which the apply request's 'record' does not carry");
            if (!(params(c).get(OFFER_TO) instanceof String to)) continue;
            ApiContext.requireCapability(e, "canOfferSignals");
            String owner = EventLog.currentSpaceId();
            String type = String.valueOf(params(c).get("type"));
            if (to.equals(owner)) throw invalid("a Signal is not offered to its own Space");
            if (!SignalOfferGrants.global().granted(owner, to, type))
                throw invalid("no ACTIVE Exchange signal grant for '" + type + "' to '" + to + "'");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    /** The consequence params that would name ANOTHER Space. D1 (operator 2026-09-28): only Signals cross
     *  Spaces (through a consented Exchange grant), so the apply path never gains a target-space parameter. */
    static final java.util.Set<String> TARGET_SPACE_PARAMS = java.util.Set.of("targetSpace", "space");

    /** 422 before any consequence runs when an {@code emit-signal} names an {@code exchange.*} type: only the
     *  Exchange writes that namespace, so a rule cannot forge another Space's delivered Signal. */
    @SuppressWarnings("unchecked")
    private static void refuseExchangeNamespace(Map<String, Object> rule) {
        if (!(rule.get("consequences") instanceof List<?> l)) return;
        for (Object o : l)
            if (o instanceof Map<?, ?> c && "emit-signal".equals(String.valueOf(c.get("action")))
                    && paramStr((Map<String, Object>) c, "type", "").startsWith("exchange."))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED,
                        "a Decision Rule cannot emit an exchange.* Signal: that namespace is written only by the"
                                + " Exchange when it delivers another Space's Signal");
    }

    /** 422 before ANY consequence runs when one names a target Space — fail closed, so an author never
     *  believes a cross-Space effect happened (cross-Space consequence D1, test N7). */
    @SuppressWarnings("unchecked")
    private static void refuseTargetSpace(Map<String, Object> rule) {
        if (!(rule.get("consequences") instanceof List<?> l)) return;
        for (Object o : l)
            if (o instanceof Map<?, ?> c)
                for (String k : TARGET_SPACE_PARAMS)
                    if (params((Map<String, Object>) c).containsKey(k))
                        throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED,
                                "a consequence cannot name another Space ('" + k + "'): only Signals cross"
                                        + " Spaces, through a consented Exchange signal grant");
    }

    private ComponentStore store(ApiContext api) {
        return new ComponentStore(WriteGates.requireWriteRoot(api, "decision rule").resolve("registry"));
    }

    private static String requireName(Map<String, Object> rule) {
        Object n = rule.get("name");
        if (n == null || String.valueOf(n).isBlank())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "decision rule requires a 'name'");
        return String.valueOf(n);
    }

    /** Validate + default a rule body (mirrors the mock's {@code normalize()} exactly, for parity):
     *  {@code targetType} clamped to pipeline|job (default pipeline), {@code consequences} required
     *  non-empty, {@code priority} default 100, {@code enabled} default true, {@code when} default an
     *  empty AND-group in the canonical {@code query-types} shape ({@code kind/op/items}) so a rule
     *  with no filter reads identically to what the UI authors and {@link ConditionTree} evaluates. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalize(Map<String, Object> body) {
        if (!(body.get("consequences") instanceof List<?> cs) || cs.isEmpty())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "decision rule requires at least one consequence");
        Map<String, Object> rule = new LinkedHashMap<>(body);
        String targetType = String.valueOf(rule.getOrDefault("targetType", "pipeline"));
        rule.put("targetType", "job".equals(targetType) ? "job" : "pipeline");
        rule.putIfAbsent("description", "");
        rule.putIfAbsent("target", "");
        rule.putIfAbsent("when", Map.of("kind", "group", "op", "AND", "items", List.of()));
        try {
            ConditionTree.requireGroupRoot(rule.get("when"));   // a bare root would match EVERY row
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "decision rule 'when': " + e.getMessage());
        }
        Object priority = rule.get("priority");
        rule.put("priority", priority instanceof Number num ? num.intValue() : 100);
        rule.put("enabled", !"false".equalsIgnoreCase(String.valueOf(rule.getOrDefault("enabled", true))));
        validateEmitSignals(rule);
        return rule;
    }

    private static Object write(ComponentStore store, String name, Map<String, Object> content) throws IOException {
        try {
            ComponentRegistry.Component c = store.write(TYPE, name, content);
            return c.content();
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
        }
    }

    private static int priorityOf(Map<String, Object> c) {
        return c.get("priority") instanceof Number n ? n.intValue() : 100;
    }
}
