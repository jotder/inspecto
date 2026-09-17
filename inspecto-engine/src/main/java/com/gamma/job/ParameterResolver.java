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
     *  named a token no provider declares (those three ⇒ REJECTED) — plus {@code undeclared}, the
     *  authored {@code params:} keys no declaration covers, which is a <b>WARNING only</b>. */
    record Resolution(Map<String, String> resolved, List<String> missingRequired, List<String> invalidType,
                      List<String> unknownExpression, List<String> undeclared,
                      Map<String, Provenance> provenance) {
        /** The pre-provenance shape, for callers that only want the values. */
        Resolution(Map<String, String> resolved, List<String> missingRequired, List<String> invalidType,
                   List<String> unknownExpression) {
            this(resolved, missingRequired, invalidType, unknownExpression, List.of(), Map.of());
        }
    }

    /**
     * Authored {@code job:} keys that land in {@link JobConfig#params()} but are read by the
     * <em>framework</em>, not by any Job Type — so no {@link JobTypeDescriptor} can ever declare them and
     * reporting them as undeclared would be a false positive by construction. Kept deliberately tiny:
     * a key earns a place here only by having a named read in framework code.
     * <ul>
     *   <li>{@code on_pipeline_gate} — {@code JobService.onCommit}'s {@code any}/{@code all} multi-upstream
     *       semantics, documented on {@link JobConfig} itself and applicable to every type.</li>
     * </ul>
     * ⚠ {@code flow} is <b>not</b> here: it is the pre-rename alias of the {@code pipeline} parameter and is
     * excused below only when a {@code pipeline} declaration exists — i.e. exactly when the ladder's
     * {@code config:flow} rung actually reads it.
     */
    private static final java.util.Set<String> FRAMEWORK_KEYS = java.util.Set.of("on_pipeline_gate");

    /**
     * Where a resolved value came from, and what it overrode (`DUCKLE-C4-PARAM-PROVENANCE-1`, 2026-09-15).
     * {@code source} is the layer that won — {@code args} (trigger / manual), {@code bind} (signal), {@code config}
     * (the {@code params:} block), {@code config:flow} (the legacy alias), {@code deduce}, {@code default} —
     * and {@code overrode} names the LOWER layers that also carried a value, in ladder order, ⚠ only when
     * their authored value differs from the winner's: two surfaces agreeing is not an override. Values are
     * never recorded here — a {@code secret} is therefore a non-question; "was a token supplied, and by
     * whom" is exactly what the layer names answer.
     */
    record Provenance(String source, List<String> overrode) {}

    /** One trip down the layer ladder: the value found ({@code null} ⇒ unresolved), or the unregistered
     *  token that stopped it (§6.3) — an Expression nobody declares must fail the Run, never fall through
     *  to the next layer, where it would surface as a confusing "missing required parameter". */
    private record Layered(String value, String unknownExpr, String source, List<String> overrode) {
        static final Layered NONE = new Layered(null, null, null, List.of());
        static Layered of(String value)     { return new Layered(value, null, null, List.of()); }
        static Layered unknown(String expr) { return new Layered(null, expr, null, List.of()); }
        boolean stops() { return value != null || unknownExpr != null; }
        Layered from(String layer, List<String> overrode) { return new Layered(value, unknownExpr, layer, List.copyOf(overrode)); }
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
        Map<String, Provenance> provenance = new LinkedHashMap<>();
        for (ParameterDecl d : decls) {
            Layered l = value(d, args, bind, config, expressions, ctx);
            if (l.unknownExpr() != null) {
                // ⚠ The unregistered token here IS the authored value. A `secret` literal that merely
                // happens to start with `$` (a password like `$ecret1`) is read as an expression, so this
                // message would otherwise print it verbatim — masked for the same reason as a violation.
                unknown.add(d.name() + " (unknown expression '"
                        + SecretMasking.shown(d, l.unknownExpr()) + "')");
                continue;
            }
            String v = l.value();
            if (v == null) {
                if (d.required()) missing.add(d.name());
                continue;
            }
            String violation = violation(d, v);
            if (violation != null) {
                invalidType.add(d.name() + " " + violation);
                continue;
            }
            out.put(d.name(), v);
            provenance.put(d.name(), new Provenance(l.source(), l.overrode()));
        }
        return new Resolution(Map.copyOf(out), List.copyOf(missing), List.copyOf(invalidType),
                List.copyOf(unknown), undeclared(decls, config), Map.copyOf(provenance));
    }

    /**
     * The authored {@code params:} keys no {@link ParameterDecl} covers
     * ({@code JOB-PARAM-UNDECLARED-UNREPORTED-1}). ⛔ <b>A WARNING, never a rejection.</b> A descriptor is
     * the UI/API contract, not the read set: built-in Jobs legitimately reach keys their own descriptor
     * never declares straight through {@link JobConfig#require}/{@link JobConfig#opt} — {@code data_dir}
     * and {@code batch_id} ({@code PipelineJobRunner}), {@code sleep_ms} ({@code MaintenanceJob}),
     * {@code top} ({@code StorageReportTask}/{@code StorageTrendTask}), {@code history_days}
     * ({@code ReferenceCompactor}), {@code max_attempts}/{@code backoff_minutes}
     * ({@code SoftBounceRetryTask}) — so a fail-closed version would refuse working configs on day one.
     * What this catches is the dead-property class: a typo'd or retired key that is authored, persisted,
     * shown in the editor, and read by nothing.
     *
     * <p>Only the {@code config} layer is checked. Trigger {@code args}/{@code bind} are per-firing and
     * already reported through {@code unknownExpression}; an extra key there is not a config that rots.
     */
    private static List<String> undeclared(List<ParameterDecl> decls, Map<String, String> config) {
        java.util.Set<String> declared = new java.util.HashSet<>();
        for (ParameterDecl d : decls) declared.add(d.name());
        List<String> extra = new ArrayList<>();
        for (String key : config.keySet()) {
            if (declared.contains(key) || FRAMEWORK_KEYS.contains(key)) continue;
            if ("flow".equals(key) && declared.contains("pipeline")) continue;   // the config:flow rung read it
            extra.add(key);
        }
        return List.copyOf(extra);
    }

    /**
     * The WARNING text for a non-empty {@link Resolution#undeclared()}. Shared by the two surfaces that
     * report it — {@code JobService}'s run log and {@link PackTestHarness} — so a pack author reads the
     * production wording rather than a lookalike that can drift away from it
     * ({@code PACKHARNESS-NO-UNDECLARED-1}). ⛔ Still a warning at both call sites: this builds a string,
     * never a rejection, and {@code rejection()}/{@code reasons} deliberately exclude {@code undeclared}.
     */
    static String undeclaredWarning(String typeId, List<String> undeclared) {
        return "undeclared parameter(s) for job type '" + typeId + "': " + String.join(", ", undeclared)
                + " — no declaration covers them, so nothing validates or renders them; the Job may "
                + "still read them directly";
    }

    /** Check a resolved value against the declaration's full contract (§7.2, step 8) — type, then
     *  {@code options}, {@code pattern} and {@code min}/{@code max}. Returns {@code null} when it holds, or
     *  the reason it doesn't.
     *
     *  <p>This runs on the value the Run will actually use, so it enforces the contract identically whether
     *  the value was authored literally or produced by an Expression — §6.4's "re-validate after
     *  resolution", with no separate path to drift. A <em>pre</em>-resolution check that an Expression's
     *  declared {@code yields} suits the field is deliberately <b>not</b> done here: at fire time it could
     *  only reject values that post-resolution validation already accepts or refuses on the evidence (a
     *  {@code STRING}-yielding {@code $signal.<field>} legitimately carries a date). That check earns its
     *  keep at <em>author</em> time, in the picker and a dry-validate route, not in the resolver. */
    private static String violation(ParameterDecl d, String v) {
        if (!d.multi()) return itemViolation(d, v);
        // multi: CSV, the house convention for list-valued job params (e.g. objects.analytics `types`).
        // Every item is checked, so one bad entry fails the Run rather than being silently dropped.
        String[] items = v.split(",", -1);
        for (String raw : items) {
            String item = raw.trim();
            // The whole CSV is echoed here, so it is masked for the same reason each item is.
            if (item.isEmpty()) return "(empty item in list '" + SecretMasking.shown(d, v) + "')";
            String bad = itemViolation(d, item);
            if (bad != null) return bad;
        }
        return null;
    }

    /** One value — or one item of a {@code multi} list — against type + options + pattern + bounds.
     *
     *  <p>⛔ <b>Every {@code got '…'} here shows {@code got}, never {@code v}</b> (PARAM-SECRET-LEAK-1).
     *  For a declaration marked {@code secret} the offending value is a credential, and these strings do
     *  not stay local: {@code resolve} collects them into {@code invalidType}, which {@code JobService}
     *  then writes to the run log, to the {@code job.run.rejected} Signal, and into the persisted
     *  {@code JobRun.reason} that the UI shows — four sinks from one concatenation.
     *  ⚠ The declaration's own terms ({@code type}, {@code options}, {@code pattern}, bounds) are NOT
     *  masked: they are what the author wrote, not what the operator supplied, and hiding them would
     *  leave a rejection message that says nothing at all. */
    private static String itemViolation(ParameterDecl d, String v) {
        String got = SecretMasking.shown(d, v);
        if (!matchesType(d.type(), v)) return "(expected " + d.type() + ", got '" + got + "')";
        if (!d.options().isEmpty() && !d.options().contains(v))
            return "(expected one of " + d.options() + ", got '" + got + "')";
        if (d.pattern() != null && !v.matches(d.pattern()))
            return "(does not match " + d.pattern() + ", got '" + got + "')";
        if (d.min() != null || d.max() != null) {
            double n;
            try {
                n = Double.parseDouble(v);
            } catch (NumberFormatException notNumeric) {
                return "(bounded parameter is not numeric, got '" + got + "')";
            }
            if (d.min() != null && n < d.min()) return "(below minimum " + d.min() + ", got '" + got + "')";
            if (d.max() != null && n > d.max()) return "(above maximum " + d.max() + ", got '" + got + "')";
        }
        return null;
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
        // The ladder, top down. Each rung is (layer name, raw authored text, how to read it). The FIRST rung
        // that stops wins — exactly as before — but the walk no longer returns there: it keeps going to
        // learn which lower rungs ALSO carried a (different) value, which is the provenance the receipt
        // records (DUCKLE-C4). Rungs whose raw text is blank are not "supplied" and are not overrides.
        Layered winner = null;
        String winnerLayer = null;
        String winnerRaw = null;
        List<String> overrode = new ArrayList<>();
        String[][] rungs = {
                {"args", args.get(d.name())},
                {"bind", bind.get(d.name())},
                {"config", config.get(d.name())},
                // Tier 3 dual-read (vocabulary plan §4): the `pipeline` job parameter's pre-rename config key
                // was `flow` — read-only fallback for *_job.toon files that were never resaved under the new name.
                {"config:flow", "pipeline".equals(d.name()) ? config.get("flow") : null},
                {"deduce", d.deduce()},
        };
        for (String[] rung : rungs) {
            String raw = rung[1];
            if (raw == null || raw.isBlank()) continue;
            if (winner != null) {
                if (!raw.trim().equals(winnerRaw)) overrode.add(rung[0]);   // agreeing is not overriding
                continue;
            }
            Layered l = switch (rung[0]) {
                case "bind", "deduce" -> expression(raw.trim(), expressions, ctx);
                default -> authored(d, raw.trim(), expressions, ctx);
            };
            if (l.stops()) { winner = l; winnerLayer = rung[0]; winnerRaw = raw.trim(); }
        }
        if (winner != null) return winner.from(winnerLayer, overrode);
        return Layered.of(d.defaultValue()).from(d.defaultValue() == null ? null : "default", List.of());   // may be null
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
