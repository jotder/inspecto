import { beforeEach, describe, expect, it } from 'vitest';
import { SPACE_STORAGE_KEY } from 'app/inspecto/api/spaces.service';
import type { G6GraphData } from 'app/inspecto/graph';
import { applyPipelineLayout, clearPipelineLayout, loadPipelineLayout, savePipelineLayout } from './pipeline-layout';

const DATA: G6GraphData = {
    nodes: [
        { id: 'a', data: { label: 'A', kind: 'pipeline' } },
        { id: 'b', data: { label: 'B', kind: 'pipeline' } },
    ],
    edges: [{ id: 'a->b', source: 'a', target: 'b', data: { kind: 'data' } }],
} as unknown as G6GraphData;

describe('pipeline-layout', () => {
    beforeEach(() => localStorage.clear());

    it('round-trips positions per Pipeline, and clear forgets them', () => {
        savePipelineLayout('orders', { a: [1, 2], b: [3, 4] });
        expect(loadPipelineLayout('orders')).toEqual({ a: [1, 2], b: [3, 4] });
        expect(loadPipelineLayout('calls')).toBeNull();
        clearPipelineLayout('orders');
        expect(loadPipelineLayout('orders')).toBeNull();
    });

    it('keys the layout by space, so two spaces never share one', () => {
        localStorage.setItem(SPACE_STORAGE_KEY, 'north');
        savePipelineLayout('orders', { a: [1, 2] });
        localStorage.setItem(SPACE_STORAGE_KEY, 'south');
        expect(loadPipelineLayout('orders')).toBeNull();
    });

    it('drops malformed entries and survives corrupt storage', () => {
        localStorage.setItem('inspecto.pipelines.layout.default.orders', '{"a":[1,2],"b":"x","c":[1]}');
        expect(loadPipelineLayout('orders')).toEqual({ a: [1, 2] });
        localStorage.setItem('inspecto.pipelines.layout.default.orders', '{not json');
        expect(loadPipelineLayout('orders')).toBeNull();
    });

    it('restores only when the layout covers EVERY node; stale ids are ignored', () => {
        const full = applyPipelineLayout(DATA, { a: [10, 20], b: [30, 40], gone: [0, 0] });
        expect(full?.nodes.map((n) => (n as unknown as { style: { x: number; y: number } }).style)).toEqual([
            { x: 10, y: 20 },
            { x: 30, y: 40 },
        ]);
        expect(full?.edges).toBe(DATA.edges);
        expect(applyPipelineLayout(DATA, { a: [10, 20] })).toBeNull(); // b unknown -> automatic layout
        expect(applyPipelineLayout(DATA, null)).toBeNull();
    });
});
