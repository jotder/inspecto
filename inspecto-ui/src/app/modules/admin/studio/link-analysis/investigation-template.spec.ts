import { describe, expect, it } from 'vitest';
import { InvestigationLogEntry } from 'app/inspecto/api';
import { templatePreview } from './investigation-template';

const e = (step: number, op: InvestigationLogEntry['op'], ids: string[], extra: Partial<InvestigationLogEntry> = {}) =>
    ({
        step,
        kind: 'op',
        op,
        params: { ids },
        author: 'a',
        at: '',
        undoneBy: null,
        workingSetHash: '',
        text: '',
        ...extra,
    }) as InvestigationLogEntry;

describe('templatePreview (the D-E8 extraction, previewed before a write-once save)', () => {
    it('makes seeds parameters, drops case ops with counts only, and generalises named expands', () => {
        const p = templatePreview([
            e(1, 'seed', ['a', 'b'], { params: { ids: ['a', 'b'], entityType: 'msisdn' } }),
            e(2, 'expand', ['a']),
            e(3, 'expand', []),
            e(4, 'exclude', ['x', 'y'], { params: { ids: ['x', 'y'], reason: 'hub' } }),
            e(5, 'keep', ['a']),
            e(6, 'seed', ['c']),
        ]);
        expect(p.parameters).toEqual([
            { name: 'seed1', kind: 'seed', entityType: 'msisdn', step: 1 },
            { name: 'seed2', kind: 'seed', entityType: null, step: 6 },
        ]);
        expect(p.generalised).toEqual([{ step: 2, namedFrontier: 1 }]);
        expect(p.dropped).toEqual([
            { step: 4, op: 'exclude', count: 2 },
            { step: 5, op: 'keep', count: 1 },
        ]);
    });

    it('LA-13: a window step becomes a kind:window parameter defaulting to the authored window, numbered apart from seeds', () => {
        const w = { from: '2026-01-01T00:00:00Z', to: null, slot: null, days: null, timezone: null };
        const p = templatePreview([
            e(1, 'seed', ['a']),
            { ...e(2, 'window', []), params: { window: w } },
            { ...e(3, 'window', []), params: { window: null } },
            e(4, 'seed', ['b']),
        ]);
        expect(p.parameters).toEqual([
            { name: 'seed1', kind: 'seed', entityType: null, step: 1 },
            { name: 'window1', kind: 'window', default: w, step: 2 },
            { name: 'window2', kind: 'window', default: null, step: 3 },
            { name: 'seed2', kind: 'seed', entityType: null, step: 4 },
        ]);
        expect(p.dropped).toEqual([]);
    });

    it('ignores undone ops and undo entries — only the effective log is templated', () => {
        const p = templatePreview([
            e(1, 'seed', ['a']),
            e(2, 'hide', ['a'], { undoneBy: 3 }),
            { ...e(3, undefined, []), kind: 'undo', undoes: 2 },
        ]);
        expect(p.dropped).toEqual([]);
        expect(p.parameters.length).toBe(1);
    });
});
