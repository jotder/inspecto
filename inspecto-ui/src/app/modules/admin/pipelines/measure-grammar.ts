import { AbstractControl, ValidatorFn } from '@angular/forms';

import MEASURE_CONTRACT from 'app/inspecto/mock/measure-grammar.contract.json';

/**
 * The `transform.summarize` measure grammar — client-side authoring validation for a rule the pipeline
 * itself never checks.
 *
 * <p><b>Why this exists.</b> `processing.summarize` is authoring-only until the branch-aware executor
 * arms it (`PipelineConfig.prepare()` refuses `active && summarize != null`), so a pipeline carrying
 * measures never parses them. The only thing that does is `MaterializeTask.compileSpec` — a *separate*
 * maintenance Job, on its own schedule. So `median(x)` saves clean, validates clean, and surfaces as an
 * `IllegalArgumentException` somewhere else entirely, possibly days later. Catching it at the keystroke
 * is the whole point.
 *
 * <p><b>The grammar, mirrored from the engine.</b> A measure is either the bare literal `count`
 * (`MaterializeTask.compileSpec` special-cases it, no field), or `agg(field)` where `agg` is one of
 * {@link MEASURE_AGGS} (`MeasureCompiler.parse` rejects anything else) and `field` is a SQL-safe
 * identifier (`MeasureCompiler.safeIdent`, `[A-Za-z_][A-Za-z0-9_]*`).
 *
 * <p>⚠ The agg list is NOT re-declared here — it comes from the committed
 * `measure-grammar.contract.json`, which `MeasureGrammarContractTest` compares against
 * `MeasureCompiler.AGGS`. Neither side can add an aggregate without the other's suite failing. Do not
 * inline the names; that is exactly the drift the contract file prevents.
 *
 * <p>⚠ One deliberate strictness gap: the engine skips the field check for `count`, so `count()` reaches
 * it and compiles to `COUNT(*)`. This rejects it, because a degenerate empty field is a typo every time
 * and the form is where a typo should die. Stricter-than-server is the safe direction here — the banned
 * direction is a client that accepts what the server refuses.
 */

/** The aggregates the engine's `MeasureCompiler` accepts, in its own order. Contract-pinned. */
export const MEASURE_AGGS: readonly string[] = MEASURE_CONTRACT.aggregations;

/** `MeasureCompiler.SAFE_IDENT`, anchored — a measure field must match it wholly. */
const SAFE_IDENT = /^[A-Za-z_][A-Za-z0-9_]*$/;

/**
 * Why `measure` is rejected, or `null` when it is well-formed. The message is the user-facing one, so it
 * names what to write rather than restating the regex.
 */
export function measureError(measure: string): string | null {
    const m = measure.trim();
    if (!m) return null; // blank is `required`'s business, not the grammar's
    if (m === 'count') return null;
    const open = m.indexOf('(');
    if (open < 0 || !m.endsWith(')')) {
        return `"${m}" must be count or agg(column) — for example sum(amount)`;
    }
    const agg = m.slice(0, open);
    const field = m.slice(open + 1, -1);
    if (!MEASURE_AGGS.includes(agg)) {
        return `"${agg}" is not an aggregate — use ${MEASURE_AGGS.join(', ')}`;
    }
    if (!SAFE_IDENT.test(field)) {
        return `"${m}" needs a single column name in the brackets, starting with a letter or _`;
    }
    return null;
}

/**
 * Validates every committed entry of a `type: 'list'` measures control. Reports the FIRST offending
 * entry — a chip row is short, and one precise message beats a concatenation of several.
 *
 * <p>Returns `{ message }`, which `<inspecto-schema-form>`'s `errorFor` renders verbatim; the generic
 * error keys cannot phrase a domain rule.
 */
export function measuresValidator(): ValidatorFn {
    return (control: AbstractControl) => {
        const value = control.value;
        if (!Array.isArray(value)) return null;
        for (const entry of value as unknown[]) {
            if (typeof entry !== 'string') continue; // the spec's own `list` check owns non-strings
            const error = measureError(entry);
            if (error) return { message: error };
        }
        return null;
    };
}
