package com.gamma.pipeline;

import com.gamma.api.PublicApi;
import com.gamma.config.spec.Finding;
import com.gamma.etl.TransformCompiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Authoring-time validation of a {@code mapping} component's {@code rules} — the ELT amendment UI
 * plan's S6b "server validate" step.
 *
 * <h3>Why this is server-side</h3>
 * Every rule below is a precondition of {@link TransformCompiler}, which is the runtime authority on
 * what a mapping rule means. Restating those preconditions in TypeScript would let the browser accept
 * a mapping the engine then rejects (or crashes on) at run time — the mock-leniency failure mode. The
 * most drift-prone piece, the transform-type vocabulary, is not restated at all: it is read from
 * {@link TransformCompiler#TRANSFORM_TYPES}.
 *
 * <h3>What is deliberately NOT checked</h3>
 * An {@code EXPR} rule's SQL is <b>not</b> validated. That is not an oversight: {@code TransformCompiler}
 * emits it verbatim and documents the decision — schema config is operator-authored and trusted, the
 * same model as the Stage-2 transform SQL. Nor is a target column checked against a schema: a
 * {@code mapping} component carries no schema reference, so there is nothing to check it against here.
 *
 * <p>Pure: no I/O, no clock, no DuckDB. The result is a function of the rules alone.
 */
@PublicApi(since = "5.1.0")
public final class MappingRules {

    private MappingRules() {}

    /**
     * Validate mapping rules. Findings are anchored to {@code rules[N].<key>} so a grid editor can mark
     * the exact cell; a finding about the rule set as a whole carries a blank {@code fieldPath}.
     *
     * @param rules the rule rows, each with {@code targetColumn} / {@code sourceExpression} /
     *              {@code transformType} (a blank or absent type means {@code DIRECT})
     * @return findings in rule order; empty when the rules are clean
     */
    public static List<Finding> validate(List<Map<String, Object>> rules) {
        List<Finding> out = new ArrayList<>();
        if (rules == null || rules.isEmpty()) {
            out.add(Finding.error("", "A mapping needs at least one rule."));
            return out;
        }
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < rules.size(); i++) {
            Map<String, Object> rule = rules.get(i);
            String at = "rules[" + i + "].";
            String target = str(rule, "targetColumn");
            String source = str(rule, "sourceExpression");
            String type = str(rule, "transformType").toUpperCase();

            if (target.isEmpty()) {
                out.add(Finding.error(at + "targetColumn", "A target column is required."));
            } else {
                Integer prior = seen.putIfAbsent(target, i);
                if (prior != null) {
                    out.add(Finding.error(at + "targetColumn", "Duplicate target column '" + target
                            + "' — rule " + (prior + 1) + " already writes it."));
                }
            }

            // The vocabulary is TransformCompiler's own set, not a copy. Blank means DIRECT.
            if (!type.isEmpty() && !TransformCompiler.TRANSFORM_TYPES.contains(type)) {
                out.add(Finding.error(at + "transformType", "Unknown transform type '"
                        + str(rule, "transformType") + "'. Valid: DIRECT (or leave blank), "
                        + String.join(", ", TransformCompiler.TRANSFORM_TYPES) + "."));
                continue;   // the type-specific checks below cannot apply to a type we do not know
            }

            if (source.isEmpty()) {
                out.add(Finding.error(at + "sourceExpression", "A source expression is required."));
                continue;
            }

            switch (type) {
                // TransformCompiler.concatDt splits on '|' and reads part 2 unconditionally: without
                // the separator that is an index-out-of-bounds at run time, not a bad column.
                case "CONCAT_DT" -> {
                    if (!source.contains("|"))
                        out.add(Finding.error(at + "sourceExpression",
                                "CONCAT_DT needs '<dateColumn>|<timeColumn>'."));
                }
                // TransformCompiler.filenameDate rejects any other target.
                case "FILENAME_DATE" -> {
                    if (!"EVENT_DATE".equals(target))
                        out.add(Finding.error(at + "targetColumn",
                                "FILENAME_DATE is only supported for the EVENT_DATE column, got '"
                                + target + "'."));
                }
                default -> { }   // DIRECT/blank and EXPR carry no further structural precondition
            }
        }
        return out;
    }

    /** Trimmed string value of {@code key}, never {@code null}. */
    private static String str(Map<String, Object> rule, String key) {
        Object v = rule == null ? null : rule.get(key);
        return v == null ? "" : v.toString().trim();
    }
}
