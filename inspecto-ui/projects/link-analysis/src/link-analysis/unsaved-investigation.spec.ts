import { describe, expect, it } from 'vitest';
import { UnsavedStep, replayStopMessage, unsavedStepLine } from './unsaved-investigation';

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
