package com.gamma.control;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The Space rule for a <b>Working Set Widget</b> (LA-21, decision D-E6 in
 * {@code docs/superpower/link-analysis-backlog-plan.md} §4): a {@code widget} component whose content carries a
 * {@code workingSet} binding {@code {relation, mode, pin{step, workingSetHash, pinnedAt}}} and whose {@code viewId} names
 * the Investigation it reads. The Widget holds NO rows — every render reads through
 * {@code GET /inv/investigations/{id}/working-set}, so the owner-only / PDP gate (D-E7) applies on every read.
 *
 * <p>D-E6: a Widget is FROZEN by default (it re-reads the relation at its pinned step); LIVE is opt-in and re-reads the
 * head, and <b>a Live Widget cannot leave the Space</b>. This class is the one statement of that rule for the paths a
 * Widget leaves by — the core cannot see the optional {@code inspecto-geo-link} module, so it judges the content only:
 * <ul>
 *   <li>the Exchange ({@code inspecto-exchange}) REFUSES both modes — a Live one under D-E6, a Frozen one because the
 *       Exchange grants Datasets and a Working Set is not one: under D-E7 only the Investigation's owner may evaluate
 *       it, so no consumer Space could ever render it;</li>
 *   <li>a Metadata Bundle export ({@link BundleRoutes}) CONVERTS a Live Widget to a Frozen one at its own pin and
 *       reports it — the copy that leaves never moves, and still reads through the gate wherever it lands.</li>
 * </ul>
 * A whole-Space export ({@code GET /export}) is exempt on purpose: it zips the Space's whole write root, the
 * Investigation's sealed log and owner included, so the Widget travels WITH what it reads rather than leaving it.
 */
public final class WorkingSetWidgets {

    private WorkingSetWidgets() {}

    public static final String LIVE = "live";
    public static final String FROZEN = "frozen";

    /** The {@code workingSet} binding of a widget's content, if it is a Working Set Widget. */
    @SuppressWarnings("unchecked")
    public static Optional<Map<String, Object>> binding(Map<String, Object> content) {
        return content != null && content.get("workingSet") instanceof Map<?, ?> m
                ? Optional.of((Map<String, Object>) m) : Optional.empty();
    }

    /** True for a Working Set Widget in Live mode — the one D-E6 keeps inside its Space. */
    public static boolean isLive(Map<String, Object> content) {
        return binding(content).map(b -> LIVE.equals(b.get("mode"))).orElse(false);
    }

    /** Why the Exchange refuses this widget, or empty when it is not a Working Set Widget. */
    public static Optional<String> exchangeRefusal(String item, Map<String, Object> content) {
        if (binding(content).isEmpty()) return Optional.empty();
        if (isLive(content))
            return Optional.of("widget '" + item + "' is a Live Working Set Widget, and a Live Widget cannot leave its "
                    + "Space (D-E6)");
        return Optional.of("widget '" + item + "' reads an Investigation's Working Set, not a Dataset: only the "
                + "Investigation's owner may evaluate it (D-E7), so no other Space could render it");
    }

    /** A Live Working Set Widget's content as the Frozen Widget it becomes on leaving the Space: same pin, mode frozen. */
    public static Map<String, Object> frozenCopy(Map<String, Object> content) {
        Map<String, Object> binding = new LinkedHashMap<>(binding(content).orElseThrow());
        binding.put("mode", FROZEN);
        Map<String, Object> out = new LinkedHashMap<>(content);
        out.put("workingSet", binding);
        return out;
    }
}
