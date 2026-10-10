/**
 * Authoring model of an `anomaly-model` component (ANOMALY-DETECTION-1 slice S5, design §6 / §13.1). Framework-free:
 * the pane's form components render what this returns and hand their values back through
 * {@link toAnomalyModelContent}. Only cheap required/number checks live here — `AnomalyModel.fromMap` +
 * `requireStorable` 422s are the gate, and {@link mapAnomalyRefusal} places their message on the field it names.
 */
import { AttributeSpec } from 'app/inspecto/component-model/attribute-spec';

/** Entity types offered as suggestions; a free plain token is also accepted (the server checks the shape). */
export const ANOMALY_ENTITY_TYPES = ['subscriber', 'account', 'device', 'sim', 'merchant', 'dealer', 'cell'];
/** `AnomalyModel.MAX_FEATURES`. */
export const MAX_FEATURES = 16;
/** `AnomalyModel.Direction`, lower-cased as authored. */
export const ANOMALY_DIRECTIONS: { value: string; label: string }[] = [
    { value: 'up', label: 'Up (higher than usual is unusual)' },
    { value: 'down', label: 'Down (lower than usual is unusual)' },
    { value: 'both', label: 'Both directions' },
];

/** The flat top-level fields, one schema-form. Nested `peers` / `watchList` are flattened here. */
export function anomalyModelAttributes(isEdit: boolean): AttributeSpec[] {
    return [
        ...(isEdit
            ? []
            : [
                  {
                      key: 'id',
                      label: 'Id',
                      type: 'string',
                      tier: 'required',
                      pattern: '[A-Za-z0-9][A-Za-z0-9_]*',
                      help: 'Letters, digits and _. Names the derived Datasets anomaly_scores_<id> and _latest.',
                  } as AttributeSpec,
              ]),
        {
            key: 'entityType',
            label: 'Entity type',
            type: 'autocomplete',
            tier: 'required',
            help: 'What is scored: one of the named types, or a free-form name.',
        },
        {
            key: 'window',
            label: 'Baseline window (days)',
            type: 'number',
            tier: 'required',
            min: 1,
            max: 90,
            default: 28,
            help: 'Days of history each entity is compared with (up to 90). The scored day is never in its own baseline.',
        },
        {
            key: 'seasonality',
            label: 'Seasonality',
            type: 'select',
            tier: 'required',
            default: 'none',
            options: [
                { value: 'none', label: 'None — every day alike' },
                { value: 'weekday', label: 'Weekday — compare a Monday with Mondays' },
            ],
        },
        {
            key: 'elevatedThreshold',
            label: 'Elevated threshold',
            type: 'number',
            tier: 'required',
            min: 0.000001,
            max: 100,
            default: 60,
            help: 'A score at or above this is Elevated.',
        },
        {
            key: 'highThreshold',
            label: 'High threshold',
            type: 'number',
            tier: 'required',
            min: 0.000001,
            max: 100,
            default: 80,
            help: 'A score at or above this (above Elevated, at most 100) is High.',
        },
        { key: 'description', label: 'Description', type: 'multiline', tier: 'optional' },
        {
            key: 'minBaselinePoints',
            label: 'Minimum baseline points',
            type: 'number',
            tier: 'optional',
            required: false,
            min: 1,
            help: 'Fewer days of history than this and the entity is compared with its peers only.',
        },
        {
            key: 'maxEntities',
            label: 'Entity cap',
            type: 'number',
            tier: 'optional',
            required: false,
            min: 1,
            max: 2000000,
            help: 'More entities than this and the run fails — it never scores a subset.',
        },
        { key: 'dataScope', label: 'Data scope', type: 'string', tier: 'optional', pattern: '[A-Za-z0-9_.:-]+' },
        {
            key: 'peersBy',
            label: 'Peer Group columns',
            type: 'list',
            tier: 'optional',
            required: false,
            group: 'Peer Group',
            help: 'Up to 3 columns (for example tariff_plan) that put an entity in a cohort.',
        },
        {
            key: 'peersDataset',
            label: 'Peer Group Dataset',
            type: 'string',
            tier: 'optional',
            group: 'Peer Group',
            help: 'Blank = the first Feature’s Dataset.',
        },
        {
            key: 'peersKey',
            label: 'Peer Group key column',
            type: 'string',
            tier: 'optional',
            group: 'Peer Group',
            help: 'Blank = the first Feature’s key.',
        },
        {
            key: 'peersMinGroupSize',
            label: 'Minimum cohort size',
            type: 'number',
            tier: 'optional',
            required: false,
            min: 2,
            group: 'Peer Group',
            help: 'Default 30. A smaller cohort gives no peer comparison.',
        },
        {
            key: 'peersFallback',
            label: 'Small cohort',
            type: 'select',
            tier: 'optional',
            required: false,
            default: '',
            group: 'Peer Group',
            options: [
                { value: '', label: 'No peer comparison' },
                { value: 'population', label: 'Compare with the whole population (flagged)' },
            ],
        },
        { key: 'watchList', label: 'Watch Entity List', type: 'string', tier: 'optional', group: 'Entity Lists' },
        {
            key: 'watchTtlHours',
            label: 'Watch entry lifetime (hours)',
            type: 'number',
            tier: 'optional',
            required: false,
            min: 1,
            max: 24,
            group: 'Entity Lists',
        },
        {
            key: 'exclusionList',
            label: 'Exclusion Entity List',
            type: 'string',
            tier: 'optional',
            group: 'Entity Lists',
            help: 'Members (known heavy users, test SIMs) are left out before scoring, counted as excluded.',
        },
    ];
}

/** One Feature row as the editor holds it; `extra` carries keys the editor does not model (filters, unit). */
export interface AnomalyFeatureDraft {
    id: string;
    label: string;
    dataset: string;
    key: string;
    time: string;
    measure: string;
    direction: string;
    weight: string;
    extra: Record<string, unknown>;
}

export function emptyFeature(n: number): AnomalyFeatureDraft {
    return {
        id: `feature_${n}`,
        label: '',
        dataset: '',
        key: '',
        time: '',
        measure: '',
        direction: 'up',
        weight: '1',
        extra: {},
    };
}

const str = (v: unknown): string => (v == null ? '' : String(v));

/** The schema-form seed of a stored model (edit), with `peers` / `watchList` flattened. */
export function anomalyModelInitial(content: Record<string, unknown>): Record<string, unknown> {
    const peers = (content['peers'] ?? null) as Record<string, unknown> | null;
    const watch = (content['watchList'] ?? null) as Record<string, unknown> | null;
    return {
        entityType: str(content['entityType']),
        window: content['window'] ?? null,
        seasonality: str(content['seasonality']) || 'none',
        elevatedThreshold: content['elevatedThreshold'] ?? null,
        highThreshold: content['highThreshold'] ?? null,
        description: str(content['description']),
        minBaselinePoints: content['minBaselinePoints'] ?? null,
        maxEntities: content['maxEntities'] ?? null,
        dataScope: str(content['dataScope']),
        peersBy: peers && Array.isArray(peers['by']) ? (peers['by'] as unknown[]).map(str) : null,
        peersDataset: peers ? str(peers['dataset']) : '',
        peersKey: peers ? str(peers['key']) : '',
        peersMinGroupSize: peers ? (peers['minGroupSize'] ?? null) : null,
        peersFallback: peers && peers['fallback'] === 'population' ? 'population' : '',
        watchList: watch ? str(watch['list']) : '',
        watchTtlHours: watch ? (watch['ttlHours'] ?? null) : null,
        exclusionList: str(content['exclusionList']),
    };
}

const FEATURE_MODELLED = ['id', 'label', 'dataset', 'key', 'time', 'measure', 'direction', 'weight'];

export function featureDrafts(content: Record<string, unknown>): AnomalyFeatureDraft[] {
    const raw = Array.isArray(content['features']) ? (content['features'] as unknown[]) : [];
    return raw
        .filter((f): f is Record<string, unknown> => !!f && typeof f === 'object')
        .map((f) => {
            const extra: Record<string, unknown> = {};
            for (const [k, v] of Object.entries(f)) if (!FEATURE_MODELLED.includes(k)) extra[k] = v;
            return {
                id: str(f['id']),
                label: str(f['label']),
                dataset: str(f['dataset']),
                key: str(f['key']),
                time: str(f['time']),
                measure: str(f['measure']),
                direction: str(f['direction']) || 'up',
                weight: f['weight'] == null ? '1' : str(f['weight']),
                extra,
            };
        });
}

const numeric = (v: string): number | string => {
    const t = v.trim();
    return t !== '' && !Number.isNaN(Number(t)) ? Number(t) : t;
};

/** Top-level keys the form does not model; carried from the stored model untouched. */
const TOP_PASSTHROUGH = ['name', 'owner', 'shares', 'bucket', 'scoredPeriod', 'zCap', 'scale'];

/** Build the stored content from the form: blank optionals are left out (no key), never written as null. */
export function toAnomalyModelContent(
    id: string,
    top: Record<string, unknown>,
    features: AnomalyFeatureDraft[],
    stored: Record<string, unknown> = {},
): Record<string, unknown> {
    const out: Record<string, unknown> = {};
    for (const k of TOP_PASSTHROUGH) if (k in stored) out[k] = stored[k];
    out['id'] = id;
    out['entityType'] = str(top['entityType']).trim();
    out['window'] = top['window'];
    out['seasonality'] = str(top['seasonality']) || 'none';
    out['elevatedThreshold'] = top['elevatedThreshold'];
    out['highThreshold'] = top['highThreshold'];
    const desc = str(top['description']).trim();
    if (desc) out['description'] = desc;
    for (const k of ['minBaselinePoints', 'maxEntities']) if (top[k] != null && top[k] !== '') out[k] = top[k];
    const scope = str(top['dataScope']).trim();
    if (scope) out['dataScope'] = scope;
    const by = Array.isArray(top['peersBy']) ? (top['peersBy'] as unknown[]).map(str).filter(Boolean) : [];
    if (by.length) {
        const peers: Record<string, unknown> = { by };
        const ds = str(top['peersDataset']).trim();
        if (ds) peers['dataset'] = ds;
        const key = str(top['peersKey']).trim();
        if (key) peers['key'] = key;
        if (top['peersMinGroupSize'] != null && top['peersMinGroupSize'] !== '')
            peers['minGroupSize'] = top['peersMinGroupSize'];
        if (top['peersFallback'] === 'population') peers['fallback'] = 'population';
        out['peers'] = peers;
    }
    const list = str(top['watchList']).trim();
    if (list) {
        const w: Record<string, unknown> = { list };
        if (top['watchTtlHours'] != null && top['watchTtlHours'] !== '') w['ttlHours'] = top['watchTtlHours'];
        out['watchList'] = w;
    }
    const ex = str(top['exclusionList']).trim();
    if (ex) out['exclusionList'] = ex;
    out['features'] = features.map((f) => {
        const row: Record<string, unknown> = {
            ...f.extra,
            id: f.id.trim(),
            dataset: f.dataset.trim(),
            key: f.key.trim(),
            time: f.time.trim(),
            measure: f.measure.trim(),
            direction: f.direction || 'up',
            weight: numeric(f.weight),
        };
        if (f.label.trim()) row['label'] = f.label.trim();
        return row;
    });
    return out;
}

/** Where a server refusal lands: a top-level field, one Feature row (optionally its field), or the banner only. */
export interface AnomalyRefusalTarget {
    field?: string;
    feature?: number;
    featureField?: string;
}

const TOP_FIELD: Record<string, string> = {
    entityType: 'entityType',
    window: 'window',
    seasonality: 'seasonality',
    elevatedThreshold: 'elevatedThreshold',
    highThreshold: 'highThreshold',
    minBaselinePoints: 'minBaselinePoints',
    maxEntities: 'maxEntities',
    dataScope: 'dataScope',
    description: 'description',
    peers: 'peersBy',
    watchList: 'watchList',
    exclusionList: 'exclusionList',
};

/**
 * Map an `AnomalyModel` / `requireStorable` 422 message onto the field it names — the messages are path-led
 * (`anomaly-model.features[1].measure …`, `anomaly-model.window must be …`). The message is shown verbatim; this
 * only decides where. `features[i]` is the server's 0-based index, the editor's row.
 */
export function mapAnomalyRefusal(message: string): AnomalyRefusalTarget {
    const f = /anomaly-model\.features\[(\d+)\](?:\.(\w+))?/.exec(message);
    if (f) return { feature: Number(f[1]), ...(f[2] ? { featureField: f[2] } : {}) };
    if (/anomaly-model thresholds/.test(message)) return { field: 'highThreshold' };
    const t = /anomaly-model\.(\w+)/.exec(message);
    if (t && TOP_FIELD[t[1]]) return { field: TOP_FIELD[t[1]] };
    if (/^anomaly-model id /.test(message)) return { field: 'id' };
    return {};
}

/** The per-entity Alert Rule prefill over `anomaly_scores_<id>_latest` (design §9): a link, never a second write. */
export function anomalyAlertRuleParams(id: string, highThreshold: number | null): Record<string, string> {
    return {
        newRule: '1',
        dataset: `anomaly_scores_${id}_latest`,
        measure: 'max(score)',
        by: 'model,entity_key',
        comparator: 'gte',
        ...(highThreshold != null ? { threshold: String(highThreshold) } : {}),
    };
}

/** A read-only summary of a stored model for the list + detail. */
export interface AnomalyModelView {
    id: string;
    entityType: string;
    description: string;
    window: number | null;
    seasonality: string;
    elevatedThreshold: number | null;
    highThreshold: number | null;
    dataScope: string;
    peersBy: string[];
    watchList: string;
    exclusionList: string;
    features: AnomalyFeatureDraft[];
    outputs: string[];
}

const num = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null);

export function anomalyModelView(id: string, content: Record<string, unknown>): AnomalyModelView {
    const i = anomalyModelInitial(content);
    return {
        id,
        entityType: str(i['entityType']),
        description: str(i['description']),
        window: num(content['window']),
        seasonality: str(i['seasonality']),
        elevatedThreshold: num(content['elevatedThreshold']),
        highThreshold: num(content['highThreshold']),
        dataScope: str(i['dataScope']),
        peersBy: (i['peersBy'] as string[] | null) ?? [],
        watchList: str(i['watchList']),
        exclusionList: str(i['exclusionList']),
        features: featureDrafts(content),
        outputs: [`anomaly_scores_${id}`, `anomaly_scores_${id}_latest`],
    };
}

/**
 * Geometry of the preview's baseline strip (design §13.1 "a small chart of its baseline window with the observed
 * point"): the usual range median ± 3 × MAD (the `high` deviation), the median tick and the observed point, on one
 * axis scaled to include all of them. Positions are 0..1. `null` when the feature has no usable baseline.
 */
export interface BaselineStrip {
    lo: number;
    hi: number;
    median: number;
    observed: number | null;
    min: number;
    max: number;
}

export function baselineStrip(
    median: number | null,
    mad: number | null,
    observed: number | null,
): BaselineStrip | null {
    if (median == null || !Number.isFinite(median)) return null;
    const spread = mad != null && Number.isFinite(mad) ? 3 * mad : 0;
    const lo = median - spread;
    const hi = median + spread;
    const vals = [lo, hi, median, ...(observed != null && Number.isFinite(observed) ? [observed] : [])];
    let min = Math.min(...vals);
    let max = Math.max(...vals);
    if (max === min) {
        min -= 1;
        max += 1;
    }
    const pad = (max - min) * 0.05;
    min -= pad;
    max += pad;
    const at = (v: number): number => (v - min) / (max - min);
    return {
        lo: at(lo),
        hi: at(hi),
        median: at(median),
        observed: observed != null && Number.isFinite(observed) ? at(observed) : null,
        min,
        max,
    };
}
