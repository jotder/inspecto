package com.gamma.etl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single statement of when an authored {@code route:} block may ARM — i.e. actually execute its
 * branch tree on the ingest path (branch-aware-executor arming plan S3).
 *
 * <p>Every rule here is FAIL-CLOSED against one shape that would drop rows silently, which is the
 * class of bug the pre-arming refusal existed to prevent. Each refusal names its own fix, because
 * the operator reading it is mid-authoring and "refused" without "do this instead" is a dead end.
 *
 * <p><b>Why this is a free function over plain data, not a method on {@link PipelineConfig}.</b>
 * It has two callers that hold the config in two different states:
 * <ul>
 *   <li>{@link PipelineConfig#prepare()} — a fully parsed config, at REGISTRATION time. It throws on
 *       the first refusal, so an unarmable pipeline never reaches a run.</li>
 *   <li>{@code ConfigRoutes} — an unparsed DRAFT map, at SAVE time, reporting all refusals at once as
 *       {@code Finding}s so the editor can show them while the operator is still typing.</li>
 * </ul>
 * Running the draft through {@code PipelineConfig.fromMap} to reuse the parsed form is deliberately
 * NOT how the save path does this: {@code fromMap} hard-fails on an unresolvable schema reference,
 * which the save path intentionally keeps a WARNING (the file may be created after the save, or
 * belong to another host) — see {@code ConfigRoutes.armedWithoutSchemaFindings}, which makes the same
 * call for the same reason. So the rules take plain data and both callers supply it from what they
 * have.
 *
 * <p>⚠ The alternative — restating the rules over raw maps in the control plane — is the
 * hand-mirrored-map failure this repo has already paid for three times: two copies drift, and the
 * copy that drifts is the one the operator sees. There is one copy.
 */
public final class RouteArming {

    private RouteArming() {}

    /**
     * Every reason {@code route} would refuse to arm, in the order {@link PipelineConfig#prepare()}
     * checks them — empty means it arms.
     *
     * <p>Callers decide what a refusal MEANS: {@code prepare()} throws the first (registration is
     * all-or-nothing), the save path reports all of them (the operator wants the whole list, not a
     * one-at-a-time game). Reporting order is therefore load-bearing only for {@code prepare()},
     * whose exact first message is pinned by {@code RecordDedupRouteConfigTest}.
     *
     * @param route           the authored {@code route:} block; {@code null} = no route, no refusals
     * @param sinkDatabases   the {@code database} of every {@code sinks[]} destination — the branch↔sink
     *                        join key, so this is the set a branch's {@code database} must hit
     * @param multiSchema     true when the pipeline selects among schemas ({@code processing.schemas[]}
     *                        or plugin {@code segments}); the lift emits one route node per schema
     *                        branch and the divert executes exactly one
     */
    public static List<String> refusals(Map<?, ?> route, Collection<String> sinkDatabases, boolean multiSchema) {
        List<String> out = new ArrayList<>();
        if (route == null) return out;

        List<?> rawBranches = route.get("branches") instanceof List<?> b ? b : List.of();
        if (rawBranches.isEmpty()) {
            // Nothing further is checkable — every remaining rule reads the branch list.
            out.add("route: needs a non-empty branches list to arm");
            return out;
        }
        // (1) clone mode stays authoring-only. ⚠ NOT for lack of engine substrate — that reason was
        //     stale and was corrected 2026-08-26: D8 (the `(batch, branch)` commit, source finalised
        //     only when every branch commits, idempotent sinks) SHIPPED, `BranchCommitCoordinator`
        //     keys expectedBranches by sink NODE ID so it is already generic over case vs clone, and
        //     `RowShaper.route` already implements clone fan-out. B9 ("no cross-branch transactional
        //     commit") is a DELIBERATE ACCEPTED constraint, not missing work.
        //     The real reason it stays refused: nothing SURFACES partial-commit state to an operator,
        //     so a clone that lands 2 of 3 destinations and retries the third is invisible. Arming
        //     this is an operator/product call about that visibility, not an engine gap.
        if ("clone".equalsIgnoreCase(String.valueOf(route.get("mode"))))
            out.add("route: mode 'clone' is authoring-only — arming runs 'case' (exclusive) branches; "
                    + "keep the pipeline inactive or switch to mode: case");
        // (2) every branch names a database matching a sinks[] destination, and no two branches share
        //     one — the lift pairs route:<key> edges to sinks BY DATABASE, so an unmatched or
        //     duplicated database is a branch whose rows land NOWHERE.
        Set<String> sinkDbs = new HashSet<>(sinkDatabases);
        Set<String> seenDbs = new HashSet<>();
        Set<String> keys = new LinkedHashSet<>();
        for (Object b : rawBranches) {
            if (!(b instanceof Map<?, ?> m) || m.get("key") == null || m.get("database") == null) {
                out.add("every armed route: branch needs both a key and a database (the sink it pairs "
                        + "with) — found: " + b);
                continue;
            }
            keys.add(String.valueOf(m.get("key")));
            // (2b) every branch needs a `where` predicate. RowShaper.route requires it on EVERY branch
            //      of both modes (`reqStr(b, "where", …)`, inside the CASE builder and the clone loop),
            //      so a branch without one throws mid-run — after the route has already registered and
            //      armed. That is precisely what this gate exists to convert into an authoring-time
            //      answer. ⚠ Reachable from the product itself, not just hand-editing: the editor's
            //      `addRouteBranch` creates the entry as `{key}` with the predicate typed in afterwards
            //      (a separate `setRouteBranchWhere`), so a branch added and then left blank saves
            //      clean today. An INACTIVE draft still only warns — ConfigRoutes picks the severity —
            //      which is what keeps mid-authoring saves working.
            //      ⛔ Do not exempt the `default:` branch: it is one of `branches[]` and RowShaper emits
            //      a WHEN for it like any other. "Everything else" is the ELSE arm, not a blank branch.
            if (m.get("where") == null || String.valueOf(m.get("where")).isBlank())
                out.add("route: branch '" + m.get("key") + "' has no where: predicate — every armed "
                        + "branch needs one (the run fails on the first row otherwise)");
            // (2c) MIDBRANCH-1 (R3): a branch's steps[] sub-chain executes on the INGEST graph walk
            //      (ConsignmentGraphRunner → PipelineExecutor → RowShaper), flattened by PipelineLift.
            //      That walk carries NO ReferenceResolver and NO ExecutionContext (both NONE at the
            //      ConsignmentGraphRunner.run seam), so kinds needing either would throw MID-RUN —
            //      after the route registered and armed. Refuse them here instead, by name, exactly
            //      the authoring-time conversion rule (2b) exists for. Grounded allowed set = what
            //      RowShaper can shape with NONE context: filter (where-predicate split), dedup with
            //      a consignment (batch) scope, summarize. Everything else fails closed.
            branchStepRefusals(m, out);
            String db = String.valueOf(m.get("database"));
            if (!sinkDbs.contains(db))
                out.add("route: branch '" + m.get("key") + "' names database '" + db + "', which matches "
                        + "no sinks[] destination — its rows would land nowhere");
            if (!seenDbs.add(db))
                out.add("route: branches share database '" + db + "' — the branch↔sink pairing is by "
                        + "database, so only one of them would ever receive rows");
        }
        // (3) default: is REQUIRED and must name a branch key. mode:case labels an unmatched row NULL
        //     and the executor emits it on no relation — an armed route with no default silently
        //     discards every row no branch claims.
        Object def = route.get("default");
        if (def == null || !keys.contains(String.valueOf(def)))
            out.add("an armed route: needs default: naming one of its branch keys (" + keys + ") — "
                    + "without it a row matching no branch is silently dropped");
        // (4) multi-schema stays authoring-only with route:. ⛔ Do NOT lift this by "fixing the branch
        //     count" — that was investigated 2026-08-26 and is the WRONG fix. The mechanism, grounded:
        //     `ConsignmentIngestStrategy.writeAndTrace` is called ONCE PER SEGMENT (see
        //     `UnionModeIngester`'s loop, destTable = "transformed_" + segKey), while the branch-aware
        //     divert inside it lifts `PipelineLift.lift(cfg)` — the WHOLE multi-schema graph. Arming
        //     would therefore execute EVERY schema's route tree against EVERY segment's table.
        //     A real fix needs a segment-scoped lift (only the current schema's subtree) — a design
        //     change, not an unrefusal. `ConsignmentGraphRunner.dataFedSinkCount`'s collapsing of identical
        //     route keys across schema branches is real but harmless: it feeds `engages()` (a `> 1`
        //     test, and any multi-schema+route count is already > 1) and one diagnostic log line.
        if (multiSchema)
            out.add("route: on a multi-schema pipeline (selector/segments) is authoring-only — arm it "
                    + "on a single-schema pipeline");
        return out;
    }

    /** The steps[] kinds a route branch may arm with — what the ingest walk can actually run with
     *  its NONE reference/execution context (see {@code branchStepRefusals}). */
    public static final Set<String> BRANCH_STEP_KINDS = Set.of(
            PipelineConfig.Step.FILTER, PipelineConfig.Step.DEDUP, PipelineConfig.Step.SUMMARIZE);

    /**
     * Every reason one branch's {@code steps[]} sub-chain would refuse to arm (MIDBRANCH-1). Empty
     * list = the chain arms. The rules mirror what the ingest graph walk can execute:
     * <ul>
     *   <li>{@code join} — {@code RowShaper.join} needs a {@code ReferenceResolver}; the ingest walk
     *       passes {@code NONE} (it refuses loudly), so the step would throw on the first batch;</li>
     *   <li>{@code dedup} with a windowed {@code scope:} — the durable ledger needs an
     *       {@code ExecutionContext}; the ingest walk passes {@code NONE};</li>
     *   <li>{@code route} — a nested branch tree inside a branch;</li>
     *   <li>a {@code filter} with no {@code where:} (or one carrying a pre-parse list key) — mid-branch
     *       filters run POST-map; {@code RowShaper.filter} reads only {@code where} and throws on a
     *       blank predicate;</li>
     *   <li>any other kind (contributed included) — fail-closed until its mid-branch execution is
     *       proven, the same posture {@code route:} itself shipped with.</li>
     * </ul>
     *
     * <p>⚠ The windowed-scope test is textual ({@code scope} present and not {@code consignment}) —
     * this module sits below {@code inspecto-engine} and cannot see {@code DedupScope}, whose parse
     * treats exactly null/blank/{@code consignment} as within-consignment and every other spelling as
     * a window (or malformed, which is unarmable too). Both sides of that contract are pinned by
     * {@code DedupScopeTest} and {@code RouteArmingTest}.
     */
    private static void branchStepRefusals(Map<?, ?> branch, List<String> out) {
        List<PipelineConfig.Step> steps = PipelineConfig.Step.branchSteps(branch);
        Object key = branch.get("key");
        for (PipelineConfig.Step s : steps) {
            String kind = s.kind();
            if (!BRANCH_STEP_KINDS.contains(kind)) {
                out.add("route: branch '" + key + "' chains a '" + kind + "' step, which cannot execute "
                        + "mid-branch — the ingest walk runs " + new java.util.TreeSet<>(BRANCH_STEP_KINDS)
                        + " only" + (PipelineConfig.Step.JOIN.equals(kind)
                                ? " (join needs a reference resolver the ingest lane does not carry; "
                                        + "run the join at rest via output_store:)"
                                : PipelineConfig.Step.ROUTE.equals(kind)
                                        ? " (a route cannot nest inside its own branch)" : ""));
                continue;
            }
            if (PipelineConfig.Step.DEDUP.equals(kind)) {
                Object scope = s.config().get("scope");
                if (scope != null && !String.valueOf(scope).isBlank()
                        && !"consignment".equalsIgnoreCase(String.valueOf(scope).trim()))
                    out.add("route: branch '" + key + "' chains a dedup with scope '" + scope + "' — a "
                            + "windowed dedup needs the durable ledger context the ingest walk does not "
                            + "carry; drop scope: (consignment-grain) or run it at rest via output_store:");
            }
            if (PipelineConfig.Step.FILTER.equals(kind)) {
                Object where = s.config().get("where");
                if (where == null || String.valueOf(where).isBlank())
                    out.add("route: branch '" + key + "' chains a filter with no where: predicate — a "
                            + "mid-branch filter runs post-map and needs one (the run fails otherwise)");
                for (String k : List.of("filter_target_column", "include_prefixes", "include_regex",
                        "exclude_prefixes", "exclude_regex"))
                    if (s.config().containsKey(k))
                        out.add("route: branch '" + key + "' chains a filter carrying pre-parse key '" + k
                                + "', which cannot execute mid-branch (rows are already parsed and mapped)");
            }
        }
    }

    /**
     * True when a draft's {@code processing} block selects among several schemas — the draft-map form
     * of {@code PipelineConfig.schemas().selector() != null || segments non-empty}, mirroring how
     * {@code PipelineConfigParser} reads both spellings ({@code parsing.plugin.segments} wins over
     * {@code processing.segments}).
     */
    public static boolean draftIsMultiSchema(Map<?, ?> processing, Map<?, ?> parsing) {
        if (processing != null && processing.get("schemas") instanceof List<?> l && !l.isEmpty()) return true;
        Object plugin = parsing == null ? null : parsing.get("plugin");
        Object segments = (plugin instanceof Map<?, ?> pm && pm.get("segments") != null)
                ? pm.get("segments")
                : (processing == null ? null : processing.get("segments"));
        return segments instanceof Map<?, ?> sm && !sm.isEmpty();
    }

    /** The {@code database} of every {@code sinks[]} entry in a draft, in declaration order. */
    public static List<String> draftSinkDatabases(Object sinks) {
        List<String> out = new ArrayList<>();
        if (!(sinks instanceof List<?> list)) return out;
        for (Object s : list)
            if (s instanceof Map<?, ?> m && m.get("database") != null)
                out.add(String.valueOf(m.get("database")));
        return out;
    }
}
