import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { LensService, PendingChange, PendingChangesService } from 'app/inspecto/api';
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
    pending?: ReturnType<typeof vi.fn>;
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
                },
            },
            {
                provide: ToastrService,
                useValue: opts.toastr ?? { success: () => undefined, error: () => undefined, info: () => undefined },
            },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            {
                provide: PendingChangesService,
                useValue: { list: opts.pending ?? (() => of({ items: [], total: 0, truncated: false })) },
            },
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
        /** The detail dialog closes with Create KPI, then the KPI dialog with the server's answer. */
        const dialogs = (answer: unknown) =>
            vi
                .fn()
                .mockReturnValueOnce({ afterClosed: () => of({ action: 'createKpi' }) })
                .mockReturnValueOnce({ afterClosed: () => of(answer) })
                .mockReturnValue({ afterClosed: () => of(undefined) });
        const toasts = () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn() });
        const HELD = { status: 'pending', written: false, pendingChange: { id: 'pc-1' } };
        const detailData = (open: ReturnType<typeof vi.fn>, call: number) =>
            (open.mock.calls[call] as unknown as [unknown, { data: { kpiPending?: boolean } }])[1].data;

        it('opens the KPI dialog from the detail and names the created KPI', () => {
            const toastr = toasts();
            const dialogOpen = dialogs({ name: 'refunds', requirement: DELIVERED.id });
            const fixture = create({ list: [DELIVERED], toastr, dialogOpen });
            fixture.componentInstance.openDetail(DELIVERED);
            expect(dialogOpen).toHaveBeenCalledTimes(2);
            expect(toastr.success).toHaveBeenCalledWith(expect.stringContaining('KPI "refunds" created'));
        });

        it('after a 202 hold, says so and the detail no longer offers Create KPI (even if the pending read fails)', () => {
            const toastr = toasts();
            const dialogOpen = dialogs(HELD);
            const pending = vi.fn(() => throwError(() => new Error('down')));
            const fixture = create({ list: [DELIVERED], toastr, dialogOpen, pending });
            fixture.componentInstance.openDetail(DELIVERED);
            expect(toastr.info).toHaveBeenCalledWith(expect.stringContaining('waiting for approval'));
            expect(toastr.success).not.toHaveBeenCalled();
            fixture.componentInstance.openDetail(DELIVERED);
            expect(detailData(dialogOpen, 0).kpiPending).toBe(false);
            expect(detailData(dialogOpen, 2).kpiPending).toBe(true);
        });

        it('on reload, a held kpi create in Pending Changes marks its requirement pending', () => {
            const held = { kind: 'kpi', name: DELIVERED.id, operation: 'create' } as PendingChange;
            const other = { kind: 'dataset', name: DELIVERED.id, operation: 'create' } as PendingChange;
            const pending = vi.fn(() => of({ items: [held, other], total: 2, truncated: false }));
            const dialogOpen = vi.fn(() => ({ afterClosed: () => of(undefined) }));
            const fixture = create({ list: [DELIVERED], pending, dialogOpen });
            expect(pending).toHaveBeenCalledWith('pending');
            fixture.componentInstance.openDetail(DELIVERED);
            expect(detailData(dialogOpen, 0).kpiPending).toBe(true);
        });

        it('does nothing when the KPI dialog is cancelled, or without canAuthorWorkbench', () => {
            const toastr = toasts();
            const dialogOpen = dialogs(undefined);
            const fixture = create({ list: [DELIVERED], toastr, dialogOpen });
            fixture.componentInstance.openDetail(DELIVERED);
            expect(toastr.success).not.toHaveBeenCalled();
            expect(toastr.info).not.toHaveBeenCalled();

            TestBed.inject(LensService).selectLens('business');
            fixture.componentInstance.createKpi(DELIVERED);
            expect(dialogOpen).toHaveBeenCalledTimes(2); // the business lens never opens the KPI dialog
        });
    });

    it('renders with no a11y violations', async () => {
        const fixture = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
