import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it } from 'vitest';
import { of, throwError } from 'rxjs';
import { InvService, MultiProjectionResult, RecursivePathsResult } from 'app/inspecto/api';
import { G6GraphData } from 'app/inspecto/graph';
import {
    MultiProjectionGraphSource,
    entityId,
    invErrorMessage,
    projectMultiResult,
    recursivePathsToGraph,
} from './entity-projection';

/**
 * LA-08 + LA-11 SPA half: mapping the two server answers into the studio's graph model. Every entity id is
 * minted through `entityId()` (D-S4), so the SAME entity arriving from two Datasets is ONE node.
 */
describe('projectMultiResult (LA-08)', () => {
    const res: MultiProjectionResult = {
        nodes: [
            { id: 'ACME Ltd', label: 'Acme Limited', category: 'company', __provenance_dataset: 'registry' },
            // Same entity, other Dataset, other spelling — normalises to the same key.
            { id: ' acme  ltd.', label: null, category: 'org', __provenance_dataset: 'ledger' },
        ],
        edges: [
            { source: 'acme ltd', target: 'Bob', kind: 'paid', count: 3, __provenance_dataset: 'ledger' },
            { source: 'ACME LTD', target: 'bob', kind: 'paid', count: 2, __provenance_dataset: 'wires' },
        ],
        mappings: [
            { dataset: 'registry', role: 'node', rows: 1, truncated: false },
            { dataset: 'ledger', role: 'node', rows: 1, truncated: false },
            { dataset: 'ledger', role: 'edge', rows: 1, truncated: true },
            { dataset: 'wires', role: 'edge', rows: 1, truncated: false },
        ],
        truncated: true,
    };

    it('merges one normalised key from several Datasets into ONE node, keeping every provenance', () => {
        const g = projectMultiResult(res);
        const acme = g.nodes.filter((n) => n.id === entityId(undefined, 'ACME Ltd'));
        expect(acme).toHaveLength(1);
        expect(acme[0].data.provenance).toEqual(['registry', 'ledger', 'wires']);
        // The node mapping's label column wins the display; the raw id spellings are kept for the D-S4 notice.
        expect(acme[0].data.label).toBe('Acme Limited');
        expect(acme[0].data.spellings).toEqual(['ACME Ltd', 'acme  ltd.', 'acme ltd', 'ACME LTD']);
        expect(acme[0].data.kind).toBe('company');
        expect(g.nodes).toHaveLength(2); // acme + bob — no duplicate from either spelling
    });

    it("mints every id through entityId() and folds the two Datasets' edges into one link with both provenances", () => {
        const g = projectMultiResult(res);
        const sid = entityId(undefined, 'acme ltd');
        const tid = entityId(undefined, 'Bob');
        expect(g.nodes.map((n) => n.id).sort()).toEqual([sid, tid].sort());
        expect(g.edges).toHaveLength(1);
        expect(g.edges[0]).toMatchObject({ source: sid, target: tid });
        expect(g.edges[0].data).toMatchObject({ kind: 'paid · 5', count: 5, provenance: ['ledger', 'wires'] });
    });

    it('carries the server truncated flag and the per-mapping summary through unchanged', () => {
        const g = projectMultiResult(res);
        expect(g.truncated).toBe(true);
        expect(g.mappings).toEqual(res.mappings);
    });
});

describe('recursivePathsToGraph (LA-11)', () => {
    const a = entityId(undefined, 'A');
    const b = entityId(undefined, 'B');
    const base: G6GraphData = {
        nodes: [
            { id: a, data: { label: 'A', kind: 'entity' } },
            { id: b, data: { label: 'B', kind: 'entity' } },
        ],
        edges: [{ id: `${a}->${b}:link`, source: a, target: b, data: { kind: 'link' } }],
    };
    const res: RecursivePathsResult = {
        paths: [{ nodes: ['A', 'b ', 'C'], hops: 2, weight: null }],
        truncated: true,
        edgeYieldCapped: true,
        fences: { maxDepth: 4, maxEdgeYield: 10000, timeoutMs: 5000 },
    };

    it("reuses the working set's nodes and links and adds only what the walk found beyond it", () => {
        const { graph, state } = recursivePathsToGraph(res, base);
        const c = entityId(undefined, 'C');
        expect(graph.nodes.map((n) => n.id)).toEqual([a, b, c]);
        expect(state.paths[0].nodeIds).toEqual([a, b, c]);
        // A→B already existed: highlighted, not duplicated. B→C did not: added as a path link.
        expect(state.paths[0].edgeIds[0]).toBe(`${a}->${b}:link`);
        expect(graph.edges).toHaveLength(2);
        expect(graph.edges[1]).toMatchObject({ source: b, target: c, data: { kind: 'path' } });
    });

    it('reports the fences honestly: truncated, edge-yield cap, depth limit and deepest path', () => {
        const { state } = recursivePathsToGraph(res, base);
        expect(state).toMatchObject({ truncated: true, edgeYieldCapped: true, depthLimit: 4, deepest: 2 });
    });

    it('scopes ids with the mapping entity type, so they match a type-scoped projection', () => {
        const { state } = recursivePathsToGraph(res, { nodes: [], edges: [] }, 'person');
        expect(state.paths[0].nodeIds[0]).toBe(entityId('person', 'A'));
    });
});

describe('MultiProjectionGraphSource + invErrorMessage', () => {
    const err404 = new HttpErrorResponse({ status: 404, error: { error: { message: "no dataset 'secret'" } } });

    it('turns the whole-call 404 into a readable refusal naming the server reason', async () => {
        const inv = { projectMulti: () => throwError(() => err404) } as unknown as InvService;
        const src = new MultiProjectionGraphSource(inv);
        await expect(
            src.query({ multi: { nodes: [{ dataset: 'secret', idColumn: 'id' }], edges: [] } }),
        ).rejects.toThrow(/not available to you.*no partial graph.*no dataset 'secret'/);
    });

    it('surfaces a 422 as the server message', () => {
        const e = new HttpErrorResponse({ status: 422, error: { error: { message: "unknown column 'X'" } } });
        expect(invErrorMessage(e, 'fallback')).toBe("unknown column 'X'");
    });

    it('sends the mappings plus the pushed filter, and maps the answer', async () => {
        let sent: unknown;
        const inv = {
            projectMulti: (req: unknown) => {
                sent = req;
                return of({ nodes: [], edges: [], mappings: [], truncated: false });
            },
        } as unknown as InvService;
        const filter = { kind: 'group' as const, op: 'AND' as const, items: [] };
        const multi = { nodes: [], edges: [{ dataset: 'calls', sourceColumn: 'a', targetColumn: 'b' }] };
        const g = await new MultiProjectionGraphSource(inv).query({ multi, filter });
        expect(sent).toEqual({ ...multi, filter });
        expect(g.truncated).toBe(false);
    });
});
