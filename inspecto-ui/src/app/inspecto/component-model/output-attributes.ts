import { AttributeSpec } from './attribute-spec';

/**
 * The `output:` block — ONE shared table for both authoring surfaces (U-D's "one table per
 * concern", extended from the collector to the sink by W4a): the Pipelines editor's three sink
 * kinds and Onboarding's Dataset & Go-live stage author the same block, read by
 * `PipelineConfigParser.parseOutputAndSinks` (`format` CSV | PARQUET — absent = PARQUET, the
 * engine default; `compression` codec, blank = format default).
 *
 * <p>The `format` default here is the ENGINE's absent-key behaviour (PARQUET). A surface that wants to
 * *suggest* a different format for brand-new configs seeds
 * it via the form's `initial`, never by forking this table — two hand-written tables with divergent
 * defaults is exactly the drift this replaces (`SINK_ATTRIBUTES` CSV/advanced vs
 * `PUBLISH_ATTRIBUTES` PARQUET/optional).
 */
export const OUTPUT_ATTRIBUTES: AttributeSpec[] = [
    {
        key: 'format',
        label: 'Output format',
        type: 'select',
        tier: 'required',
        required: false,
        default: 'PARQUET',
        options: [
            { value: 'CSV', label: 'CSV' },
            { value: 'PARQUET', label: 'Parquet' },
        ],
        help: 'Stage-1 output file format; absent = Parquet (the engine default). A CSV store is text: it reads back VARCHAR whatever the schema declares.',
    },
    {
        key: 'compression',
        label: 'Compression',
        type: 'string',
        tier: 'optional',
        placeholder: 'snappy',
        help: 'Codec for the output (e.g. snappy / zstd / gzip); blank = format default.',
    },
    {
        key: 'filename_column',
        label: 'Source filename column',
        type: 'identifier',
        tier: 'advanced',
        placeholder: 'file_name',
        help: 'Adds a column of this name carrying each row’s source file. New pipelines default to file_name; blank = no column (lineage stays in the ledger only).',
    },
];
