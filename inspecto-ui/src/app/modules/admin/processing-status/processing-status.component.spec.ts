import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { AiStatusDialog } from 'app/inspecto/ai-assist/ai-status.dialog';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ProblemFilesPage, ReportsService, RunStatus, StatusReport } from 'app/inspecto/api';
import { ProcessingStatusComponent } from './processing-status.component';

const PIPELINES: RunStatus[] = [
    {
        pipeline: 'cdr_ingest',
        paused: false,
        committedBatches: 120,
        quarantineFiles: 2,
        lastBatchId: 'batch-1000',
        lastBatchStatus: 'SUCCESS',
        lastBatchTime: '2026-06-30T00:00:00.000Z',
    },
    {
        pipeline: 'billing_daily',
        paused: true,
        committedBatches: 231,
        quarantineFiles: 0,
        lastBatchId: 'batch-1003',
        lastBatchStatus: 'FAILED',
        lastBatchTime: '2026-06-29T00:00:00.000Z',
    },
];

const REPORT: StatusReport = {
    generatedAt: '2026-06-30T00:00:00.000Z',
    pipelineCount: PIPELINES.length,
    pausedCount: 1,
    totalCommittedBatches: 351,
    totalQuarantineFiles: 2,
    pipelines: PIPELINES,
};

/** A page with one FULL, one PARTIAL, and the not-carried (-1) counts the route emits. */
const PROBLEMS: ProblemFilesPage = {
    rows: [
        {
            pipeline: 'billing_daily',
            filename: 'bad.csv',
            verdict: 'FULL',
            status: 'QUARANTINED_UNREADABLE',
            parsedRows: -1,
            errorRows: -1,
            error: 'could not read',
            consignmentId: '',
            time: '2026-06-30 03:00:00',
            origin: 'nightly.tar.gz', // came out of an archive — what the operator dropped
            logicalName: 'nightly', // …and the identity a re-delivery would group to
        },
        {
            pipeline: 'cdr_ingest',
            filename: 'partial.csv',
            verdict: 'PARTIAL',
            status: 'SUCCESS',
            parsedRows: 90,
            errorRows: 10,
            error: '',
            consignmentId: 'c-1',
            time: '2026-06-30 02:00:00',
            origin: '', // arrived as itself
            logicalName: 'partial', // …but still has an identity: its own name, extension stripped
        },
    ],
    total: 5,
    truncated: true,
    fullCount: 1,
    partialCount: 4,
    warningCount: 0,
    pipelinesWithProblems: 2,
};

function create(
    report: StatusReport | null = REPORT,
    dialogOpen?: (...args: unknown[]) => unknown,
    problems: ProblemFilesPage | null = PROBLEMS,
) {
    TestBed.configureTestingModule({
        imports: [ProcessingStatusComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            {
                provide: ReportsService,
                useValue: {
                    status: () => (report ? of(report) : of()),
                    problemFiles: () => (problems ? of(problems) : of()),
                },
            },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    // The data-table injects the real MatDialog, so it must be overridden, not just provided.
    if (dialogOpen) TestBed.overrideProvider(MatDialog, { useValue: { open: dialogOpen } });
    return TestBed.createComponent(ProcessingStatusComponent);
}

describe('ProcessingStatusComponent', () => {
    it('loads the status report into summary cards and rows', () => {
        const fixture = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        expect(c.report()?.pipelines).toHaveLength(2);
        expect(c.cards().map((x) => x.value)).toEqual(['2', '1', '351', '2']);
    });

    it('asks "what happened" for the row\'s own pipeline, over the windowed path', () => {
        const open = vi.fn();
        const fixture = create(REPORT, open);
        fixture.detectChanges();
        fixture.componentInstance.rowActions[0].onClick(PIPELINES[1]);
        expect(open).toHaveBeenCalledWith(AiStatusDialog, {
            data: { label: 'billing_daily', pipelineId: 'billing_daily' },
        });
    });

    it('renders with no a11y violations', async () => {
        const fixture = create();
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    describe('the Problem files tab (cross-pipeline, file grain)', () => {
        /** Lazy by design: opening this page for the pipeline rollup must not pay for the walk. */
        it('does not fetch until the tab is revealed, and fetches once', () => {
            const fixture = create();
            fixture.detectChanges();
            const c = fixture.componentInstance;
            expect(c.problems()).toBeNull();

            c.onTabChange(1);
            expect(c.problems()?.rows).toHaveLength(2);

            // Re-revealing does not refetch — only the explicit refresh does.
            c.problems.set(null);
            c.onTabChange(0);
            expect(c.problems()).toBeNull();
        });

        it('summarises PRE-limit counts, not the page', () => {
            const fixture = create();
            fixture.detectChanges();
            fixture.componentInstance.onTabChange(1);
            // 2 rows shown, but the cards describe all 5 problems.
            expect(fixture.componentInstance.problemCards().map((x) => x.value)).toEqual(['2', '1', '4', '0']);
            expect(fixture.componentInstance.problems()?.total).toBe(5);
        });

        /**
         * 🔴 `-1` means "not carried by the source row" and must render BLANK — a 0 would claim a
         * quarantined file parsed zero rows AND rejected zero rows, i.e. that nothing went wrong.
         */
        it('renders not-carried row counts blank, never 0', () => {
            const fixture = create();
            fixture.detectChanges();
            const cols = fixture.componentInstance.problemColumnDefs;
            const parsed = cols.find((c) => c.field === 'parsedRows')!;
            const fmt = parsed.valueFormatter as (p: { value: number }) => string;
            expect(fmt({ value: -1 })).toBe('');
            expect(fmt({ value: 0 })).toBe('0');
            expect(fmt({ value: 1234 })).toBe('1,234');
        });

        /** Origin names the archive a member came out of, and reads as an em dash when it is absent. */
        it('renders the origin archive, and an em dash when the file arrived as itself', () => {
            const fixture = create();
            fixture.detectChanges();
            const col = fixture.componentInstance.problemColumnDefs.find((c) => c.field === 'origin')!;
            const fmt = col.valueFormatter as (p: { value: string }) => string;
            expect(fmt({ value: 'nightly.tar.gz' })).toBe('nightly.tar.gz');
            expect(fmt({ value: '' })).toBe('—');
        });

        /** The rejected-rows drill-down is only offered where rows were actually rejected. */
        it('offers the rejected-rows action only for files that rejected rows', () => {
            const fixture = create();
            fixture.detectChanges();
            const action = fixture.componentInstance.problemRowActions[0];
            expect(action.visible!(PROBLEMS.rows[1])).toBe(true); // errorRows 10
            expect(action.visible!(PROBLEMS.rows[0])).toBe(false); // errorRows -1
        });

        it('survives a failed fetch without stale rows or cards', () => {
            const fixture = create(REPORT, undefined, null);
            fixture.detectChanges();
            fixture.componentInstance.onTabChange(1);
            expect(fixture.componentInstance.problems()).toBeNull();
            expect(fixture.componentInstance.problemCards()).toEqual([]);
        });
    });
});
