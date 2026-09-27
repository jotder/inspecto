import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { LensService } from 'app/inspecto/api';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import {
    buildRequirement,
    decideRequirement,
    deliverRequirement,
    Requirement,
    RequirementsService,
} from 'app/inspecto/requirement';
import { RequirementsComponent } from './requirements.component';

const REQ: Requirement = buildRequirement('Daily churn KPI', 'kpi', 'Track churn by region.');

interface CreateOptions {
    list?: Requirement[];
    dialogOpen?: ReturnType<typeof vi.fn>;
    create?: ReturnType<typeof vi.fn>;
    decide?: ReturnType<typeof vi.fn>;
    deliver?: ReturnType<typeof vi.fn>;
    createKpi?: ReturnType<typeof vi.fn>;
    toastr?: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn>; info: ReturnType<typeof vi.fn> };
}

function create(opts: CreateOptions = {}) {
    const dialogOpen = opts.dialogOpen ?? vi.fn(() => ({ afterClosed: () => of(undefined) }));
    TestBed.configureTestingModule({
        imports: [RequirementsComponent],
        providers: [
            provideNoopAnimations(),
            {
                provide: RequirementsService,
                useValue: {
                    list: () => of(opts.list ?? [REQ]),
                    create: opts.create ?? (() => of(REQ)),
                    decide: opts.decide ?? (() => of(REQ)),
                    deliver: opts.deliver ?? (() => of(REQ)),
                    createKpi: opts.createKpi ?? (() => of({ name: REQ.id })),
                },
            },
            {
                provide: ToastrService,
                useValue: opts.toastr ?? { success: () => undefined, error: () => undefined, info: () => undefined },
            },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
        ],
    });
    // DataTableComponent (used in the template) also provides/injects MatDialog; a plain providers[]
    // entry can lose to that transitive registration, so override it explicitly instead.
    TestBed.overrideProvider(MatDialog, { useValue: { open: dialogOpen } });
    const fixture = TestBed.createComponent(RequirementsComponent);
    fixture.detectChanges(); // runs ngOnInit (list load)
    return fixture;
}

describe('RequirementsComponent', () => {
    // LensService persists to localStorage; clear it so a lens set by one test/file can't leak into another.
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('loads requirements on init', () => {
        const c = create().componentInstance;
        expect(c.requirements()).toEqual([REQ]);
    });

    it('shows the empty state when there are none', () => {
        const fixture = create({ list: [] });
        expect(fixture.nativeElement.textContent).toContain('No requirements yet');
    });

    it('opens the submit dialog and creates on a result', () => {
        const create$ = vi.fn(() => of(REQ));
        const dialogOpen = vi.fn(() => ({ afterClosed: () => of({ title: 'x', kind: 'kpi', description: 'y' }) }));
        const fixture = create({ list: [], create: create$, dialogOpen });
        fixture.componentInstance.submit();
        expect(create$).toHaveBeenCalledWith(expect.objectContaining({ title: 'x', kind: 'kpi' }));
    });

    it('opens the decision dialog and no-ops in the Business (read-only) lens even if the dialog returns a result', () => {
        const decide = vi.fn(() => of(REQ));
        const dialogOpen = vi.fn(() => ({ afterClosed: () => of({ action: 'decide', accept: true }) }));
        const fixture = create({ decide, dialogOpen });
        TestBed.inject(LensService).selectLens('business');
        fixture.componentInstance.openDetail(REQ);
        expect(decide).not.toHaveBeenCalled();
    });

    describe('Create KPI from a delivered kpi requirement (ASSURE-KPI-DEFINITIONS-1)', () => {
        const DELIVERED = deliverRequirement(decideRequirement(REQ, true));
        const BODY = { dataset: 'orders', measure: 'sum(amount)', timeField: 'order_date', grain: 'month' };
        /** The detail dialog closes with Create KPI, then the KPI dialog with the Builder's answers. */
        const dialogs = (body: unknown = BODY) =>
            vi
                .fn()
                .mockReturnValueOnce({ afterClosed: () => of({ action: 'createKpi' }) })
                .mockReturnValueOnce({ afterClosed: () => of(body) });
        const toasts = () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn() });

        it('asks the Measure and period, calls createKpi and names the created KPI', () => {
            const createKpi = vi.fn(() => of({ name: 'refunds', requirement: DELIVERED.id }));
            const toastr = toasts();
            const dialogOpen = dialogs();
            const fixture = create({ list: [DELIVERED], createKpi, toastr, dialogOpen });
            fixture.componentInstance.openDetail(DELIVERED);
            expect(dialogOpen).toHaveBeenCalledTimes(2);
            expect(createKpi).toHaveBeenCalledWith(DELIVERED.id, BODY);
            expect(toastr.success).toHaveBeenCalledWith(expect.stringContaining('KPI "refunds" created'));
        });

        it('says so when the KPI is held for approval', () => {
            const createKpi = vi.fn(() => of({ status: 'pending', written: false, pendingChange: { id: 'pc-1' } }));
            const toastr = toasts();
            const fixture = create({ list: [DELIVERED], createKpi, toastr, dialogOpen: dialogs() });
            fixture.componentInstance.openDetail(DELIVERED);
            expect(toastr.info).toHaveBeenCalledWith(expect.stringContaining('waiting for approval'));
            expect(toastr.success).not.toHaveBeenCalled();
        });

        it("toasts the server's refusal", () => {
            const refusal = new HttpErrorResponse({
                status: 422,
                error: { error: { code: 'CONFIG_VALIDATION_FAILED', message: "kpi dataset 'nope' does not exist" } },
            });
            const createKpi = vi.fn(() => throwError(() => refusal));
            const toastr = toasts();
            const fixture = create({ list: [DELIVERED], createKpi, toastr, dialogOpen: dialogs() });
            fixture.componentInstance.openDetail(DELIVERED);
            expect(toastr.error).toHaveBeenCalledWith("kpi dataset 'nope' does not exist");
        });

        it('does nothing when the KPI dialog is cancelled, or without canAuthorWorkbench', () => {
            const createKpi = vi.fn(() => of({ name: 'refunds' }));
            const dialogOpen = dialogs(null); // null: undefined would take the default body
            const fixture = create({ list: [DELIVERED], createKpi, dialogOpen });
            fixture.componentInstance.openDetail(DELIVERED);
            expect(createKpi).not.toHaveBeenCalled();

            TestBed.inject(LensService).selectLens('business');
            fixture.componentInstance.createKpi(DELIVERED);
            expect(dialogOpen).toHaveBeenCalledTimes(2); // the business lens never opens the KPI dialog
            expect(createKpi).not.toHaveBeenCalled();
        });
    });

    it('renders with no a11y violations', async () => {
        const fixture = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
