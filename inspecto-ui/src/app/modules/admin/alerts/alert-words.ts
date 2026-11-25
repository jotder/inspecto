import type { AlertRule, FiredAlert } from 'app/inspecto/api';
import { words } from 'app/inspecto/viz/column-label';
import { formatNumber } from 'app/inspecto/viz/number-format';

/**
 * The words the Alerts screens show for what an Alert Rule stores (ALERTS-PAGE-WORDS-1). Display only: every stored
 * key, id and wire value stays as it is; these functions only decide how one reads.
 */

/** Comparators as words: `gte 1` reads "at least 1" (the server's own titles say the same). */
const COMPARATORS: Record<string, string> = { gt: 'above', gte: 'at least', lt: 'below', lte: 'at most' };

/** The ledger Metrics (`AlertRule.METRICS`); a Consignment is what the engine's ledger calls a batch. */
const LEDGER_METRICS: Record<string, string> = {
    error_rate: 'Error rate',
    failed_batches: 'Failed Consignments',
    rejected_files: 'Rejected files',
    duration_ms: 'Average duration (ms)',
};

/** The rules the engine raises from events (`EventAlertBridge`): no Alert Rule record names them. */
const BUILT_IN_RULES: Record<string, string> = {
    sequence_gap: 'Missing file in sequence',
    file_problem: 'File problem',
    conservation_imbalance: 'Row count imbalance',
    la_index_build: 'Link Analysis index build',
};

/** The Value Measures an Alert Rule can watch: the Link Analysis library's labels (`value-measures.ts`). */
const VALUE_MEASURES: Record<string, string> = {
    passThrough: 'Pass-through',
    velocity: 'Velocity',
    timeToCashOut: 'Time to withdrawal',
    cashOutConcentration: 'Withdrawal concentration',
    structuring: 'Structuring',
    benefitTransfer: 'Benefit transfer',
};

const AGGREGATES: Record<string, string> = {
    count: 'Count',
    countDistinct: 'Distinct count',
    sum: 'Sum',
    avg: 'Average',
    min: 'Minimum',
    max: 'Maximum',
    median: 'Median',
    stddev: 'Standard deviation',
    variance: 'Variance',
};

const UNITS: Record<string, [string, string]> = {
    s: ['second', 'seconds'],
    m: ['minute', 'minutes'],
    h: ['hour', 'hours'],
    d: ['day', 'days'],
    b: ['Consignment', 'Consignments'],
};

/** Where an Alert fired: the server keeps the scope in the Alert's `pipeline` field, whatever it is. */
export type AlertScopeKind = 'Pipeline' | 'Dataset' | 'Investigation' | 'Job';

/** `gte` → "at least"; an unknown comparator is shown as stored. */
export function comparatorWords(comparator: string): string {
    return COMPARATORS[comparator] ?? comparator;
}

/** A number as read: thousands commas; a fraction keeps every digit it has (a 0.005 threshold is not 0.01). */
export function numberWords(n: number): string {
    return Number.isInteger(n) ? formatNumber(n) : String(n);
}

/** `15m` → "15 minutes", `1h` → "1 hour", `20b` → "20 Consignments"; anything else as stored. */
export function durationWords(span: string): string {
    const m = /^(\d+)([smhdb])$/.exec(span.trim());
    if (!m) return span;
    const n = Number(m[1]);
    return `${numberWords(n)} ${UNITS[m[2]][n === 1 ? 0 : 1]}`;
}

/** A rule id as words: a built-in rule by its name, else `pass_through_watch` → "Pass through watch". */
export function readableId(id: string): string {
    return BUILT_IN_RULES[id] ?? (words(id.replace(/[-.\s]+/g, '_')) || id);
}

/** What a person calls an Alert Rule: its description (its title), else its id as words. */
export function ruleName(rule: AlertRule | undefined, id: string): string {
    return rule?.description?.trim() || readableId(id);
}

/** A Value Measure's label: `passThrough` → "Pass-through". */
export function valueMeasureWords(name: string): string {
    return VALUE_MEASURES[name] ?? words(name.replace(/([a-z])([A-Z])/g, '$1_$2').toLowerCase());
}

/** A Measure (`count | agg(column)`): `count` → "Row count", `sum(exposure_sar)` → "Sum of exposure_sar". */
export function measureWords(measure: string): string {
    if (measure === 'count') return 'Row count';
    const m = /^([A-Za-z][A-Za-z0-9]*)\(([A-Za-z_][A-Za-z0-9_]*)\)$/.exec(measure);
    if (!m) return measure;
    const agg = AGGREGATES[m[1]] ?? (/^p\d\d$/.test(m[1]) ? `${Number(m[1].slice(1))}th percentile` : null);
    return agg ? `${agg} of ${m[2]}` : measure;
}

/** A Measure over one relation of an Investigation's Working Set: "Number of entities", "Sum of amount of links". */
function workingSetWords(relation: string, measure: string): string {
    return measure === 'count' ? `Number of ${relation}` : `${measureWords(measure)} of ${relation}`;
}

/** A ledger Metric id as words: `failed_batches` → "Failed Consignments". */
export function ledgerMetricWords(metric: string): string {
    return LEDGER_METRICS[metric] ?? words(metric);
}

/**
 * What a fired Alert measured, from the label the server stored in its `metric` field (`Alert.of`): a ledger Metric
 * id, a Measure, "freshness", "entities breaching passThrough", "entities count" or a built-in rule's id.
 */
export function firedWatchText(label: string): string {
    if (!label) return '';
    if (LEDGER_METRICS[label]) return LEDGER_METRICS[label];
    if (BUILT_IN_RULES[label]) return BUILT_IN_RULES[label];
    if (label === 'freshness') return 'Time since last publish';
    if (label === 'index-lag') return 'Link Analysis index lag';
    const breaching = /^entities breaching (\S+)$/.exec(label);
    if (breaching) return `Entities breaching ${valueMeasureWords(breaching[1])}`;
    const workingSet = /^(entities|links|excluded) (\S+)$/.exec(label);
    if (workingSet) return workingSetWords(workingSet[1], workingSet[2]);
    const measure = measureWords(label);
    if (measure !== label) return measure;
    return /^[a-z0-9_]+$/.test(label) ? words(label) : label;
}

/** What an Alert Rule watches. Its Dataset, Investigation or Pipeline is the Scope column, so it is not repeated. */
/** What a KPI rule (BI-INSIGHTS-1 I-17) reads, in words. */
export function kpiWatchWords(r: AlertRule): string {
    if (r.projection === 'probability') return 'Chance of meeting the KPI target';
    if (r.projection === 'value') return r.rag ? 'RAG status of the projected landing' : 'Projected landing of the KPI';
    return r.rag ? 'KPI RAG status' : 'KPI value so far this period';
}

export function ruleWatchText(r: AlertRule): string {
    if (r.metric) return ledgerMetricWords(r.metric);
    if (r.kpi) return kpiWatchWords(r);
    if (r.valueMeasure?.name) return `Entities breaching ${valueMeasureWords(r.valueMeasure.name)}`;
    if (r.investigation) return workingSetWords(r.relation || 'entities', r.measure || 'count');
    if (r.maximumAge) return r.indexLag ? 'Link Analysis index lag' : 'Time since last publish';
    if (!r.measure) return '';
    const by = r.by?.length ? ` per ${r.by.join(', ')}` : '';
    return `${measureWords(r.measure)}${by}`;
}

/** The Condition cell: "above 5 over the last 1 hour", "at least 6", "older than 2 hours". */
export function ruleConditionText(r: AlertRule): string {
    if (r.maximumAge) return `older than ${durationWords(r.maximumAge)}`;
    if (r.kpi && r.rag) return r.rag === 'red' ? 'Red' : 'Amber or Red';
    const base = `${comparatorWords(r.comparator)} ${numberWords(r.threshold)}`;
    return r.window ? `${base} over the last ${durationWords(r.window)}` : base;
}

/** The Scope cell: the Investigation, Dataset or Pipeline the rule watches; a ledger rule with none watches every Pipeline. */
export function ruleScopeText(r: AlertRule): string {
    if (r.investigation) return `Investigation ${r.investigation}`;
    if (r.kpi) return `KPI ${r.kpi}`;
    if (r.dataset) return `Dataset ${r.dataset}`;
    return r.onPipeline ? `Pipeline ${r.onPipeline}` : 'Every Pipeline';
}

/** What a fired Alert's scope is, decided by its rule; `null` when the rule is gone and nothing says. */
export function firedScopeKind(a: FiredAlert, rule: AlertRule | undefined): AlertScopeKind | null {
    if (rule?.investigation) return 'Investigation';
    if (rule?.dataset) return 'Dataset';
    if (rule) return 'Pipeline';
    if (a.rule === 'la_index_build') return 'Job';
    if (BUILT_IN_RULES[a.rule]) return 'Pipeline';
    return null;
}

/** The Scope cell of a fired Alert: "Investigation inv-7", "Dataset fraud_cases_open", "Pipeline EVENTS". */
export function firedScopeText(a: FiredAlert, rule: AlertRule | undefined): string {
    if (!a.pipeline) return '—';
    const kind = firedScopeKind(a, rule);
    return kind ? `${kind} ${a.pipeline}` : a.pipeline;
}
