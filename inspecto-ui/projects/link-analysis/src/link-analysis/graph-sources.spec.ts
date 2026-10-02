import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { of } from 'rxjs';
import {
    CatalogService,
    MetadataEdge,
    MetadataNode,
    PipelineGraph,
    PipelinesService,
    ProvenanceBatch,
    ProvenanceCount,
} from '@inspecto/core/api';
import { Component } from '@inspecto/core/component-model';
import { deriveComponentGraph } from '@inspecto/core/component-model';
import { toG6Data } from '@inspecto/core/graph/catalog-graph';
import { provenanceCounts, toPipelineG6Data } from 'app/modules/admin/pipelines/pipeline-graph';
import { REGISTRY_KINDS } from 'app/modules/admin/catalog/registry.component';
import { mergeGraphs } from '@inspecto/link-analysis/graph/graph-analysis';
import { InvService } from '@inspecto/link-analysis/api/inv.service';
import { LA_CATALOG, LA_DATASETS, LA_PIPELINE_GRAPH } from '@inspecto/link-analysis/la-host';
import {
    ComponentRegistryGraphSource,
    GraphSourcesService,
    LineageGraphSource,
    PipelineGraphSource,
} from './graph-sources';

const hostGraph = { toG6Data: toPipelineG6Data, provenanceCounts };

/**
 * The behavior-preserving contract (design doc §6): each GraphSource.query() must return data
 * deep-equal to its plane's existing pure mapper for the same input — proving the seam adds no logic.
 */

const metaNodes: MetadataNode[] = [
    { id: 'src1', label: 'Files', kind: 'STREAM' } as MetadataNode,
    { id: 'tbl1', label: 'cdr', kind: 'TABLE' } as MetadataNode,
];
const metaEdges: MetadataEdge[] = [
    { from: 'src1', to: 'tbl1', kind: 'FEEDS' } as MetadataEdge,
    { from: 'src1', to: 'tbl1', kind: 'EMITS' } as MetadataEdge, // parallel edge — id must stay unique
];

describe('LineageGraphSource', () => {
    it('returns exactly toG6Data() of the API graph and forwards the query', async () => {
        let seen: unknown;
        const catalog = {
            graph: (q: unknown) => {
                seen = q;
                return of({ nodes: metaNodes, edges: metaEdges });
            },
        };
        const src = new LineageGraphSource(catalog as never);
        const out = await src.query({
            from: 'src1',
            depth: 2,
            direction: 'out',
            kinds: ['STREAM'],
            edgeKinds: ['FEEDS'],
            overlay: true,
        });
        expect(out).toEqual(toG6Data(metaNodes, metaEdges));
        expect(seen).toEqual({
            from: 'src1',
            depth: 2,
            direction: 'out',
            kinds: ['STREAM'],
            edgeKinds: ['FEEDS'],
            overlay: true,
        });
    });

    it('multi-root (Phase D): queries each root and merges into one graph', async () => {
        const otherNode: MetadataNode = { id: 'src2', label: 'API', kind: 'STREAM' } as MetadataNode;
        const catalog = {
            graph: (q: { from?: string }) =>
                of(q.from === 'src2' ? { nodes: [otherNode], edges: [] } : { nodes: metaNodes, edges: metaEdges }),
        };
        const src = new LineageGraphSource(catalog as never);
        const out = await src.query({ roots: ['src1', 'src2'] });
        expect(out).toEqual(mergeGraphs([toG6Data(metaNodes, metaEdges), toG6Data([otherNode], [])]));
    });

    it('expand (Phase E): fetches a one-hop neighborhood from the clicked node', async () => {
        let seen: unknown;
        const catalog = {
            graph: (q: unknown) => {
                seen = q;
                return of({ nodes: metaNodes, edges: metaEdges });
            },
        };
        const src = new LineageGraphSource(catalog as never);
        const out = await src.expand('tbl1', 'cdr', { direction: 'out', kinds: ['TABLE'] });
        expect(out).toEqual(toG6Data(metaNodes, metaEdges));
        expect(seen).toEqual({ from: 'tbl1', depth: 1, direction: 'out', kinds: ['TABLE'], edgeKinds: undefined });
    });
});

describe('ComponentRegistryGraphSource', () => {
    const comps: Component[] = [
        {
            kind: 'dashboard',
            id: 'd1',
            name: 'Ops',
            parts: [{ partId: 'w', ref: { kind: 'widget', id: 'w1' } }],
        } as Component,
        { kind: 'widget', id: 'w1', name: 'Trend' } as Component,
    ];

    it('returns exactly deriveComponentGraph() over all listed kinds', async () => {
        const provider = {
            kinds: REGISTRY_KINDS,
            list: (kind: string) => Promise.resolve(comps.filter((c) => c.kind === kind)),
        };
        const src = new ComponentRegistryGraphSource(provider as never);
        // expected components in REGISTRY_KINDS load order, exactly as the source assembles them
        const ordered = REGISTRY_KINDS.flatMap((k) => comps.filter((c) => c.kind === k));
        expect(await src.query({})).toEqual(deriveComponentGraph({ components: ordered }));
    });

    it('a failing kind degrades to the remaining kinds (no throw)', async () => {
        const provider = {
            kinds: REGISTRY_KINDS,
            list: (kind: string) =>
                kind === 'widget'
                    ? Promise.reject(new Error('boom'))
                    : Promise.resolve(comps.filter((c) => c.kind === kind)),
        };
        const src = new ComponentRegistryGraphSource(provider as never);
        const expected = deriveComponentGraph({ components: comps.filter((c) => c.kind !== 'widget') });
        expect(await src.query({})).toEqual(expected);
    });
});

describe('PipelineGraphSource', () => {
    const graph: PipelineGraph = {
        nodes: [
            { id: 'in', label: 'Read', category: 'SOURCE' },
            { id: 'out', label: 'Write', category: 'SINK' },
        ],
        edges: [{ from: 'in', to: 'out', rel: 'data', kind: 'data' }],
    } as PipelineGraph;

    it('requires a pipeline root', async () => {
        const src = new PipelineGraphSource({} as never, hostGraph);
        await expect(src.query({})).rejects.toThrow(/pipeline/);
    });

    it('without counts returns exactly toPipelineG6Data()', async () => {
        const pipelines = { graph: () => of(graph) };
        const src = new PipelineGraphSource(pipelines as never, hostGraph);
        expect(await src.query({ from: 'p1' })).toEqual(toPipelineG6Data(graph));
    });

    it('with counts weights edges from the latest batch, identical to the mapper', async () => {
        const batches: ProvenanceBatch[] = [
            { batchId: 'b2', runTs: '2026-07-04T00:00:00Z', totalRows: 10, simulated: false },
        ];
        const rows: ProvenanceCount[] = [{ nodeId: 'in', rel: 'data', rowCount: 10, simulated: false }];
        const pipelines = {
            graph: () => of(graph),
            provenanceBatches: () => of(batches),
            provenance: () => of(rows),
        };
        const src = new PipelineGraphSource(pipelines as never, hostGraph);
        expect(await src.query({ from: 'p1', counts: true })).toEqual(toPipelineG6Data(graph, provenanceCounts(rows)));
    });

    it('with counts but no recorded batches falls back to the unweighted mapper', async () => {
        const pipelines = { graph: () => of(graph), provenanceBatches: () => of([]) };
        const src = new PipelineGraphSource(pipelines as never, hostGraph);
        expect(await src.query({ from: 'p1', counts: true })).toEqual(toPipelineG6Data(graph));
    });

    it('multi-root (Phase D): queries each pipeline and merges into one graph', async () => {
        const graph2: PipelineGraph = {
            nodes: [{ id: 'in2', label: 'Read2', category: 'SOURCE' }],
            edges: [],
        } as never;
        const pipelines = { graph: (id: string) => of(id === 'p2' ? graph2 : graph) };
        const src = new PipelineGraphSource(pipelines as never, hostGraph);
        const out = await src.query({ roots: ['p1', 'p2'] });
        expect(out).toEqual(mergeGraphs([toPipelineG6Data(graph), toPipelineG6Data(graph2)]));
    });
});

describe('GraphSourcesService - sources a shell does not have (available: false)', () => {
    function service(catalogAvailable: boolean | undefined, pipelineAvailable: boolean | undefined) {
        TestBed.configureTestingModule({
            providers: [
                { provide: CatalogService, useValue: {} },
                { provide: PipelinesService, useValue: {} },
                { provide: InvService, useValue: {} },
                { provide: LA_DATASETS, useValue: { list: () => of([]), get: () => of({}) } },
                { provide: LA_CATALOG, useValue: { available: catalogAvailable, kinds: [], list: async () => [] } },
                {
                    provide: LA_PIPELINE_GRAPH,
                    useValue: { available: pipelineAvailable, toG6Data: toPipelineG6Data, provenanceCounts },
                },
            ],
        });
        return TestBed.inject(GraphSourcesService);
    }

    it('offers every source when the host says nothing (absent = available)', () => {
        const ids = service(undefined, undefined).sources.map((s) => s.id);
        expect(ids).toEqual(expect.arrayContaining(['component-registry', 'provenance']));
        expect(ids).toHaveLength(5);
    });

    it('drops the reuse-graph and provenance sources from the picker, but byId still resolves them', () => {
        const svc = service(false, false);
        expect(svc.sources.map((s) => s.id)).not.toContain('component-registry');
        expect(svc.sources.map((s) => s.id)).not.toContain('provenance');
        expect(svc.sources).toHaveLength(3);
        expect(svc.byId('provenance')).toBeTruthy(); // a saved view may still name it
    });
});
