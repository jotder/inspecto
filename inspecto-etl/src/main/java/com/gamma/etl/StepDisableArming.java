package com.gamma.etl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The single statement of when {@code processing.disabled_steps} may ARM (ELT Phase 4 S4 / D-13,
 * {@code docs/superpower/elt-s4-park-drain-plan.md}) — the same free-function-over-plain-data shape
 * as {@link RouteArming}, for the same two callers in two states: {@link PipelineConfig#prepare()}
 * throws the first refusal at registration; {@code ConfigRoutes} reports them all as {@code Finding}s
 * at save time over the unparsed draft (ERROR on an active pipeline, WARNING on an inactive draft).
 *
 * <p><b>S4a posture: EVERY non-empty list refuses to arm.</b> Park/drain semantics do not exist yet
 * (S4b/S4c) — a disabled step on the at-rest path today could only be the skip-and-vanish bypass,
 * which silently drops the rows the step would have carried. The shape lands first so authoring,
 * round-trip and the canvas toggle are real; execution stays fail-closed, exactly how {@code route:}
 * itself landed. When S4b ships, this gate relaxes exactly one shape — steps strictly inside an armed
 * {@code route:}'s route→sink subtree — and everything else keeps refusing by name (the plan's
 * Refusals list).
 */
public final class StepDisableArming {

    private StepDisableArming() {}

    /**
     * Every reason {@code disabled_steps} would refuse to arm — empty list in, or empty result, means
     * it arms. Scratch paths (dry-run / run-to-here) are unaffected either way: they keep the
     * in-memory bypass semantics regardless of arming.
     */
    public static List<String> refusals(List<String> disabledSteps) {
        List<String> out = new ArrayList<>();
        if (disabledSteps == null || disabledSteps.isEmpty()) return out;
        out.add("processing.disabled_steps names " + disabledSteps + ", but per-Step park/drain "
                + "semantics are not built yet (S4b) — a disabled step at rest would silently drop its "
                + "rows. Keep the pipeline inactive (active: false), remove the entries, or use "
                + "dry-run / run-to-here to test around a step");
        return out;
    }

    /** The draft-map form: {@code processing.disabled_steps} as a string list (absent ⇒ empty). */
    public static List<String> draftDisabledSteps(Map<?, ?> processing) {
        List<String> out = new ArrayList<>();
        if (processing != null && processing.get("disabled_steps") instanceof List<?> ds)
            for (Object s : ds) out.add(String.valueOf(s));
        return out;
    }
}
