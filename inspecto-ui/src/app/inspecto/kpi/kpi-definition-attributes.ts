import { AttributeSpec } from 'app/inspecto/component-model';
import { KpiDefinition } from 'app/inspecto/api';

/**
 * The KPI definition editor's attributes (ASSURE-KPI-DEFINITIONS-1) — drives `<inspecto-schema-form>` in
 * the KPI editor (`kpi-reports/kpi-definition.dialog`) and, filtered, the create-KPI action on a Requirement
 * (`requirements/requirement-kpi.dialog`) — shared here so neither feature imports the other. The server
 * validates the same content fail closed at save (the Measure must exist, the bands must be ordered, the grain
 * must be known); this form only asks.
 */
export const KPI_DEFINITION_ATTRIBUTES: AttributeSpec[] = [
    { key: 'title', label: 'Title', type: 'string', tier: 'required', placeholder: 'e.g. Refund exposure' },
    { key: 'dataset', label: 'Dataset', type: 'autocomplete', tier: 'required' },
    {
        key: 'measure',
        label: 'Measure',
        type: 'string',
        tier: 'required',
        pattern: '(count|(count|countDistinct|sum|avg|min|max)\\([A-Za-z_][A-Za-z0-9_]*\\))',
        placeholder: 'sum(amount)',
        help: 'count, or agg(column) with agg one of count, countDistinct, sum, avg, min, max.',
    },
    {
        key: 'timeField',
        label: 'Date column',
        type: 'autocomplete',
        tier: 'required',
        help: 'The column a period is cut on.',
    },
    {
        key: 'grain',
        label: 'Period',
        type: 'select',
        tier: 'required',
        default: 'month',
        options: [
            { value: 'day', label: 'Day' },
            { value: 'week', label: 'Week (from Monday)' },
            { value: 'month', label: 'Month' },
            { value: 'quarter', label: 'Quarter' },
            { value: 'year', label: 'Year' },
        ],
    },
    {
        key: 'timezone',
        label: 'Time zone',
        type: 'string',
        tier: 'optional',
        required: false,
        placeholder: 'UTC',
        help: 'The IANA zone the periods are cut in, e.g. Asia/Kolkata. Blank: UTC.',
    },
    {
        key: 'comparison',
        label: 'Compare with',
        type: 'select',
        tier: 'required',
        default: 'previous',
        options: [
            { value: 'previous', label: 'The previous period' },
            { value: 'last-year', label: 'The same period last year' },
            { value: 'none', label: 'Nothing' },
        ],
    },
    {
        key: 'direction',
        label: 'Good direction',
        type: 'select',
        tier: 'required',
        default: 'up',
        options: [
            { value: 'up', label: 'Higher is better' },
            { value: 'down', label: 'Lower is better' },
            { value: 'band', label: 'Inside a band' },
        ],
    },
    { key: 'target', label: 'Target', type: 'number', tier: 'optional', required: false },
    {
        key: 'green',
        label: 'Green from',
        type: 'number',
        tier: 'optional',
        required: false,
        dependsOn: { key: 'direction', notEquals: 'band' },
        help: 'Higher is better: green at or above this. Lower is better: green at or below it.',
    },
    {
        key: 'amber',
        label: 'Amber from',
        type: 'number',
        tier: 'optional',
        required: false,
        dependsOn: { key: 'direction', notEquals: 'band' },
    },
    {
        key: 'greenLo',
        label: 'Green band: low',
        type: 'number',
        tier: 'required',
        dependsOn: { key: 'direction', equals: 'band' },
    },
    {
        key: 'greenHi',
        label: 'Green band: high',
        type: 'number',
        tier: 'required',
        dependsOn: { key: 'direction', equals: 'band' },
    },
    {
        key: 'amberLo',
        label: 'Amber band: low',
        type: 'number',
        tier: 'required',
        dependsOn: { key: 'direction', equals: 'band' },
    },
    {
        key: 'amberHi',
        label: 'Amber band: high',
        type: 'number',
        tier: 'required',
        dependsOn: { key: 'direction', equals: 'band' },
    },
    { key: 'unit', label: 'Unit', type: 'string', tier: 'optional', required: false, placeholder: 'SAR' },
];

const num = (v: unknown): number | undefined =>
    v === null || v === undefined || v === '' || !Number.isFinite(Number(v)) ? undefined : Number(v);

/** The form's flat values → the `kpi` component content. Bands are written only when both ends are given. */
export function toKpiContent(v: Record<string, unknown>): KpiDefinition {
    const direction = (v['direction'] as KpiDefinition['direction']) ?? 'up';
    const out: KpiDefinition = {
        title: String(v['title'] ?? '').trim() || undefined,
        dataset: String(v['dataset'] ?? '').trim(),
        measure: String(v['measure'] ?? '').trim(),
        timeField: String(v['timeField'] ?? '').trim(),
        grain: (v['grain'] as KpiDefinition['grain']) ?? 'month',
        comparison: (v['comparison'] as KpiDefinition['comparison']) ?? 'previous',
        direction,
    };
    const target = num(v['target']);
    if (target !== undefined) out.target = target;
    if (direction === 'band') {
        const [gl, gh, al, ah] = ['greenLo', 'greenHi', 'amberLo', 'amberHi'].map((k) => num(v[k]));
        if ([gl, gh, al, ah].every((x) => x !== undefined)) out.bands = { green: [gl!, gh!], amber: [al!, ah!] };
    } else {
        const green = num(v['green']);
        const amber = num(v['amber']);
        if (green !== undefined && amber !== undefined) out.bands = { green, amber };
    }
    const timezone = String(v['timezone'] ?? '').trim();
    if (timezone) out.timezone = timezone;
    const unit = String(v['unit'] ?? '').trim();
    if (unit) out.unit = unit;
    return out;
}

/** A component id from a title: `Refund exposure` → `refund_exposure` (the store's id alphabet). */
export function kpiIdFor(title: string): string {
    const id = title
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, '_')
        .replace(/^_+|_+$/g, '');
    return id || 'kpi';
}

/** The stored content → the form's flat values (the inverse of {@link toKpiContent}). */
export function fromKpiContent(c: KpiDefinition): Record<string, unknown> {
    const v: Record<string, unknown> = { ...c };
    delete v['bands'];
    const b = c.bands;
    if (b && Array.isArray(b.green) && Array.isArray(b.amber)) {
        [v['greenLo'], v['greenHi']] = b.green;
        [v['amberLo'], v['amberHi']] = b.amber;
    } else if (b) {
        v['green'] = b.green;
        v['amber'] = b.amber;
    }
    return v;
}
