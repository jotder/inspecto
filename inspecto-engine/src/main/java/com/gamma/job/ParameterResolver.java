package com.gamma.job;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a Job's declared {@link ParameterDecl}s to concrete values for one Run (job-framework §7.2,
 * the parameter slice of P1b/P3a + P3a-2). For each declaration the first hit wins across the layers:
 * <ol>
 *   <li>trigger {@code args} — explicit values on this firing (manual {@code POST} body {@code params:}
 *       or a Trigger's static {@code args:} block),</li>
 *   <li>signal {@code bind} — the Trigger's {@code bind:} map, each value a {@code $}-expression
 *       (typically {@code $signal.<field>}) evaluated against the firing Signal's payload,</li>
 *   <li>authored {@code config} (the {@code *_job.toon} {@code params:} block),</li>
 *   <li>{@code deduce} — the declaration's {@code $}-expression against the built-in context (§7.3),</li>
 *   <li>{@code defaultValue} — the literal fallback.</li>
 * </ol>
 * A {@code required} parameter still unresolved goes into {@link Resolution#missingRequired()} so the
 * framework can fail the Run <b>REJECTED</b> before any user code runs (§7.2, fail-closed). Likewise
 * (2026-07-20), a resolved value that doesn't parse as its declared {@link ParamType} goes into
 * {@link Resolution#invalidType()} instead of {@code resolved()} — a required INTEGER parameter bound
 * from {@code $signal.foo} to a non-numeric string is REJECTED here rather than throwing an uncaught
 * {@code NumberFormatException} deep inside a Job's {@code run(ctx)} once it tries to parse the string
 * itself ({@link ParamType} was previously form-gen/descriptor metadata only, never enforced).
 *
 * <p>{@code $}-expressions are no longer evaluated here: the vocabulary lives behind the
 * {@link ExpressionRegistry} seam (job-parameter-contract §4), so a plugin or Job Pack can contribute a
 * token without editing the engine. Consolidating that registry with {@code com.gamma.query.Parameters}
 * (SQL-literal output, a different token set) and {@link WhenGuard}'s {@code $signal} evaluator is
 * deliberate future work.
 */
final class ParameterResolver {

    private ParameterResolver() {}

    /** Outcome: the resolved values, any {@code required} names that stayed unresolved, and any name whose
     *  resolved value didn't parse as its declared {@link ParamType} (both ⇒ REJECTED). */
    record Resolution(Map<String, String> resolved, List<String> missingRequired, List<String> invalidType) {}

    static Resolution resolve(List<ParameterDecl> decls, Map<String, String> args,
                              Map<String, String> bind, Map<String, String> config,
                              ExpressionRegistry expressions, ExpressionContext ctx) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> invalidType = new ArrayList<>();
        for (ParameterDecl d : decls) {
            String v = value(d, args, bind, config, expressions, ctx);
            if (v == null) {
                if (d.required()) missing.add(d.name());
                continue;
            }
            if (!matchesType(d.type(), v)) {
                invalidType.add(d.name() + " (expected " + d.type() + ", got '" + v + "')");
                continue;
            }
            out.put(d.name(), v);
        }
        return new Resolution(Map.copyOf(out), List.copyOf(missing), List.copyOf(invalidType));
    }

    /** Whether {@code v} parses as {@code type} (§7.1). {@code STRING}/{@code DATASET_REF} accept any
     *  non-blank string — a dataset reference's *existence* is a different, later concern, not a parse
     *  format. {@code null}/blank never reaches here (see {@link #value}, which already excludes it). */
    private static boolean matchesType(ParamType type, String v) {
        try {
            switch (type) {
                case INTEGER: Long.parseLong(v); return true;
                case DECIMAL: Double.parseDouble(v); return true;
                case BOOLEAN: return "true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v);
                case DATE: LocalDate.parse(v); return true;
                case INSTANT: Instant.parse(v); return true;
                case STRING:
                case DATASET_REF:
                default: return true;
            }
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    /** First hit of: trigger args → signal bind → authored config → deduce → default. {@code null} ⇒ unresolved. */
    private static String value(ParameterDecl d, Map<String, String> args,
                                Map<String, String> bind, Map<String, String> config,
                                ExpressionRegistry expressions, ExpressionContext ctx) {
        String a = args.get(d.name());
        if (a != null && !a.isBlank()) return a;
        String b = bind.get(d.name());
        if (b != null && !b.isBlank()) {
            String bv = expressions.evaluate(b.trim(), ctx).orElse(null);
            if (bv != null) return bv;
        }
        String c = config.get(d.name());
        if (c != null && !c.isBlank()) return c;
        // Tier 3 dual-read (vocabulary plan §4): the `pipeline` job parameter's pre-rename config key was
        // `flow` — read-only fallback for *_job.toon files that were never resaved under the new name.
        if ("pipeline".equals(d.name())) {
            String legacy = config.get("flow");
            if (legacy != null && !legacy.isBlank()) return legacy;
        }
        if (d.deduce() != null && !d.deduce().isBlank()) {
            String dv = expressions.evaluate(d.deduce().trim(), ctx).orElse(null);
            if (dv != null) return dv;
        }
        return d.defaultValue();   // may be null
    }
}
