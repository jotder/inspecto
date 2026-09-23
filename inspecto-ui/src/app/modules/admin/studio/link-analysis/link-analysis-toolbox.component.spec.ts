import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { of } from 'rxjs';
import { ComponentDef, ComponentsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { G6GraphData } from 'app/inspecto/graph';
import { GraphEmphasis } from 'app/modules/admin/catalog/graph-view.component';
import { LinkAnalysisToolboxComponent } from './link-analysis-toolbox.component';

/** A tiny two-cluster graph: a–b–c plus d–e (mirrors the studio spec fixture). */
const GRAPH: G6GraphData = {
    nodes: ['a', 'b', 'c', 'd', 'e'].map((id) => ({
        id,
        data: { label: id.toUpperCase(), kind: id < 'd' ? 'entity' : 'other' },
    })),
    edges: [
        { id: 'a->b', source: 'a', target: 'b', data: { kind: 'link' } },
        { id: 'b->c', source: 'b', target: 'c', data: { kind: 'link' } },
        { id: 'd->e', source: 'd', target: 'e', data: { kind: 'link' } },
    ],
};

function make(graph: G6GraphData | null = GRAPH, packs: ComponentDef[] = []) {
    TestBed.configureTestingModule({
        imports: [LinkAnalysisToolboxComponent],
        providers: [
            provideNoopAnimations(),
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
            // The toolbox fetches this Space's authored pattern packs; an empty list leaves the shipped
            // built-ins in place, which is what most of these tests assert against.
            { provide: ComponentsService, useValue: { list: () => of(packs) } },
        ],
    });
    const fixture = TestBed.createComponent(LinkAnalysisToolboxComponent);
    const c = fixture.componentInstance;
    fixture.componentRef.setInput('graph', graph);
    fixture.componentRef.setInput(
        'nodeOptions',
        (graph?.nodes ?? []).map((n) => ({ id: n.id, label: n.data.label })),
    );
    fixture.componentRef.setInput('nodeKinds', ['entity', 'other']);
    fixture.componentRef.setInput('edgeKinds', ['link']);
    fixture.componentRef.setInput('labelOf', (id: string) => graph?.nodes.find((n) => n.id === id)?.data.label ?? id);
    const emphases: (GraphEmphasis | null)[] = [];
    c.emphasisChange.subscribe((e) => emphases.push(e));
    return { fixture, c, emphases, last: () => emphases[emphases.length - 1] };
}

describe('LinkAnalysisToolboxComponent', () => {
    it('shortest path: finds the hops and emits the path emphasis, else an inline error', () => {
        const { c, last } = make();
        c.pathFrom.set('a');
        c.pathTo.set('c');
        c.runPath();
        expect(c.pathResult()?.hops).toEqual(['a', 'b', 'c']);
        expect(last()?.edgeIds).toEqual(['a->b', 'b->c']);

        c.pathTo.set('e');
        c.runPath();
        expect(c.analysisError()).toMatch(/No path/);
    });

    it('explain, centrality and communities work over the graph', () => {
        const { c, last } = make();
        c.explainFor.set('b');
        c.runExplain();
        expect(c.explainText()).toContain('B (entity)');

        c.runCentrality();
        expect(c.ranking()[0].id).toBe('b'); // the a–b–c middle has the highest degree

        c.runCommunities();
        expect(c.communities()).toHaveLength(2);
        expect(last()?.groups?.get('a')).toBe(last()?.groups?.get('c'));
    });

    it('all paths + connected components partition the graph', () => {
        const { c } = make();
        c.pathFrom.set('a');
        c.pathTo.set('c');
        c.runAllPaths();
        expect(c.allPathsResult()).toHaveLength(1);
        expect(c.allPathsResult()[0].nodeIds).toEqual(['a', 'b', 'c']);

        c.pathTo.set('e');
        c.runAllPaths();
        expect(c.analysisError()).toMatch(/No path/);

        c.runConnectedComponents();
        expect(c.components()).toHaveLength(2);
        expect(c.components()[0]).toHaveLength(3); // a-b-c is the larger component, sorted first
    });

    it('communities: the Louvain method also groups the graph and paints group emphasis', () => {
        const { c, last } = make();
        c.communityMethod.set('louvain');
        c.runCommunities();
        expect(c.communities()).toHaveLength(2); // a–b–c and d–e
        expect(c.toolBadge('communities')).toBe('2 found');
        expect(last()?.groups?.get('a')).toBe(last()?.groups?.get('c'));
        expect(last()?.groups?.get('a')).not.toBe(last()?.groups?.get('d'));
    });

    it('accordion + result chips: toggleTool opens/collapses, toolBadge chips each result', () => {
        const { c } = make();
        c.toggleTool('communities');
        expect(c.tab()).toBe('communities');
        c.toggleTool('communities');
        expect(c.tab()).toBeNull();
        c.toggleTool('path');
        expect(c.tab()).toBe('path');

        expect(c.toolBadge('path')).toBe('');
        c.pathFrom.set('a');
        c.pathTo.set('c');
        c.runPath();
        expect(c.toolBadge('path')).toBe('3 hops');

        c.explainFor.set('b');
        c.runExplain();
        expect(c.toolBadge('explain')).toBe('B');

        c.runCentrality();
        expect(c.toolBadge('centrality')).toBe('top 5');

        c.runCommunities();
        expect(c.toolBadge('communities')).toBe('2 found');
    });

    it('pattern: builds a motif, matches it, gates on node kind, and focuses a match', () => {
        const { c, last } = make();

        // default motif = any start → any out-edge → any node: every out-edge (a→b, b→c, d→e)
        c.runPattern();
        expect(c.patternMatches()).toHaveLength(3);
        expect(c.toolBadge('pattern')).toBe('3 matches');
        expect(last()?.nodeIds).toEqual(expect.arrayContaining(['a', 'b', 'c', 'd', 'e']));

        // constrain the start to 'entity' (a,b,c): only a→b and b→c remain
        c.updatePatternStep(0, { nodeKind: 'entity' });
        c.runPattern();
        expect(c.patternMatches()).toHaveLength(2);

        c.addPatternStep();
        expect(c.patternSteps()).toHaveLength(3);
        c.removePatternStep(2);
        expect(c.patternSteps()).toHaveLength(2);
        c.removePatternStep(0); // never drops below one step
        expect(c.patternSteps()).toHaveLength(1);

        const first = c.patternMatches()[0];
        c.focusMatch(first);
        expect(last()?.nodeIds).toEqual(first.nodeIds);

        // a motif with no occurrence surfaces an inline message, not a blank result
        c.patternSteps.set([{ nodeKind: 'other' }, { edgeKind: 'nope', direction: 'out' }]);
        c.runPattern();
        expect(c.patternMatches()).toHaveLength(0);
        expect(c.analysisError()).toMatch(/No matches/);
    });

    it('reset() clears results but keeps presentation choices (open tab, metric, motif)', () => {
        const { c } = make();
        c.tab.set('centrality');
        c.centralityMetric.set('betweenness');
        c.pathFrom.set('a');
        c.pathTo.set('c');
        c.runPath();
        expect(c.pathResult()).not.toBeNull();

        c.reset();
        expect(c.pathResult()).toBeNull();
        expect(c.analysisError()).toBe('');
        expect(c.pathFrom()).toBe('');
        // presentation-only choices persist
        expect(c.tab()).toBe('centrality');
        expect(c.centralityMetric()).toBe('betweenness');
    });

    it('weighted path prefers strongest ties; centrality supports the extended metric set', () => {
        const wg: G6GraphData = {
            nodes: ['a', 'b', 'c'].map((id) => ({ id, data: { label: id.toUpperCase(), kind: 'entity' } })),
            edges: [
                { id: 'a->c', source: 'a', target: 'c', data: { kind: 'link' } },
                { id: 'a->b', source: 'a', target: 'b', data: { kind: 'link · 5' } },
                { id: 'b->c', source: 'b', target: 'c', data: { kind: 'link · 5' } },
            ],
        };
        const { c } = make(wg);
        c.pathFrom.set('a');
        c.pathTo.set('c');
        c.pathMetric.set('weighted');
        c.runPath();
        expect(c.pathResult()?.hops).toEqual(['a', 'b', 'c']); // strongest ties, not fewest hops
        c.pathMetric.set('hops');
        c.runPath();
        expect(c.pathResult()?.hops).toEqual(['a', 'c']);

        for (const metric of ['closeness', 'eigenvector', 'katz', 'pagerank', 'hub', 'authority'] as const) {
            c.centralityMetric.set(metric);
            c.runCentrality();
            expect(c.ranking().length).toBeGreaterThan(0);
        }
    });

    it('V2 groups: cycles, cut points, cohesion, similarity, flow and scoring all run', () => {
        // triangle a-b-c plus two pendants x,y hanging off the hub a.
        const gr: G6GraphData = {
            nodes: ['a', 'b', 'c', 'x', 'y'].map((id) => ({ id, data: { label: id.toUpperCase(), kind: 'entity' } })),
            edges: [
                { id: 'a->b', source: 'a', target: 'b', data: { kind: 'link' } },
                { id: 'b->c', source: 'b', target: 'c', data: { kind: 'link' } },
                { id: 'c->a', source: 'c', target: 'a', data: { kind: 'link' } },
                { id: 'x->a', source: 'x', target: 'a', data: { kind: 'link' } },
                { id: 'y->a', source: 'y', target: 'a', data: { kind: 'link' } },
            ],
        };
        const { c } = make(gr);

        c.runCycles();
        expect(c.cycles().length).toBeGreaterThan(0);
        expect(c.toolBadge('cycles')).toMatch(/found/);

        c.runCutPoints();
        expect(c.cutNodes()).toContain('a'); // removing the hub isolates x and y

        c.cohesionMetric.set('k-core');
        c.runCohesion();
        expect(c.cohesionRanking().length).toBeGreaterThan(0);
        c.cohesionMetric.set('cliques');
        c.runCohesion();
        expect(c.cliquesResult().some((cl) => cl.length === 3)).toBe(true); // the a-b-c triangle

        c.similarityFor.set('x');
        c.runSimilarity();
        expect(c.similarityResult()[0].id).toBe('y'); // x and y share exactly neighbor a
        c.runPrediction();
        expect(c.predictions().length).toBeGreaterThan(0);

        c.flowFrom.set('x');
        c.flowTo.set('c');
        c.runFlow();
        expect(c.flowResult()?.value).toBeGreaterThan(0);
        c.runSpanningForest();
        expect(c.spanningForest()?.edgeIds.length).toBeGreaterThan(0);

        c.runScoring();
        expect(c.suspicion()[0].id).toBe('a'); // the hub scores highest
        expect(c.suspicion()[0].score).toBeGreaterThan(0);
    });

    it('pattern packs: loading a pack fills the motif and exposes its hint', () => {
        const { c } = make();
        c.loadPatternPack('layering-chain');
        expect(c.loadedPack()?.id).toBe('layering-chain');
        expect(c.patternSteps()).toHaveLength(4); // A → B → C → D
        // editing a loaded step must not mutate the shared catalog
        c.updatePatternStep(1, { nodeKind: 'account' });
        expect(c.patternPacks().find((p) => p.id === 'layering-chain')!.steps[1].nodeKind).toBeUndefined();

        c.loadPatternPack('circular-flow');
        expect(c.loadedPack()?.tool).toBe('cycles');

        c.loadPatternPack(''); // back to a custom motif
        expect(c.loadedPack()).toBeNull();
    });

    it("pattern packs: a Space's authored packs merge over the built-ins, a malformed one is skipped", () => {
        const def = (name: string, content: Record<string, unknown>): ComponentDef => ({
            type: 'pattern-pack',
            name,
            ref: `pattern-pack/${name}`,
            content: { name, ...content },
        });
        const { c } = make(GRAPH, [
            // the persisted shape: uniform {direction} steps, the start node's being the EMPTY STRING
            def('smurfing-fan', {
                label: 'Smurfing fan-out',
                category: 'money',
                description: 'One account fans out to many.',
                steps: [{ direction: '' }, { direction: 'out' }, { direction: 'out' }],
                tool: 'cohesion',
            }),
            def('broken-pack', { label: 'No category, no steps' }),
            // reuses a built-in id — the authored one must WIN, and must not appear twice
            def('circular-flow', {
                label: 'Circular flow (house)',
                category: 'money',
                description: 'The Space overrides the shipped pack.',
                steps: [{ direction: '' }, { direction: 'out' }],
                tool: 'cycles',
            }),
        ]);

        const ids = c.patternPacks().map((p) => p.id);
        expect(ids).toContain('layering-chain'); // PACK-1: authoring a pack no longer removes the built-ins
        expect(ids).toContain('smurfing-fan'); // junk dropped, the authored pack added
        expect(ids.filter((id) => id === 'circular-flow')).toHaveLength(1); // overridden, not duplicated
        expect(c.patternPacks().find((p) => p.id === 'circular-flow')!.label).toBe('Circular flow (house)');
        c.loadPatternPack('smurfing-fan');
        expect(c.patternSteps()).toHaveLength(3);
        expect(c.patternSteps()[0].direction).toBeUndefined(); // blank start ⇒ wildcard
        expect(c.patternSteps()[1].direction).toBe('out');
        expect(c.loadedPack()?.tool).toBe('cohesion');
    });

    // ── LA-14b: the structuring pack runs through the branching matcher ──

    /** Six sub-threshold deposits into a hub, a split to two relays, a re-convergence — with times and amounts. */
    function structuringGraph(amount = 950): G6GraphData {
        const legs: [string, string, number, string][] = [
            ...['s1', 's2', 's3', 's4', 's5', 's6'].map(
                (s, i) => [s, 'hub', amount, `2026-09-01 1${i}:00:00`] as [string, string, number, string],
            ),
            ['hub', 'r1', 2800, '2026-09-01 20:00:00'],
            ['hub', 'r2', 2800, '2026-09-01 20:30:00'],
            ['r1', 'off', 2750, '2026-09-01 22:00:00'],
            ['r2', 'off', 2750, '2026-09-01 23:00:00'],
        ];
        const ids = [...new Set(legs.flatMap(([s, t]) => [s, t]))];
        return {
            nodes: ids.map((id) => ({ id, data: { label: id.toUpperCase(), kind: 'acct' } })),
            edges: legs.map(([source, target, a, at], i) => ({
                id: `tx${i}`,
                source,
                target,
                data: { kind: 'pays', attrs: { AMOUNT: String(a), BOOKED_AT: at } },
            })),
        };
    }

    it('structuring pack: shows its threshold band and finds the branching motif, labelled by layer', () => {
        const { fixture, c, last } = make(structuringGraph());
        fixture.componentRef.setInput('timeAttr', 'BOOKED_AT');
        c.tab.set('pattern');
        c.loadPatternPack('structuring');
        fixture.detectChanges();
        expect(c.branchStages()).toHaveLength(3);
        // the band is ON SCREEN, not buried in the pack
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('900 ≤ AMOUNT < 1000');

        c.runPattern();
        expect(c.analysisError()).toBe('');
        expect(c.patternMatches()).toHaveLength(1);
        expect(c.patternMatchLabel(c.patternMatches()[0])).toBe('S1, S2 +4 ⇒ HUB ⇒ R1, R2 ⇒ OFF');
        expect(last()?.nodeIds).toContain('off');

        // editing the band re-scopes the search — and never touches the shared catalog
        c.updateBranchThreshold(0, { max: 900 });
        c.runPattern();
        expect(c.patternMatches()).toHaveLength(0);
        expect(c.patternPacks().find((p) => p.id === 'structuring')!.stages![0].threshold!.max).toBe(1000);
    });

    it('structuring pack: says the legs were filtered away instead of "No matches" (§2.6 trap)', () => {
        const { fixture, c } = make(structuringGraph(6000)); // every deposit above the band, as after AMOUNT ≥ 5 000
        fixture.componentRef.setInput('timeAttr', 'BOOKED_AT');
        c.loadPatternPack('structuring');
        c.runPattern();
        expect(c.analysisError()).toContain('removed before it ran');
        expect(c.analysisError()).not.toBe('No matches for this pattern.');
    });

    it('loading a linear pack after a branching one returns the builder to linear steps', () => {
        const { c } = make();
        c.loadPatternPack('structuring');
        expect(c.branchStages()).not.toBeNull();
        c.loadPatternPack('pass-through');
        expect(c.branchStages()).toBeNull();
        expect(c.patternSteps()).toHaveLength(3);
        c.loadPatternPack('');
        expect(c.branchStages()).toBeNull();
    });

    it('renders the branching editor with no a11y violations', async () => {
        const { fixture, c } = make(structuringGraph());
        c.tab.set('pattern');
        c.loadPatternPack('structuring');
        fixture.detectChanges();
        await fixture.whenStable();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('structuring pack: offers "Run on server" ONLY on a truncated graph with an edge mapping, and emits the motif', () => {
        const { fixture, c } = make(structuringGraph());
        c.tab.set('pattern');
        c.loadPatternPack('structuring');
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        const serverButton = () =>
            [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('Run on server'));
        expect(serverButton()).toBeUndefined(); // complete graph: the browser answer is honest

        fixture.componentRef.setInput('graphTruncated', true);
        fixture.detectChanges();
        expect(serverButton()).toBeUndefined(); // truncated, but nothing to run it against

        fixture.componentRef.setInput('traversalMappings', [{ value: '0', label: 'tx: PAYER → PAYEE' }]);
        fixture.detectChanges();
        expect(el.textContent).toContain('first links a truncation cuts');
        const asked: unknown[] = [];
        c.runPatternOnServer.subscribe((r) => asked.push(r));
        serverButton()!.click();
        expect(asked).toEqual([{ mapping: 0, stages: c.branchStages() }]);
    });

    it('structuring pack: renders the server answer — matches by layer, the fence stated, a11y clean', async () => {
        const { fixture, c, last } = make(structuringGraph());
        fixture.componentRef.setInput('graphTruncated', true);
        fixture.componentRef.setInput('traversalMappings', [{ value: '0', label: 'tx: PAYER → PAYEE' }]);
        const match = {
            nodeIds: ['s1', 's2', 's3', 'hub', 'r1', 'r2', 'off'],
            edgeIds: ['tx0'],
            layers: [['s1', 's2', 's3'], ['hub'], ['r1', 'r2'], ['off']],
        };
        fixture.componentRef.setInput('serverPattern', { matches: [match], truncated: true, legCapped: false });
        c.tab.set('pattern');
        c.loadPatternPack('structuring');
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('[aria-label="Server pattern search"]')?.textContent?.replace(/\s+/g, ' ')).toContain(
            'Server: 1 match over the whole Dataset',
        );
        expect(el.textContent).toContain('The match limit cut the answer short');
        const item = el.querySelector<HTMLButtonElement>('[aria-label="Server pattern matches"] button')!;
        expect(item.textContent).toContain('S1, S2 +1 ⇒ HUB ⇒ R1, R2 ⇒ OFF');
        item.click();
        expect(last()).toEqual({ nodeIds: match.nodeIds, edgeIds: match.edgeIds });
        await fixture.whenStable();
        await expectNoA11yViolations(el);

        fixture.componentRef.setInput('serverPattern', {
            matches: [],
            truncated: false,
            legCapped: false,
            refusal: 'No link carries a numeric AMOUNT.',
        });
        fixture.detectChanges();
        expect(el.querySelector('[aria-label="Server pattern search"]')?.textContent).toContain(
            'No link carries a numeric AMOUNT.',
        );
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = make();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    // ── LA-11: server-side multi-hop traversal ──

    it('find paths (server): says what it needs until a projection offers an edge mapping', () => {
        const { fixture, c } = make();
        c.tab.set('server-paths');
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('Load an Entity/Link projection first');
    });

    it('find paths (server): emits the walk request — To optional, depth clamped to the server fence', () => {
        const { fixture, c } = make();
        fixture.componentRef.setInput('traversalMappings', [
            { value: '0', label: 'calls: A → B' },
            { value: '1', label: 'wires: X → Y' },
        ]);
        const asked: unknown[] = [];
        c.findPaths.subscribe((r) => asked.push(r));
        c.pathFrom.set('a');
        c.serverMapping.set('1');
        c.serverDepth.set(40);
        c.serverDirection.set('UNDIRECTED');
        c.runServerPaths();
        expect(asked).toEqual([{ from: 'a', to: undefined, mapping: 1, maxDepth: 10, direction: 'UNDIRECTED' }]);
    });

    it('find paths (server): states the fences honestly and lists the paths', async () => {
        const { fixture, c, last } = make();
        fixture.componentRef.setInput('traversalMappings', [{ value: '0', label: 'calls: A → B' }]);
        const path = { nodeIds: ['a', 'b', 'c'], edgeIds: ['a->b', 'b->c'] };
        fixture.componentRef.setInput('serverPaths', {
            paths: [path],
            truncated: true,
            edgeYieldCapped: true,
            depthLimit: 6,
            deepest: 2,
        });
        c.tab.set('server-paths');
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('[role="status"]')?.textContent?.replace(/\s+/g, ' ')).toContain(
            '1 path · longest 2 hops · searched up to 6 hops',
        );
        expect(el.textContent).toContain('Edge-yield cap reached');
        expect(c.toolBadge('server-paths')).toBe('1 paths · truncated');
        const item = el.querySelector<HTMLButtonElement>('[aria-label="Server paths"] button')!;
        expect(item.textContent).toContain('A → B → C');
        item.click();
        expect(last()).toEqual(path);
        await expectNoA11yViolations(el);
    });
});
