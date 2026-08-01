import { describe, expect, it } from 'vitest';
import { registerIntegrityRules } from '../integrity';
import { MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { seedDefaultSpace } from '../seeds/default-space.seed';
import { NODE_TYPES, pipelinesHandler } from './pipelines.handler';

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
        'acquisition', 'adapter',
        'parser',
        'transform.map', 'transform.filter', 'transform.select', 'transform.derive', 'transform.validate',
        'transform.dedup.marker', 'transform.dedup.fingerprint', 'transform.route', 'transform.split',
        'transform.merge', 'enrichment',
        'sink.persistent', 'sink.materialized', 'sink.view',
        'alert', 'gap', 'event',
    ];

    it('serves exactly the enum types, in enum order', () => {
        expect(NODE_TYPES.map((t) => t.type)).toEqual(ENUM_TYPES);
    });

    it('carries none of the invented types the editor used to author', () => {
        const served = new Set(NODE_TYPES.map((t) => t.type));
        for (const fiction of ['collector.file', 'collector.database', 'collector.stream', 'sink.file',
            'sink.database', 'parser.dsv', 'parser.asn1', 'transform.record', 'transform.aggregate',
            'transform.alert']) {
            expect(served.has(fiction)).toBe(false);
        }
    });

    it('models the CONTROL category the old palette omitted entirely', () => {
        const control = NODE_TYPES.filter((t) => t.category === 'CONTROL').map((t) => t.type);
        expect(control).toEqual(['alert', 'gap', 'event']);
        // CONTROL nodes are side-tasks: they consume an outcome and emit no downstream edge.
        for (const t of NODE_TYPES.filter((n) => n.category === 'CONTROL')) expect(t.emits).toEqual([]);
    });

    it('keeps the two dedup subsystems distinct rather than one flattened dedup', () => {
        const dedups = NODE_TYPES.filter((t) => t.type.startsWith('transform.dedup')).map((t) => t.type);
        expect(dedups).toEqual(['transform.dedup.marker', 'transform.dedup.fingerprint']);
    });

    it('only the parser and the router emit operator-named routes', () => {
        expect(NODE_TYPES.filter((t) => t.emitsNamedRoutes).map((t) => t.type))
            .toEqual(['parser', 'transform.route']);
    });
});

describe('pipelinesHandler — authored DELETE referential integrity (R2)', () => {
    const handler = pipelinesHandler({ mockFlows: true, mockStudio: true });

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
    const handler = pipelinesHandler({ mockFlows: true, mockStudio: true });

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
    const handler = pipelinesHandler({ mockFlows: true, mockStudio: true });

    it('lists the registered CANONICAL pipelines (config store), not the grandfathered flows', () => {
        const store = seededStore();
        const names = ((handler(req('GET', '/api/pipelines'), store)?.body as { name: string }[]) ?? []).map((p) => p.name);
        expect(names).toContain('cdr_ingest'); // the canonical config seed
    });

    it('lifts a config to the editable graph and lowers an edit back verbatim', () => {
        const store = seededStore();
        const raw = handler(req('GET', '/api/pipelines/cdr_ingest/graph/raw'), store)?.body as { nodes: { type: string }[] };
        expect(raw.nodes.some((n) => n.type === 'acquisition')).toBe(true);
        expect(raw.nodes.some((n) => n.type === 'sink.persistent')).toBe(true);
        const put = handler(req('PUT', '/api/pipelines/cdr_ingest/graph', raw), store);
        expect((put?.body as { written: boolean }).written).toBe(true);
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
        expect(handler(req('POST', '/api/pipelines/authored', { name: 'x', nodes: [], edges: [] }), store)?.status).toBe(405);
        expect(handler(req('PUT', '/api/pipelines/authored/cdr_ingest', { nodes: [], edges: [] }), store)?.status).toBe(405);
    });
});
