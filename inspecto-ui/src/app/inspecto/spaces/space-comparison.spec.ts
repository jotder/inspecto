import { describe, expect, it } from 'vitest';
import type { SpaceComparisonAxis, SpaceComparisonResult } from '../api/space-comparison.service';
import {
    comparisonColumns,
    comparisonRequest,
    comparisonSummary,
    fmtGrowth,
    isTerminalRunStatus,
    notComparableList,
} from './space-comparison';

const RESULT: SpaceComparisonResult = {
    spaces: ['alpha', 'beta', 'gamma'],
    windowDays: 30,
    comparable: ['alpha', 'beta'],
    notComparable: { gamma: 'no storage_report history' },
    axes: [
        {
            axis: 'data',
            spreadBytes: 1024,
            fastest: 'beta',
            spaces: { alpha: { bytes: 1024, bytesPerDay: -10 }, beta: { bytes: 2048, bytesPerDay: 2048 } },
        },
    ],
};

describe('space comparison shaping', () => {
    it('treats every status but RUNNING as terminal', () => {
        expect(isTerminalRunStatus('RUNNING')).toBe(false);
        expect(isTerminalRunStatus(undefined)).toBe(false);
        for (const s of ['SUCCESS', 'FAILED', 'SKIPPED', 'ERROR']) expect(isTerminalRunStatus(s)).toBe(true);
    });

    it('leaves blank bounds and an empty axes list out so the server defaults apply', () => {
        expect(comparisonRequest(['a', 'b'], { windowDays: '', top: null, axes: null })).toEqual({
            spaces: ['a', 'b'],
        });
        expect(comparisonRequest(['a', 'b'], { windowDays: 0, top: 2.5, axes: [] })).toEqual({ spaces: ['a', 'b'] });
        expect(comparisonRequest(['a', 'b'], { windowDays: '14', top: 3, axes: [' data ', '', 'events'] })).toEqual({
            spaces: ['a', 'b'],
            window_days: 14,
            top: 3,
            axes: ['data', 'events'],
        });
    });

    it('signs a growth rate', () => {
        expect(fmtGrowth(2048)).toBe('+2.0 KB/day');
        expect(fmtGrowth(-10)).toBe('−10 B/day');
        expect(fmtGrowth(0)).toBe('0 B/day');
    });

    it('summarises the result and lists the Spaces left out with their reason', () => {
        expect(comparisonSummary(RESULT)).toBe('2 of 3 Spaces compared over the last 30 days.');
        expect(comparisonSummary({ ...RESULT, windowDays: 1 })).toContain('last 1 day.');
        expect(notComparableList(RESULT)).toEqual([{ space: 'gamma', reason: 'no storage_report history' }]);
    });

    it('builds a size and a growth column per COMPARED Space, read by getter', () => {
        const cols = comparisonColumns(RESULT);
        expect(cols.map((c) => c.colId)).toEqual([
            'axis',
            'spread',
            'fastest',
            'alpha:bytes',
            'alpha:rate',
            'beta:bytes',
            'beta:rate',
        ]);
        expect(cols.some((c) => c.field)).toBe(false);
        const row = RESULT.axes[0];
        const col = (id: string) => cols.find((c) => c.colId === id)!;
        const get = (id: string, data: SpaceComparisonAxis) =>
            (col(id).valueGetter as (p: { data: SpaceComparisonAxis }) => unknown)({ data });
        const fmt = (id: string, value: unknown) =>
            (col(id).valueFormatter as (p: { value: unknown }) => string)({ value });
        expect(get('beta:bytes', row)).toBe(2048);
        expect(fmt('beta:bytes', 2048)).toBe('2.0 KB');
        expect(fmt('alpha:rate', get('alpha:rate', row))).toBe('−10 B/day');
        expect(fmt('spread', get('spread', row))).toBe('1.0 KB');
        // An axis a Space lacks holds 0 bytes there (the engine's own rule).
        expect(get('alpha:bytes', { ...row, spaces: {} })).toBe(0);
    });
});
