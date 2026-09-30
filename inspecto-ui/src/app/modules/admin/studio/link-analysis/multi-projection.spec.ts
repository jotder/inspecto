import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it } from 'vitest';
import { of, throwError } from 'rxjs';
import { InvService, MultiProjectionResult, RecursivePathsResult } from 'app/inspecto/api';
import { G6GraphData, entityId } from 'app/inspecto/graph';
import {
    MultiProjectionGraphSource,
    invErrorMessage,
    projectMultiResult,
    recursivePathsToGraph,
} from './entity-projection';
import { legendItemsFor } from './link-analysis-overlays.component';
import { CHART_CATEGORICAL_NEUTRAL } from 'app/inspecto/theme/chart-tokens';

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

    it('a typed value whose key normalises to empty mints no node and no edge (never a `msisdn:` super-node)', () => {
        const msisdn = { id: 'msisdn', normaliser: 'digits' as const };
        const g = projectMultiResult({
            nodes: [{ id: 'N/A', label: null, category: null, entityType: msisdn, __provenance_dataset: 'subs' }],
            edges: [
                {
                    source: 'unknown',
                    target: 'Bob',
                    kind: 'call',
                    count: 1,
                    sourceType: msisdn,
                    __provenance_dataset: 'c',
                },
                { source: '-', target: 'Bob', kind: 'call', count: 1, sourceType: msisdn, __provenance_dataset: 'c' },
            ],
            mappings: [],
            truncated: false,
        } as MultiProjectionResult);
        expect(g.nodes).toEqual([]);
        expect(g.edges).toEqual([]);
    });

    it('colours each category from the categorical palette, one colour per category; unmapped nodes stay uncoloured', () => {
        const g = projectMultiResult({
            nodes: [
                { id: 'SIM-1', label: null, category: 'SIM', __provenance_dataset: 'sims' },
                { id: 'SIM-2', label: null, category: 'SIM', __provenance_dataset: 'sims' },
                { id: 'IMEI-1', label: null, category: 'IMEI', __provenance_dataset: 'imeis' },
            ],
            edges: [{ source: 'SIM-1', target: 'CELL-9', kind: 'on', count: 1, __provenance_dataset: 'links' }],
            mappings: [],
            truncated: false,
        });
        const color = (v: string) => g.nodes.find((n) => n.id === entityId(undefined, v))!.data.color;
        expect(color('SIM-1')).toBe(CHART_CATEGORICAL_NEUTRAL[0]);
        expect(color('SIM-2')).toBe(CHART_CATEGORICAL_NEUTRAL[0]);
        expect(color('IMEI-1')).toBe(CHART_CATEGORICAL_NEUTRAL[1]);
        expect(color('CELL-9')).toBeUndefined();
        // The legend reads the stamped colour, so it matches the canvas.
        expect(legendItemsFor(g)).toEqual([
            { kind: 'SIM', count: 2, color: CHART_CATEGORICAL_NEUTRAL[0] },
            { kind: 'IMEI', count: 1, color: CHART_CATEGORICAL_NEUTRAL[1] },
            { kind: 'entity', count: 1, color: expect.any(String) },
        ]);
    });

    it('carries the server truncated flag and the per-mapping summary through unchanged', () => {
        const g = projectMultiResult(res);
        expect(g.truncated).toBe(true);
        expect(g.mappings).toEqual(res.mappings);
    });
});

describe('projectMultiResult typed rows (LA-17 D-M6)', () => {
    it('mints <type>:<key> from a row type, so a typed id in two Datasets is ONE node; untyped stays entity:', () => {
        const imei = { id: 'imei', normaliser: 'digits' as const };
        const g = projectMultiResult({
            nodes: [{ id: '35-01', label: null, category: null, __provenance_dataset: 'devices', entityType: imei }],
            edges: [
                {
                    source: 'Ann',
                    target: '3501',
                    kind: 'uses',
                    count: 1,
                    __provenance_dataset: 'calls',
                    targetType: imei,
                },
            ],
            mappings: [],
            truncated: false,
        });
        expect(g.nodes.map((n) => n.id)).toEqual(['imei:3501', 'entity:ann']);
        expect(g.nodes[0].data.provenance).toEqual(['devices', 'calls']);
        expect(g.idMappings).toEqual([{}, { sourceType: imei, targetType: imei }]);
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
        const { state } = recursivePathsToGraph(res, { nodes: [], edges: [] }, [{ entityType: 'person' }]);
        expect(state.paths[0].nodeIds[0]).toBe(entityId('person', 'A'));
    });

    it('mints typed hops: the start as a source, later hops as targets, reusing a drawn id (D-M6)', () => {
        const t = { id: 'cell', normaliser: 'upper-trim' as const };
        const drawn = { nodes: [{ id: 'cell:B', data: { label: 'b', kind: 'entity' } }], edges: [] };
        const { state } = recursivePathsToGraph(res, drawn, [{ targetType: t }]);
        expect(state.paths[0].nodeIds).toEqual(['entity:a', 'cell:B', 'cell:C']);
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
