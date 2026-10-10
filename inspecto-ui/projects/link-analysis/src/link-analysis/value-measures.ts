import type { ColDef } from 'ag-grid-community';
import type { AttributeSpec } from '@inspecto/core/component-model';
import type { ValueMeasureName, ValueMeasureQuery } from '@inspecto/link-analysis/api/inv.service';

/**
 * LA-18 value Measures (plan §2.6.1) — the SPA side of `GET /inv/value-measures`. Framework-free: the form's
 * spec set, the query it sends and the result columns. The server (`ValueMeasures.java`) is the only judge of a
 * block; this file mirrors only WHICH settings each Measure has, never their defaults — a blank threshold is
 * sent as nothing and the server fills its own default, which the answer's `measure` block then states.
 */
export const VALUE_MEASURES: { value: ValueMeasureName; label: string; alertable: boolean }[] = [
    { value: 'passThrough', label: 'Pass-through (retention = 1 − ratio)', alertable: true },
    { value: 'velocity', label: 'Velocity (hours from inbound to next outbound)', alertable: true },
    { value: 'timeToCashOut', label: 'Time to cash-out', alertable: true },
    { value: 'cashOutConcentration', label: 'Cash-out concentration', alertable: true },
    { value: 'structuring', label: 'Structuring (legs just under a limit)', alertable: true },
    { value: 'benefitTransfer', label: 'Benefit transfer', alertable: true },
    { value: 'valueWeightedLinks', label: 'Value-weighted links (read-only, cannot be watched)', alertable: false },
];

/** Each Measure's threshold keys — `ValueMeasures.DEFAULTS`' key sets. */
export const VALUE_MEASURE_THRESHOLDS: Record<ValueMeasureName, string[]> = {
    passThrough: ['minInbound', 'minRatio'],
    velocity: ['minInbound', 'maxHours'],
    timeToCashOut: ['minInbound', 'maxHours'],
    cashOutConcentration: ['minShare', 'minPayers'],
    structuring: ['min', 'max', 'minLegs', 'minPayers'],
    benefitTransfer: ['maxHours', 'minShare', 'minRecipients'],
    valueWeightedLinks: [],
};

/** The Measures that read a link-kind list, and its key (`ValueMeasures.KIND_LIST`). */
export const VALUE_MEASURE_KIND_KEY: Partial<Record<ValueMeasureName, 'cashOutKinds' | 'benefitKinds'>> = {
    timeToCashOut: 'cashOutKinds',
    cashOutConcentration: 'cashOutKinds',
    benefitTransfer: 'benefitKinds',
};

const THRESHOLD_LABELS: Record<string, string> = {
    minInbound: 'Minimum inbound value',
    minRatio: 'Minimum out ÷ in ratio',
    maxHours: 'Maximum hours',
    minShare: 'Minimum share (0–1)',
    minPayers: 'Minimum payers',
    min: 'Leg value from (inclusive)',
    max: 'Leg value below',
    minLegs: 'Minimum legs',
    minRecipients: 'Minimum recipients',
};

const using = (key: string): ValueMeasureName[] =>
    (Object.keys(VALUE_MEASURE_THRESHOLDS) as ValueMeasureName[]).filter((m) =>
        VALUE_MEASURE_THRESHOLDS[m].includes(key),
    );

/**
 * The schema-rendered part of the form — Dataset, link roles, Measure, columns, the rolling window, then the chosen
 * Measure's own settings. The fixed From / To window is two date pickers the host renders itself (DR-D4).
 */
export function valueMeasureAttributes(): AttributeSpec[] {
    const col = (key: string, label: string, required = true, help?: string): AttributeSpec => ({
        key,
        label,
        type: 'autocomplete',
        tier: 'required',
        required,
        pattern: '[A-Za-z_][A-Za-z0-9_]*',
        help,
    });
    return [
        { key: 'dataset', label: 'Dataset', type: 'autocomplete', tier: 'required' },
        col('sourceCol', 'Source column (payer)'),
        col('targetCol', 'Target column (payee)'),
        col('linkKindCol', 'Link kind column', false, 'Needed by the cash-out and benefit Measures.'),
        {
            key: 'name',
            label: 'Measure',
            type: 'select',
            tier: 'required',
            options: VALUE_MEASURES.map(({ value, label }) => ({ value, label })),
        },
        col('valueCol', 'Value column'),
        col('timeCol', 'Time column'),
        {
            key: 'last',
            label: 'Or the last (rolling window)',
            type: 'string',
            tier: 'required',
            required: false,
            pattern: LAST,
            placeholder: '24h or 7d',
            help: 'Hours or days back from now, in UTC, re-read at every evaluation — never together with From/To. At most 31 days (744h).',
        },
        ...Object.keys(THRESHOLD_LABELS).map(
            (key): AttributeSpec => ({
                key,
                label: THRESHOLD_LABELS[key],
                type: 'number',
                tier: 'required',
                required: false,
                min: 0,
                placeholder: 'server default',
                dependsOn: { key: 'name', in: using(key) },
            }),
        ),
        {
            key: 'cashOutKinds',
            label: 'Cash-out link kinds',
            type: 'list',
            tier: 'required',
            dependsOn: { key: 'name', in: ['timeToCashOut', 'cashOutConcentration'] },
        },
        {
            key: 'benefitKinds',
            label: 'Benefit link kinds',
            type: 'list',
            tier: 'required',
            dependsOn: { key: 'name', in: ['benefitTransfer'] },
        },
        {
            key: 'agentList',
            label: 'Agent list (optional)',
            type: 'autocomplete',
            tier: 'required',
            required: false,
            pattern: AGENT_LIST,
            help: 'An Entity List of Entity Type agent: only its members count as cash-out agents.',
            dependsOn: { key: 'name', in: ['cashOutConcentration'] },
        },
    ];
}

/** `ValueMeasures.LAST`: 1–999 999 hours or days. */
const LAST = '[1-9]\\d{0,5}[hd]';
/** `EntityListFacts.LIST_ID`: the id an Entity List is minted with. */
const AGENT_LIST = '[a-z0-9][a-z0-9_-]{0,63}';

/** `ValueMeasures.MAX_WINDOW_DAYS`: a window the server reads is at most this long (`from`/`to`, or a rolling `last`). */
export const MAX_WINDOW_DAYS = 31;

const DAY_MS = 24 * 3_600_000;

/** A rolling `last` ('24h' | '7d') as days, or null when it is not one. */
function lastDays(last: string): number | null {
    const m = /^([1-9]\d{0,5})([hd])$/.exec(last.trim());
    return m ? (m[2] === 'h' ? Number(m[1]) / 24 : Number(m[1])) : null;
}

/** The server's "exactly one window" rule (and its {@link MAX_WINDOW_DAYS} cap), stated before the call: `from` + `to`, or `last`, never both. */
export function valueMeasureWindowIssue(v: Record<string, unknown>): string | null {
    const set = (k: string) => typeof v[k] === 'string' && (v[k] as string).trim() !== '';
    const fixed = set('from') || set('to');
    if (fixed && set('last')) return 'Use either From and To or a rolling window (last), not both.';
    if (!fixed && !set('last')) return 'Give a window: From and To, or a rolling window such as 7d.';
    if (fixed && !(set('from') && set('to'))) return 'A fixed window needs both From and To.';
    if (fixed) {
        const days = (Date.parse(String(v['to'])) - Date.parse(String(v['from']))) / DAY_MS;
        if (days <= 0) return 'To must be after From.';
        if (days > MAX_WINDOW_DAYS)
            return `The window is at most ${MAX_WINDOW_DAYS} days; this one is ${Math.ceil(days)}.`;
    } else {
        const days = lastDays(String(v['last']));
        if (days !== null && days > MAX_WINDOW_DAYS) return `The window is at most ${MAX_WINDOW_DAYS} days (744h).`;
    }
    return null;
}

/**
 * The query `GET /inv/value-measures` receives: only the keys the chosen Measure has, blanks dropped (a blank
 * threshold = the server's default). ⛔ Never a `filter` — whatever view the analyst has, the Measure reads the
 * whole Dataset (the §2.6 ≥ 5 000 trap), and the server refuses the key.
 */
export function valueMeasureQuery(v: Record<string, unknown>): ValueMeasureQuery {
    const name = v['name'] as ValueMeasureName;
    const kindKey = VALUE_MEASURE_KIND_KEY[name];
    const keys = [
        'dataset',
        'sourceCol',
        'targetCol',
        'linkKindCol',
        'name',
        'valueCol',
        'timeCol',
        'from',
        'to',
        'last',
        ...(VALUE_MEASURE_THRESHOLDS[name] ?? []),
        ...(kindKey ? [kindKey] : []),
        ...(name === 'cashOutConcentration' ? ['agentList'] : []),
    ];
    const out: Record<string, unknown> = {};
    for (const k of keys) {
        const x = v[k];
        if (x === null || x === undefined) continue;
        if (typeof x === 'string' && x.trim() === '') continue;
        if (Array.isArray(x) && x.length === 0) continue;
        out[k] = typeof x === 'string' ? x.trim() : x;
    }
    return out as unknown as ValueMeasureQuery;
}

/** Result columns in the server's order; `retention` is labelled as what it is — derived, 1 − ratio. */
export function valueMeasureColumns(rows: Record<string, unknown>[]): ColDef[] {
    const first = rows[0];
    if (!first) return [];
    return Object.keys(first).map((field) => ({
        field,
        headerName: field === 'retention' ? 'Retention (derived: 1 − ratio)' : field,
    }));
}
