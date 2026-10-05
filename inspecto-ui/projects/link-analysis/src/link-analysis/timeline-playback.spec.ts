import { describe, expect, it } from 'vitest';
import { G6GraphData } from '@inspecto/core/graph';
import { PLAYBACK_SLICES, activeInSlice, playbackSlice, playbackSliceCount } from './timeline-playback';

const edge = (id: string, source: string, target: string, ts?: string) => ({
    id,
    source,
    target,
    data: { kind: 'x', attrs: ts === undefined ? {} : { ts } },
});

const GRAPH = {
    nodes: ['a', 'b', 'c', 'd'].map((id) => ({ id, data: { label: id, kind: 'entity' } })),
    edges: [
        edge('e1', 'a', 'b', '2026-01-01T00:00:00Z'),
        edge('e2', 'b', 'c', '2026-01-02T00:00:00Z'),
        edge('e3', 'c', 'd', '2026-01-03T00:00:00Z'),
        edge('e4', 'a', 'd', 'not a date'),
        edge('e5', 'a', 'c'),
    ],
} as unknown as G6GraphData;

const EXTENT: [number, number] = [Date.parse('2026-01-01T00:00:00Z'), Date.parse('2026-01-03T00:00:00Z')];

describe('timeline playback slices', () => {
    it('divides the range into equal half-open slices; the last one includes the end', () => {
        expect(playbackSliceCount(EXTENT)).toBe(PLAYBACK_SLICES);
        const first = playbackSlice(EXTENT, 0);
        const last = playbackSlice(EXTENT, PLAYBACK_SLICES - 1);
        expect(first.start).toBe(EXTENT[0]);
        expect(last.end).toBe(EXTENT[1]);
        expect(last.last).toBe(true);
        expect(playbackSlice(EXTENT, 0).end).toBe(playbackSlice(EXTENT, 1).start);
        expect(playbackSlice(EXTENT, 99).index).toBe(PLAYBACK_SLICES - 1);
        expect(playbackSlice(EXTENT, -5).index).toBe(0);
    });

    it('a one-instant range is a single slice holding that instant', () => {
        const one: [number, number] = [EXTENT[0], EXTENT[0]];
        expect(playbackSliceCount(one)).toBe(1);
        expect(activeInSlice(GRAPH, 'ts', playbackSlice(one, 0)).edgeIds).toEqual(['e1']);
    });

    it('highlights only the links dated inside the slice, with their endpoints, and never mutates the graph', () => {
        const before = JSON.stringify(GRAPH);
        const first = activeInSlice(GRAPH, 'ts', playbackSlice(EXTENT, 0));
        expect(first).toEqual({ nodeIds: ['a', 'b'], edgeIds: ['e1'] });
        const mid = activeInSlice(GRAPH, 'ts', playbackSlice(EXTENT, PLAYBACK_SLICES / 2));
        expect(mid.edgeIds).toEqual(['e2']);
        const last = activeInSlice(GRAPH, 'ts', playbackSlice(EXTENT, PLAYBACK_SLICES - 1));
        expect(last.edgeIds).toEqual(['e3']);
        expect(JSON.stringify(GRAPH)).toBe(before);
    });

    it('every dated link is active in exactly one slice; undated or unparseable ones in none', () => {
        const seen: string[] = [];
        for (let i = 0; i < PLAYBACK_SLICES; i++) seen.push(...activeInSlice(GRAPH, 'ts', playbackSlice(EXTENT, i)).edgeIds);
        expect(seen.sort()).toEqual(['e1', 'e2', 'e3']);
    });
});
