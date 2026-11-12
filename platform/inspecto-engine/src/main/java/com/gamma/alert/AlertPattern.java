package com.gamma.alert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The {@code pattern:} block of a windowed Alert Rule (PA-9c, D-PA40): a shape an aggregate over one Window cannot
 * express. It rides on the rule's {@code window:} block, which still names the event-time column, the lateness and
 * the {@code distinctOn} identity.
 *
 * <pre>
 *   pattern: {type: sequence, steps: [&lt;when A&gt;, &lt;when B&gt;]}   window: {type: tumbling, size: 30m, time: event_ts, lateness: 2m}
 *   pattern: {type: absence}                                  window: {type: sliding, size: 5m, slide: 1m, time: event_ts, lateness: 1m}
 * </pre>
 *
 * <ul>
 *   <li><b>{@code sequence}</b> — an event matching step A, then an event matching step B for the same key, with
 *       {@code a.time ≤ b.time ≤ a.time + size} (the Window is anchored at A's event, so it is {@code tumbling} and
 *       takes no slide). Each step is its own Condition Language tree (D-PA42); the rule takes no {@code when} of its
 *       own. It fires once per (key, A's event time), as soon as both events are buffered, while A's Window is still
 *       open ({@code a.time + size + lateness > Watermark}).</li>
 *   <li><b>{@code absence}</b> — a key that had an event matching the rule's {@code when} in the slide just before a
 *       Window, and none in the Window. It is judged only once the Window has closed: when the close frontier —
 *       the event-time Watermark, or the clock floor on a silent Stream — reaches {@code hi + lateness}. It fires
 *       once per silence: the first Window after the key's last event.</li>
 * </ul>
 *
 * <p>A Pattern rule takes no Measure, comparator or threshold: they are fixed ({@code count}, {@code gte 1}), as a
 * freshness rule's are.
 */
public record AlertPattern(String type, List<Object> steps) {

    public static final String SEQUENCE = "sequence";
    public static final String ABSENCE = "absence";
    public static final Set<String> TYPES = Set.of(SEQUENCE, ABSENCE);
    /** The steps a sequence takes: A then B. Longer chains are not built (PA-9c). */
    public static final int SEQUENCE_STEPS = 2;
    static final Set<String> KEYS = Set.of("type", "steps");

    public AlertPattern {
        require(type != null && TYPES.contains(type), "alert.pattern.type must be one of " + TYPES);
        steps = steps == null ? List.of() : java.util.Collections.unmodifiableList(new ArrayList<>(steps));
        if (SEQUENCE.equals(type)) {
            require(steps.size() == SEQUENCE_STEPS, "a sequence alert.pattern takes exactly " + SEQUENCE_STEPS
                    + " steps [<when A>, <when B>], got " + steps.size());
            for (int i = 0; i < steps.size(); i++) {
                Object s = steps.get(i);
                require(s instanceof Map<?, ?> m && !m.isEmpty(), "alert.pattern.steps[" + i
                        + "] must be a Condition Language tree ({kind: group, op, items})");
                try {
                    com.gamma.query.ConditionTree.requireGroupRoot(s);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("alert.pattern.steps[" + i + "]: " + e.getMessage());
                }
            }
        } else {
            require(steps.isEmpty(), "an absence alert.pattern takes no steps (the rule's when says which events count)");
        }
    }

    public boolean isSequence() {
        return SEQUENCE.equals(type);
    }

    public boolean isAbsence() {
        return ABSENCE.equals(type);
    }

    /** Parse the authored block; every refusal is an {@link IllegalArgumentException} naming the key. */
    public static AlertPattern fromMap(Object raw) {
        require(raw instanceof Map<?, ?>, "alert.pattern must be a block {type: sequence|absence, steps}");
        Map<?, ?> m = (Map<?, ?>) raw;
        for (Object k : m.keySet())
            require(KEYS.contains(String.valueOf(k)), "alert.pattern." + k + " is not a pattern key (one of " + KEYS + ")");
        Object steps = m.get("steps");
        require(steps == null || steps instanceof List<?>, "alert.pattern.steps must be a list of Condition Language trees");
        Object t = m.get("type");
        return new AlertPattern(t == null ? null : String.valueOf(t).trim().toLowerCase(Locale.ROOT),
                steps == null ? null : new ArrayList<>((List<?>) steps));
    }

    /** The stored shape. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        if (!steps.isEmpty()) m.put("steps", steps);
        return m;
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }
}
