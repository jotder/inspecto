import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, input, output } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { CatalogService, ComponentDef, ComponentsService, PipelinesService, apiUrl } from '@inspecto/core/api';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { ComponentsDataProvider } from 'app/modules/admin/catalog/components-data-provider';
import { DatasetsService } from 'app/modules/admin/studio/datasets/datasets.service';
import { G6GraphData, GraphSource } from '@inspecto/core/graph';
import {
    GraphDisplayOptions,
    GraphViewComponent,
} from '@inspecto/core/graph/graph-view.component';
import { By } from '@angular/platform-browser';
import { SUPER_NODE_KIND } from '@inspecto/link-analysis/graph/graph-analysis';
import { nodeColor } from '@inspecto/core/graph/catalog-graph';
import { LinkViewWidgetComponent } from './link-view-widget.component';
import { legendItemsFor } from './link-analysis-overlays.component';
import { provideLaHostServices } from 'app/modules/admin/studio/la-host.providers';
import { GraphSourcesService } from './graph-sources';
import { LA_LIMIT_FALLBACKS, laLimit, resetLaLimits } from './la-limits';

/** G6 never mounts in these paths (no data), so the host is jsdom-safe — the source query contracts are
 *  covered by graph-sources.spec. */
function create(components: Partial<ComponentsService>) {
    TestBed.configureTestingModule({
        imports: [LinkViewWidgetComponent],
        providers: [
            ...provideLaHostServices(),
            provideNoopAnimations(),
            { provide: ComponentsService, useValue: components },
            { provide: CatalogService, useValue: {} },
            { provide: PipelinesService, useValue: {} },
            { provide: ComponentsDataProvider, useValue: {} },
            { provide: DatasetsService, useValue: {} },
        ],
    });
    return TestBed.createComponent(LinkViewWidgetComponent);
}

const viewDef = (content: Record<string, unknown>): ComponentDef => ({
    type: 'link-analysis-view',
    name: 'v1',
    ref: 'link-analysis-view/v1',
    content,
});

describe('LinkViewWidgetComponent', () => {
    it('shows the unbound empty state (accessible) when no viewId is set', async () => {
        const fixture = create({});
        fixture.detectChanges();
        expect(fixture.componentInstance.loaded()).toBe(true);
        expect(fixture.nativeElement.textContent).toContain('No saved Link-Analysis view bound');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('shows the empty state when the saved view does not exist (mock GET returns null)', () => {
        const fixture = create({ get: () => of(null as unknown as ComponentDef) });
        fixture.componentRef.setInput('viewId', 'missing');
        fixture.detectChanges();
        expect(fixture.componentInstance.view()).toBeNull();
        expect(fixture.nativeElement.textContent).toContain('No saved Link-Analysis view bound');
    });

    it('surfaces an unknown graph source as an inline warning', () => {
        const fixture = create({ get: () => of(viewDef({ name: 'V', sourceId: 'bogus', query: {} })) });
        fixture.componentRef.setInput('viewId', 'v1');
        fixture.detectChanges();
        expect(fixture.componentInstance.error()).toContain('Unknown graph source');
    });

    it('surfaces a failed view fetch as an inline warning', () => {
        const fixture = create({ get: () => throwError(() => new Error('down')) });
        fixture.componentRef.setInput('viewId', 'v1');
        fixture.detectChanges();
        expect(fixture.componentInstance.error()).toContain('Could not load the saved view');
    });

    // R3-04: a Menu-embedded view showed no description and no legend although its TOON set `view: legend: true`.
    it('shows the view description when the host asks for it', async () => {
        const fixture = create({
            get: () => of(viewDef({ name: 'V', description: 'SIM-box ring, Jeddah', sourceId: 'bogus', query: {} })),
        });
        fixture.componentRef.setInput('viewId', 'v1');
        fixture.componentRef.setInput('showDescription', true);
        fixture.detectChanges();
        const header = fixture.nativeElement.querySelector('[data-testid="dashboard-header"]') as HTMLElement;
        expect(header?.textContent?.trim()).toBe('SIM-box ring, Jeddah');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('shows no description on a dashboard tile (the tile card owns its own title)', () => {
        const fixture = create({
            get: () => of(viewDef({ name: 'V', description: 'SIM-box ring, Jeddah', sourceId: 'bogus', query: {} })),
        });
        fixture.componentRef.setInput('viewId', 'v1');
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).not.toContain('SIM-box ring, Jeddah');
    });

    it('opens the legend by default, as the studio restores a view without the flag', () => {
        const fixture = create({ get: () => of(viewDef({ name: 'V', sourceId: 'bogus', query: {} })) });
        fixture.componentRef.setInput('viewId', 'v1');
        fixture.detectChanges();
        expect(fixture.componentInstance.legendOpen()).toBe(true);
    });

    it('minimises the legend when the view saved `legend: false`', () => {
        const content = { name: 'V', sourceId: 'bogus', query: {}, view: { legend: false } };
        const fixture = create({ get: () => of(viewDef(content)) });
        fixture.componentRef.setInput('viewId', 'v1');
        fixture.detectChanges();
        expect(fixture.componentInstance.legendOpen()).toBe(false);
    });

    it("lists the drawn graph's kinds in the view's own colours, as the studio does", () => {
        const fixture = create({});
        const c = fixture.componentInstance;
        const graph: G6GraphData = {
            nodes: [
                { id: 'a', data: { kind: 'sim', label: 'SIM 1' } },
                { id: 'b', data: { kind: 'sim', label: 'SIM 2' } },
                { id: 'c', data: { kind: 'device', label: 'IMEI 1' } },
            ],
            edges: [{ id: 'e', source: 'a', target: 'c', data: { kind: 'uses · 2' } }],
        } as G6GraphData;
        c.view.set({
            id: 'v',
            name: 'v',
            sourceId: 'entity-projection',
            query: {},
            display: { nodeColors: { sim: 'x' } } as never,
        });
        c.data.set(graph);
        expect(c.legendItems()).toEqual([
            { kind: 'sim', count: 2, color: 'x' },
            { kind: 'device', count: 1, color: nodeColor('device' as never) },
        ]);
        expect(c.edgeKinds()).toEqual(['uses']);
    });
});

/** Stands in for G6, which cannot mount in jsdom: the widget's own template around it is what these specs read. */
@Component({ selector: 'inspecto-graph-view', standalone: true, template: '' })
class StubGraphViewComponent {
    readonly data = input<unknown>(null);
    readonly display = input<unknown>(null);
    readonly layout = input<unknown>(null);
    readonly fill = input(false);
    readonly emphasis = input<unknown>(null);
    readonly interaction = input<unknown>(null);
    readonly plugins = input<unknown>(null);
    readonly selection = input<string[] | null>(null);
    readonly selectionChange = output<string[]>();
    readonly nodeClick = output<string>();
}

// LA-STUDIO-UI-REORG-1 §5.13: the read-only toolset of a dashboard tile. Session only - nothing is saved or sent.
describe('LinkViewWidgetComponent — the read-only toolset', () => {
    const LIMITS = apiUrl('/settings/link-analysis/limits');
    const graph = {
        nodes: [
            { id: 'a', data: { kind: 'sim', label: 'SIM A' } },
            { id: 'b', data: { kind: 'sim', label: 'SIM B' } },
            { id: 'c', data: { kind: 'device', label: 'Phone C' } },
        ],
        edges: [
            { id: 'ab', source: 'a', target: 'b', data: { kind: 'calls', attrs: { at: '2026-01-05' } } },
            { id: 'bc', source: 'b', target: 'c', data: { kind: 'uses', attrs: { at: '2026-03-05' } } },
        ],
    } as unknown as G6GraphData;
    afterEach(() => resetLaLimits());

    async function setUp(allowExport = false) {
        resetLaLimits();
        TestBed.configureTestingModule({
            imports: [LinkViewWidgetComponent],
            providers: [
                ...provideLaHostServices(),
                provideNoopAnimations(),
                provideHttpClient(withXhr()),
                provideHttpClientTesting(),
                {
                    provide: ComponentsService,
                    useValue: { get: () => of(viewDef({ name: 'V', sourceId: 'entity-projection', query: {} })) },
                },
                { provide: CatalogService, useValue: {} },
                { provide: PipelinesService, useValue: {} },
                { provide: ComponentsDataProvider, useValue: {} },
                { provide: DatasetsService, useValue: {} },
            ],
        });
        TestBed.overrideComponent(LinkViewWidgetComponent, {
            remove: { imports: [GraphViewComponent] },
            add: { imports: [StubGraphViewComponent] },
        });
        TestBed.inject(GraphSourcesService).byId = () =>
            ({ id: 'entity-projection', query: async () => graph }) as unknown as GraphSource;
        const http = TestBed.inject(HttpTestingController);
        const fixture = TestBed.createComponent(LinkViewWidgetComponent);
        fixture.componentRef.setInput('viewId', 'v1');
        fixture.componentRef.setInput('allowExport', allowExport);
        fixture.detectChanges();
        http.expectOne(LIMITS).flush({});
        await new Promise((resolve) => setTimeout(resolve, 0));
        fixture.detectChanges();
        const stub = () =>
            fixture.debugElement.query(By.directive(StubGraphViewComponent)).componentInstance as StubGraphViewComponent;
        return { fixture, c: fixture.componentInstance, stub, http };
    }

    it('searches, selects with a details pop-over, filters and runs Shortest path, with no further request', async () => {
        const { fixture, c, stub, http } = await setUp();
        const el: HTMLElement = fixture.nativeElement;
        expect(el.querySelector('[data-testid="la-widget-toolbar"]')).not.toBeNull();

        c.searchText.set('phone');
        c.search();
        fixture.detectChanges();
        expect(stub().selection()).toEqual(['c']);
        expect(el.querySelector('[data-testid="la-widget-details"]')?.textContent).toContain('Phone C');

        c.toggleKind('device');
        fixture.detectChanges();
        expect((stub().data() as G6GraphData).nodes.map((n) => n.id)).toEqual(['a', 'b']);
        expect(el.querySelector('[data-testid="la-widget-details"]')).toBeNull(); // the selected entity is filtered out
        c.toggleKind('device');

        c.timeColumn.set('at');
        c.setCutoff('2026-02-01');
        fixture.detectChanges();
        expect((stub().data() as G6GraphData).edges.map((e) => e.id)).toEqual(['ab']);
        c.setCutoff('');

        expect(c.analysisBlocked('shortestPath')).toBe('Select exactly two entities first.');
        stub().selectionChange.emit(['a', 'c']);
        c.analyse('shortestPath');
        fixture.detectChanges();
        expect(stub().emphasis()).toEqual({ nodeIds: ['a', 'b', 'c'], edgeIds: ['ab', 'bc'] });
        expect(el.querySelector('[data-testid="la-widget-result"]')?.textContent).toContain('Shortest path: 2 link(s).');
        http.verify(); // nothing beyond the limits read: no write, no server run
        await expectNoA11yViolations(el);
    });

    it('hides the selection for this visit only, and switches Canvas modes', async () => {
        const { fixture, c, stub } = await setUp();
        stub().nodeClick.emit('a');
        c.hideSelected();
        fixture.detectChanges();
        expect((stub().data() as G6GraphData).nodes.map((n) => n.id)).toEqual(['b', 'c']);
        expect(fixture.nativeElement.textContent).toContain('Show hidden (1)');
        c.canvasMode.set('fisheye');
        fixture.detectChanges();
        expect(stub().plugins()).toEqual({ fisheye: true, edgeFilterLens: false });
        expect((stub().interaction() as { panByDrag: boolean }).panByDrag).toBe(false);
    });

    it('offers export only when the sharer allowed it', async () => {
        const off = await setUp(false);
        expect(off.fixture.nativeElement.querySelector('[data-testid="la-widget-export"]')).toBeNull();
    });

    it('shows the export menu when the sharer allowed it', async () => {
        const on = await setUp(true);
        expect(on.fixture.nativeElement.querySelector('[data-testid="la-widget-export"]')).not.toBeNull();
    });
});

// LA-CONFIG-LIMITS-RESIDUALS-1 (5): a dashboard tile projected with the SPA fallbacks, never the Space's limits.
describe('LinkViewWidgetComponent — the Space limits', () => {
    const LIMITS = apiUrl('/settings/link-analysis/limits');
    /** The node cap each projection ran under, in call order. */
    let capsSeen: number[];
    let truncateAnswer: boolean;

    beforeEach(() => {
        resetLaLimits();
        capsSeen = [];
        truncateAnswer = true;
    });
    afterEach(() => resetLaLimits());
    /** Lets the widget's limits → query → data promise chain run. */
    const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

    function setUp() {
        TestBed.configureTestingModule({
            imports: [LinkViewWidgetComponent],
            providers: [
                ...provideLaHostServices(),
                provideNoopAnimations(),
                provideHttpClient(withXhr()),
                provideHttpClientTesting(),
                {
                    provide: ComponentsService,
                    useValue: { get: () => of(viewDef({ name: 'V', sourceId: 'entity-projection', query: {} })) },
                },
                { provide: CatalogService, useValue: {} },
                { provide: PipelinesService, useValue: {} },
                { provide: ComponentsDataProvider, useValue: {} },
                { provide: DatasetsService, useValue: {} },
            ],
        });
        TestBed.overrideComponent(LinkViewWidgetComponent, {
            remove: { imports: [GraphViewComponent] },
            add: { imports: [StubGraphViewComponent] },
        });
        const source: Partial<GraphSource> = {
            id: 'entity-projection',
            query: async () => {
                capsSeen.push(laLimit('projection_node_cap'));
                return {
                    nodes: [{ id: 'a', data: { kind: 'sim', label: 'SIM 1' } }],
                    edges: [],
                    truncated: truncateAnswer,
                } as G6GraphData;
            },
        };
        const sources = TestBed.inject(GraphSourcesService);
        sources.byId = () => source as GraphSource;
        return TestBed.inject(HttpTestingController);
    }

    function bound() {
        const fixture = TestBed.createComponent(LinkViewWidgetComponent);
        fixture.componentRef.setInput('viewId', 'v1');
        fixture.detectChanges();
        return fixture;
    }

    it("projects under the Space's node cap, read before the query runs, and says when it cut the graph", async () => {
        const http = setUp();
        const fixture = bound();
        expect(capsSeen).toEqual([]); // waits for the limits
        http.expectOne(LIMITS).flush({ items: [{ key: 'projection_node_cap', inForce: 7 }] });
        await settle();
        fixture.detectChanges();
        expect(capsSeen).toEqual([7]);
        expect(fixture.componentInstance.truncated()).toBe(true);
        expect(fixture.nativeElement.textContent).toContain(
            'The projection hit the node cap — narrow the dataset or filter to see everything.',
        );
    });

    it('falls back to the shipped default when the limits read fails, and says nothing when nothing was cut', async () => {
        truncateAnswer = false;
        const http = setUp();
        const fixture = bound();
        http.expectOne(LIMITS).flush({}, { status: 500, statusText: 'Server Error' });
        await settle();
        fixture.detectChanges();
        expect(capsSeen).toEqual([LA_LIMIT_FALLBACKS.projection_node_cap]);
        expect(fixture.componentInstance.error()).toBeNull();
        expect(fixture.nativeElement.textContent).not.toContain('hit the node cap');
    });

    it('a saved Radial view over its node cap opens in the default layout and says why (N1, UIR-D11)', async () => {
        truncateAnswer = false;
        const http = setUp();
        const fixture = bound();
        http.expectOne(LIMITS).flush({});
        await settle();
        const c = fixture.componentInstance;
        c.view.set({ id: 'v', name: 'V', sourceId: 'entity-projection', query: {}, layout: 'radial' });
        const big = {
            nodes: Array.from({ length: 101 }, (_, i) => ({ id: 'n' + i, data: { kind: 'sim', label: 'n' + i } })),
            edges: [],
        } as unknown as G6GraphData;
        c.data.set(big);
        fixture.detectChanges();
        const stub = fixture.debugElement.query(By.directive(StubGraphViewComponent))
            .componentInstance as StubGraphViewComponent;
        const display = stub.display() as GraphDisplayOptions;
        expect(display.layoutCaps).toEqual({ radial: 100, mds: 300, 'force-cluster': 200 });
        expect(fixture.nativeElement.querySelector('[data-testid="la-layout-cap-notice"]')?.textContent).toContain(
            'Radial layout needs at most 100 nodes (this graph has 101), so the default layout is shown.',
        );
        expect(stub.layout()).toBeNull(); // the graph view's default layout, never the over-cap Radial
    });

    it('reads the limits once for a dashboard of many tiles', async () => {
        const http = setUp();
        const tiles = [bound(), bound(), bound()];
        http.expectOne(LIMITS).flush({ items: [{ key: 'projection_node_cap', inForce: 7 }] });
        await settle();
        expect(tiles.every((t) => t.componentInstance.data() !== null)).toBe(true);
        expect(capsSeen).toEqual([7, 7, 7]);
        http.expectNone(LIMITS);
    });
});

describe('legendItemsFor', () => {
    it('never counts a super-node as a kind', () => {
        const g = {
            nodes: [
                { id: 'a', data: { kind: 'entity' } },
                { id: 's', data: { kind: SUPER_NODE_KIND } },
            ],
            edges: [],
        } as unknown as G6GraphData;
        expect(legendItemsFor(g).map((i) => i.kind)).toEqual(['entity']);
        expect(legendItemsFor(null)).toEqual([]);
    });
});
