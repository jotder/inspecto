import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { AuditRow, LensService, RunsService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { RunDetailComponent } from './run-detail.component';

const BATCH: AuditRow = { consignment_id: 'b1', status: 'SUCCESS' };
const QUARANTINED: AuditRow = { consignment_id: 'q1', file: 'bad.csv', reason: 'parse_error' };

/** `inputs` exercises the embedded side-panel mode (R5); without it the route snapshot drives the name.
 *  `confirmResult` controls what the (stubbed) confirm dialog resolves to for reprocess tests. */
function create(inputs?: { name: string; embedded: boolean }, confirmResult = true) {
    TestBed.configureTestingModule({
        imports: [RunDetailComponent],
        providers: [
            provideNoopAnimations(),
            {
                provide: ActivatedRoute,
                useValue: { snapshot: { paramMap: convertToParamMap({ name: 'cdr_ingest' }) } },
            },
            {
                provide: RunsService,
                useValue: {
                    batches: () => of([BATCH]),
                    files: () => of([]),
                    pending: () => of(null),
                    lineage: () => of([]),
                    quarantine: () => of([QUARANTINED]),
                    commits: () => of([]),
                    reprocess: () => of({}),
                    rejectedRows: () =>
                        of({
                            pipeline: 'cdr_ingest',
                            file: 'x',
                            errorsFile: 'x_errors.csv',
                            rowCount: 0,
                            truncated: false,
                            rows: [],
                        }),
                },
            },
            { provide: InspectoConfirmService, useValue: { confirm: () => Promise.resolve(confirmResult) } },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            {
                provide: ToastrService,
                useValue: { warning: () => undefined, success: () => undefined, error: () => undefined },
            },
        ],
    });
    // ⚠ `<inspecto-data-table>` injects the REAL MatDialog, so a plain useValue provider in the same
    // TestBed is silently ignored (the give-away is the dialog actually constructing). overrideProvider
    // is the documented fix — without it the `open` spy below never sees a call.
    TestBed.overrideProvider(MatDialog, {
        useValue: { open: vi.fn(() => ({ afterClosed: () => of(undefined) })) },
    });
    const fixture = TestBed.createComponent(RunDetailComponent);
    if (inputs) {
        fixture.componentRef.setInput('name', inputs.name);
        fixture.componentRef.setInput('embedded', inputs.embedded);
    }
    fixture.detectChanges(); // runs ngOnInit (batches tab loads)
    return fixture;
}

describe('RunDetailComponent', () => {
    // LensService persists to localStorage; clear it so a lens set by one test/file can't leak into another.
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('loads the batches tab on init', () => {
        const c = create().componentInstance;
        expect(c.rows).toEqual([BATCH]);
    });

    it('shows Reprocess in the default (Builder) lens on the Batches tab', () => {
        const c = create().componentInstance;
        expect(c.auditRowActions.map((a) => a.hint)).toEqual(['Lineage & details', 'Reprocess this batch']);
    });

    it('hides Reprocess (keeps Lineage & details) in the Business (read-only) lens', () => {
        const c = create().componentInstance;
        TestBed.inject(LensService).selectLens('business');
        expect(c.auditRowActions.map((a) => a.hint)).toEqual(['Lineage & details']);
    });

    it('the Business lens blocks reprocessRow even when called directly', () => {
        const c = create().componentInstance;
        TestBed.inject(LensService).selectLens('business');
        const spy = vi.spyOn(TestBed.inject(RunsService), 'reprocess');
        c.reprocessRow(BATCH);
        expect(spy).not.toHaveBeenCalled();
    });

    it('shows quarantined rows and offers Reprocess on the Quarantine tab', () => {
        const c = create().componentInstance;
        c.selectedIndex = c.tabs.findIndex((t) => t.id === 'quarantine');
        c.onTabChange();
        expect(c.rows).toEqual([QUARANTINED]);
        // A quarantined file's whole content was rejected, so its rejected-row detail is always offered.
        expect(c.auditRowActions.map((a) => a.hint)).toEqual([
            'Lineage & details',
            'Reprocess this batch',
            'View the rejected rows',
        ]);
    });

    it('offers the rejected rows only on files that actually rejected some', () => {
        const c = create().componentInstance;
        const action = c.fileRowActions.find((a) => a.hint === 'View the rejected rows')!;
        expect(action).toBeDefined();
        expect(action.visible?.({ filename: 'clean.csv', error_rows: '0' })).toBe(false);
        expect(action.visible?.({ filename: 'partly.csv', error_rows: '37' })).toBe(true);
    });

    it('opens the rejected-row dialog with the file NAME, whichever key the surface spells it with', () => {
        const c = create().componentInstance;
        const spy = TestBed.inject(MatDialog).open as unknown as ReturnType<typeof vi.fn>;

        c.openRejectedRows({ filename: 'a.csv' }); // status ledger
        c.openRejectedRows({ file: 'b.csv' }); // quarantine listing
        expect(spy.mock.calls.map((call) => (call[1] as { data: { file: string } }).data.file)).toEqual([
            'a.csv',
            'b.csv',
        ]);

        spy.mockClear();
        c.openRejectedRows({ consignment_id: 'no-file-here' }); // nothing to key on → no dialog
        expect(spy).not.toHaveBeenCalled();
    });

    it('reprocessing a quarantined row asks for confirmation before calling the API', async () => {
        const c = create().componentInstance;
        const spy = vi.spyOn(TestBed.inject(RunsService), 'reprocess');
        await c.reprocessRow(QUARANTINED);
        expect(spy).toHaveBeenCalledWith('cdr_ingest', 'q1');
    });

    it('cancelling the confirm dialog skips the reprocess call', async () => {
        const c = create(undefined, false).componentInstance;
        const spy = vi.spyOn(TestBed.inject(RunsService), 'reprocess');
        await c.reprocessRow(QUARANTINED);
        expect(spy).not.toHaveBeenCalled();
    });

    it('embedded mode hides the page chrome, shows the compact header, and emits closed on X (R5)', async () => {
        const fixture = create({ name: 'other_run', embedded: true });
        const c = fixture.componentInstance;
        const el = fixture.nativeElement as HTMLElement;
        expect(c.name).toBe('other_run'); // the input wins over the route snapshot
        expect(el.querySelector('h1')).toBeNull(); // full-page breadcrumb/back chrome hidden
        expect(el.querySelector('h2')?.textContent).toContain('other_run');
        const closed = vi.fn();
        c.closed.subscribe(closed);
        (el.querySelector('button[aria-label="Close panel"]') as HTMLButtonElement).click();
        expect(closed).toHaveBeenCalled();
        await expectNoA11yViolations(el);
    });

    it('reloads when the bound name changes while the panel stays mounted', () => {
        const fixture = create({ name: 'run_a', embedded: true });
        const spy = vi.spyOn(TestBed.inject(RunsService), 'batches');
        fixture.componentRef.setInput('name', 'run_b');
        fixture.detectChanges(); // flushes the reload effect
        expect(fixture.componentInstance.name).toBe('run_b');
        expect(spy).toHaveBeenCalledWith('run_b');
    });

    it('renders the live step gauge with its AGE on the Files tab (S7 follow-on)', () => {
        const fixture = create();
        const c = fixture.componentInstance;
        vi.spyOn(TestBed.inject(RunsService), 'pending').mockReturnValue(
            of({
                pipeline: 'cdr_ingest',
                inbox: 'inboxes/cdr_ingest',
                pending: 1,
                running: true,
                current: null,
                step: {
                    consignmentId: 'cdr_ingest-b7',
                    step: 'transform',
                    index: 2,
                    total: 4,
                    startedAt: new Date(Date.now() - 5000).toISOString(),
                },
            }),
        );
        c.selectedIndex = c.tabs.findIndex((t) => t.id === 'files');
        c.onTabChange();
        fixture.detectChanges();
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('transform');
        expect(text).toContain('Step 2 of 4');
        // the age is the design's only hang signal — assert it actually renders, not just "3/5"
        expect(text).toMatch(/in step for \d+s/);
    });

    it('formats the step age across second/minute/hour scales', () => {
        const c = create().componentInstance;
        const at = (ms: number) => new Date(Date.now() - ms).toISOString();
        expect(c.stepAge(at(12_000))).toBe('12s');
        expect(c.stepAge(at(185_000))).toBe('3m 05s');
        expect(c.stepAge(at(8_040_000))).toBe('2h 14m');
        expect(c.stepAge('not-a-date')).toBe('0s');
    });

    it('renders with no a11y violations', async () => {
        const fixture = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
