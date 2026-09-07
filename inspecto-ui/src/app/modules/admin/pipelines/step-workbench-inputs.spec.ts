import { describe, expect, it } from 'vitest';
import { InputGraph, inputRelations, inputSummary } from './step-workbench-inputs';

const graph: InputGraph = {
    nodes: [
        { id: 'parse', label: 'Parse CDRs' },
        { id: 'route', label: 'Route by region' },
        { id: 'eu', name: 'EU branch' },
        { id: 'shape' },
    ],
    edges: [
        { from: 'parse', rel: 'DATA', to: 'route' },
        { from: 'route', rel: 'route:eu', to: 'shape' },
        { from: 'eu', rel: 'DATA', to: 'shape' },
    ],
};

describe('inputRelations', () => {
    it('returns the inbound edges in model order, resolving the upstream label', () => {
        expect(inputRelations(graph, 'shape')).toEqual([
            { from: 'route', rel: 'route:eu', label: 'Route by region' },
            { from: 'eu', rel: 'DATA', label: 'EU branch' },
        ]);
    });

    it('falls back to the node id when the upstream declares no label or name', () => {
        expect(inputRelations({ ...graph, nodes: [{ id: 'parse' }] }, 'route')[0].label).toBe('parse');
    });

    it('is empty for a node nothing points at, and for a missing model', () => {
        expect(inputRelations(graph, 'parse')).toEqual([]);
        expect(inputRelations(null, 'shape')).toEqual([]);
        expect(inputRelations(graph, '')).toEqual([]);
    });
});

describe('inputSummary', () => {
    it('says so when nothing is connected rather than rendering an empty statement', () => {
        expect(inputSummary([])).toContain('Reads nothing yet');
    });

    it('names a single plain DATA input without the relation, which adds nothing', () => {
        expect(inputSummary(inputRelations(graph, 'route'))).toBe('Reads Parse CDRs');
        // `data` and `DATA` have both been written into the authored model.
        expect(inputSummary([{ from: 'a', rel: 'data', label: 'A' }])).toBe('Reads A');
    });

    it('NAMES the relation when it is not plain DATA — a route branch is not the same input', () => {
        expect(inputSummary([{ from: 'route', rel: 'route:eu', label: 'Route by region' }])).toBe(
            'Reads Route by region · route:eu',
        );
        expect(inputSummary([{ from: 'v', rel: 'DROPPED', label: 'Validate' }])).toBe('Reads Validate · DROPPED');
    });

    it('counts and lists a fan-in', () => {
        expect(inputSummary(inputRelations(graph, 'shape'))).toBe(
            'Reads 2 inputs: Route by region · route:eu, EU branch',
        );
    });
});
