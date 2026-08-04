import { describe, expect, it } from 'vitest';
import { MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { onboardingHandler, PIPELINE_CONFIGS_COLL, StoredPipelineConfig } from './onboarding.handler';

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
    const res = handler(req('POST', '/api/config/write', {
        type: 'pipeline',
        config: {
            name,
            dirs: { poll: 'in', database: 'out' },
            parsing: { delimiter: ';' },
            collector: {
                connector: 'local', discovery: 'poll', connection: 'old_conn',
                duplicate: { mode: 'checksum', algorithm: 'xxh64' },
            },
            processing: { threads: 1 },
        },
    }), store);
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
        expect(handler(req('POST', '/api/config/patch', { type: 'pipeline', name: 'x', patch: 'scalar' }), store)?.status).toBe(400);
    });

    it('404s an unknown config type', () => {
        const store = new MockStore();
        expect(handler(req('POST', '/api/config/patch', { type: 'nonsense', name: 'x', patch: {} }), store)?.status).toBe(404);
    });

    it('404s a missing target — patch never creates a file', () => {
        const store = new MockStore();
        const res = handler(req('POST', '/api/config/patch',
            { type: 'pipeline', name: 'never_written', patch: { collector: { discovery: 'watch' } } }), store);
        expect(res?.status).toBe(404);
        expect(store.get('default', PIPELINE_CONFIGS_COLL, 'never_written')).toBeUndefined();
    });

    it('409s a patch that changes the identity field', () => {
        const store = new MockStore();
        writePipeline(store, 'stable');
        const res = handler(req('POST', '/api/config/patch',
            { type: 'pipeline', name: 'stable', patch: { name: 'renamed' } }), store);
        expect(res?.status).toBe(409);
        expect(storedConfig(store, 'stable')['name']).toBe('stable');
    });

    /** The anti-clobber regression the route exists for. */
    it('merges the patched block server-side, leaving sibling blocks and unnamed keys intact', () => {
        const store = new MockStore();
        writePipeline(store, 'orders');
        const res = handler(req('POST', '/api/config/patch',
            { type: 'pipeline', name: 'orders', patch: { collector: { discovery: 'watch' } } }), store);
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
        const res = handler(req('POST', '/api/config/patch',
            { type: 'pipeline', name: 'cleared', patch: { collector: { connection: null } } }), store);
        expect(res?.status ?? 200).toBe(200);

        const collector = storedConfig(store, 'cleared')['collector'] as Record<string, unknown>;
        expect('connection' in collector).toBe(false);
        expect(collector['connector']).toBe('local');
    });
});
