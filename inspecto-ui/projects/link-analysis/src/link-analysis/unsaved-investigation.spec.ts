import { describe, expect, it } from 'vitest';
import type { WorkingSet } from '@inspecto/link-analysis/api/inv.service';
import { UnsavedStep, expandStepDegrees, extendExpandStep, replayStopMessage, trimExpandStep, unsavedStepLine } from './unsaved-investigation';

const RING = { direction: 'either' as const, degrees: 1, linkKinds: null, hold: true, threshold: 500 };
const STEPS: UnsavedStep[] = [
    { kind: 'seed', ids: ['a', 'b'] },
    { kind: 'expand', from: 'outer', ids: [], req: RING },
    { kind: 'expand', from: 'ids', ids: ['c'], req: { ...RING, degrees: 2, direction: 'out', linkKinds: ['call'], hold: false } },
    { kind: 'seed', ids: ['d'] },
];

describe('unsaved steps in words (LA-UNSAVED-INVESTIGATION-1)', () => {
    it('says each step as the step list shows it', () => {
        expect(STEPS.map(unsavedStepLine)).toEqual([
            'Start from 2 entities',
            'Expand 1 degree from the outer ring, both ways',
            'Expand 2 degrees from 1 entity, outgoing, call, through busy entities',
            'Start from 1 entity',
        ]);
    });

    it('names where a replay stopped and what was not recorded', () => {
        expect(replayStopMessage(STEPS, 2, 'refused', 'too wide')).toBe(
            'Saved with 1 step. Step 2 (Expand 1 degree from the outer ring, both ways) was refused: too wide. Steps 3 to 4 were not recorded.',
        );
        expect(replayStopMessage(STEPS, 4, 'refused')).toBe('Saved with 3 steps. Step 4 (Start from 1 entity) was refused.');
        expect(replayStopMessage(STEPS, 3, 'held')).toContain('Step 4 was not recorded.');
    });
});

const ws = (...ids: string[]): WorkingSet =>
    ({ entities: ids.map((id, hop) => ({ id, hop })), links: [], excluded: [], hash: String(ids.length) }) as unknown as WorkingSet;

describe('resizing the last unsaved Expand (LA-DEGREES-TRIM-1)', () => {
    const three: UnsavedStep = {
        kind: 'expand',
        from: 'outer',
        ids: [],
        req: { ...RING, degrees: 3 },
        walked: [['a'], ['b'], ['c']],
        stages: [ws('a', 'b'), ws('a', 'b', 'c'), ws('a', 'b', 'c', 'd')],
    };

    it('says how many degrees it can be resized to only when it kept what each degree drew', () => {
        expect(expandStepDegrees(three)).toBe(3);
        expect(expandStepDegrees({ ...three, stages: undefined } as UnsavedStep)).toBeNull();
        expect(expandStepDegrees(STEPS[0])).toBeNull();
        expect(expandStepDegrees(undefined)).toBeNull();
    });

    it('lowering cuts the step to its first degrees and returns the Working Set they drew, without the outer ring', () => {
        const cut = trimExpandStep(three, 2)!;
        expect(cut.step).toEqual(expect.objectContaining({ walked: [['a'], ['b']], req: expect.objectContaining({ degrees: 2 }) }));
        expect(cut.ws.entities.map((e) => e.id)).toEqual(['a', 'b', 'c']);
        expect(expandStepDegrees(cut.step)).toBe(2);
        expect(trimExpandStep(three, 1)!.ws.entities.map((e) => e.id)).toEqual(['a', 'b']);
    });

    it('cannot cut to the same size, to nothing, or a step that is not an Expand', () => {
        expect(trimExpandStep(three, 3)).toBeNull();
        expect(trimExpandStep(three, 0)).toBeNull();
        expect(trimExpandStep(STEPS[0], 1)).toBeNull();
    });

    it('raising appends the further degrees and says the new total', () => {
        const more = extendExpandStep(three, { walked: [['d']], stages: [ws('a', 'b', 'c', 'd', 'e')] });
        expect(expandStepDegrees(more)).toBe(4);
        expect(more.kind === 'expand' && more.req.degrees).toBe(4);
        expect(extendExpandStep(three, { walked: [], stages: [] })).toBe(three);
    });
});
