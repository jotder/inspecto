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
     * replay, retry now, retry cancel and drain, and the release of one quarantined file (widening plan W1, the route
     * of {@code QUARANTINE-RELEASE-1}); a Job's trigger and a Job Run's replay; and (widening plan W7, D-AK11 signed
     * 2026-11-25) evaluating the Expectations or Alert Rules, and a Job's backlog: drain, pause, resume, run one day now,
     * give up waiting on one day. Each is still gated by its own capability ({@code canOperateRuns}).
     */
    static final Set<String> RUN_ACTIONS = Set.of(
            "POST /runs/([^/]+)/trigger", "POST /runs/([^/]+)/pause", "POST /runs/([^/]+)/resume",
            "POST /runs/([^/]+)/reprocess", "POST /runs/([^/]+)/replay-rejects",
            "POST /runs/([^/]+)/retries/retry-now", "POST /runs/([^/]+)/retries/cancel", "POST /runs/([^/]+)/drain",
            "POST /runs/([^/]+)/quarantine/release",
            "POST /jobs/([^/]+)/trigger", "POST /jobs/runs/([^/]+)/replay",
            "POST /expectations/evaluate", "POST /expectations/([^/]+)/evaluate", "POST /alerts/evaluate",
            "POST /jobs/([^/]+)/backlog/drain", "POST /jobs/([^/]+)/backlog/pause", "POST /jobs/([^/]+)/backlog/resume",
            "POST /jobs/([^/]+)/backlog/days/([^/]+)/run", "POST /jobs/([^/]+)/backlog/days/([^/]+)/give-up");

    /**
     * The case actions (plan §4.6, analyst rows, AK-2e): comment on an Incident or Case, build an Incident Brief, ack,
     * assign, resolve or transition one, ack or resolve an Alert, open a Case from entities; and (widening plan W11, D-AK8
     * signed 2026-11-25) edit its record: the postmortem, category, impact, Findings values, an RCA skeleton, and adding or
     * removing a correlation link. Each is still gated by its own route — {@code canWorkIncidents}, {@code canManageIncidents}
     * for a new Case (a machine agent keeps it since W2 step 3, operator 2026-11-25; its request is held), the
     * collaboration exemption of the comment, Findings, RCA and link routes.
     */
    static final Set<String> CASE_ACTIONS = Set.of(
            "POST /objects/([^/]+)/comments", "POST /incidents/([^/]+)/briefs",
            "POST /objects/([^/]+)/ack", "POST /objects/([^/]+)/assign", "POST /objects/([^/]+)/resolve",
            "POST /objects/([^/]+)/transition", "POST /alerts/([^/]+)/ack", "POST /alerts/([^/]+)/resolve",
            "POST /cases/from-entities",
            "PUT /objects/([^/]+)/postmortem", "PUT /objects/([^/]+)/category", "PUT /objects/([^/]+)/impact",
            "PUT /objects/([^/]+)/findings", "POST /objects/([^/]+)/rca", "POST /objects/([^/]+)/links",
            "DELETE /objects/([^/]+)/links");

    /**
     * The capability a machine agent's hold needs on an action route the manifest exempts (phase-3 security review): a
     * comment is open to anyone who can see the object, but the hold runs before the route's scope guard, so without
     * this a machine holding no right at all could fill the approvers' queue with comments on any id. The record edits
     * open as collaboration (Findings, RCA, links — W11) need the same right.
     */
    static final Map<String, String> MACHINE_HOLD_CAPABILITY = Map.of(
            "POST /objects/([^/]+)/comments", Roles.CAN_WORK_INCIDENTS,
            "PUT /objects/([^/]+)/findings", Roles.CAN_WORK_INCIDENTS,
            "POST /objects/([^/]+)/rca", Roles.CAN_WORK_INCIDENTS,
            "POST /objects/([^/]+)/links", Roles.CAN_WORK_INCIDENTS,
            "DELETE /objects/([^/]+)/links", Roles.CAN_WORK_INCIDENTS,
            "POST /inv/graph/runs/([^/]+)/cancel", Roles.CAN_RUN_LINK_GRAPH_ANALYSIS);

    /**
     * The Link Analysis actions (plan §6.6, AK-2f, operator 2026-11-25): work in a Draft — open one, add ops, undo,
     * discard, rebase ({@code canDraftInvestigations}, which a machine agent may keep) — attach an Investigation to a Case
     * ({@code canManageIncidents}) and start or cancel a graph run ({@code canRunLinkGraphAnalysis}). Never promote a Draft,
     * edit an Investigation directly, reveal or approve an expand: those stay refused.
     */
    static final Set<String> LA_ACTIONS = Set.of(
            "POST /inv/investigations/([^/]+)/drafts", "POST /inv/investigations/([^/]+)/drafts/([^/]+)/ops",
            "POST /inv/investigations/([^/]+)/drafts/([^/]+)/undo", "POST /inv/investigations/([^/]+)/drafts/([^/]+)/discard",
            "POST /inv/investigations/([^/]+)/drafts/([^/]+)/rebase", "PUT /inv/investigations/([^/]+)/case",
            "POST /inv/graph/runs", "POST /inv/graph/runs/([^/]+)/cancel");

    /** Every action route: {@link #RUN_ACTIONS}, {@link #CASE_ACTIONS} and {@link #LA_ACTIONS}. */
    static final Set<String> ACTIONS;
    static {
        Set<String> all = new java.util.HashSet<>(RUN_ACTIONS);
        all.addAll(CASE_ACTIONS);
        all.addAll(LA_ACTIONS);
        ACTIONS = Set.copyOf(all);
    }

    /**
     * The read-shaped Link Analysis queries (plan §6.6, AK-2f, operator 2026-11-25: open to every agent): each persists
     * nothing and runs under the caller's masking — an agent never holds {@code canRevealLinkEntities}, so it sees masked
     * keys. {@code LinkAnalysisRateClasses} rate-limits them per caller.
     */
    static final Set<String> LA_READS = Set.of(
            "POST /inv/projection", "POST /inv/projection/neighbors", "POST /inv/projection/neighbors/preview",
            "POST /inv/projection/multi", "POST /inv/expand/preview", "POST /inv/schema/overlap-profile",
            "POST /inv/reference-rows", "POST /inv/case-history", "POST /inv/entities/([^/]+)/resolve",
            "POST /inv/entities/([^/]+)/suggest", "POST /inv/deep-links/resolve", "POST /inv/traversal/recursive-paths",
            "POST /inv/presets/([^/]+)/run", "POST /inv/datasets/([^/]+)/query", "POST /inv/pattern/branching",
            "POST /inv/pattern/temporal", "POST /inv/investigations/([^/]+)/replay",
            "POST /inv/investigations/([^/]+)/dossier/verify", "POST /inv/investigations/([^/]+)/dossier/bundle/verify");

    /**
     * The authoring previews (widening plan W6 group A, D-AK10 signed 2026-11-25: for every agent): a draft's parsing over
     * raw sample text, its schema casts and mapping over parsed rows, the schema the server infers from rows, a mapping's
     * rule check. Each persists nothing (scratch DuckDB). {@code POST /lookups/test} is NOT one: it answers a Reference
     * row's values unmasked, lists fuzzy {@code candidates} and has no rate limit, so it stays refused to agents until
     * those are built (BACKLOG, operator 2026-11-26). For an agent the host runs them with two more bounds ({@code ControlApi.handle}): the body is at
     * most {@link #PREVIEW_MAX_BODY_BYTES} (413), and the draft may not name a config file — a {@code schema_file},
     * {@code grammar}, {@code mapping_file} or profile ref is refused before anything is read
     * ({@code PathJail.withoutConfigRefs}). The schema preview's draft SQL (an EXPR rule) runs sealed, with no file access.
     */
    static final Set<String> AUTHORING_PREVIEWS = Set.of(
            "POST /config/suggest/schema", "POST /config/preview/parsing", "POST /config/preview/schema",
            "POST /components/mapping/validate");

    /** An agent's authoring-preview body cap: the MCP endpoint's own 256 KiB, so a direct call gets no more than a tool call. */
    static final int PREVIEW_MAX_BODY_BYTES = 256 * 1024;

    /** Why an agent's authoring preview may not name a config file (W6 group A). */
    static final String NO_FILE_REFS = "an agent's preview draft names no file — put the schema, grammar or mapping inline";

    /** Whether {@code subject} is an agent calling one of the {@link #AUTHORING_PREVIEWS} (route pattern without anchors). */
    static boolean agentPreview(Subject subject, String method, String pattern) {
        return subject != null && subject.agent() && AUTHORING_PREVIEWS.contains(method + " " + pattern);
    }

    /**
     * Writes an agent may make that change nothing governed: the MCP endpoint itself, validation and dry runs, the Ship
     * reads, taking back its own proposal, an Action Request (held for approval by its own design), {@code POST /db/query}
     * (the read the AK-1 {@code query_dataset} tool replays; SqlGuard and masking run as for the person) and a Pipeline
     * run (D-AK2: a delegated person only — {@link #PERSON_ONLY}); the Link Analysis queries ({@link #LA_READS}, AK-2f);
     * the authoring previews ({@link #AUTHORING_PREVIEWS}, W6 group A); and the Space tests ({@code POST /tests/run}, W6
     * group C, D-AK10: a delegated person only, as a run): each case runs its Pipeline over the case folder's own files
     * into a JVM-temp scratch root deleted afterwards, with no batch commit, batch audit, provenance or Signal, and answers
     * verdicts (counts, a checksum, a named column's min or max), never rows.
     */
    static final Set<String> ALLOWED;
    static {
        Set<String> all = new java.util.HashSet<>(Set.of(
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
                "POST /pipelines/authored/([^/]+)/run",
                "POST /tests/run"));
        all.addAll(LA_READS);
        all.addAll(AUTHORING_PREVIEWS);
        ALLOWED = Set.copyOf(all);
    }

    /** {@link #ALLOWED} routes a machine agent may not call (D-AK2: never a run for a machine; D-AK10: nor the Space tests). */
    static final Set<String> PERSON_ONLY = Set.of("POST /pipelines/authored/([^/]+)/run", "POST /tests/run");

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

    /** An agent's posture (D-AK7, {@code MCP-AGENT-POSTURE-1}): how its {@link Bucket#ACTION} calls are treated. */
    enum Posture {
        /** A person's agent: its actions run at once within the person's rights (D-AK5); config changes are held. */
        PERSON("person", "an action runs at once within your rights; a config change is held as a Pending Change"),
        /** A machine agent: every action is held as a Pending Action, every config change as a Pending Change. */
        MACHINE("machine", "an action is held as a Pending Action for a person to approve; a config change is held as a "
                + "Pending Change");

        final String id, effect;

        Posture(String id, String effect) {
            this.id = id;
            this.effect = effect;
        }
    }

    /**
     * The posture of {@code subject}, or {@code null} for a caller no agent client acts for. The ONE predicate: the
     * machine-action hold ({@code PendingActions.holdIfMachineAction}), the machine's {@link #PERSON_ONLY} refusal and the
     * posture an agent is told ({@code GET /agent-posture}) all read it.
     */
    static Posture posture(Subject subject) {
        if (subject == null || !subject.agent()) return null;
        return subject.machine() ? Posture.MACHINE : Posture.PERSON;
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
        if (b == Bucket.ALLOWED && posture(subject) == Posture.MACHINE && PERSON_ONLY.contains(method + " " + pattern))
            return "an agent machine client may not run a Pipeline or the Space tests — a delegated person may";
        if (b != Bucket.REFUSED) return null;
        return never(method, pattern)
                ? "an agent client never approves, reveals, promotes, or changes Apps or access — a person must"
                : "an agent client may not call " + method + " " + pattern + " — an agent proposes config changes (held "
                        + "for approval), takes run, Case and Link Analysis Draft actions within its rights, validates, previews, "
                        + "dry-runs and reads; sign in "
                        + "without the agent client for anything else";
    }
}
