import { describe, expect, it } from 'vitest';
import {
    describeSearchAround,
    searchAroundIssues,
    searchAroundToSteps,
    stepsToSearchAround,
} from './search-around';

describe('search-around', () => {
    const spec = {
        startType: 'Person',
        conditions: [
            { direction: 'out' as const, linkKind: 'calls', neighbourType: 'Phone' },
            { direction: 'in' as const, linkKind: '  ', neighbourType: undefined },
        ],
    };

    it('compiles to motif steps with blanks as wildcards', () => {
        expect(searchAroundToSteps(spec)).toEqual([
            { nodeKind: 'Person' },
            { direction: 'out', edgeKind: 'calls', nodeKind: 'Phone' },
            { direction: 'in' },
        ]);
    });

    it('round-trips a plain path motif and refuses a temporal one', () => {
        expect(stepsToSearchAround(searchAroundToSteps(spec))!.conditions).toHaveLength(2);
        expect(stepsToSearchAround([{}, { direction: 'out', afterPrevious: true }])).toBeNull();
        expect(stepsToSearchAround([])).toBeNull();
    });

    it('requires a condition and describes in plain language', () => {
        expect(searchAroundIssues({ conditions: [] })).toHaveLength(1);
        expect(searchAroundIssues(spec)).toEqual([]);
        expect(describeSearchAround(spec)).toEqual([
            'Start from Person',
            '1. Follow outgoing calls links to Phone',
            '2. Follow incoming any links to any entity',
        ]);
    });
});
