/**
 * A Feature's `filters` and `unit` (ANOMALY-DETECTION-RESIDUALS-1 h): framework-free mapping between the stored
 * shape `AnomalyModel.fromMap` reads and the Feature editor's text fields. Both keys travel in a draft's `extra`
 * (`featureDrafts` / `toAnomalyModelContent` spread it under the modelled keys), so this only reads them out of and
 * writes them back into `extra`. A value the author did not change is written back as the stored value itself — a
 * number stays a number — so opening and saving a model never rewrites what it does not touch.
 */

/** `MeasureCompiler.Filter` ops, as the server spells them. A stored alias (`eq`, `gte`, …) is shown verbatim. */
export const FEATURE_FILTER_OPS: { value: string; label: string }[] = [
    { value: '=', label: 'equals (=)' },
    { value: '!=', label: 'does not equal (!=)' },
    { value: '>', label: 'greater than (>)' },
    { value: '>=', label: 'at least (>=)' },
    { value: '<', label: 'less than (<)' },
    { value: '<=', label: 'at most (<=)' },
    { value: 'in', label: 'is one of (comma-separated)' },
    { value: 'like', label: 'matches a LIKE pattern' },
    { value: 'isNull', label: 'is empty' },
    { value: 'notNull', label: 'is not empty' },
];

/** Ops that take no value (`MeasureCompiler.filterTerm`). */
export const NO_VALUE_OPS = ['isNull', 'notNull'];

/** One `{field, op, value}` filter as the editor holds it; `raw` is the stored map it came from (empty when new). */
export interface FeatureFilterDraft {
    field: string;
    op: string;
    value: string;
    raw: Record<string, unknown>;
}

const text = (v: unknown): string => (v == null ? '' : Array.isArray(v) ? v.map(String).join(', ') : String(v));

export function emptyFilter(): FeatureFilterDraft {
    return { field: '', op: '=', value: '', raw: {} };
}

/** The filters stored in a draft's `extra`. */
export function filterDrafts(extra: Record<string, unknown>): FeatureFilterDraft[] {
    const raw = Array.isArray(extra['filters']) ? (extra['filters'] as unknown[]) : [];
    return raw
        .filter((f): f is Record<string, unknown> => !!f && typeof f === 'object' && !Array.isArray(f))
        .map((f) => ({ field: text(f['field']), op: text(f['op']) || '=', value: text(f['value']), raw: { ...f } }));
}

/** The stored unit as text; blank = no key (the server's default floor of 1). */
export function unitText(extra: Record<string, unknown>): string {
    return text(extra['unit']);
}

/** One filter for storage: no `value` for isNull/notNull, a list for `in`, the stored value when unchanged. */
export function toFilter(d: FeatureFilterDraft): Record<string, unknown> {
    const out: Record<string, unknown> = { ...d.raw, field: d.field.trim(), op: d.op };
    if (NO_VALUE_OPS.includes(d.op)) {
        delete out['value'];
        return out;
    }
    const unchanged = 'value' in d.raw && d.value === text(d.raw['value']) && d.op === text(d.raw['op']);
    if (unchanged) out['value'] = d.raw['value'];
    else if (d.op === 'in')
        out['value'] = d.value
            .split(',')
            .map((s) => s.trim())
            .filter(Boolean);
    else out['value'] = d.value;
    return out;
}

/** `extra` with the edited filters and unit written back: an empty list and a blank unit leave no key. */
export function withFeatureFields(
    extra: Record<string, unknown>,
    filters: FeatureFilterDraft[],
    unit: string,
): Record<string, unknown> {
    const out: Record<string, unknown> = { ...extra };
    if (filters.length) out['filters'] = filters.map(toFilter);
    else delete out['filters'];
    const u = unit.trim();
    if (!u) delete out['unit'];
    else if (u !== unitText(extra)) out['unit'] = Number(u);
    return out;
}
