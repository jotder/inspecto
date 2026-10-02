import { describe, expect, it, vi } from 'vitest';
import { G6GraphData } from '@inspecto/core/graph/graph-types';
import { canonicalJson, sha256, snapshotGraph, verifySnapshot } from './graph-snapshot';

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
    it('canonical JSON is key-order independent and the hash is SHA-256 in the server format', async () => {
        expect(canonicalJson({ b: 1, a: [{ d: 2, c: 3 }] })).toBe('{"a":[{"c":3,"d":2}],"b":1}');
        expect(await sha256('')).toBe('sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
        expect(await sha256('abc')).toBe('sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
    });

    it('refuses outside a secure context instead of falling back to a weaker digest', async () => {
        vi.stubGlobal('crypto', {});
        try {
            await expect(sha256('x')).rejects.toThrow(/secure context/);
        } finally {
            vi.unstubAllGlobals();
        }
    });

    it('equals the server hash for the same content — known vector from InvestigationEvaluator.sha256(canonical(…))', async () => {
        // Keys deliberately out of order, a non-ASCII label (hashed as UTF-8, not UTF-16 units), a fraction and
        // a null. The expected value was computed by the server's own recipe: Jackson 2.21.3 with
        // ORDER_MAP_ENTRIES_BY_KEYS over the parsed body, then SHA-256 of its UTF-8 bytes, "sha256:" + hex.
        const content = {
            origin: { sourceId: 'entity-projection', dataset: 'tx', query: {} },
            predicate: null,
            metrics: { degree: { b: 1, a: 2.5 } },
            edges: [{ target: 'b', source: 'a', id: 'ab', data: { kind: 'wire' } }],
            nodes: [{ id: 'a', data: { label: 'Zoë → €', kind: 'entity' } }],
        };
        expect(await sha256(canonicalJson(content))).toBe(
            'sha256:fc3b046ff214c84e36e72308624e3ee49ca8eade1153b0750bae04024b624d27',
        );
    });

    it('freezes the non-stranded graph with a manifest hash that verifies, and drifts on any change', async () => {
        const now = new Date('2026-09-20T09:41:00Z');
        const s = await snapshotGraph({ title: 'Chain', graph: G, origin, metrics: { degree: { a: 2 } }, now });
        expect(s.nodes.map((n) => n.id)).toEqual(['a', 'b']);
        expect(s.edges.map((e) => e.id)).toEqual(['ab']);
        expect(s.createdAt).toBe('2026-09-20T09:41:00.000Z');
        expect(s.manifestHash).toMatch(/^sha256:[0-9a-f]{64}$/);
        expect(s.id).toBe(`snp-${now.getTime().toString(36)}-${s.manifestHash.slice(7, 13)}`);
        expect(s.attachedTo).toEqual([]);
        expect(await verifySnapshot(s)).toBe(true);

        const same = await snapshotGraph({
            title: 'Other title',
            graph: G,
            origin,
            metrics: { degree: { a: 2 } },
            now,
        });
        expect(same.manifestHash).toBe(s.manifestHash); // title is not part of the evidence
        expect('investigationId' in s).toBe(false);
        const anchored = await snapshotGraph({
            title: 'Chain',
            graph: G,
            origin,
            metrics: { degree: { a: 2 } },
            now,
            investigationId: 'inv-7',
        });
        expect(anchored.investigationId).toBe('inv-7');
        expect(anchored.manifestHash).toBe(s.manifestHash); // the anchor is provenance, not evidence content

        const tampered = { ...s, edges: [] };
        expect(await verifySnapshot(tampered)).toBe(false);
        const other = await snapshotGraph({ title: 'Chain', graph: G, origin, metrics: { degree: { a: 3 } }, now });
        expect(other.manifestHash).not.toBe(s.manifestHash);
    });
});
