import { describe, expect, it } from 'vitest';
import { MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { agentHandler } from './agent.handler';

const req = (url: string, body: unknown): MockRequest => ({
    method: 'POST',
    url,
    body,
    params: {},
    space: 'default',
});

/**
 * AGT-6a A5.3 — `pipeline_author` offline.
 *
 * This spec exists because of how the two A2 bugs survived: the mock was **more lenient than the real
 * backend**, so a pane calling the tool wrongly looked correct offline and failed against every real
 * deployment. A mock that accepts what the server rejects is worse than no mock, so its strictness is
 * pinned here rather than left to the preview.
 */
describe('agentHandler · pipeline_author', () => {
    const handler = agentHandler({ mockOps: true });
    const store = new MockStore();

    const WIRED = {
        name: 'orders_flow',
        nodes: [
            { id: 'acq', type: 'acquisition' },
            { id: 'sink', type: 'sink.persistent' },
        ],
        edges: [{ from: 'acq', to: 'sink' }],
    };

    it('rejects the graph passed flat, exactly as the real tool does', () => {
        const res = handler(req('/api/agent/tools/pipeline_author', { args: WIRED }), store);
        expect(res?.status).toBe(422);
        expect(String((res?.body as { error?: string })?.error)).toContain('flow is required');
    });

    it('accepts the graph under `flow` and echoes the parsed graph back', () => {
        const res = handler(req('/api/agent/tools/pipeline_author', { args: { flow: WIRED } }), store);
        const body = res?.body as Record<string, unknown>;

        expect(res?.status ?? 200).toBe(200);
        expect(body['name']).toBe('orders_flow');
        // The adapter reads `flow` as the graph — a name string here renders as "no suggestion".
        expect(body['flow']).toMatchObject({ name: 'orders_flow' });
        expect(body['clean']).toBe(true);
    });

    it('reports a dangling edge as a finding rather than a refusal', () => {
        const flow = { ...WIRED, edges: [{ from: 'acq', to: 'warehouse' }] };
        const res = handler(req('/api/agent/tools/pipeline_author', { args: { flow } }), store);
        const body = res?.body as Record<string, unknown>;

        expect(res?.status ?? 200).toBe(200);
        expect(body['clean']).toBe(false);
        expect(body['simulated']).toBe(false);
        expect(body['findings']).toMatchObject([{ code: 'DANGLING_TO', fieldPath: 'edges' }]);
    });

    it('derives a topology from a sentence and reports the turn count', () => {
        const res = handler(
            req('/api/agent/tools/pipeline_author/derive',
                { prompt: 'collect orders, drop rows under 100 and write them to the store', args: {} }),
            store,
        );
        const body = res?.body as { value: Record<string, unknown>; derivedArgs: Record<string, unknown>; turns: number };

        expect(res?.status ?? 200).toBe(200);
        expect(body.turns).toBe(1);
        const flow = body.derivedArgs['flow'] as { nodes: { type: string }[] };
        expect(flow.nodes.map((n) => n.type)).toEqual(['acquisition', 'transform.filter', 'sink.persistent']);
        expect(body.value['clean']).toBe(true);
    });

    it('answers an unreadable sentence with a retryable 422, never an empty graph', () => {
        const res = handler(
            req('/api/agent/tools/pipeline_author/derive', { prompt: 'do something clever', args: {} }),
            store,
        );
        expect(res?.status).toBe(422);
    });
});
