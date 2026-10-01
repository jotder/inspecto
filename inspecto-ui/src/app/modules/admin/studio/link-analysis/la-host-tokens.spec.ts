import { ChangeDetectionStrategy, Component, InjectionToken, input, output, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { GammaConfigService } from '@gamma/services/config';
import { ToastrService } from 'ngx-toastr';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { CatalogService, InvService, PipelinesService } from 'app/inspecto/api';
import {
    LA_AI_ASSIST,
    LA_CASES,
    LA_CATALOG,
    LA_DASHBOARD_HEADER,
    LA_DATASETS,
    LA_PIPELINE_GRAPH,
    LA_TAGS,
    LA_TRANSFER,
    LA_WIDGETS,
    LaCases,
    LaCatalog,
    LaDataset,
    LaDatasets,
} from 'app/inspecto/la-host';
import { GraphSourcesService } from './graph-sources';
import { LinkAnalysisCaseFieldComponent } from './link-analysis-case-field.component';
import { LinkAnalysisSnapshotsService } from './link-analysis-snapshots.service';
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

@Component({
    selector: 'fake-transfer-menu',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    template: '',
})
class FakeTransferMenuComponent {
    readonly items = input<unknown[]>([]);
    readonly allowedKinds = input<string[] | undefined>(undefined);
    readonly importDraft = input(false);
    readonly label = input('');
    readonly changed = output<void>();
    readonly draftImported = output<unknown>();
}

@Component({ selector: 'fake-banner', standalone: true, changeDetection: ChangeDetectionStrategy.Eager, template: '' })
class FakeBannerComponent {
    readonly draft = input<unknown>(null);
    readonly stored = input<unknown>(null);
    readonly discard = output<void>();
}

@Component({ selector: 'fake-ai', standalone: true, changeDetection: ChangeDetectionStrategy.Eager, template: '' })
class FakeAiComponent {
    readonly screen = input('');
    readonly terms = input<string[]>([]);
}

@Component({ selector: 'fake-assist', standalone: true, changeDetection: ChangeDetectionStrategy.Eager, template: '' })
class FakeAssistComponent {
    readonly tool = input('');
    readonly args = input<unknown>(null);
    readonly current = input<unknown>(null);
    readonly label = input('');
    readonly disabled = input(false);
    readonly disabledReason = input('');
    readonly applyDraft = output<unknown>();
}

const fakeTags = { open: vi.fn() };
const fakeCases = (available: boolean): LaCases => ({
    available: signal(available),
    list: () => of([{ id: 'CASE-FAKE', title: 'From the fake host' }]),
    openFromEntities: () => of({ caseId: 'CASE-FAKE', memberCount: 1 }),
});

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
        { provide: LA_TAGS, useValue: fakeTags },
        { provide: LA_TRANSFER, useValue: { menu: FakeTransferMenuComponent, banner: FakeBannerComponent } },
        { provide: LA_AI_ASSIST, useValue: { assist: FakeAssistComponent, explain: FakeAiComponent } },
        { provide: LA_CASES, useValue: fakeCases(true) },
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

    it('Tags, Transfer and AI explain come from the fake host, and the slot wires their inputs and outputs', () => {
        const list = vi.fn(() => of([]));
        TestBed.configureTestingModule({
            imports: [LinkAnalysisComponent],
            providers: [
                ...fakeHost(),
                provideNoopAnimations(),
                provideRouter([]),
                { provide: CatalogService, useValue: {} },
                { provide: PipelinesService, useValue: { list: () => of([]) } },
                { provide: InvService, useValue: {} },
                { provide: LinkAnalysisService, useValue: { list } },
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
        // the host's transfer menu rendered with the inputs the studio passes
        const menu = fixture.debugElement.query(By.directive(FakeTransferMenuComponent))
            .componentInstance as FakeTransferMenuComponent;
        expect(menu.allowedKinds()).toEqual(['link-analysis-view']);
        expect(menu.importDraft()).toBe(true);
        // its `changed` output reaches the studio (reload of the saved views)
        const before = list.mock.calls.length;
        menu.changed.emit();
        expect(list.mock.calls.length).toBe(before + 1);
        // AI explain rendered by the same slot
        const explain = fixture.debugElement
            .queryAll(By.directive(FakeAiComponent))
            .map((d) => d.componentInstance as FakeAiComponent);
        expect(explain.map((e) => e.screen())).toContain('Link Analysis');
        // Tags go through the token, not a host dialog
        fixture.componentInstance.openTags({ id: 'v1', name: 'View one' } as never);
        expect(fakeTags.open).toHaveBeenCalledWith({
            targetKind: 'link-analysis-view',
            targetId: 'v1',
            label: 'View one',
        });
    });

    it.each([
        [false, ['CASE-2026-0318', 'CASE-2026-0322']],
        [true, ['CASE-FAKE']],
    ])('the Case field follows the host: available=%s offers %j', (available, ids) => {
        const store = {
            mockCases: [
                { id: 'CASE-2026-0318', title: 'p' },
                { id: 'CASE-2026-0322', title: 'p' },
            ],
        };
        TestBed.configureTestingModule({
            imports: [LinkAnalysisCaseFieldComponent],
            providers: [
                provideNoopAnimations(),
                { provide: LA_CASES, useValue: fakeCases(available) },
                { provide: LinkAnalysisSnapshotsService, useValue: store },
            ],
        });
        const fixture = TestBed.createComponent(LinkAnalysisCaseFieldComponent);
        fixture.detectChanges();
        expect(fixture.componentInstance.cases().map((c) => c.id)).toEqual(ids);
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
        ['LA_TAGS', LA_TAGS],
        ['LA_TRANSFER', LA_TRANSFER],
        ['LA_AI_ASSIST', LA_AI_ASSIST],
        ['LA_CASES', LA_CASES],
    ] as [string, InjectionToken<unknown>][])('a missing %s provider fails loudly, naming the token', (name, token) => {
        TestBed.configureTestingModule({ providers: [] });
        expect(() => TestBed.inject(token)).toThrow(new RegExp(`${name} has no provider`));
    });
});
