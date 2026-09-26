package com.gamma.control;

import com.gamma.event.EventLog;
import com.gamma.job.JobService;
import com.gamma.objects.ObjectType;
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
                (e, m) -> apply(api, ApiContext.name(m), ApiContext.actor(e))));
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
        checkInvokeApi(e, rule);
        long now = System.currentTimeMillis();
        rule.put("lastSimulation", null);
        rule.put("createdAt", now);
        rule.put("updatedAt", now);
        rule.put("createdBy", ApiContext.actor(e));   // the makers an invoke-api Action Request's four-eyes excludes
        rule.put("updatedBy", ApiContext.actor(e));
        PendingChanges.hold(api, e, TYPE, name, rule, null);   // maker-checker
        return write(store, name, rule);
    }

    private Object update(ApiContext api, com.sun.net.httpserver.HttpExchange e, String name, Map<String, Object> body) throws IOException {
        ComponentStore store = store(api);
        Map<String, Object> prev = RouteErrors.existing(store, TYPE, "decision rule", name);
        Map<String, Object> rule = normalize(body);
        rule.put("name", name);
        rule.put("lastSimulation", prev.get("lastSimulation"));
        checkInvokeApi(e, rule);
        rule.put("createdAt", prev.getOrDefault("createdAt", System.currentTimeMillis()));
        rule.put("updatedAt", System.currentTimeMillis());
        rule.put("createdBy", prev.get("createdBy"));
        rule.put("updatedBy", ApiContext.actor(e));
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

    /**
     * A rule carrying an {@code invoke-api} consequence raises Action Requests whenever it is applied, so saving one
     * is proposing outbound calls by proxy (verification finding 3): the saver must hold {@code canWorkIncidents}, the
     * capability {@code POST /action-requests} demands (403). Its params are validated here, not at apply time
     * (finding 4): {@code params.connection} is required and must name a registered {@code https} Connection; a
     * legacy {@code params.url} is refused (422) — a URL was never an authorable egress target.
     */
    @SuppressWarnings("unchecked")
    private static void checkInvokeApi(com.sun.net.httpserver.HttpExchange e, Map<String, Object> rule) {
        List<Map<String, Object>> cs = (List<Map<String, Object>>) (List<?>) (rule.get("consequences") instanceof List<?> l ? l : List.of());
        boolean any = false;
        for (Object o : cs) if (o instanceof Map<?, ?> c && "invoke-api".equals(String.valueOf(c.get("action")))) any = true;
        if (!any) return;
        ApiContext.requireCapability(e, "canWorkIncidents");
        for (Object o : cs) {
            if (!(o instanceof Map<?, ?> raw) || !"invoke-api".equals(String.valueOf(raw.get("action")))) continue;
            Map<String, Object> p = params((Map<String, Object>) raw);
            if (p.containsKey("url"))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "an invoke-api consequence takes "
                        + "params.connection (the id of an https Connection), not params.url — the target is always an "
                        + "onboarded Connection, never a URL written into a rule");
            Object id = p.get("connection");
            if (id == null || String.valueOf(id).isBlank())
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "an invoke-api consequence needs "
                        + "params.connection (the id of an https Connection)");
            com.gamma.acquire.ConnectionProfile cp = com.gamma.acquire.ConnectionRegistry.find(String.valueOf(id)).orElseThrow(
                    () -> new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "invoke-api: Connection '" + id
                            + "' is not registered in this Space"));
            if (!com.gamma.pipeline.exec.WebhookSink.CONNECTOR.equals(cp.connector()))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "invoke-api: Connection '" + id
                        + "' is a '" + cp.connector() + "' connection — an Action Request target must be an https Connection");
        }
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
    private Object apply(ApiContext api, String name, String actor) throws IOException {
        Map<String, Object> rule = RouteErrors.existing(store(api), TYPE, "decision rule", name);
        return applyConsequences(api, name, rule, false, actor);
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
        List<Map<String, Object>> consequences = (List<Map<String, Object>>) (List<?>)
                (rule.get("consequences") instanceof List<?> l ? l : List.of());
        List<Map<String, Object>> executed = consequences.stream()
                .map(c -> executeOne(api, name, rule, c, automatic, actor)).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rule", name);
        result.put("executed", executed);
        return result;
    }

    private static Map<String, Object> executeOne(ApiContext api, String ruleName, Map<String, Object> rule,
                                                  Map<String, Object> c, boolean automatic, String actor) {
        String action = String.valueOf(c.get("action"));
        String status = "skipped";
        String detail;
        String runId = null;
        String actionRequestId = null;
        switch (action) {
            case "emit-signal" -> {
                String type = paramStr(c, "type", "decision-rule." + ruleName);
                emitSignal(type, "decision-rule:" + ruleName, Map.of("rule", ruleName));
                status = "executed";
                detail = "emitted signal '" + type + "'";
            }
            case "create-alert" -> {
                String alertName = paramStr(c, "rule", ruleName);
                String severity = paramStr(c, "severity", "warning");
                // Always record the decision on the ledger.
                emitSignal("decision-rule.create-alert", "decision-rule:" + ruleName,
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
                com.gamma.objects.ObjectAccess objects = api.service().objects().orElse(null);
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
                JobService svc = api.service().jobService().orElse(null);
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
                if (pipelineId != null && api.service().triggerRunAsync(pipelineId).isPresent()) {
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
                com.gamma.objects.ObjectAccess objs = api.service().objects().orElse(null);
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
                emitSignal("decision-rule." + action, "decision-rule:" + ruleName, Map.of("action", action));
                status = "executed";
                detail = "recorded " + action + " stub signal (execution engine not built yet)";
            }
            case "route", "tag", "quarantine", "drop" ->
                    detail = "routing action — applied to matching records during the target pipeline's runs";
            default -> detail = "unknown action '" + action + "'";
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

    private static String[] proposeActionRequest(ApiContext api, String ruleName, Map<String, Object> rule,
                                                 Map<String, Object> c, boolean automatic, String actor) {
        com.gamma.objects.ObjectAccess objects = api.service().objects().orElse(null);
        if (objects == null)
            return new String[] {"skipped", "no Action Request — operational objects are not installed in this "
                    + "bundle, so there is no Incident to raise it on", null};
        Path root = api.writeRoot();
        if (root == null)
            return new String[] {"skipped", "no Action Request — set -Dassist.write.root to enable", null};
        // Verification finding 3: the makers of the rule are co-authors of every request it raises, so neither may
        // approve one. A rule saved before its editors were recorded cannot say who they were — fail closed.
        List<String> coAuthors = java.util.stream.Stream.of(rule.get("createdBy"), rule.get("updatedBy"))
                .filter(java.util.Objects::nonNull).map(String::valueOf).distinct().toList();
        if (coAuthors.isEmpty())
            return new String[] {"skipped", "no Action Request — Decision Rule '" + ruleName + "' has no recorded "
                    + "editor (it was saved before editors were recorded), so four-eyes cannot exclude its maker; "
                    + "save the rule again", null};
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

    private static void emitSignal(String type, String source, Map<String, Object> payload) {
        EventLog el = EventLog.current();
        if (el == null) return;
        el.emit(new Signal(null, type, Instant.now(), Severity.INFO, Ref.parseCompact(source), null,
                null, null, null, null, type, payload, 1).toEvent());
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

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
