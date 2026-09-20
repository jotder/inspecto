import { describe, expect, it } from 'vitest';
import { G6GraphData } from './graph-types';
import { canonicalJson, fnv1a64, snapshotGraph, verifySnapshot } from './graph-snapshot';

const G: G6GraphData = {
    nodes: [
        { id: 'a', data: { label: 'A', kind: 'entity' } },
        { id: 'b', data: { label: 'B', kind: 'entity' } },
        { id: 'z', data: { label: 'Z', kind: 'entity', missing: true } }, // stranded — not evidence
    ],
    edges: [
        { id: 'ab', source: 'a', target: 'b', data: { kind: 'wire' } },
        { id: 'az', source: 'a', target: 'z', data: { kind: 'wire' } },
    ],
};
const origin = { sourceId: 'entity-projection', dataset: 'tx', query: { projection: { sourceCol: 's' } } };

describe('graph-snapshot', () => {
    it('canonical JSON is key-order independent and the fingerprint is stable', () => {
        expect(canonicalJson({ b: 1, a: [{ d: 2, c: 3 }] })).toBe('{"a":[{"c":3,"d":2}],"b":1}');
        expect(fnv1a64('')).toBe('cbf29ce484222325');
        expect(fnv1a64('a')).toBe('af63dc4c8601ec8c');
        expect(fnv1a64('hello')).toHaveLength(16);
    });

    it('freezes the non-stranded graph with a manifest hash that verifies, and drifts on any change', () => {
        const now = new Date('2026-09-20T09:41:00Z');
        const s = snapshotGraph({ title: 'Chain', graph: G, origin, metrics: { degree: { a: 2 } }, now });
        expect(s.nodes.map((n) => n.id)).toEqual(['a', 'b']);
        expect(s.edges.map((e) => e.id)).toEqual(['ab']);
        expect(s.createdAt).toBe('2026-09-20T09:41:00.000Z');
        expect(s.id.startsWith('snp-')).toBe(true);
        expect(s.attachedTo).toEqual([]);
        expect(verifySnapshot(s)).toBe(true);

        const same = snapshotGraph({ title: 'Other title', graph: G, origin, metrics: { degree: { a: 2 } }, now });
        expect(same.manifestHash).toBe(s.manifestHash); // title is not part of the evidence

        const tampered = { ...s, edges: [] };
        expect(verifySnapshot(tampered)).toBe(false);
        const other = snapshotGraph({ title: 'Chain', graph: G, origin, metrics: { degree: { a: 3 } }, now });
        expect(other.manifestHash).not.toBe(s.manifestHash);
    });
});
