import { describe, expect, it } from 'vitest';
import {
    datasetDescribesColumns,
    guideDone,
    NO_SPACE,
    setupIncomplete,
    SpaceGuideState,
    spaceGuideSteps,
} from './space-guide';

const ticks = (s: SpaceGuideState) => Object.fromEntries(spaceGuideSteps(s).map((x) => [x.id, x.done]));

describe('space guide rules', () => {
    it('lists the five steps in order, A list optional', () => {
        const steps = spaceGuideSteps(NO_SPACE);
        expect(steps.map((s) => s.label)).toEqual(['Space', 'Data', 'What the columns hold', 'First graph', 'A list']);
        expect(steps.filter((s) => s.optional).map((s) => s.id)).toEqual(['list']);
    });

    it('ticks nothing without a Space, even when counts leak through', () => {
        expect(ticks({ ...NO_SPACE, datasets: 2, columnsDescribed: true, views: 1, lists: 1 })).toEqual({
            space: false,
            data: false,
            columns: false,
            graph: false,
            list: false,
        });
    });

    it('ticks each step from its own server fact', () => {
        const s: SpaceGuideState = { hasSpace: true, datasets: 1, columnsDescribed: false, views: 0, lists: 0 };
        expect(ticks(s)).toEqual({ space: true, data: true, columns: false, graph: false, list: false });
        expect(ticks({ ...s, columnsDescribed: true, views: 2, lists: 1 })).toEqual({
            space: true,
            data: true,
            columns: true,
            graph: true,
            list: true,
        });
    });

    it('setup is incomplete with no Space or no Dataset (not on zero Runs)', () => {
        expect(setupIncomplete(NO_SPACE)).toBe(true);
        expect(setupIncomplete({ ...NO_SPACE, hasSpace: true })).toBe(true);
        expect(setupIncomplete({ ...NO_SPACE, hasSpace: true, datasets: 1 })).toBe(false);
    });

    it('the guide is done without the optional list, never before the required steps', () => {
        const done: SpaceGuideState = { hasSpace: true, datasets: 1, columnsDescribed: true, views: 1, lists: 0 };
        expect(guideDone(done)).toBe(true);
        expect(guideDone({ ...done, views: 0 })).toBe(false);
        expect(guideDone({ ...done, columnsDescribed: false })).toBe(false);
    });

    it('reads what the columns hold defensively (phase 2 fields may be absent or odd)', () => {
        expect(datasetDescribesColumns(undefined)).toBe(false);
        expect(datasetDescribesColumns({})).toBe(false);
        expect(datasetDescribesColumns({ columns: {} })).toBe(false); // an empty TOON block arrives as {}
        expect(datasetDescribesColumns({ columns: [{ name: 'A', classification: '' }, null] })).toBe(false);
        expect(datasetDescribesColumns({ columns: [{ name: 'A', classification: 'ACCOUNT' }] })).toBe(true);
        expect(datasetDescribesColumns({ defaultLinkMapping: { from: 'A' } })).toBe(false);
        expect(datasetDescribesColumns({ defaultLinkMapping: { from: 'A', to: 'B' } })).toBe(true);
        expect(datasetDescribesColumns({ defaultLinkMapping: 'A->B' })).toBe(false);
    });
});
