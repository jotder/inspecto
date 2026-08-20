import type { AuthoredPipeline } from '../../api/pipelines.service';
import { PIPELINES_COLL } from '../handlers/pipelines.handler';
import { MockStore } from '../mock-store';

/**
 * Format-example pack — one small, authored pipeline per DuckDB-native parser frontend
 * (delimited/fixedwidth/xlsx/json), mirroring the real examples shipped under
 * `spaces/default/config/{csv,fixedwidth,excel,json}_example/`. Retired the CS1–CS5 canvas-stress
 * pack (`docs/archived-documents/plans-archive/pipeline-case-studies.md`) 2026-08-20 — operator
 * request: one worked example per format, not five editor-boundary stress fixtures.
 *
 * <p>Each node authors its Grammar **inline** (`config.parsing`), the current model (Grammar
 * templates are copies, never bindings — `docs/okf/frontend/features/grammar-config.md`), unlike
 * the retired pack's `use: 'grammar/<id>'` bindings, which predate that reversal. Invariants pinned
 * by `modules/admin/pipelines/format-examples.spec.ts`.
 */

const CSV_EXAMPLE: AuthoredPipeline = {
    name: 'csv_example',
    active: true,
    nodes: [
        { id: 'collect', type: 'acquisition', name: 'Orders inbox', config: { include: 'glob:**/*.csv' } },
        {
            id: 'parse',
            type: 'parser.delimited',
            name: 'Parse orders CSV',
            config: {
                parsing: {
                    frontend: 'delimited',
                    delimited: {
                        delimiter: '|',
                        quote: '"',
                        comment: '#',
                        has_header: true,
                        skip_header_lines: 1,
                        null_strings: ['NULL', 'N/A'],
                    },
                },
            },
        },
        {
            id: 'map',
            type: 'transform.map',
            name: 'Normalize orders',
            config: { rename: 'ORDER_ID=order_id', cast: 'AMOUNT:decimal(18,2)' },
        },
        { id: 'sink', type: 'sink.persistent', name: 'Orders lake', config: { format: 'PARQUET' } },
    ],
    edges: [
        { from: 'collect', rel: 'data', to: 'parse' },
        { from: 'parse', rel: 'data', to: 'map' },
        { from: 'map', rel: 'data', to: 'sink' },
    ],
};

const FIXEDWIDTH_EXAMPLE: AuthoredPipeline = {
    name: 'fixedwidth_example',
    active: true,
    nodes: [
        { id: 'collect', type: 'acquisition', name: 'Ledger inbox', config: { include: 'glob:**/*.dat' } },
        {
            id: 'parse',
            type: 'parser.fixedwidth',
            name: 'Parse ledger extract',
            config: {
                parsing: {
                    frontend: 'fixedwidth',
                    delimited: { has_header: true, skip_header_lines: 1 },
                    fixedwidth: {
                        record: 'line',
                        trim: 'both',
                        min_record_length: 34,
                        fields: [
                            { name: 'ACCT', start: 0, length: 8 },
                            { name: 'TXN_DATE', start: 8, length: 10 },
                            { name: 'AMOUNT', start: 18, length: 12 },
                            { name: 'TXN_TYPE', start: 30, length: 4 },
                        ],
                    },
                },
            },
        },
        {
            id: 'map',
            type: 'transform.map',
            name: 'Normalize ledger rows',
            config: { cast: 'AMOUNT:decimal(18,2)' },
        },
        { id: 'sink', type: 'sink.persistent', name: 'Ledger lake', config: { format: 'PARQUET' } },
    ],
    edges: [
        { from: 'collect', rel: 'data', to: 'parse' },
        { from: 'parse', rel: 'data', to: 'map' },
        { from: 'map', rel: 'data', to: 'sink' },
    ],
};

const EXCEL_EXAMPLE: AuthoredPipeline = {
    name: 'excel_example',
    active: true,
    nodes: [
        { id: 'collect', type: 'acquisition', name: 'Inventory inbox', config: { include: 'glob:**/*.xlsx' } },
        {
            id: 'parse',
            type: 'parser.xlsx',
            name: 'Parse inventory workbook',
            config: {
                parsing: {
                    frontend: 'xlsx',
                    xlsx: {
                        sheet: 'Inventory',
                        range: 'A1:D6',
                        header: true,
                        normalize_names: true,
                        ignore_errors: true,
                    },
                },
            },
        },
        {
            id: 'map',
            type: 'transform.map',
            name: 'Normalize inventory rows',
            config: { cast: 'unit_price:decimal(18,2)' },
        },
        { id: 'sink', type: 'sink.persistent', name: 'Inventory lake', config: { format: 'PARQUET' } },
    ],
    edges: [
        { from: 'collect', rel: 'data', to: 'parse' },
        { from: 'parse', rel: 'data', to: 'map' },
        { from: 'map', rel: 'data', to: 'sink' },
    ],
};

const JSON_EXAMPLE: AuthoredPipeline = {
    name: 'json_example',
    active: true,
    nodes: [
        { id: 'collect', type: 'acquisition', name: 'Shipments inbox', config: { include: 'glob:**/*.json' } },
        {
            id: 'parse',
            type: 'parser.json',
            name: 'Parse nested shipments',
            config: {
                parsing: {
                    frontend: 'json',
                    json: {
                        format: 'auto',
                        records_path: 'payload.shipments',
                        maximum_object_size: 4194304,
                    },
                },
            },
        },
        {
            id: 'map',
            type: 'transform.map',
            name: 'Normalize shipments',
            config: { cast: 'weight_kg:decimal(18,2)' },
        },
        { id: 'sink', type: 'sink.persistent', name: 'Shipments lake', config: { format: 'PARQUET' } },
    ],
    edges: [
        { from: 'collect', rel: 'data', to: 'parse' },
        { from: 'parse', rel: 'data', to: 'map' },
        { from: 'map', rel: 'data', to: 'sink' },
    ],
};

export const FORMAT_EXAMPLE_PIPELINES: AuthoredPipeline[] = [
    CSV_EXAMPLE,
    FIXEDWIDTH_EXAMPLE,
    EXCEL_EXAMPLE,
    JSON_EXAMPLE,
];

export function seedFormatExamplePipelines(store: MockStore, space: string): void {
    for (const p of FORMAT_EXAMPLE_PIPELINES) {
        store.put(space, PIPELINES_COLL, p.name, p);
    }
}
