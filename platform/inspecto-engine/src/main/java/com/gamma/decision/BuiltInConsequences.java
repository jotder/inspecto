package com.gamma.decision;

import com.gamma.decision.ConsequenceProvider.Result;
import com.gamma.objects.ObjectAccess;
import com.gamma.workflow.ObjectType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Stream;

/**
 * The consequences the base platform always provides. {@code route}/{@code tag}/{@code quarantine}/{@code drop}
 * are executed record-by-record by {@code DecisionRuleApplier} during pipeline runs; their providers here only
 * DESCRIBE them (execute = report). {@code invoke-api} is not here: it stays in the host until Action Requests
 * is a module; {@code create-incident} is contributed by the ops module.
 */
public final class BuiltInConsequences {
    private BuiltInConsequences() {}

    /** The routing actions' report: they take effect during live runs, so an on-demand apply has nothing to run. */
    static final String ROUTING_DETAIL = "routing action — applied to matching records during the target pipeline's runs";

    public static List<ConsequenceProvider> all() {
        return List.of(
                of("emit-signal", "Emit signal", "platform", List.of(), BuiltInConsequences::emitSignal),
                of("create-alert", "Create alert", "notify", List.of(), BuiltInConsequences::createAlert),
                of("start-job", "Start job", "platform", List.of(), BuiltInConsequences::startJob),
                of("trigger-pipeline", "Trigger pipeline", "platform", List.of(), BuiltInConsequences::triggerPipeline),
                of("render-widget", "Render widget", "platform", List.of(), BuiltInConsequences::stubSignal),
                of("generate-report", "Generate report", "platform", List.of(), BuiltInConsequences::stubSignal),
                of("route", "Route to branch", "routing", List.of(), (x, c) -> Result.skipped(ROUTING_DETAIL)),
                of("tag", "Tag record", "routing", List.of(), (x, c) -> Result.skipped(ROUTING_DETAIL)),
                of("quarantine", "Quarantine", "routing", List.of(), (x, c) -> Result.skipped(ROUTING_DETAIL)),
                of("drop", "Drop", "routing", List.of(), (x, c) -> Result.skipped(ROUTING_DETAIL)));
    }

    /** A provider from its description plus an execute function (the same shape as {@code JobTypeProvider.of}). */
    public static ConsequenceProvider of(String id, String displayName, String group, List<String> requires,
                                         BiFunction<ConsequenceContext, Map<String, Object>, Result> exec) {
        return new ConsequenceProvider() {
            @Override public String id() { return id; }
            @Override public String displayName() { return displayName; }
            @Override public String group() { return group; }
            @Override public List<String> requires() { return requires; }
            @Override public Result execute(ConsequenceContext ctx, Map<String, Object> c) { return exec.apply(ctx, c); }
        };
    }

    // ── param helpers (the consequence map: action / destination / target / params) ──

    @SuppressWarnings("unchecked")
    public static Map<String, Object> params(Map<String, Object> c) {
        return c.get("params") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
    }

    public static String paramStr(Map<String, Object> c, String key, String fallback) {
        Object v = params(c).get(key);
        return v != null ? String.valueOf(v) : fallback;
    }

    public static String targetId(Map<String, Object> c) {
        return c.get("target") instanceof Map<?, ?> t && t.get("id") != null ? String.valueOf(t.get("id")) : null;
    }

    // ── executions ────────────────────────────────────────────────────────────────

    private static Result emitSignal(ConsequenceContext ctx, Map<String, Object> c) {
        String type = paramStr(c, "type", "decision-rule." + ctx.ruleName());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rule", ctx.ruleName());
        if (params(c).get("payload") instanceof Map<?, ?> m)
            m.forEach((key, field) -> payload.put(String.valueOf(key), ctx.record().get(String.valueOf(field))));
        String offerTo = params(c).get("offerTo") instanceof String to ? to : null;
        ctx.emitSignal(type, "decision-rule:" + ctx.ruleName(), payload, offerTo);
        return Result.executed("emitted signal '" + type + "'" + (offerTo == null ? "" : " offered to '" + offerTo + "'"));
    }

    private static Result createAlert(ConsequenceContext ctx, Map<String, Object> c) {
        String ruleName = ctx.ruleName();
        String alertName = paramStr(c, "rule", ruleName);
        String severity = paramStr(c, "severity", "warning");
        ctx.emitSignal("decision-rule.create-alert", "decision-rule:" + ruleName,
                Map.of("alert", alertName, "severity", severity), null);   // always record the decision
        String authoredDetail = null;
        if (looksLikeAlertRuleBody(c)) {
            Map<String, Object> alertBody = new LinkedHashMap<>(params(c));
            alertBody.putIfAbsent("name", alertName);
            alertBody.putIfAbsent("severity", severity.toUpperCase(Locale.ROOT));
            alertBody.remove("rule");   // the consequence's alias for the Alert Rule name, not an Alert Rule key (MODULE-REORG-P4-2)
            try {
                ctx.authorAlertRule(alertBody);
                authoredDetail = "authored Alert Rule '" + alertName + "'";
            } catch (Exception authoringFailure) {
                authoredDetail = "could not author Alert Rule '" + alertName + "': " + authoringFailure.getMessage();
            }
        }
        // High-severity decisions also open a managed Incident, deduped to one open Incident per rule.
        // Three outcomes: an absent ops module must NOT read as "already open".
        String corr = "decision-rule:" + ruleName;
        String incidentDetail = null;
        if (severity.equalsIgnoreCase("critical") || severity.equalsIgnoreCase("error")) {
            Optional<ObjectAccess> objects = ctx.objects();
            if (objects.isEmpty()) {
                incidentDetail = "decision '" + alertName + "' — no Incident opened, operational "
                        + "objects are not installed in this bundle";
            } else if (!objects.get().hasActive(ObjectType.INCIDENT, corr)) {
                objects.get().open(ObjectType.INCIDENT, "Decision Rule " + alertName,
                        "Raised by Decision Rule '" + ruleName + "'", severity, corr,
                        Map.of("rule", ruleName, "decisionRule", ruleName, "severity", severity));
                incidentDetail = "opened Incident for '" + alertName + "' (" + severity + ")";
            } else {
                incidentDetail = "decision '" + alertName + "' — Incident already open";
            }
        }
        return Result.executed(Stream.of(authoredDetail, incidentDetail)
                .filter(java.util.Objects::nonNull)
                .reduce((a, b) -> a + "; " + b)
                .orElse("recorded create-alert signal for '" + alertName + "' (" + severity + ")"));
    }

    private static boolean looksLikeAlertRuleBody(Map<String, Object> c) {
        Map<String, Object> p = params(c);
        boolean hasComparatorAndThreshold = p.get("comparator") != null && p.get("threshold") != null;
        boolean ledgerMetric = p.get("metric") != null && p.get("window") != null;
        boolean measureRule = p.get("dataset") != null && p.get("measure") != null;
        return hasComparatorAndThreshold && (ledgerMetric || measureRule);
    }

    private static Result startJob(ConsequenceContext ctx, Map<String, Object> c) {
        String jobId = targetId(c);
        // A disabled job is "not scheduled", not "not runnable" (operator 2026-09-25): an AUTOMATIC application
        // skips it; a PERSON's apply runs it like Run now.
        boolean disabled = jobId != null && ctx.jobDisabled(jobId);
        if (disabled && ctx.automatic()) return Result.skipped("job '" + jobId + "' is disabled — not started");
        Optional<String> runId = jobId == null ? Optional.empty()
                : ctx.triggerJob(jobId, ctx.automatic() ? "decision-rule:" + ctx.ruleName() : ctx.actor());
        if (runId.isEmpty()) return Result.skipped("no such job '" + jobId + "'");
        return Result.executed("triggered job '" + jobId + "'" + (disabled ? " (disabled — run on a manual apply)" : ""))
                .with("runId", runId.get());
    }

    private static Result triggerPipeline(ConsequenceContext ctx, Map<String, Object> c) {
        String pipelineId = targetId(c);
        return pipelineId != null && ctx.triggerPipeline(pipelineId)
                ? Result.executed("triggered pipeline '" + pipelineId + "'")
                : Result.skipped("no such pipeline '" + pipelineId + "'");
    }

    private static Result stubSignal(ConsequenceContext ctx, Map<String, Object> c) {
        String action = String.valueOf(c.get("action"));
        ctx.emitSignal("decision-rule." + action, "decision-rule:" + ctx.ruleName(), Map.of("action", action), null);
        return Result.executed("recorded " + action + " stub signal (execution engine not built yet)");
    }
}
