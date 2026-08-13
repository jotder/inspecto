import { describe, expect, it } from 'vitest';
import { registerIntegrityRules } from '../integrity';
import { MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { seedDefaultSpace } from '../seeds/default-space.seed';
import { NODE_TYPES, pipelinesHandler } from './pipelines.handler';
import { PIPELINE_CONFIGS_COLL } from './onboarding.handler';

const req = (method: string, url: string, body: unknown = null): MockRequest => ({
    method,
    url,
    body,
    params: {},
    space: 'default',
});

function seededStore(): MockStore {
    const store = new MockStore();
    registerIntegrityRules(store);
    store.ensureSeeded('default', seedDefaultSpace);
    return store;
}

/**
 * W2/U-D. The palette was invented and shared only `transform.filter`/`transform.route` with the
 * backend enum `BuiltinNodeType`, so the editor authored nodes `PipelineCompiler` silently dropped —
 * and the mock made it look correct offline. This pins the mock to the enum: if the two drift again,
 * this fails instead of the round-trip failing quietly against a real server.
 *
 * Keep this list byte-identical to `BuiltinNodeType`'s `type()` values, in enum order. When the enum
 * gains a type, update BOTH. Never add a type to the mock that the enum does not have.
 */
describe('pipelinesHandler — the palette mirrors the backend BuiltinNodeType enum', () => {
    const ENUM_TYPES = [
        'acquisition',
        'adapter',
        'parser',
        'transform.map',
        'transform.filter',
        'transform.select',
        'transform.derive',
        'transform.validate',
        'transform.dedup.marker',
        'transform.dedup',
        'transform.route',
        'transform.join',
        'transform.summarize',
        'transform.split',
        'transform.merge',
        'enrichment',
        'sink.persistent',
        'sink.materialized',
        'sink.view',
        'alert',
        'gap',
        'event',
    ];

    it('serves exactly the enum types, in enum order', () => {
        expect(NODE_TYPES.map((t) => t.type)).toEqual(ENUM_TYPES);
    });

    it('carries none of the invented types the editor used to author', () => {
        const served = new Set(NODE_TYPES.map((t) => t.type));
        for (const fiction of [
            'collector.file',
            'collector.database',
            'collector.stream',
            'sink.file',
            'sink.database',
            'parser.dsv',
            'parser.asn1',
            'transform.record',
            'transform.aggregate',
            'transform.alert',
        ]) {
            expect(served.has(fiction)).toBe(false);
        }
    });

    it('models the CONTROL category the old palette omitted entirely', () => {
        const control = NODE_TYPES.filter((t) => t.category === 'CONTROL').map((t) => t.type);
        expect(control).toEqual(['alert', 'gap', 'event']);
        // CONTROL nodes are side-tasks: they consume an outcome and emit no downstream edge.
        for (const t of NODE_TYPES.filter((n) => n.category === 'CONTROL')) expect(t.emits).toEqual([]);
    });

    /**
     * File dedup (marker) and record dedup are DIFFERENT subsystems — never flatten them into one
     * node. `transform.dedup.fingerprint` was removed 2026-08-04: content-fingerprint dedup executes
     * inside the `CollectorProcessor` poll cycle (`ledgerFilter` reads `collector.duplicate`), so it
     * is collector-block policy authored on the acquisition node — never a transform of its own.
     */
    it('serves the two dedup subsystems and not the removed fingerprint node', () => {
        const dedups = NODE_TYPES.filter((t) => t.type.startsWith('transform.dedup')).map((t) => t.type);
        expect(dedups).toEqual(['transform.dedup.marker', 'transform.dedup']);
    });

    it('marks exactly the 12 types the server can lower — a laxer mock is the whole failure mode', () => {
        // Must equal PipelineEditable.LOWERABLE. If the server's set changes, this test is the
        // tripwire: a mock that offers more than the backend accepts sends the user into a 422.
        expect(
            NODE_TYPES.filter((t) => t.lowerable)
                .map((t) => t.type)
                .sort(),
        ).toEqual([
            'acquisition',
            'enrichment',
            'gap',
            'parser',
            'sink.persistent',
            'transform.dedup',
            'transform.dedup.marker',
            'transform.filter',
            'transform.join',
            'transform.map',
            'transform.route',
            'transform.summarize',
        ]);
        expect(NODE_TYPES.length).toBe(22);
    });

    it('only the parser and the router emit operator-named routes', () => {
        expect(NODE_TYPES.filter((t) => t.emitsNamedRoutes).map((t) => t.type)).toEqual(['parser', 'transform.route']);
    });
});

describe('pipelinesHandler — authored DELETE referential integrity (R2)', () => {
    const handler = pipelinesHandler({ mockPipelines: true, mockStudio: true });

    it('409s deleting a pipeline a job triggers on, listing the referencing job', () => {
        const store = seededStore();
        // Seeded: job enrich_roaming has onPipeline: 'cdr_ingest'.
        const res = handler(req('DELETE', '/api/pipelines/authored/cdr_ingest'), store);
        expect(res?.status).toBe(409);
        expect(String((res?.body as { error: string }).error)).toContain('job/enrich_roaming');
        expect(store.get('default', 'authored-pipeline', 'cdr_ingest')).toBeDefined();
    });

    it('deletes an unreferenced pipeline', () => {
        const store = seededStore();
        const res = handler(req('DELETE', '/api/pipelines/authored/subscriber_load'), store);
        expect(res?.body).toEqual({ deleted: true });
        expect(store.get('default', 'authored-pipeline', 'subscriber_load')).toBeUndefined();
    });
});

describe('pipelinesHandler — /views (sink.view UI-consumer, T32 follow-up)', () => {
    const handler = pipelinesHandler({ mockPipelines: true, mockStudio: true });

    function withView(store: MockStore): void {
        // Views come from sink.view nodes on grandfathered flows — seed one directly (authoring
        // POST /pipelines/authored retired with W5).
        store.put('default', 'authored-pipeline', 'orders_etl', {
            name: 'orders_etl',
            active: false,
            nodes: [{ id: 'v', type: 'sink.view', name: 'orders_view' }],
            edges: [],
        });
    }

    it('lists views across authored pipelines', () => {
        const store = seededStore();
        withView(store);
        const res = handler(req('GET', '/api/views'), store);
        expect(res?.body).toEqual([expect.objectContaining({ store: 'orders_view', flow: 'orders_etl' })]);
    });

    it('404s an unknown view name', () => {
        const store = seededStore();
        withView(store);
        const res = handler(req('GET', '/api/views/nope'), store);
        expect(res?.status).toBe(404);
    });

    it('returns bounded rows for /views/{name}/data', () => {
        const store = seededStore();
        withView(store);
        const res = handler(req('GET', '/api/views/orders_view/data'), store);
        expect(res?.status).toBe(200);
        expect((res?.body as { rows: unknown[] }).rows.length).toBeGreaterThan(0);
    });
});

describe('pipelinesHandler — the W5 canonical graph round-trip (lift/lower over the config)', () => {
    const handler = pipelinesHandler({ mockPipelines: true, mockStudio: true });

    it('lists the registered CANONICAL pipelines (config store), not the grandfathered flows', () => {
        const store = seededStore();
        const names = ((handler(req('GET', '/api/pipelines'), store)?.body as { name: string }[]) ?? []).map(
            (p) => p.name,
        );
        expect(names).toContain('cdr_ingest'); // the canonical config seed
    });

    it('lifts a config to the editable graph and lowers an edit back verbatim', () => {
        const store = seededStore();
        const raw = handler(req('GET', '/api/pipelines/cdr_ingest/graph/raw'), store)?.body as {
            nodes: { type: string }[];
        };
        expect(raw.nodes.some((n) => n.type === 'acquisition')).toBe(true);
        expect(raw.nodes.some((n) => n.type === 'sink.persistent')).toBe(true);
        const put = handler(req('PUT', '/api/pipelines/cdr_ingest/graph', raw), store);
        expect((put?.body as { written: boolean }).written).toBe(true);
    });

    /**
     * D7 mock parity (2026-08-03). The row-filter keys live inside `processing.csv_settings` in the file,
     * but the backend's `PipelineLift.filterConfig` lifts them onto their own `transform.filter` node.
     * The mock used to leave them on the PARSER node's verbatim `csv_settings`, so the offline editor drew
     * a different graph than the server for the same file — and the Filter node's new attribute schema
     * had nothing to bind to. This pins the split, and that the value still round-trips back into
     * `csv_settings` on save (`lower` merges every filter node's cfg into that map).
     */
    it('lifts a csv_settings row filter onto a Filter node, and lowers it back into csv_settings', () => {
        const store = seededStore();
        const raw = handler(req('GET', '/api/pipelines/cdr_ingest/graph/raw'), store)?.body as {
            nodes: { id: string; type: string; config?: Record<string, unknown> }[];
        };

        const filter = raw.nodes.find((n) => n.type === 'transform.filter');
        expect(filter, 'the seed carries csv_settings.where, so a Filter node must be lifted').toBeDefined();
        expect(filter?.config?.['where']).toBe("msisdn NOT LIKE '0000%'");
        // ...and it must NOT also be left on the parser node (that is the split-brain the split fixes)
        const parser = raw.nodes.find((n) => n.type === 'parser');
        expect((parser?.config?.['csv_settings'] as Record<string, unknown>)?.['where']).toBeUndefined();
        // `filter_target_column` travels only with the pre-parse lists, which this seed has none of
        expect(filter?.config?.['filter_target_column']).toBeUndefined();

        const put = handler(req('PUT', '/api/pipelines/cdr_ingest/graph', raw), store);
        expect((put?.body as { written: boolean }).written).toBe(true);
        const saved = store.get<{ config: { processing: { csv_settings: Record<string, unknown> } } }>(
            'default',
            PIPELINE_CONFIGS_COLL,
            'cdr_ingest',
        )!;
        expect(saved.config.processing.csv_settings['where']).toBe("msisdn NOT LIKE '0000%'");
    });

    it('refuses an unrepresentable topology with a named code (never silently truncates)', () => {
        const store = seededStore();
        const bad = {
            active: true,
            nodes: [
                { id: 'acq', type: 'acquisition', config: { poll: 'in' } },
                { id: 'd', type: 'transform.derive' },
                { id: 'p', type: 'parser', config: { schema_file: 's.toon' } },
                { id: 'out', type: 'sink.persistent', config: { database: 'db' } },
            ],
            edges: [],
        };
        const res = handler(req('PUT', '/api/pipelines/refuse/graph', bad), store);
        expect(res?.status).toBe(422);
        expect((res?.body as { refusals: { code: string }[] }).refusals[0].code).toBe('UNSUPPORTED_NODE');
    });

    it('POST/PUT /pipelines/authored are retired (405) — authoring goes through the graph now', () => {
        const store = seededStore();
        expect(
            handler(req('POST', '/api/pipelines/authored', { name: 'x', nodes: [], edges: [] }), store)?.status,
        ).toBe(405);
        expect(handler(req('PUT', '/api/pipelines/authored/cdr_ingest', { nodes: [], edges: [] }), store)?.status).toBe(
            405,
        );
    });
});

/**
 * T4 — save-as-template / label offline.
 *
 * The mock must refuse exactly what `PipelineRoutes` refuses AND neutralise exactly what it
 * neutralises. A lenient mock here is worse than none: the whole feature is the promise that a copy
 * cannot touch its source, so a mock that left one `dirs` entry pointing at the original would let the
 * preview greenlight precisely the collision this exists to prevent.
 */
describe('pipelinesHandler — save-as-template mirrors the server gates and neutralising', () => {
    const handler = pipelinesHandler({ mockPipelines: true, mockStudio: true });
    const post = (url: string, body: unknown, store: MockStore) => handler(req('POST', url, body), store);

    it('refuses an unknown source (404), a missing id (400), a bad id (422) and a taken id (409)', () => {
        const store = seededStore();
        expect(post('/api/pipelines/nope/save-as-template', { id: 'x' }, store)?.status).toBe(404);
        expect(post('/api/pipelines/cdr_ingest/save-as-template', {}, store)?.status).toBe(400);
        for (const bad of ['Orders EU', 'orders-eu', '_x', 'x!']) {
            expect(
                post('/api/pipelines/cdr_ingest/save-as-template', { id: bad }, store)?.status,
                `${bad} must be refused`,
            ).toBe(422);
        }
        expect(post('/api/pipelines/cdr_ingest/save-as-template', { id: 'cdr_ingest' }, store)?.status).toBe(409);
    });

    it('shares no dirs, stream or collector id with the source, and is never active', () => {
        const store = seededStore();
        const src = store.get<{ config: Record<string, unknown> }>('default', PIPELINE_CONFIGS_COLL, 'cdr_ingest')!;
        const srcDirs = { ...(src.config['dirs'] as Record<string, string>) };

        expect(post('/api/pipelines/cdr_ingest/save-as-template', { id: 'cdr_eu' }, store)?.status ?? 200).toBe(200);

        const tpl = store.get<{ config: Record<string, unknown> }>('default', PIPELINE_CONFIGS_COLL, 'cdr_eu')!;
        expect(tpl.config['template']).toBe(true);
        expect(tpl.config['active']).toBe(false);
        expect(tpl.config['id']).toBe('cdr_eu');
        expect(tpl.config['stream']).toBe('cdr_eu');

        const tplDirs = tpl.config['dirs'] as Record<string, string>;
        expect(Object.keys(tplDirs)).toEqual(Object.keys(srcDirs));
        for (const k of Object.keys(srcDirs)) {
            expect(tplDirs[k], `dirs.${k} must not be shared`).not.toBe(srcDirs[k]);
            expect(tplDirs[k]).toContain('templates/cdr_eu');
        }
        const col = (tpl.config['collector'] ?? tpl.config['source']) as Record<string, unknown>;
        expect(col['id']).toBe('cdr_eu');
    });

    it('lists the template with its flag and display name, still keyed by identity', () => {
        const store = seededStore();
        post('/api/pipelines/cdr_ingest/save-as-template', { id: 'cdr_eu', name: 'CDR (EU)' }, store);

        const rows = handler(req('GET', '/api/pipelines'), store)?.body as {
            name: string;
            template?: boolean;
            displayName?: string;
            active: boolean;
        }[];
        const row = rows.find((r) => r.name === 'cdr_eu')!;
        expect(row.template).toBe(true);
        expect(row.displayName).toBe('CDR (EU)');
        expect(row.active).toBe(false);
        // An ordinary pipeline carries neither key — the server emits them only when set.
        const plain = rows.find((r) => r.name === 'cdr_ingest')!;
        expect(plain.template).toBeUndefined();
    });
});

describe('pipelinesHandler — label relabels without moving the identity', () => {
    const handler = pipelinesHandler({ mockPipelines: true, mockStudio: true });

    it('stamps the derived id, keeps the record key, and reports stampedId once', () => {
        const store = seededStore();
        const first = handler(req('POST', '/api/pipelines/cdr_ingest/label', { name: 'CDR (EU)' }), store)?.body as {
            id: string;
            name: string;
            stampedId: boolean;
        };
        expect(first).toMatchObject({ id: 'cdr_ingest', name: 'CDR (EU)', stampedId: true });

        // Still addressable by identity — the whole point of stamping before relabelling.
        expect(handler(req('GET', '/api/pipelines/cdr_ingest/graph/raw'), store)?.status ?? 200).toBe(200);
        const rows = handler(req('GET', '/api/pipelines'), store)?.body as { name: string; displayName?: string }[];
        expect(rows.find((r) => r.name === 'cdr_ingest')?.displayName).toBe('CDR (EU)');

        const second = handler(req('POST', '/api/pipelines/cdr_ingest/label', { name: 'CDR (APAC)' }), store)?.body as {
            stampedId: boolean;
        };
        expect(second.stampedId).toBe(false); // idempotent
    });

    it('refuses an unknown pipeline (404) and a blank name (400)', () => {
        const store = seededStore();
        expect(handler(req('POST', '/api/pipelines/nope/label', { name: 'X' }), store)?.status).toBe(404);
        expect(handler(req('POST', '/api/pipelines/cdr_ingest/label', { name: '  ' }), store)?.status).toBe(400);
    });
});

describe('pipelinesHandler — rename moves the identity itself', () => {
    const handler = pipelinesHandler({ mockPipelines: true, mockStudio: true });
    const post = (url: string, body: unknown, store: MockStore) => handler(req('POST', url, body), store);

    it('moves the record key, id and name, and the old id is no longer addressable', () => {
        const store = seededStore();
        // The seed is active — deactivate first, exactly as the server's gate demands of an operator.
        const src = store.get<{ config: Record<string, unknown> }>('default', PIPELINE_CONFIGS_COLL, 'cdr_ingest')!;
        store.put('default', PIPELINE_CONFIGS_COLL, 'cdr_ingest', {
            ...src,
            config: { ...src.config, active: false },
        });
        const res = post('/api/pipelines/cdr_ingest/rename', { newId: 'cdr_eu' }, store)?.body as {
            written: boolean;
            oldId: string;
            id: string;
        };
        expect(res).toMatchObject({ written: true, oldId: 'cdr_ingest', id: 'cdr_eu' });

        expect(store.get('default', PIPELINE_CONFIGS_COLL, 'cdr_ingest')).toBeUndefined();
        expect(handler(req('GET', '/api/pipelines/cdr_ingest/graph/raw'), store)?.status).toBe(404);
        expect(handler(req('GET', '/api/pipelines/cdr_eu/graph/raw'), store)?.status ?? 200).toBe(200);
    });

    it('refuses an unknown pipeline (404), a missing/bad newId (400/422) and an active source (409)', () => {
        const store = seededStore();
        expect(post('/api/pipelines/nope/rename', { newId: 'x' }, store)?.status).toBe(404);
        expect(post('/api/pipelines/cdr_ingest/rename', {}, store)?.status).toBe(400);
        for (const bad of ['Orders EU', 'orders-eu', '_x', 'x!']) {
            expect(post('/api/pipelines/cdr_ingest/rename', { newId: bad }, store)?.status, `${bad} must be refused`).toBe(
                422,
            );
        }
        expect(post('/api/pipelines/cdr_ingest/rename', { newId: 'cdr_ingest_new' }, store)?.status).toBe(409); // active
    });

    it('refuses a taken newId (409) — the source must be inactive to reach this gate', () => {
        const store = seededStore();
        const src = store.get<{ config: Record<string, unknown> }>('default', PIPELINE_CONFIGS_COLL, 'cdr_ingest')!;
        store.put('default', PIPELINE_CONFIGS_COLL, 'cdr_ingest_draft', {
            id: 'cdr_ingest_draft',
            path: 'cdr_ingest_draft_pipeline.toon',
            config: { ...src.config, id: 'cdr_ingest_draft', name: 'cdr_ingest_draft', active: false },
            registered: true,
        });
        expect(post('/api/pipelines/cdr_ingest_draft/rename', { newId: 'cdr_ingest' }, store)?.status).toBe(409);
    });
});

/**
 * The dry-run mock used to ignore BOTH the candidate body and `sampleRows`, answering every request with
 * two canned CDR rows for every node. That is the textbook *mock more lenient than the server* trap in its
 * most misleading form: an old-vs-new output-row diff built on it would show identical rows on both sides
 * forever, and the offline preview would look correct the whole time. These pin it to the real contract.
 */
describe('pipelinesHandler — dry-run honours the candidate body and the sample', () => {
    const handler = pipelinesHandler({ mockPipelines: true, mockStudio: true });

    const candidate = (rules: Record<string, string>[]) => ({
        name: 'scratch',
        active: false,
        nodes: [
            { id: 'seed', type: 'acquisition' },
            { id: 'map', type: 'transform.map', config: { rules } },
            { id: 'sink', type: 'sink.persistent', config: { store: 'out' } },
        ],
        edges: [
            { from: 'seed', rel: 'data', to: 'map' },
            { from: 'map', rel: 'data', to: 'sink' },
        ],
    });

    /** The map step's first output relation — where the projected rows land. */
    const mapRel = (body: unknown) =>
        (body as { nodes: { node: string; relations: { rel: string; rows: unknown[] }[] }[] }).nodes.find(
            (n) => n.node === 'map',
        )!.relations[0];

    it('projects the candidate rules over the supplied sample, not canned rows', () => {
        const store = seededStore();
        const res = handler(
            req('POST', '/api/pipelines/authored/scratch/dry-run', {
                sampleRows: [{ a: '8801700000001', b: '150' }],
                pipeline: candidate([{ targetColumn: 'MSISDN', sourceExpression: 'a', transformType: 'DIRECT' }]),
            }),
            store,
        );
        expect(res?.status ?? 200).toBe(200);
        const rel = mapRel(res?.body);
        expect(rel.rows).toEqual([{ MSISDN: '8801700000001' }]);
        expect(rel.rel).toBe('data'); // the server's PipelineRel.DATA, never 'success'
    });

    it('a different rule set really produces different rows — what makes an old-vs-new diff meaningful', () => {
        const store = seededStore();
        const sampleRows = [{ a: '1', b: '2' }];
        const before = mapRel(
            handler(
                req('POST', '/api/pipelines/authored/scratch/dry-run', {
                    sampleRows,
                    pipeline: candidate([{ targetColumn: 'X', sourceExpression: 'a', transformType: 'DIRECT' }]),
                }),
                store,
            )?.body,
        ).rows;
        const after = mapRel(
            handler(
                req('POST', '/api/pipelines/authored/scratch/dry-run', {
                    sampleRows,
                    pipeline: candidate([{ targetColumn: 'X', sourceExpression: 'b', transformType: 'DIRECT' }]),
                }),
                store,
            )?.body,
        ).rows;
        expect(before).toEqual([{ X: '1' }]);
        expect(after).toEqual([{ X: '2' }]);
    });

    it('an EXPR rule yields null rather than a fabricated value (no SQL engine on this path)', () => {
        const store = seededStore();
        const res = handler(
            req('POST', '/api/pipelines/authored/scratch/dry-run', {
                sampleRows: [{ amt: '5' }],
                pipeline: candidate([{ targetColumn: 'DOUBLED', sourceExpression: 'amt * 2', transformType: 'EXPR' }]),
            }),
            store,
        );
        expect(mapRel(res?.body).rows).toEqual([{ DOUBLED: null }]);
    });

    it('a candidate for an id with no stored pipeline previews too — the server skips the lookup, so no 404', () => {
        const store = seededStore();
        const res = handler(
            req('POST', '/api/pipelines/authored/no_such_pipeline_at_all/dry-run', {
                sampleRows: [{ a: 'x' }],
                pipeline: candidate([{ targetColumn: 'A', sourceExpression: 'a', transformType: 'DIRECT' }]),
            }),
            store,
        );
        expect(res?.status ?? 200).toBe(200);
        expect(mapRel(res?.body).rows).toEqual([{ A: 'x' }]);
    });

    it('without a candidate it still falls back to the stored/lifted pipeline (W5 behaviour kept)', () => {
        const store = seededStore();
        const res = handler(
            req('POST', '/api/pipelines/authored/cdr_ingest/dry-run', { sampleRows: [{ a: '1' }] }),
            store,
        );
        expect(res?.status ?? 200).toBe(200);
        expect((res?.body as { nodes: unknown[] }).nodes.length).toBeGreaterThan(0);
    });
});
