package com.gamma.actionrequests;

import com.gamma.control.DecisionRuleGuard;
import com.gamma.control.HostConsequenceContext;
import com.gamma.control.LinkedSubjectProvider;
import com.gamma.control.LinkedSubjects;
import com.gamma.decision.BuiltInConsequences;
import com.gamma.decision.ConsequenceContext;
import com.gamma.decision.ConsequenceProvider;
import com.gamma.event.EventLog;
import com.gamma.pipeline.ComponentStore;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.http.ApiContext;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code invoke-api} Decision Rule consequence (ASSURE-ACTION-REQUESTS-1), contributed by this module: propose a
 * {@code pending} Action Request linked to the rule's open Incident (correlation {@code decision-rule:<rule>}; opened
 * through the Incident {@link LinkedSubjectProvider} when none is open, reusing the active one otherwise, as
 * {@code create-incident} would), with the consequence's {@code params} - {@code connection}, {@code method} (default
 * POST) and {@code payload}, a JSON object whose string leaves may use {@code {{incident.id}}} / {@code {{context.rule}}}.
 * Never a direct call. Deduped: while one this rule proposed on that Incident is still pending, another is not. The
 * author is the person applying the rule, or {@code decision-rule:<rule>} when the engine did - never the approver.
 *
 * <p>A bundle without this module reports the action {@code unavailable} naming the module (the manifest's
 * {@code provides.consequences} + {@code absentMessage}), exactly as it does for {@code create-incident}.
 */
public final class InvokeApiConsequence implements ConsequenceProvider {

    public static final String ID = "invoke-api";

    /** The payload an {@code invoke-api} consequence sends when it names none: which Incident, which rule. */
    static final Map<String, Object> DEFAULT_INVOKE_PAYLOAD = Map.of("incident", "{{incident.id}}", "rule", "{{context.rule}}");

    @Override public String id() { return ID; }
    @Override public String displayName() { return "Invoke API"; }
    @Override public String group() { return "integration"; }
    @Override public List<String> requires() { return List.of("objects"); }

    @Override
    public Result execute(ConsequenceContext ctx, Map<String, Object> c) {
        if (!(ctx instanceof HostConsequenceContext host))
            return Result.unavailable("no Action Request - this context cannot reach the Space the rule runs in");
        String[] made = propose(host.api(), ctx.ruleName(), ctx.rule(), c, ctx.automatic(), ctx.actor());
        return new Result(made[0], made[1], made[2] == null ? Map.of() : Map.of("actionRequestId", made[2]));
    }

    /** @return {status, detail, actionRequestId-or-null} */
    private static String[] propose(ApiContext api, String ruleName, Map<String, Object> rule, Map<String, Object> c,
                                    boolean automatic, String actor) {
        LinkedSubjectProvider incidents = LinkedSubjects.of(api, "incident").orElse(null);
        if (incidents == null)
            return new String[] {"unavailable", "no Action Request — operational objects are not installed in this "
                    + "bundle, so there is no Incident to raise it on", null};
        Path root = api.writeRoot();
        if (root == null)
            return new String[] {"skipped", "no Action Request — set -Dassist.write.root to enable", null};
        // Round-2 finding 1b: the makers are every editor, from the VERSION HISTORY, since the invoke-api
        // consequence last changed - all co-authors, none may approve. Unknown provenance fails closed.
        List<String> coAuthors = DecisionRuleGuard.makers(new ComponentStore(root.resolve("registry")), ruleName, rule);
        if (coAuthors == null) auditUnknownMakers(ruleName, actor, automatic);   // ASSURE-ACTION-REQUESTS-RESIDUALS-1 (3)
        if (coAuthors == null || coAuthors.isEmpty())
            return new String[] {"skipped", "no Action Request — the version history of Decision Rule '" + ruleName
                    + "' has no recorded editor for its invoke-api consequence (a version saved before editors were "
                    + "recorded, or history pruned past the change), so four-eyes cannot exclude its makers; save the "
                    + "rule again", null};
        String corr = "decision-rule:" + ruleName;
        String severity = BuiltInConsequences.paramStr(c, "severity", "warning");
        String incident = incidents.open(api, new LinkedSubjectProvider.OpenRequest(corr, "decisionRule", ruleName,
                "Decision Rule " + ruleName, "Raised by Decision Rule '" + ruleName + "' for an invoke-api action",
                severity, Map.of("rule", ruleName, "decisionRule", ruleName, "severity", severity))).orElse(null);
        if (incident == null)
            return new String[] {"skipped", "no Action Request — the Incident for this rule could not be opened", null};
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

    /** A consequence's {@code params} block as a map (empty if absent/malformed). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> params(Map<String, Object> c) {
        return c.get("params") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
    }
}
