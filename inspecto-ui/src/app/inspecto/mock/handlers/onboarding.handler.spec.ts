import { describe, expect, it } from 'vitest';
import { MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { componentCollection } from './components.handler';
import {
    onboardingHandler,
    PIPELINE_CONFIGS_COLL,
    StoredPipelineConfig,
    schemaZoneFindings,
    zoneRefusal,
    formatZoneDirectiveRefusal,
} from './onboarding.handler';

/**
 * Pins the mock's strictness to the server's (mock-never-more-lenient, 2026-07-27 A5.3) for the
 * onboarding draft lifecycle — especially `POST /config/patch` (collector-config unification,
 * 2026-08-04), whose whole point is semantics a lenient mock would fake green: server-side merge
 * against the CURRENT stored config, explicit-null deletes, no create-on-miss, no identity rename.
 * Server counterpart: `ControlApiConfigPatchTest`.
 */

const req = (method: string, url: string, body: unknown = null): MockRequest => ({
    method,
    url,
    body,
    params: {},
    space: 'default',
});

const handler = onboardingHandler({ mockDemo: true });

function writePipeline(store: MockStore, name: string): void {
    const res = handler(
        req('POST', '/api/config/write', {
            type: 'pipeline',
            config: {
                name,
                dirs: { poll: 'in', database: 'out' },
                parsing: { delimiter: ';' },
                collector: {
                    connector: 'local',
                    discovery: 'poll',
                    connection: 'old_conn',
                    duplicate: { mode: 'checksum', algorithm: 'xxh64' },
                },
                processing: { threads: 1 },
            },
        }),
        store,
    );
    expect(res?.status ?? 200).toBe(200);
}

function storedConfig(store: MockStore, name: string): Record<string, unknown> {
    return store.get<StoredPipelineConfig>('default', PIPELINE_CONFIGS_COLL, name)!.config;
}

describe('onboardingHandler POST /config/patch', () => {
    it('400s a body missing type, name, or a patch map', () => {
        const store = new MockStore();
        expect(handler(req('POST', '/api/config/patch', { type: 'pipeline', name: 'x' }), store)?.status).toBe(400);
        expect(handler(req('POST', '/api/config/patch', { type: 'pipeline', patch: {} }), store)?.status).toBe(400);
        expect(
            handler(req('POST', '/api/config/patch', { type: 'pipeline', name: 'x', patch: 'scalar' }), store)?.status,
        ).toBe(400);
    });

    it('404s an unknown config type', () => {
        const store = new MockStore();
        expect(
            handler(req('POST', '/api/config/patch', { type: 'nonsense', name: 'x', patch: {} }), store)?.status,
        ).toBe(404);
    });

    it('404s a missing target — patch never creates a file', () => {
        const store = new MockStore();
        const res = handler(
            req('POST', '/api/config/patch', {
                type: 'pipeline',
                name: 'never_written',
                patch: { collector: { discovery: 'watch' } },
            }),
            store,
        );
        expect(res?.status).toBe(404);
        expect(store.get('default', PIPELINE_CONFIGS_COLL, 'never_written')).toBeUndefined();
    });

    it('409s a patch that changes the identity field', () => {
        const store = new MockStore();
        writePipeline(store, 'stable');
        const res = handler(
            req('POST', '/api/config/patch', { type: 'pipeline', name: 'stable', patch: { name: 'renamed' } }),
            store,
        );
        expect(res?.status).toBe(409);
        expect(storedConfig(store, 'stable')['name']).toBe('stable');
    });

    /** The anti-clobber regression the route exists for. */
    it('merges the patched block server-side, leaving sibling blocks and unnamed keys intact', () => {
        const store = new MockStore();
        writePipeline(store, 'orders');
        const res = handler(
            req('POST', '/api/config/patch', {
                type: 'pipeline',
                name: 'orders',
                patch: { collector: { discovery: 'watch' } },
            }),
            store,
        );
        expect(res?.status ?? 200).toBe(200);
        expect((res?.body as { written: boolean }).written).toBe(true);

        const after = storedConfig(store, 'orders');
        expect(after['parsing']).toEqual({ delimiter: ';' });
        expect(after['processing']).toEqual({ threads: 1 });
        const collector = after['collector'] as Record<string, unknown>;
        expect(collector['discovery']).toBe('watch');
        expect(collector['connection']).toBe('old_conn');
        // A key no spec models travels verbatim through the block patch.
        expect(collector['duplicate']).toEqual({ mode: 'checksum', algorithm: 'xxh64' });
    });

    it('deletes a key on an explicit null, keeping its siblings', () => {
        const store = new MockStore();
        writePipeline(store, 'cleared');
        const res = handler(
            req('POST', '/api/config/patch', {
                type: 'pipeline',
                name: 'cleared',
                patch: { collector: { connection: null } },
            }),
            store,
        );
        expect(res?.status ?? 200).toBe(200);

        const collector = storedConfig(store, 'cleared')['collector'] as Record<string, unknown>;
        expect('connection' in collector).toBe(false);
        expect(collector['connector']).toBe('local');
    });
});

// ── the schema BACKWARD gate (S5b) — pins the mock to SchemaCompatibility.check exactly ──────────

const schemaConfig = (fields: Record<string, unknown>[]): Record<string, unknown> => ({
    raw: { name: 'ev', format: 'CSV', fields },
});

function writeSchema(
    store: MockStore,
    fields: Record<string, unknown>[],
    compatibility?: string,
): ReturnType<typeof handler> {
    return handler(
        req('POST', '/api/config/write', {
            type: 'schema',
            config: schemaConfig(fields),
            overwrite: true,
            ...(compatibility ? { compatibility } : {}),
        }),
        store,
    );
}

describe('onboardingHandler schema BACKWARD gate on /config/write', () => {
    const BASE = [
        { name: 'ID', selector: '0', type: 'VARCHAR' },
        { name: 'QTY', selector: '1', type: 'INTEGER' },
    ];

    it('lets a first write and a compatible edit (add field, widen type) through', () => {
        const store = new MockStore();
        expect(writeSchema(store, BASE)?.status ?? 200).toBe(200);
        const res = writeSchema(store, [
            { name: 'ID', selector: '0', type: 'VARCHAR' },
            { name: 'QTY', selector: '1', type: 'BIGINT' }, // INTEGER → BIGINT widening
            { name: 'NOTE', selector: '2', type: 'VARCHAR' }, // added field
        ]);
        expect(res?.status ?? 200).toBe(200);
    });

    it('422s a removed field, anchored raw.fields[NAME]', () => {
        const store = new MockStore();
        writeSchema(store, BASE);
        const res = writeSchema(store, [{ name: 'ID', selector: '0', type: 'VARCHAR' }]);
        expect(res?.status).toBe(422);
        const body = res?.body as { written: boolean; findings: { severity: string; fieldPath: string }[] };
        expect(body.written).toBe(false);
        expect(body.findings).toHaveLength(1);
        expect(body.findings[0]).toMatchObject({ severity: 'ERROR', fieldPath: 'raw.fields[QTY]' });
    });

    it('422s a non-widening type change (.type) and a moved selector (.selector), and does not store', () => {
        const store = new MockStore();
        writeSchema(store, BASE);
        const res = writeSchema(store, [
            { name: 'ID', selector: '9', type: 'VARCHAR' }, // selector moved
            { name: 'QTY', selector: '1', type: 'DATE' }, // INTEGER → DATE: not a widening
        ]);
        expect(res?.status).toBe(422);
        const findings = (res?.body as { findings: { fieldPath: string }[] }).findings;
        expect(findings.map((f) => f.fieldPath).sort()).toEqual(['raw.fields[ID].selector', 'raw.fields[QTY].type']);
        // refused ⇒ the stored schema is untouched
        const stored = store.get<{ config: Record<string, unknown> }>('default', 'schema-config', 'ev')!.config;
        expect((stored['raw'] as Record<string, unknown>)['fields']).toEqual(BASE);
    });

    it('compatibility: "none" overrides the gate (case-insensitive), like the server', () => {
        const store = new MockStore();
        writeSchema(store, BASE);
        const res = writeSchema(store, [{ name: 'ID', selector: '0', type: 'VARCHAR' }], 'NONE');
        expect(res?.status ?? 200).toBe(200);
    });

    it('runs the same gate on /config/patch, which has NO override key', () => {
        const store = new MockStore();
        writeSchema(store, BASE);
        const res = handler(
            req('POST', '/api/config/patch', {
                type: 'schema',
                name: 'ev',
                patch: { raw: { fields: [{ name: 'ID', selector: '0', type: 'VARCHAR' }] } },
            }),
            store,
        );
        expect(res?.status).toBe(422);
        expect((res?.body as { findings: { fieldPath: string }[] }).findings[0].fieldPath).toBe('raw.fields[QTY]');
    });
});

describe('onboardingHandler schema gate ↔ component-registry bridge', () => {
    it('gates against a schema that exists only in the component registry (one file server-side)', () => {
        const store = new MockStore();
        store.put('default', componentCollection('schema'), 'ev', {
            type: 'schema',
            name: 'ev',
            ref: 'schema/ev',
            content: { raw: { name: 'ev', fields: [{ name: 'ID', selector: '0', type: 'VARCHAR' }] } },
        });
        const res = handler(
            req('POST', '/api/config/write', {
                type: 'schema',
                overwrite: true,
                config: { raw: { name: 'ev', fields: [] } }, // removes ID
            }),
            store,
        );
        expect(res?.status).toBe(422);
    });

    it('mirrors a gated write into the component registry, so the components page reflects it', () => {
        const store = new MockStore();
        handler(
            req('POST', '/api/config/write', {
                type: 'schema',
                config: { raw: { name: 'ev', fields: [{ name: 'ID', selector: '0', type: 'VARCHAR' }] } },
            }),
            store,
        );
        const comp = store.get<{ content: Record<string, unknown> }>('default', componentCollection('schema'), 'ev');
        expect(comp).toBeDefined();
        expect((comp!.content['raw'] as Record<string, unknown>)['name']).toBe('ev');
    });
});

describe('onboardingHandler POST /config/preview/schema — the mapped half (B1)', () => {
    const preview = (config: unknown, sampleRows: unknown) =>
        handler(req('POST', '/api/config/preview/schema', { config, sampleRows }), new MockStore());

    const FIELDS = {
        raw: {
            fields: [
                { name: 'ID', type: 'DOUBLE' },
                { name: 'NOTE', type: 'VARCHAR' },
            ],
        },
    };
    const ROWS = [
        { ID: '1', NOTE: 'a' },
        { ID: 'nope', NOTE: 'b' }, // fails the DOUBLE cast → rejected, so never mapped
    ];

    it('omits the mapped half entirely when the draft declares no rules (pre-B1 shape)', () => {
        const body = preview(FIELDS, ROWS)?.body as Record<string, unknown>;
        expect(body['okCount']).toBe(1);
        expect(body['mappedRows']).toBeUndefined();
        expect(body['mappedColumns']).toBeUndefined();
    });

    it('projects DIRECT rules into target columns over the cast-passing rows only', () => {
        const config = {
            ...FIELDS,
            mapping: {
                rules: [
                    { targetColumn: 'account', sourceExpression: 'ID', transformType: 'DIRECT' },
                    { targetColumn: 'memo', sourceExpression: 'NOTE' }, // omitted type == DIRECT
                ],
            },
        };
        const body = preview(config, ROWS)?.body as Record<string, unknown>;
        expect(body['mappedColumns']).toEqual(['account', 'memo']);
        expect(body['mappedCount']).toBe(1);
        expect(body['mappedRows']).toEqual([{ account: '1', memo: 'a' }]);
    });

    /**
     * DuckDB resolves an unquoted identifier case-insensitively, so a rule seeded from a schema (field
     * names upper-cased) must still find the parsed column it names. Found in the preview: the
     * exact-match-only version answered "3 rows mapped" with every cell blank.
     */
    it('resolves a DIRECT source case-insensitively, as an unquoted identifier binds', () => {
        const config = {
            raw: { fields: [{ name: 'Column0', type: 'VARCHAR' }] },
            mapping: { rules: [{ targetColumn: 'msisdn', sourceExpression: 'COLUMN0', transformType: 'DIRECT' }] },
        };
        const body = preview(config, [{ Column0: '9198765' }])?.body as Record<string, unknown>;
        expect(body['mappedRows']).toEqual([{ msisdn: '9198765' }]);
    });

    it('yields null for an EXPR rule — the mock has no SQL engine, and must not invent a value', () => {
        const config = {
            ...FIELDS,
            mapping: { rules: [{ targetColumn: 'shout', sourceExpression: 'UPPER(NOTE)', transformType: 'EXPR' }] },
        };
        const body = preview(config, ROWS)?.body as Record<string, unknown>;
        expect(body['mappedColumns']).toEqual(['shout']);
        expect(body['mappedRows']).toEqual([{ shout: null }]);
    });
});

describe('onboardingHandler POST /config/suggest/schema', () => {
    const suggest = (sampleRows: unknown) =>
        handler(req('POST', '/api/config/suggest/schema', { sampleRows }), new MockStore());

    it('400s a missing or empty sampleRows list (server: ConfigRoutes.suggestSchema)', () => {
        expect(suggest(undefined)?.status).toBe(400);
        expect(suggest([])?.status).toBe(400);
    });

    describe('drift (B3)', () => {
        const withDraft = (draft: unknown, sampleRows: unknown) =>
            handler(req('POST', '/api/config/suggest/schema', { config: draft, sampleRows }), new MockStore());

        const SAMPLE = [
            { QUANTITY: '1.5', NOTE: 'hi' },
            { QUANTITY: '2.5', NOTE: 'there' },
        ];

        it('omits drift entirely when no draft is posted — nothing to have drifted from', () => {
            const body = suggest(SAMPLE)?.body as Record<string, unknown>;
            expect(body['fields']).toBeDefined();
            expect(body['drift']).toBeUndefined();
        });

        it('reports added, missing and typeChanged against the posted draft', () => {
            const draft = {
                raw: {
                    fields: [
                        { name: 'qty', selector: 'QUANTITY', type: 'BIGINT' },
                        { name: 'legacy', selector: 'OLD_COL', type: 'VARCHAR' },
                    ],
                },
            };
            const drift = (withDraft(draft, SAMPLE)?.body as Record<string, unknown>)['drift'] as Record<
                string,
                unknown
            >;
            expect(drift['drifted']).toBe(true);
            expect(drift['added']).toEqual([{ name: 'NOTE', type: 'VARCHAR' }]);
            expect(drift['missing']).toEqual([{ name: 'legacy', type: 'VARCHAR' }]);
            expect(drift['typeChanged']).toEqual([{ name: 'qty', declared: 'BIGINT', suggested: 'DOUBLE' }]);
        });

        it('does not treat a deliberate OUTPUT rename as drift — the join key is the selector', () => {
            const draft = { raw: { fields: [{ name: 'quantity_sold', selector: 'QUANTITY', type: 'DOUBLE' }] } };
            const drift = (withDraft(draft, [{ QUANTITY: '1.5' }])?.body as Record<string, unknown>)['drift'] as Record<
                string,
                unknown
            >;
            expect(drift['drifted']).toBe(false);
        });

        it('surfaces a renamed SOURCE column as the missing+added pair, claiming no rename', () => {
            const draft = { raw: { fields: [{ name: 'ts', selector: 'EVENT_TS', type: 'VARCHAR' }] } };
            const drift = (withDraft(draft, [{ EVENT_TIME: 'x' }])?.body as Record<string, unknown>)['drift'] as Record<
                string,
                unknown
            >;
            expect(drift['missing']).toEqual([{ name: 'ts', type: 'VARCHAR' }]);
            expect(drift['added']).toEqual([{ name: 'EVENT_TIME', type: 'VARCHAR' }]);
        });

        it('a field with no declared type has nothing to have changed from', () => {
            const draft = { raw: { fields: [{ name: 'qty', selector: 'QUANTITY', type: '' }] } };
            const drift = (withDraft(draft, [{ QUANTITY: '1.5' }])?.body as Record<string, unknown>)['drift'] as Record<
                string,
                unknown
            >;
            expect(drift['typeChanged']).toEqual([]);
            expect(drift['drifted']).toBe(false);
        });
    });

    it('422s column-less sample rows (server: SchemaSuggest.infer refuses)', () => {
        expect(suggest([{}])?.status).toBe(422);
    });

    it('votes per column with the BIGINT round-trip guard, blank abstention and DATE demotion', () => {
        const res = suggest([
            { ID: '1', AMT: '1.5', DAY: '2026-01-02', TS: '2026-01-02 10:30:00', OK: 'true', NOTE: 'hi', BLANK: '' },
            { ID: '2', AMT: '2', DAY: '2026-01-03', TS: '2026-01-03 00:00:00', OK: 'f', NOTE: '', BLANK: null },
        ]);
        expect(res?.status ?? 200).toBe(200);
        const body = res?.body as {
            fields: { name: string; selector: string; type: string }[];
            mapping: { rules: { transformType: string }[] };
        };
        const type = (n: string) => body.fields.find((f) => f.name === n)?.type;
        expect(type('ID')).toBe('BIGINT');
        expect(type('AMT')).toBe('DOUBLE'); // TRY_CAST('1.5' AS BIGINT) rounds — the guard demotes
        expect(type('DAY')).toBe('DATE'); // every value is midnight
        expect(type('TS')).toBe('TIMESTAMP');
        expect(type('OK')).toBe('BOOLEAN');
        expect(type('NOTE')).toBe('VARCHAR'); // the blank second value abstains; 'hi' decides
        expect(type('BLANK')).toBe('VARCHAR'); // nothing to vote with — unknown is not evidence
        // The DRAFT shape: selector = the column key, identity mapping rules, DIRECT transform.
        expect(body.fields.find((f) => f.name === 'ID')?.selector).toBe('ID');
        expect(body.mapping.rules).toHaveLength(body.fields.length);
        expect(body.mapping.rules[0].transformType).toBe('DIRECT');
    });
});

/**
 * Pins the dependents gate on `DELETE /config/pipeline/{name}` and the `…/impact` read to the
 * server's behaviour (`ControlApiConfigImpactTest`). ⚠ If the mock reported no dependents where the
 * backend reports some, the offline rehearsal of a delete would pass and the real one would 409.
 */
describe('onboardingHandler pipeline delete — dependents gate', () => {
    const reqWith = (method: string, url: string, params: Record<string, string>): MockRequest => ({
        method,
        url,
        body: null,
        params,
        space: 'default',
    });

    /** A stored draft named "Orders Feed", i.e. registered id `orders_feed`. */
    function seedOrigin(store: MockStore): void {
        store.put<StoredPipelineConfig>('default', PIPELINE_CONFIGS_COLL, 'orders_feed', {
            id: 'orders_feed',
            path: 'orders_feed_pipeline.toon',
            config: { name: 'Orders Feed', active: false },
            registered: true,
        });
    }

    function seedDataset(store: MockStore): void {
        store.put('default', componentCollection('dataset'), 'orders_ds', {
            type: 'dataset',
            name: 'orders_ds',
            ref: 'dataset/orders_ds',
            content: { physicalRef: 'orders_feed/database' },
        });
    }

    it('reports nothing for an unreferenced origin, keyed on the DERIVED id', () => {
        const store = new MockStore();
        seedOrigin(store);
        const res = handler(req('GET', '/api/config/pipeline/orders_feed/impact'), store);
        expect(res?.status ?? 200).toBe(200);
        const body = res?.body as { pipeline: string; total: number };
        expect(body.pipeline).toBe('orders_feed');
        expect(body.total).toBe(0);
    });

    it('follows the dataset → widget → dashboard chain', () => {
        const store = new MockStore();
        seedOrigin(store);
        seedDataset(store);
        store.put('default', componentCollection('widget'), 'orders_chart', {
            type: 'widget',
            name: 'orders_chart',
            ref: 'widget/orders_chart',
            content: { datasetId: 'orders_ds' },
        });
        store.put('default', componentCollection('dashboard'), 'ops', {
            type: 'dashboard',
            name: 'ops',
            ref: 'dashboard/ops',
            content: { tiles: [{ widgetId: 'orders_chart' }] },
        });
        const body = handler(req('GET', '/api/config/pipeline/orders_feed/impact'), store)?.body as {
            total: number;
            dependents: Record<string, { name: string; via: string }[]>;
        };
        expect(body.total).toBe(3);
        expect(body.dependents['widget'][0].via).toBe('datasetId');
        expect(body.dependents['dashboard'][0].name).toBe('ops');
    });

    it('409s the delete when something still references it, and keeps the config', () => {
        const store = new MockStore();
        seedOrigin(store);
        seedDataset(store);
        const res = handler(req('DELETE', '/api/config/pipeline/orders_feed'), store);
        expect(res?.status).toBe(409);
        expect(String((res?.body as { error?: string })?.error)).toContain('dataset/orders_ds');
        expect(store.get('default', PIPELINE_CONFIGS_COLL, 'orders_feed')).toBeTruthy();
    });

    it('force=true deletes over the dependents', () => {
        const store = new MockStore();
        seedOrigin(store);
        seedDataset(store);
        const res = handler(reqWith('DELETE', '/api/config/pipeline/orders_feed', { force: 'true' }), store);
        expect(res?.status ?? 200).toBe(200);
        expect(store.get('default', PIPELINE_CONFIGS_COLL, 'orders_feed')).toBeFalsy();
    });

    it('force does NOT bypass the active gate — that is a separate refusal', () => {
        const store = new MockStore();
        store.put<StoredPipelineConfig>('default', PIPELINE_CONFIGS_COLL, 'orders_feed', {
            id: 'orders_feed',
            path: 'orders_feed_pipeline.toon',
            config: { name: 'Orders Feed', active: true },
            registered: true,
        });
        const res = handler(reqWith('DELETE', '/api/config/pipeline/orders_feed', { force: 'true' }), store);
        expect(res?.status).toBe(409);
        expect(String((res?.body as { error?: string })?.error)).toContain('is active');
    });

    // ── source time zone: the mock must refuse exactly what the server refuses ─────

    it('⛔ rejects an offset zone — modern Intl ACCEPTS +05:30, DuckDB does not', () => {
        expect(zoneRefusal('+05:30', 'parsing.source_timezone')).toContain('fixed offset');
        expect(zoneRefusal('-08:00', 'parsing.source_timezone')).toContain('fixed offset');
        expect(zoneRefusal('Z', 'parsing.source_timezone')).toContain('fixed offset');
    });

    it('rejects an unknown zone and a blank one, naming the key', () => {
        expect(zoneRefusal('Not/AZone', 'raw.fields[T].timezone')).toContain('raw.fields[T].timezone');
        expect(zoneRefusal('', 'parsing.source_timezone')).toContain('blank');
    });

    it('accepts region ids INCLUDING a legacy alias the offered list omits', () => {
        expect(zoneRefusal('Asia/Kolkata', 'x')).toBeNull();
        expect(zoneRefusal('UTC', 'x')).toBeNull();
        // ⚠ Both spellings must pass. The offered list and the JVM's set disagree on which one they
        // carry (this ICU has Calcutta, not Kolkata), so validating against the OFFERED list would
        // refuse a config the server accepts — in whichever direction the runtime happens to fall.
        // The resolver accepts both, which is the point.
        expect(zoneRefusal('Asia/Calcutta', 'x')).toBeNull();
    });

    it('refuses a schema field that sets BOTH zone forms', () => {
        const findings = schemaZoneFindings({
            raw: { fields: [{ name: 'A', type: 'TIMESTAMP', timezone: 'UTC', timezone_column: 'TZ' }] },
        });
        expect(findings[0]).toContain('both timezone and timezone_column');
    });

    it('refuses a timezone_column that names no declared field', () => {
        const findings = schemaZoneFindings({
            raw: { fields: [{ name: 'A', type: 'TIMESTAMP', timezone_column: 'NOPE' }] },
        });
        expect(findings[0]).toContain('NOPE');
    });

    it('accepts a timezone_column that names a sibling', () => {
        expect(
            schemaZoneFindings({
                raw: {
                    fields: [
                        { name: 'A', type: 'TIMESTAMP', timezone_column: 'TZ' },
                        { name: 'TZ', type: 'VARCHAR' },
                    ],
                },
            }),
        ).toEqual([]);
    });

    it('a schema write carrying a bad zone is a 422, not a green offline save', () => {
        const store = new MockStore();
        const res = handler(
            req('POST', '/api/config/write', {
                type: 'schema',
                config: { raw: { name: 's', fields: [{ name: 'A', type: 'TIMESTAMP', timezone: '+05:30' }] } },
            }),
            store,
        );
        expect(res?.status).toBe(422);
    });

    it('a pipeline write carrying a bad parsing.source_timezone is a 422', () => {
        const store = new MockStore();
        const res = handler(
            req('POST', '/api/config/write', {
                type: 'pipeline',
                config: { name: 'tz_demo', parsing: { source_timezone: 'Not/AZone' } },
            }),
            store,
        );
        expect(res?.status).toBe(422);
        expect(String((res?.body as { error?: string })?.error)).toContain('Not/AZone');
    });

    it('a pipeline write with a GOOD source_timezone still saves', () => {
        const store = new MockStore();
        const res = handler(
            req('POST', '/api/config/write', {
                type: 'pipeline',
                config: { name: 'tz_demo', parsing: { source_timezone: 'Asia/Kolkata' } },
            }),
            store,
        );
        expect(res?.status).toBe(200);
        expect((res?.body as { written?: boolean })?.written).toBe(true);
    });
});

describe('formatZoneDirectiveRefusal (mirrors the server %z/%Z rule)', () => {
    const fmts = (key: string, list: string[]) => ({ delimited: { [key]: list } });

    it('accepts a plain format list, and an absent block', () => {
        expect(formatZoneDirectiveRefusal(undefined)).toBeNull();
        expect(formatZoneDirectiveRefusal({ frontend: 'delimited' })).toBeNull();
        expect(formatZoneDirectiveRefusal(fmts('timestamp_formats', ['%Y-%m-%d %H:%M:%S']))).toBeNull();
    });

    it('refuses both zone directives, in both lists', () => {
        expect(formatZoneDirectiveRefusal(fmts('timestamp_formats', ['%Y-%m-%d %H:%M:%S%z'])))
            .toContain('%z');
        expect(formatZoneDirectiveRefusal(fmts('timestamp_formats', ['%Y-%m-%d %H:%M:%S %Z'])))
            .toContain('%Z');
        expect(formatZoneDirectiveRefusal(fmts('date_formats', ['%Y-%m-%d%z'])))
            .toContain('date_formats');
        // a clean format beside a dirty one is still refused
        expect(formatZoneDirectiveRefusal(
            fmts('timestamp_formats', ['%Y-%m-%d %H:%M:%S', '%Y-%m-%d %H:%M:%S%z']))).not.toBeNull();
    });

    it('treats %% as an escaped literal percent, not a directive', () => {
        expect(formatZoneDirectiveRefusal(fmts('timestamp_formats', ['%Y-%m-%d %H:%M:%S%%z']))).toBeNull();
        // ...but a real directive after an escaped one is still a directive
        expect(formatZoneDirectiveRefusal(fmts('timestamp_formats', ['%Y-%m-%d %H:%M:%S%%%z'])))
            .not.toBeNull();
    });

    it('ignores a list authored at parsing: level, which the engine never reads', () => {
        expect(formatZoneDirectiveRefusal({ timestamp_formats: ['%Y-%m-%d %H:%M:%S%z'] })).toBeNull();
    });
});
