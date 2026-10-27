import { describe, expect, it } from 'vitest';
import { buildBehaviors } from '@inspecto/core/graph/graph-view.component';
import { CANVAS_MODES, canvasInteractionFor, canvasModeForKey, canvasModeOptions } from './canvas-mode';

const types = (b: unknown[]) => b.map((x) => (typeof x === 'string' ? x : (x as { type: string }).type));
const key = (k: string, mods: KeyboardEventInit = {}) => new KeyboardEvent('keydown', { key: k, ...mods });

describe('Canvas modes (LA-STUDIO-UI-REORG-1 UIR-D3)', () => {
    it('offers exactly the four modes, Pan first', () => {
        expect(CANVAS_MODES.map((m) => m.id)).toEqual(['pan', 'lasso', 'fisheye', 'linkFilter']);
    });

    it('Pan: a plain drag pans, Shift+drag lassos, no rectangle box', () => {
        const b = buildBehaviors(canvasInteractionFor('pan'), null, () => undefined);
        expect(types(b)).toContain('drag-canvas');
        expect(types(b)).not.toContain('brush-select');
        const lassos = (b as Record<string, unknown>[]).filter((x) => x['type'] === 'lasso-select');
        expect(lassos.map((l) => l['trigger'])).toEqual([['shift'], ['shift', 'alt']]);
        const pan = (b as Record<string, unknown>[]).find((x) => x['type'] === 'drag-canvas')!;
        const enable = pan['enable'] as (e: { targetType?: string; shiftKey?: boolean }) => boolean;
        expect(enable({ targetType: 'canvas' })).toBe(true);
        expect(enable({ targetType: 'canvas', shiftKey: true })).toBe(false); // Shift belongs to the lasso
    });

    it('Lasso: a plain drag lassos and never pans', () => {
        const b = buildBehaviors(canvasInteractionFor('lasso'), null, () => undefined) as Record<string, unknown>[];
        expect(types(b)).not.toContain('drag-canvas');
        expect(b.filter((x) => x['type'] === 'lasso-select').map((l) => l['trigger'])).toContainEqual(['drag']);
    });

    it('Fisheye and Link filter own the drag: no pan, no lasso to fight the lens', () => {
        for (const mode of ['fisheye', 'linkFilter'] as const) {
            const b = buildBehaviors(canvasInteractionFor(mode), { behaviors: ['hover-activate'] }, () => undefined);
            expect(types(b), mode).not.toContain('drag-canvas');
            expect(types(b), mode).not.toContain('lasso-select');
            expect(types(b), mode).not.toContain('brush-select');
            expect(canvasInteractionFor(mode).shiftClickToggle, mode).toBe(true); // Shift+click still selects
        }
    });

    it('Alt+Shift lasso removes: the selected state is dropped from every lassoed node', () => {
        const got: [string[], string][] = [];
        const b = buildBehaviors(canvasInteractionFor('pan'), null, () => undefined, {}, (ids, mode) => got.push([ids, mode]));
        const remove = (b as Record<string, unknown>[]).find((x) => x['key'] === 'lasso-remove')!;
        const states: Record<string, string[]> = { a: ['active'], b: ['selected', 'active'] }; // G6 'diff' flipped a on
        (remove['onSelect'] as (s: Record<string, string[]>) => void)(states);
        expect(states).toEqual({ a: ['active'], b: ['active'] });
        expect(got).toEqual([[['a', 'b'], 'remove']]);
    });

    it('a saved view: the brush and the old lasso toggle go, Fisheye wins over Link filter', () => {
        expect(
            canvasModeOptions({ behaviors: ['brush-select', 'lasso-select', 'hover-activate'], fisheye: true, edgeFilterLens: true }),
        ).toEqual({ behaviors: ['hover-activate'], fisheye: true, edgeFilterLens: false });
    });

    it('keys: L lasso, F fisheye, Esc back to Pan only from another mode', () => {
        expect(canvasModeForKey(key('l'), 'pan')).toBe('lasso');
        expect(canvasModeForKey(key('F'), 'pan')).toBe('fisheye');
        expect(canvasModeForKey(key('Escape'), 'fisheye')).toBe('pan');
        expect(canvasModeForKey(key('Escape'), 'pan')).toBeNull(); // Esc still clears the selection in Pan
        expect(canvasModeForKey(key('l', { ctrlKey: true }), 'pan')).toBeNull();
    });
});
