import { describe, expect, it } from 'vitest';
import { WorkingSetRelation } from 'app/inspecto/api';
import { asWorkingSetBinding, driftLine, pinBinding, readFailure, rowKey, workingSetDrift } from './working-set-widget';

function rel(step: number, hash: string, rows: Record<string, unknown>[], truncated = false): WorkingSetRelation {
    return {
        id: 'case-a',
        relation: 'links',
        columns: [],
        rows,
        total: rows.length + (truncated ? 10 : 0),
        offset: 0,
        limit: 1000,
        truncated,
        head: { step, workingSetHash: hash },
        key: 'k',
        cached: false,
    };
}

describe('working-set-widget', () => {
    it('pins the head it is given, Frozen or Live', () => {
        const b = pinBinding('links', 'frozen', { step: 4, workingSetHash: 'h' }, new Date('2026-09-23T10:00:00Z'));
        expect(b).toEqual({
            relation: 'links',
            mode: 'frozen',
            pin: { step: 4, workingSetHash: 'h', pinnedAt: '2026-09-23T10:00:00.000Z' },
        });
        expect(asWorkingSetBinding(b)).toBe(b);
    });

    it('refuses a binding it cannot state: bad mode, relation or pin', () => {
        const pin = { step: 4, workingSetHash: 'h', pinnedAt: '' };
        expect(asWorkingSetBinding(undefined)).toBeNull();
        expect(asWorkingSetBinding({ relation: 'entities', mode: 'sometimes', pin })).toBeNull();
        expect(asWorkingSetBinding({ relation: 'nodes', mode: 'live', pin })).toBeNull();
        expect(asWorkingSetBinding({ relation: 'entities', mode: 'live' })).toBeNull();
        expect(asWorkingSetBinding({ relation: 'entities', mode: 'live', pin: { ...pin, step: -1 } })).toBeNull();
        expect(
            asWorkingSetBinding({ relation: 'entities', mode: 'live', pin: { ...pin, workingSetHash: '' } }),
        ).toBeNull();
    });

    it('a link is identified by source, target AND kind', () => {
        expect(rowKey('links', { source: 'a', target: 'b', kind: 'sms' })).not.toBe(
            rowKey('links', { source: 'a', target: 'b', kind: 'call' }),
        );
        expect(rowKey('entities', { entityId: 'alice' })).toBe('alice');
    });

    it('measures drift by row identity and says when the count is partial', () => {
        const pinned = rel(4, 'h4', [
            { source: 'a', target: 'b', kind: 'sms' },
            { source: 'a', target: 'c', kind: null },
        ]);
        const now = rel(6, 'h6', [
            { source: 'a', target: 'c', kind: null },
            { source: 'c', target: 'f', kind: null },
        ]);
        const d = workingSetDrift(pinned, now);
        expect(d).toMatchObject({ pinStep: 4, nowStep: 6, added: 1, removed: 1, unchanged: false, partial: false });
        expect(driftLine('links', d)).toBe(
            'Pinned step 4: 2 links · now step 6: 2 · 1 added · 1 removed since the pin',
        );

        const big = workingSetDrift(pinned, rel(6, 'h6', now.rows, true));
        expect(big.partial).toBe(true);
        expect(driftLine('links', big)).toContain('counted over the rows read');

        expect(driftLine('links', workingSetDrift(pinned, rel(9, 'h4', pinned.rows)))).toBe(
            'No change since the pin at step 4.',
        );
    });

    it('404 is "not available" (the D-E7 gate or absence), 422 a vanished pin', () => {
        expect(readFailure({ status: 404 })).toBe('unavailable');
        expect(readFailure({ status: 422 })).toBe('pin-gone');
        expect(readFailure({ status: 500 })).toBe('error');
        expect(readFailure(null)).toBe('error');
    });
});
