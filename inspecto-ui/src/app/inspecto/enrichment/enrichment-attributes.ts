import { AttributeSpec } from 'app/inspecto/component-model';

/**
 * The `EnrichmentConfig` wiring keys a host must ASK for when it cannot derive them — the Pipelines
 * editor's `enrichment` node dialog renders this table; the Onboarding stage derives the same keys
 * from its own draft instead (input = the Stage-1 output, trigger = the pipeline itself) and so
 * never renders it. Keys and defaults mirror `EnrichmentConfig.fromMap` exactly (engine-real:
 * `input.format`/`output.format` default PARQUET there).
 *
 * <p>`input.partitions` / `output.partitions` are specced as `list` (chips) since 2026-08-13. Both are
 * REQUIRED by the engine — `EnrichmentConfig.fromMap` throws `Missing or invalid list` when either key
 * is absent — but an EMPTY list is legal (an unpartitioned store), which is why they declare
 * `required: false` on the `required` tier and default to `[]`: the key is always written, never forced
 * to carry an entry. The `pattern` mirrors `Identifiers.validate` (`^[A-Za-z_][A-Za-z0-9_]*$`), the same
 * guard the parser applies to every entry.
 *
 * <p>⚠ An `output.partitions` entry may be authored as a `{column, source}` map (the sink shape, 2026-08-11)
 * where `source` declares event time and drives the recorded bounds. A `list` spec is `string[]` only, so
 * the host MUST flatten map entries to their `column` when hydrating and re-marry the `source` back on
 * save — see `node-config.dialog.ts`. Speccing this key without that round-trip silently drops `source`,
 * because a specced key is form-OWNED and replaced wholesale.
 */
export const ENRICHMENT_WIRING_ATTRIBUTES: AttributeSpec[] = [
    {
        key: 'input__database',
        label: 'Input store',
        type: 'string',
        tier: 'required',
        placeholder: 'spaces/demo/data/orders_feed/database',
        help: 'The Stage-1 output directory the transform reads as the `input` view.',
    },
    {
        key: 'input__format',
        label: 'Input format',
        type: 'select',
        tier: 'optional',
        default: 'PARQUET',
        options: [
            { value: 'PARQUET', label: 'Parquet' },
            { value: 'CSV', label: 'CSV' },
        ],
    },
    {
        key: 'input__partitions',
        label: 'Input partitions',
        type: 'list',
        tier: 'required',
        required: false,
        default: [],
        pattern: '[A-Za-z_][A-Za-z0-9_]*',
        help: 'Hive partition columns present on the Stage-1 output. Empty = unpartitioned.',
    },
    {
        key: 'output__database',
        label: 'Output store',
        type: 'string',
        tier: 'required',
        placeholder: 'spaces/demo/data/enriched/orders_feed_enrich',
        help: 'Where the enriched output lands.',
    },
    {
        key: 'output__format',
        label: 'Output format',
        type: 'select',
        tier: 'optional',
        default: 'PARQUET',
        options: [
            { value: 'PARQUET', label: 'Parquet' },
            { value: 'CSV', label: 'CSV' },
        ],
    },
    {
        key: 'output__partitions',
        label: 'Output partitions',
        type: 'list',
        tier: 'required',
        required: false,
        default: [],
        pattern: '[A-Za-z_][A-Za-z0-9_]*',
        help: 'Output grain, which may differ from the input grain. Empty = unpartitioned.',
    },
    {
        key: 'output__compression',
        label: 'Compression',
        type: 'string',
        tier: 'advanced',
        placeholder: 'snappy',
        help: 'Codec for the enriched output; blank = format default.',
    },
    {
        key: 'triggers__on_pipeline',
        label: 'Run after pipeline',
        type: 'autocomplete',
        tier: 'required',
        required: false,
        help: 'Runs after every committed batch of this pipeline — a partition-scoped recompute, not a full rescan.',
    },
    {
        key: 'triggers__schedule_seconds',
        label: 'Also run every (seconds)',
        type: 'number',
        tier: 'advanced',
        min: 1,
        help: 'Optional scheduled recompute alongside (or instead of) the batch trigger.',
    },
];
