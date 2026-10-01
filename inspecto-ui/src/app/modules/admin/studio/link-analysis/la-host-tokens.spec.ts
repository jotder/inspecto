import { ChangeDetectionStrategy, Component, InjectionToken, input } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { GammaConfigService } from '@gamma/services/config';
import { ToastrService } from 'ngx-toastr';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { CatalogService, InvService, PipelinesService } from 'app/inspecto/api';
import {
    LA_CATALOG,
    LA_DASHBOARD_HEADER,
    LA_DATASETS,
    LA_PIPELINE_GRAPH,
    LA_WIDGETS,
    LaCatalog,
    LaDataset,
    LaDatasets,
} from 'app/inspecto/la-host';
import { GraphSourcesService } from './graph-sources';
import { LinkAnalysisComponent } from './link-analysis.component';
import { LinkAnalysisService } from './link-analysis.service';
import { LinkViewWidgetComponent } from './link-view-widget.component';

/**
 * D-5 prep: the Link Analysis feature runs on a host-service set that is NOT the Inspecto one. Nothing here
 * imports `modules/admin/**` — the fakes below are what a second shell (the LA App) would provide.
 */

const DS: LaDataset = { id: 'fake-ds', name: 'Fake Dataset', sourceName: 'fake_store' };

@Component({
    selector: 'fake-header',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `<span data-testid="fake-header">{{ header()?.description }}</span>`,
})
class FakeHeaderComponent {
    readonly header = input<{ description?: string } | null>(null);
}

const fakeDatasets: LaDatasets = { list: () => of([DS]), get: () => of(DS) };
const fakeCatalog: LaCatalog = {
    kinds: ['dataset'],
    list: (kind) => Promise.resolve([{ kind, id: 'fake-ds', name: 'Fake Dataset', config: {} }]),
};

function fakeHost() {
    return [
        { provide: LA_DATASETS, useValue: fakeDatasets },
        { provide: LA_CATALOG, useValue: fakeCatalog },
        { provide: LA_WIDGETS, useValue: { saveWorkingSetWidget: () => of(null) } },
        { provide: LA_PIPELINE_GRAPH, useValue: { toG6Data: () => ({ nodes: [], edges: [] }), provenanceCounts: () => new Map() } },
        { provide: LA_DASHBOARD_HEADER, useValue: FakeHeaderComponent },
    ];
}

describe('Link Analysis host-service tokens', () => {
    it('the Link Analysis studio works on a fake host provider set', async () => {
        TestBed.configureTestingModule({
            imports: [LinkAnalysisComponent],
            providers: [
                ...fakeHost(),
                provideNoopAnimations(),
                provideRouter([]),
                { provide: CatalogService, useValue: {} },
                { provide: PipelinesService, useValue: { list: () => of([]) } },
                { provide: InvService, useValue: {} },
                { provide: LinkAnalysisService, useValue: { list: () => of([]) } },
                { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
                { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined, info: () => undefined } },
                {
                    provide: ActivatedRoute,
                    useValue: { snapshot: { queryParamMap: convertToParamMap({}) }, queryParamMap: of(convertToParamMap({})) },
                },
            ],
        });
        const fixture = TestBed.createComponent(LinkAnalysisComponent);
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('No graph yet');
        // the Datasets the studio offers came through the token, not a host service
        expect(fixture.componentInstance.datasets()).toEqual([DS]);
    });

    it('the component-registry GraphSource reads the catalog through the token', async () => {
        TestBed.configureTestingModule({
            providers: [
                ...fakeHost(),
                { provide: CatalogService, useValue: {} },
                { provide: PipelinesService, useValue: {} },
                { provide: InvService, useValue: {} },
            ],
        });
        const g = await TestBed.inject(GraphSourcesService).byId('component-registry')!.query({});
        expect(g.nodes.length).toBeGreaterThan(0);
    });

    it('the Link view widget draws the host-provided Dashboard header', () => {
        TestBed.configureTestingModule({
            imports: [LinkViewWidgetComponent],
            providers: [
                ...fakeHost(),
                { provide: CatalogService, useValue: {} },
                { provide: PipelinesService, useValue: {} },
                { provide: InvService, useValue: {} },
                { provide: LinkAnalysisService, useValue: { get: () => of({ id: 'v', description: 'About this view' }) } },
            ],
        });
        const fixture = TestBed.createComponent(LinkViewWidgetComponent);
        fixture.componentRef.setInput('viewId', 'v');
        fixture.componentRef.setInput('showDescription', true);
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('[data-testid="fake-header"]')?.textContent).toBe('About this view');
    });

    it.each([
        ['LA_DATASETS', LA_DATASETS],
        ['LA_WIDGETS', LA_WIDGETS],
        ['LA_CATALOG', LA_CATALOG],
        ['LA_PIPELINE_GRAPH', LA_PIPELINE_GRAPH],
        ['LA_DASHBOARD_HEADER', LA_DASHBOARD_HEADER],
    ] as [string, InjectionToken<unknown>][])('a missing %s provider fails loudly, naming the token', (name, token) => {
        TestBed.configureTestingModule({ providers: [] });
        expect(() => TestBed.inject(token)).toThrow(new RegExp(`${name} has no provider`));
    });
});
