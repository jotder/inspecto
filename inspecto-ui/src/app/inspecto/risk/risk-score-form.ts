/**
 * Authoring model of a `risk-score` component (ASSURE-RISK-SCORE-RESIDUALS-1 (2), slice S2; spec
 * `docs/superpower/risk-score-authoring-pane-spec.md` §3). Framework-free: the form components render what this
 * returns and hand their values back through {@link toRiskScoreContent}; no rule is duplicated here beyond cheap
 * required/number checks — the server's `RiskScoreModel.fromMap` + `requireStorable` 422s are the gate, and
 * {@link mapRiskRefusal} places their message on the field it names.
 */
import { AttributeSpec } from 'app/inspecto/component-model/attribute-spec';

/** The seven named entity types `RiskScoreModel.ENTITY_TYPES` documents; a free token is also accepted. */
export const RISK_ENTITY_TYPES = ['subscriber', 'account', 'device', 'sim', 'dealer', 'channel', 'partner'];

/** Operators `MeasureCompiler` compiles for a factor filter (`=`, `!=`, …, `in`), canonical spellings only. */
export const RISK_FILTER_OPS: { op: string; label: string; arity: 0 | 1 | 'list' }[] = [
    { op: '=', label: 'equals', arity: 1 },
    { op: '!=', label: 'not equal', arity: 1 },
    { op: '>', label: '>', arity: 1 },
    { op: '>=', label: '≥', arity: 1 },
    { op: '<', label: '<', arity: 1 },
    { op: '<=', label: '≤', arity: 1 },
    { op: 'like', label: 'like', arity: 1 },
    { op: 'in', label: 'in (comma list)', arity: 'list' },
    { op: 'isNull', label: 'is empty', arity: 0 },
    { op: 'notNull', label: 'is not empty', arity: 0 },
];

/** `RiskScoreModel` limits (`MAX_FACTORS`, `MAX_EVIDENCE`). */
export const MAX_FACTORS = 32;
export const MAX_EVIDENCE = 8;

/** The flat top-level fields, one schema-form (D-RP2 a). Nested `watchList`/retention are flattened here. */
export function riskScoreAttributes(isEdit: boolean): AttributeSpec[] {
    return [
        ...(isEdit
            ? []
            : [
                  {
                      key: 'id',
                      label: 'Id',
                      type: 'string',
                      tier: 'required',
                      pattern: '[A-Za-z0-9_]+',
                      help: 'Letters, digits and _. Names the derived Datasets risk_scores_<id> and _latest.',
                  } as AttributeSpec,
              ]),
        {
            key: 'entityType',
            label: 'Entity type',
            type: 'autocomplete',
            tier: 'required',
            help: 'One of the named types, or a free-form name.',
        },
        {
            key: 'highThreshold',
            label: 'High threshold',
            type: 'number',
            tier: 'required',
            min: 0.000001,
            max: 100,
            help: 'A score at or above this (0 < x ≤ 100) is High.',
        },
        { key: 'description', label: 'Description', type: 'multiline', tier: 'optional' },
        { key: 'dataScope', label: 'Data scope', type: 'string', tier: 'optional', pattern: '[A-Za-z0-9_.:-]+' },
        { key: 'watchList', label: 'Watch Entity List', type: 'string', tier: 'optional', group: 'Watch List' },
        {
            key: 'watchTtlHours',
            label: 'Watch entry lifetime (hours)',
            type: 'number',
            tier: 'optional',
            required: false,
            min: 1,
            max: 24,
            group: 'Watch List',
        },
        {
            key: 'retention',
            label: 'History retention',
            type: 'select',
            tier: 'optional',
            required: false,
            default: '',
            options: [
                { value: '', label: 'Keep forever' },
                { value: 'days', label: 'Keep N days' },
                { value: 'runs', label: 'Keep N runs' },
            ],
            group: 'Retention',
        },
        {
            key: 'retainValue',
            label: 'Keep',
            type: 'number',
            tier: 'optional',
            min: 1,
            max: 100000,
            dependsOn: { key: 'retention', in: ['days', 'runs'] },
            group: 'Retention',
        },
    ];
}

export interface RiskFilterDraft {
    field: string;
    op: string;
    value: string;
}

/** One factor row as the editor holds it — strings for the free inputs, so a half-typed value survives. */
export interface RiskFactorDraft {
    id: string;
    label: string;
    dataset: string;
    key: string;
    measure: string;
    weight: string;
    cap: string;
    filters: RiskFilterDraft[];
    evidence: string[];
}

const str = (v: unknown): string => (v == null ? '' : String(v));

export function emptyFactor(n: number): RiskFactorDraft {
    return {
        id: `factor_${n}`,
        label: '',
        dataset: '',
        key: '',
        measure: 'count',
        weight: '1',
        cap: '',
        filters: [],
        evidence: [],
    };
}

/** The schema-form seed of a stored model (edit), with `watchList`/retention flattened. */
export function riskScoreInitial(content: Record<string, unknown>): Record<string, unknown> {
    const watch = (content['watchList'] ?? null) as Record<string, unknown> | null;
    const days = content['retainDays'];
    const runs = content['retainRuns'];
    return {
        entityType: str(content['entityType']),
        highThreshold: content['highThreshold'] ?? null,
        description: str(content['description']),
        dataScope: str(content['dataScope']),
        watchList: watch ? str(watch['list']) : '',
        watchTtlHours: watch ? (watch['ttlHours'] ?? null) : null,
        retention: days != null ? 'days' : runs != null ? 'runs' : '',
        retainValue: days ?? runs ?? null,
    };
}

export function factorDrafts(content: Record<string, unknown>): RiskFactorDraft[] {
    const raw = Array.isArray(content['factors']) ? (content['factors'] as unknown[]) : [];
    return raw
        .filter((f): f is Record<string, unknown> => !!f && typeof f === 'object')
        .map((f) => ({
            id: str(f['id']),
            label: str(f['label']),
            dataset: str(f['dataset']),
            key: str(f['key']),
            measure: str(f['measure']),
            weight: str(f['weight']),
            cap: str(f['cap']),
            filters: (Array.isArray(f['filters']) ? (f['filters'] as Record<string, unknown>[]) : []).map((x) => ({
                field: str(x?.['field']),
                op: str(x?.['op']) || '=',
                value: Array.isArray(x?.['value']) ? (x['value'] as unknown[]).map(str).join(', ') : str(x?.['value']),
            })),
            evidence: Array.isArray(f['evidence']) ? (f['evidence'] as unknown[]).map(str) : [],
        }));
}

/** A typed number when the text is numeric, else the text itself (so the server names the bad value). */
const numeric = (s: string): number | string => (s.trim() !== '' && isFinite(Number(s)) ? Number(s) : s.trim());

/**
 * The component body to save. `stored` is the content being edited: keys this form does not author
 * (`name`, `owner`, `shares`) ride through untouched; the keys it does author are replaced or dropped.
 */
export function toRiskScoreContent(
    id: string,
    top: Record<string, unknown>,
    factors: RiskFactorDraft[],
    stored: Record<string, unknown> = {},
): Record<string, unknown> {
    const out: Record<string, unknown> = {};
    for (const k of ['name', 'owner', 'shares']) if (k in stored) out[k] = stored[k];
    out['id'] = id;
    out['entityType'] = str(top['entityType']).trim();
    out['highThreshold'] = top['highThreshold'];
    const desc = str(top['description']).trim();
    if (desc) out['description'] = desc;
    const scope = str(top['dataScope']).trim();
    if (scope) out['dataScope'] = scope;
    const list = str(top['watchList']).trim();
    if (list) out['watchList'] = { list, ttlHours: top['watchTtlHours'] ?? null };
    if (top['retention'] === 'days') out['retainDays'] = top['retainValue'];
    if (top['retention'] === 'runs') out['retainRuns'] = top['retainValue'];
    out['factors'] = factors.map((f) => {
        const row: Record<string, unknown> = {
            id: f.id.trim(),
            dataset: f.dataset.trim(),
            key: f.key.trim(),
            measure: f.measure.trim(),
            weight: numeric(f.weight),
        };
        if (f.label.trim()) row['label'] = f.label.trim();
        if (f.cap.trim()) row['cap'] = numeric(f.cap);
        if (f.filters.length)
            row['filters'] = f.filters.map((x) => {
                const def = RISK_FILTER_OPS.find((o) => o.op === x.op);
                const value =
                    def?.arity === 0
                        ? null
                        : def?.arity === 'list'
                          ? x.value
                                .split(',')
                                .map((s) => s.trim())
                                .filter(Boolean)
                          : ['>', '>=', '<', '<='].includes(x.op)
                            ? numeric(x.value)
                            : x.value;
                return { field: x.field.trim(), op: x.op, value };
            });
        if (f.evidence.length) row['evidence'] = [...f.evidence];
        return row;
    });
    return out;
}

/** Where a server refusal lands: a top-level field, one factor row (optionally its field), or nowhere (banner). */
export interface RiskRefusalTarget {
    field?: string;
    factor?: number;
    factorField?: string;
}

const TOP_FIELD: Record<string, string> = {
    id: 'id',
    entityType: 'entityType',
    highThreshold: 'highThreshold',
    dataScope: 'dataScope',
    description: 'description',
    watchList: 'watchList',
    retainDays: 'retainValue',
    retainRuns: 'retainValue',
};

/**
 * Map a `RiskScoreModel` / `requireStorable` 422 message onto the field it names — the messages are path-led
 * (`risk-score.factors[2].cap must be >= 0`, `risk-score.highThreshold must be …`). The message itself is
 * shown verbatim; this only decides where. `factors[i]` is the server's 0-based index, the editor's row.
 */
export function mapRiskRefusal(message: string): RiskRefusalTarget {
    const f = /risk-score\.factors\[(\d+)\](?:\.(\w+))?/.exec(message);
    if (f) return { factor: Number(f[1]), ...(f[2] ? { factorField: f[2] } : {}) };
    const t = /risk-score\.(\w+)/.exec(message);
    if (t && TOP_FIELD[t[1]]) return { field: TOP_FIELD[t[1]] };
    if (/^risk-score id /.test(message)) return { field: 'id' };
    if (/set retainDays or retainRuns/.test(message)) return { field: 'retainValue' };
    return {};
}

/**
 * The prefilled per-entity Alert Rule (D-RP9 a): a link, never a second write. It follows the OKF recipe —
 * the rule watches `risk_scores_<id>_latest` (which the author must register as a Dataset first), `max(score)`
 * by `model, entity_key`, `gte` the model's High threshold.
 */
export function perEntityAlertRuleParams(id: string, highThreshold: number | null): Record<string, string> {
    return {
        newRule: '1',
        dataset: `risk_scores_${id}_latest`,
        measure: 'max(score)',
        by: 'model,entity_key',
        comparator: 'gte',
        ...(highThreshold != null ? { threshold: String(highThreshold) } : {}),
    };
}
