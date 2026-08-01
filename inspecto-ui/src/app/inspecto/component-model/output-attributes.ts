import { AttributeSpec } from './attribute-spec';

/**
 * The `output:` block — ONE shared table for both authoring surfaces (U-D's "one table per
 * concern", extended from the collector to the sink by W4a): the Pipelines editor's three sink
 * kinds and Onboarding's Dataset & Go-live stage author the same block, read by
 * `PipelineConfigParser:255-257` (`format` CSV | PARQUET — absent = CSV, the `PartitionWriter`
 * default; `compression` codec, blank = format default).
 *
 * <p>The `format` default here is the ENGINE's absent-key behaviour (CSV). A surface that wants to
 * *suggest* a different format for brand-new configs (Onboarding suggests Parquet at go-live) seeds
 * it via the form's `initial`, never by forking this table — two hand-written tables with divergent
 * defaults is exactly the drift this replaces (`SINK_ATTRIBUTES` CSV/advanced vs
 * `PUBLISH_ATTRIBUTES` PARQUET/optional).
 */
export const OUTPUT_ATTRIBUTES: AttributeSpec[] = [
    {
        key: 'format', label: 'Output format', type: 'select', tier: 'required', required: false, default: 'CSV',
        options: [
            { value: 'CSV', label: 'CSV' },
            { value: 'PARQUET', label: 'Parquet' },
        ],
        help: 'Stage-1 output file format; absent = CSV (the engine default).',
    },
    {
        key: 'compression', label: 'Compression', type: 'string', tier: 'optional',
        placeholder: 'snappy', help: 'Codec for the output (e.g. snappy / zstd / gzip); blank = format default.',
    },
];
