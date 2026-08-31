import { AbstractControl, ValidatorFn } from '@angular/forms';

import MEASURE_CONTRACT from 'app/inspecto/contracts/measure-grammar.contract.json';

/**
 * The `transform.summarize` grammar — client-side authoring validation for rules the pipeline itself
 * never checks. Covers **both** of the node's fields: `measures` and `group_by`.
 *
 * <p><b>Why this exists.</b> `processing.summarize` is authoring-only until the branch-aware executor
 * arms it (`PipelineConfig.prepare()` refuses `active && summarize != null`), so a pipeline carrying
 * measures never parses them. The only thing that does is `MaterializeTask.compileSpec` — a *separate*
 * maintenance Job, on its own schedule. So `median(x)` saves clean, validates clean, and surfaces as an
 * `IllegalArgumentException` somewhere else entirely, possibly days later. Catching it at the keystroke
 * is the whole point. `group_by` reaches the same compiler by the same route
 * (`MaterializeTask:135` splits it into `body.groupBy`, `MeasureCompiler:66` runs every entry through
 * `safeIdent`), so `region name` fails exactly as far from the form as a bad measure does.
 *
 * <p><b>The grammar, mirrored from the engine.</b> A measure is either the bare literal `count`
 * (`MaterializeTask.compileSpec` special-cases it, no field), or `agg(field)` where `agg` is one of
 * {@link MEASURE_AGGS} (`MeasureCompiler.parse` rejects anything else) and `field` is a SQL-safe
 * identifier ({@link SAFE_IDENT}). A `group_by` entry is that identifier and nothing else — no call
 * brackets, no expression, since `MeasureCompiler` quotes each one straight into `GROUP BY`.
 *
 * <p>⚠ Neither the agg list NOR the identifier pattern is re-declared here — both come from the
 * committed `measure-grammar.contract.json`, which `MeasureGrammarContractTest` compares against
 * `MeasureCompiler.AGGS` and `MeasureCompiler.SAFE_IDENT`. Neither side can move alone without the
 * other's suite failing. Do not inline either; that is exactly the drift the contract file prevents.
 *
 * <p>⚠ One deliberate strictness gap: the engine skips the field check for `count`, so `count()` reaches
 * it and compiles to `COUNT(*)`. This rejects it, because a degenerate empty field is a typo every time
 * and the form is where a typo should die. Stricter-than-server is the safe direction here — the banned
 * direction is a client that accepts what the server refuses.
 */

/** The aggregates the engine's `MeasureCompiler` accepts, in its own order. Contract-pinned. */
export const MEASURE_AGGS: readonly string[] = MEASURE_CONTRACT.aggregations;

/**
 * `MeasureCompiler.SAFE_IDENT`, contract-pinned and **anchored here** — Java's `matcher().matches()` is
 * whole-string while JS `test()` is a substring search, so the bare pattern travels and each side applies
 * its own language's "wholly" rule. Unanchored, `1amount` would pass on this side alone.
 */
const SAFE_IDENT = new RegExp(`^(?:${MEASURE_CONTRACT.identifier})$`);

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
 * Why a `group_by` column is rejected, or `null` when it is well-formed.
 *
 * <p>Deliberately a bare identifier and not the measure grammar's superset: a grouping column is quoted
 * verbatim into `GROUP BY` (`MeasureCompiler.compile`), so `sum(amount)` there is a category error rather
 * than a near-miss — the message says so instead of restating the pattern.
 */
export function groupByError(column: string): string | null {
    const c = column.trim();
    if (!c) return null; // blank is `required`'s business, not the grammar's
    if (SAFE_IDENT.test(c)) return null;
    if (c.includes('(')) {
        return `"${c}" is a column to group by, not an aggregate — put aggregates in Measures`;
    }
    return `"${c}" must be a single column name, starting with a letter or _`;
}

/**
 * Validates every committed entry of a `type: 'list'` control against one entry-level rule. Reports the
 * FIRST offending entry — a chip row is short, and one precise message beats a concatenation of several.
 *
 * <p>Returns `{ message }`, which `<inspecto-schema-form>`'s `errorFor` renders verbatim; the generic
 * error keys cannot phrase a domain rule.
 */
function listEntryValidator(entryError: (entry: string) => string | null): ValidatorFn {
    return (control: AbstractControl) => {
        const value = control.value;
        if (!Array.isArray(value)) return null;
        for (const entry of value as unknown[]) {
            if (typeof entry !== 'string') continue; // the spec's own `list` check owns non-strings
            const error = entryError(entry);
            if (error) return { message: error };
        }
        return null;
    };
}

/** The `transform.summarize` `measures` list validator. */
export function measuresValidator(): ValidatorFn {
    return listEntryValidator(measureError);
}

/** The `transform.summarize` `group_by` list validator. */
export function groupByValidator(): ValidatorFn {
    return listEntryValidator(groupByError);
}
