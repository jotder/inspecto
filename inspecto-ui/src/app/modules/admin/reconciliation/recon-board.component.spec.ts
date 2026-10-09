import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, ParamMap, Router, convertToParamMap } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { BehaviorSubject, EMPTY, Observable, Subject, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { LensService, ReconApiService } from 'app/inspecto/api';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import {
    aggregateRecon,
    Reconciliation,
    ReconciliationsService,
    ReconRunQuery,
    ReconRunResult,
    ReconState,
} from 'app/inspecto/reconciliation';
import { queryFromParams, ReconBoardComponent } from './recon-board.component';
import { ReconExecService } from './recon-exec.service';
import { DatasetsService } from '../studio/datasets/datasets.service';
import { Dataset } from '../studio/datasets/dataset-types';

const RECON: Reconciliation = {
    id: 'med_vs_bill',
    name: 'Mediation vs Billing',
    leftDataset: 'mediation_daily',
    rightDataset: 'billing_daily',
    keyColumns: ['region', 'product'],
    compareColumns: [{ column: 'amount', toleranceType: 'percent', tolerance: 0.5 }],
};

/** What `POST /recon/{id}/record` answers: the server-merged lifecycle, one Break 100 days old. */
const DAY = 86_400_000;
const RECORDED: ReconState = {
    reconciliation: 'med_vs_bill',
    lastRunAt: '2026-09-26T08:00:00.000Z',
    runs: 4,
    breaks: [
        {
            key: 'MEA · voice',
            type: 'missing_right',
            status: 'open',
            firstSeenAt: new Date(Date.now() - 100 * DAY).toISOString(),
        },
        {
            key: 'EU · data',
            type: 'value_break',
            column: 'amount',
            status: 'resolved',
            firstSeenAt: '2026-09-01T00:00:00Z',
        },
    ],
};

const LEFT = [
    { region: 'EU', product: 'voice', amount: 100 },
    { region: 'EU', product: 'data', amount: 118 },
    { region: 'MEA', product: 'voice', amount: 10 },
];
const RIGHT = [
    { region: 'EU', product: 'voice', amount: 100 },
    { region: 'EU', product: 'data', amount: 114 },
];
const RESULT: ReconRunResult = {
    ...aggregateRecon(RECON, LEFT, RIGHT),
    day: '2026-09-26',
    availableDays: ['2026-09-26', '2026-09-25'],
    page: { offset: 0, limit: 50, total: 3 },
};

async function create(
    opts: {
        patch?: Partial<Reconciliation>;
        result?: ReconRunResult;
        datasets?: Partial<Dataset>[];
        record?: () => Observable<ReconState>;
        state?: () => Observable<ReconState>;
        canOperateRuns?: boolean;
        canAuthor?: boolean;
        params?: Record<string, string>;
        page?: (q: ReconRunQuery) => Observable<ReconRunResult>;
        settle?: boolean;
    } = {},
) {
    const recon: Reconciliation = { ...RECON, ...opts.patch };
    const navigate = vi.fn(async (..._args: unknown[]) => true);
    const save = vi.fn((r: Reconciliation) => of(r));
    const record = vi.fn(opts.record ?? (() => of({ ...RECORDED, day: '2026-09-26' })));
    const state = vi.fn(opts.state ?? (() => of({ ...RECORDED, runs: 3 })));
    const toastr = { success: vi.fn(), error: vi.fn() };
    const params = new BehaviorSubject<ParamMap>(convertToParamMap(opts.params ?? {}));
    const page = vi.fn((_r: Reconciliation, q: ReconRunQuery) =>
        opts.page ? opts.page(q) : of(opts.result ?? RESULT),
    );
    TestBed.configureTestingModule({
        imports: [ReconBoardComponent],
        providers: [
            provideNoopAnimations(),
            {
                provide: ActivatedRoute,
                useValue: { snapshot: { paramMap: convertToParamMap({ id: RECON.id }) }, queryParamMap: params },
            },
            {
                provide: Router,
                useValue: { navigate, createUrlTree: () => ({}), serializeUrl: () => '', events: EMPTY },
            },
            { provide: ReconciliationsService, useValue: { get: () => of(recon), save } },
            { provide: ReconApiService, useValue: { record, state } },
            {
                provide: LensService,
                useValue: {
                    canOperateRuns: () => opts.canOperateRuns ?? true,
                    canAuthorWorkbench: () => opts.canAuthor ?? true,
                },
            },
            { provide: ReconExecService, useValue: { page } },
            { provide: DatasetsService, useValue: { list: () => of(opts.datasets ?? []) } },
            { provide: MatDialog, useValue: { open: vi.fn() } },
            { provide: ToastrService, useValue: toastr },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
        ],
    });
    const fixture = TestBed.createComponent(ReconBoardComponent);
    fixture.detectChanges(); // ngOnInit — load + the first page of the URL's day
    const c = fixture.componentInstance;
    if (opts.settle !== false) await vi.waitFor(() => expect(c.result()).not.toBeNull());
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    return { fixture, c, el, navigate, save, record, state, toastr, params, page };
}

describe('ReconBoardComponent', () => {
    it('loads the latest day and renders the summary, the TOTAL strip and the band-tinted rows', async () => {
        const { c, el, page } = await create();
        expect(page).toHaveBeenCalledWith(expect.objectContaining({ id: RECON.id }), {
            day: null,
            filter: 'all',
            limit: 50,
            offset: 0,
            sample: null,
        });
        const text = el.textContent ?? '';
        expect(text).toContain('Mediation vs Billing');
        expect(text).toContain('2026-09-26');
        expect(text).toContain('2 matched');
        expect(text).toContain('only in A: 1');
        expect(text).toContain('value breaks: 1');
        expect(text).toContain('Total');
        expect(c.result()).not.toBeNull();
        const rows = Array.from(el.querySelectorAll('inspecto-recon-grain-table tbody tr'));
        expect(rows.map((r) => r.getAttribute('data-band'))).toEqual(['breach', 'ok', 'structural']);
        expect(rows[2].querySelector('.sr-only')?.textContent).toContain('Missing on a side');
        const heads = Array.from(el.querySelectorAll('inspecto-recon-grain-table thead th')).map((h) =>
            h.textContent?.trim(),
        );
        expect(heads.slice(0, 2)).toEqual(['Region', 'Product']);
        expect(heads).not.toContain('Status');
        expect(el.querySelector('mat-paginator')).not.toBeNull();
    });

    /**
     * R2-03: a run is RECORDED server-side (`canOperateRuns`), never saved through the authoring PUT — an
     * operations-only user got a 403 there and no run was ever recorded.
     */
    it('opening the Board READS the lifecycle and never records (operator, 2026-10-09)', async () => {
        const { c, record, state, save, el } = await create();
        await vi.waitFor(() => expect(c.state()).not.toBeNull());
        expect(record).not.toHaveBeenCalled();
        expect(state).toHaveBeenCalledWith('med_vs_bill');
        expect(save).not.toHaveBeenCalled();
        expect(c.ageBuckets()).toEqual([{ bucket: '90+', count: 1 }]);
        expect(el.querySelector('[data-testid="record-run"]')?.textContent).toContain('Record run');
    });

    it('"Record run" records the SELECTED day server-side and shows the recorded lifecycle', async () => {
        const { c, record, toastr } = await create({ params: { day: '2026-09-25' } });
        c.record();
        expect(record).toHaveBeenCalledWith('med_vs_bill', '2026-09-25');
        expect(c.state()?.runs).toBe(4);
        expect(toastr.success).toHaveBeenCalled();
    });

    it('counts the open Breaks of BOTH pairs of a 3-way Reconciliation in the aging strip', async () => {
        const old = new Date(Date.now() - 100 * DAY).toISOString();
        const recent = new Date(Date.now() - 10 * DAY).toISOString();
        const threeWay: ReconState = {
            ...RECORDED,
            breaks: [
                { pair: 'AB', key: 'MEA · voice', type: 'missing_right', status: 'open', firstSeenAt: old },
                { pair: 'AC', key: 'MEA · voice', type: 'missing_right', status: 'open', firstSeenAt: recent },
                { pair: 'AC', key: 'EU · data', type: 'value_break', column: 'amount', status: 'resolved' },
            ],
        };
        const { c } = await create({ patch: { thirdDataset: 'crm_daily' }, state: () => of(threeWay) });
        await vi.waitFor(() => expect(c.state()).toEqual(threeWay));
        expect(c.ageBuckets()).toEqual([
            { bucket: '0-30', count: 1 },
            { bucket: '90+', count: 1 },
        ]);
    });

    /** ASSURE-BREAK-LIFECYCLE-1: the server's age wins, assigned Breaks still age, and the strip counts them. */
    it('ages assigned Breaks by the server age and shows the assigned and recurring counts', async () => {
        const lifecycle: ReconState = {
            ...RECORDED,
            breaks: [
                // the server says 45 days; the stamp alone would say ~0 — the server's number must win
                {
                    key: 'MEA · voice',
                    type: 'missing_right',
                    status: 'assigned',
                    assignee: 'dana',
                    firstSeenAt: new Date().toISOString(),
                    ageDays: 45,
                    occurrences: 5,
                    recurrences: 2,
                },
                { key: 'APAC · sms', type: 'missing_left', status: 'open', ageDays: 3, occurrences: 1, recurrences: 0 },
                // settled work: recurred once but resolved — not counted as recurring
                { key: 'EU · data', type: 'value_break', column: 'amount', status: 'resolved', recurrences: 1 },
            ],
        };
        const { fixture, c } = await create({ state: () => of(lifecycle) });
        await vi.waitFor(() => expect(c.state()).toEqual(lifecycle));
        fixture.detectChanges();
        expect(c.ageBuckets()).toEqual([
            { bucket: '0-30', count: 1 },
            { bucket: '30-60', count: 1 },
        ]);
        expect(c.lifecycle()).toEqual({ assigned: 1, recurring: 1 });
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('[data-testid="board-assigned"]')?.textContent).toContain('Assigned: 1');
        expect(el.querySelector('[data-testid="board-recurring"]')?.textContent).toContain('Recurring: 1');
        await expectNoA11yViolations(el);
    });

    it('toasts a run it could not record and keeps the last recorded lifecycle', async () => {
        const { c, toastr } = await create({
            record: () => throwError(() => ({ status: 403, error: { error: { message: 'requires canOperateRuns' } } })),
        });
        await vi.waitFor(() => expect(c.state()).not.toBeNull());
        c.record();
        expect(toastr.error).toHaveBeenCalled();
        expect(c.state()?.runs).toBe(3);
        expect(c.result()).not.toBeNull(); // the Board itself still renders
    });

    it('a viewer who may not operate runs gets no Record button', async () => {
        const { c, el, record } = await create({ canOperateRuns: false });
        expect(el.querySelector('[data-testid="record-run"]')).toBeNull();
        c.record();
        expect(record).not.toHaveBeenCalled();
    });

    it('shows the spinner — never the empty state — while the day is compared', async () => {
        const { el, c } = await create({ page: () => new Subject<ReconRunResult>(), settle: false });
        expect(c.running()).toBe(true);
        expect(el.querySelector('[data-testid="board-loading"]')?.textContent).toContain(
            'Comparing mediation_daily and billing_daily for the latest day…',
        );
        expect(el.querySelector('inspecto-empty-state')).toBeNull();
        await expectNoA11yViolations(el);
    });

    it('the URL drives the request, and a newer view cancels the one in flight', async () => {
        const inflight: Subject<ReconRunResult>[] = [];
        const { params, page } = await create({
            settle: false,
            page: () => {
                const s = new Subject<ReconRunResult>();
                inflight.push(s);
                return s;
            },
            params: { day: '2026-09-26', filter: 'breaks', offset: '50', limit: '25', sample: '4000' },
        });
        expect(page.mock.calls[0][1]).toEqual({
            day: '2026-09-26',
            filter: 'breaks',
            offset: 50,
            limit: 25,
            sample: 4000,
        });
        params.next(convertToParamMap({ day: '2026-09-25' }));
        expect(inflight[0].observed).toBe(false);
        expect(page.mock.calls[1][1]).toEqual({ day: '2026-09-25', filter: 'all', offset: 0, limit: 50, sample: null });
    });

    it('moving the view rewrites the URL only — date, page and filter are never saved', async () => {
        const { c, navigate, save } = await create();
        c.setView({ day: '2026-09-25', offset: 0 });
        expect(navigate).toHaveBeenCalledWith(
            [],
            expect.objectContaining({
                queryParams: { day: '2026-09-25', filter: null, offset: null, limit: null, sample: null },
                replaceUrl: true,
            }),
        );
        c.setView({ filter: 'value_break', offset: 100, limit: 100 });
        expect(navigate.mock.calls[1][1]).toEqual(
            expect.objectContaining({
                queryParams: { day: null, filter: 'value_break', offset: 100, limit: 100, sample: null },
            }),
        );
        expect(save).not.toHaveBeenCalled();
    });

    it('reads a hostile or stale URL as the defaults', () => {
        expect(
            queryFromParams(
                convertToParamMap({ day: "2026-09-26' OR 1", filter: 'nope', limit: '5000', offset: '-3' }),
            ),
        ).toEqual({ day: null, filter: 'all', limit: 50, offset: 0, sample: null });
    });

    it('the band editor is hidden from, and refuses, a user who may not author', async () => {
        const viewer = await create({ canAuthor: false });
        expect(viewer.el.querySelector('button[aria-label="Edit tolerance bands"]')).toBeNull();
        viewer.c.saveBands({ warnPct: 2, breachPct: 5 });
        expect(viewer.save).not.toHaveBeenCalled();
    });

    it('an author edits the bands, saved through the component save', async () => {
        const author = await create();
        expect(author.el.querySelector('button[aria-label="Edit tolerance bands"]')).not.toBeNull();
        author.c.saveBands({ warnPct: 2, breachPct: 5 });
        expect(author.save).toHaveBeenCalledWith(expect.objectContaining({ bands: { warnPct: 2, breachPct: 5 } }));
        expect(author.c.bands()).toEqual({ warnPct: 2, breachPct: 5 });
    });

    // R3-05: the pencil opened an editor whose save the server refuses (the component PUT needs canAuthorWorkbench).
    it('shows the edit pencil only to a user who may author', async () => {
        const editButton = (el: HTMLElement) => el.querySelector('button[aria-label="Edit"]');
        const viewer = await create({ canAuthor: false });
        expect(editButton(viewer.fixture.nativeElement)).toBeNull();
        viewer.c.edit();
        expect(TestBed.inject(MatDialog).open).not.toHaveBeenCalled();
    });

    it('shows the edit pencil to an author', async () => {
        const { fixture } = await create();
        expect((fixture.nativeElement as HTMLElement).querySelector('button[aria-label="Edit"]')).not.toBeNull();
    });

    it("a row's action opens the Breaks page for that key on the same day", async () => {
        const { c, navigate } = await create();
        c.viewKey({ region: 'EU', product: 'data' });
        expect(navigate).toHaveBeenCalledWith(['/reconciliation', RECON.id, 'breaks'], {
            queryParams: { path: 'region:EU|product:data', day: '2026-09-26' },
        });
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = await create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

describe('ReconBoardComponent — title rule and duplicate keys', () => {
    /** RA-C01's shape: 3-way, `one_to_one`, the duplicates on C (CBS) only. */
    const withCardinality = (): ReconRunResult => ({
        ...RESULT,
        summary: {
            ...RESULT.summary,
            byType: { ...RESULT.summary.byType, cardinality_break: 0 },
            pairs: [
                { side: 'b', matchedKeys: 2, byType: { ...RESULT.summary.byType, cardinality_break: 0 } },
                { side: 'c', matchedKeys: 2, byType: { ...RESULT.summary.byType, cardinality_break: 4 } },
            ],
        },
    });

    it('titles the Board with the description and keeps the code in the subtitle', async () => {
        const { fixture, c } = await create({ patch: { description: 'Mediation vs Billing — daily revenue' } });
        const el = fixture.nativeElement as HTMLElement;
        expect(c.title()).toBe('Mediation vs Billing — daily revenue');
        expect(el.querySelector('h1')?.textContent).toContain('Mediation vs Billing — daily revenue');
        expect(el.querySelector('h1')?.textContent).not.toContain('Mediation vs Billing ·');
        expect(el.textContent).toContain('Mediation vs Billing · A: mediation_daily');
    });

    it('falls back to the name, without repeating it in the subtitle', async () => {
        const { fixture, c } = await create();
        expect(c.title()).toBe('Mediation vs Billing');
        expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Mediation vs Billing · A');
    });

    it('counts duplicate keys per compared side when the server reports them', async () => {
        const { fixture, c } = await create({ result: withCardinality() });
        expect(c.duplicateCounts()).toEqual([
            { label: 'duplicate keys A·B', count: 0 },
            { label: 'duplicate keys A·C', count: 4 },
        ]);
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('duplicate keys A·C: 4');
    });

    it('shows no duplicate-key count for a Reconciliation without a cardinality', async () => {
        const { fixture, c } = await create();
        expect(c.duplicateCounts()).toEqual([]);
        expect((fixture.nativeElement as HTMLElement).querySelector('[data-testid="board-duplicates"]')).toBeNull();
    });

    it('renders the duplicate-key counts with no a11y violations', async () => {
        const { fixture } = await create({ result: withCardinality(), patch: { description: 'Subscriber status' } });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

describe('ReconBoardComponent — readable side and column names (R2-16)', () => {
    const DATASETS: Partial<Dataset>[] = [
        { id: 'mediation_daily', name: 'mediation_daily', description: 'Mediation daily extract' },
        { id: 'billing_daily', name: 'Billing daily' },
    ];
    const heads = (el: HTMLElement) =>
        Array.from(el.querySelectorAll('inspecto-recon-grain-table thead th'))
            .map((h) => h.textContent?.trim())
            .slice(2, -1);
    const BANDS = ' · tree Region › Product · bands ok < 1% · warn 1–2% · breach > 2%';

    it('names the sides by their Dataset and humanises the columns', async () => {
        const { fixture, c } = await create({ datasets: DATASETS });
        expect(c.subtitle()).toBe('A: Mediation daily extract ⇄ B: Billing daily' + BANDS);
        expect((fixture.nativeElement as HTMLElement).textContent).toContain(
            'A: Mediation daily extract ⇄ B: Billing daily',
        );
        expect(heads(fixture.nativeElement)).toEqual([
            'Mediation daily extract · Amount',
            'Billing daily · Amount',
            'Δ% Amount',
            'Mediation daily extract · Records',
            'Billing daily · Records',
            'Δ% Records',
        ]);
        // the builder's ids stay one hover away
        expect(
            (fixture.nativeElement as HTMLElement)
                .querySelectorAll('inspecto-recon-grain-table thead th')[2]
                .getAttribute('title'),
        ).toBe('A · amount');
        expect(c.keyColumnsLabel()).toBe('Region › Product');
    });

    it('falls back to the Dataset id when the Dataset is not found', async () => {
        const { c, el } = await create();
        expect(c.subtitle()).toBe('A: mediation_daily ⇄ B: billing_daily' + BANDS);
        expect(heads(el)).toEqual([
            'mediation_daily · Amount',
            'billing_daily · Amount',
            'Δ% Amount',
            'mediation_daily · Records',
            'billing_daily · Records',
            'Δ% Records',
        ]);
    });

    it('humanises the TOTAL strip measures', async () => {
        const { c } = await create();
        expect(c.totalLines().map((t) => t.label)).toEqual(['Amount', 'Records']);
    });

    it('titles through reconciliationTitle — a blank description falls back to the name', async () => {
        const { c } = await create({ patch: { description: '   ' } });
        expect(c.title()).toBe('Mediation vs Billing');
    });

    it('renders the named sides with no a11y violations', async () => {
        const { fixture } = await create({ datasets: DATASETS });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
