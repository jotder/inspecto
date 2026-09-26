import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { Observable, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { ReconApiService } from 'app/inspecto/api';
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
) {
    TestBed.configureTestingModule({
        imports: [ReconciliationsComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: ReconciliationsService, useValue: { list: () => of(list), create: () => of(RECON) } },
            { provide: ReconApiService, useValue: { states } },
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
        expect(fmt('2026-09-26T08:00:00Z')).not.toBe('never');
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

    it('renders with no a11y violations', async () => {
        await expectNoA11yViolations(create().fixture.nativeElement);
    });
});
