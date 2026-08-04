import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { CatalogService, ExchangeService, MetadataGraph, MetadataNode, PipelinesService, SessionService, SpacesService } from 'app/inspecto/api';
import { ToastrService } from 'ngx-toastr';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ComponentsDataProvider } from './components-data-provider';
import { CatalogComponent } from './catalog.component';

const TABLE: MetadataNode = { id: 'tbl/cdr', kind: 'TABLE', label: 'cdr' };
const STREAM: MetadataNode = {
    id: 'stream:orders', kind: 'STREAM', label: 'orders',
    attrs: { connector: 'local', pipeline: 'orders', active: false },
};
const GRAPH: MetadataGraph = { nodes: [TABLE], edges: [] };
const EMPTY_GRAPH: MetadataGraph = { nodes: [], edges: [] };

/** Only the onboarding create dialog is opened from here; it closes with no result by default. */
const DIALOG = { open: vi.fn((_cmp: unknown, _config?: unknown) => ({ afterClosed: () => of(undefined) })) };

function create(
    overrides: Partial<CatalogService> = {},
    queryParams: Record<string, string> = {},
    // No capability ⇒ Builder is not an allowed lens ⇒ LensService snaps to read-only Business, which
    // hides every authoring affordance (onboarding included). Pass this to test an authoring lens.
    capabilities: string[] = [],
) {
    const api = {
        tables: () => of([TABLE]),
        streams: () => of([]),
        references: () => of([]),
        kpis: () => of({ kpis: [] }),
        graph: () => of(GRAPH),
        ...overrides,
    } as unknown as CatalogService;
    TestBed.configureTestingModule({
        imports: [CatalogComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } } },
            { provide: CatalogService, useValue: api },
            { provide: MatDialog, useValue: DIALOG },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
            // RegistryComponent is embedded (not lazy) for the "usage" tab.
            { provide: ComponentsDataProvider, useValue: { list: () => Promise.resolve([]) } },
            { provide: PipelinesService, useValue: { list: () => of([]), pipelineGraphRaw: () => of(undefined) } },
            // The Exchange tabs gate on bootstrap.features.exchange (SharingComponent is embedded).
            // authMode 'none' keeps LensService on the honor system (R2 grant checks bypassed).
            { provide: SessionService, useValue: { exchangeEnabled: () => true, authMode: () => 'none', capabilities: () => capabilities } },
            { provide: ExchangeService, useValue: { grants: () => of([]), offers: () => of([]) } },
            { provide: SpacesService, useValue: { currentSpaceId: () => 'default' } },
            { provide: ToastrService, useValue: {} },
        ],
    });
    // MatDialogModule (in the component's own imports) provides MatDialog at the element level, which
    // SHADOWS a root provider — the mock has to be overridden on the component to be reached at all.
    TestBed.overrideComponent(CatalogComponent, { add: { providers: [{ provide: MatDialog, useValue: DIALOG }] } });
    const fixture = TestBed.createComponent(CatalogComponent);
    fixture.detectChanges(); // runs ngOnInit (loads the Tables tab)
    return fixture;
}

describe('CatalogComponent', () => {
    beforeEach(() => {
        localStorage.removeItem('inspecto.currentLens');
        DIALOG.open.mockClear();
    });

    it('opens the create dialog from ?onboard=stream, AFTER the rows are in', () => {
        // The nav item Catalog ▸ Onboard Stream is this link. The dialog must see the loaded names so a
        // duplicate is rejected inline rather than only by the server's 409.
        const c = create({ streams: () => of([STREAM]) }, { onboard: 'stream' }, ['canAuthorWorkbench'])
            .componentInstance;
        expect(c.activeTab).toBe('streams');
        expect(DIALOG.open).toHaveBeenCalledTimes(1);
        expect(DIALOG.open.mock.calls[0][1]).toMatchObject({ data: { kind: 'stream', existingNames: ['orders'] } });
    });

    it('does not open the dialog without ?onboard=', () => {
        // Authoring lens, so a miss here means the deep link is absent — not that the lens hid it.
        create({ streams: () => of([STREAM]) }, {}, ['canAuthorWorkbench']);
        expect(DIALOG.open).not.toHaveBeenCalled();
    });

    it('loads the Streams tab on init (data origins are the default tab)', () => {
        const c = create({ streams: () => of([STREAM]) }).componentInstance;
        expect(c.activeTab).toBe('streams');
        expect(c.streams).toEqual([STREAM]);
    });

    it('offers the two Exchange tabs when bootstrap.features.exchange is on', () => {
        const c = create().componentInstance;
        expect(c.tabs.map((t) => t.id)).toContain('shared-with-me');
        expect(c.tabs.map((t) => t.id)).toContain('shared-by-me');
    });

    it('switching to a tab loads its data', () => {
        const c = create().componentInstance;
        c.tabIndex = 3; // kpis
        c.loadTab();
        expect(c.activeTab).toBe('kpis');
        expect(c.kpis).toEqual([]);
    });

    it('degrades gracefully when the streams call fails', () => {
        const c = create({ streams: () => throwError(() => ({ status: 404 })) }).componentInstance;
        expect(c.streams).toEqual([]);
        expect(c.loading).toBe(false);
    });

    it('runs a graph traversal and derives the G6 legend', () => {
        const c = create().componentInstance;
        c.runGraph();
        expect(c.graph?.nodes).toEqual([TABLE]);
        expect(c.legend).toEqual([{ kind: 'TABLE', fill: expect.any(String), label: 'TABLE' }]);
    });

    it('deep-links to the Lineage tab and runs the traversal from ?tab=graph&from=', () => {
        // EMPTY_GRAPH so the G6 canvas is NOT mounted in jsdom: GraphViewComponent.rebuild()
        // short-circuits on data.nodes.length===0; a non-empty graph would `new Graph().render()`,
        // which throws an unhandled clearRect error under jsdom and fails the vitest run (non-zero exit).
        const graph = vi.fn(() => of(EMPTY_GRAPH));
        const c = create({ graph }, { tab: 'graph', from: 'stream:orders' }).componentInstance;
        expect(c.activeTab).toBe('graph');
        expect(c.graphFrom).toBe('stream:orders');
        expect(graph).toHaveBeenCalledWith(expect.objectContaining({ from: 'stream:orders' })); // traversal ran with the deep-link root
    });

    it('opens the requested tab without a from and does not traverse', () => {
        const c = create({}, { tab: 'graph' }).componentInstance;
        expect(c.activeTab).toBe('graph');
        expect(c.graph).toBeNull(); // no ?from ⇒ empty Lineage tab, user traverses manually
    });

    it('renders the empty-graph state with no a11y violations', async () => {
        const fixture = create({ graph: () => of(EMPTY_GRAPH) });
        const c = fixture.componentInstance;
        c.tabIndex = 4; // graph
        c.loadTab();
        c.runGraph();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
