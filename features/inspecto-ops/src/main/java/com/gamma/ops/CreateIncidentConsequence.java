package com.gamma.ops;

import com.gamma.decision.ConsequenceContext;
import com.gamma.decision.ConsequenceProvider;
import com.gamma.decision.BuiltInConsequences;
import com.gamma.objects.ObjectAccess;
import com.gamma.workflow.ObjectType;

import java.util.List;
import java.util.Map;

/**
 * The {@code create-incident} Decision Rule consequence — the author-selectable, any-severity generalization of
 * {@code create-alert}'s high-severity Incident promotion. Contributed by this module (it needs operational
 * objects), so a bundle without it reports the action {@code unavailable} rather than pretending it executed.
 * Deduped to one open Incident per rule (correlationId = the rule); an Incident already open is a successful no-op.
 */
public final class CreateIncidentConsequence implements ConsequenceProvider {

    public static final String ID = "create-incident";

    @Override public String id() { return ID; }
    @Override public String displayName() { return "Open incident"; }
    @Override public String group() { return "object"; }
    @Override public List<String> requires() { return List.of("objects"); }

    @Override
    public Result execute(ConsequenceContext ctx, Map<String, Object> c) {
        ObjectAccess objects = ctx.objects().orElse(null);
        if (objects == null) return Result.unavailable("requires platform service 'objects' — operational objects are not available");
        String rule = ctx.ruleName();
        String corr = "decision-rule:" + rule;
        String title = BuiltInConsequences.paramStr(c, "title", "Decision Rule " + rule);
        String severity = BuiltInConsequences.paramStr(c, "severity", "error");
        if (objects.hasActive(ObjectType.INCIDENT, corr)) return Result.executed("Incident already open for rule '" + rule + "'");
        objects.open(ObjectType.INCIDENT, title, "Raised by Decision Rule '" + rule + "'", severity, corr,
                Map.of("rule", rule, "decisionRule", rule, "severity", severity));
        return Result.executed("opened Incident '" + title + "' (" + severity + ")");
    }
}
