import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, input } from '@angular/core';
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
    GraphLayoutId,
    GraphViewComponent,
    cappedLayout,
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
}

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

    it('a saved Radial view hands the canvas the layout node caps, so a graph over them is drawn as Degree ordered (N1)', async () => {
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
        const stub = fixture.debugElement.query(By.directive(StubGraphViewComponent)).componentInstance as StubGraphViewComponent;
        const display = stub.display() as GraphDisplayOptions;
        expect(display.layoutCaps).toEqual({ radial: 100, mds: 300 });
        // the graph view runs what cappedLayout says (graph-view-layout-cap.spec): the saved Radial is over its cap
        expect(cappedLayout(stub.layout() as GraphLayoutId, (stub.data() as G6GraphData).nodes.length, display.layoutCaps)).toBe(
            'concentric',
        );
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
