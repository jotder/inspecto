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

    /** Outcome: the resolved values, any {@code required} names that stayed unresolved, any name whose
     *  resolved value didn't parse as its declared {@link ParamType}, and any name whose {@code $}-value
     *  named a token no provider declares (all three ⇒ REJECTED). */
    record Resolution(Map<String, String> resolved, List<String> missingRequired, List<String> invalidType,
                      List<String> unknownExpression) {}

    /** One trip down the layer ladder: the value found ({@code null} ⇒ unresolved), or the unregistered
     *  token that stopped it (§6.3) — an Expression nobody declares must fail the Run, never fall through
     *  to the next layer, where it would surface as a confusing "missing required parameter". */
    private record Layered(String value, String unknownExpr) {
        static final Layered NONE = new Layered(null, null);
        static Layered of(String value)     { return new Layered(value, null); }
        static Layered unknown(String expr) { return new Layered(null, expr); }
        boolean stops() { return value != null || unknownExpr != null; }
    }

    /** Deliberately permissive: {@code local@domain.tld} with no spaces. An address is only truly validated
     *  by delivering to it, and a stricter regex rejects valid addresses — this catches the typo class the
     *  declaration is for, and nothing more. */
    private static final java.util.regex.Pattern EMAIL =
            java.util.regex.Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    static Resolution resolve(List<ParameterDecl> decls, Map<String, String> args,
                              Map<String, String> bind, Map<String, String> config,
                              ExpressionRegistry expressions, ExpressionContext ctx) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> invalidType = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (ParameterDecl d : decls) {
            Layered l = value(d, args, bind, config, expressions, ctx);
            if (l.unknownExpr() != null) {
                unknown.add(d.name() + " (unknown expression '" + l.unknownExpr() + "')");
                continue;
            }
            String v = l.value();
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
        return new Resolution(Map.copyOf(out), List.copyOf(missing), List.copyOf(invalidType),
                List.copyOf(unknown));
    }

    /** Whether {@code v} parses as {@code type} (§7.1). {@code STRING}/{@code TEXT}/{@code DATASET_REF}
     *  accept any non-blank string — a dataset reference's *existence* is a different, later concern, not a
     *  parse format. {@code null}/blank never reaches here (see {@link #value}, which already excludes it). */
    private static boolean matchesType(ParamType type, String v) {
        try {
            switch (type) {
                case INTEGER: Long.parseLong(v); return true;
                case DECIMAL: Double.parseDouble(v); return true;
                case BOOLEAN: return "true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v);
                case DATE: LocalDate.parse(v); return true;
                case INSTANT: Instant.parse(v); return true;
                case EMAIL: return EMAIL.matcher(v).matches();
                case STRING:
                case TEXT:
                case DATASET_REF:
                default: return true;
            }
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    /** First hit of: trigger args → signal bind → authored config → deduce → default. A layer holding an
     *  Expression also stops the ladder when its token is unregistered (§6.3). */
    private static Layered value(ParameterDecl d, Map<String, String> args,
                                 Map<String, String> bind, Map<String, String> config,
                                 ExpressionRegistry expressions, ExpressionContext ctx) {
        String a = args.get(d.name());
        if (a != null && !a.isBlank()) {
            Layered av = authored(d, a.trim(), expressions, ctx);
            if (av.stops()) return av;
        }
        String b = bind.get(d.name());
        if (b != null && !b.isBlank()) {
            Layered bv = expression(b.trim(), expressions, ctx);
            if (bv.stops()) return bv;
        }
        String c = config.get(d.name());
        if (c != null && !c.isBlank()) {
            Layered cv = authored(d, c.trim(), expressions, ctx);
            if (cv.stops()) return cv;
        }
        // Tier 3 dual-read (vocabulary plan §4): the `pipeline` job parameter's pre-rename config key was
        // `flow` — read-only fallback for *_job.toon files that were never resaved under the new name.
        if ("pipeline".equals(d.name())) {
            String legacy = config.get("flow");
            if (legacy != null && !legacy.isBlank()) {
                Layered lv = authored(d, legacy.trim(), expressions, ctx);
                if (lv.stops()) return lv;
            }
        }
        if (d.deduce() != null && !d.deduce().isBlank()) {
            Layered dv = expression(d.deduce().trim(), expressions, ctx);
            if (dv.stops()) return dv;
        }
        return Layered.of(d.defaultValue());   // may be null
    }

    /** An author-typed value — trigger {@code args} (layer 1) or the {@code params:} block (layer 3), the
     *  two places the UI writes (§6.1). Unlike {@code bind:}/{@code deduce:}, whose whole purpose is to hold
     *  an Expression, these layers are <em>usually</em> literals: anything that is not a whole-value token
     *  stops the ladder as itself. A declaration may opt out entirely with {@code expressions: false}, which
     *  is how the {@code sql.template} body stays verbatim. */
    private static Layered authored(ParameterDecl d, String raw, ExpressionRegistry expressions,
                                    ExpressionContext ctx) {
        if (!d.expressions()) return Layered.of(raw);
        if (raw.startsWith("$$")) return Layered.of(ExpressionRegistry.unescape(raw));
        if (!ExpressionRegistry.isExpression(raw)) return Layered.of(raw);
        if (!expressions.declares(raw)) return Layered.unknown(raw);
        return expressions.evaluate(raw, ctx).map(Layered::of).orElse(Layered.NONE);
    }

    /** Evaluate one {@code bind:}/{@code deduce:} value (§6.2/§6.3): the {@code $$} escape yields a literal
     *  {@code $}; a registered token yields its value, or {@link Layered#NONE} when it has none in this
     *  context (a bind to an absent {@code $signal.<field>} still falls through to the next layer); an
     *  unregistered token fails the Run. A value that is not {@code $}-led can never name a token, so it
     *  keeps its pre-registry behaviour of falling through. */
    private static Layered expression(String raw, ExpressionRegistry expressions, ExpressionContext ctx) {
        if (!ExpressionRegistry.isExpression(raw))
            return raw.startsWith("$$") ? Layered.of(ExpressionRegistry.unescape(raw)) : Layered.NONE;
        if (!expressions.declares(raw)) return Layered.unknown(raw);
        return expressions.evaluate(raw, ctx).map(Layered::of).orElse(Layered.NONE);
    }
}
