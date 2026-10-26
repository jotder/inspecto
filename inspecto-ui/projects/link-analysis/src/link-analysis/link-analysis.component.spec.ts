import { DomainProfileService } from '@inspecto/link-analysis/api/domain-profile.service';
import { installDomainProfiles, profileFromPack } from '@inspecto/link-analysis/graph/domain-profile';
import { TELECOM_PACK_FIXTURE } from '@inspecto/link-analysis/graph/domain-profile.testing';
import { LA_FEATURES } from '@inspecto/link-analysis/la-host';
import type { LaFeatures, LaImportDraft } from '@inspecto/link-analysis/la-host';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { Component, EventEmitter, Input, Output, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { MatTooltip } from '@angular/material/tooltip';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { DatasetRowsService } from '@inspecto/core/viz/dataset-rows.service';
import { MatDialog } from '@angular/material/dialog';
import { MatMenuTrigger } from '@angular/material/menu';
import { HttpErrorResponse } from '@angular/common/http';
import { BehaviorSubject, delay, of, throwError } from 'rxjs';
import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ToastrService } from 'ngx-toastr';
import { InvService, MaskingNote, RecursivePathsRequest } from '@inspecto/link-analysis/api/inv.service';
import { EntityProfileService, EntityTypeSummary } from '@inspecto/link-analysis/api/entity-profile.service';
import { PipelinesService } from '@inspecto/core/api';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { ElementDetailData } from '@inspecto/link-analysis/investigation/element-detail.dialog';
import { G6GraphData, GraphSource } from '@inspecto/core/graph';
import type { ConditionGroup } from '@inspecto/core/query/query-types';
import { GeoLinkBrushService } from '@inspecto/link-analysis/graph/geo-link-brush';
import { Dataset } from 'app/modules/admin/studio/datasets/dataset-types';
import { DatasetsService } from 'app/modules/admin/studio/datasets/datasets.service';
import { GraphViewComponent } from '@inspecto/core/graph/graph-view.component';
import { GraphSourcesService } from './graph-sources';
import { LinkAnalysisSettingsService } from './link-analysis-settings.service';
import { CANVAS_MIN_PX, LinkAnalysisComponent, defaultLayoutFor, snapshotTitle } from './link-analysis.component';
import { ConditionalReadCache } from '@inspecto/link-analysis/api/conditional-read-cache';
import { GRAPH_CACHE_TTL_MS, PUSH_FILTER_DEBOUNCE_MS } from './graph-query-cache';
import { SpacesService } from '@inspecto/core/api';
import { LinkAnalysisQueryPanelComponent } from './link-analysis-query-panel.component';
import { LinkAnalysisService, LinkAnalysisView } from './link-analysis.service';
import { provideLaHostServices } from 'app/modules/admin/studio/la-host.providers';
import { configureLaLimits, laLimit, resetLaLimits } from '@inspecto/link-analysis/link-analysis/la-limits';
import { SessionService } from 'app/inspecto/auth/session.service';
import { LinkAnalysisIdentitiesComponent } from '@inspecto/link-analysis/link-analysis/link-analysis-identities.component';

/** LA-EDITIONS-1: these specs drive the Investigation surface, which the host reports present on Standard and above. */
const STANDARD_EDITION_FEATURES = {
    ops: signal(false),
    exchange: signal(false),
    geoLink: signal(true),
    investigations: signal(true),
};

const DS: Dataset = {
    id: 'links-ds',
    name: 'Links',
    kind: 'physical',
    sourceName: 'links',
    columns: [],
    measures: [],
    calculated: [],
};

const TEST_COLOR = '#ff0000'; // a literal test value passed to setNodeColor(), not app styling — ds-allow

/** A tiny two-cluster graph: a–b–c plus d–e. */
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

/** Stands in for the G6 host (which cannot instantiate in jsdom) — same selector and inputs. */
@Component({ selector: 'inspecto-graph-view', standalone: true, template: '' })
class StubGraphView {
    @Input() data: G6GraphData | null = null;
    @Input() fill = false;
    @Input() emphasis: unknown;
    @Input() display: unknown;
    @Input() tooltips = false;
    @Input() entityIcons = false;
    @Input() textHint = '';
    @Input() layout: unknown;
    @Input() plugins: unknown;
    @Input() interaction: unknown;
    @Input() selection: unknown;
    @Output() nodeClick = new EventEmitter<string>();
    @Output() edgeClick = new EventEmitter<string>();
    @Output() nodeDblClick = new EventEmitter<string>();
    @Output() selectionChange = new EventEmitter<string[]>();
    @Output() nodeContextMenu = new EventEmitter<{ id: string; x: number; y: number }>();
}

// The Toolbox dock remembers an explicit open/close in sessionStorage; no test may inherit another's.
beforeEach(() => sessionStorage.clear());

function create(
    opts: {
        fail?: boolean;
        views?: LinkAnalysisView[];
        expand?: GraphSource['expand'];
        graph?: G6GraphData;
        /** What the fake source returns when the query carries a `filter` (the stage-2 push). */
        filtered?: G6GraphData;
        queryParams?: Record<string, string>;
        /** LA-11: a stand-in for the traversal route (the real service is used when absent). */
        inv?: Partial<InvService>;
        /** Swap the G6 host for an inert stand-in, so the template can render with a graph in jsdom. Default ON: the real G6 host throws an unhandled clearRect under jsdom. */
        stubGraph?: boolean;
        /** "Investigate a number": a stand-in for the Dataset reads (latest event time, column probe). */
        rows?: Partial<DatasetRowsService>;
        /** The host's module flags (default: Standard and above - the Investigation surface present). */
        features?: LaFeatures;
        /** The Space's Datasets (default: the one `links-ds`). */
        datasets?: Dataset[];
        /** `GET /inv/entity-types` (default: unreadable - the Start box then knows the Datasets' primary types only). */
        entityTypes?: EntityTypeSummary[];
    } = {},
) {
    const queried: unknown[] = [];
    const fakeSource: GraphSource = {
        id: 'entity-projection',
        label: 'Entity/Link (from a Dataset)',
        query: (q) => {
            queried.push(q);
            if (opts.fail) return Promise.reject(new Error('bad mapping'));
            const q2 = q as { filter?: unknown };
            return Promise.resolve(q2.filter && opts.filtered ? opts.filtered : (opts.graph ?? GRAPH));
        },
        expand: opts.expand,
    };
    const save = vi.fn((v: LinkAnalysisView) => of(v));
    TestBed.configureTestingModule({
        imports: [LinkAnalysisComponent],
        providers: [
            ...provideLaHostServices(),
            { provide: LA_FEATURES, useValue: opts.features ?? STANDARD_EDITION_FEATURES },
            provideNoopAnimations(),
            provideRouter([]),
            // the packs are installed above; the real service would read the server and fall back to generic
            { provide: DomainProfileService, useValue: { state: signal(null), ready: () => Promise.resolve() } },
            { provide: GraphSourcesService, useValue: { sources: [fakeSource], byId: () => fakeSource } },
            { provide: DatasetsService, useValue: { list: () => of(opts.datasets ?? [DS]) } },
            {
                provide: EntityProfileService,
                useValue: {
                    entityTypes: () =>
                        // async, as over HTTP: a deep-linked start must wait for it (the live drive caught the race)
                        opts.entityTypes
                            ? of({ items: opts.entityTypes }).pipe(delay(5))
                            : throwError(() => new Error('no registry')),
                    suggestEntities: () => throwError(() => new Error('no type-ahead')),
                },
            },
            { provide: PipelinesService, useValue: { list: () => of([]) } },
            ...(opts.rows ? [{ provide: DatasetRowsService, useValue: opts.rows }] : []),
            ...(opts.inv
                ? [
                      {
                          provide: InvService,
                          useValue: {
                              masking: signal(null),
                              reads: new ConditionalReadCache(() => ''),
                              listEntityLists: () => of({ lists: [], headSeq: 0, headHash: null }),
                              ...opts.inv,
                          },
                      },
                  ]
                : []),
            { provide: LinkAnalysisService, useValue: { list: () => of(opts.views ?? []), save } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
            {
                provide: ToastrService,
                useValue: { success: () => undefined, error: () => undefined, info: () => undefined },
            },
            {
                provide: ActivatedRoute,
                // 🔴 BOTH halves, because a real ActivatedRoute has both and the component uses each for a
                // different reason: `snapshot.queryParamMap` is the one-shot read the pivot handshake makes,
                // while `queryParamMap` is the live observable behind the `?case=` deep link, which must
                // survive a reload. A stub carrying only the snapshot made every test in this file die in the
                // CONSTRUCTOR on `undefined.pipe(...)` — 28 of 28, none of them on an assertion, so the file
                // stopped guarding anything the day the deep link was added.
                useValue: {
                    snapshot: { queryParamMap: convertToParamMap(opts.queryParams ?? {}) },
                    queryParamMap: of(convertToParamMap(opts.queryParams ?? {})),
                },
            },
        ],
    });
    if (opts.stubGraph ?? true) {
        TestBed.overrideComponent(LinkAnalysisComponent, {
            remove: { imports: [GraphViewComponent] },
            add: { imports: [StubGraphView] },
        });
    }
    return { fixture: TestBed.createComponent(LinkAnalysisComponent), queried, save };
}

// After a successful load the state is driven directly (no detectChanges) —
// the G6 host can't instantiate in jsdom (see registry.component.spec.ts).
async function runQuery(fixture: ReturnType<typeof create>['fixture']): Promise<void> {
    const c = fixture.componentInstance;
    // The query form lives in the (always-mounted, [hidden]-gated) query-panel child; patch it there.
    // Callers detectChanges() once before this (graph is still null then, so the G6 host never mounts).
    const panel = fixture.debugElement.query(By.directive(LinkAnalysisQueryPanelComponent))
        .componentInstance as LinkAnalysisQueryPanelComponent;
    panel.queryForm.patchValue({ datasetId: 'links-ds', sourceCol: 'source', targetCol: 'target' });
    await c.run();
}

// The SPA holds no industry profile of its own (LA-EDITIONS-1): the server's packs, as the specs need them.
installDomainProfiles(
    [
        profileFromPack(TELECOM_PACK_FIXTURE),
        profileFromPack({
            ...TELECOM_PACK_FIXTURE,
            id: 'mobile-money',
            name: 'Mobile Money',
            profile: { measureHints: ['amount'], timeHints: [], suggestedTools: ['scoring'] },
            terms: [
                { key: 'concept.node', label: 'Accounts', abbreviation: '', definition: '' },
                { key: 'concept.link', label: 'Transfers', abbreviation: '', definition: '' },
            ],
        }),
    ],
    null,
);
// ...and hands the registry back empty, so a later spec in the same worker does not inherit these packs.
afterAll(() => installDomainProfiles([], null));

describe('LinkAnalysisComponent', () => {
    it('renders the empty state, is a11y-clean, and validates the mapping before querying', async () => {
        const { fixture, queried } = create();
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('Start from...');
        await fixture.componentInstance.run(); // no mapping picked yet
        expect(fixture.componentInstance.loadError()).toMatch(/source and target/);
        expect(queried).toHaveLength(0);
        await expectNoA11yViolations(fixture.nativeElement);
    }, 45_000); // the axe pass under `test:coverage` (v8 instrumentation, what ui.yml runs) took over 15 s (2026-10-15)

    it('runs the query, shows the graph counts, and search/kind filters drive emphasis + display', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        expect(c.graph()).toEqual(GRAPH);
        expect(c.nodeKinds()).toEqual(['entity', 'other']);

        c.onSearch('a');
        expect(c.emphasis()?.nodeIds).toEqual(['a']);

        c.toggleKind('other', false);
        expect(c.displayed()?.nodes.map((n) => n.id)).toEqual(['a', 'b', 'c']);
        c.clearFilters();
        expect(c.displayed()?.nodes).toHaveLength(5);
    });

    it('timeline: filters edges by a temporal attrs column, resets on a fresh run and via clearFilters', async () => {
        const timedGraph: G6GraphData = {
            nodes: GRAPH.nodes,
            edges: [
                {
                    id: 'a->b',
                    source: 'a',
                    target: 'b',
                    data: { kind: 'link', attrs: { when: '2026-01-01T00:00:00Z' } },
                },
                {
                    id: 'b->c',
                    source: 'b',
                    target: 'c',
                    data: { kind: 'link', attrs: { when: '2026-06-01T00:00:00Z' } },
                },
                { id: 'd->e', source: 'd', target: 'e', data: { kind: 'link' } }, // no attrs
            ],
        };
        const { fixture } = create({ graph: timedGraph });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;

        expect(c.attrColumns()).toEqual(['when']);
        c.setTimeColumn('when');
        expect(c.timeColumn()).toBe('when');
        expect(c.timeExtent()).toEqual([Date.parse('2026-01-01T00:00:00Z'), Date.parse('2026-06-01T00:00:00Z')]);
        expect(c.timeCutoff()).toBe(Date.parse('2026-06-01T00:00:00Z')); // defaults to the extent max — nothing hidden yet

        c.setTimeCutoff(Date.parse('2026-03-01T00:00:00Z'));
        expect(c.displayed()?.edges.map((e) => e.id)).toEqual(['a->b']); // b->c is after cutoff, d->e has no attrs

        c.clearFilters();
        expect(c.timeColumn()).toBe('');
        expect(c.displayed()?.edges).toHaveLength(3);

        c.setTimeColumn('when');
        c.setTimeCutoff(Date.parse('2026-03-01T00:00:00Z'));
        await runQuery(fixture); // a fresh query resets the timeline like the other presentation filters
        expect(c.timeColumn()).toBe('');
        expect(c.timeCutoff()).toBeNull();
    });

    it('offers a pivot to the map for a node carrying an objectRef (ui-design-review R8)', async () => {
        const graphWithRef: G6GraphData = {
            nodes: [
                ...GRAPH.nodes,
                { id: 'f', data: { label: 'F', kind: 'entity', objectRef: { id: 'case-1', type: 'CASE' } } },
            ],
            edges: GRAPH.edges,
        };
        const { fixture } = create({ graph: graphWithRef });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        const dialog = fixture.debugElement.injector.get(MatDialog);
        const openSpy = vi.spyOn(dialog, 'open').mockReturnValue({ afterClosed: () => of(undefined) } as never);
        const dataOf = (call: number): ElementDetailData =>
            (openSpy.mock.calls[call][1] as { data: ElementDetailData }).data;

        c.onNodeClick('a'); // plain node — no objectRef, no pivot offered
        expect(dataOf(0).pivotViews).toBeUndefined();

        c.onNodeClick('f');
        expect(dataOf(1).objectRef).toEqual({ id: 'case-1', type: 'CASE' });
        expect(dataOf(1).pivotViews).toEqual(['map']);
    });

    it('LA-22: a Geo brush highlights the nodes its keys project to, and a node click brushes the map', async () => {
        const keyed: G6GraphData = {
            nodes: [
                { id: 'entity:k1', data: { label: 'Alias', kind: 'entity' } },
                { id: 'entity:k2', data: { label: 'K1', kind: 'entity' } }, // label equal to the key: must NOT match
            ],
            edges: [{ id: 'e', source: 'entity:k1', target: 'entity:k2', data: { kind: 'link' } }],
        };
        const { fixture } = create({ graph: keyed });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        const brush = TestBed.inject(GeoLinkBrushService);
        brush.fromGeo(['K1']);
        expect(c.geoBrushEmphasis()).toEqual({ nodeIds: ['entity:k1'], edgeIds: [] });

        const dialog = fixture.debugElement.injector.get(MatDialog);
        vi.spyOn(dialog, 'open').mockReturnValue({ afterClosed: () => of(undefined) } as never);
        c.onNodeClick('entity:k2');
        expect(brush.brush()).toMatchObject({ origin: 'link', nodeIds: ['entity:k2'] });
        expect(c.geoBrushEmphasis()).toBeNull();
    });

    it('resolves an incoming investigation pivot against the loaded graph, or toasts if absent', async () => {
        const graphWithRef: G6GraphData = {
            nodes: [
                ...GRAPH.nodes,
                { id: 'f', data: { label: 'F', kind: 'entity', objectRef: { id: 'case-1', type: 'CASE' } } },
            ],
            edges: GRAPH.edges,
        };
        const { fixture } = create({ graph: graphWithRef, queryParams: { pivotId: 'case-1', pivotType: 'CASE' } });
        fixture.detectChanges();
        const c = fixture.componentInstance;
        await runQuery(fixture);
        expect(c.emphasis()?.nodeIds).toEqual(['f']);
    });

    it('toasts when the pivoted-in record is not in the loaded graph', async () => {
        const { fixture } = create({ queryParams: { pivotId: 'case-missing', pivotType: 'CASE' } });
        fixture.detectChanges();
        const toastr = TestBed.inject(ToastrService);
        const infoSpy = vi.spyOn(toastr, 'info');
        await runQuery(fixture);
        expect(infoSpy).toHaveBeenCalled();
    });

    it('opens the Investigation named by ?investigation= (the Alerts deep link, DR-U9)', async () => {
        const { fixture } = create({ queryParams: { investigation: 'case-v' } });
        const adopt = vi.spyOn(fixture.componentInstance.investigation, 'adopt').mockResolvedValue(true);
        fixture.detectChanges();
        expect(adopt).toHaveBeenCalledWith('case-v');
        expect(fixture.componentInstance.toolboxTab()).toBe('investigation');
    });

    it('adopts a SECOND ?investigation= id on the reused route, and ignores the id it already has open (DR-U9)', async () => {
        const params = new BehaviorSubject(convertToParamMap({ investigation: 'inv-1' }));
        const { fixture } = create({ queryParams: { investigation: 'inv-1' } });
        (TestBed.inject(ActivatedRoute) as { queryParamMap: unknown }).queryParamMap = params;
        const c = fixture.componentInstance;
        const adopt = vi.spyOn(c.investigation, 'adopt').mockImplementation(async (id) => {
            c.investigation.activeId.set(id);
            return true;
        });
        fixture.detectChanges();
        expect(adopt).toHaveBeenCalledTimes(1);
        params.next(convertToParamMap({ investigation: 'inv-2' }));
        expect(adopt).toHaveBeenLastCalledWith('inv-2');
        params.next(convertToParamMap({ investigation: 'inv-2', other: 'x' })); // the store's own sync: no re-adopt
        c.investigation.close();
        params.next(convertToParamMap({})); // closed: nothing to adopt
        expect(adopt).toHaveBeenCalledTimes(2);
    });

    it('puts ?investigation= back when the kept-alive page is reached through a link without it (the nav rail)', async () => {
        const params = new BehaviorSubject(convertToParamMap({ investigation: 'inv-1' }));
        const { fixture } = create({ queryParams: { investigation: 'inv-1' } });
        (TestBed.inject(ActivatedRoute) as { queryParamMap: unknown }).queryParamMap = params;
        const c = fixture.componentInstance;
        vi.spyOn(c.investigation, 'adopt').mockImplementation(async (id) => {
            c.investigation.activeId.set(id);
            return true;
        });
        fixture.detectChanges();
        const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

        params.next(convertToParamMap({})); // back from Settings through the nav rail's plain /link-analysis

        expect(c.investigation.activeId()).toBe('inv-1'); // still open
        expect(navigate).toHaveBeenCalledWith(
            [],
            expect.objectContaining({
                queryParams: { investigation: 'inv-1' },
                queryParamsHandling: 'merge',
                replaceUrl: true,
            }),
        );
    });

    it('resolves a pivot that lands on the kept-alive page with its graph already drawn, without a new load', async () => {
        const graphWithRef: G6GraphData = {
            nodes: [
                ...GRAPH.nodes,
                { id: 'f', data: { label: 'F', kind: 'entity', objectRef: { id: 'case-1', type: 'CASE' } } },
            ],
            edges: GRAPH.edges,
        };
        const params = new BehaviorSubject(convertToParamMap({}));
        const { fixture, queried } = create({ graph: graphWithRef });
        const route = TestBed.inject(ActivatedRoute) as { queryParamMap: unknown; snapshot: unknown };
        route.queryParamMap = params;
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        expect(c.emphasis()).toBeNull();

        const pivot = convertToParamMap({ pivotId: 'case-1', pivotType: 'CASE' });
        route.snapshot = { queryParamMap: pivot };
        params.next(pivot);

        expect(c.emphasis()?.nodeIds).toEqual(['f']);
        expect(queried).toHaveLength(1);
    });

    it('Close: absent on the empty state; names the drawn analysis, in the toolbar and Saved views, and goes back to empty', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('[data-testid="la-close"]')).toBeNull();

        await runQuery(fixture);
        fixture.detectChanges();
        const close = el.querySelector<HTMLButtonElement>('[data-testid="la-close"]')!;
        expect(close.getAttribute('aria-label')).toBe('Close the analysis');
        el.querySelector<HTMLButtonElement>('button[aria-label="Open saved views"]')!.click();
        fixture.detectChanges();
        expect(document.querySelector('[data-testid="la-close-menu-item"]')?.textContent?.trim()).toBe(
            'Close the analysis',
        );
        await expectNoA11yViolations(el.querySelector('[data-testid="la-header"]')!);

        close.click();
        await fixture.whenStable();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        expect(c.graph()).toBeNull();
        expect(c.lastRun()).toBeNull();
        expect(el.textContent).toContain('Start from...');
        expect(el.querySelector('[data-testid="la-close"]')).toBeNull();
    }, 45_000);

    it('Close: an open Investigation in a Draft scope closes whole, and the store drops ?investigation=', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        c.investigation.refs.set([{ id: 'inv-1', title: 'Mule hub' }]);
        c.investigation.activeId.set('inv-1');
        c.investigation.activeDraftId.set('d-7');
        TestBed.tick();
        expect(c.openWorkLabel()).toBe('Draft d-7 of Investigation Mule hub');
        c.investigation.busy.set(true); // a step in flight: Close waits for it, or the step's answer would reopen
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('[data-testid="la-close"]').disabled).toBe(true);
        c.investigation.busy.set(false);

        await c.closeOpenWork();
        TestBed.tick();

        expect(c.investigation.activeId()).toBeNull();
        expect(c.investigation.activeDraftId()).toBeNull();
        expect(c.openWorkLabel()).toBe('');
        expect(navigate).toHaveBeenLastCalledWith([], expect.objectContaining({ queryParams: { investigation: null } }));
    });

    it('Close: an imported view that was never saved is asked about first; Cancel keeps it', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        const ask = vi.spyOn(TestBed.inject(InspectoConfirmService), 'confirmDestructive').mockResolvedValue(false);
        const draft: LaImportDraft = {
            kind: 'link-analysis-view',
            id: 'mule_ring',
            content: {},
            sourceSpace: null,
            targetExists: false,
            integrity: null,
            prerequisites: [],
        };
        c.importDraft.set(draft);
        expect(c.openWorkLabel()).toBe('unsaved view mule_ring');

        await c.closeOpenWork();
        expect(ask).toHaveBeenCalledTimes(1);
        expect(c.importDraft()).toBe(draft);

        ask.mockResolvedValue(true);
        await c.closeOpenWork();
        expect(c.importDraft()).toBeNull();
    });

    it('says what a Space switch closed (remembered for the tab across the reload), once', () => {
        sessionStorage.setItem(
            'inspecto.la.openWork',
            // actor '': no one is signed in under this TestBed, so the record is this (anonymous) user's own
            JSON.stringify({ space: 'some-other-space', actor: '', label: 'Investigation Mule hub' }),
        );
        const { fixture } = create();
        const info = vi.spyOn(TestBed.inject(ToastrService), 'info');
        fixture.detectChanges();
        expect(info).toHaveBeenCalledWith(
            'Investigation Mule hub was closed when you switched Space. It stays in Space some-other-space; switch back to reopen it.',
            'Closed',
        );
    });

    it('ignores Ctrl+Z while kept alive behind another page (it is out of the document)', () => {
        const { fixture } = create();
        fixture.detectChanges();
        const undo = vi.spyOn(fixture.componentInstance, 'undoPresentation');
        const el = fixture.nativeElement as HTMLElement;
        const parent = el.parentElement!;
        el.remove();
        document.dispatchEvent(new KeyboardEvent('keydown', { key: 'z', ctrlKey: true }));
        expect(undo).not.toHaveBeenCalled();
        parent.appendChild(el);
        document.dispatchEvent(new KeyboardEvent('keydown', { key: 'z', ctrlKey: true }));
        expect(undo).toHaveBeenCalledTimes(1);
    });

    it('says the Investigation is not available when the deep-linked id is unknown (404 is absence, DR-U9)', async () => {
        const { fixture } = create({
            queryParams: { investigation: 'inv-gone' },
            inv: {
                investigationLog: () =>
                    throwError(() => new HttpErrorResponse({ status: 404, error: { error: 'no such' } })),
                replayInvestigation: () =>
                    throwError(() => new HttpErrorResponse({ status: 404, error: { error: 'no such' } })),
            },
        });
        fixture.detectChanges();
        for (let i = 0; i < 5; i++) await new Promise((r) => setTimeout(r));
        expect(fixture.componentInstance.investigation.error()).toContain('not available');
        expect(fixture.componentInstance.toolboxTab()).toBe('investigation');
    });

    /** Run ngOnInit over a ?seed= link with the param-clearing navigation stubbed; returns the navigate spy. */
    async function consume(fixture: ReturnType<typeof create>['fixture']) {
        const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        fixture.componentInstance.ngOnInit();
        for (let i = 0; i < 5; i++) await new Promise((r) => setTimeout(r));
        return navigate;
    }
    const ROWS = (latest: unknown, columns = ['a_msisdn', 'b_msisdn', 'link_kind', 'last_seen', 'events']) => ({
        sql: vi.fn(async () => ({ rows: [{ latest }], columns: [], truncated: false })),
        columns: vi.fn(async () => columns.map((name) => ({ name, type: 'string' as const }))),
    });

    it('?seed= binds the telecom mapping, anchors the window at the Dataset latest time, and clears the params', async () => {
        const rows = ROWS('2026-09-29 18:30:00');
        const { fixture } = create({
            queryParams: { seed: '966501', entityType: 'msisdn', dataset: 'links-ds' },
            rows,
        });
        const navigate = await consume(fixture);
        const c = fixture.componentInstance;
        expect(c.pendingInvestigation()).toMatchObject({
            seed: '966501',
            profileId: 'telecom',
            projection: {
                datasetId: 'links-ds',
                sourceCol: 'a_msisdn',
                targetCol: 'b_msisdn',
                linkKindCol: 'link_kind',
            },
            timeCol: 'last_seen',
            eventsCol: 'events',
            windowEnd: '2026-09-29T18:30:00.000Z',
        });
        expect(rows.sql).toHaveBeenCalledWith(
            'links',
            `SELECT strftime(MAX("last_seen"), '%Y-%m-%dT%H:%M:%S') AS latest FROM "links"`,
            1,
        );
        expect(c.profileId()).toBe('telecom');
        expect(c.degreePresets()).toEqual({ windowDays: 30, minEvents: 1, maxFanOut: 500, budget: 10000 });
        expect(c.toolboxTab()).toBe('investigation');
        expect(c.investigateIssue()).toBe('');
        expect(navigate).toHaveBeenCalledWith(
            [],
            expect.objectContaining({
                queryParams: { seed: null, entityType: null, dataset: null },
                queryParamsHandling: 'merge',
                replaceUrl: true,
            }),
        );
    });

    it('?seed= with an unreadable latest time binds without a window end (no "30 days" claim)', async () => {
        const rows = {
            ...ROWS(null),
            sql: vi.fn(async () => ({ rows: [], columns: [], truncated: false, error: 'denied' })),
        };
        const { fixture } = create({ queryParams: { seed: '966501', dataset: 'links-ds' }, rows });
        await consume(fixture);
        expect(fixture.componentInstance.pendingInvestigation()).not.toHaveProperty('windowEnd');
    });

    it('?seed= naming a Dataset without the mapped columns shows why and binds nothing', async () => {
        const { fixture } = create({
            queryParams: { seed: '966501', dataset: 'links-ds' },
            rows: ROWS(null, ['src', 'dst']),
        });
        await consume(fixture);
        const c = fixture.componentInstance;
        expect(c.pendingInvestigation()).toBeNull();
        expect(c.investigateIssue()).toContain('has no column a_msisdn, b_msisdn, link_kind, last_seen, events');
    });

    it('?seed= that is not an MSISDN is refused with the starter card message, nothing queued', async () => {
        const { fixture } = create({ queryParams: { seed: '12ab', entityType: 'msisdn' }, rows: ROWS(null) });
        await consume(fixture);
        const c = fixture.componentInstance;
        expect(c.investigateIssue()).toBe(
            '12ab cannot be investigated: A number is 6 to 15 digits, optionally starting with +.',
        );
        expect(c.investigation.queuedSeeds()).toEqual([]);
    });

    it('?seed= over a Dataset the Space lacks: says so, queues the number, creates nothing', async () => {
        // telecom_links_dataset is absent from this Space
        const { fixture } = create({ queryParams: { seed: '966501', entityType: 'msisdn' }, rows: ROWS(null) });
        await consume(fixture);
        const c = fixture.componentInstance;
        expect(c.pendingInvestigation()).toBeNull();
        expect(c.investigateIssue()).toContain('The Dataset telecom_links_dataset is not in this Space');
        expect(c.investigation.queuedSeeds()).toEqual([{ id: '966501', label: '966501', ids: ['966501'] }]);
    });

    // LA-ONBOARD-1 phase 3: the first-run sample lands on /link-analysis?view=<id> with its graph drawn.
    it('?view= opens that saved view and KEEPS the param, so a browser refresh reopens it (operator 2026-10-24)', async () => {
        const view: LinkAnalysisView = { id: 'ring', name: 'Ring', sourceId: 'entity-projection', query: {} };
        const { fixture } = create({ views: [view], queryParams: { view: 'ring' } });
        const load = vi.spyOn(fixture.componentInstance, 'loadView').mockResolvedValue(undefined);
        const navigate = await consume(fixture);
        expect(load).toHaveBeenCalledWith(view);
        expect(navigate).not.toHaveBeenCalledWith([], expect.objectContaining({ queryParams: { view: null } }));
    });

    it('?view= naming the view this page already has open does not load it a second time', async () => {
        const view: LinkAnalysisView = { id: 'ring', name: 'Ring', sourceId: 'entity-projection', query: {} };
        const { fixture } = create({ views: [view], queryParams: { view: 'ring' } });
        fixture.componentInstance.openViewId.set('ring'); // the page wrote the param itself when it opened the view
        const load = vi.spyOn(fixture.componentInstance, 'loadView').mockResolvedValue(undefined);
        await consume(fixture);
        expect(load).not.toHaveBeenCalled();
    });

    it('opening a saved view writes ?view=<id> and remembers it; an imported draft does neither', async () => {
        const { fixture } = create({});
        const c = fixture.componentInstance;
        const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        await c.loadView({ id: 'ring', name: 'Ring', sourceId: 'entity-projection', query: {} });
        expect(c.openViewId()).toBe('ring');
        expect(navigate).toHaveBeenCalledWith(
            [],
            expect.objectContaining({ queryParams: { view: 'ring' }, queryParamsHandling: 'merge', replaceUrl: true }),
        );
        navigate.mockClear();
        c.importDraft.set({ id: 'imported', content: {}, targetExists: false } as never);
        await c.loadView({ id: 'imported', name: 'Imported', sourceId: 'entity-projection', query: {} });
        expect(c.openViewId()).toBeNull(); // nothing saved for a refresh to reopen
        expect(navigate).not.toHaveBeenCalledWith([], expect.objectContaining({ queryParams: { view: 'imported' } }));
        // ...and the previous view's id is dropped from the address too, or a refresh would reopen it over the draft
        expect(navigate).toHaveBeenCalledWith([], expect.objectContaining({ queryParams: { view: null } }));
    });

    it('back on the kept-alive page without ?view=, the open view\'s id is put back in the address', async () => {
        const { fixture } = create({});
        fixture.componentInstance.openViewId.set('ring');
        const navigate = await consume(fixture);
        expect(navigate).toHaveBeenCalledWith(
            [],
            expect.objectContaining({ queryParams: { view: 'ring' }, queryParamsHandling: 'merge', replaceUrl: true }),
        );
    });

    it('an incoming ?investigation= link does not get the open view\'s ?view= put back beside it', async () => {
        const { fixture } = create({ queryParams: { investigation: 'inv-1' } });
        const c = fixture.componentInstance;
        vi.spyOn(c.investigation, 'adopt').mockResolvedValue(true);
        c.openViewId.set('ring'); // the kept-alive page still has a view open when the Alert's link arrives
        const navigate = await consume(fixture);
        expect(navigate).not.toHaveBeenCalledWith([], expect.objectContaining({ queryParams: { view: 'ring' } }));
    });

    it('Close drops the remembered view and ?view= from the address', async () => {
        const { fixture } = create({});
        const c = fixture.componentInstance;
        const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        c.openViewId.set('ring');
        await c.closeOpenWork();
        expect(c.openViewId()).toBeNull();
        expect(navigate).toHaveBeenCalledWith(
            [],
            expect.objectContaining({ queryParams: { view: null }, queryParamsHandling: 'merge', replaceUrl: true }),
        );
    });

    // LA-ONBOARD-1 phase 4: Add data's "Explore in Link Analysis" lands on /link-analysis?dataset=<id>.
    it('?dataset= naming a Dataset with no link yet picks it as "Explore a Dataset" does, and clears the param', async () => {
        const { fixture } = create({ queryParams: { dataset: 'links-ds' } });
        const explore = vi.spyOn(fixture.componentInstance, 'exploreDataset').mockImplementation(() => undefined);
        const navigate = await consume(fixture);
        expect(explore).toHaveBeenCalledWith('links-ds');
        expect(navigate).toHaveBeenCalledWith(
            [],
            expect.objectContaining({ queryParams: { dataset: null }, queryParamsHandling: 'merge', replaceUrl: true }),
        );
    });

    it('?dataset= naming no Dataset of this Space says so and picks nothing', async () => {
        const { fixture } = create({ queryParams: { dataset: 'gone' } });
        const explore = vi.spyOn(fixture.componentInstance, 'exploreDataset').mockImplementation(() => undefined);
        const info = vi.spyOn(TestBed.inject(ToastrService), 'info');
        await consume(fixture);
        expect(explore).not.toHaveBeenCalled();
        expect(info).toHaveBeenCalledWith('That Dataset is not in this Space.', 'Not found');
    });

    it('?view= naming no saved view of this Space says so and draws nothing', async () => {
        const { fixture } = create({ views: [], queryParams: { view: 'gone' } });
        const load = vi.spyOn(fixture.componentInstance, 'loadView').mockResolvedValue(undefined);
        const info = vi.spyOn(TestBed.inject(ToastrService), 'info');
        await consume(fixture);
        expect(load).not.toHaveBeenCalled();
        expect(info).toHaveBeenCalledWith('That saved view is not in this Space.', 'Not found');
    });

    it('?view= naming a view that is gone also drops the param, so a refresh does not repeat the warning', async () => {
        const { fixture } = create({ views: [], queryParams: { view: 'gone' } });
        vi.spyOn(fixture.componentInstance, 'loadView').mockResolvedValue(undefined);
        const navigate = await consume(fixture);
        expect(navigate).toHaveBeenCalledWith(
            [],
            expect.objectContaining({ queryParams: { view: null }, queryParamsHandling: 'merge', replaceUrl: true }),
        );
    });

    it('opens no Investigation without ?investigation=', async () => {
        const { fixture } = create({});
        const adopt = vi.spyOn(fixture.componentInstance.investigation, 'adopt').mockResolvedValue(true);
        fixture.detectChanges();
        expect(adopt).not.toHaveBeenCalled();
    });

    // Analysis-toolbox logic (shortest path, explain, centrality, communities, all-paths, components,
    // patterns, tool badges) lives in LinkAnalysisToolboxComponent — see its own spec.

    it("expandNode (Phase E): merges the source's one-hop neighborhood into the loaded graph, filters intact", async () => {
        const expand = vi.fn(async () => ({
            nodes: [{ id: 'f', data: { label: 'F', kind: 'entity' } }],
            edges: [{ id: 'c->f', source: 'c', target: 'f', data: { kind: 'link' } }],
        }));
        const { fixture } = create({ expand });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        c.onSearch('a'); // some filter/analysis state that must survive the merge

        await c.expandNode('c', 'C', ['C']);
        expect(expand).toHaveBeenCalledWith('c', 'C', expect.objectContaining({ projection: expect.anything() }), [
            'C',
        ]);
        expect(
            c
                .graph()
                ?.nodes.map((n) => n.id)
                .sort(),
        ).toEqual(['a', 'b', 'c', 'd', 'e', 'f']);
        expect(c.graph()?.edges.map((e) => e.id)).toContain('c->f');
        expect(c.emphasis()?.nodeIds).toEqual(['a']); // untouched by the merge
    });

    it('LA-UNEXPANDED-MARKER-1 (4): the exploration canvas dots every node an expand has not followed, only after the first expand', async () => {
        const expand = vi.fn(async () => ({
            nodes: [{ id: 'f', data: { label: 'F', kind: 'entity' } }],
            edges: [{ id: 'c->f', source: 'c', target: 'f', data: { kind: 'link' } }],
        }));
        const { fixture } = create({ expand });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        expect(c.unexpandedDrawn()).toBe(0);
        await c.expandNode('c', 'C', ['C']);
        const dotted = c
            .canvasData()!
            .nodes.filter((n) => n.data.unexpanded)
            .map((n) => n.id)
            .sort();
        expect(dotted).toEqual(['a', 'b', 'd', 'e', 'f']);
        expect(c.unexpandedDrawn()).toBe(5);
        c.selectNextUnexpanded();
        expect(c.emphasis()?.nodeIds).toEqual(['a']);
        await c.run(); // a fresh run forgets the expand history
        expect(c.unexpandedDrawn()).toBe(0);
    });

    // LA-02: an unsurfaced truncation is a false negative presented as a finding. `mergeGraphs` returns
    // a bare `G6GraphData` and structurally drops `truncated`, so the flag has to be carried onto the
    // signal by `expandNode` itself.
    it('expandNode surfaces a TRUNCATED neighborhood instead of leaving the working set reading complete', async () => {
        const expand = vi.fn(async () => ({
            nodes: [{ id: 'f', data: { label: 'F', kind: 'entity' } }],
            edges: [{ id: 'c->f', source: 'c', target: 'f', data: { kind: 'link' } }],
            truncated: true,
        }));
        const { fixture } = create({ expand: expand as unknown as GraphSource['expand'] });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        expect(c.truncated()).toBe(false); // the initial projection was complete

        await c.expandNode('c', 'C', ['C']);
        expect(c.truncated()).toBe(true);
    });

    it('expandNode leaves the flag alone when the neighborhood came back complete', async () => {
        const expand = vi.fn(async () => ({
            nodes: [{ id: 'f', data: { label: 'F', kind: 'entity' } }],
            edges: [{ id: 'c->f', source: 'c', target: 'f', data: { kind: 'link' } }],
            truncated: false,
        }));
        const { fixture } = create({ expand: expand as unknown as GraphSource['expand'] });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;

        await c.expandNode('c', 'C', ['C']);
        expect(c.truncated()).toBe(false);
    });

    it('expandNode is a no-op when the source has no expand()', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        await c.expandNode('c', 'C', ['C']);
        expect(c.graph()).toEqual(GRAPH); // unchanged
    });

    it('undo/redo (Phase G): a sequence of display mutations restores exact prior signal values', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        expect(c.canUndo()).toBe(false);

        c.setNodeColor('entity', TEST_COLOR);
        expect(c.nodeColors()).toEqual({ entity: TEST_COLOR });
        expect(c.canUndo()).toBe(true);

        c.toggleKind('other', false); // a second, independent mutation
        expect(c.kindFilter()).toEqual(['entity']);

        c.undoPresentation();
        expect(c.kindFilter()).toEqual([]); // back to before the toggle
        expect(c.nodeColors()).toEqual({ entity: TEST_COLOR }); // the color change is untouched
        expect(c.canRedo()).toBe(true);

        c.undoPresentation();
        expect(c.nodeColors()).toEqual({}); // back to before the color change
        expect(c.canUndo()).toBe(false);

        c.redoPresentation();
        c.redoPresentation();
        expect(c.nodeColors()).toEqual({ entity: TEST_COLOR });
        expect(c.kindFilter()).toEqual(['entity']);
        expect(c.canRedo()).toBe(false);
    });

    it('undo/redo: a fresh run() clears history (stale snapshots would reference a different graph)', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        c.setNodeColor('entity', TEST_COLOR);
        expect(c.canUndo()).toBe(true);
        await runQuery(fixture);
        expect(c.canUndo()).toBe(false);
    });

    it('exportGraphml (Phase F): downloads the displayed graph as generic GraphML', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
        c.exportGraphml();
        expect(clickSpy).toHaveBeenCalled();
        clickSpy.mockRestore();
    });

    it('Investigation canvas: kind lists and JSON/GraphML export work from the drawn Working Set', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        const blobs: Blob[] = [];
        vi.spyOn(URL, 'createObjectURL').mockImplementation((b) => (blobs.push(b as Blob), 'blob:x'));
        vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
        let name = '';
        vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
            name = this.download;
        });
        c.exportJson(); // query graph: unchanged name
        expect(name).toBe('link-analysis.json');
        c.investigation.activeId.set('inv-9');
        c.investigation.log.set({
            header: { dataset: 'd', sourceCol: 's', targetCol: 't', title: 'Fraud ring' },
            entries: [],
        } as never);
        c.investigation.workingSet.set({
            entities: [{ id: 'ws-a' }, { id: 'ws-b' }],
            links: [{ source: 'ws-a', target: 'ws-b', kind: 'calls', count: 1 }],
            excluded: [],
            hash: 'h',
        } as never);
        expect(c.workingSetCanvas()).toBe(true);
        expect(c.nodeKinds().length).toBeGreaterThan(0);
        expect(c.edgeKinds()).toContain('calls');
        c.exportJson();
        expect(name).toBe('link-analysis-Fraud-ring.json');
        expect(await blobs[blobs.length - 1].text()).toContain('ws-a');
        c.exportGraphml();
        expect(name).toBe('link-analysis-Fraud-ring.graphml');
        expect(await blobs[blobs.length - 1].text()).toContain('ws-b');
        vi.restoreAllMocks();
    });

    it('smart form: auto-collapses to the selected-values summary after a run, and edit reopens it', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        expect(fixture.componentInstance.queryOpen()).toBe(true); // full form until something runs
        await runQuery(fixture);
        const c = fixture.componentInstance;

        expect(c.queryOpen()).toBe(false); // collapsed — the summary + status bar take over
        expect(c.sourceLabel()).toBe('Entity/Link (from a Dataset)');
        expect(c.querySummary().map((i) => `${i.label}: ${i.value}`)).toEqual([
            'Dataset: Links',
            'Mapping: source → target',
        ]);

        c.queryDockOpen.set(false);
        c.editQuery(); // the status-bar pencil reopens the query dock with the form expanded
        expect(c.queryDockOpen()).toBe(true);
        expect(c.queryOpen()).toBe(true);
    });

    it('the View tools are hidden (not merely [hidden]) under the other toolbox tabs, and stay mounted', () => {
        // jsdom does not compute Tailwind, so pin the class contract: `.flex` beat the [hidden] attribute in the
        // browser (computed display: flex), which put Layout / Overlays / Lenses under Analysis and Investigation.
        const { fixture } = create();
        const c = fixture.componentInstance;
        c.toolboxDockOpen.set(true);
        c.toolboxTab.set('analysis');
        fixture.detectChanges();
        const view = (): HTMLElement | null => fixture.nativeElement.querySelector('[data-toolbox-view]');
        expect(view()).not.toBeNull(); // still mounted
        expect(view()!.classList.contains('hidden')).toBe(true);
        expect(view()!.hasAttribute('hidden')).toBe(false); // the attribute Tailwind overrides is not relied on

        c.toolboxTab.set('investigation');
        fixture.detectChanges();
        expect(view()!.classList.contains('hidden')).toBe(true);

        c.toolboxTab.set('view');
        fixture.detectChanges();
        expect(view()!.classList.contains('hidden')).toBe(false);
    });

    it('Save this analysis is disabled, with the reason, for a user without Incident management', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const btn = (): HTMLButtonElement =>
            [...(fixture.nativeElement as HTMLElement).querySelectorAll('button')].find((b) =>
                (b.getAttribute('aria-label') ?? '').startsWith('Save this analysis'),
            )!;
        fixture.detectChanges();
        expect(btn().disabled).toBe(false); // honor-system mode grants everything
        const session = TestBed.inject(SessionService);
        session.authMode.set('oidc');
        session.capabilities.set([]);
        fixture.detectChanges();
        expect(btn().disabled).toBe(true);
        expect(btn().getAttribute('aria-label')).toContain('needs the Incident management capability');
        session.capabilities.set(['canManageIncidents']);
        fixture.detectChanges();
        expect(btn().disabled).toBe(false);
        fixture.destroy();
    });

    it('a toolbox tab opens at its top: the three tabs share one scroll container', async () => {
        const { fixture } = create();
        const c = fixture.componentInstance;
        c.toolboxDockOpen.set(true);
        c.toolboxTab.set('analysis');
        fixture.detectChanges();
        const box = fixture.nativeElement.querySelector('[data-testid=toolbox-scroll]') as HTMLElement;
        expect(box).not.toBeNull();
        box.scrollTop = 400;
        c.openInvestigation();
        fixture.detectChanges();
        await Promise.resolve();
        expect(box.scrollTop).toBe(0);
    });

    it('workspace: a failed query keeps the form open; the docks open, collapse to rails and maximize', async () => {
        const { fixture } = create({ fail: true });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        expect(c.queryOpen()).toBe(true); // a failing query needs its form back
        expect(c.querySummary()).toEqual([]);

        c.toolboxDockOpen.set(false);
        c.toolboxTab.set('view');
        c.openAnalysis(); // the toolbar algorithms icon opens the right dock on its Analysis tab
        expect(c.toolboxDockOpen()).toBe(true);
        expect(c.toolboxTab()).toBe('analysis');
        c.openViewTools();
        expect(c.toolboxTab()).toBe('view');

        // Maximize hides both docks in one step and a second call restores them.
        c.toggleCanvasMaximized();
        expect(c.canvasMaximized()).toBe(true);
        fixture.detectChanges();
        const el: HTMLElement = fixture.nativeElement;
        expect(el.querySelector('[aria-label="Show the query panel"]')).not.toBeNull(); // the rail
        expect(el.querySelector('[aria-label="Show the toolbox"]')).not.toBeNull();
        expect(el.querySelector('inspecto-link-analysis-query-panel')).toBeNull();
        c.toggleCanvasMaximized();
        expect(c.queryDockOpen()).toBe(true);
        expect(c.toolboxDockOpen()).toBe(true);
        fixture.detectChanges();
        expect(el.querySelector('inspecto-link-analysis-query-panel')).not.toBeNull();
        expect(el.querySelector('[aria-label="Resize the query panel (arrow keys or drag)"]')).not.toBeNull();
    });

    it('surfaces a failing source as an inline error, not a blank pane', async () => {
        const { fixture } = create({ fail: true });
        fixture.detectChanges();
        await runQuery(fixture);
        fixture.detectChanges(); // graph stays null on failure, so the G6 host never mounts
        expect(fixture.componentInstance.loadError()).toBe('bad mapping');
        expect(fixture.nativeElement.textContent).toContain('Query failed');
    });

    it('collapse/expand branches hide and restore a node’s downstream subtree', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;

        c.collapseBranch('b'); // a→b→c: hides c, keeps b
        expect(c.displayed()?.nodes.map((n) => n.id)).toEqual(['a', 'b', 'd', 'e']);
        c.expandBranch('b');
        expect(c.displayed()?.nodes).toHaveLength(5);

        c.collapseBranch('a');
        c.collapseBranch('d');
        expect(c.displayed()?.nodes.map((n) => n.id)).toEqual(['a', 'd']);
        c.expandAll();
        expect(c.displayed()?.nodes).toHaveLength(5);
    });

    it('display options travel with a saved view and are re-applied on load', async () => {
        const { fixture, save } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;

        expect(c.displayCustomized()).toBe(false);
        c.edgeLabels.set(false);
        c.setNodeColor('entity', c.swatches[0]);
        c.setNodeColor('entity', c.swatches[1]); // re-pick replaces
        c.setEdgeColor('link', c.swatches[2]);
        c.setNodeShape('entity', 'diamond');
        c.setEdgePattern('link', 'dashed');
        c.setEdgeSize('link', 3);
        expect(c.displayCustomized()).toBe(true);

        c.saveForm.patchValue({ name: 'Styled' });
        await c.saveView();
        const saved = save.mock.calls[0][0];
        expect(saved.display).toEqual({
            nodeLabels: false, // labels start off (operator 2026-10-15)
            edgeLabels: false,
            nodeColors: { entity: c.swatches[1] },
            edgeColors: { link: c.swatches[2] },
            nodeShapes: { entity: 'diamond' },
            edgePatterns: { link: 'dashed' },
            edgeSizes: { link: 3 },
        });

        c.setNodeColor('entity', null); // drift away, then load restores the captured styling
        c.setNodeShape('entity', null);
        c.setEdgePattern('link', null);
        c.edgeLabels.set(true);
        await c.loadView(saved);
        expect(c.edgeLabels()).toBe(false);
        expect(c.nodeColors()).toEqual({ entity: c.swatches[1] });
        expect(c.nodeShapes()).toEqual({ entity: 'diamond' });
        expect(c.edgePatterns()).toEqual({ link: 'dashed' });
        expect(c.edgeSizes()).toEqual({ link: 3 });

        await c.loadView({ id: 'plain', name: 'Plain', sourceId: 'entity-projection', query: {} });
        expect(c.displayCustomized()).toBe(false); // a view without display resets to defaults
    });

    it('node and link labels are OFF by default; a saved view that omits them opens with them off, one that states them keeps them', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;

        expect(c.nodeLabels()).toBe(false);
        expect(c.edgeLabels()).toBe(false);
        expect(c.displayOptions().nodeLabels).toBe(false);
        expect(c.displayOptions().edgeLabels).toBe(false);
        expect(c.displayCustomized()).toBe(false); // off IS the default: the paint-brush is not tinted

        // An OLD saved view: its display block never stated the label switches (the cast is the point of the case).
        const oldDisplay = { nodeColors: {}, edgeColors: {}, nodeShapes: {}, edgePatterns: {}, edgeSizes: {} };
        await c.loadView({
            id: 'old',
            name: 'Old',
            sourceId: 'entity-projection',
            query: {},
            display: oldDisplay as unknown as NonNullable<LinkAnalysisView['display']>,
        });
        expect(c.nodeLabels()).toBe(false); // an old view without the keys must not switch labels on
        expect(c.edgeLabels()).toBe(false);

        await c.loadView({
            id: 'labelled',
            name: 'Labelled',
            sourceId: 'entity-projection',
            query: {},
            display: { ...oldDisplay, nodeLabels: true, edgeLabels: true },
        });
        expect(c.nodeLabels()).toBe(true); // a stated value is the analyst's choice and is kept
        expect(c.edgeLabels()).toBe(true);
        expect(c.displayCustomized()).toBe(true);
    });

    it('all-link-labels toggle: off by default, persisted in the saved view display, restored on load', async () => {
        const { fixture, save } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;

        expect(c.allEdgeLabels()).toBe(false);
        expect(c.displayOptions().allEdgeLabels).toBeUndefined(); // absent = the density rule applies
        c.allEdgeLabels.set(true);
        expect(c.displayOptions().allEdgeLabels).toBe(true);
        expect(c.edgeLabelsDense()).toBe(false);
        expect(c.displayCustomized()).toBe(true);

        c.saveForm.patchValue({ name: 'All labels' });
        await c.saveView();
        const saved = save.mock.calls[0][0];
        expect(saved.display.allEdgeLabels).toBe(true);

        c.allEdgeLabels.set(false);
        await c.loadView(saved);
        expect(c.allEdgeLabels()).toBe(true);
        await c.loadView({ id: 'plain', name: 'Plain', sourceId: 'entity-projection', query: {} });
        expect(c.allEdgeLabels()).toBe(false);
    });

    it('layout: defaults to dagre, gates tree layouts on the graph shape, and travels with a saved view', async () => {
        const { fixture, save } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;

        expect(c.layoutId()).toBe('dagre');
        expect(c.isTreeShaped()).toBe(true); // GRAPH (a→b→c, d→e) is a forest — tree layouts enabled

        c.setLayout('radial');
        expect(c.layoutId()).toBe('radial');

        c.saveForm.patchValue({ name: 'Radial view' });
        await c.saveView();
        expect(save.mock.calls[0][0].layout).toBe('radial');

        c.setLayout('mindmap'); // drift, then load restores the captured layout
        await c.loadView({ id: 'r', name: 'Radial view', sourceId: 'entity-projection', query: {}, layout: 'radial' });
        expect(c.layoutId()).toBe('radial');

        await c.loadView({ id: 'plain', name: 'Plain', sourceId: 'entity-projection', query: {} });
        expect(c.layoutId()).toBe('dagre'); // a view without a layout resets to the default
    });

    it('the bottom panel tables the displayed graph and narrows with the search', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;

        expect(c.tableRows()).toHaveLength(3); // links mode: all three edges
        c.tableMode.set('nodes');
        expect(c.tableRows()).toHaveLength(5);
        expect(c.tableRows()[0]).toEqual({ label: 'A', kind: 'entity', links: 1, id: 'a' });

        c.onSearch('a'); // search narrows the table to the matched node…
        expect(c.tableRows()).toEqual([{ label: 'A', kind: 'entity', links: 1, id: 'a' }]);
        c.tableMode.set('links');
        expect(c.tableRows().map((r) => r['id'])).toEqual(['a->b']); // …and to links touching it

        c.toggleKind('other', false); // the kind filter flows through too
        c.onSearch('');
        expect(c.tableRows().map((r) => r['id'])).toEqual(['a->b', 'b->c']);
    });

    it('saves a view (duplicate name blocked inline) and reloads a saved view', async () => {
        const existing: LinkAnalysisView = { id: 'ring', name: 'Ring', sourceId: 'entity-projection', query: {} };
        const { fixture, save, queried } = create({ views: [existing] });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;

        c.saveForm.patchValue({ name: 'Ring' });
        await c.saveView();
        expect(c.saveForm.controls.name.hasError('duplicate')).toBe(true);
        expect(save).not.toHaveBeenCalled();

        c.saveForm.patchValue({ name: 'Device ring' });
        await c.saveView();
        expect(save).toHaveBeenCalledOnce();
        expect(c.views().map((v) => v.name)).toContain('Device ring');

        await c.loadView(existing);
        expect(queried.length).toBeGreaterThan(1);
        expect(c.sourceId()).toBe('entity-projection');
    });

    it('opens the shared version-history dialog for a saved view and reloads on restore', async () => {
        const view: LinkAnalysisView = { id: 'ring', name: 'Ring', sourceId: 'entity-projection', query: {} };
        const { fixture } = create({ views: [view] });
        fixture.detectChanges();
        const c = fixture.componentInstance;
        const dialog = fixture.debugElement.injector.get(MatDialog);
        const reloadSpy = vi.spyOn(c, 'reloadViews');

        // restore succeeded → dialog closes true → the list is reloaded
        const openSpy = vi.spyOn(dialog, 'open').mockReturnValue({ afterClosed: () => of(true) } as never);
        c.openHistory(view);
        expect((openSpy.mock.calls[0][1] as { data: unknown }).data).toEqual({
            type: 'link-analysis-view',
            id: 'ring',
            label: 'Ring',
        });
        expect(reloadSpy).toHaveBeenCalledTimes(1);

        // closed without restoring → no reload
        openSpy.mockReturnValue({ afterClosed: () => of(undefined) } as never);
        c.openHistory(view);
        expect(reloadSpy).toHaveBeenCalledTimes(1);
    });
    it('domain profile: renames the working-set tiles, badges suggested tools, and travels with a saved view', async () => {
        const { fixture, save } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        expect(c.workingSet().map((s) => s.label)).toEqual(['Nodes', 'Links']); // generic: no counts/attrs in GRAPH
        expect(c.profile().suggestedTools).toEqual([]);

        c.profileControl.setValue('mobile-money');
        expect(c.workingSet().map((s) => s.label)).toEqual(['Accounts', 'Transfers']);
        expect(c.profile().suggestedTools).toContain('scoring');

        c.saveForm.setValue({ name: 'Falcon', description: '' });
        await c.saveView();
        expect(save).toHaveBeenCalledWith(expect.objectContaining({ profile: 'mobile-money' }), expect.anything());

        await c.loadView({ id: 'x', name: 'x', sourceId: 'entity-projection', query: c.lastRun()!.query });
        expect(c.profileId()).toBe('generic'); // a view without a profile reads the Space's profile: generic here
    });
    it('two-stage loop: a local predicate narrows the canvas for free; a push marks stranded nodes, never drops them', async () => {
        const FILTERED: G6GraphData = { nodes: GRAPH.nodes.slice(0, 3), edges: GRAPH.edges.slice(0, 2) }; // a–b–c only
        const { fixture, queried } = create({ filtered: FILTERED });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        expect(c.filterColumns().map((col) => col.name)).toEqual(['source', 'target', 'kind', 'count']);
        expect(c.localMatch()).toEqual({ matched: 3, total: 3 });

        // Stage 1 — edit the tree (as the editor would, in place) and apply locally: no query issued.
        c.filterWhere().items.push({ kind: 'condition', field: 'source', operator: '=', value: 'D' });
        c.onFilterChanged();
        expect(c.localMatch()).toEqual({ matched: 1, total: 3 });
        c.applyFilterLocally();
        expect(c.displayed()!.edges.map((e) => e.id)).toEqual(['d->e']);
        expect(c.displayed()!.nodes.map((n) => n.id)).toEqual(['d', 'e']);
        expect(queried).toHaveLength(1);

        // Stage 2 — push: the query carries the tree as `filter`; the result merges over the working set.
        await c.pushFilter();
        expect(queried).toHaveLength(2);
        expect((queried[1] as { filter?: unknown }).filter).toMatchObject({ kind: 'group', op: 'AND' });
        expect(c.localFilter()).toBeNull(); // the server applied it
        expect(c.pushState()).toBe('2 links · complete');
        const g = c.graph()!;
        expect(g.edges.map((e) => e.id)).toEqual(['a->b', 'b->c']);
        expect(g.nodes.filter((n) => n.data.missing).map((n) => n.id)).toEqual(['d', 'e']); // marked, kept
        expect(c.strandedCount()).toBe(2);
        expect(c.lastRun()!.query.filter).toBeDefined(); // a saved view now persists the predicate

        c.clearFilter();
        expect(c.filterWhere().items).toEqual([]);

        // Loading a view that carries a predicate seeds the builder (and a view without one clears it).
        await c.loadView({ id: 'v', name: 'v', sourceId: 'entity-projection', query: c.lastRun()!.query });
        expect(c.filterWhere().items).toHaveLength(1);
        expect(c.pushState()).toBe('sent with the query');
        await c.loadView({
            id: 'w',
            name: 'w',
            sourceId: 'entity-projection',
            query: { ...c.lastRun()!.query, filter: undefined },
        });
        expect(c.filterWhere().items).toEqual([]);
    });
    it('advanced search: needs a loaded Dataset projection, then adopts the folded result with stranded marks', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        const dialog = fixture.debugElement.injector.get(MatDialog);
        const open = vi.spyOn(dialog, 'open').mockReturnValue({
            afterClosed: () =>
                of({
                    nodes: [{ id: 'a', data: { label: 'A', kind: 'entity' } }],
                    edges: [],
                    truncated: false,
                }),
        } as never);

        c.openAdvancedSearch(); // nothing loaded yet — a hint, not a dialog
        expect(open).not.toHaveBeenCalled();

        await runQuery(fixture);
        c.openAdvancedSearch();
        expect(open).toHaveBeenCalledTimes(1);
        const data = (open.mock.calls[0] as unknown as [unknown, { data: { dataset: Dataset } }])[1].data;
        expect(data.dataset.id).toBe('links-ds');
        expect(c.graph()!.nodes.map((n) => [n.id, !!n.data.missing])).toEqual([
            ['a', false],
            ['b', true],
            ['c', true],
            ['d', true],
            ['e', true],
        ]);
        expect(c.pushState()).toBe('0 links · from advanced search');
    });
    it('view toolbox: every Lenses & selection toggle starts off (fisheye, link filter lens, snap lines, hover activate, lasso select)', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        const o = c.viewOptions();
        expect(o.fisheye).toBeFalsy();
        expect(o.edgeFilterLens).toBeFalsy();
        expect(o.snapline).toBeFalsy();
        expect(c.viewBehaviors.map((b) => b.id).sort()).toEqual(['hover-activate', 'lasso-select']);
        for (const b of c.viewBehaviors) expect(c.hasBehavior(b.id), b.id).toBe(false);
        expect(c.canvasPlugins().behaviors ?? []).toEqual([]);
    });

    it('view toolbox: plugin and behavior toggles build the canvas plugin set, hulls follow communities, and the view persists them', async () => {
        const { fixture, save } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        expect(c.canvasPlugins()).toEqual({ minimap: true, behaviors: [], hulls: null });

        c.toggleViewFlag('minimap', false);
        c.toggleViewFlag('hulls', true);
        c.toggleBehavior('brush-select', true);
        c.toggleBehavior('hover-activate', true);
        expect(c.hasBehavior('hover-activate')).toBe(true);
        c.toggleBehavior('hover-activate', false);
        const p = c.canvasPlugins();
        expect(p.minimap).toBe(false);
        expect(p.behaviors).toEqual(['brush-select']);
        expect([...p.hulls!.values()].map((m) => m.length).sort()).toEqual([2, 3]); // a–b–c and d–e

        c.legendOpen.set(false);
        c.saveForm.setValue({ name: 'Hulls', description: '' });
        await c.saveView();
        const saved = save.mock.calls[0][0] as LinkAnalysisView;
        expect(saved.view).toEqual({
            plugins: { minimap: false, hulls: true, behaviors: ['brush-select'] },
            legend: false,
            workingSet: true,
        });

        await c.loadView({ id: 'x', name: 'x', sourceId: 'entity-projection', query: c.lastRun()!.query });
        expect(c.legendOpen()).toBe(true); // a view without options resets the overlays
        expect(c.viewOptions().hulls).toBe(true); // …but keeps the plugin set the analyst last chose
    });
    it('N2: Louvain never runs with hulls off, and runs once per graph content however often hulls are toggled', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        const louvain = vi.spyOn(c as unknown as { louvainOf(g: unknown): unknown }, 'louvainOf');
        louvain.mockClear();
        c.toggleViewFlag('minimap', false);
        void c.canvasPlugins();
        expect(louvain).not.toHaveBeenCalled(); // hulls off: no community work at all

        for (let i = 0; i < 3; i++) {
            c.toggleViewFlag('hulls', true);
            expect(c.canvasPlugins().hulls?.size).toBe(2);
            c.toggleViewFlag('hulls', false);
            expect(c.canvasPlugins().hulls).toBeNull();
        }
        expect(louvain).toHaveBeenCalledTimes(1);

        // Aggregating pendant leaves is a different graph (one more run); coming back to the first one reuses its assignment.
        c.toggleViewFlag('hulls', true);
        c.superNodeThreshold.set(2);
        void c.canvasPlugins();
        expect(louvain).toHaveBeenCalledTimes(2);
        c.superNodeThreshold.set(0);
        expect(c.canvasPlugins().hulls?.size).toBe(2);
        expect(louvain).toHaveBeenCalledTimes(2);
    });
    it('evidence: Attach to Case is ONE dialog that seals the displayed graph (stranded excluded) and attaches it', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        const dialog = fixture.debugElement.injector.get(MatDialog);
        const snap = { id: 'snp-1', title: 'T', manifestHash: 'abcdef0123456789', attachedTo: ['CASE-1'] };
        const open = vi
            .spyOn(dialog, 'open')
            .mockReturnValueOnce({ afterClosed: () => of({ snapshot: snap, caseId: 'CASE-1' }) } as never)
            // a result without a snapshot (an older/partial close) must not crash the host
            .mockReturnValueOnce({ afterClosed: () => of({ caseId: 'CASE-2' }) } as never);

        expect(c.latestSnapshot()).toBeNull();
        c.openAttachToCase();
        expect(open).toHaveBeenCalledTimes(1); // no separate "save first" dialog
        const data = (open.mock.calls[0] as unknown as [unknown, { data: { graph: G6GraphData; origin: unknown } }])[1]
            .data;
        expect(data.graph.nodes).toHaveLength(5);
        expect(data.origin).toMatchObject({ sourceId: 'entity-projection', dataset: 'links-ds' });
        expect((data as { investigationId?: string }).investigationId).toBeUndefined(); // none open
        expect(c.latestSnapshot()).toBe(snap);

        expect(() => c.openAttachToCase()).not.toThrow();
        expect(c.latestSnapshot()).toBe(snap);

        await runQuery(fixture); // a fresh graph is a new answer — the old snapshot no longer describes it
        expect(c.latestSnapshot()).toBeNull();
    });
    it('evidence: a snapshot taken while an Investigation is open carries its id (the Dossier anchor)', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        const open = vi
            .spyOn(fixture.debugElement.injector.get(MatDialog), 'open')
            .mockReturnValueOnce({ afterClosed: () => of(undefined) } as never);
        c.investigation.activeId.set('inv-7');
        await c.openSnapshot();
        const data = (open.mock.calls[0] as unknown as [unknown, { data: { investigationId?: string } }])[1].data;
        expect(data.investigationId).toBe('inv-7');
    });
    it('evidence A1: a snapshot captures what the canvas shows — the Working Set, else the query graph', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        const open = vi
            .spyOn(fixture.debugElement.injector.get(MatDialog), 'open')
            .mockReturnValue({ afterClosed: () => of(undefined) } as never);
        type Data = { graph: G6GraphData; investigationId?: string; origin: { sourceId: string; dataset?: string } };
        const dataAt = (i: number) => (open.mock.calls[i] as unknown as [unknown, { data: Data }])[1].data;

        await c.openSnapshot(); // no Investigation: the query graph is on screen
        expect(dataAt(0).graph).toBe(c.canvasData());
        expect(dataAt(0).graph.nodes).toHaveLength(5);

        c.investigation.activeId.set('inv-9');
        c.investigation.log.set({
            header: { dataset: 'links-ds', sourceCol: 'src', targetCol: 'dst' },
            entries: [],
        } as never);
        c.investigation.workingSet.set({
            entities: [{ id: 'ws-a' }, { id: 'ws-b' }],
            links: [{ source: 'ws-a', target: 'ws-b', kind: 'k', count: 1 }],
            excluded: [],
            hash: 'h',
        } as never);
        expect(c.investigation.canvas()).not.toBeNull();
        await c.openSnapshot();
        const ws = dataAt(1);
        expect(ws.graph).toBe(c.canvasData());
        expect(ws.graph.nodes.map((n) => n.data.label).sort()).toEqual(['ws-a', 'ws-b']);
        expect(ws.investigationId).toBe('inv-9');
        expect(ws.origin).toMatchObject({ sourceId: 'investigation', dataset: 'links-ds' });

        c.investigation.showWorkingSet.set(false); // the query graph is drawn again while it stays open
        await c.openSnapshot();
        expect(dataAt(2).graph).toBe(c.canvasData());
        expect(dataAt(2).graph.nodes).toHaveLength(5);
    });
    it('LA-UNEXPANDED-MARKER-1 (b): Next unexpanded picks each unexpanded node in turn; Only unexpanded draws just the frontier', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        c.investigation.activeId.set('inv-8');
        c.investigation.log.set({
            header: { dataset: 'links-ds', sourceCol: 'src', targetCol: 'dst' },
            entries: [],
        } as never);
        const ent = (id: string, expanded: boolean) => ({ id, type: 'x', hop: 0, seed: 's', admittedBy: 1, expanded });
        c.investigation.workingSet.set({
            entities: [ent('ws-a', true), ent('ws-b', false), ent('ws-c', false)],
            links: [
                { source: 'ws-a', target: 'ws-b', kind: 'k', count: 1 },
                { source: 'ws-a', target: 'ws-c', kind: 'k', count: 1 },
            ],
            excluded: [],
            hash: 'h',
        } as never);
        const picked = () => c.investigation.selected()?.data.label;
        c.selectNextUnexpanded();
        expect(picked()).toBe('ws-b');
        c.selectNextUnexpanded();
        expect(picked()).toBe('ws-c');
        c.selectNextUnexpanded(); // wraps
        expect(picked()).toBe('ws-b');

        c.onlyUnexpanded.set(true);
        expect(
            c
                .canvasData()!
                .nodes.map((n) => n.data.label)
                .sort(),
        ).toEqual(['ws-b', 'ws-c']);
        c.onlyUnexpanded.set(false);
        expect(c.canvasData()!.nodes).toHaveLength(3);
    });
    it('LA-SEED-CONNECT-1 (b): a clicked pair route emphasises its nodes and the links between consecutive ones', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        c.investigation.activeId.set('inv-9');
        c.investigation.log.set({
            header: { dataset: 'links-ds', sourceCol: 'src', targetCol: 'dst' },
            entries: [],
        } as never);
        const ent = (id: string) => ({ id, type: 'x', hop: 0, seed: 's', admittedBy: 1, expanded: true });
        c.investigation.workingSet.set({
            entities: [ent('ws-a'), ent('ws-b'), ent('ws-c')],
            links: [
                { source: 'ws-a', target: 'ws-b', kind: 'k', count: 1 },
                { source: 'ws-b', target: 'ws-c', kind: 'k', count: 1 },
                { source: 'ws-a', target: 'ws-c', kind: 'k', count: 1 },
            ],
            excluded: [],
            hash: 'h',
        } as never);
        c.investigation.highlightRoute.set(['ws-a', 'ws-b', 'ws-c']);
        fixture.detectChanges();
        const em = c.emphasis()!;
        expect(em.nodeIds).toHaveLength(3);
        expect(em.edgeIds).toHaveLength(2); // a-b and b-c, not the a-c shortcut
    });
    it('evidence: Attach to Case is on the rail with only an Investigation canvas open (no query graph) and seals the Working Set', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        const el = fixture.nativeElement as HTMLElement;
        const attach = () =>
            Array.from(el.querySelectorAll('[data-testid="la-tool-rail"] button')).find((b) =>
                (b.getAttribute('aria-label') ?? '').startsWith('Attach to Case'),
            ) as HTMLButtonElement | undefined;
        expect(c.graph()).toBeNull();
        expect(attach()).toBeUndefined(); // nothing to seal yet

        c.investigation.activeId.set('inv-3');
        c.investigation.log.set({
            header: { dataset: 'links-ds', sourceCol: 'src', targetCol: 'dst' },
            entries: [],
        } as never);
        c.investigation.workingSet.set({
            entities: [{ id: 'ws-a' }, { id: 'ws-b' }],
            links: [{ source: 'ws-a', target: 'ws-b', kind: 'k', count: 1 }],
            excluded: [],
            hash: 'h',
        } as never);
        fixture.detectChanges();
        expect(c.graph()).toBeNull();
        const btn = attach()!;
        expect(btn).toBeDefined();
        expect(btn.disabled).toBe(c.attachUnavailable() !== '');
        if (btn.disabled) expect(btn.getAttribute('aria-label')).toContain(c.attachUnavailable());
        const open = vi
            .spyOn(fixture.debugElement.injector.get(MatDialog), 'open')
            .mockReturnValue({ afterClosed: () => of(undefined) } as never);
        vi.spyOn(c, 'attachUnavailable').mockReturnValue('');
        c.openAttachToCase();
        const data = (open.mock.calls[0] as unknown as [unknown, { data: { graph: G6GraphData; origin: unknown } }])[1]
            .data;
        expect(data.graph).toBe(c.canvasData());
        expect(data.origin).toMatchObject({ sourceId: 'investigation', dataset: 'links-ds' });
        fixture.destroy(); // the Working Set canvas mounted G6; tear it down before jsdom's null 2D context is touched
    });
    it('a pattern pack on the Investigation canvas runs on the server over its Dataset with its kind / time columns', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        c.investigation.activeId.set('inv-4');
        c.investigation.log.set({
            header: {
                dataset: 'telecom_links',
                sourceCol: 'a',
                targetCol: 'b',
                linkKindCol: 'link_kind',
                timeCol: 'first_seen',
            },
            entries: [],
        } as never);
        c.investigation.workingSet.set({
            entities: [{ id: 'm1' }, { id: 'm2' }],
            links: [{ source: 'm1', target: 'm2', kind: 'k', count: 1 }],
            excluded: [],
            hash: 'h',
        } as never);
        expect(c.investigationPatternTarget()).toEqual({
            dataset: 'telecom_links',
            linkKindCol: 'link_kind',
            timeCol: 'first_seen',
        });
        const inv = fixture.debugElement.injector.get(InvService);
        const call = vi
            .spyOn(inv, 'branchingPattern')
            .mockReturnValue(of({ matches: [], edges: [], truncated: false, legCapped: false }) as never);
        await c.runPatternOnServer({ mapping: -1, stages: [], overInvestigation: true, timeCol: 'first_seen' });
        expect(call).toHaveBeenCalledWith(
            expect.objectContaining({
                investigationId: 'inv-4', // ids answered as this Investigation's pseudonyms, as the canvas draws them
                dataset: 'telecom_links',
                sourceCol: 'a',
                targetCol: 'b',
                linkKindCol: 'link_kind',
                timeCol: 'first_seen',
            }),
        );
        expect(c.serverPattern()).toMatchObject({ matches: [] });
        expect(c.graph()).toBeNull(); // the query graph is not touched
    });
    it('the query graph "Truncated" alert shows on the query graph, not over an open Investigation canvas, and returns after', async () => {
        const expand = vi.fn(async () => ({
            nodes: [{ id: 'f', data: { label: 'F', kind: 'entity' } }],
            edges: [{ id: 'c->f', source: 'c', target: 'f', data: { kind: 'link' } }],
            truncated: true,
        }));
        const { fixture } = create({ expand: expand as unknown as GraphSource['expand'] });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        await c.expandNode('c', 'C', ['C']);
        const alert = () => (fixture.nativeElement as HTMLElement).textContent?.includes('projection hit the node cap');
        fixture.detectChanges();
        expect(alert()).toBe(true);

        c.investigation.activeId.set('inv-1');
        c.investigation.log.set({ header: { dataset: 'd', sourceCol: 's', targetCol: 't' }, entries: [] } as never);
        c.investigation.workingSet.set({
            entities: [{ id: 'a' }, { id: 'b' }],
            links: [{ source: 'a', target: 'b', kind: 'k', count: 1 }],
            excluded: [],
            hash: 'h',
        } as never);
        fixture.detectChanges();
        expect(c.truncated()).toBe(true);
        expect(alert()).toBe(false);

        c.investigation.close();
        fixture.detectChanges();
        expect(alert()).toBe(true);
        fixture.destroy();
    });
    it('From / To pickers list the nodes the canvas draws: over an Investigation canvas every option resolves to a server id, on the query graph they are the query graph', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        const queryLabels = c.nodeOptions().map((o) => o.label);
        expect(queryLabels.length).toBeGreaterThan(0);

        c.investigation.activeId.set('inv-1');
        c.investigation.log.set({ header: { dataset: 'd', sourceCol: 's', targetCol: 't' }, entries: [] } as never);
        c.investigation.workingSet.set({
            entities: [{ id: 'ws-a' }, { id: 'ws-b' }],
            links: [{ source: 'ws-a', target: 'ws-b', kind: 'k', count: 1 }],
            excluded: [],
            hash: 'h',
        } as never);
        fixture.detectChanges();
        // Reported: the options were the QUERY graph's, so a pick never resolved through the Working Set's ServerIdMap.
        expect(c.nodeOptions().map((o) => o.label)).toEqual(['ws-a', 'ws-b']);
        for (const o of c.nodeOptions()) expect(c.serverIds()!.serverNode(o.id)).toBeTruthy();

        c.investigation.close();
        fixture.detectChanges();
        expect(c.nodeOptions().map((o) => o.label)).toEqual(queryLabels);
        fixture.destroy();
    });
    it('footer: a Working Set over the link render ceiling says how many links were left off; below it, nothing', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        c.investigation.activeId.set('inv-9');
        c.investigation.log.set({ header: { dataset: 'd', sourceCol: 's', targetCol: 't' }, entries: [] } as never);
        const ws = (links: number) => ({
            entities: Array.from({ length: 200 }, (_, i) => ({ id: `n${i}`, hidden: false })),
            links: Array.from({ length: links }, (_, k) => ({
                source: `n${Math.floor(k / 199)}`,
                target: `n${(Math.floor(k / 199) + 1 + (k % 199)) % 200}`,
                kind: 'k',
                count: 1,
                admittedBy: 2,
            })),
            excluded: [],
            hash: 'h',
        });
        c.investigation.workingSet.set(ws(5003) as never);
        fixture.detectChanges();
        const footer = (fixture.nativeElement as HTMLElement).querySelector('[aria-label="Render status"]')!;
        expect(footer.textContent).toContain('Showing 5,000 of 5,003 links (render limit)');
        expect(c.workingSetCanvas()).toBe(true);

        c.investigation.workingSet.set(ws(40) as never);
        fixture.detectChanges();
        expect(footer.textContent).not.toContain('render limit');
        // the node and link counts live in the footer status bar now, not on the canvas strip
        expect(footer.textContent).not.toContain('links drawn');
        fixture.destroy();
        // Builds and renders a 5,003-link Working Set; the CI runner under coverage took more than the default 5 s (run 36952537914).
    }, 30_000);
    it('Level of detail and Community hulls both start off', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        expect(c.levelOfDetail()).toBe(false);
        expect(c.lodLabelsOff()).toBe(false);
        expect(c.viewOptions().hulls).toBeFalsy();
        expect(c.canvasPlugins().hulls).toBeNull();
    });

    it('level of detail: drops labels above the cap once switched on, and the footer states the published caps', async () => {
        const big: G6GraphData = {
            nodes: Array.from({ length: 301 }, (_, i) => ({ id: `n${i}`, data: { label: `N${i}`, kind: 'entity' } })),
            edges: [],
        };
        const { fixture } = create({ graph: big });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        c.nodeLabels.set(true); // labels start off now: switch them on to see the level-of-detail rule drop them
        c.edgeLabels.set(true);
        expect(c.levelOfDetail()).toBe(false); // starts off: a big graph keeps its labels until the user switches the rule on
        expect(c.lodLabelsOff()).toBe(false);
        expect(c.displayOptions().nodeLabels).toBe(true);
        c.levelOfDetail.set(true);
        expect(c.lodLabelsOff()).toBe(true);
        expect(c.displayOptions().nodeLabels).toBe(false);
        expect(c.displayOptions().edgeLabels).toBe(false);

        c.levelOfDetail.set(false);
        expect(c.displayOptions().nodeLabels).toBe(true);
        expect(c.caps).toEqual({ projection: 1000, analysis: 2500 }); // CL-1, CL-3
    });

    it('LA-11 find paths: sends the RAW spelling over the loaded mapping, merges the walk and highlights it', async () => {
        const minted: G6GraphData = {
            nodes: [
                { id: 'entity:acme', data: { label: 'ACME', kind: 'entity', spellings: ['ACME', 'acme.'] } },
                { id: 'entity:bob', data: { label: 'Bob', kind: 'entity', spellings: ['Bob'] } },
            ],
            edges: [
                {
                    id: 'entity:acme->entity:bob:link',
                    source: 'entity:acme',
                    target: 'entity:bob',
                    data: { kind: 'link' },
                },
            ],
        };
        const sent: RecursivePathsRequest[] = [];
        const recursivePaths = vi.fn((req: RecursivePathsRequest) => {
            sent.push(req);
            return of({
                paths: [{ nodes: ['ACME', 'Bob', 'Cara'], hops: 2, weight: null }],
                truncated: false,
                edgeYieldCapped: true,
                fences: { maxDepth: 3, maxEdgeYield: 10000, timeoutMs: 5000 },
            });
        });
        const { fixture } = create({ graph: minted, inv: { recursivePaths } });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        expect(c.traversalMappingOptions()).toEqual([{ value: '0', label: 'Links: source → target' }]);

        await c.findPaths({ from: 'entity:acme', mapping: 0, maxDepth: 3, direction: 'DIRECTED' });
        expect(sent[0]).toMatchObject({
            dataset: 'links-ds',
            sourceCol: 'source',
            targetCol: 'target',
            startNode: 'ACME', // the first raw spelling, never the normalised id
            targetNode: undefined,
            maxDepth: 3,
            direction: 'DIRECTED',
        });
        expect(c.graph()?.nodes.map((n) => n.id)).toEqual(['entity:acme', 'entity:bob', 'entity:cara']);
        expect(c.emphasis()?.nodeIds).toEqual(['entity:acme', 'entity:bob', 'entity:cara']);
        expect(c.emphasis()?.edgeIds[0]).toBe('entity:acme->entity:bob:link');
        expect(c.serverPaths()).toMatchObject({ edgeYieldCapped: true, depthLimit: 3, deepest: 2 });
    });

    /** LA-A11Y-AUDIT-1: an empty role=menu panel (text only) breaks aria-required-children, so the empty text is a menuitem. */
    it('the empty saved-views menu holds a (disabled) menu item, not bare text', () => {
        const { fixture } = create({ views: [] });
        fixture.detectChanges();
        (fixture.nativeElement.querySelector('button[aria-label="Open saved views"]') as HTMLElement).click();
        fixture.detectChanges();
        const panel = document.querySelector('.mat-mdc-menu-panel') as HTMLElement;
        expect(panel.getAttribute('role')).toBe('menu');
        const item = panel.querySelector('[role="menuitem"]') as HTMLElement;
        expect(item.textContent).toContain('Nothing saved yet: draw a graph, then Save as a case.');
        expect(item.hasAttribute('disabled')).toBe(true);
    });
    describe('toolbox tablist (LA-A11Y-AUDIT-1: the Analysis / View / Investigation switch)', () => {
        const open = () => {
            const { fixture } = create();
            fixture.detectChanges();
            const el = fixture.nativeElement as HTMLElement;
            const tabs = (): HTMLElement[] => Array.from(el.querySelectorAll<HTMLElement>('[role="tab"]'));
            const press = (k: string): void => {
                tabs()
                    .find((t) => t.tabIndex === 0)!
                    .dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true }));
                fixture.detectChanges();
            };
            return { fixture, c: fixture.componentInstance, el, tabs, press };
        };

        it('keeps the keyboard order the reader sees: no positive tabindex, the Query dock before the Toolbox dock, one tab stop in the tablist', () => {
            const { el, tabs } = open();
            const positive = Array.from(el.querySelectorAll<HTMLElement>('[tabindex]')).filter((n) => n.tabIndex > 0);
            expect(positive).toEqual([]); // a positive tabindex reorders focus ahead of everything else
            const query = el.querySelector('aside[aria-label="Query"]')!;
            const toolbox = el.querySelector('aside[aria-label="Toolbox"]')!;
            expect(query.compareDocumentPosition(toolbox) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
            expect(tabs().filter((t) => t.tabIndex === 0)).toHaveLength(1);
        });

        it('stacks the three docks below md so 400% zoom / 320 px reflows (live-proven 2026-10-05: a row of two fixed-width docks left the canvas 0 px wide)', () => {
            const { el } = open();
            const workspace = el.querySelector('aside[aria-label="Query"]')!.parentElement as HTMLElement;
            expect(workspace.classList).toContain('max-md:flex-col');
            for (const dock of ['Query', 'Toolbox']) {
                expect(el.querySelector(`aside[aria-label="${dock}"]`)!.classList).toContain('max-md:!w-full'); // beats the inline px width
            }
            // the drag handles have nothing to drag once the docks stack
            const handles = Array.from(el.querySelectorAll('[aria-label^="Resize the"]'));
            expect(handles).toHaveLength(2);
            for (const handle of handles) {
                expect(handle.classList).toContain('max-md:hidden');
            }
        });

        it('is a tablist of three tabs, one tab stop, whose panes are tabpanels labelled by their tab', () => {
            const { el, tabs } = open();
            const list = el.querySelector('[role="tablist"]') as HTMLElement;
            expect(list.getAttribute('aria-label')).toBe('Toolbox tab');
            expect(tabs().map((t) => t.textContent?.trim())).toEqual(['Analysis', 'View', 'Investigation']);
            expect(tabs().map((t) => t.getAttribute('aria-selected'))).toEqual(['true', 'false', 'false']);
            expect(tabs().map((t) => t.tabIndex)).toEqual([0, -1, -1]);
            expect(list.parentElement!.querySelector('[role="radio"], [role="radiogroup"]')).toBeNull(); // the old switch
            const panel = el.querySelector('#la-toolbox-panel-analysis') as HTMLElement;
            expect(panel.getAttribute('role')).toBe('tabpanel');
            expect(panel.getAttribute('aria-labelledby')).toBe('la-toolbox-tab-analysis');
            expect(tabs()[0].getAttribute('aria-controls')).toBe('la-toolbox-panel-analysis');
            const view = el.querySelector('[data-toolbox-view]') as HTMLElement;
            expect(view.getAttribute('role')).toBe('tabpanel');
            expect(view.getAttribute('aria-labelledby')).toBe('la-toolbox-tab-view');
            // the Investigation pane is only mounted while it is the tab: no dangling aria-controls
            expect(el.querySelector('#la-toolbox-panel-investigation')).toBeNull();
            expect(tabs()[2].hasAttribute('aria-controls')).toBe(false);
        });

        it('click and arrow keys select (automatic activation), wrap, and Home / End jump; the tab stop roves', () => {
            const { fixture, c, tabs, press } = open();
            const state = () => [c.toolboxTab(), tabs().findIndex((t) => t.tabIndex === 0)];
            tabs()[1].click();
            fixture.detectChanges();
            expect(state()).toEqual(['view', 1]);
            press('ArrowRight');
            expect(state()).toEqual(['investigation', 2]);
            expect(document.activeElement).toBe(tabs()[2]);
            press('ArrowRight'); // wraps
            expect(state()).toEqual(['analysis', 0]);
            press('ArrowLeft'); // wraps back
            expect(state()).toEqual(['investigation', 2]);
            press('Home');
            expect(state()).toEqual(['analysis', 0]);
            press('End');
            expect(state()).toEqual(['investigation', 2]);
            press('a'); // any other key is left alone
            expect(state()).toEqual(['investigation', 2]);
        });

        it('the Investigation pane mounts as a labelled tabpanel on its tab, and selecting a tab does not move focus into a panel', () => {
            const { fixture, el, tabs, press } = open();
            tabs()[0].focus();
            press('End');
            const panel = el.querySelector('#la-toolbox-panel-investigation') as HTMLElement;
            expect(panel.getAttribute('role')).toBe('tabpanel');
            expect(panel.getAttribute('aria-labelledby')).toBe('la-toolbox-tab-investigation');
            expect(tabs()[2].getAttribute('aria-controls')).toBe('la-toolbox-panel-investigation');
            expect(document.activeElement).toBe(tabs()[2]);
            expect(panel.contains(document.activeElement)).toBe(false);
            expect(fixture.componentInstance.toolboxTab()).toBe('investigation');
        });

        it('the collapsed rail still switches the tab (openInvestigation unchanged)', () => {
            const { fixture, c, el } = open();
            c.openInvestigation();
            fixture.detectChanges();
            expect(c.toolboxTab()).toBe('investigation');
            expect(el.querySelector('#la-toolbox-tab-investigation')!.getAttribute('aria-selected')).toBe('true');
        });
    });

    describe('tool rail (the former top toolbar, docked on the LEFT for canvas height)', () => {
        const ready = async () => {
            const { fixture } = create({ stubGraph: true });
            fixture.detectChanges();
            await runQuery(fixture);
            fixture.detectChanges();
            const el = fixture.nativeElement as HTMLElement;
            return { fixture, el, rail: el.querySelector('nav[data-testid="la-tool-rail"]') as HTMLElement };
        };

        it('is a labelled nav in the page header, so the canvas starts right under the title row', async () => {
            const { el, rail } = await ready();
            expect(rail.getAttribute('aria-label')).toBe('Link Analysis tools');
            expect(rail.closest('header[data-testid="la-header"]')).toBeTruthy(); // the header IS the toolbar
            const workspace = el.querySelector('aside[aria-label="Query"]')!.parentElement as HTMLElement;
            expect(rail.compareDocumentPosition(workspace) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
            expect(rail.classList).toContain('flex-row');
        });

        it('has a table icon that shows and hides the data table below the canvas completely', async () => {
            const { fixture, el, rail } = await ready();
            expect(el.querySelector('#bottom-panel-body')).toBeNull();
            const toggle = rail.querySelector('button[aria-label="Show the rows"]') as HTMLButtonElement;
            // aria-controls must name an element that exists (axe aria-valid-attr-value): only while it is open
            expect(toggle.hasAttribute('aria-controls')).toBe(false);
            toggle.click();
            fixture.detectChanges();
            expect(el.querySelector('#bottom-panel-body')).toBeTruthy();
            expect(toggle.getAttribute('aria-controls')).toBe('bottom-panel-body');
            toggle.click();
            fixture.detectChanges();
            expect(el.querySelector('#bottom-panel-body')).toBeNull();
        });

        it('keeps every action reachable by its accessible name', async () => {
            const { rail } = await ready();
            const names = Array.from(rail.querySelectorAll('button')).map((b) => b.getAttribute('aria-label') ?? '');
            for (const name of [
                'Search nodes',
                'Filter node types',
                'Graph layout',
                'Save this analysis',
                'Attach to Case',
                'Open the graph-algorithms toolbox',
                'Open the Investigation',
                'Open saved views',
                'Save as a case',
                'Undo the last display/filter change',
                'Redo the last undone display/filter change',
                'Fit the graph to the screen',
                'Show as list',
                'View the graph in full screen',
                'Export the graph',
            ]) {
                expect(
                    names.some((n) => n.startsWith(name)),
                    name,
                ).toBe(true);
            }
            expect(names.every((n) => n.length > 0)).toBe(true); // an icon-only button without a name is an axe failure
        });

        it('groups the four exports behind one Export menu whose items keep their names', async () => {
            const { fixture, rail } = await ready();
            expect(rail.querySelector('[aria-label="Export as PNG"]')).toBeNull(); // not loose in the rail
            (rail.querySelector('button[aria-label="Export the graph"]') as HTMLElement).click();
            fixture.detectChanges();
            const panel = document.querySelector('.mat-mdc-menu-panel') as HTMLElement;
            for (const f of ['PNG', 'JSON', 'SVG', 'GraphML']) {
                expect(panel.querySelector(`[aria-label="Export as ${f}"]`), f).not.toBeNull();
            }
        });

        it('Hide the side panels still collapses both docks and the rail stays', async () => {
            const { fixture, el, rail } = await ready();
            (el.querySelector('button[aria-label="Hide the side panels"]') as HTMLElement).click();
            fixture.detectChanges();
            expect(el.querySelector('inspecto-link-analysis-query-panel')).toBeNull();
            expect(el.querySelector('button[aria-label="Show the side panels"]')).not.toBeNull();
            expect(el.querySelector('nav[data-testid="la-tool-rail"]')).toBe(rail);
        });

        it('the workspace fills the remaining height instead of a fixed calc()', async () => {
            const { el } = await ready();
            const workspace = el.querySelector('aside[aria-label="Query"]')!.parentElement as HTMLElement;
            expect(workspace.classList).toContain('flex-auto');
            expect(workspace.style.height).toBe('');
        });
    });

    describe('graph as a list (LA-A11Y-AUDIT-1: the canvas text alternative)', () => {
        const ready = async () => {
            const { fixture } = create({ stubGraph: true });
            fixture.detectChanges();
            await runQuery(fixture);
            fixture.detectChanges();
            const el = fixture.nativeElement as HTMLElement;
            const toggle = (): HTMLButtonElement =>
                el.querySelector('[data-testid="graph-list-toggle"]') as HTMLButtonElement;
            const rowIds = (): (string | undefined)[] =>
                Array.from(el.querySelectorAll<HTMLElement>('table[role="grid"] tbody tr')).map(
                    (r) => r.dataset['nodeId'],
                );
            return { fixture, c: fixture.componentInstance, el, toggle, rowIds };
        };
        const openWorkingSet = (c: LinkAnalysisComponent, ids: string[]): void => {
            c.investigation.activeId.set('inv-9');
            c.investigation.log.set({
                header: { dataset: 'links-ds', sourceCol: 'src', targetCol: 'dst' },
                entries: [],
            } as never);
            c.investigation.workingSet.set({
                entities: ids.map((id) => ({ id })),
                links: ids.length > 1 ? [{ source: ids[0], target: ids[1], kind: 'k', count: 1 }] : [],
                excluded: [],
                hash: 'h',
            } as never);
        };

        it('the toggle is a pressed button that swaps the canvas for the list and keeps focus on itself', async () => {
            const { fixture, el, toggle, rowIds } = await ready();
            expect(toggle().getAttribute('aria-pressed')).toBe('false');
            expect(toggle().getAttribute('aria-label')).toBe('Show as list');
            expect(el.querySelector('inspecto-link-analysis-node-list')).toBeNull();
            toggle().focus();
            toggle().click();
            fixture.detectChanges();
            expect(toggle().getAttribute('aria-pressed')).toBe('true');
            expect(toggle().getAttribute('aria-label')).toBe('Show graph');
            expect(document.activeElement).toBe(toggle());
            expect(rowIds()).toEqual(['a', 'b', 'c', 'd', 'e']);
            // the canvas behind the list is out of the tab order and the accessibility tree
            expect(el.querySelector('inspecto-graph-view')!.hasAttribute('inert')).toBe(true);
            toggle().click();
            fixture.detectChanges();
            expect(el.querySelector('inspecto-link-analysis-node-list')).toBeNull();
            expect(el.querySelector('inspecto-graph-view')!.hasAttribute('inert')).toBe(false);
            expect(document.activeElement).toBe(toggle());
        });

        it('the canvas figure points to the list for a text version', async () => {
            const { fixture } = await ready();
            const view = fixture.debugElement.query(By.directive(StubGraphView)).componentInstance as StubGraphView;
            expect(view.textHint).toBe('Use Show as list for a text version.');
        });

        it('lists what the canvas draws: over an open Investigation that is the Working Set, not the query graph', async () => {
            const { fixture, c, toggle, rowIds } = await ready();
            openWorkingSet(c, ['****0001', '****0002']);
            toggle().click();
            fixture.detectChanges();
            expect(rowIds()).toEqual(c.canvasData()!.nodes.map((n) => n.id));
            expect(rowIds()).not.toEqual(['a', 'b', 'c', 'd', 'e']);
            expect(rowIds()).toHaveLength(2);
            expect((fixture.nativeElement as HTMLElement).textContent).toContain('****0001'); // masked as drawn
        });

        it('activating a row takes the same path as a canvas click', async () => {
            const { fixture, c, el, toggle } = await ready();
            const click = vi.spyOn(c, 'onNodeClick').mockImplementation(() => undefined);
            toggle().click();
            fixture.detectChanges();
            const row = el.querySelector('table[role="grid"] tbody tr[data-node-id="c"]') as HTMLElement;
            row.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
            fixture.detectChanges();
            expect(click).toHaveBeenCalledWith('c');
        });

        it('D2: a pure Investigation canvas (no query graph) has the toolbar, lists the Working Set, Enter opens the detail', async () => {
            const { fixture } = create({ stubGraph: true });
            fixture.detectChanges();
            const c = fixture.componentInstance;
            const el = fixture.nativeElement as HTMLElement;
            expect(c.graph()).toBeNull();
            expect(el.querySelector('[data-testid="graph-list-toggle"]')).toBeNull();
            openWorkingSet(c, ['ws-a', 'ws-b']);
            fixture.detectChanges();
            for (const label of [
                'Display options (labels, colors, shapes and line styles)',
                'Fit the graph to the screen',
            ])
                expect(el.querySelector(`button[aria-label="${label}"]`)).not.toBeNull();
            expect(el.querySelector('button[aria-label="View the graph in full screen"]')).not.toBeNull();
            const toggle = el.querySelector('[data-testid="graph-list-toggle"]') as HTMLButtonElement;
            toggle.click();
            fixture.detectChanges();
            const rows = Array.from(el.querySelectorAll<HTMLElement>('table[role="grid"] tbody tr'));
            expect(rows.map((r) => r.dataset['nodeId'])).toEqual(c.canvasData()!.nodes.map((n) => n.id));
            expect(rows).toHaveLength(2);
            const detail = vi
                .spyOn(c as unknown as { openNodeDetail: (id: string) => void }, 'openNodeDetail')
                .mockImplementation(() => undefined);
            rows[1].dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
            fixture.detectChanges();
            // ONE Enter on a fresh row picks it AND opens the detail (no second-click convention in the list)
            expect(c.investigation.selected()?.id).toBe(rows[1].dataset['nodeId']);
            expect(detail).toHaveBeenCalledWith(rows[1].dataset['nodeId']);
        });

        it('carries the Working Set render-limit notice into the list', async () => {
            const { fixture, c, el, toggle } = await ready();
            openWorkingSet(c, ['x']);
            vi.spyOn(c, 'omittedLinks').mockReturnValue(40);
            toggle().click();
            fixture.detectChanges();
            expect(el.querySelector('[data-testid="node-list-omitted"]')?.textContent).toContain('40 more links');
        });
    });

    describe('sealTemporal (LA-INVESTIGATION-OPS-DEFERRED-1)', () => {
        const REQ = { mapping: 0, mode: 'burst', series: 'link', windowSeconds: 60, minEvents: 5, maxCv: 0.2 };
        /** The scan panel is spec'd on its own; here the host's seal wiring is driven from the state a run leaves. */
        function scanned() {
            const { fixture } = create();
            fixture.detectChanges();
            const c = fixture.componentInstance;
            (c as unknown as { lastTemporalRun: unknown }).lastTemporalRun = { ...REQ };
            c.temporalFindings.set({} as never);
            return c;
        }

        it('sends the scan knobs of the shown run as a temporal op and records what was sealed', async () => {
            const c = scanned();
            const apply = vi.spyOn(c.investigation, 'apply').mockResolvedValue(true);
            c.investigation.lastStep.set({
                step: 4,
                temporalFindings: { count: 2, outsideWorkingSet: 1, fingerprint: 'abc' },
            } as never);
            await c.sealTemporal();
            expect(apply).toHaveBeenCalledWith({
                op: 'temporal',
                mode: 'burst',
                series: 'link',
                minEvents: 5,
                windowSeconds: 60,
            });
            expect(c.temporalSealed()).toEqual({ step: 4, count: 2, outsideWorkingSet: 1, fingerprint: 'abc' });
            expect(c.temporalSealError()).toBe('');
        });

        it('sends maxCv, not windowSeconds, for periodicity', async () => {
            const c = scanned();
            (c as unknown as { lastTemporalRun: unknown }).lastTemporalRun = { ...REQ, mode: 'periodicity' };
            const apply = vi.spyOn(c.investigation, 'apply').mockResolvedValue(true);
            await c.sealTemporal();
            expect(apply).toHaveBeenCalledWith({
                op: 'temporal',
                mode: 'periodicity',
                series: 'link',
                minEvents: 5,
                maxCv: 0.2,
            });
        });

        it('shows the server refusal and records no seal', async () => {
            const c = scanned();
            vi.spyOn(c.investigation, 'apply').mockResolvedValue(false);
            c.investigation.error.set('this Investigation has no time column');
            await c.sealTemporal();
            expect(c.temporalSealError()).toBe('this Investigation has no time column');
            expect(c.temporalSealed()).toBeNull();
        });

        it('does nothing when no scan is showing', async () => {
            const { fixture } = create();
            fixture.detectChanges();
            const apply = vi.spyOn(fixture.componentInstance.investigation, 'apply');
            await fixture.componentInstance.sealTemporal();
            expect(apply).not.toHaveBeenCalled();
        });
    });
});

describe('LinkAnalysisComponent - the Explorer level: Investigations locked, not hidden (LA-EDITIONS-1 ED-4, LA-ONBOARD-1 OB-10)', () => {
    const REASON = 'Investigations are in Standard and above, with the Investigations add-on.';
    const BASIC: LaFeatures = {
        ...STANDARD_EDITION_FEATURES,
        investigations: signal(false),
        featureReasons: signal({ investigations: REASON }),
    };

    it('keeps the Investigation tab; opening it shows Start an Investigation LOCKED with the server reason', async () => {
        const { fixture } = create({ features: BASIC });
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        const c = fixture.componentInstance;
        const tabs = Array.from(el.querySelectorAll<HTMLElement>('[role="tab"]')).map((t) => t.textContent?.trim());
        expect(tabs).toEqual(['Analysis', 'View', 'Investigation']);
        c.openInvestigation(); // what the rail's "Open the Investigation" button calls - no longer a no-op on this level
        fixture.detectChanges();
        expect(c.toolboxTab()).toBe('investigation');
        expect(el.querySelector('inspecto-link-analysis-investigation')).toBeNull();
        const panel = el.querySelector('[data-testid="investigation-locked"]') as HTMLElement;
        expect(panel.getAttribute('role')).toBe('tabpanel');
        expect(panel.getAttribute('aria-labelledby')).toBe('la-toolbox-tab-investigation');
        const action = panel.querySelector('[data-testid="locked-action"]') as HTMLButtonElement;
        expect(action.textContent).toContain('Start an Investigation');
        expect(action.getAttribute('aria-disabled')).toBe('true');
        expect(panel.querySelector('[data-testid="locked-reason"]')?.textContent?.trim()).toBe(REASON);
        await expectNoA11yViolations(panel);
    });

    it('Standard (the flag on) keeps the three tabs', () => {
        const { fixture } = create();
        fixture.detectChanges();
        const tabs = Array.from(
            (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('[role="tab"]'),
        ).map((t) => t.textContent?.trim());
        expect(tabs).toEqual(['Analysis', 'View', 'Investigation']);
    });
});

describe('LinkAnalysisComponent - starter cards and result actions (operator 2026-10-10)', () => {
    const MONEY: LinkAnalysisView = {
        id: 'mule_layering_ring',
        name: 'mule_layering_ring',
        description: 'Money-mule layering ring. Planted inside ordinary transfers.',
        sourceId: 'entity-projection',
        query: { projection: { datasetId: 'links-ds', sourceCol: 'source', targetCol: 'target' } },
    };
    const OTHER: LinkAnalysisView = {
        id: 'roaming',
        name: 'roaming',
        description: 'Roaming footprint',
        sourceId: 'entity-projection',
        query: { projection: { datasetId: 'links-ds', sourceCol: 'source', targetCol: 'target' } },
    };
    const card = (el: HTMLElement, id: string) => el.querySelector(`[data-testid="${id}"]`) as HTMLButtonElement | null;

    it('replaces the empty canvas with the Start box; the Explore card is always there', () => {
        const { fixture } = create();
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.textContent).toContain('Start from...');
        expect(card(el, 'starter-explore')).toBeTruthy();
        // nothing saved in this Space: the cards that need a saved view are hidden, not dead
        expect(card(el, 'starter-follow')).toBeNull();
        expect(card(el, 'starter-views')).toBeNull();
    });

    it('Follow the money loads the ready-made saved view and puts its graph on screen in one click', async () => {
        const { fixture, queried } = create({ views: [OTHER, MONEY] });
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(card(el, 'starter-follow')?.textContent).toContain('mule_layering_ring');
        card(el, 'starter-follow')!.click();
        await fixture.whenStable();
        expect(queried).toEqual([MONEY.query]);
        expect(fixture.componentInstance.graph()).not.toBeNull();
    });

    it('the follow-the-money card is hidden when the saved views are not money-themed', () => {
        const { fixture } = create({ views: [OTHER] });
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(card(el, 'starter-follow')).toBeNull();
        expect(card(el, 'starter-views')?.textContent).toContain('1 saved view');
    });

    it('Open a saved view opens the same menu as the rail button', () => {
        const { fixture } = create({ views: [OTHER] });
        fixture.detectChanges();
        const open = vi.spyOn(MatMenuTrigger.prototype, 'openMenu').mockImplementation(() => undefined);
        card(fixture.nativeElement, 'starter-views')!.click();
        expect(open).toHaveBeenCalledTimes(1);
        open.mockRestore();
    });

    it('Explore a Dataset shows the Query panel and hands over to its guided picker', () => {
        const { fixture } = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        c.queryDockOpen.set(false);
        const panel = fixture.debugElement.query(By.directive(LinkAnalysisQueryPanelComponent))
            ?.componentInstance as LinkAnalysisQueryPanelComponent;
        const explore = vi
            .spyOn(LinkAnalysisQueryPanelComponent.prototype, 'startExplore')
            .mockImplementation(() => undefined);
        fixture.detectChanges();
        card(fixture.nativeElement, 'starter-explore')!.click();
        expect(c.queryDockOpen()).toBe(true);
        expect(explore).toHaveBeenCalledTimes(1);
        explore.mockRestore();
        expect(panel === undefined || panel instanceof LinkAnalysisQueryPanelComponent).toBe(true);
    });

    it('a picked ranking row highlights the node and centres the canvas on it', () => {
        const { fixture } = create();
        const c = fixture.componentInstance;
        const centerOn = vi.fn();
        (c as unknown as { graphView: unknown }).graphView = { centerOn };
        c.onNodePick('b');
        expect(c.emphasis()).toEqual({ nodeIds: ['b'], edgeIds: [] });
        expect(centerOn).toHaveBeenCalledWith(['b']);
    });

    it('"Start an Investigation from the top results" queues the seeds and opens the Investigation tab - creating nothing', () => {
        const { fixture } = create();
        const c = fixture.componentInstance;
        c.toolboxTab.set('analysis');
        c.queueSeedsAndOpen([{ id: 'a', label: 'A', ids: ['a'] }]);
        expect(c.investigation.queuedSeeds()).toEqual([{ id: 'a', label: 'A', ids: ['a'] }]);
        expect(c.toolboxTab()).toBe('investigation');
        expect(c.investigation.active()).toBe(false); // the analyst confirms title/purpose first
    });

    it('queues nothing when no top result can seed (all masked)', () => {
        const { fixture } = create();
        const c = fixture.componentInstance;
        c.queueSeedsAndOpen([]);
        expect(c.investigation.queuedSeeds()).toEqual([]);
    });

    /**
     * Measured in the real shell 2026-10-10: the fill-mode graph view is `flex-auto`, which does nothing in a block
     * parent, so G6 kept its 480px default inside a 1012px zone. Its wrapper must be a flex column.
     */
    it('the canvas wrapper is a flex column, so the fill-mode graph view grows into the zone', async () => {
        const { fixture } = create({ stubGraph: true });
        fixture.detectChanges();
        await runQuery(fixture);
        fixture.detectChanges();
        const wrapper = (fixture.nativeElement as HTMLElement).querySelector('inspecto-graph-view')!
            .parentElement as HTMLElement;
        expect(wrapper.classList).toContain('flex');
        expect(wrapper.classList).toContain('flex-col');
        expect(wrapper.classList).toContain('max-md:min-h-[480px]'); // stacked layout keeps the old canvas height
    });

    it('bounds the page to the viewport at md+ (no page scroll): root height, row min-h-0, strip shrink-0; stacked keeps page scroll', async () => {
        const { fixture } = create({ stubGraph: true });
        fixture.detectChanges();
        await runQuery(fixture);
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        const root = el.firstElementChild as HTMLElement;
        // header 4rem + footer 3.5rem; floored at 35rem so a short window scrolls the PAGE instead of crushing the canvas
        expect(root.classList).toContain('h-[max(calc(100dvh-var(--shell-chrome-height,7.5rem)),35rem)]');
        expect(root.classList).toContain('max-md:h-auto');
        expect(root.classList).not.toContain('h-full');
        const workspace = el.querySelector('aside[aria-label="Query"]')!.parentElement as HTMLElement;
        for (const c of ['min-h-[26.25rem]', 'flex-auto', 'overflow-hidden', 'max-md:min-h-[28rem]']) {
            expect(workspace.classList).toContain(c);
        }
        for (const dock of ['Query', 'Toolbox']) {
            const aside = el.querySelector(`aside[aria-label="${dock}"]`)!;
            expect(aside.classList).toContain('overflow-hidden');
            expect(aside.querySelector('.overflow-y-auto')).toBeTruthy(); // the dock body scrolls inside
        }
        // the data strip is hidden entirely until the toolbox table icon is pressed; the status bar is the footer strip
        const strip = Array.from(el.querySelectorAll<HTMLElement>('div.shrink-0.rounded-lg.border')).find((d) =>
            d.querySelector('[data-testid="la-status"]'),
        );
        expect(strip).toBeTruthy();
    });

    it('keeps the canvas row at least 420px high (short windows scroll the page) and the page header floor holds it', () => {
        const { fixture } = create({ stubGraph: true });
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        const workspace = el.querySelector('aside[aria-label="Query"]')!.parentElement as HTMLElement;
        expect(workspace.classList).toContain('min-h-[26.25rem]'); // 26.25rem = 420px
        expect(workspace.classList).not.toContain('min-h-0');
        // the root floor (35rem) leaves room for the chrome above the row plus that 420px
        expect((el.firstElementChild as HTMLElement).className).toContain(',35rem)]');
    });

    it('puts the active-query status chips in the status bar at the footer, not in the header', async () => {
        const { fixture } = create({ stubGraph: true });
        fixture.detectChanges();
        await runQuery(fixture);
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        const status = el.querySelector('[data-testid="la-status"]') as HTMLElement;
        expect(status).toBeTruthy();
        expect(status.closest('header[data-testid="la-header"]')).toBeNull();
        expect(status.textContent).toContain('nodes');
        expect(status.querySelector('button[aria-label="Change the query"]')).toBeTruthy();
        expect(el.querySelectorAll('[aria-label="Active query"]').length).toBe(1); // no second, separate panel
    });

    describe('Toolbox dock start state (width)', () => {
        const media = (narrow: boolean): void => {
            const noop = (): void => undefined;
            vi.stubGlobal('matchMedia', (q: string) => ({
                matches: narrow && q.includes('1299px'),
                media: q,
                addListener: noop,
                removeListener: noop,
                addEventListener: noop,
                removeEventListener: noop,
            }));
        };
        const toolboxRail = (el: HTMLElement): HTMLElement | null =>
            el.querySelector('aside[aria-label="Toolbox"] button[aria-label="Open the view tools"]');

        afterEach(() => {
            vi.unstubAllGlobals();
            sessionStorage.clear();
        });

        it('starts collapsed to its rail below 1300px and the Query dock stays open', () => {
            sessionStorage.clear();
            media(true);
            const { fixture } = create({ stubGraph: true });
            fixture.detectChanges();
            const c = fixture.componentInstance;
            expect(c.toolboxDockOpen()).toBe(false);
            expect(c.queryDockOpen()).toBe(true);
            expect(c.canvasMaximized()).toBe(false); // "Hide the side panels" is untouched
            expect(toolboxRail(fixture.nativeElement)).toBeTruthy();
        });

        it('starts open at 1300px and wider', () => {
            sessionStorage.clear();
            media(false);
            const { fixture } = create({ stubGraph: true });
            fixture.detectChanges();
            expect(fixture.componentInstance.toolboxDockOpen()).toBe(true);
        });

        it('remembers an explicit open (narrow) and an explicit close (wide) for the session', () => {
            sessionStorage.clear();
            media(true);
            const a = create({ stubGraph: true });
            a.fixture.detectChanges();
            a.fixture.componentInstance.openAnalysis(); // the user opens it on demand
            a.fixture.detectChanges();
            expect(sessionStorage.getItem('inspecto.la.toolboxOpen')).toBe('true');
            TestBed.resetTestingModule();
            const b = create({ stubGraph: true }); // a fresh visit, still narrow
            b.fixture.detectChanges();
            expect(b.fixture.componentInstance.toolboxDockOpen()).toBe(true);

            sessionStorage.clear();
            media(false);
            TestBed.resetTestingModule();
            const c = create({ stubGraph: true });
            c.fixture.detectChanges();
            c.fixture.componentInstance.toolboxDockOpen.set(false);
            c.fixture.detectChanges();
            TestBed.resetTestingModule();
            const d = create({ stubGraph: true });
            d.fixture.detectChanges();
            expect(d.fixture.componentInstance.toolboxDockOpen()).toBe(false);
        });

        it('writes nothing until the user acts (the viewport default is not a choice)', () => {
            sessionStorage.clear();
            media(true);
            const { fixture } = create({ stubGraph: true });
            fixture.detectChanges();
            expect(sessionStorage.getItem('inspecto.la.toolboxOpen')).toBeNull();
        });
    });

    it('docks are narrower by default: Query 220px (min 200), Toolbox 340px', () => {
        const { fixture } = create({ stubGraph: true });
        fixture.detectChanges();
        const q = fixture.debugElement.query(By.css('[inspectoSplit="link-analysis.query"]'));
        const t = fixture.debugElement.query(By.css('[inspectoSplit="link-analysis.toolbox"]'));
        expect(q.nativeElement.getAttribute('aria-valuemin')).toBe('200');
        expect(q.nativeElement.getAttribute('aria-valuenow')).toBe('220');
        expect(t.nativeElement.getAttribute('aria-valuenow')).toBe('340');
    });

    it('the starter cards are a11y-clean', async () => {
        const { fixture } = create({ views: [OTHER, MONEY] });
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

describe('LinkAnalysisComponent - third live run (Investigation canvas)', () => {
    const chain = (n: number): G6GraphData =>
        ({
            nodes: Array.from({ length: n }, (_, i) => ({ id: `n${i}`, data: { label: `n${i}` } })),
            edges: Array.from({ length: n - 1 }, (_, i) => ({
                id: `e${i}`,
                source: `n${i}`,
                target: `n${i + 1}`,
                data: {},
            })),
        }) as unknown as G6GraphData;

    it('the snapshot title never starts with a bare dash', () => {
        expect(snapshotTitle(true, 'Ring 7', '', 1666)).toBe('Investigation Ring 7 — 1666 nodes');
        expect(snapshotTitle(true, undefined, '', 2)).toBe('Investigation — 2 nodes');
        expect(snapshotTitle(false, undefined, '', 1666)).toBe('Graph — 1666 nodes');
        expect(snapshotTitle(false, undefined, 'Entity projection', 1)).toBe('Entity projection — 1 node');
    });

    it('default layout: Force for an Investigation canvas, a big graph or a hub; Layered for a small chain; never force-atlas2', () => {
        expect(defaultLayoutFor(null, false)).toBe('dagre');
        expect(defaultLayoutFor(chain(5), false)).toBe('dagre');
        expect(defaultLayoutFor(chain(5), true)).toBe('force');
        expect(defaultLayoutFor(chain(1666), false)).toBe('force');
        const hub = {
            nodes: Array.from({ length: 10 }, (_, i) => ({ id: `h${i}`, data: {} })),
            edges: Array.from({ length: 9 }, (_, i) => ({ id: `x${i}`, source: 'h0', target: `h${i + 1}`, data: {} })),
        } as unknown as G6GraphData;
        expect(defaultLayoutFor(hub, false)).toBe('force');
        for (const g of [chain(900), chain(5000)]) expect(defaultLayoutFor(g, true)).not.toBe('force-cluster');
    });

    it("the analyst's explicit layout wins over the default", async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        c.investigation.activeId.set('inv-1');
        c.setLayout('dagre');
        expect(c.layoutId()).toBe('dagre');
    });

    describe('layout node caps: Radial and Information density froze the tab for minutes on 767 nodes (N1)', () => {
        const radialCap = (): number => laLimit('display.radial_max_nodes');
        const mdsCap = (): number => laLimit('display.mds_max_nodes');
        /** A page whose Investigation canvas is a REAL signal, so the guard reads the displayed graph as it does live. */
        const stage = (n: number) => {
            const { fixture } = create();
            const c = fixture.componentInstance;
            const canvas = signal<G6GraphData | null>(chain(n));
            Object.defineProperty(c.investigation, 'canvas', { value: canvas });
            return { c, canvas, fixture };
        };

        it('the default caps are 100 (Radial), 300 (Information density) and 200 (Clustering force)', () => {
            expect([radialCap(), mdsCap(), laLimit('display.force_atlas_max_nodes')]).toEqual([100, 300, 200]);
        });

        it('Clustering force is capped whether picked here or restored; at the cap it runs', async () => {
            const cap = laLimit('display.force_atlas_max_nodes');
            const r = stage(cap + 1);
            r.c.setLayout('force-cluster'); // a pick made here no longer bypasses the cap
            expect(r.c.layoutId()).toBe('concentric');
            expect(r.c.layoutCapNotice()).toContain('Clustering force layout is limited to 200 nodes');
            await r.c.loadView({
                id: 'v',
                name: 'V',
                sourceId: 'entity-projection',
                query: {},
                layout: 'force-cluster',
            });
            expect(r.c.layoutId()).toBe('concentric');
            r.canvas.set(chain(cap));
            expect(r.c.layoutId()).toBe('force-cluster');
        });

        it('the canvas is handed every layout cap (page displayOptions)', () => {
            const r = stage(10);
            expect(r.c.displayOptions().layoutCaps).toEqual({ radial: 100, mds: 300, 'force-cluster': 200 });
        });

        it('a saved view keeps the analyst PICK even while it is drawn as the fallback', async () => {
            const { fixture, save } = create();
            fixture.detectChanges();
            await runQuery(fixture);
            const c = fixture.componentInstance;
            configureLaLimits({ 'display.radial_max_nodes': 2 }); // the fixture graph has 5 nodes
            try {
                c.setLayout('radial');
                expect(c.layoutId()).toBe('concentric');
                c.saveForm.patchValue({ name: 'Over the cap' });
                await c.saveView();
                expect(save.mock.calls[0][0].layout).toBe('radial');
            } finally {
                resetLaLimits();
            }
        });

        it('above its cap a pick made here is NOT run: Degree ordered is drawn and the analyst is told', () => {
            const r = stage(radialCap() + 1);
            r.c.setLayout('radial');
            expect(r.c.layoutId()).toBe('concentric');
            expect(r.c.layoutCapNotice()).toBe(
                `Radial layout is limited to ${radialCap()} nodes (this graph has ${radialCap() + 1}), so it is shown in Degree ordered.`,
            );
        });

        it('Information density over its cap is guarded the same way', () => {
            const r = stage(mdsCap() + 1);
            r.c.setLayout('mds');
            expect(r.c.layoutId()).toBe('concentric');
            expect(r.c.layoutCapNotice()).toContain('Information density layout is limited to 300 nodes');
        });

        it('at the cap a layout is unchanged and there is no notice (boundary)', () => {
            const r = stage(radialCap());
            r.c.setLayout('radial');
            expect(r.c.layoutId()).toBe('radial');
            expect(r.c.layoutCapNotice()).toBeNull();
            r.canvas.set(chain(mdsCap()));
            r.c.setLayout('mds');
            expect(r.c.layoutId()).toBe('mds');
            expect(r.c.layoutCapNotice()).toBeNull();
        });

        it('grow past the cap -> fallback; shrink back under -> true Radial again', () => {
            const r = stage(radialCap());
            r.c.setLayout('radial');
            expect(r.c.layoutId()).toBe('radial');
            r.canvas.set(chain(radialCap() + 50));
            expect(r.c.layoutId()).toBe('concentric');
            r.canvas.set(chain(radialCap() - 10));
            expect(r.c.layoutId()).toBe('radial');
        });

        it('the guard compares the DISPLAYED graph: hiding nodes under the cap brings Radial back', () => {
            const r = stage(radialCap() + 20);
            r.c.setLayout('radial');
            expect(r.c.layoutId()).toBe('concentric');
            r.c.hiddenNodeIds.set(Array.from({ length: 20 }, (_, i) => `n${i}`));
            expect(r.c.canvasData()?.nodes.length).toBe(radialCap());
            expect(r.c.layoutId()).toBe('radial');
        });

        it('a saved view whose layout is Radial, opened on a big graph, is guarded too; a small one is not', async () => {
            const big = stage(767);
            await big.c.loadView({ id: 'v', name: 'V', sourceId: 'entity-projection', query: {}, layout: 'radial' });
            expect(big.c.pickedLayout()).toBe('radial');
            expect(big.c.layoutId()).toBe('concentric');
        });

        it('a restored Radial on a graph at the cap still opens as Radial', async () => {
            const small = stage(radialCap());
            await small.c.loadView({ id: 'v', name: 'V', sourceId: 'entity-projection', query: {}, layout: 'radial' });
            expect(small.c.layoutId()).toBe('radial');
        });

        it('undo restores the analyst PICK, not the layout that was drawn: a graph that shrinks returns to Radial', () => {
            const r = stage(radialCap() + 50);
            r.c.setLayout('radial'); // drawn as Degree ordered
            r.c.setLayout('circular'); // records the snapshot taken while Radial was picked but not drawn
            r.c.undoPresentation();
            expect(r.c.pickedLayout()).toBe('radial');
            r.canvas.set(chain(radialCap() - 10));
            expect(r.c.layoutId()).toBe('radial');
        });

        it('the notice is in the page only while the fallback applies', () => {
            const r = stage(radialCap() + 1);
            const notice = () => r.fixture.nativeElement.querySelector('[data-testid="la-layout-cap-notice"]');
            r.c.setLayout('radial');
            r.fixture.detectChanges();
            expect(notice()?.getAttribute('role')).toBe('status');
            expect(notice()?.textContent).toContain('so it is shown in Degree ordered');
            r.canvas.set(chain(radialCap()));
            r.fixture.detectChanges();
            expect(notice()).toBeNull();
        });
    });

    it('a first click on an Investigation canvas selects without switching the toolbox tab or opening the dock', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        c.toolboxTab.set('analysis');
        c.toolboxDockOpen.set(false);
        c.investigation.activeId.set('inv-1');
        c.onNodeClick('a');
        expect(c.investigation.selected()?.id).toBe('a');
        expect(c.toolboxTab()).toBe('analysis');
        expect(c.toolboxDockOpen()).toBe(false);
    });

    it('a reloaded Investigation restores the domain profile it is bound to', () => {
        const { fixture } = create();
        const c = fixture.componentInstance;
        const bound = signal<'telecom' | null>(null);
        Object.defineProperty(c.investigation, 'boundProfileId', { value: bound });
        fixture.detectChanges();
        expect(c.profileControl.value).toBe('generic');
        bound.set('telecom');
        fixture.detectChanges();
        expect(c.profileControl.value).toBe('telecom');
    });

    it('a canvas zone squeezed below the minimum collapses the Toolbox dock, then the Query dock', () => {
        const { fixture } = create();
        const c = fixture.componentInstance;
        c.toolboxDockOpen.set(true);
        c.queryDockOpen.set(true);
        c.fitDocksToWidth(CANVAS_MIN_PX + 100);
        expect([c.toolboxDockOpen(), c.queryDockOpen()]).toEqual([true, true]);
        c.fitDocksToWidth(15);
        expect([c.toolboxDockOpen(), c.queryDockOpen()]).toEqual([false, true]);
        c.fitDocksToWidth(15);
        expect([c.toolboxDockOpen(), c.queryDockOpen()]).toEqual([false, false]);
    });
});

describe('LinkAnalysisComponent - focus after the first draw (LA-A11Y-AUDIT-1 a, WCAG 2.4.3)', () => {
    const MONEY: LinkAnalysisView = {
        id: 'mule_layering_ring',
        name: 'mule_layering_ring',
        description: 'Money-mule layering ring.',
        sourceId: 'entity-projection',
        query: { projection: { datasetId: 'links-ds', sourceCol: 'source', targetCol: 'target' } },
    };
    const region = (el: HTMLElement) => el.querySelector('[data-testid="la-graph-region"]') as HTMLElement | null;
    const follow = (el: HTMLElement) => el.querySelector('[data-testid="starter-follow"]') as HTMLButtonElement;

    /** A real focus target needs the fixture in the document; jsdom ignores focus() on a detached node. */
    function mount() {
        const made = create({ views: [MONEY] });
        document.body.appendChild(made.fixture.nativeElement);
        made.fixture.detectChanges();
        return made;
    }

    it('moves focus from the removed landing card to the named graph region, and announces the draw', async () => {
        const { fixture } = mount();
        const el = fixture.nativeElement as HTMLElement;
        follow(el).focus();
        expect(document.activeElement).toBe(follow(el));
        follow(el).click();
        await fixture.whenStable();
        fixture.detectChanges();
        const r = region(el)!;
        expect(r.getAttribute('aria-label')).toBe('Link graph');
        expect(r.getAttribute('tabindex')).toBe('-1');
        expect(document.activeElement).toBe(r);
        expect(el.querySelector('[data-testid="la-draw-announcement"]')?.textContent).toContain(
            'Graph drawn, 5 nodes, 3 links',
        );
        await expectNoA11yViolations(el);
        fixture.nativeElement.remove();
    });

    it('does not move focus when the user has already put it somewhere else during the draw', async () => {
        const { fixture } = mount();
        const el = fixture.nativeElement as HTMLElement;
        const elsewhere = document.createElement('button');
        document.body.appendChild(elsewhere);
        follow(el).click();
        elsewhere.focus(); // the user tabbed away while the query was in flight
        await fixture.whenStable();
        fixture.detectChanges();
        expect(document.activeElement).toBe(elsewhere);
        elsewhere.remove();
        fixture.nativeElement.remove();
    });

    it('does not steal focus on a later run over a graph that is already on screen', async () => {
        const { fixture } = mount();
        const el = fixture.nativeElement as HTMLElement;
        follow(el).click();
        await fixture.whenStable();
        fixture.detectChanges();
        const elsewhere = document.createElement('button');
        document.body.appendChild(elsewhere);
        elsewhere.focus();
        await fixture.componentInstance.loadView(MONEY);
        fixture.detectChanges();
        expect(document.activeElement).toBe(elsewhere);
        elsewhere.remove();
        fixture.nativeElement.remove();
    });
});

describe('LinkAnalysisComponent - header is the toolbar, the footer carries the title (operator 2026-10-15)', () => {
    it('moves the title, the explain button and the page info to the footer status bar; the page keeps exactly one h1', async () => {
        const { fixture } = create();
        fixture.detectChanges(); // the landing state: no graph yet
        const el = fixture.nativeElement as HTMLElement;
        const header = el.querySelector('header[data-testid="la-header"]') as HTMLElement;
        const status = el.querySelector('[data-testid="la-status"]') as HTMLElement;
        expect(el.querySelector('inspecto-page-header')).toBeNull();
        expect(header.querySelector('h1')).toBeNull(); // nothing of the title is left up there
        expect(header.querySelector('nav[data-testid="la-tool-rail"]')).toBeTruthy(); // only the toolbar
        expect(status.querySelector('h1')?.textContent?.trim()).toBe('Link Analysis');
        expect(status.querySelector('inspecto-ai-explain')).toBeTruthy();
        expect(status.textContent).toContain('Draw a Dataset'); // the page info shows until a graph is drawn
        expect(status.textContent).not.toMatch(/pipeline/i); // no Pipeline word on an Inspecto LA screen (LA-ONBOARD-1 acceptance)
        expect(el.querySelectorAll('h1')).toHaveLength(1);
        expect(header.compareDocumentPosition(status) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it('keeps the title in the footer after a query, next to the counts', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        fixture.detectChanges();
        const status = (fixture.nativeElement as HTMLElement).querySelector('[data-testid="la-status"]') as HTMLElement;
        expect(status.querySelector('h1')?.textContent?.trim()).toBe('Link Analysis');
        expect(status.textContent).toContain('5 nodes');
        expect(status.textContent).not.toContain('Draw a Dataset');
    });

    it('states the counts once in the footer, with Caps at its left edge and Installed modules at its right (operator 2026-10-16)', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        const status = el.querySelector('[data-testid="la-status"]') as HTMLElement;
        const modules = status.querySelector('inspecto-link-analysis-modules') as HTMLElement;
        expect(modules).toBeTruthy();
        expect(status.lastElementChild).toBe(modules); // the right-most item
        expect(status.firstElementChild?.tagName.toLowerCase()).toBe('inspecto-link-analysis-caps'); // the left-most item
        expect(el.querySelectorAll('inspecto-link-analysis-modules')).toHaveLength(1); // no longer on the canvas strip
        const strip = el.querySelector('[aria-label="Render status"]') as HTMLElement;
        expect(strip.textContent).not.toContain('links drawn');
        expect(strip.querySelector('inspecto-link-analysis-modules')).toBeNull();
        expect(strip.textContent).not.toContain('caps:'); // the caps are a popover in the footer now
        expect(strip.querySelector('inspecto-link-analysis-caps')).toBeNull();
    });
});

describe('LinkAnalysisComponent - display: node size, spacing and the automatic look (operator 2026-10-24)', () => {
    const KINDS: G6GraphData = {
        nodes: [
            ...Array.from({ length: 6 }, (_, i) => ({ id: `a${i}`, data: { label: `a${i}`, kind: 'account' } })),
            ...Array.from({ length: 2 }, (_, i) => ({ id: `d${i}`, data: { label: `d${i}`, kind: 'device' } })),
        ],
        edges: [
            { id: 'e1', source: 'a0', target: 'a1', data: { kind: 'pays' } },
            { id: 'e2', source: 'a1', target: 'a2', data: { kind: 'pays' } },
            { id: 'e3', source: 'a0', target: 'd0', data: { kind: 'uses' } },
        ],
    } as never;

    async function drawn() {
        const made = create({ graph: KINDS });
        made.fixture.detectChanges();
        await runQuery(made.fixture);
        made.fixture.detectChanges();
        return made.fixture.componentInstance;
    }

    it('a query the analyst runs replaces the saved view, so a refresh must not reopen the old one over it', async () => {
        const { fixture } = create({ graph: KINDS });
        fixture.detectChanges();
        const c = fixture.componentInstance;
        const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        c.openViewId.set('ring');
        await runQuery(fixture);
        expect(c.openViewId()).toBeNull();
        expect(navigate).toHaveBeenCalledWith(
            [],
            expect.objectContaining({ queryParams: { view: null }, queryParamsHandling: 'merge', replaceUrl: true }),
        );
    });

    it('the legend is given the colours the canvas paints for LINK types too, not only the analyst\'s own choices', async () => {
        const made = create({ graph: KINDS });
        made.fixture.detectChanges();
        await runQuery(made.fixture);
        made.fixture.detectChanges();
        const c = made.fixture.componentInstance;
        const legend = made.fixture.debugElement.query(By.css('inspecto-link-analysis-legend')).componentInstance as {
            edgeColors: () => Record<string, string>;
        };
        expect(c.edgeColors()).toEqual({}); // the analyst chose nothing...
        expect(legend.edgeColors()).toEqual(c.displayOptions().edgeColors); // ...yet the legend shows the automatic link colour
        expect(Object.keys(legend.edgeColors())).toEqual(['uses']);
    });

    it('builds the automatic look from the graph the canvas DRAWS: an open Investigation\'s Working Set, not the query graph', () => {
        const { fixture } = create({ graph: KINDS });
        const c = fixture.componentInstance;
        const workingSet = {
            nodes: [
                ...Array.from({ length: 5 }, (_, i) => ({ id: `w${i}`, data: { label: `w${i}`, kind: 'wallet' } })),
                { id: 'g0', data: { label: 'g0', kind: 'agent' } },
            ],
            edges: [{ id: 'we', source: 'w0', target: 'g0', data: { kind: 'cashes' } }],
        };
        Object.defineProperty(c.investigation, 'canvas', { value: () => workingSet });
        expect(Object.keys(c.autoStyleOf().nodeColors)).toEqual(['agent']); // `wallet` is the most common type THERE
        expect(c.autoStyleOf().nodeColors['device']).toBeUndefined(); // the query graph's types play no part
    });

    it('does not remember a saved view while an Investigation canvas is on screen: it has its own ?investigation=', () => {
        const { fixture } = create({});
        const c = fixture.componentInstance;
        const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        Object.defineProperty(c.investigation, 'canvas', { value: () => ({ nodes: [], edges: [] }) });
        (c as unknown as { rememberOpenView(id: string): void }).rememberOpenView('ring');
        expect(c.openViewId()).toBeNull();
        expect(navigate).not.toHaveBeenCalledWith([], expect.objectContaining({ queryParams: { view: 'ring' } }));
    });

    it('opening a saved view closes an open Investigation FIRST, then remembers the view (it is not remembered over a canvas)', async () => {
        const { fixture } = create({});
        const c = fixture.componentInstance;
        vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        let canvasOpen = true;
        Object.defineProperty(c.investigation, 'canvas', {
            value: () => (canvasOpen ? { nodes: [], edges: [] } : null),
        });
        vi.spyOn(c.investigation, 'restore').mockImplementation(() => {
            canvasOpen = false; // what the real restore() does: close() first
        });
        await c.loadView({
            id: 'ring',
            name: 'Ring',
            sourceId: 'entity-projection',
            query: {},
            investigations: [{ id: 'inv-1' } as never],
        });
        expect(c.openViewId()).toBe('ring');
    });

    it('opening another saved view while an imported draft is still up remembers that saved view', async () => {
        const { fixture } = create({});
        const c = fixture.componentInstance;
        vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        c.importDraft.set({ id: 'imported', content: {}, targetExists: false } as never);
        await c.loadView({ id: 'other', name: 'Other', sourceId: 'entity-projection', query: {} });
        expect(c.openViewId()).toBe('other'); // only the draft's own id is "not saved yet"
    });

    it('investigating an entity drops the remembered view at once, before anything is drawn', () => {
        const { fixture } = create({});
        const c = fixture.componentInstance;
        vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        c.openViewId.set('ring');
        void c.startFromEntity({ seed: '966501', entityType: 'msisdn' } as never).catch(() => undefined);
        expect(c.openViewId()).toBeNull(); // the synchronous part of startFromEntity
    });

    it('starts small and wide, and a saved view keeps only what was changed from that', async () => {
        const c = await drawn();
        expect(c.displayOptions().nodeSize).toBe(20);
        expect(c.displayOptions().layoutSpacing).toBe(4);
        expect(c.chosenDisplay().nodeSize).toBeUndefined();
        expect(c.chosenDisplay().layoutSpacing).toBeUndefined();
        expect(c.displayCustomized()).toBe(false);
    });

    it('size and spacing are the analyst\'s alone: set from the Display menu, saved with the view, undoable', async () => {
        const c = await drawn();
        c.setNodeSize(28);
        c.setLayoutSpacing(2);
        expect(c.displayOptions()).toMatchObject({ nodeSize: 28, layoutSpacing: 2 });
        expect(c.chosenDisplay()).toMatchObject({ nodeSize: 28, layoutSpacing: 2 });
        expect(c.displayCustomized()).toBe(true);
        c.undoPresentation();
        expect(c.layoutSpacing()).toBe(4);
        c.undoPresentation();
        expect(c.nodeSize()).toBe(20);
    });

    it('opening a view restores its size and spacing, and a view that never set them gets the defaults', async () => {
        const c = await drawn();
        await c.loadView({
            id: 'v', name: 'V', sourceId: 'entity-projection', query: {},
            display: { nodeLabels: false, edgeLabels: false, nodeColors: {}, edgeColors: {}, nodeShapes: {}, edgePatterns: {}, edgeSizes: {}, nodeSize: 40, layoutSpacing: 1 },
        });
        expect(c.nodeSize()).toBe(40);
        expect(c.layoutSpacing()).toBe(1);
        await c.loadView({ id: 'plain', name: 'Plain', sourceId: 'entity-projection', query: {} });
        expect(c.nodeSize()).toBe(20);
        expect(c.layoutSpacing()).toBe(4);
    });

    it('gives the less common node and link types a look of their own and leaves the most common neutral', async () => {
        const c = await drawn();
        const d = c.displayOptions();
        expect(Object.keys(d.nodeColors)).toEqual(['device']); // `account` is the most common node type
        expect(Object.keys(d.nodeShapes)).toEqual(['device']);
        expect(Object.keys(d.edgeColors)).toEqual(['uses']); // `pays` is the most common link type
        expect(Object.keys(d.edgePatterns)).toEqual(['uses']);
        expect(c.legendItems().find((i) => i.kind === 'device')?.color).toBe(d.nodeColors['device']);
    });

    it('lets the analyst\'s own choice win over the automatic look, and never saves the automatic one', async () => {
        const c = await drawn();
        const auto = c.displayOptions().nodeColors['device'];
        c.setNodeColor('device', c.swatches.find((s) => s !== auto)!);
        expect(c.displayOptions().nodeColors['device']).not.toBe(auto);
        expect(c.chosenDisplay().nodeColors).toEqual({ device: c.displayOptions().nodeColors['device'] });
        c.setNodeColor('device', null);
        expect(c.displayOptions().nodeColors['device']).toBe(auto); // back to automatic
        expect(c.chosenDisplay().nodeColors).toEqual({});
        expect(c.chosenDisplay().edgeColors).toEqual({}); // the automatic link look is not in what a view keeps either
    });
});

describe('LinkAnalysisComponent - canvas pointer model (operator 2026-10-15)', () => {
    const drawn = async (opts: Parameters<typeof create>[0] = {}) => {
        const made = create(opts);
        made.fixture.detectChanges();
        await runQuery(made.fixture);
        made.fixture.detectChanges();
        const el = made.fixture.nativeElement as HTMLElement;
        const stub = made.fixture.debugElement.query(By.directive(StubGraphView)).componentInstance as StubGraphView;
        return { ...made, c: made.fixture.componentInstance, el, stub };
    };
    const key = (el: HTMLElement, init: KeyboardEventInit) =>
        (el.querySelector('[data-testid="la-graph-region"]') as HTMLElement).dispatchEvent(
            new KeyboardEvent('keydown', { bubbles: true, ...init }),
        );

    it('hands the host the pointer model: no pan by drag, a drag draws a box, double click, re-fit on data change', async () => {
        const { stub } = await drawn();
        expect(stub.interaction).toEqual({
            panByDrag: false,
            boxSelect: true,
            doubleClick: true,
            fitOnDataChange: true,
            fitOnResize: true,
        });
    });

    it('a box selection is highlighted on the canvas, counted in the toolbar and the footer, and ignores nodes that are not drawn', async () => {
        const { fixture, c, el, stub } = await drawn();
        expect(el.querySelector('[data-testid="la-selection-bar"]')).toBeNull();
        c.onSelectionChange(['a', 'b', 'not-drawn']);
        fixture.detectChanges();
        expect(c.selectedOnCanvas()).toEqual(['a', 'b']);
        expect(stub.selection).toEqual(['a', 'b']);
        expect(el.querySelector('[data-testid="la-selection-count"]')?.textContent).toContain('2 selected');
        expect(el.querySelector('[data-testid="la-status-selected"]')?.textContent).toContain('2 selected');
        expect(el.querySelector('[data-testid="la-selection-announcement"]')?.textContent).toContain(
            '3 nodes selected',
        );
    });

    it('every selection action is a named button; Exclude appears only inside an Investigation', async () => {
        const { fixture, c, el } = await drawn();
        c.onSelectionChange(['a', 'b']);
        fixture.detectChanges();
        const bar = el.querySelector('[data-testid="la-selection-bar"]') as HTMLElement;
        const names = Array.from(bar.querySelectorAll('button')).map((b) => b.getAttribute('aria-label'));
        expect(names).toEqual([
            'Expand from the 2 selected nodes',
            'Hide the 2 selected nodes',
            'Attach the 2 selected nodes to a Case',
            'Clear the selection',
        ]);
        expect(bar.querySelector('[aria-label^="Exclude"]')).toBeNull();
        // an OVERLAY on the canvas, not part of the toolbar: a selection must never change the layout, or the canvas
        // would resize and the view re-fit under the analyst's zoom
        expect(bar.closest('[data-testid="la-graph-region"]')).toBeTruthy();
        expect(el.querySelector('header[data-testid="la-header"] [data-testid="la-selection-bar"]')).toBeNull();
    });

    it('Escape clears the selection and Ctrl+A selects every drawn node (the keyboard path to a box select)', async () => {
        const { fixture, c, el } = await drawn();
        key(el, { key: 'a', ctrlKey: true });
        fixture.detectChanges();
        expect(c.selectedOnCanvas()).toEqual(['a', 'b', 'c', 'd', 'e']);
        key(el, { key: 'Escape' });
        fixture.detectChanges();
        expect(c.selectedOnCanvas()).toEqual([]);
        expect(el.querySelector('[data-testid="la-selection-bar"]')).toBeNull();
        expect(el.querySelector('[data-testid="la-selection-announcement"]')?.textContent).toContain(
            'Selection cleared',
        );
    });

    it('a selection a hide takes off the canvas is dropped with it', async () => {
        const { fixture, c } = await drawn();
        c.onSelectionChange(['a', 'b', 'c']);
        c.hiddenNodeIds.set(['b']);
        fixture.detectChanges();
        expect(c.selectedOnCanvas()).toEqual(['a', 'c']);
    });

    it('Hide selected (exploration) takes the nodes off the canvas, clears the selection, and is undoable', async () => {
        const { fixture, c } = await drawn();
        c.onSelectionChange(['a', 'b']);
        await c.hideSelected();
        fixture.detectChanges();
        expect(c.hiddenNodeIds()).toEqual(['a', 'b']);
        expect(c.selectedOnCanvas()).toEqual([]);
        c.undoPresentation();
        expect(c.hiddenNodeIds()).toEqual([]);
    });

    it('Expand from the selected nodes fetches one hop for each node not yet expanded, once', async () => {
        const expand = vi.fn(async () => ({
            nodes: [{ id: 'x', data: { label: 'X', kind: 'entity' } }],
            edges: [{ id: 'a->x', source: 'a', target: 'x', data: { kind: 'link' } }],
        }));
        const { c } = await drawn({ expand });
        c.onSelectionChange(['a', 'b']);
        await c.expandFromSelected();
        expect(expand).toHaveBeenCalledTimes(2);
        expect(c.expandedNodeIds()).toEqual(['a', 'b']);
        await c.expandFromSelected(); // already expanded: nothing more is fetched
        expect(expand).toHaveBeenCalledTimes(2);
    });

    it('double click toggles: expand the neighbours, then fold the branch it brought in, then unfold it', async () => {
        const expand = vi.fn(async () => ({
            nodes: [{ id: 'x', data: { label: 'X', kind: 'entity' } }],
            edges: [{ id: 'a->x', source: 'a', target: 'x', data: { kind: 'link' } }],
        }));
        const { c } = await drawn({ expand });
        await c.onNodeDblClick('a');
        expect(expand).toHaveBeenCalledTimes(1);
        expect(c.expandedNodeIds()).toContain('a');
        expect(c.collapsedRoots()).not.toContain('a');

        await c.onNodeDblClick('a'); // expanded: fold the branch
        expect(expand).toHaveBeenCalledTimes(1); // no second fetch
        expect(c.collapsedRoots()).toContain('a');

        await c.onNodeDblClick('a'); // folded: unfold it
        expect(c.collapsedRoots()).not.toContain('a');
        expect(expand).toHaveBeenCalledTimes(1);
    });

    it('double click on a node with no branch to fold says so instead of doing nothing silently', async () => {
        const expand = vi.fn(async () => ({ nodes: [], edges: [] }));
        const { c } = await drawn({ expand });
        await c.onNodeDblClick('e'); // e has no outgoing link: expand first
        expect(c.expandedNodeIds()).toContain('e');
        await c.onNodeDblClick('e'); // expanded and nothing downstream
        expect(c.collapsedRoots()).not.toContain('e');
        expect(c.drawAnnouncement()).toContain('no branch to fold');
    });
});

describe('LinkAnalysisComponent - "Merge with..." selection action (LA-17, mergeUnavailable)', () => {
    /** An open Investigation whose Working Set holds the given typed entities; all of them drawn, none selected. */
    const inInvestigation = async (ids: string[]) => {
        const made = create();
        made.fixture.detectChanges();
        await runQuery(made.fixture);
        const c = made.fixture.componentInstance;
        c.investigation.activeId.set('inv-1');
        c.investigation.log.set({ header: { dataset: 'links-ds', sourceCol: 'src', targetCol: 'dst' }, entries: [] } as never);
        c.investigation.workingSet.set({
            entities: ids.map((id) => ({ id })),
            links: [],
            excluded: [],
            hash: 'h',
        } as never);
        made.fixture.detectChanges();
        const el = made.fixture.nativeElement as HTMLElement;
        const select = (count: number) => {
            c.onSelectionChange((c.canvasData()?.nodes ?? []).slice(0, count).map((n) => n.id));
            made.fixture.detectChanges();
        };
        const merge = () => el.querySelector('[data-testid="la-selection-merge"]') as HTMLButtonElement | null;
        return { ...made, c, el, select, merge };
    };

    it('is available for two typed, readable entities with the capability, inside an Investigation', async () => {
        const { fixture, c, select, merge } = await inInvestigation(['msisdn:+447700900123', 'imsi:234150000000001']);
        select(2);
        expect(c.selectedOnCanvas()).toHaveLength(2);
        expect(c.mergeUnavailable()).toBe('');
        expect(merge()!.getAttribute('aria-disabled')).toBeNull();
        fixture.destroy();
    });

    it('is unavailable, with the reason, when there is no open Investigation', async () => {
        const { fixture, c, el } = await inInvestigation(['msisdn:+447700900123', 'imsi:234150000000001']);
        c.investigation.activeId.set(null);
        c.investigation.log.set(null as never);
        c.investigation.workingSet.set(null);
        fixture.detectChanges();
        c.onSelectionChange(['a', 'b']); // the query graph is drawn again
        fixture.detectChanges();
        expect(c.selectedOnCanvas()).toHaveLength(2);
        expect(c.mergeUnavailable()).toContain('exactly two');
        expect(el.querySelector('[data-testid="la-selection-merge"]')).toBeNull(); // the action is Investigation-only
        fixture.destroy();
    });

    it('is unavailable, with the reason, without the Incident management capability', async () => {
        const { fixture, c, select, merge } = await inInvestigation(['msisdn:+447700900123', 'imsi:234150000000001']);
        select(2);
        const session = TestBed.inject(SessionService);
        session.authMode.set('oidc');
        session.capabilities.set([]);
        fixture.detectChanges();
        expect(c.mergeUnavailable()).toContain('needs the Incident management capability');
        expect(merge()!.getAttribute('aria-disabled')).toBe('true');
        session.capabilities.set(['canManageIncidents']);
        fixture.detectChanges();
        expect(c.mergeUnavailable()).toBe('');
        expect(merge()!.getAttribute('aria-disabled')).toBeNull();
        fixture.destroy();
    });

    it('is unavailable unless exactly two typed entities are selected', async () => {
        const { fixture, c, select, merge } = await inInvestigation(['msisdn:+44', 'imsi:1', 'plain']);
        select(1);
        expect(c.mergeUnavailable()).toContain('exactly two');
        expect(merge()!.getAttribute('aria-disabled')).toBe('true');
        select(3);
        expect(c.mergeUnavailable()).toContain('exactly two');
        expect(merge()!.getAttribute('aria-disabled')).toBe('true');
        c.onSelectionChange((c.canvasData()?.nodes ?? []).slice(1, 3).map((n) => n.id)); // imsi:1 + the untyped one
        fixture.detectChanges();
        expect(c.mergeUnavailable()).toContain('typed entities');
        expect(merge()!.getAttribute('aria-disabled')).toBe('true');
        fixture.destroy();
    });

    // LA-I2-GAPS-RESIDUALS-1 (5): the prefilled merge sits in the Investigation tab, so the action opens it.
    it('opens the Investigation tab holding the prefilled merge', async () => {
        const { fixture, c, select, merge } = await inInvestigation(['msisdn:+447700900123', 'imsi:234150000000001']);
        select(2);
        c.toolboxTab.set('analysis');
        fixture.detectChanges();
        expect(c.mergeUnavailable()).toBe('');
        expect(merge()).not.toBeNull();
        c.mergeSelected();
        fixture.detectChanges();
        expect(c.toolboxTab()).toBe('investigation');
        // The Identity section rendered in that tab took the hand-off into its form (and cleared it).
        const identities = fixture.debugElement.query(By.directive(LinkAnalysisIdentitiesComponent));
        expect(identities.componentInstance.assertForm.getRawValue()).toMatchObject({
            typeA: 'msisdn',
            valueA: '+447700900123',
            typeB: 'imsi',
            valueB: '234150000000001',
        });
        expect(c.investigation.mergeDraft()).toBeNull();
        fixture.destroy();
    });

    it('is unavailable when either selected node is a masked alias', async () => {
        const { fixture, c, select, merge } = await inInvestigation(['msisdn:masked:0123456789abcdef', 'imsi:1']);
        select(2);
        expect(c.selectedOnCanvas()).toHaveLength(2);
        expect(c.mergeUnavailable()).toContain('masked entity cannot be merged');
        expect(merge()!.getAttribute('aria-disabled')).toBe('true');
        fixture.destroy();
    });

    it('builds the pair from the Working Set types when the ids are bare raw values (ONB-LA-ENDPOINT-TYPE-1)', async () => {
        const made = create();
        made.fixture.detectChanges();
        await runQuery(made.fixture);
        const c = made.fixture.componentInstance;
        c.investigation.activeId.set('inv-1');
        c.investigation.log.set({ header: { dataset: 'links-ds', sourceCol: 'src', targetCol: 'dst' }, entries: [] } as never);
        c.investigation.workingSet.set({
            entities: [
                { id: '99979100001', type: 'msisdn' },
                { id: 'SHELL-A', type: 'account' },
            ],
            links: [],
            excluded: [],
            hash: 'h',
        } as never);
        made.fixture.detectChanges();
        c.onSelectionChange((c.canvasData()?.nodes ?? []).map((n) => n.id));
        made.fixture.detectChanges();
        expect(c.canvasData()?.nodes.map((n) => n.data.kind).sort()).toEqual(['account', 'msisdn']);
        expect(c.mergeUnavailable()).toBe('');
        c.mergeSelected();
        made.fixture.detectChanges();
        // the Identity section (Investigation tab) took the hand-off: what reached its form is the typed pair
        const identities = made.fixture.debugElement.query(By.directive(LinkAnalysisIdentitiesComponent));
        expect(identities.componentInstance.assertForm.getRawValue()).toMatchObject({
            typeA: 'msisdn',
            valueA: '99979100001',
            typeB: 'account',
            valueB: 'SHELL-A',
        });
        made.fixture.destroy();
    });

    it('keeps a disabled Merge focusable and states why (aria-disabled + described reason, axe clean)', async () => {
        const { fixture, c, el, select, merge } = await inInvestigation(['msisdn:+44', 'imsi:1', 'plain']);
        select(1);
        const btn = merge()!;
        expect(btn.disabled).toBe(false); // focusable: a disabled button fires no tooltip and is skipped by Tab
        expect(btn.getAttribute('aria-disabled')).toBe('true');
        // the reason has ONE source: the tooltip (which Material also exposes as the button's description)
        const tip = fixture.debugElement.query(By.css('[data-testid="la-selection-merge"]')).injector.get(MatTooltip);
        expect(tip.message).toContain('exactly two');
        expect(el.querySelector('#la-merge-reason')).toBeNull();
        const draft = c.investigation.mergeDraft();
        btn.click(); // the click guard: nothing is prefilled
        expect(c.investigation.mergeDraft()).toBe(draft);
        await expectNoA11yViolations(el.querySelector('[data-testid="la-selection-bar"]') as HTMLElement);
        fixture.destroy();
    });
});

describe('LinkAnalysisComponent - Grow, Held and Exclude on the graph (LA-GROW-EXCLUDE-1)', () => {
    const BASIC_EDITION_FEATURES = { ...STANDARD_EDITION_FEATURES, investigations: signal(false) };
    const notThere = () => throwError(() => new HttpErrorResponse({ status: 404 }));
    /** Lanes A and B absent: every new route 404s — the UI must degrade, never error. */
    const OLD_SERVER: Partial<InvService> = {
        exclusionLists: notThere,
        listEntityLists: notThere,
        previewExpand: notThere,
        previewNeighbors: notThere,
    };
    const drawn = async (opts: Parameters<typeof create>[0] = {}) => {
        const made = create({ inv: OLD_SERVER, ...opts });
        made.fixture.detectChanges();
        await runQuery(made.fixture);
        made.fixture.detectChanges();
        const el = made.fixture.nativeElement as HTMLElement;
        const stub = made.fixture.debugElement.query(By.directive(StubGraphView)).componentInstance as StubGraphView;
        return { ...made, c: made.fixture.componentInstance, el, stub };
    };
    const press = (el: HTMLElement, init: KeyboardEventInit) =>
        el.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, ...init }));
    const settle = async (fixture: ReturnType<typeof create>['fixture'], ms = 0) => {
        await new Promise((r) => setTimeout(r, ms));
        await fixture.whenStable();
        fixture.detectChanges();
    };
    afterEach(() => {
        document.querySelectorAll('.cdk-overlay-container').forEach((o) => (o.innerHTML = ''));
    });

    it('the rail offers Grow and Hidden in plain words; G opens Grow, which degrades to "no preview" without lane B', async () => {
        const expand = vi.fn(async () => ({ nodes: [], edges: [] }));
        const { fixture, el } = await drawn({ expand });
        const grow = el.querySelector('[data-testid="la-grow-button"]') as HTMLButtonElement;
        expect(grow.textContent).toContain('Grow');
        expect(grow.getAttribute('aria-keyshortcuts')).toBe('G');
        expect(el.querySelector('[data-testid="la-exclusions-button"]')?.getAttribute('aria-label')).toBe(
            'Hidden by lists: 0 hidden',
        );
        press(el, { key: 'g' });
        await settle(fixture);
        await settle(fixture); // the panel's preview starts on its first render
        const panel = el.querySelector('[data-testid="la-grow-panel"]') as HTMLElement;
        expect(panel.closest('[data-testid="la-graph-region"]')).toBeTruthy(); // an overlay on the canvas
        expect(panel.textContent).toContain('(not recorded)');
        expect(el.querySelector('[data-testid="la-grow-no-preview"]')).not.toBeNull();
        expect(grow.getAttribute('aria-expanded')).toBe('true');
        await expectNoA11yViolations(panel);
        // typing G in a field never opens it
        fixture.componentInstance.closeGrow();
        fixture.detectChanges();
        const input = document.createElement('input');
        el.appendChild(input);
        press(input, { key: 'g' });
        expect(fixture.componentInstance.growExclude.growOpen()).toBe(false);
    });

    it('Basic preview: ONE neighbours/preview over the whole frontier (ids, drawn, direction, hold threshold), never one per node', async () => {
        const previewNeighbors = vi.fn(() =>
            of({
                addsNodes: 4,
                addsLinks: 6,
                held: [],
                heldCount: 0,
                excludedByLists: [],
                truncated: false,
                readPath: 'flat',
                linksWithinGraph: 3, // both ends shown: a-b and b-c are drawn, so 1 more the Grow will add
            }),
        );
        const expand = vi.fn(async () => ({ nodes: [], edges: [] }));
        const { fixture, c, el } = await drawn({ expand, inv: { ...OLD_SERVER, previewNeighbors }, features: BASIC_EDITION_FEATURES });
        c.onSelectionChange(['a', 'b']);
        press(el, { key: 'g' });
        await settle(fixture);
        await settle(fixture);
        expect(previewNeighbors).toHaveBeenCalledTimes(1);
        const body = (previewNeighbors.mock.calls[0] as unknown[])[0] as Record<string, unknown>;
        expect(body).toEqual({
            dataset: 'links-ds',
            sourceCol: 'source',
            targetCol: 'target',
            ids: ['A', 'B'], // the raw values, as the neighbours read names them
            // LA-GROW-EXCLUDE-1: the rest of the graph, which the Grow cannot add again - C (b's neighbour) first. Without
            // it a graph the query drew whole previewed "about 5 nodes" that its Grow then reported as 0.
            drawn: ['C', 'D', 'E'],
            direction: 'either',
            hubThreshold: c.growThreshold(),
        });
        expect(el.querySelector('[data-testid="la-grow-preview"]')?.textContent).toContain(
            'This step adds about 4 nodes and 7 links.',
        );

        // The view's predicate rides along, as it does on the Grow's own neighbours reads (expandNode → run.query).
        const filter: ConditionGroup = {
            kind: 'group',
            op: 'AND',
            items: [{ kind: 'condition', field: 'kind', operator: '=', value: 'x' }],
        };
        c.lastRun.update((r) => r && { ...r, query: { ...r.query, filter } });
        await c.growBasicAdapter.preview(['a'], {
            direction: 'out',
            steps: 1,
            linkKinds: null,
            window: 'inherit',
            hold: true,
            threshold: 500,
        });
        expect((previewNeighbors.mock.calls[1] as unknown[])[0]).toMatchObject({
            ids: ['A'],
            drawn: ['B', 'C', 'D', 'E'],
            filter,
        });
    });

    it('Basic preview: a 403 (restricted list) shows the server reason in the Grow panel, and Grow still runs', async () => {
        const reason = "entity list 'politicians' is restricted: you may not preview an exclusion by it";
        const previewNeighbors = vi.fn(() =>
            throwError(() => new HttpErrorResponse({ status: 403, error: { error: { code: 'FORBIDDEN', message: reason } } })),
        );
        const expand = vi.fn(async () => ({ nodes: [], edges: [] }));
        const { fixture, c, el } = await drawn({ expand, inv: { ...OLD_SERVER, previewNeighbors }, features: BASIC_EDITION_FEATURES });
        c.onSelectionChange(['a']);
        press(el, { key: 'g' });
        await settle(fixture);
        await settle(fixture);
        expect(c.growExclude.preview()).toMatchObject({ state: 'error', message: reason });
        const shown = el.querySelector('[data-testid="la-grow-preview"]') as HTMLElement;
        expect(shown.textContent).toContain('The preview was refused');
        expect(shown.textContent).toContain(reason);
        expect(el.querySelector('[data-testid="la-grow-no-preview"]')).toBeNull();
        expect((el.querySelector('[data-testid="la-grow-go"]') as HTMLButtonElement).getAttribute('aria-disabled')).not.toBe('true');
        await expectNoA11yViolations(el.querySelector('[data-testid="la-grow-panel"]') as HTMLElement);
    });

    it('Basic Grow: the explorer grows through neighbours in the chosen direction, says so, and Ctrl+Z undoes it', async () => {
        const expand = vi.fn(async () => ({
            nodes: [
                { id: 'c', data: { label: 'C', kind: 'entity' } },
                { id: 'f', data: { label: 'F', kind: 'entity' } },
                { id: 'g', data: { label: 'G', kind: 'entity' } },
            ],
            edges: [
                { id: 'c->f', source: 'c', target: 'f', data: { kind: 'link' } },
                { id: 'g->c', source: 'g', target: 'c', data: { kind: 'link' } },
            ],
        }));
        const { fixture, c, el } = await drawn({ expand, features: BASIC_EDITION_FEATURES });
        c.onSelectionChange(['c']);
        expect(c.growContext()).toMatchObject({ mode: 'basic', frontier: ['c'], from: 'the 1 node you selected', blocked: null });
        await c.onGrow({ direction: 'out', steps: 1, linkKinds: null, window: 'inherit', hold: true, threshold: 500 });
        fixture.detectChanges();
        const ids = () => c.graph()!.nodes.map((n) => n.id).sort();
        expect(ids()).toEqual(['a', 'b', 'c', 'd', 'e', 'f']); // the incoming g was not kept
        expect(el.querySelector('[data-testid="la-change-line"]')?.textContent).toContain(
            'Grew one step: 1 node and 1 link added (not recorded).',
        );
        press(el, { key: 'z', ctrlKey: true });
        fixture.detectChanges();
        expect(ids()).toEqual(['a', 'b', 'c', 'd', 'e']);
        expect(el.querySelector('[data-testid="la-change-line"]')?.textContent).toContain('Undone.');
    });

    it('Basic Grow Undo is withdrawn once the graph changed after it (a later expand is never thrown away)', async () => {
        const expand = vi.fn(async (id: string) => ({
            nodes: [{ id, data: { label: id.toUpperCase(), kind: 'entity' } }, { id: id + '2', data: { label: 'X', kind: 'entity' } }],
            edges: [{ id: id + '->' + id + '2', source: id, target: id + '2', data: { kind: 'link' } }],
        }));
        const { fixture, c, el } = await drawn({ expand, features: BASIC_EDITION_FEATURES });
        c.onSelectionChange(['a']);
        await c.onGrow({ direction: 'either', steps: 1, linkKinds: null, window: 'inherit', hold: true, threshold: 500 });
        expect(c.growExclude.undoable()).toBe(true);
        await c.expandNode('b', 'B'); // another change to the graph
        fixture.detectChanges();
        expect(c.growExclude.undoable()).toBe(false);
        expect(el.querySelector('[data-testid="la-change-undo"]')).toBeNull();
        expect(el.querySelector('[data-testid="la-change-stale"]')?.textContent).toContain('a later change came after it');
        press(el, { key: 'z', ctrlKey: true });
        expect(c.graph()!.nodes.map((n) => n.id)).toContain('b2'); // the later expand survives
    });

    it('a Basic hide Undo brings back only what it hid', async () => {
        const { fixture, c } = await drawn({ features: BASIC_EDITION_FEATURES });
        c.hiddenNodeIds.set(['e']);
        c.onSelectionChange(['a']);
        c.openExclude(['a']);
        await settle(fixture);
        (document.querySelector('[data-testid="la-exclude-go"]') as HTMLButtonElement).click();
        await settle(fixture);
        expect(c.hiddenNodeIds()).toEqual(['e', 'a']);
        c.hiddenNodeIds.update((h) => [...h, 'd']); // hidden later, elsewhere
        await c.growExclude.undoLast();
        expect(c.hiddenNodeIds()).toEqual(['e', 'd']);
    });

    it('Basic Grow is blocked, with the reason, when the graph source has no neighbours read', async () => {
        const { c } = await drawn({ features: BASIC_EDITION_FEATURES });
        expect(c.growContext().blocked).toBe('Grow needs a graph drawn from one Entity/Link mapping (its neighbours read).');
    });

    it('X with nothing selected says to select first; with a selection it opens Exclude… and a Basic hide is undoable', async () => {
        const { fixture, c, el } = await drawn({ features: BASIC_EDITION_FEATURES });
        press(el, { key: 'x' });
        expect(c.drawAnnouncement()).toContain('Select the nodes to exclude first');
        c.onSelectionChange(['a', 'b']);
        press(el, { key: 'x' });
        await settle(fixture);
        const dialog = document.querySelector('mat-dialog-container') as HTMLElement;
        expect(dialog.querySelector('h2')?.textContent).toContain('Exclude 2 nodes');
        // lane A absent: the list choice says why it is not possible
        expect(dialog.querySelector('[data-testid="la-exclude-list-hint"]')?.textContent).toContain(
            'Exclusion lists cannot be read on this server.',
        );
        (dialog.querySelector('[data-testid="la-exclude-go"]') as HTMLButtonElement).click();
        await settle(fixture);
        expect(c.hiddenNodeIds()).toEqual(['a', 'b']);
        expect(c.canvasData()!.nodes.map((n) => n.id)).toEqual(['c', 'd', 'e']);
        expect(el.querySelector('[data-testid="la-change-line"]')?.textContent).toContain('2 nodes hidden (not recorded).');
        (el.querySelector('[data-testid="la-change-undo"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(c.hiddenNodeIds()).toEqual([]);
    });

    it('right-click a node: the menu acts on that node, or on the whole selection it belongs to', async () => {
        const { fixture, c, stub } = await drawn();
        c.onSelectionChange(['a', 'b']);
        stub.nodeContextMenu.emit({ id: 'd', x: 40, y: 50 });
        expect(c.nodeMenuTargets()).toEqual(['d']);
        expect(c.nodeMenuAt()).toEqual({ x: 40, y: 50 });
        stub.nodeContextMenu.emit({ id: 'a', x: 1, y: 2 });
        expect(c.nodeMenuTargets()).toEqual(['a', 'b']);
        await settle(fixture, 10);
        const exclude = document.querySelector('[data-testid="la-node-menu-exclude"]') as HTMLButtonElement;
        expect(exclude.textContent).toContain('Exclude 2…');
        (document.querySelector('[data-testid="la-node-menu-grow"]') as HTMLButtonElement).click();
        await settle(fixture);
        expect(c.growExclude.growOpen()).toBe(true);
        expect(c.growContext().from).toBe('the 2 nodes you selected');
    });

    it('Investigation: Grow appends ONE expand from the outer ring; held busy nodes carry the badge; Remove held is one exclude', async () => {
        const { c } = await drawn();
        c.investigation.activeId.set('inv-9');
        c.investigation.log.set({
            header: { dataset: 'd', sourceCol: 's', targetCol: 't', title: 'Ring' },
            entries: [],
        } as never);
        c.investigation.workingSet.set({
            entities: [
                { id: 'ws-a', hop: 0, hidden: false },
                { id: 'ws-b', hop: 1, hidden: false },
                { id: 'ws-hub', hop: 1, hidden: false, highConnectivity: true, contacts: 9000 },
            ],
            links: [
                { source: 'ws-a', target: 'ws-b', kind: 'calls', count: 1 },
                { source: 'ws-a', target: 'ws-hub', kind: 'calls', count: 1 },
            ],
            excluded: [],
            hash: 'h',
        } as never);
        expect(c.growContext()).toMatchObject({
            mode: 'investigation',
            frontier: ['ws-b'],
            heldFrontier: ['ws-hub'],
            from: 'the outer ring (degree 1): 2 nodes',
            blocked: null,
        });
        expect(c.heldItems()).toEqual([{ id: 'ws-hub', label: 'ws-hub', contacts: 9000 }]);
        const hub = c.canvasData()!.nodes.find((n) => n.data.label === 'ws-hub')!;
        expect(hub.data.held).toBe(true);
        expect(c.canvasData()!.nodes.find((n) => n.data.label === 'ws-b')!.data.held).toBeUndefined();

        const ops: unknown[] = [];
        vi.spyOn(c.investigation, 'apply').mockImplementation(async (op) => {
            ops.push(op);
            c.investigation.lastStep.set({ step: ops.length, op: op.op, delta: { admitted: [], excluded: ['ws-hub'], linksAdded: 0 } } as never);
            return true;
        });
        await c.onGrow({ direction: 'either', steps: 1, linkKinds: null, window: 'inherit', hold: true, threshold: 500 });
        expect(ops).toEqual([{ op: 'expand', ids: ['ws-b'], direction: 'either', hubThreshold: 500 }]);
        await c.removeHeld();
        expect(ops[1]).toEqual({ op: 'exclude', ids: ['ws-hub'], reason: 'busy node above 500 contacts' });
        expect(c.growExclude.lastChange()?.text).toBe('Removed 1 busy node.');
    });

    it('Investigation without Incident management: Grow says why it is not possible', async () => {
        const { c } = await drawn();
        c.investigation.activeId.set('inv-9');
        c.investigation.log.set({ header: { dataset: 'd', sourceCol: 's', targetCol: 't' }, entries: [] } as never);
        c.investigation.workingSet.set({
            entities: [{ id: 'ws-a', hop: 0, hidden: false }],
            links: [],
            excluded: [],
            hash: 'h',
        } as never);
        const session = TestBed.inject(SessionService);
        session.authMode.set('oidc');
        session.capabilities.set([]);
        expect(c.growContext().blocked).toBe(
            'This changes the Investigation log and needs the Incident management capability.',
        );
        expect(c.growWriteWhyNot()).toContain('Incident management');
    });
});

describe('LinkAnalysisComponent - canvas overlays at laptop width (LA-ONBOARD-FIXES-1, F-11)', () => {
    const LEGEND = 'inspecto.la.overlayOpen.legend';
    const WS = 'inspecto.la.overlayOpen.workingSet';
    const clear = () => [LEGEND, WS].forEach((k) => localStorage.removeItem(k));
    beforeEach(clear);
    afterEach(() => {
        clear();
        vi.restoreAllMocks();
    });

    it('on an ~800px canvas the legend and Working set start as their pills, expandable, and axe-clean', async () => {
        const { fixture } = create({ stubGraph: true });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        c.fitOverlaysToWidth(800);
        fixture.detectChanges();
        expect([c.legendOpen(), c.workingSetOpen()]).toEqual([false, false]);
        const el = fixture.nativeElement as HTMLElement;
        const legend = el.querySelector('inspecto-link-analysis-legend') as HTMLElement;
        const ws = el.querySelector('inspecto-link-analysis-working-set') as HTMLElement;
        expect(legend.querySelector('[aria-label="Node kinds"]')).toBeNull();
        expect(ws.querySelector('[aria-label="Working set statistics"]')).toBeNull();
        await expectNoA11yViolations(legend);
        await expectNoA11yViolations(ws);
        expect(localStorage.getItem(LEGEND)).toBeNull(); // the width rule is not the user's choice

        (legend.querySelector('button[aria-label="Show the legend"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(c.legendOpen()).toBe(true);
        expect(legend.querySelector('[aria-label="Node kinds"]')).toBeTruthy();
        expect(localStorage.getItem(LEGEND)).toBe('true'); // an explicit open is remembered
    });

    it('a wide canvas starts them open, and the rule decides once (a later narrow reading collapses nothing)', () => {
        const { fixture } = create();
        const c = fixture.componentInstance;
        c.fitOverlaysToWidth(1400);
        expect([c.legendOpen(), c.workingSetOpen()]).toEqual([true, true]);
        c.fitOverlaysToWidth(600);
        expect([c.legendOpen(), c.workingSetOpen()]).toEqual([true, true]);
    });

    it('a choice this browser remembers wins over the width rule, both ways', () => {
        localStorage.setItem(LEGEND, 'true');
        localStorage.setItem(WS, 'false');
        const { fixture } = create();
        const c = fixture.componentInstance;
        c.fitOverlaysToWidth(800);
        expect(c.legendOpen()).toBe(true);
        c.fitOverlaysToWidth(1400);
        expect(c.workingSetOpen()).toBe(false);
        c.setOverlayOpen('workingSet', true); // View > Overlays, or the pill
        expect(localStorage.getItem(WS)).toBe('true');
    });

    it('a saved view stating the overlays open does not force them open on a narrow canvas; its "collapsed" stands', async () => {
        const { fixture } = create();
        const c = fixture.componentInstance;
        const view = (legend: boolean, workingSet: boolean) =>
            ({
                id: 'v',
                name: 'v',
                sourceId: 'entity-projection',
                query: { projection: { datasetId: 'links-ds', sourceCol: 'source', targetCol: 'target' } },
                view: { legend, workingSet },
            }) as LinkAnalysisView;
        // loaded before the canvas was measured (wide until known), then the first measurement says narrow
        await c.loadView(view(true, true));
        expect([c.legendOpen(), c.workingSetOpen()]).toEqual([true, true]);
        c.fitOverlaysToWidth(800);
        expect([c.legendOpen(), c.workingSetOpen()]).toEqual([false, false]);
        // the showcase starter view states both open; loaded on the measured narrow canvas they stay pills
        await c.loadView(view(true, true));
        expect([c.legendOpen(), c.workingSetOpen()]).toEqual([false, false]);
        // a wide canvas: the view's own state, including a collapsed Working set
        c.fitOverlaysToWidth(1400);
        await c.loadView(view(true, false));
        expect([c.legendOpen(), c.workingSetOpen()]).toEqual([true, false]);
    });

    it('with storage blocked the width rule still applies and a toggle still works', () => {
        const { fixture } = create();
        const c = fixture.componentInstance;
        // blocked after the shell's own services are up (they read storage unguarded at construction)
        vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
            throw new Error('blocked');
        });
        vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
            throw new Error('blocked');
        });
        c.fitOverlaysToWidth(800);
        expect([c.legendOpen(), c.workingSetOpen()]).toEqual([false, false]);
        c.setOverlayOpen('legend', true);
        expect(c.legendOpen()).toBe(true);
    });
});

describe('LinkAnalysisComponent - Start from... (LA-ONBOARD-1 phase 6, OB-9; F-7, F-8)', () => {
    const BASIC: LaFeatures = { ...STANDARD_EDITION_FEATURES, investigations: signal(false) };
    /** An imported transfers file (phase 2): its primary Entity Type and default link on the registry entry. */
    const TRANSFERS: Dataset = {
        ...DS,
        id: 'transfers',
        name: 'Transfers',
        sourceName: 'transfers',
        primaryEntityType: 'account',
        defaultLinkMapping: { from: 'from_account', to: 'to_account', kind: 'channel' },
    };
    const MAPPING = {
        datasetId: 'transfers',
        sourceCol: 'from_account',
        targetCol: 'to_account',
        linkKindCol: 'channel',
        entityType: 'account',
    };
    /** One neighbours read: the value and its ring (R1, R2 and the busy HUB); a value not in the data reads nothing. */
    const ring = (v: string): G6GraphData =>
        v.startsWith('NOPE')
            ? { nodes: [], edges: [] }
            : {
                  nodes: [v, `${v}-R1`, `${v}-R2`, 'HUB'].map((id) => ({ id, data: { label: id, kind: 'entity' } })),
                  edges: [`${v}-R1`, `${v}-R2`, 'HUB'].map((r) => ({
                      id: `${v}->${r}`,
                      source: v,
                      target: r,
                      data: { kind: 'transfer' },
                  })),
              };
    const preview = () =>
        vi.fn(() =>
            of({
                addsNodes: 0,
                addsLinks: 0,
                held: [{ id: 'HUB', contacts: 900 }],
                heldCount: 1,
                excludedByLists: [],
                truncated: false,
                readPath: 'dataset',
            }),
        );
    async function settle(fixture: ReturnType<typeof create>['fixture']) {
        const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        fixture.componentInstance.ngOnInit();
        for (let i = 0; i < 8; i++) await new Promise((r) => setTimeout(r));
        return navigate;
    }

    /**
     * F-8 (2026-10-22 walkthrough): an Entity profile's *Investigate* on Basic navigates `?seed=<account>&entityType=account
     * &dataset=<its Dataset>`. The page used to stop at "Investigations are available in Standard and above", drop the seed
     * and offer "Choose a Dataset", whose pick projected the WHOLE Dataset (312 nodes). It now draws that account plus one
     * ring through the neighbours read - never the whole-Dataset projection - with the busy neighbour held, so Grow goes on.
     */
    it('F-8: ?seed= from an Entity profile on the Explorer level draws that entity and ONE ring, never the whole Dataset', async () => {
        const expand = vi.fn(async (_id: string, label: string) => ring(label));
        const previewNeighbors = preview();
        const { fixture, queried } = create({
            features: BASIC,
            datasets: [TRANSFERS],
            expand,
            inv: { previewNeighbors } as Partial<InvService>,
            queryParams: { seed: 'MULE-HUB-01', entityType: 'account', dataset: 'transfers' },
        });
        const navigate = await settle(fixture);
        const c = fixture.componentInstance;
        expect(queried).toEqual([]); // the whole-Dataset projection never ran
        expect(expand).toHaveBeenCalledWith('MULE-HUB-01', 'MULE-HUB-01', { projection: MAPPING });
        expect(c.graph()!.nodes.map((n) => n.id).sort()).toEqual(['HUB', 'MULE-HUB-01', 'MULE-HUB-01-R1', 'MULE-HUB-01-R2']);
        expect(c.investigateIssue()).toBe('');
        expect(c.expandedNodeIds()).toEqual(['MULE-HUB-01']);
        expect(previewNeighbors).toHaveBeenCalledWith(
            expect.objectContaining({ dataset: 'transfers', ids: ['MULE-HUB-01'], hubThreshold: c.growThreshold() }),
        );
        expect(c.growExclude.basicHeld().get('HUB')).toBe(900);
        expect(c.lastRun()?.query.projection).toMatchObject({ datasetId: 'transfers', sourceCol: 'from_account' });
        // Grow continues from the ring: the seed is grown, the busy one held
        const ctx = c.growContext();
        expect(ctx.blocked).toBeNull();
        expect([...ctx.frontier].sort()).toEqual(['MULE-HUB-01-R1', 'MULE-HUB-01-R2']);
        expect(ctx.heldFrontier).toEqual(['HUB']);
        expect(c.startNotice()?.text).toContain('Drew 1 start and 3 linked');
        expect(c.startNotice()?.text).toContain('1 busy');
        expect(navigate).toHaveBeenCalledWith(
            [],
            expect.objectContaining({ queryParams: { seed: null, entityType: null, dataset: null } }),
        );
    });

    /** The la-sample shape: no default link; the Dataset's link comes from its saved view, its type from the column classification. */
    it('F-8: a Dataset linked only by a saved view and its column types starts the entity once the registry answers', async () => {
        const MULE: Dataset = { ...DS, id: 'mule', name: 'mule', sourceName: 'mule' };
        const view: LinkAnalysisView = {
            id: 'ring',
            name: 'ring',
            sourceId: 'entity-projection',
            query: { projection: { datasetId: 'mule', sourceCol: 'PAYER', targetCol: 'PAYEE', linkKindCol: 'CHANNEL' } },
        };
        const account: EntityTypeSummary = {
            id: 'account',
            label: 'Account',
            normaliser: 'upper-trim',
            masked: false,
            classifications: ['ACCOUNT'],
            bindings: [
                { dataset: 'mule', column: 'PAYEE', role: 'target' },
                { dataset: 'mule', column: 'PAYER', role: 'source' },
            ],
            headline: { assessed: false, reason: null, distinct: null, approximate: false, rows: null, dataAsOf: null, computedAt: null },
        };
        const expand = vi.fn(async (_id: string, label: string) => ring(label));
        const { fixture } = create({
            features: BASIC,
            datasets: [MULE],
            views: [view],
            entityTypes: [account],
            expand,
            inv: { previewNeighbors: preview() } as Partial<InvService>,
            queryParams: { seed: 'MULE-HUB-02', entityType: 'account', dataset: 'mule' },
        });
        await settle(fixture);
        expect(expand).toHaveBeenCalledWith('MULE-HUB-02', 'MULE-HUB-02', {
            // typed by the seed's Entity Type (LA-ONBOARD-DEFECTS-1): the Dataset names no primary type
            projection: {
                datasetId: 'mule',
                sourceCol: 'PAYER',
                targetCol: 'PAYEE',
                linkKindCol: 'CHANNEL',
                entityType: 'account',
            },
        });
        expect(fixture.componentInstance.startNotice()?.text).toContain('Drew 1 start');
    });

    it('F-8: a profile Dataset that does not link the type falls back to one that does', async () => {
        const expand = vi.fn(async (_id: string, label: string) => ring(label));
        const { fixture } = create({
            features: BASIC,
            datasets: [DS, TRANSFERS],
            expand,
            inv: { previewNeighbors: preview() } as Partial<InvService>,
            queryParams: { seed: 'ACC-7', entityType: 'account', dataset: 'links-ds' },
        });
        await settle(fixture);
        expect(expand).toHaveBeenCalledWith('ACC-7', 'ACC-7', { projection: MAPPING });
    });

    it('an Entity Type no Dataset links says so and draws nothing', async () => {
        const expand = vi.fn(async (_id: string, label: string) => ring(label));
        const { fixture } = create({
            features: BASIC,
            datasets: [TRANSFERS],
            expand,
            queryParams: { seed: '35000000001', entityType: 'imei' },
        });
        await settle(fixture);
        const c = fixture.componentInstance;
        expect(expand).not.toHaveBeenCalled();
        expect(c.graph()).toBeNull();
        expect(c.startNotice()?.text).toContain('No Dataset in this Space links imei entities yet');
    });

    it('a paste draws the values it finds and reports the unknown ones', async () => {
        const expand = vi.fn(async (_id: string, label: string) => ring(label));
        const { fixture } = create({
            features: BASIC,
            datasets: [TRANSFERS],
            expand,
            inv: { previewNeighbors: preview() } as Partial<InvService>,
        });
        await settle(fixture);
        const c = fixture.componentInstance;
        await c.startFrom({ kind: 'paste', datasetId: 'transfers', entityType: 'account', values: ['A-1', 'NOPE-9', 'A-2'] });
        expect(expand).toHaveBeenCalledTimes(3);
        expect([...c.expandedNodeIds()].sort()).toEqual(['A-1', 'A-2']);
        expect(c.startNotice()).toEqual({ variant: 'warning', text: expect.stringContaining('Not in Transfers: NOPE-9.') });
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).querySelector('[data-testid="start-outcome"]')?.textContent).toContain(
            'NOPE-9',
        );
    });

    it('a paste where nothing is found draws nothing and says what was not found', async () => {
        const expand = vi.fn(async (_id: string, label: string) => ring(label));
        const { fixture } = create({ features: BASIC, datasets: [TRANSFERS], expand });
        await settle(fixture);
        const c = fixture.componentInstance;
        await c.startFrom({ kind: 'paste', datasetId: 'transfers', entityType: 'account', values: ['NOPE-1', 'NOPE-2'] });
        expect(c.graph()).toBeNull();
        expect(c.startNotice()?.text).toContain('Not in Transfers: NOPE-1, NOPE-2. Nothing was drawn');
    });

    it('a list draws its members with one ring', async () => {
        const expand = vi.fn(async (_id: string, label: string) => ring(label));
        const getEntityList = vi.fn(() => of({ id: 'suspects', title: 'Suspects', members: ['S-1', 'S-2'] }));
        const { fixture } = create({
            features: BASIC,
            datasets: [TRANSFERS],
            expand,
            inv: { previewNeighbors: preview(), getEntityList } as unknown as Partial<InvService>,
        });
        await settle(fixture);
        const c = fixture.componentInstance;
        await c.startFrom({ kind: 'list', datasetId: 'transfers', listId: 'suspects' });
        expect(getEntityList).toHaveBeenCalledWith('suspects');
        expect([...c.expandedNodeIds()].sort()).toEqual(['S-1', 'S-2']);
    });

    /**
     * LA-ONBOARD-DEFECTS-1 (2): the Start box's Entity Type types the drawn mapping. It used to be the Dataset's primary type
     * whatever was picked, so a wallet start over a transfers file was normalised, masked and listed as an account, and *Add
     * to a list* offered account lists for it.
     */
    it('a start types its mapping by the picked Entity Type, not the primary; Add to a list and the case follow it', async () => {
        const expand = vi.fn(async (_id: string, label: string) => ring(label));
        const previewNeighbors = preview();
        const { fixture } = create({
            features: BASIC,
            datasets: [TRANSFERS],
            expand,
            inv: { previewNeighbors } as Partial<InvService>,
        });
        await settle(fixture);
        const c = fixture.componentInstance;
        await c.startFrom({ kind: 'entity', datasetId: 'transfers', entityType: 'wallet', value: 'W-1' });
        expect(expand).toHaveBeenCalledWith('W-1', 'W-1', { projection: { ...MAPPING, entityType: 'wallet' } });
        expect(c.lastRun()?.query.projection?.entityType).toBe('wallet');
        expect(c.growEntityType()).toBe('wallet');
        expect(c.caseStart()).toEqual({ datasetId: 'transfers', values: ['W-1'], entityType: 'wallet' });

        await c.startFrom({ kind: 'paste', datasetId: 'transfers', entityType: 'wallet', values: ['W-2'] });
        expect(expand).toHaveBeenLastCalledWith('W-2', 'W-2', { projection: { ...MAPPING, entityType: 'wallet' } });
        expect(c.caseStart()?.entityType).toBe('wallet');
    });

    it('a list start is typed by the list, whatever the Dataset\'s primary type', async () => {
        const expand = vi.fn(async (_id: string, label: string) => ring(label));
        const getEntityList = vi.fn(() => of({ id: 'w', title: 'Wallets', entityType: 'wallet', members: ['W-1'] }));
        const { fixture } = create({
            features: BASIC,
            datasets: [TRANSFERS],
            expand,
            inv: { previewNeighbors: preview(), getEntityList } as unknown as Partial<InvService>,
        });
        await settle(fixture);
        const c = fixture.componentInstance;
        await c.startFrom({ kind: 'list', datasetId: 'transfers', listId: 'w' });
        expect(expand).toHaveBeenCalledWith('W-1', 'W-1', { projection: { ...MAPPING, entityType: 'wallet' } });
        expect(c.growEntityType()).toBe('wallet');
    });

    it('the whole Dataset projects the default link (the explicit choice)', async () => {
        const { fixture, queried } = create({ features: BASIC, datasets: [TRANSFERS] });
        await settle(fixture);
        await fixture.componentInstance.startFrom({ kind: 'dataset', datasetId: 'transfers' });
        expect(queried).toEqual([{ projection: MAPPING }]);
    });

    it('Start from a Dataset replaces the graph, so a refresh must not reopen the saved view over it', async () => {
        const { fixture } = create({ features: BASIC, datasets: [TRANSFERS] });
        const navigate = await settle(fixture);
        const c = fixture.componentInstance;
        c.openViewId.set('ring');
        await c.startFrom({ kind: 'dataset', datasetId: 'transfers' });
        expect(c.openViewId()).toBeNull();
        expect(navigate).toHaveBeenCalledWith([], expect.objectContaining({ queryParams: { view: null } }));
    });

    it('?dataset= alone preselects that Dataset in the Start box and clears the param', async () => {
        const { fixture } = create({ features: BASIC, datasets: [DS, TRANSFERS], queryParams: { dataset: 'transfers' } });
        const navigate = await settle(fixture);
        fixture.detectChanges();
        const c = fixture.componentInstance;
        expect(c.startDataset()).toBe('transfers');
        expect(navigate).toHaveBeenCalledWith([], expect.objectContaining({ queryParams: { dataset: null } }));
        const picker = (fixture.nativeElement as HTMLElement).querySelector('[data-testid="start-dataset"]');
        expect(picker?.textContent).toContain('Transfers');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('Investigations level, one entity: an account seeds an Investigation over its Dataset (generic binding, OB-9)', async () => {
        const { fixture } = create({ datasets: [TRANSFERS] });
        await settle(fixture);
        const c = fixture.componentInstance;
        await c.startFrom({ kind: 'entity', datasetId: 'transfers', entityType: 'account', value: 'MULE-HUB-01' });
        expect(c.pendingInvestigation()).toMatchObject({ seed: 'MULE-HUB-01', projection: MAPPING, presets: {} });
        expect(c.investigateIssue()).toBe('');
        expect(c.toolboxTab()).toBe('investigation');
    });

    it('Investigations level, a paste: drawn with one ring and queued as the first seeds', async () => {
        const expand = vi.fn(async (_id: string, label: string) => ring(label));
        const { fixture } = create({
            datasets: [TRANSFERS],
            expand,
            inv: { previewNeighbors: preview() } as Partial<InvService>,
        });
        await settle(fixture);
        const c = fixture.componentInstance;
        await c.startFrom({ kind: 'paste', datasetId: 'transfers', entityType: 'account', values: ['A-1', 'A-2'] });
        expect(c.investigation.queuedSeeds().map((s) => s.id)).toEqual(['A-1', 'A-2']);
        expect(c.toolboxTab()).toBe('investigation');
        expect(c.graph()).not.toBeNull();
    });
});

describe('LinkAnalysisComponent - a case, and Find something interesting (LA-ONBOARD-1 phase 7)', () => {
    const BASIC: LaFeatures = { ...STANDARD_EDITION_FEATURES, investigations: signal(false) };
    const TRANSFERS: Dataset = {
        ...DS,
        id: 'transfers',
        name: 'Transfers',
        sourceName: 'transfers',
        primaryEntityType: 'account',
        defaultLinkMapping: { from: 'from_account', to: 'to_account' },
    };
    const ring = (v: string): G6GraphData => ({
        nodes: [v, `${v}-R1`, `${v}-R2`].map((id) => ({ id, data: { label: id, kind: 'entity' } })),
        edges: [`${v}-R1`, `${v}-R2`].map((r) => ({ id: `${v}->${r}`, source: v, target: r, data: { kind: 'transfer' } })),
    });
    const noPreview = () => throwError(() => new HttpErrorResponse({ status: 404 }));
    async function ready(made: ReturnType<typeof create>) {
        made.fixture.detectChanges(); // ngOnInit, and the query panel a saved view's query is built from
        for (let i = 0; i < 8; i++) await new Promise((r) => setTimeout(r));
        made.fixture.detectChanges();
        return made.fixture.componentInstance;
    }
    afterEach(() => document.querySelectorAll('.cdk-overlay-container').forEach((o) => (o.innerHTML = '')));

    it('Save as a case keeps the list it started from, the exclusion list written from it, and its start (A-1b)', async () => {
        const expand = vi.fn(async (_id: string, label: string) => ring(label));
        const getEntityList = vi.fn(() => of({ id: 'suspects', title: 'Suspects', members: ['S-1'] }));
        const made = create({
            features: BASIC,
            datasets: [TRANSFERS],
            expand,
            inv: { previewNeighbors: noPreview, getEntityList } as unknown as Partial<InvService>,
        });
        const c = await ready(made);
        await c.startFrom({ kind: 'list', datasetId: 'transfers', listId: 'suspects' });
        // *Add to a list* from a result, as an exclusion list: the case now uses it
        const open = vi.spyOn((c as unknown as { dialog: MatDialog }).dialog, 'open').mockReturnValue({
            afterClosed: () => of({ listId: 'noisy', title: 'Noisy', purpose: 'exclusion', message: 'Added.' }),
        } as never);
        c.addFoundToList({
            row: { key: 'S-1-R1', title: 'S-1-R1', reading: '', nodeIds: ['S-1-R1'], edgeIds: [], values: ['S-1-R1'], what: 'S-1-R1' },
            what: 'S-1-R1',
            purpose: 'exclusion',
        });
        // phase 8 live drive: untyped explorer nodes take the start Dataset's primary Entity Type, so the dialog can
        // offer "Create a new list…" instead of an empty choice
        expect((open.mock.calls[0][1] as { data: { entityType: string | null } }).data.entityType).toBe('account');
        expect(c.caseLists()).toEqual({ start: 'suspects', exclusions: ['noisy'] });
        expect(c.hiddenNodeIds()).toEqual(['S-1-R1']); // out of sight now, as Exclude → Add to a list does
        c.saveForm.setValue({ name: 'Ring one', description: '' });
        await c.saveView();
        expect(made.save).toHaveBeenCalledTimes(1);
        const saved = made.save.mock.calls[0][0] as LinkAnalysisView;
        expect(saved.lists).toEqual({ start: 'suspects', exclusions: ['noisy'] });
        // a list start re-reads the list when the case opens: nothing beyond its members was grown, so no values
        expect(saved.start).toEqual({ datasetId: 'transfers', listId: 'suspects' });
    });

    it('a pasted start is saved with its values; a fresh graph from the whole Dataset forgets the start', async () => {
        const expand = vi.fn(async (_id: string, label: string) => ring(label));
        const made = create({
            features: BASIC,
            datasets: [TRANSFERS],
            expand,
            inv: { previewNeighbors: noPreview } as Partial<InvService>,
        });
        const c = await ready(made);
        await c.startFrom({ kind: 'paste', datasetId: 'transfers', entityType: 'account', values: ['A-1', 'A-2'] });
        expect(c.caseStart()).toEqual({ datasetId: 'transfers', values: ['A-1', 'A-2'], entityType: 'account' });
        await c.startFrom({ kind: 'dataset', datasetId: 'transfers' });
        expect(c.caseStart()).toBeNull();
        expect(c.caseLists()).toEqual({ exclusions: [] });
    });

    it('opening a case draws its start again (never the whole Dataset) and hides what its exclusion list holds now', async () => {
        const expand = vi.fn(async (_id: string, label: string) => ring(label));
        const getEntityList = vi.fn((id: string) => of({ id, title: 'Noisy', members: ['a-1-r2'] }));
        const made = create({
            features: BASIC,
            datasets: [TRANSFERS],
            expand,
            inv: { previewNeighbors: noPreview, getEntityList } as unknown as Partial<InvService>,
        });
        const c = await ready(made);
        await c.loadView({
            id: 'ring-one',
            name: 'Ring one',
            sourceId: 'entity-projection',
            query: { projection: { datasetId: 'transfers', sourceCol: 'from_account', targetCol: 'to_account' } },
            start: { datasetId: 'transfers', values: ['A-1'] },
            lists: { exclusions: ['noisy'] },
        });
        expect(made.queried).toEqual([]); // the whole-Dataset projection never ran
        expect(expand).toHaveBeenCalledWith('A-1', 'A-1', expect.anything());
        expect(c.hiddenNodeIds()).toEqual(['A-1-R2']);
        expect(c.caseLists()).toEqual({ start: undefined, exclusions: ['noisy'] });
        expect(c.caseStart()).toEqual({ datasetId: 'transfers', values: ['A-1'] });
    });

    it('a case whose Dataset no longer starts a graph reruns its saved query instead', async () => {
        const made = create({
            features: BASIC,
            datasets: [TRANSFERS],
            inv: { previewNeighbors: noPreview } as Partial<InvService>,
        });
        const c = await ready(made);
        const query = { projection: { datasetId: 'gone', sourceCol: 'a', targetCol: 'b' } };
        await c.loadView({
            id: 'old',
            name: 'Old',
            sourceId: 'entity-projection',
            query,
            start: { datasetId: 'gone', values: ['X'] },
        });
        expect(made.queried).toEqual([query]);
    });

    it('Start from these draws a result over the graph Dataset (the "busiest / a community" start kind)', async () => {
        const expand = vi.fn(async (_id: string, label: string) => ring(label));
        const made = create({
            features: BASIC,
            datasets: [TRANSFERS],
            expand,
            inv: { previewNeighbors: noPreview } as Partial<InvService>,
        });
        const c = await ready(made);
        await c.startFrom({ kind: 'paste', datasetId: 'transfers', entityType: 'account', values: ['A-1'] });
        expect(c.discoverStartWhyNot()).toBe('');
        expand.mockClear();
        await c.startFromFound({
            row: { key: 'A-1-R1', title: 'A-1-R1', reading: '', nodeIds: ['A-1-R1'], edgeIds: [], values: ['A-1-R1'], what: '' },
            what: 'A-1-R1, the busiest account',
        });
        expect(expand).toHaveBeenCalledWith('A-1-R1', 'A-1-R1', expect.anything());
        expect(c.startNotice()?.text).toMatch(/^Started from A-1-R1, the busiest account\. Drew 1 start/);
    });

    it('Analysis leads with Find something interesting; Save as a case states not-evidence, the lists, the locked Investigation', async () => {
        const expand = vi.fn(async (_id: string, label: string) => ring(label));
        const made = create({
            features: BASIC,
            datasets: [TRANSFERS],
            expand,
            inv: { previewNeighbors: noPreview } as Partial<InvService>,
        });
        const c = await ready(made);
        await c.startFrom({ kind: 'paste', datasetId: 'transfers', entityType: 'account', values: ['A-1'] });
        c.toolboxDockOpen.set(true);
        made.fixture.detectChanges();
        const el = made.fixture.nativeElement as HTMLElement;
        const discover = el.querySelector('[data-testid="la-discover"]') as HTMLElement;
        expect(discover.textContent).toContain('Who is busiest');
        (el.querySelector('[data-testid="la-discover-busiest"]') as HTMLButtonElement).click();
        made.fixture.detectChanges();
        expect(discover.querySelector('[data-testid="la-discover-reading"]')?.textContent).toContain(
            'A-1 takes part in 2 links, the most on this graph.',
        );
        const trigger = made.fixture.debugElement
            .queryAll(By.directive(MatMenuTrigger))
            .find((d) => (d.nativeElement as HTMLElement).dataset['testid'] === 'la-save-case')!
            .injector.get(MatMenuTrigger);
        trigger.openMenu();
        made.fixture.detectChanges();
        const menu = document.querySelector('.cdk-overlay-container form[aria-label="Save as a case"]') as HTMLElement;
        expect(menu.querySelector('[data-testid="la-case-not-evidence"]')?.textContent).toContain('Not evidence');
        expect(menu.querySelector('[data-testid="la-case-lists"]')?.textContent).toContain('It uses no Entity List yet.');
        expect(menu.querySelector('[data-testid="locked-action"]')?.textContent).toContain(
            'Start an Investigation from this',
        );
        await expectNoA11yViolations(discover);
    });
});

describe('LinkAnalysisComponent - smart data fetch (PERF-P10a)', () => {
    async function drawn(opts: Parameters<typeof create>[0] = {}) {
        const made = create(opts);
        made.fixture.detectChanges();
        await runQuery(made.fixture);
        return { ...made, c: made.fixture.componentInstance };
    }
    /** Fire pushFilter on fake timers and let the debounce elapse. */
    async function pushAndWait(c: LinkAnalysisComponent): Promise<void> {
        vi.useFakeTimers();
        try {
            const p = c.pushFilter();
            await vi.advanceTimersByTimeAsync(PUSH_FILTER_DEBOUNCE_MS);
            await p;
        } finally {
            vi.useRealTimers();
        }
    }

    it('an identical re-run is served from the client with no source call, a different filter makes one, and "as of" is shown', async () => {
        const { c, queried } = await drawn();
        expect(queried).toHaveLength(1);
        expect(c.cachedAsOf()).toBeNull();
        await c.run(); // same form, same query
        expect(queried).toHaveLength(1);
        expect(c.cachedAsOf()).not.toBeNull();
        c.filterWhere().items.push({ kind: 'condition', field: 'source', operator: '=', value: 'D' });
        await pushAndWait(c);
        expect(queried).toHaveLength(2);
        await c.refreshFromServer(); // the Refresh bypass always asks
        expect(queried).toHaveLength(3);
        expect(c.cachedAsOf()).toBeNull();
    });

    it('PERF-P10a-2: an EXPIRED entry whose re-read folds to the same graph keeps the previous graph object; canvasData keeps its identity', async () => {
        const { c, queried } = await drawn();
        const src = TestBed.inject(GraphSourcesService).byId('entity-projection')!;
        const orig = src.query.bind(src);
        let variant: 'same' | 'changed' = 'same';
        // what a 304 round trip yields: a fresh fold of identical content (a new object every time)
        src.query = async (q) => {
            const g = structuredClone(await orig(q));
            if (variant === 'changed') g.nodes.push({ id: 'z', data: { label: 'Z', kind: 'entity' } } as never);
            return g;
        };
        await c.run(); // inside the trust window: served from the client
        const g1 = c.graph();
        const canvas1 = c.canvasData();
        const n = queried.length;
        const t0 = Date.now();
        const now = vi.spyOn(Date, 'now').mockReturnValue(t0 + GRAPH_CACHE_TTL_MS + 1);
        try {
            await c.run(); // expired -> source.query runs again
            expect(queried).toHaveLength(n + 1);
            expect(c.cachedAsOf()).toBeNull();
            expect(c.graph()).toBe(g1);
            expect(c.canvasData()).toBe(canvas1);
            variant = 'changed';
            now.mockReturnValue(t0 + 2 * GRAPH_CACHE_TTL_MS + 2);
            await c.run(); // expired again, but the content really changed -> a NEW graph
            expect(queried).toHaveLength(n + 2);
            expect(c.graph()).not.toBe(g1);
            expect(c.graph()!.nodes.map((x) => x.id)).toContain('z');
            expect(c.canvasData()).not.toBe(canvas1);
        } finally {
            now.mockRestore();
        }
    });

    it('a burst of pushFilter calls makes ONE request after the pause, carrying the last filter', async () => {
        const { c, queried } = await drawn();
        vi.useFakeTimers();
        try {
            const calls: Promise<void>[] = [];
            for (const v of ['A', 'B', 'C']) {
                c.filterWhere().items = [{ kind: 'condition', field: 'source', operator: '=', value: v }];
                calls.push(c.pushFilter());
                await vi.advanceTimersByTimeAsync(PUSH_FILTER_DEBOUNCE_MS - 50);
            }
            expect(queried).toHaveLength(1); // nothing sent during the burst
            await vi.advanceTimersByTimeAsync(PUSH_FILTER_DEBOUNCE_MS);
            await Promise.all(calls);
        } finally {
            vi.useRealTimers();
        }
        expect(queried).toHaveLength(2);
        expect(JSON.stringify((queried[1] as { filter?: unknown }).filter)).toContain('"C"');
    });

    it('a change of Space or of signed-in user empties the cache: the same query asks the server again', async () => {
        const { c, queried } = await drawn();
        await c.run();
        expect(queried).toHaveLength(1);
        TestBed.inject(SpacesService).currentSpaceId.set('other-space');
        await c.run();
        expect(queried).toHaveLength(2);
        await c.run();
        expect(queried).toHaveLength(2);
        TestBed.inject(SessionService).actor.set('someone-else');
        await c.run();
        expect(queried).toHaveLength(3);
    });

    it('local actions make ZERO server calls (hide, kind filter, size, spacing, layout, selection, legend, local filter)', async () => {
        const { c, queried, fixture } = await drawn();
        const n = queried.length;
        const ids = c.graph()!.nodes.map((x) => x.id);
        c.hiddenNodeIds.set([ids[0]]);
        c.hiddenLinkKinds.set(['pays']);
        c.toggleKind('account', false);
        c.nodeSize.set(c.nodeSizeOptions[0].value);
        c.setLayoutSpacing(c.spacingOptions[0].value);
        c.setLayout(c.layouts[0].id);
        c.onSelectionChange([ids[1]]);
        c.legendOpen.set(!c.legendOpen());
        c.filterWhere().items.push({ kind: 'condition', field: 'source', operator: '=', value: 'D' });
        c.onFilterChanged();
        c.applyFilterLocally();
        c.clearFilter();
        fixture.detectChanges();
        expect(queried).toHaveLength(n);
    });
});

describe('LinkAnalysisComponent - cache keeps the masking badge honest, and a destroyed page sends nothing (PERF-P10a review)', () => {
    /** Make the fake source behave like InvService.noted: each read sets the note its response carried (or none). */
    function notes(map: (q: unknown) => MaskingNote | null) {
        const src = TestBed.inject(GraphSourcesService).byId('entity-projection')!;
        const inv = TestBed.inject(InvService);
        const orig = src.query.bind(src);
        src.query = async (q) => {
            const r = await orig(q);
            const n = map(q);
            if (n) inv.masking.set(n);
            return r;
        };
        return inv;
    }
    async function pushOnce(c: LinkAnalysisComponent): Promise<void> {
        vi.useFakeTimers();
        try {
            const p = c.pushFilter();
            await vi.advanceTimersByTimeAsync(PUSH_FILTER_DEBOUNCE_MS);
            await p;
        } finally {
            vi.useRealTimers();
        }
    }
    const MASKED = { maskingMode: 'mask', masked: true } as unknown as MaskingNote;
    const OPEN = { maskingMode: 'none', masked: false } as unknown as MaskingNote;

    it('a cache hit restores the masking note of the cached result, not of the last read', async () => {
        const { fixture, queried } = create();
        fixture.detectChanges();
        const inv = notes((q) => ((q as { filter?: unknown }).filter ? OPEN : MASKED));
        await runQuery(fixture);
        const c = fixture.componentInstance;
        c.filterWhere().items.push({ kind: 'condition', field: 'source', operator: '=', value: 'D' });
        await pushOnce(c);
        expect(queried).toHaveLength(2);
        expect(inv.masking()).toBe(OPEN);
        await c.loadView({ id: 'v', name: 'v', sourceId: 'entity-projection', query: queried[0] as never }); // cache hit
        expect(queried).toHaveLength(2);
        expect(inv.masking()).toBe(MASKED);
    });

    it('a read with NO masking note is cached as "no note": its hit clears the badge', async () => {
        const { fixture, queried } = create();
        fixture.detectChanges();
        const inv = notes((q) => ((q as { filter?: unknown }).filter ? null : MASKED));
        await runQuery(fixture);
        const c = fixture.componentInstance;
        c.filterWhere().items.push({ kind: 'condition', field: 'source', operator: '=', value: 'D' });
        await pushOnce(c); // no-note read
        const pushed = c.lastRun()!.query;
        expect(inv.masking()).toBeNull();
        await c.loadView({ id: 'v', name: 'v', sourceId: 'entity-projection', query: queried[0] as never }); // masked hit
        expect(inv.masking()).toBe(MASKED);
        await c.loadView({ id: 'w', name: 'w', sourceId: 'entity-projection', query: pushed }); // no-note hit
        expect(queried).toHaveLength(2);
        expect(inv.masking()).toBeNull();
    });

    it('a change of the masking mode in force makes the next identical query a fresh fetch', async () => {
        const { fixture, queried } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        await c.run();
        expect(queried).toHaveLength(1);
        const settings = TestBed.inject(LinkAnalysisSettingsService);
        settings.limits.set({ ...settings.limits(), maskingModeInForce: 'mask' } as never);
        await c.run();
        expect(queried).toHaveLength(2);
    });

    it('destroying the page cancels a pending debounced push and settles its callers', async () => {
        const { fixture, queried } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        vi.useFakeTimers();
        try {
            const p = c.pushFilter();
            fixture.destroy();
            await p; // resolves, no hang
            await vi.advanceTimersByTimeAsync(PUSH_FILTER_DEBOUNCE_MS * 2);
        } finally {
            vi.useRealTimers();
        }
        expect(queried).toHaveLength(1);
    });
});

describe('LinkAnalysisComponent - revalidation never keeps stale data or stale ETags (PERF-P10a-2 review)', () => {
    async function drawn(opts: Parameters<typeof create>[0] = {}) {
        const made = create(opts);
        made.fixture.detectChanges();
        await runQuery(made.fixture);
        return { ...made, c: made.fixture.componentInstance };
    }
    /** What a fresh re-fold yields: a new object with the same nodes and edges, plus `extra` top-level fields. */
    function refold(extra: Record<string, unknown>) {
        const src = TestBed.inject(GraphSourcesService).byId('entity-projection')!;
        const orig = src.query.bind(src);
        src.query = async (q) => ({ ...structuredClone(await orig(q)), ...extra }) as never;
    }
    const MAPPING = { dataset: 'links-ds', rows: 5 } as never;
    /** A stored ETag for the request the component would make, to watch whether it survives. */
    function seed(inv: InvService): string {
        const key = inv.reads.keyOf('POST', '/inv/projection', { probe: 1 });
        inv.reads.store(key, '"seed"', { probe: 1 });
        return key;
    }
    async function expired<T>(body: () => Promise<T>): Promise<T> {
        const now = vi.spyOn(Date, 'now').mockReturnValue(Date.now() + GRAPH_CACHE_TTL_MS + 1);
        try {
            return await body();
        } finally {
            now.mockRestore();
        }
    }

    it('Refresh never keeps the old object: a fresh fold that differs only in `mappings` shows its rows', async () => {
        const { c } = await drawn();
        const g1 = c.graph();
        expect(c.mappingSummary()).toEqual([]);
        refold({ mappings: [MAPPING] });
        await c.refreshFromServer();
        expect(c.graph()).not.toBe(g1);
        expect(c.mappingSummary()).toHaveLength(1);
    });

    it.each([
        ['mappings', { mappings: [{ dataset: 'x', rows: 1 }] }],
        ['idMappings', { idMappings: [{ idColumn: 'A' }] }],
        ['omittedLinks', { omittedLinks: 3 }],
        ['source', { source: 'index' }],
        ['truncated', { truncated: true }],
    ])('an expired re-read that differs only in %s replaces the old graph', async (_name, extra) => {
        const { c } = await drawn();
        const g1 = c.graph();
        refold(extra);
        await expired(() => c.run());
        expect(c.graph()).not.toBe(g1);
    });

    it('an expired re-read whose node spellings / objectRef differ replaces the old graph', async () => {
        const { c } = await drawn();
        const g1 = c.graph();
        const src = TestBed.inject(GraphSourcesService).byId('entity-projection')!;
        const orig = src.query.bind(src);
        src.query = async (q) => {
            const g = structuredClone(await orig(q));
            (g.nodes[0].data as Record<string, unknown>)['spellings'] = ['A-1', 'a1'];
            (g.nodes[0].data as Record<string, unknown>)['objectRef'] = { dataset: 'd', key: 'k' };
            return g;
        };
        await expired(() => c.run());
        expect(c.graph()).not.toBe(g1);
    });

    it('an expired re-fold with identical content but a DIFFERENT masking note refreshes the graph and the note', async () => {
        const { c } = await drawn();
        const inv = TestBed.inject(InvService);
        const src = TestBed.inject(GraphSourcesService).byId('entity-projection')!;
        const orig = src.query.bind(src);
        const MASKED = { maskingMode: 'mask', masked: true } as unknown as MaskingNote;
        const OPEN = { maskingMode: 'none', masked: false } as unknown as MaskingNote;
        let note = MASKED;
        src.query = async (q) => {
            const g = structuredClone(await orig(q));
            inv.masking.set(note);
            return g;
        };
        await expired(() => c.run()); // first read under MASKED, stored
        const g1 = c.graph();
        expect(inv.masking()).toEqual(MASKED);
        note = OPEN;
        const now = vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 2 * GRAPH_CACHE_TTL_MS + 2);
        try {
            await c.run(); // expired again; same nodes and edges, other note
        } finally {
            now.mockRestore();
        }
        expect(c.graph()).not.toBe(g1);
        expect(inv.masking()).toEqual(OPEN);
    });

    it('Refresh clears the stored ETags: the entry is gone, so the re-read sends no If-None-Match', async () => {
        const { c, fixture } = await drawn({ inv: {} });
        const inv = TestBed.inject(InvService);
        const key = seed(inv);
        expect(inv.reads.entry(key)).toBeDefined();
        await c.refreshFromServer();
        expect(inv.reads.entry(key)).toBeUndefined();
        fixture.destroy();
    });

    it('an Entity List write clears the stored ETags', async () => {
        const { c } = await drawn({ inv: {} });
        const inv = TestBed.inject(InvService);
        const key = seed(inv);
        (c as unknown as { refreshEntityLists(): void }).refreshEntityLists();
        expect(inv.reads.entry(key)).toBeUndefined();
    });

    it('a change of the masking mode in force clears the stored ETags on the next read', async () => {
        const { c } = await drawn({ inv: {} });
        const inv = TestBed.inject(InvService);
        const key = seed(inv);
        const settings = TestBed.inject(LinkAnalysisSettingsService);
        settings.limits.set({ ...settings.limits(), maskingModeInForce: 'mask' } as never);
        await c.run();
        expect(inv.reads.entry(key)).toBeUndefined();
    });
});
