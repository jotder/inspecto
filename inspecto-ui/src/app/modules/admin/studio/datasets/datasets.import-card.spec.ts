import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { AlertsService, EventsService, SessionService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { LA_FEATURES } from '@inspecto/link-analysis/la-host';
import { Dataset } from './dataset-types';
import { DatasetsService } from './datasets.service';
import { DatasetsComponent } from './datasets.component';

/** An Add data Dataset of sales (a date and an amount, no link) and a call-records one (a link). */
const SALES: Dataset = {
    id: 'sales',
    name: 'sales',
    kind: 'stream' as never,
    sourceName: 'sales/database',
    physicalRef: 'sales/database',
    columns: [
        { name: 'store_id', type: 'number', role: 'dimension' },
        { name: 'sold_on', type: 'date', role: 'temporal' },
        { name: 'total', type: 'number', role: 'measure' },
    ],
    measures: [],
    calculated: [],
    extra: { origin: 'import', importPipeline: 'sales' },
};
const CALLS: Dataset = {
    ...SALES,
    id: 'calls',
    name: 'calls',
    columns: [
        { name: 'caller', type: 'string', role: 'entity', classification: 'msisdn' },
        { name: 'callee', type: 'string', role: 'entity', classification: 'msisdn' },
    ],
    primaryEntityType: 'msisdn',
    defaultLinkMapping: { from: 'caller', to: 'callee' },
    extra: { origin: 'import', importPipeline: 'calls' },
};
const VIEW: Dataset = { ...SALES, id: 'cdr_view', name: 'cdr_view', kind: 'virtual', extra: {} };

function create(
    datasets: Dataset[],
    o: { laWidgets?: boolean; apply?: ReturnType<typeof vi.fn>; boardExists?: boolean } = {},
) {
    const apply = o.apply ?? vi.fn(() => of('sales_trend_board'));
    const features = { geoLink: true, laWidgets: o.laWidgets ?? false, authoring: true };
    const reasons = { laWidgets: 'Link Analysis widgets on dashboards need Standard.' };
    TestBed.configureTestingModule({
        imports: [DatasetsComponent],
        providers: [
            provideNoopAnimations(),
            provideHttpClient(),
            provideHttpClientTesting(),
            provideRouter([]),
            {
                provide: DatasetsService,
                useValue: {
                    list: () => of(datasets),
                    facts: (d: Dataset) =>
                        of(
                            d.id === 'sales'
                                ? { rows: 1234, from: '2026-09-01', to: '2026-09-28' }
                                : { rows: 7, from: null, to: null },
                        ),
                    applyStarter: apply,
                    dashboardExists: () => of(o.boardExists ?? true),
                },
            },
            { provide: ToastrService, useValue: { warning: () => undefined } },
            {
                provide: SessionService,
                useValue: {
                    exchangeEnabled: () => false,
                    authMode: () => 'none',
                    capabilities: () => [],
                    opsEnabled: () => false,
                    edition: () => 'basic',
                    features: signal(features),
                    featureReasons: signal(reasons),
                },
            },
            {
                provide: LA_FEATURES,
                useValue: {
                    ops: signal(false),
                    exchange: signal(false),
                    geoLink: signal(true),
                    investigations: signal(false),
                    signIn: signal(false),
                    edition: signal('basic'),
                    flags: signal(features),
                    featureReasons: signal(reasons),
                },
            },
            { provide: EventsService, useValue: { signals: () => of([]) } },
            { provide: AlertsService, useValue: { rules: () => of([]) } },
        ],
    });
    const fixture = TestBed.createComponent(DatasetsComponent);
    return { fixture, apply, http: TestBed.inject(HttpTestingController) };
}

const q = (root: HTMLElement, id: string): HTMLElement => root.querySelector(`[data-testid="${id}"]`) as HTMLElement;

describe('DatasetsComponent - the console Dataset page (ONBOARDING-FIRST-RUN-1 C5)', () => {
    it('an Add data Dataset shows the shared import card with its summary, Set aside and offers; any other keeps its card', async () => {
        const { fixture, http } = create([SALES, VIEW]);
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        const cards = el.querySelectorAll('[data-testid="import-dataset-card"]');
        expect(cards.length).toBe(1);
        const card = cards[0] as HTMLElement;
        // the shared card's own gesture is there (not a fork)
        expect(q(card, 'add-a-file')).toBeTruthy();
        expect(q(card, 'run-now')).toBeTruthy();
        // the arrivals the card reads feed the summary's Set aside
        http.expectOne((r) => r.url.endsWith('/datasets/sales/arrivals')).flush({
            dataset: 'sales',
            eventDateColumn: 'sold_on',
            truncated: false,
            reason: null,
            arrivals: [
                {
                    file: 'sep.csv',
                    sender: 'upload',
                    received: '2026-10-01T09:00:00',
                    status: 'SUCCESS',
                    rowsRead: 1240,
                    rowsRejected: 6,
                    mode: 'append',
                    sha256: null,
                    index: null,
                    consignment: 'c1',
                    kept: true,
                    coversFrom: null,
                    coversTo: null,
                },
            ],
        });
        fixture.detectChanges();
        expect(q(card, 'summary-rows').textContent).toContain('1,234');
        expect(q(card, 'summary-span').textContent).toContain('2026-09-01 – 2026-09-28');
        expect(q(card, 'summary-primary').textContent).toContain('Not set');
        expect(q(card, 'summary-set-aside').textContent).toContain(
            '6 rows were set aside: they did not match the column types.',
        );
        expect(q(card, 'offer-starter-dashboard').textContent).toContain('Has a date (sold_on) and an amount (total)');
        // the pane's own actions sit in the card's header
        expect(card.querySelector('button[aria-label="Delete"]')).toBeTruthy();
        // the plain Dataset still renders its plain card
        expect(el.textContent).toContain('cdr_view');
        // the arrivals answer reaching the pane does not make the card read them again (a request loop, preview 2026-11-05)
        fixture.detectChanges();
        fixture.detectChanges();
        expect(http.match((r) => r.url.endsWith('/datasets/sales/arrivals')).length).toBe(0);
        await expectNoA11yViolations(el);
    });

    it('call records offer Link Analysis; on Basic the dashboard network view is locked with its reason, not hidden', () => {
        const { fixture } = create([CALLS]);
        const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(q(el, 'offer-network').textContent).toContain('Looks like links between entities: caller → callee.');
        expect(q(el, 'offer-network-widget').textContent).toContain(
            'Link Analysis widgets on dashboards need Standard.',
        );
        expect(q(el, 'offer-network-widget-go')).toBeNull();
        expect(q(el, 'summary-primary').textContent).toContain('msisdn');
        expect(q(el, 'summary-span').textContent).toContain('No date column');
        q(el, 'offer-network-go').click();
        expect(navigate).toHaveBeenCalledWith(['/studio/link-analysis'], { queryParams: { dataset: 'calls' } });
    });

    it('on Standard the dashboard network view opens the Widget builder', () => {
        const { fixture } = create([CALLS], { laWidgets: true });
        const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
        fixture.detectChanges();
        q(fixture.nativeElement, 'offer-network-widget-go').click();
        expect(navigate).toHaveBeenCalledWith('/studio/widgets/new');
    });

    it('Make a starter dashboard applies over the Dataset own columns and opens the board', () => {
        const { fixture, apply } = create([SALES]);
        const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        fixture.detectChanges();
        q(fixture.nativeElement, 'offer-starter-dashboard-go').click();
        expect(apply).toHaveBeenCalledWith('sales', { event_date: 'sold_on', amount: 'total' });
        expect(navigate).toHaveBeenCalledWith(['/studio/dashboards', 'sales_trend_board']);
    });

    it('a starter made before (409) links to it instead of failing', () => {
        const apply = vi.fn(() => throwError(() => new HttpErrorResponse({ status: 409 })));
        const { fixture } = create([SALES], { apply });
        fixture.detectChanges();
        q(fixture.nativeElement, 'offer-starter-dashboard-go').click();
        fixture.detectChanges();
        const exists = q(fixture.nativeElement, 'starter-exists');
        expect(exists.textContent).toContain('open sales_trend_board');
        expect(exists.querySelector('a')?.getAttribute('href')).toBe('/studio/dashboards/sales_trend_board');
    });

    it('a 409 with no such board (an approval hold, a left-over widget) shows the server reason, not a dead link', () => {
        const apply = vi.fn(() =>
            throwError(
                () =>
                    new HttpErrorResponse({
                        status: 409,
                        error: { error: { message: 'changes to widgets wait for approval in this Space' } },
                    }),
            ),
        );
        const { fixture } = create([SALES], { apply, boardExists: false });
        fixture.detectChanges();
        q(fixture.nativeElement, 'offer-starter-dashboard-go').click();
        fixture.detectChanges();
        expect(q(fixture.nativeElement, 'starter-exists')).toBeNull();
        expect(q(fixture.nativeElement, 'dataset-offers').textContent).toContain('wait for approval');
    });
});
