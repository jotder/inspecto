import { describe, expect, it } from 'vitest';
import { G6GraphData } from '@inspecto/core/graph';
import {
    AGE_FADE_FLOOR,
    PLAYBACK_SLICES,
    activeInSlice,
    ageOpacity,
    playbackSlice,
    playbackSliceCount,
    playbackWindow,
    sealedWindow,
} from './timeline-playback';

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
        for (let i = 0; i < PLAYBACK_SLICES; i++)
            seen.push(...activeInSlice(GRAPH, 'ts', playbackSlice(EXTENT, i)).edgeIds);
        expect(seen.sort()).toEqual(['e1', 'e2', 'e3']);
    });

    it('a window reaches back from the handle, never before the data starts', () => {
        const last = playbackSlice(EXTENT, PLAYBACK_SLICES - 1);
        expect(playbackWindow(EXTENT, last, 'slice')).toBe(last);
        expect(playbackWindow(EXTENT, last, '1d').start).toBe(Date.parse('2026-01-02T00:00:00Z'));
        expect(playbackWindow(EXTENT, last, '30d').start).toBe(EXTENT[0]);
        expect(activeInSlice(GRAPH, 'ts', playbackWindow(EXTENT, last, 'all')).edgeIds).toEqual(['e1', 'e2', 'e3']);
    });

    it('fades by age from 1 at the handle to the floor at the window start', () => {
        const range = playbackWindow(EXTENT, playbackSlice(EXTENT, PLAYBACK_SLICES - 1), 'all');
        const o = ageOpacity(GRAPH, 'ts', range, ['e1', 'e2', 'e3', 'e4']);
        expect(o.get('e3')).toBe(1);
        expect(o.get('e2')).toBeCloseTo(1 - (1 - AGE_FADE_FLOOR) / 2, 1);
        expect(o.get('e1')).toBe(AGE_FADE_FLOOR);
        expect(o.has('e4')).toBe(false); // no parseable date
    });

    it('seals a window as a half-open [from, to) that keeps the end of the last slice', () => {
        expect(sealedWindow(playbackSlice(EXTENT, 0))).toEqual({
            from: '2026-01-01T00:00:00.000Z',
            to: new Date(playbackSlice(EXTENT, 0).end).toISOString(),
        });
        expect(sealedWindow(playbackSlice(EXTENT, PLAYBACK_SLICES - 1)).to).toBe('2026-01-03T00:00:00.001Z');
    });

    it('seals exactly the whole milliseconds the highlight holds when slice bounds are fractional', () => {
        const odd: [number, number] = [0, 1001]; // 1001 ms / 20 slices: every inner bound is fractional
        for (let i = 0; i < PLAYBACK_SLICES; i++) {
            const slice = playbackSlice(odd, i);
            const w = sealedWindow(slice);
            const from = Date.parse(w.from);
            const to = Date.parse(w.to);
            for (let t = 0; t <= 1001; t++) {
                const lit = t >= slice.start && (slice.last ? t <= slice.end : t < slice.end);
                expect(t >= from && t < to, `slice ${i} at ${t} ms`).toBe(lit);
            }
        }
    });
});
