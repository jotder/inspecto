import { describe, expect, it } from 'vitest';
import { hashContent } from '../../transfer/content-hash';
import { MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { bundleHandler } from './bundle.handler';
import { componentCollection } from './components.handler';
import { CONNECTIONS_COLL } from './connections.handler';

/**
 * The gates are the whole point of this handler (U-F): the UI stopped writing bundle items itself so
 * that `/bundle/import` could apply them, and a mock that wrote everything regardless would turn each
 * hard failure into a passing offline rehearsal. These tests pin the strictness, not the happy path.
 */

const req = (body: unknown): MockRequest => ({
    method: 'POST',
    url: '/api/bundle/import',
    body,
    params: {},
    space: 'default',
});

const envelope = (items: unknown[]) => ({
    format: 'inspecto-metadata-bundle',
    version: 2,
    exportedAt: '2026-08-01T00:00:00.000Z',
    sourceSpace: 'staging',
    items,
});

const item = (kind: string, id: string, content: Record<string, unknown>, extra: Record<string, unknown> = {}) =>
    ({ kind, id, content, ...extra });

interface Outcome {
    imported: number; overwritten: number; skipped: number; unchanged: number; failed: number;
    results: { kind: string; id: string; status: string; message?: string }[];
}

describe('bundleHandler — POST /bundle/import', () => {
    const handler = bundleHandler({ mockOps: true });
    const run = (body: unknown, store = new MockStore()) => ({
        store,
        res: handler(req(body), store),
    });

    it('rejects anything that is not a metadata-bundle envelope', () => {
        expect(run({ bundle: { format: 'inspecto-stream-config', items: [] } }).res?.status).toBe(422);
        expect(run({ bundle: { format: 'inspecto-metadata-bundle' } }).res?.status).toBe(422);
    });

    it('writes new items and stamps the id as authoritative', () => {
        const { store, res } = run({ bundle: envelope([item('dataset', 'orders_ds', { physicalRef: 'orders', name: 'RENAMED' })]) });
        const out = res?.body as Outcome;

        expect(out.imported).toBe(1);
        expect(out.results[0].status).toBe('imported');
        const written = store.get<Record<string, unknown>>('default', componentCollection('dataset'), 'orders_ds');
        expect(written?.['name']).toBe('orders_ds');
        expect(written?.['physicalRef']).toBe('orders');
    });

    it('defaults an existing item to skip, and overwrites only when the caller asks', () => {
        const store = new MockStore();
        store.put('default', componentCollection('dataset'), 'orders_ds', { name: 'orders_ds', physicalRef: 'old' });
        const body = { bundle: envelope([item('dataset', 'orders_ds', { physicalRef: 'new' })]) };

        const skipped = (handler(req(body), store)?.body as Outcome);
        expect(skipped.skipped).toBe(1);
        expect(skipped.results[0].message).toContain('overwrite');
        expect(store.get<Record<string, unknown>>('default', componentCollection('dataset'), 'orders_ds')?.['physicalRef'])
            .toBe('old');

        const forced = (handler(req({ ...body, actions: { 'dataset/orders_ds': 'overwrite' } }), store)?.body as Outcome);
        expect(forced.overwritten).toBe(1);
        expect(store.get<Record<string, unknown>>('default', componentCollection('dataset'), 'orders_ds')?.['physicalRef'])
            .toBe('new');
    });

    it('reports an identical re-promotion as unchanged and writes nothing', () => {
        const store = new MockStore();
        const content = { name: 'orders_ds', physicalRef: 'orders' };
        store.put('default', componentCollection('dataset'), 'orders_ds', content);

        const out = handler(req({
            bundle: envelope([item('dataset', 'orders_ds', content, {
                provenance: { contentHash: `sha256:${hashContent(content)}` },
            })]),
            // Even an explicit overwrite must not re-write identical content.
            actions: { 'dataset/orders_ds': 'overwrite' },
        }), store)?.body as Outcome;

        expect(out.unchanged).toBe(1);
        expect(out.overwritten).toBe(0);
        expect(out.results[0].status).toBe('unchanged');
    });

    it('fails a connection carrying a literal secret, but passes a ${…} reference', () => {
        const literal = handler(req({
            bundle: envelope([item('connection', 'sftp_prod', { connector: 'sftp', host: 'h', password: 'hunter2' })]),
        }), new MockStore())?.body as Outcome;
        expect(literal.failed).toBe(1);
        expect(literal.results[0].message).toContain('password');

        const { store, res } = run({
            bundle: envelope([item('connection', 'sftp_prod', {
                connector: 'sftp', host: 'h', password: '${ENV:SFTP_PW}', options: { api_token: '${ENV:TOK}' },
            })]),
        });
        expect((res?.body as Outcome).imported).toBe(1);
        expect(store.has('default', CONNECTIONS_COLL, 'sftp_prod')).toBe(true);
    });

    it('finds a literal secret nested inside options', () => {
        const out = handler(req({
            bundle: envelope([item('connection', 'kafka', { connector: 'kafka', options: { sasl_password: 'literal' } })]),
        }), new MockStore())?.body as Outcome;
        expect(out.failed).toBe(1);
        expect(out.results[0].message).toContain('options.sasl_password');
    });

    it('rejects the WHOLE bundle, writing nothing, when it would introduce a dangling reference', () => {
        const { store, res } = run({
            bundle: envelope([
                item('widget', 'w1', { name: 'w1' }, { refs: [{ kind: 'dataset', id: 'absent_ds', resolution: 'included' }] }),
                item('dataset', 'present_ds', { name: 'present_ds' }),
            ]),
        });

        expect(res?.status).toBe(422);
        expect(store.has('default', componentCollection('dataset'), 'present_ds')).toBe(false);
    });

    it('accepts a bundle whose items satisfy each other, and ignores external refs', () => {
        const out = run({
            bundle: envelope([
                item('widget', 'w1', { name: 'w1' }, { refs: [{ kind: 'dataset', id: 'ds1', resolution: 'included' }] }),
                item('dataset', 'ds1', { name: 'ds1' }),
                // `external` is the bundle's declared contract, surfaced by the requires panel — not a reject.
                item('widget', 'w2', { name: 'w2' }, { refs: [{ kind: 'dataset', id: 'lives_on_target', resolution: 'external' }] }),
            ]),
        }).res?.body as Outcome;

        expect(out.imported).toBe(3);
    });

    it('applies in dependency order so a referenced kind is written before its referencer', () => {
        const out = run({
            bundle: envelope([
                item('job', 'j1', { name: 'j1' }),
                item('widget', 'w1', { name: 'w1' }),
                item('connection', 'c1', { connector: 'sftp' }),
                item('dataset', 'd1', { name: 'd1' }),
            ]),
        }).res?.body as Outcome;

        expect(out.results.map((r) => r.kind)).toEqual(['connection', 'dataset', 'widget', 'job']);
    });

    it('skips a retired kind with a reason instead of writing it', () => {
        const { store, res } = run({ bundle: envelope([item('schema', 'orders_schema', { name: 'orders_schema' })]) });
        const out = res?.body as Outcome;

        expect(out.skipped).toBe(1);
        expect(out.results[0].message).toContain('unsupported kind');
        expect(store.has('default', componentCollection('schema'), 'orders_schema')).toBe(false);
    });

    it('fails an item with no content object rather than writing an empty artifact', () => {
        const out = run({ bundle: envelope([{ kind: 'dataset', id: 'broken' }]) }).res?.body as Outcome;
        expect(out.failed).toBe(1);
        expect(out.results[0].message).toContain('content');
    });
});
