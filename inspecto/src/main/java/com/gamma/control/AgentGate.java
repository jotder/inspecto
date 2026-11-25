package com.gamma.control;

import com.gamma.access.CapabilityManifest;
import com.gamma.access.Roles;
import com.gamma.spi.auth.Subject;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The host's gate for an agent Subject ({@link Subject#agent()}, AGENT-KIT-1 AK-2a, plan
 * {@code docs/superpower/2026-11-20-agent-kit-ak2-plan.md} §4.1): <b>default deny</b>. Every route falls in exactly one
 * {@link Bucket}, decided by its registered method and pattern — the same strings {@link CapabilityManifest} lists, so
 * {@code AgentGateParityTest} can pin every non-GET manifest entry to one bucket:
 * <ol>
 *   <li>{@link Bucket#HELD} — the route reaches {@code PendingChanges.hold} / {@code holdSet} ({@link PendingChanges#REPLAYABLE}),
 *       where an agent's write always becomes a Pending Change;</li>
 *   <li>{@link Bucket#ALLOWED} — reads, and the few writes that change nothing governed ({@link #ALLOWED});</li>
 *   <li>{@link Bucket#ACTION} — the actions of plan §4.6 ({@link #ACTIONS}): a run's (AK-2d) and an Incident's or Case's (AK-2e): a person's agent takes
 *       them at once, within the person's rights (D-AK5); a machine agent's are held as Pending Actions
 *       ({@code PendingActions.holdIfMachineAction});</li>
 *   <li>{@link Bucket#REFUSED} — everything else (403), including every route that only calls {@code holdRefusing*}
 *       (those do not hold when no policy covers the kind) and the never-list ({@link #never}), which overrides the
 *       other two: an agent never approves, reveals, promotes, administers Apps or changes access.</li>
 * </ol>
 * Applied on every door: a direct request ({@code ControlApi.routeDispatch}) and every in-process replay
 * ({@code ControlApi.replay}) — so the MCP endpoint's tools and an approved agent change replayed as its author pass it too.
 */
final class AgentGate {

    private AgentGate() {}

    enum Bucket { HELD, ALLOWED, ACTION, REFUSED }

    /**
     * The operational actions (plan §4.6, operations rows, AK-2d): a run's trigger, pause, resume, reprocess, rejects
     * replay, retry now, retry cancel and drain; a Job's trigger and a Job Run's replay. Each is still gated by its own
     * capability ({@code canOperateRuns}).
     */
    static final Set<String> RUN_ACTIONS = Set.of(
            "POST /runs/([^/]+)/trigger", "POST /runs/([^/]+)/pause", "POST /runs/([^/]+)/resume",
            "POST /runs/([^/]+)/reprocess", "POST /runs/([^/]+)/replay-rejects",
            "POST /runs/([^/]+)/retries/retry-now", "POST /runs/([^/]+)/retries/cancel", "POST /runs/([^/]+)/drain",
            "POST /jobs/([^/]+)/trigger", "POST /jobs/runs/([^/]+)/replay");

    /**
     * The case actions (plan §4.6, analyst rows, AK-2e, operator 2026-11-25: the §4.6 list, no record edits): comment on
     * an Incident or Case, build an Incident Brief, ack, assign, resolve or transition one, ack or resolve an Alert, open
     * a Case from entities. Each is still gated by its own route — {@code canWorkIncidents}, {@code canManageIncidents}
     * for a new Case (which a machine agent never keeps), the comment route's collaboration exemption.
     */
    static final Set<String> CASE_ACTIONS = Set.of(
            "POST /objects/([^/]+)/comments", "POST /incidents/([^/]+)/briefs",
            "POST /objects/([^/]+)/ack", "POST /objects/([^/]+)/assign", "POST /objects/([^/]+)/resolve",
            "POST /objects/([^/]+)/transition", "POST /alerts/([^/]+)/ack", "POST /alerts/([^/]+)/resolve",
            "POST /cases/from-entities");

    /**
     * The capability a machine agent's hold needs on an action route the manifest exempts (phase-3 security review): a
     * comment is open to anyone who can see the object, but the hold runs before the route's scope guard, so without
     * this a machine holding no right at all could fill the approvers' queue with comments on any id.
     */
    static final Map<String, String> MACHINE_HOLD_CAPABILITY = Map.of(
            "POST /objects/([^/]+)/comments", Roles.CAN_WORK_INCIDENTS);

    /** Every action route: {@link #RUN_ACTIONS} and {@link #CASE_ACTIONS}. */
    static final Set<String> ACTIONS;
    static {
        Set<String> all = new java.util.HashSet<>(RUN_ACTIONS);
        all.addAll(CASE_ACTIONS);
        ACTIONS = Set.copyOf(all);
    }

    /**
     * Writes an agent may make that change nothing governed: the MCP endpoint itself, validation and dry runs, the Ship
     * reads, taking back its own proposal, an Action Request (held for approval by its own design), {@code POST /db/query}
     * (the read the AK-1 {@code query_dataset} tool replays; SqlGuard and masking run as for the person) and a Pipeline
     * run (D-AK2: a delegated person only — {@link #PERSON_ONLY}).
     */
    static final Set<String> ALLOWED = Set.of(
            "POST /mcp",
            "POST /validate",
            "POST /pipelines/([^/]+)/graph/validate",
            "POST /pipelines/authored/([^/]+)/dry-run",
            "POST /bundle/export",
            "POST /promotion/preview",
            "POST /pending-changes/([^/]+)/withdraw",
            "POST /pending-actions/([^/]+)/withdraw",
            "POST /action-requests",
            "POST /db/query",
            "POST /pipelines/authored/([^/]+)/run");

    /** {@link #ALLOWED} routes a machine agent may not call (D-AK2: never a run for a machine). */
    static final Set<String> PERSON_ONLY = Set.of("POST /pipelines/authored/([^/]+)/run");

    /** Named never-list routes the capability and suffix rules of {@link #never} do not already cover. */
    static final Set<String> NEVER = Set.of(
            "POST /promotion/apply",
            "POST /agent/approvals/(.+)/decision");

    /**
     * On {@link PendingChanges#REPLAYABLE} but refused to an agent, because a path of theirs writes WITHOUT or BEFORE the
     * hold (AK-2a held-route sweep, 2026-11-20): a Space Template upgrade that adds and updates nothing rewrites the
     * Space origin record with no {@code holdSet} ({@code SpaceOriginRoutes}); save-as-template copies schema files into
     * the write root before its hold and leaves them on a 422 ({@code PipelineSettingsRoutes}). Neither is developer
     * work; a person does them.
     */
    static final Set<String> NOT_HELD = Set.of(
            "POST /origin/upgrade",
            "POST /pipelines/([^/]+)/save-as-template");

    /** Method + pattern → the capability its manifest entry demands. */
    private static final Map<String, String> CAPABILITY = new HashMap<>();
    static {
        for (CapabilityManifest.Entry e : CapabilityManifest.ENTRIES) CAPABILITY.put(e.method() + " " + e.pattern(), e.capability());
    }

    /**
     * The never-list (§4.1): any {@code …/approve}, {@code …/decline} or {@code …/decision}; any route gated by a capability
     * an agent never holds ({@link Roles#AGENT_NEVER_CAPABILITIES} — the Action Request approver's retry / mark-failed, the
     * reveal routes, link-expansion approval); {@code /promotion/apply}; every {@code /apps} and {@code /access/*} write;
     * the Assistant's approval decision.
     */
    static boolean never(String method, String pattern) {
        String key = method + " " + pattern;
        if (NEVER.contains(key)) return true;
        String capability = CAPABILITY.get(key);   // null for an ungated route (Set.of refuses a null probe)
        if (capability != null && Roles.AGENT_NEVER_CAPABILITIES.contains(capability)) return true;
        if (pattern.endsWith("/approve") || pattern.endsWith("/decline") || pattern.endsWith("/decision")) return true;
        boolean write = !"GET".equals(method) && !"HEAD".equals(method);
        return write && (pattern.equals("/apps") || pattern.startsWith("/apps/") || pattern.startsWith("/access/"));
    }

    /** The bucket of the route registered as {@code method pattern} (pattern without {@code ^…$}). */
    static Bucket bucket(String method, String pattern) {
        if (never(method, pattern)) return Bucket.REFUSED;
        if ("GET".equals(method) || "HEAD".equals(method)) return Bucket.ALLOWED;
        String key = method + " " + pattern;
        if (PendingChanges.REPLAYABLE.contains(key) && !NOT_HELD.contains(key)) return Bucket.HELD;
        if (ALLOWED.contains(key)) return Bucket.ALLOWED;
        if (ACTIONS.contains(key)) return Bucket.ACTION;
        return Bucket.REFUSED;
    }

    /**
     * Why {@code subject} may not call the route registered as {@code method routeRegex}, or {@code null} when it may.
     * Every non-agent Subject passes untouched.
     */
    static String refusal(Subject subject, String method, String routeRegex) {
        if (subject == null || !subject.agent()) return null;
        String pattern = routeRegex.startsWith("^") && routeRegex.endsWith("$")
                ? routeRegex.substring(1, routeRegex.length() - 1) : routeRegex;
        Bucket b = bucket(method, pattern);
        if (b == Bucket.ALLOWED && subject.machine() && PERSON_ONLY.contains(method + " " + pattern))
            return "an agent machine client may not run a Pipeline — a delegated person may";
        if (b != Bucket.REFUSED) return null;
        return never(method, pattern)
                ? "an agent client never approves, reveals, promotes, or changes Apps or access — a person must"
                : "an agent client may not call " + method + " " + pattern + " — an agent proposes config changes (held "
                        + "for approval), takes run and Case actions within its rights, validates, dry-runs and reads; sign in "
                        + "without the agent client for anything else";
    }
}
