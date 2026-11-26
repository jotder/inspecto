/**
 * Authoring model of an `insight-profile` component (BI-INSIGHTS-1 P3, plan §7 and §11.9). Framework-free: the
 * Studio editor renders {@link insightProfileAttributes} and hands the values back through
 * {@link toInsightProfileContent}. Only cheap shape checks live here; `InsightProfileValidator` on the server is the
 * gate, and its 422 words are shown as they come.
 */
import { AttributeSpec } from 'app/inspecto/component-model/attribute-spec';

/**
 * The Insight Types this UI knows by name, offered as suggestions only. The server validates `types` and lists the
 * types it runs, so a type it adds later (concentration, distribution-shift) can be typed in before this list learns it.
 */
export const KNOWN_INSIGHT_TYPES: { value: string; label: string }[] = [
    { value: 'anomaly', label: 'Anomaly — one period far from its usual value' },
    { value: 'trend-break', label: 'Trend break — the level changed and stayed' },
    { value: 'drift', label: 'Drift — a steady rise or fall' },
    { value: 'concentration', label: 'Concentration — a few values hold most of the Measure' },
    { value: 'distribution-shift', label: 'Distribution shift — the mix across values changed' },
];

/** The default `types` of a new Profile: the three every server with P3 runs. */
export const DEFAULT_INSIGHT_TYPES = ['anomaly', 'trend-break', 'drift'];

/** One business calendar entry: a period that is excluded from every series, or where a finding is expected. */
export interface CalendarEntry {
    from: string;
    to: string;
    label: string;
    mode: 'exclude' | 'expected';
}

export function insightProfileAttributes(isEdit: boolean): AttributeSpec[] {
    return [
        ...(isEdit
            ? []
            : [
                  {
                      key: 'id',
                      label: 'Id',
                      type: 'string',
                      tier: 'required',
                      pattern: '[A-Za-z0-9_][A-Za-z0-9_-]{0,63}',
                      help: 'Letters, digits, _ and -. Names the Insights Dataset insights_<id>.',
                  } as AttributeSpec,
              ]),
        { key: 'dataset', label: 'Dataset', type: 'autocomplete', tier: 'required', help: 'The Dataset to scan.' },
        {
            key: 'measureAgg',
            label: 'Measure',
            type: 'select',
            tier: 'required',
            default: 'sum',
            options: [
                { value: 'sum', label: 'Sum of a column' },
                { value: 'count', label: 'Count of rows' },
            ],
            help: 'Only an additive Measure: a slice’s impact is its share of the whole.',
        },
        {
            key: 'measureField',
            label: 'Measure column',
            type: 'autocomplete',
            tier: 'required',
            dependsOn: { key: 'measureAgg', equals: 'sum' },
        },
        { key: 'field', label: 'Date column', type: 'autocomplete', tier: 'required' },
        {
            key: 'grain',
            label: 'Grain',
            type: 'select',
            tier: 'required',
            default: 'week',
            options: [
                { value: 'day', label: 'Day' },
                { value: 'week', label: 'Week' },
                { value: 'month', label: 'Month' },
            ],
        },
        {
            key: 'dimensions',
            label: 'Dimensions',
            type: 'list',
            tier: 'required',
            required: false,
            help: 'Up to 12 columns to slice by. The whole Measure is always scanned.',
        },
        {
            key: 'types',
            label: 'Insight Types',
            type: 'list',
            tier: 'required',
            help: 'anomaly, trend-break, drift, or any other type the server runs.',
        },
        {
            key: 'sensitivity',
            label: 'Sensitivity',
            type: 'select',
            tier: 'required',
            default: 'normal',
            options: [
                { value: 'low', label: 'Low — fewer Insights (q ≤ 0.01)' },
                { value: 'normal', label: 'Normal (q ≤ 0.05)' },
                { value: 'high', label: 'High — more Insights (q ≤ 0.10)' },
            ],
        },
        num('minImpact', 'Minimum impact', 0, 1, 'Share of the period’s Measure a finding must move. Default 0.01.'),
        num('minSupport', 'Minimum support', 0, 1, 'Share of the window a slice must hold. Default 0.01.'),
        num('maxValues', 'Values per dimension', 1, 200, 'The largest values scanned per dimension. Default 50.'),
        num(
            'lookback',
            'Lookback (periods)',
            12,
            120,
            'History each scan reads. Default 56 days, 26 weeks or 24 months.',
        ),
        {
            key: 'season',
            label: 'Season',
            type: 'string',
            tier: 'advanced',
            required: false,
            pattern: 'auto|none|[0-9]+',
            placeholder: 'auto',
            help: 'auto, none, or a number of periods (2-366). auto = 7 days, 52 weeks or 12 months.',
        },
        num('noveltyRuns', 'Novelty runs', 0, 52, 'Runs a repeated Insight counts as less new. Default 4.'),
        num('maxInsights', 'Insights per run', 1, 200, 'The most Insights one run keeps. Default 50.'),
        { key: 'description', label: 'Description', type: 'multiline', tier: 'optional', required: false },
    ];
}

function num(key: string, label: string, min: number, max: number, help: string): AttributeSpec {
    return { key, label, type: 'number', tier: 'advanced', required: false, min, max, help };
}

/** The schema-form's initial values from stored content (the nested `measure` is flattened). */
export function insightProfileInitial(c: Record<string, unknown>): Record<string, unknown> {
    const m = (c['measure'] ?? {}) as Record<string, unknown>;
    const out: Record<string, unknown> = { ...c, measureAgg: m['agg'] ?? 'sum', measureField: m['field'] ?? null };
    delete out['measure'];
    delete out['calendar'];
    if (out['season'] != null) out['season'] = String(out['season']);
    if (!Array.isArray(out['types'])) out['types'] = [...DEFAULT_INSIGHT_TYPES];
    return out;
}

/** The calendar entries of stored content, `to` defaulting to `from`. */
export function calendarEntries(c: Record<string, unknown>): CalendarEntry[] {
    const list = Array.isArray(c['calendar']) ? (c['calendar'] as Record<string, unknown>[]) : [];
    return list.map((e) => ({
        from: String(e['from'] ?? ''),
        to: String(e['to'] ?? e['from'] ?? ''),
        label: String(e['label'] ?? ''),
        mode: e['mode'] === 'exclude' ? 'exclude' : 'expected',
    }));
}

/**
 * The `insight-profile` content to save or replay. Keys the form does not author (`filters`, `when`) are carried over
 * from `stored`; a blank optional field is left out so the server's default applies.
 */
export function toInsightProfileContent(
    id: string,
    v: Record<string, unknown>,
    calendar: CalendarEntry[],
    stored: Record<string, unknown> = {},
): Record<string, unknown> {
    const out: Record<string, unknown> = {};
    for (const k of ['filters', 'when']) if (stored[k] != null) out[k] = stored[k];
    out['id'] = id;
    out['dataset'] = str(v['dataset']);
    const agg = str(v['measureAgg']) ?? 'sum';
    const field = agg === 'count' ? null : str(v['measureField']);
    out['measure'] = field ? { agg, field } : { agg };
    out['field'] = str(v['field']);
    out['grain'] = str(v['grain']);
    out['dimensions'] = list(v['dimensions']);
    out['types'] = list(v['types']);
    out['sensitivity'] = str(v['sensitivity']) ?? 'normal';
    for (const k of ['minImpact', 'minSupport', 'maxValues', 'lookback', 'noveltyRuns', 'maxInsights']) {
        const n = v[k];
        if (n !== null && n !== undefined && n !== '' && !Number.isNaN(Number(n))) out[k] = Number(n);
    }
    const season = str(v['season']);
    if (season) out['season'] = /^[0-9]+$/.test(season) ? Number(season) : season;
    const desc = str(v['description']);
    if (desc) out['description'] = desc;
    const cal = calendar
        .filter((e) => e.from || e.label)
        .map((e) => ({ from: e.from, ...(e.to && e.to !== e.from ? { to: e.to } : {}), label: e.label, mode: e.mode }));
    if (cal.length) out['calendar'] = cal;
    return out;
}

function str(v: unknown): string | null {
    const s = v == null ? '' : String(v).trim();
    return s ? s : null;
}

function list(v: unknown): string[] {
    if (Array.isArray(v)) return v.map((x) => String(x).trim()).filter((x) => x);
    const s = str(v);
    return s
        ? s
              .split(',')
              .map((x) => x.trim())
              .filter((x) => x)
        : [];
}

/** The words of a band, for the status badge: the tone is the badge's, the label is ours. */
export function bandBadge(band: string | null | undefined): { tone: string; label: string } {
    switch (band) {
        case 'high':
            return { tone: 'error', label: 'High' };
        case 'elevated':
            return { tone: 'warning', label: 'Elevated' };
        default:
            return { tone: 'info', label: 'Normal' };
    }
}

/** "region = North" or "the whole Measure". */
export function sliceLabel(i: { dimension?: string | null; slice_value?: string | null }): string {
    return !i.dimension || i.dimension === '*' ? 'Whole Measure' : `${i.dimension} = ${i.slice_value ?? '(empty)'}`;
}
