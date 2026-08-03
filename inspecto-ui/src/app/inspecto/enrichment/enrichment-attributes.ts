import { AttributeSpec } from 'app/inspecto/component-model';

/**
 * The `EnrichmentConfig` wiring keys a host must ASK for when it cannot derive them — the Pipelines
 * editor's `enrichment` node dialog renders this table; the Onboarding stage derives the same keys
 * from its own draft instead (input = the Stage-1 output, trigger = the pipeline itself) and so
 * never renders it. Keys and defaults mirror `EnrichmentConfig.fromMap` exactly (engine-real:
 * `input.format`/`output.format` default PARQUET there).
 *
 * <p>⚠ `input.partitions` / `output.partitions` are still NOT specced, but the original reason is gone:
 * `AttributeSpec` gained a `list` type (string[]) with D7, 2026-08-03, and these are exactly string
 * lists. Speccing them is now a small, unblocked follow-up rather than a capability gap — it was left
 * out of D7 to keep that change scoped. Until then, hosts must carry existing partition lists through a
 * save verbatim, and a comma-string knob is still wrong here (`strList` rejects it at load).
 */
export const ENRICHMENT_WIRING_ATTRIBUTES: AttributeSpec[] = [
    {
        key: 'input__database', label: 'Input store', type: 'string', tier: 'required',
        placeholder: 'spaces/demo/data/orders_feed/database',
        help: 'The Stage-1 output directory the transform reads as the `input` view.',
    },
    {
        key: 'input__format', label: 'Input format', type: 'select', tier: 'optional', default: 'PARQUET',
        options: [{ value: 'PARQUET', label: 'Parquet' }, { value: 'CSV', label: 'CSV' }],
    },
    {
        key: 'output__database', label: 'Output store', type: 'string', tier: 'required',
        placeholder: 'spaces/demo/data/enriched/orders_feed_enrich',
        help: 'Where the enriched output lands.',
    },
    {
        key: 'output__format', label: 'Output format', type: 'select', tier: 'optional', default: 'PARQUET',
        options: [{ value: 'PARQUET', label: 'Parquet' }, { value: 'CSV', label: 'CSV' }],
    },
    {
        key: 'output__compression', label: 'Compression', type: 'string', tier: 'advanced',
        placeholder: 'snappy', help: 'Codec for the enriched output; blank = format default.',
    },
    {
        key: 'triggers__on_pipeline', label: 'Run after pipeline', type: 'autocomplete', tier: 'required',
        required: false,
        help: 'Runs after every committed batch of this pipeline — a partition-scoped recompute, not a full rescan.',
    },
    {
        key: 'triggers__schedule_seconds', label: 'Also run every (seconds)', type: 'number', tier: 'advanced',
        min: 1, help: 'Optional scheduled recompute alongside (or instead of) the batch trigger.',
    },
];
