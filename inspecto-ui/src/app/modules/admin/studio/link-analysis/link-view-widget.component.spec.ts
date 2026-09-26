import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { CatalogService, ComponentDef, ComponentsService, PipelinesService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ComponentsDataProvider } from 'app/modules/admin/catalog/components-data-provider';
import { DatasetsService } from '../datasets/datasets.service';
import { G6GraphData, SUPER_NODE_KIND } from 'app/inspecto/graph';
import { nodeColor } from 'app/modules/admin/catalog/catalog-graph';
import { LinkViewWidgetComponent } from './link-view-widget.component';
import { legendItemsFor } from './link-analysis-overlays.component';

/** G6 never mounts in these paths (no data), so the host is jsdom-safe — the source query contracts are
 *  covered by graph-sources.spec. */
function create(components: Partial<ComponentsService>) {
    TestBed.configureTestingModule({
        imports: [LinkViewWidgetComponent],
        providers: [
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
