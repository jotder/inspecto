package com.gamma.etl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single statement of when {@code processing.disabled_steps} may ARM (ELT Phase 4 S4,
 * {@code docs/superpower/elt-s4-park-drain-plan.md}) — the same free-function-over-plain-data shape
 * as {@link RouteArming}, for the same two callers in two states: {@link PipelineConfig#prepare()}
 * throws the first refusal at registration; {@code ConfigRoutes} reports them all as {@code Finding}s
 * at save time over the unparsed draft (ERROR on an active pipeline, WARNING on an inactive draft).
 *
 * <p><b>S4b posture: exactly the route-branch SINKS of an armed {@code route:} pipeline may be
 * disabled</b> — the executor is seeded downstream of parse/map at rest, so a route branch's sink is
 * the one boundary where the rows are already materialised and can PARK durably
 * ({@code PipelineExecutor.ParkWriter}). Everything else refuses by name, per the plan's Refusals
 * list: no armed route (the flat lane has no park boundary), an upstream/unknown step (a typo must
 * never become a silently-enabled step), the route node itself (the divert's engagement anchor), and
 * disabling EVERY branch (the executor would have no live sink; {@code active: false} is that ask's
 * honest spelling).
 *
 * <p><b>And no park home.</b> {@code dirs.backup} is OPTIONAL ({@code PipelineConfigParser} reads it
 * with a plain {@code get}), but {@code ConsignmentIngestor.parkSource} parks under
 * {@code <backup>/parked} — so without it the pipeline arms, runs, and dies at the park with an NPE
 * the operator sees as {@code park failed: null}, one batch at a time. That is the same
 * arms-then-fails-every-cycle shape every refusal here exists to convert into an authoring-time
 * answer, so it is refused by name rather than left to the filesystem.
 */
public final class StepDisableArming {

    private StepDisableArming() {}

    /**
     * Every reason {@code disabledSteps} would refuse to arm — empty in, or empty result, arms.
     * Scratch paths (dry-run / run-to-here) are unaffected either way: they keep the in-memory
     * bypass regardless of arming.
     *
     * @param disabledSteps   {@code processing.disabled_steps} — step (node) ids
     * @param parkableSinkIds the lifted ids of the armed route-branch sinks — the ONLY steps that can
     *                        park; empty when the pipeline has no armed {@code route:}. Supplied by
     *                        the caller from what it has ({@link #parkableSinkIds}), exactly like
     *                        {@link RouteArming}'s sink databases.
     * @param parkHomeConfigured whether {@code dirs.backup} is set — the park home's parent. A required
     *                        argument rather than an overload defaulting to {@code true}: a caller that
     *                        cannot answer must not be allowed to arm a pipeline that has nowhere to park.
     */
    public static List<String> refusals(List<String> disabledSteps, Collection<String> parkableSinkIds,
                                        boolean parkHomeConfigured) {
        List<String> out = new ArrayList<>();
        if (disabledSteps == null || disabledSteps.isEmpty()) return out;

        Set<String> parkable = new LinkedHashSet<>(parkableSinkIds == null ? List.of() : parkableSinkIds);
        if (parkable.isEmpty()) {
            out.add("processing.disabled_steps names " + disabledSteps + ", but only a route-branch "
                    + "sink of an armed route: pipeline can park — the flat lane has no park boundary. "
                    + "Keep the pipeline inactive (active: false), remove the entries, or use dry-run / "
                    + "run-to-here to test around a step");
            return out;
        }
        if (!parkHomeConfigured)
            out.add("processing.disabled_steps names " + disabledSteps + ", but dirs.backup is not "
                    + "configured and a parked Consignment's members are moved to <backup>/parked — "
                    + "there is nowhere to park. Set dirs.backup, or remove the entries (dry-run / "
                    + "run-to-here tests around a step without parking)");
        for (String step : disabledSteps) {
            if (!parkable.contains(step))
                out.add("processing.disabled_steps entry '" + step + "' is not a route-branch sink "
                        + "(parkable: " + parkable + ") — only a branch sink has a park boundary at "
                        + "rest; an upstream step has none (use active: false, or dry-run / "
                        + "run-to-here), and an unknown id would otherwise be a silently-enabled step");
        }
        if (disabledSteps.containsAll(parkable))
            out.add("processing.disabled_steps disables EVERY route branch (" + parkable + ") — at "
                    + "least one must stay live; pausing the whole pipeline is spelled active: false");
        return out;
    }

    /**
     * The lifted node ids of an armed {@code route:} pipeline's branch sinks, from plain config data:
     * {@code sink__d<i>} for each {@code sinks[]} index whose {@code database} matches a branch
     * (single-destination shorthand: {@code sink}). Empty when there is no route block or no branches.
     *
     * <p>⚠ This mirrors {@code PipelineLift.emitSinks}' id grammar — legitimate only because an armed
     * {@code route:} pipeline is single-schema (RouteArming rule 4), where the lift's schema suffix is
     * empty. The mirror is pinned VERBATIM against a real lift by
     * {@code PipelineLiftTest.parkableSinkIdsMatchTheLiftedGraph} — extend the grammar there and here
     * together or the gate drifts from the graph.
     */
    public static List<String> parkableSinkIds(Map<?, ?> route, List<String> sinkDatabases) {
        List<String> out = new ArrayList<>();
        if (route == null || sinkDatabases == null || sinkDatabases.isEmpty()) return out;
        List<?> branches = route.get("branches") instanceof List<?> b ? b : List.of();
        if (branches.isEmpty()) return out;
        Set<String> branchDbs = new LinkedHashSet<>();
        for (Object b : branches)
            if (b instanceof Map<?, ?> m && m.get("database") != null)
                branchDbs.add(String.valueOf(m.get("database")));
        for (int d = 0; d < sinkDatabases.size(); d++) {
            if (branchDbs.contains(sinkDatabases.get(d)))
                out.add("sink" + (sinkDatabases.size() == 1 ? "" : "__d" + d));
        }
        return out;
    }

    /**
     * The draft-map form of {@code parkHomeConfigured}: {@code dirs.backup} present and non-blank.
     * Mirrors {@code PipelineConfigParser}'s plain {@code dirs.get("backup")} — absent and blank are
     * the same "no park home" to every consumer of it.
     */
    public static boolean draftHasParkHome(Map<?, ?> dirs) {
        Object backup = dirs == null ? null : dirs.get("backup");
        return backup != null && !String.valueOf(backup).isBlank();
    }

    /** The draft-map form: {@code processing.disabled_steps} as a string list (absent ⇒ empty). */
    public static List<String> draftDisabledSteps(Map<?, ?> processing) {
        List<String> out = new ArrayList<>();
        if (processing != null && processing.get("disabled_steps") instanceof List<?> ds)
            for (Object s : ds) out.add(String.valueOf(s));
        return out;
    }
}
