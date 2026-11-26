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
     * row's values; its people fix (masking, at most 5 candidates, per-caller budgets: LOOKUP-TEST-OPEN-READ-1) is built,
     * but it stays refused to agents until the signed agent change (AGENT-LOOKUP-PREVIEW-1, after S0). For an agent the host runs them with two more bounds ({@code ControlApi.handle}): the body is at
     * most {@link #PREVIEW_MAX_BODY_BYTES} (413), and the draft may not name a config file — a {@code schema_file},
     * {@code grammar}, {@code mapping_file} or profile ref is refused before anything is read
     * ({@code PathJail.withoutConfigRefs}). The schema preview's draft SQL (an EXPR rule) runs sealed, with no file access.
     */
    static final Set<String> AUTHORING_PREVIEWS = Set.of(
            "POST /config/suggest/schema", "POST /config/preview/parsing", "POST /config/preview/schema",
            "POST /components/mapping/validate");

    /**
     * The analyst previews (widening plan W6 group E, design {@code 2026-11-26-agent-data-answers-masking-design.md} §4,
     * D-AK15…D-AK19 signed 2026-11-26: for every agent, a person's or a machine's) → their weight in the agent budget. A
     * score preview scores one entity under a saved or draft model and writes nothing; its key leaves as the Space token
     * and its evidence masked ({@code EntityKeyMasking}, {@code EvidenceMasker}); the raw {@code entityKey} an agent sends
     * is allowed (D-AK15, an operator-accepted membership oracle). A screening check answers each entry as its list shows
     * it, at most 10 matches per subject for an agent (D-AK16), and no submitted name reaches an audit row or a log line
     * (D-AK19). Each route's own gate still decides: {@code canWorkIncidents} for a score preview (a draft model also
     * {@code canAuthorWorkbench}); a restricted list's check needs a caller who sees its members.
     */
    static final Map<String, Integer> ANALYST_PREVIEWS = Map.of(
            "POST /risk-scores/preview", 5,
            "POST /anomaly-scores/preview", 5,
            "POST /screening/check", 2);

    /**
     * The data routes whose answer is unmasked by operator decision, so their handler proves it with
     * {@code AnswerMasking.unmaskedByDecision} instead of a masking step: the host opens their scope allowing that stamp, and
     * no other route's scope accepts it. D-AK23 (operator, 2026-11-26): a DRAFT score preview ({@code content}) answers its
     * factor and feature values raw to a person or a person's agent holding {@code canAuthorWorkbench}; a machine agent's
     * draft is refused by the route (403). A saved model's preview keeps its key and evidence masked for every agent. (Lane B adds
     * {@code POST /enrichment/preview} here under D-AK22.)
     */
    static final Set<String> RAW_FOR_BUILDERS = Set.of("POST /risk-scores/preview", "POST /anomaly-scores/preview");

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
     * the authoring previews ({@link #AUTHORING_PREVIEWS}, W6 group A); the analyst previews ({@link #ANALYST_PREVIEWS},
     * W6 group E: score previews and the screening check, masked, weighted data routes); and the Space tests ({@code POST /tests/run}, W6
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
        all.addAll(ANALYST_PREVIEWS.keySet());
        ALLOWED = Set.copyOf(all);
    }

    /**
     * The {@link #LA_READS} that answer no Dataset value (names, counts, verdicts), so they need no stamp: the overlap
     * profile (column names and counts) and the two dossier verifications (verdicts, hashes, entry keys of the sealed file).
     */
    static final Set<String> LA_READS_WITHOUT_VALUES = Set.of("POST /inv/schema/overlap-profile",
            "POST /inv/investigations/([^/]+)/dossier/verify", "POST /inv/investigations/([^/]+)/dossier/bundle/verify");

    /**
     * The agent data routes (widening plan W6, design {@code 2026-11-26-agent-data-answers-masking-design.md} §3.1, D-AK13,
     * D-AK17): the {@link #ALLOWED} routes whose answer carries values read from a Dataset or Reference, each with its weight
     * in the agent budget (1 = a page read). For an agent, on every door, the host (a) charges the weight to the agent budget
     * keyed by agent client and subject, and (b) answers 500 {@code masking not proven} instead of a 2xx answer no masking
     * step stamped ({@code com.gamma.util.AnswerMasking}). S0 lists {@code POST /db/query} and the Link Analysis reads that
     * answer entity ids or rows, and the masked value GETs ({@link #VALUE_GETS}); group E adds the analyst previews
     * ({@link #ANALYST_PREVIEWS}, weights 5 and 2); groups B, D and {@code /lookups/test} join one change each.
     */
    static final Map<String, Integer> DATA_ROUTES;

    /**
     * The GET routes on {@link #DATA_ROUTES} (AGENT-GET-DATA-READS-1, operator 2026-11-26): every GET whose answer carries
     * a value read from a Dataset or Reference and that masks it (BiResultMasking, the Link Analysis maskings,
     * EntityKeyMasking, the Entity List and screening tokens, ObjectReadMasking). The ones that cannot are on
     * {@link #RAW_GETS}; the stored-record GETs (events, audit, Incidents and Cases, notes) wait on their own change.
     */
    static final Set<String> VALUE_GETS = Set.of(
            "GET /anomaly-scores/([^/]+)/([^/]+)",
            "GET /datasets/([^/]+)/rows",
            "GET /db/table",
            "GET /entity-lists/([^/]+)",
            "GET /inv/datasets/([^/]+)/discover",
            "GET /inv/entities/([^/]+)/([^/]+)/profile",
            "GET /inv/entities/([^/]+)/([^/]+)/summary",
            "GET /inv/entity-identities",
            "GET /inv/entity-identities/group",
            "GET /inv/entity-types/([^/]+)/profile",
            "GET /inv/graph/runs/([^/]+)",
            "GET /inv/investigations/([^/]+)/compare",
            "GET /inv/investigations/([^/]+)/dossier",
            "GET /inv/investigations/([^/]+)/dossier/bundle",
            "GET /inv/investigations/([^/]+)/drafts/([^/]+)/log",
            "GET /inv/investigations/([^/]+)/drafts/([^/]+)/working-set",
            "GET /inv/investigations/([^/]+)/log",
            "GET /inv/investigations/([^/]+)/working-set",
            "GET /inv/value-measures",
            "GET /kpis/([^/]+)/value",
            "GET /predictions/([^/]+)/([^/]+)/([^/]+)",
            "GET /public/investigations/([^/]+)",
            "GET /regulatory-reports",
            "GET /regulatory-reports/([^/]+)",
            "GET /risk-score-lists/([^/]+)",
            "GET /risk-scores/([^/]+)/([^/]+)",
            "GET /screening/hits",
            "GET /screening/hits/([^/]+)");

    static {
        Map<String, Integer> m = new HashMap<>();
        m.put("POST /db/query", 1);
        for (String r : LA_READS) if (!LA_READS_WITHOUT_VALUES.contains(r)) m.put(r, 1);
        for (String r : VALUE_GETS) m.put(r, 1);
        m.putAll(ANALYST_PREVIEWS);
        DATA_ROUTES = Map.copyOf(m);
    }

    /** The weight of the heaviest {@link #DATA_ROUTES} entry: the smallest agent burst that can ever pay every route. */
    static int heaviestDataWeight() {
        return heaviest(DATA_ROUTES);
    }

    static int heaviest(Map<String, Integer> routes) {
        return routes.values().stream().mapToInt(Integer::intValue).max().orElse(1);
    }

    /**
     * Why an agent burst of {@code burst} is refused, or {@code null}: a burst below a route's weight could never pay that
     * route, so it would answer 429 for ever ({@code PUT /system/link-analysis/limits} answers 422 instead).
     */
    static String burstProblem(double burst, Map<String, Integer> routes) {
        int heaviest = heaviest(routes);
        if (burst >= heaviest) return null;
        String route = routes.entrySet().stream().filter(e -> e.getValue() == heaviest).map(Map.Entry::getKey).sorted()
                .findFirst().orElse("");
        return "rate.agent_burst " + (burst == Math.rint(burst) ? String.valueOf((long) burst) : String.valueOf(burst))
                + " is below the weight " + heaviest + " of the agent data route " + route
                + ": an agent could never call it - set at least " + heaviest;
    }

    /** The weight {@code subject} pays for the route registered as {@code method pattern}: 0 when it is no agent or no data route. */
    static int agentDataWeight(Subject subject, String method, String pattern) {
        if (subject == null || !subject.agent()) return 0;
        Integer w = DATA_ROUTES.get(method + " " + pattern);
        return w == null ? 0 : w;
    }

    /** The agent budget key (D-AK17): per agent client AND subject, so a person's agent never spends the person's own budget
     *  and two agent clients of one person never share. {@code null} for a caller no agent client acts for. */
    static String agentBudgetKey(Subject subject) {
        return subject == null || !subject.agent() ? null : "agent:" + subject.agentClient() + ":" + subject.id();
    }

    /**
     * GET routes refused to every agent (AGENT-GET-DATA-READS-1, operator 2026-11-26): each answers values read from a
     * Dataset, a Reference or a raw file with no masking step, so it can never prove its answer masked (D-AK13). The
     * reason is the one an agent is told. A person's call is unchanged; {@code GET /views/{id}/data} is also a people
     * defect ({@code VIEW-DATA-UNMASKED-1}).
     */
    static final Map<String, String> RAW_GETS = Map.of(
            "GET /views/([^/]+)/data", "it answers a view's rows unmasked",
            "GET /connections/([^/]+)/sample", "it answers a remote file's rows unmasked",
            "GET /jobs/([^/]+)/runs/([^/]+)/artifacts/([^/]+)/content", "it answers a Job artifact's raw bytes",
            "GET /runs/([^/]+)/errors", "it answers rejected input rows unmasked",
            "GET /export", "it answers the Space's config and state files, Break keys and Entity List facts included",
            "GET /recon/promoted", "it answers Break match keys unmasked",
            "GET /recon/([^/]+)/state", "it answers recorded Breaks with their key and side values unmasked",
            "GET /insights", "it answers stored Insight rows it cannot prove masked at read",
            "GET /screening/hits/([^/]+)/reveal", "it answers a screened subject's raw name and entry");

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
        if (RAW_GETS.containsKey("GET " + pattern) && ("GET".equals(method) || "HEAD".equals(method))) return Bucket.REFUSED;
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
        String raw = RAW_GETS.get("GET " + pattern);
        if (raw != null && ("GET".equals(method) || "HEAD".equals(method)))
            return "an agent client may not call GET " + pattern + ": " + raw + " - sign in without the agent client";
        return never(method, pattern)
                ? "an agent client never approves, reveals, promotes, or changes Apps or access — a person must"
                : "an agent client may not call " + method + " " + pattern + " — an agent proposes config changes (held "
                        + "for approval), takes run, Case and Link Analysis Draft actions within its rights, validates, previews, "
                        + "dry-runs and reads; sign in "
                        + "without the agent client for anything else";
    }
}
