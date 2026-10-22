import { describe, expect, it } from 'vitest';
import { NO_SPACE, SpaceGuideState } from '@inspecto/core/onboarding/space-guide';
import { ASK_FOR_A_SPACE, nextSteps } from './next-steps';

const OPEN = { canCreateSpace: true, addDataLock: null };
const space = (s: Partial<SpaceGuideState>): SpaceGuideState => ({
    hasSpace: true,
    datasets: 0,
    columnsDescribed: false,
    views: 0,
    lists: 0,
    ...s,
});
const ids = (s: SpaceGuideState, ctx = OPEN) => nextSteps(s, ctx).map((x) => x.id);

describe('nextSteps - What can I do next? (LA-ONBOARD-1 phase 8, UC-15 rule 4)', () => {
    it('with nothing yet: create a Space (or try the sample); someone who may not is told whom to ask', () => {
        expect(ids(NO_SPACE)).toEqual(['create-space', 'sample']);
        const locked = nextSteps(NO_SPACE, { canCreateSpace: false, addDataLock: null });
        expect(locked).toEqual([expect.objectContaining({ id: 'create-space', locked: ASK_FOR_A_SPACE })]);
    });

    it('an empty Space: Add data first, said why; locked with the reason when Add data is not open here', () => {
        const [first] = nextSteps(space({}), OPEN);
        expect(first).toEqual(expect.objectContaining({ id: 'add-data', to: '/add-data' }));
        expect(first.why).toContain('no Dataset');
        const lock = 'Data in this Space comes from Pipelines - ask an administrator.';
        expect(nextSteps(space({}), { canCreateSpace: false, addDataLock: lock })[0].locked).toBe(lock);
    });

    it('then follows the Space guide: the columns, the first graph, a list - at most three', () => {
        expect(ids(space({ datasets: 1 }))).toEqual(['columns', 'graph', 'list']);
        expect(ids(space({ datasets: 1, columnsDescribed: true }))).toEqual(['graph', 'list']);
        expect(ids(space({ datasets: 1, columnsDescribed: true, views: 1 }))).toEqual(['list']);
        expect(nextSteps(space({ datasets: 1 }), OPEN)[0].to).toBe('/entities?tab=columns');
    });

    it('with everything done: open a case, find something interesting, add more data', () => {
        expect(ids(space({ datasets: 2, columnsDescribed: true, views: 3, lists: 1 }))).toEqual([
            'my-work',
            'discover',
            'add-data',
        ]);
    });

    it('every step says why in one line', () => {
        for (const s of [
            NO_SPACE,
            space({}),
            space({ datasets: 1 }),
            space({ datasets: 1, columnsDescribed: true, views: 1, lists: 1 }),
        ])
            for (const step of nextSteps(s, OPEN)) expect(step.why.length).toBeGreaterThan(20);
    });
});
