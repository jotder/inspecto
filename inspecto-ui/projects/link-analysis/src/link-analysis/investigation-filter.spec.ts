import { describe, expect, it } from 'vitest';
import { FILTER_DEFAULTS, changedGroups, quickWindow } from './investigation-filter';
import { redoRequest } from './investigation-state';
import { rungForm, rungOf } from './investigation-rung-form';

describe('investigation-filter (LA-INVESTIGATE-UX-1)', () => {
    it('an untouched filter has no changed group; each field marks only its own group', () => {
        expect(changedGroups(FILTER_DEFAULTS)).toEqual([]);
        expect(changedGroups({ ...FILTER_DEFAULTS, direction: 'out' })).toEqual(['direction']);
        expect(changedGroups({ ...FILTER_DEFAULTS, kinds: ['call'] })).toEqual(['kinds']);
        expect(changedGroups({ ...FILTER_DEFAULTS, window: 'last30', maxFanOut: '50' })).toEqual(['time', 'limits']);
        expect(changedGroups({ ...FILTER_DEFAULTS, minEvents: 3, degrees: 2 })).toEqual(['depth', 'strength']);
    });

    it('a cleared field reads as the default (blank = the pack preset applies)', () => {
        expect(changedGroups({ ...FILTER_DEFAULTS, minEvents: '', budget: '  ', typedKinds: '' })).toEqual([]);
    });

    it('a quick window counts back from the latest event, half-open so that event stays in', () => {
        expect(quickWindow(30, '2026-10-31T12:00:00Z')).toEqual({
            from: '2026-10-01T12:00:00.000Z',
            to: '2026-10-31T12:00:01.000Z',
        });
        expect(quickWindow(30, null)).toBeNull(); // never "today": an unknown end sends no window
        expect(quickWindow(7, 'not a time')).toBeNull();
    });

    it('the rung sends a quick window as its range, and leaves it out when no end is known', () => {
        const f = rungForm();
        f.controls.window.setValue('last7');
        expect(rungOf(f, undefined, '2026-10-31T00:00:00Z').window).toEqual({
            from: '2026-10-24T00:00:00.000Z',
            to: '2026-10-31T00:00:01.000Z',
        });
        expect(rungOf(f, undefined, null).window).toBeUndefined();
        f.controls.window.setValue('full');
        expect(rungOf(f).window).toBe('full');
        f.controls.window.setValue('inherit');
        expect(rungOf(f).window).toBeUndefined();
    });

    it('redo re-sends an undone step as sealed; an undo entry has nothing to redo', () => {
        const base = { step: 4, author: 'a', at: '', undoneBy: 5, workingSetHash: '', text: '' };
        expect(redoRequest({ ...base, kind: 'op', op: 'exclude', params: { ids: ['1'], reason: 'noise', note: undefined } })).toEqual({
            op: 'exclude',
            ids: ['1'],
            reason: 'noise',
        });
        expect(redoRequest({ ...base, kind: 'undo', undoes: 3 })).toBeNull();
        // a sealed null window was "all time"; the request says 'full' (a bare {op:'window'} is refused)
        expect(redoRequest({ ...base, kind: 'op', op: 'window', params: { window: null } })).toEqual({ op: 'window', window: 'full' });
        // a note on links is sealed as {source, target, kind}; the request wants lk. ids, so it is not offered again
        expect(
            redoRequest({ ...base, kind: 'op', op: 'annotate', params: { note: 'n', links: [{ source: 'a', target: 'b', kind: 'k' }] } as never }),
        ).toBeNull();
    });
});
