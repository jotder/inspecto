import { describe, expect, it } from 'vitest';
import type { IndexSource, RecursivePathsResult } from '@inspecto/link-analysis/api/inv.service';
import { recursivePathsToGraph } from './entity-projection';
import {
    FALLBACK_REASON_TEXT,
    combineSources,
    datasetSourceNote,
    indexSourceNote,
    readSourceNote,
    stepReadSuffix,
} from './index-source';
import { expandSourceNote } from './investigation-state';

/** The closed list `IndexedRead.Reason` - a new server reason must be added here AND to the words map. */
const CLOSED_REASONS = [
    'index_disabled',
    'no_index',
    'mapping_not_indexed',
    'column_not_indexed',
    'time_zone_not_servable',
    'filter_not_indexed',
    'index_stale_refused',
    'depth_over_index_cap',
    'frontier_over_index_cap',
    'index_read_failed',
    'rung_not_indexable',
];

describe('index source wording (DR-U4)', () => {
    it('has plain words for every reason of the closed list, none of them the raw code', () => {
        expect(Object.keys(FALLBACK_REASON_TEXT).sort()).toEqual([...CLOSED_REASONS].sort());
        for (const r of CLOSED_REASONS) {
            const note = datasetSourceNote(r);
            expect(note.startsWith('Answered from the Dataset because ')).toBe(true);
            expect(note).not.toContain(r);
        }
    });

    it('says which link index version answered, and warns when it was stale', () => {
        expect(indexSourceNote({ kind: 'index', version: 7, stale: false })).toBe('Answered from the link index v7.');
        expect(indexSourceNote({ kind: 'index', version: 7, stale: true, staleReason: 'files added' })).toBe(
            'Answered from the link index v7 (stale: files added).',
        );
    });

    it('says why the Dataset answered, keeping the server details, and shows an unknown code verbatim', () => {
        expect(indexSourceNote({ kind: 'dataset', reason: 'no_index' })).toBe(
            'Answered from the Dataset because this Dataset has no link index yet.',
        );
        expect(indexSourceNote({ kind: 'dataset', reason: 'index_read_failed', details: 'IOException' })).toContain(
            '(IOException)',
        );
        expect(indexSourceNote({ kind: 'dataset', reason: 'brand_new_reason' })).toContain('brand_new_reason');
    });

    it('says nothing when the server said nothing', () => {
        expect(indexSourceNote(undefined)).toBeNull();
        expect(readSourceNote(undefined)).toBeNull();
        expect(readSourceNote({})).toBeNull();
    });

    it('reads a sealed expand: index = the index answered, fallback = the Dataset did', () => {
        expect(expandSourceNote({ index: { version: 2, stale: false } })).toBe('Answered from the link index v2.');
        expect(expandSourceNote({ fallback: { reason: 'depth_over_index_cap' } })).toContain(
            'Answered from the Dataset because the walk is deeper',
        );
    });

    it('suffixes a log step line only when the link index answered it', () => {
        expect(stepReadSuffix({ index: { version: 5 } })).toBe('read from link index v5');
        expect(stepReadSuffix({})).toBeNull();
        expect(stepReadSuffix(undefined)).toBeNull();
    });

    it('a fan-out of reads is only "from the link index" when EVERY read was; else the Dataset reason wins', () => {
        const a: IndexSource = { kind: 'index', version: 4, stale: false };
        const b: IndexSource = { kind: 'index', version: 3, stale: true };
        const d: IndexSource = { kind: 'dataset', reason: 'no_index' };
        expect(combineSources([a, b])).toEqual(b); // the OLDEST version read
        expect(combineSources([a, d, b])).toEqual(d);
        expect(combineSources([undefined, undefined])).toBeUndefined();
    });

    it('carries the server `source` of a path search onto the paths state', () => {
        const source: IndexSource = { kind: 'dataset', reason: 'filter_not_indexed' };
        const res: RecursivePathsResult = {
            paths: [],
            truncated: false,
            edgeYieldCapped: false,
            fences: { maxDepth: 6, maxEdgeYield: 1, timeoutMs: 1 },
            source,
        };
        expect(recursivePathsToGraph(res, { nodes: [], edges: [] }).state.source).toEqual(source);
        expect(recursivePathsToGraph({ ...res, source: undefined }, { nodes: [], edges: [] }).state.source).toBeUndefined();
    });
});
