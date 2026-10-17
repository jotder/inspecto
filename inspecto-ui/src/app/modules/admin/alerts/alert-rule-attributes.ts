import { AttributeSpec } from 'app/inspecto/component-model';
import { MEASURE_AGGS } from 'app/inspecto/query/measure-grammar';

/**
 * The Alert Rule kind's attribute declarations — drives `<inspecto-schema-form>` in
 * {@link AlertRuleFormDialog} (audit C3). An Alert Rule watches either an observability **Metric**
 * over a window of the batch ledger (GLOSSARY §4/§8) or a **Measure** over a Dataset's current data
 * (BI-5), against a threshold. `kind` picks the shape and is FORM-ONLY — never written: the engine
 * (`AlertRule.fromMap`) tells the two apart by `dataset`, so each kind's fields hang off `kind` via
 * `dependsOn` and a hidden field is neither validated nor saved.
 */
// 'name' (the rule id) is asked at save time (ui-design-review R9 — name-at-save), not declared
// here; see AlertRuleFormDialog's `saveForm`.
export const ALERT_RULE_ATTRIBUTES: AttributeSpec[] = [
    {
        key: 'kind',
        label: 'Watch',
        type: 'select',
        tier: 'required',
        default: 'metric',
        options: [
            { value: 'metric', label: 'A Pipeline metric (over the batch ledger)' },
            { value: 'measure', label: 'A Dataset Measure (over its current data)' },
        ],
    },
    {
        key: 'dataset',
        label: 'Dataset',
        type: 'autocomplete',
        tier: 'required',
        dependsOn: { key: 'kind', equals: 'measure' },
        placeholder: 'e.g. fraud_cases_open',
        help: 'The Dataset whose current data the Measure is computed over.',
    },
    {
        key: 'measure',
        label: 'Measure',
        type: 'string',
        tier: 'required',
        dependsOn: { key: 'kind', equals: 'measure' },
        placeholder: 'e.g. count, sum(exposure_sar)',
        help: `count, or agg(column) with agg one of ${MEASURE_AGGS.join(', ')}.`,
    },
    {
        key: 'metric',
        label: 'Metric',
        type: 'autocomplete',
        tier: 'required',
        dependsOn: { key: 'kind', equals: 'metric' },
        placeholder: 'e.g. error_rate, rejected_files, duration_ms',
        help: 'The observability metric to watch (as emitted by the engine).',
    },
    {
        key: 'comparator',
        label: 'Comparator',
        type: 'select',
        tier: 'required',
        default: 'gt',
        options: [
            { value: 'gt', label: '> greater than' },
            { value: 'gte', label: '≥ at least' },
            { value: 'lt', label: '< less than' },
            { value: 'lte', label: '≤ at most' },
        ],
    },
    // No default: the engine refuses a threshold that is not > 0 (`AlertRule`), and a pre-filled 0 is exactly that.
    {
        key: 'threshold',
        label: 'Threshold',
        type: 'number',
        tier: 'required',
        help: 'A positive number (an error_rate is a fraction, e.g. 0.05).',
    },
    {
        key: 'window',
        label: 'Window',
        type: 'select',
        tier: 'required',
        default: '15m',
        dependsOn: { key: 'kind', equals: 'metric' },
        options: [
            { value: '5m', label: '5 minutes' },
            { value: '15m', label: '15 minutes' },
            { value: '1h', label: '1 hour' },
            { value: '24h', label: '24 hours' },
        ],
        help: 'The evaluation window the metric is aggregated over.',
    },
    {
        key: 'severity',
        label: 'Severity',
        type: 'select',
        tier: 'required',
        default: 'WARNING',
        options: [
            { value: 'INFO', label: 'Info' },
            { value: 'WARNING', label: 'Warning' },
            { value: 'CRITICAL', label: 'Critical' },
        ],
    },
    {
        key: 'by',
        label: 'Incident per',
        type: 'list',
        tier: 'optional',
        dependsOn: { key: 'kind', equals: 'measure' },
        placeholder: 'e.g. msisdn',
        help: 'Key columns of the Dataset: the Measure is computed per key. The rule raises ONE Alert that counts its breaching keys, and each breaching key opens its own Incident.',
    },
    {
        key: 'stormCap',
        label: 'Storm cap',
        type: 'number',
        tier: 'optional',
        required: false,
        min: 1,
        dependsOn: { key: 'kind', equals: 'measure' },
        placeholder: '100',
        help: 'With Incident per: above this many breaching keys, the Alert says so and one storm Incident replaces the per-key ones (default 100).',
    },
    {
        key: 'healAfterSweeps',
        label: 'Heal after sweeps',
        type: 'number',
        tier: 'optional',
        required: false,
        min: 1,
        dependsOn: { key: 'kind', equals: 'measure' },
        placeholder: '1',
        help: 'Healthy sweeps in a row before the Alert is cleared; a value that keeps crossing its threshold stays one open Alert (default 1).',
    },
    {
        key: 'onPipeline',
        label: 'Pipeline scope',
        type: 'autocomplete',
        tier: 'optional',
        placeholder: 'e.g. cdr_ingest',
        help: 'Limit the rule to one Pipeline; leave blank to watch every Pipeline.',
    },
    {
        key: 'description',
        label: 'Description',
        type: 'string',
        tier: 'optional',
        placeholder: 'e.g. Open fraud exposure is too high',
        help: 'The human title for the Alerts and Incidents this rule raises; leave blank to generate one.',
    },
    {
        key: 'runbook',
        label: 'Runbook',
        type: 'autocomplete',
        tier: 'optional',
        placeholder: 'e.g. fraud_irsf',
        help: 'The Runbook shown on the Incident or Case this rule raises. It must already exist.',
    },
];
