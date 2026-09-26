import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { Observable, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { LensService, ReconApiService } from 'app/inspecto/api';
import { DatasetsService } from '../studio/datasets/datasets.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { Reconciliation, ReconciliationsService } from 'app/inspecto/reconciliation';
import { ReconciliationsComponent } from './reconciliations.component';

const RECON: Reconciliation = {
    id: 'switch_vs_billing',
    name: 'switch vs billing',
    leftDataset: 'switch_cdr',
    rightDataset: 'billing_cdr',
    keyColumns: ['id'],
    compareColumns: [],
};

const NO_RUNS = () => of({ states: [], total: 0, truncated: false });

function create(
    list: Reconciliation[] = [RECON],
    dialogOpen = vi.fn(() => ({ afterClosed: () => of(undefined) })),
    states: () => Observable<unknown> = NO_RUNS,
    opts: { canAuthor?: boolean; datasets?: () => Observable<unknown> } = {},
) {
    TestBed.configureTestingModule({
        imports: [ReconciliationsComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: ReconciliationsService, useValue: { list: () => of(list), create: () => of(RECON) } },
            { provide: ReconApiService, useValue: { states } },
            { provide: DatasetsService, useValue: { list: opts.datasets ?? (() => of([])) } },
            { provide: LensService, useValue: { canAuthorWorkbench: () => opts.canAuthor !== false } },
            { provide: ToastrService, useValue: { error: () => undefined } },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
        ],
    });
    // DataTableComponent (template) also injects MatDialog — override wins over a plain providers[] entry.
    TestBed.overrideProvider(MatDialog, { useValue: { open: dialogOpen } });
    const fixture = TestBed.createComponent(ReconciliationsComponent);
    fixture.detectChanges();
    return { fixture, dialogOpen };
}

describe('ReconciliationsComponent', () => {
    it('loads reconciliations on init', () => {
        expect(create().fixture.componentInstance.reconciliations()).toEqual([RECON]);
    });

    // R2-03: "Last run" reads the server-RECORDED state, not a field of the config — an operations user's
    // runs used to 403 on the config PUT, so the list said "never" for runs that had happened.
    it("shows each row's last recorded run from the server state", () => {
        const other: Reconciliation = { ...RECON, id: 'never_run' };
        const states = () =>
            of({
                states: [
                    { reconciliation: RECON.id, lastRunAt: '2026-09-26T08:00:00Z', runs: 3 },
                    { reconciliation: 'never_run', lastRunAt: null, runs: 0 },
                ],
                total: 2,
                truncated: false,
            });
        const c = create([RECON, other], undefined, states).fixture.componentInstance;
        const last = c.columns.find((col) => col.field === 'lastRunAt')!;
        const fmt = (v: unknown) => (last.valueFormatter as (p: { value: unknown }) => string)({ value: v });
        expect(c.rows().map((r) => r.lastRunAt)).toEqual(['2026-09-26T08:00:00Z', null]);
        expect(fmt(null)).toBe('never');
        // R3-01: the app's one date spelling (`26 Sep 2026, …`), never the host's `9/26/2026, …`.
        expect(fmt('2026-09-26T08:00:00Z')).toMatch(/^2[67] Sep 2026, \d\d:\d\d:\d\d$/);
    });

    it('never claims "never" when the recorded state could not be read', () => {
        const c = create([RECON], undefined, () => throwError(() => ({ status: 500 }))).fixture.componentInstance;
        const last = c.columns.find((col) => col.field === 'lastRunAt')!;
        expect(c.rows()[0].lastRunAt).toBeUndefined();
        expect((last.valueFormatter as (p: { value: unknown }) => string)({ value: undefined })).toBe('—');
    });

    it('shows the empty state when there are none', () => {
        expect(create([]).fixture.nativeElement.textContent).toContain('No reconciliations yet');
    });

    // R2-16: the list led with the id (`ra_c02_offer_fee`); it now leads with the business title — the same one
    // the Breaks page shows — with the id as secondary text, sorted by title and searchable by either.
    it('titles each row by its description, with the id as secondary text', async () => {
        const offerFee: Reconciliation = {
            ...RECON,
            id: 'ra_c02_offer_fee',
            name: 'ra_c02_offer_fee',
            description: 'Offer fee billed: CRM vs CBS (daily, 0.01 SAR tolerance)',
        };
        const { fixture } = create([offerFee, RECON]);
        await fixture.whenStable();
        fixture.detectChanges();
        const cells = Array.from(
            fixture.nativeElement.querySelectorAll('.ag-center-cols-container .ag-cell[col-id="name"]'),
        ) as HTMLElement[];
        const texts = cells.map((c) => c.textContent?.trim());
        expect(texts).toContain('Offer fee billed: CRM vs CBS (daily, 0.01 SAR tolerance)ra_c02_offer_fee');
        // A recon without a description falls back to its name, with its (different) id alongside.
        expect(texts).toContain('switch vs billingswitch_vs_billing');

        const col = fixture.componentInstance.columns[0];
        const params = (data: Reconciliation) => ({ data }) as never;
        expect((col.valueGetter as (p: never) => string)(params(offerFee))).toBe(
            'Offer fee billed: CRM vs CBS (daily, 0.01 SAR tolerance)',
        );
        expect(col.getQuickFilterText!(params(offerFee))).toBe(
            'Offer fee billed: CRM vs CBS (daily, 0.01 SAR tolerance) ra_c02_offer_fee',
        );
    });

    // R3-02: the sides were Dataset ids (`crm_subscribers`); they read as the Board names them, id in the tooltip.
    it('names each side by its Dataset label, keeping the id in the tooltip and the search', async () => {
        const datasets = () =>
            of([
                { id: 'switch_cdr', name: 'switch_cdr', description: 'Switch CDRs (mediation)' },
                { id: 'billing_cdr', name: 'Billing CDRs' },
            ]);
        const { fixture } = create([RECON], undefined, NO_RUNS, { datasets });
        await fixture.whenStable();
        fixture.detectChanges();
        const cell = (col: string) =>
            (
                fixture.nativeElement.querySelector(
                    `.ag-center-cols-container .ag-cell[col-id="${col}"]`,
                ) as HTMLElement | null
            )?.textContent?.trim();
        expect(cell('leftLabel')).toBe('Switch CDRs (mediation)');
        expect(cell('rightLabel')).toBe('Billing CDRs');
        const left = fixture.componentInstance.columns.find((c) => c.field === 'leftLabel')!;
        const row = { data: fixture.componentInstance.rows()[0] } as never;
        expect((left.tooltipValueGetter as (p: never) => string)(row)).toBe('switch_cdr');
        expect(left.getQuickFilterText!(row)).toBe('Switch CDRs (mediation) switch_cdr');
    });

    it('names a side by its id when the Dataset list cannot be read', () => {
        const c = create([RECON], undefined, NO_RUNS, { datasets: () => throwError(() => ({ status: 500 })) }).fixture
            .componentInstance;
        expect(c.rows()[0].leftLabel).toBe('switch_cdr');
    });

    // R3-05: creating writes a `reconciliation` Component, which the server refuses without canAuthorWorkbench.
    it('offers New and Duplicate only to a user who may author', () => {
        const hidden = create([RECON], undefined, NO_RUNS, { canAuthor: false }).fixture;
        expect(hidden.nativeElement.textContent).not.toContain('New reconciliation');
        expect(hidden.componentInstance.rowActions().map((a) => a.hint)).toEqual(['Open']);
    });

    it('offers New and Duplicate to an author', () => {
        const shown = create().fixture;
        expect(shown.nativeElement.textContent).toContain('New reconciliation');
        expect(shown.componentInstance.rowActions().map((a) => a.hint)).toEqual(['Open', 'Duplicate']);
    });

    it('renders with no a11y violations', async () => {
        await expectNoA11yViolations(create().fixture.nativeElement);
    });
});
