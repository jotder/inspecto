package com.gamma.decision;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One Decision Rule consequence action (the string a rule's {@code consequences[].action} carries) and how it
 * executes: the registry seam that replaces the hard-coded string switch in {@code DecisionRoutes.executeOne}
 * (MODULE-REORG-P7-KERNEL, "Consequence registry"). Built-ins register through {@link BuiltInConsequences};
 * an optional module contributes more through {@link java.util.ServiceLoader} (fail-soft, see {@link Consequences}).
 *
 * <p>An action whose provider is absent (module not installed, or a required service missing) is reported
 * {@link Result#UNAVAILABLE} — never silently executed or skipped. An action nobody ever declared stays
 * {@link Result#SKIPPED} ("unknown action").
 */
public interface ConsequenceProvider {

    /** The action string, e.g. {@code emit-signal}. The registry key. */
    String id();

    /** Human label for the editor / the {@code GET /decision-rules/consequences} listing. */
    String displayName();

    /** {@code platform} | {@code routing} | {@code notify} | {@code object} | {@code integration}. */
    String group();

    /** Host service ids this consequence needs ({@link ConsequenceContext#has}); any missing one makes it unavailable. */
    default List<String> requires() { return List.of(); }

    /**
     * Execute for one matched application of a rule.
     *
     * @param consequence the whole consequence map ({@code action}, {@code destination}, {@code target},
     *                    {@code params}) — not only {@code params}, because the routing actions read
     *                    {@code destination} and {@code start-job} reads {@code target}
     */
    Result execute(ConsequenceContext ctx, Map<String, Object> consequence);

    /** The outcome of one consequence: {@code status}, a human {@code detail}, and extra response keys (e.g. {@code runId}). */
    record Result(String status, String detail, Map<String, Object> extras) {
        public static final String EXECUTED = "executed";
        public static final String SKIPPED = "skipped";
        public static final String UNAVAILABLE = "unavailable";

        public static Result executed(String detail) { return new Result(EXECUTED, detail, Map.of()); }
        public static Result skipped(String detail) { return new Result(SKIPPED, detail, Map.of()); }
        public static Result unavailable(String detail) { return new Result(UNAVAILABLE, detail, Map.of()); }

        public Result with(String key, Object value) {
            Map<String, Object> m = new LinkedHashMap<>(extras);
            if (value != null) m.put(key, value);
            return new Result(status, detail, m);
        }
    }
}
