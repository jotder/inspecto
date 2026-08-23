import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, OnInit, signal, ViewEncapsulation } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router } from '@angular/router';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { ProblemFile, ProblemFilesPage, ReportsService, RunStatus, StatusReport } from 'app/inspecto/api';
import { statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime, InspectoRowAction } from 'app/inspecto/grid';
import { AiStatusData, AiStatusDialog } from 'app/inspecto/ai-assist/ai-status.dialog';

/** `-1` means "not carried by the source row" — render blank, never a misleading 0. */
function blankIfUnknown(p: { value: number | null | undefined }): string {
    return p.value === undefined || p.value === null || p.value < 0 ? '' : p.value.toLocaleString();
}

/** A summary card above the grid. */
interface MetricCard {
    label: string;
    value: string;
}

/**
 * Processing Status — the cross-pipeline rollup Operations lacked: every pipeline's committed/
 * quarantine counts and last-batch outcome in one grid (GET /status), instead of drilling into
 * each pipeline's own Runs > Files/Lineage tabs one at a time. A row opens that pipeline's Run
 * detail for the full provenance/lineage/quarantine breakdown — this page doesn't duplicate it.
 *
 * <p>Since 2026-08-23 it carries a second, FILE-grain tab: <b>Problem files</b>
 * (`GET /status/problem-files`) — every pipeline's whole failures (quarantined) and partial failures
 * (ingested with rejected rows) in one list, because triaging 100s of pipelines one Run Detail at a
 * time does not scale (operator ask). Deliberately a tab here rather than a new pane: this page is
 * already "the cross-pipeline rollup", and a sibling pane would split one question across two.
 */
@Component({
    selector: 'app-processing-status',
    standalone: true,
    imports: [
        DecimalPipe,
        MatButtonModule,
        MatIconModule,
        MatTabsModule,
        MatTooltipModule,
        DataTableComponent,
        InspectoAlertComponent,
    ],
    templateUrl: './processing-status.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
    encapsulation: ViewEncapsulation.None,
})
export class ProcessingStatusComponent implements OnInit {
    private api = inject(ReportsService);
    private router = inject(Router);
    private dialog = inject(MatDialog);

    readonly loading = signal(false);
    readonly report = signal<StatusReport | null>(null);

    /** Which tab is showing — the problem-files page is fetched lazily, on first reveal. */
    readonly tab = signal<'pipelines' | 'problems'>('pipelines');
    readonly problemsLoading = signal(false);
    readonly problems = signal<ProblemFilesPage | null>(null);
    readonly problemCards = signal<MetricCard[]>([]);
    readonly cards = signal<MetricCard[]>([]);

    readonly columnDefs: ColDef<RunStatus>[] = [
        { field: 'pipeline', headerName: 'Pipeline', flex: 1 },
        {
            field: 'paused',
            headerName: 'State',
            width: 110,
            cellRenderer: (p: ICellRendererParams<RunStatus>) => statusBadgeHtml(p.value ? 'PAUSED' : 'RUNNING'),
        },
        { field: 'committedBatches', headerName: 'Committed batches', width: 170 },
        { field: 'quarantineFiles', headerName: 'Quarantine files', width: 160 },
        {
            field: 'lastBatchStatus',
            headerName: 'Last batch',
            width: 130,
            cellRenderer: (p: ICellRendererParams<RunStatus>) => (p.value ? statusBadgeHtml(p.value) : '—'),
        },
        {
            field: 'lastBatchId',
            headerName: 'Last batch id',
            flex: 1,
            valueFormatter: (p) => p.value ?? '—',
        },
        {
            field: 'lastBatchTime',
            headerName: 'Last batch time',
            width: 180,
            valueFormatter: (p) => fmtDateTime(p.value),
        },
    ];

    readonly rowActions: InspectoRowAction<RunStatus>[] = [
        {
            // "Why is this red" (AGT-6a A4-status) — this row IS the pipeline's state, so it is the
            // natural place to ask. No correlationId here, so the dialog takes the windowed path,
            // focused on this pipeline. Ungated: reading state is not an authoring act.
            icon: 'heroicons_outline:information-circle',
            hint: 'What happened',
            onClick: (r) =>
                this.dialog.open(AiStatusDialog, {
                    data: {
                        label: r.pipeline,
                        pipelineId: r.pipeline,
                    } satisfies AiStatusData,
                }),
        },
        {
            icon: 'heroicons_outline:rectangle-group',
            hint: 'Open provenance & lineage for this pipeline',
            onClick: (r) => this.router.navigate(['/runs', r.pipeline]),
        },
    ];

    /**
     * The file-grain grid. Verdict is a status badge so FULL/PARTIAL read at a glance; the row counts
     * render BLANK for `-1` ("not carried by the source row") rather than a misleading 0 — the same
     * not-measured convention the batches ledger's `cast_failures` uses.
     */
    readonly problemColumnDefs: ColDef<ProblemFile>[] = [
        { field: 'pipeline', headerName: 'Pipeline', width: 170 },
        {
            field: 'verdict',
            headerName: 'Verdict',
            width: 130,
            cellRenderer: (p: ICellRendererParams<ProblemFile>) =>
                statusBadgeHtml(p.value === 'FULL' ? 'FAILED' : p.value === 'PARTIAL' ? 'WARNING' : 'UNKNOWN'),
        },
        { field: 'filename', headerName: 'File', flex: 1 },
        { field: 'status', headerName: 'Status', width: 200 },
        { field: 'parsedRows', headerName: 'Parsed rows', width: 130, valueFormatter: blankIfUnknown },
        { field: 'errorRows', headerName: 'Rejected rows', width: 140, valueFormatter: blankIfUnknown },
        { field: 'error', headerName: 'Reason', flex: 1 },
        { field: 'consignmentId', headerName: 'Consignment', width: 170 },
        { field: 'time', headerName: 'When', width: 180, valueFormatter: (p) => fmtDateTime(p.value) },
    ];

    readonly problemRowActions: InspectoRowAction<ProblemFile>[] = [
        {
            // The rejected ROWS behind the count — the existing per-pipeline errors route, reached
            // without leaving the cross-pipeline list. Only meaningful where rows were rejected.
            icon: 'heroicons_outline:table-cells',
            hint: 'View the rejected rows',
            visible: (r) => r.errorRows > 0,
            onClick: (r) =>
                this.router.navigate(['/runs', r.pipeline], { queryParams: { tab: 'files', file: r.filename } }),
        },
        {
            icon: 'heroicons_outline:rectangle-group',
            hint: 'Open this pipeline’s run detail',
            onClick: (r) => this.router.navigate(['/runs', r.pipeline]),
        },
    ];

    ngOnInit(): void {
        this.load();
    }

    /** Fetch the problem-files page on first reveal, then on explicit refresh. */
    onTabChange(index: number): void {
        this.tab.set(index === 1 ? 'problems' : 'pipelines');
        if (index === 1 && this.problems() === null) this.loadProblems();
    }

    loadProblems(): void {
        this.problemsLoading.set(true);
        this.api.problemFiles().subscribe({
            next: (p) => {
                this.problems.set(p);
                this.problemCards.set([
                    { label: 'Pipelines affected', value: String(p.pipelinesWithProblems) },
                    { label: 'Failed outright', value: String(p.fullCount) },
                    { label: 'Partially ingested', value: String(p.partialCount) },
                    // Surfaced as a card, not swallowed: a pipeline whose ledger cannot be read is
                    // NOT a healthy pipeline, and a zero here is the honest common case.
                    { label: 'Unreadable ledgers', value: String(p.warningCount) },
                ]);
                this.problemsLoading.set(false);
            },
            error: () => {
                this.problems.set(null);
                this.problemCards.set([]);
                this.problemsLoading.set(false);
            },
        });
    }

    load(): void {
        this.loading.set(true);
        this.api.status().subscribe({
            next: (r) => {
                this.report.set(r);
                this.cards.set([
                    { label: 'Pipelines', value: String(r.pipelineCount) },
                    { label: 'Paused', value: String(r.pausedCount) },
                    {
                        label: 'Committed batches',
                        value: r.totalCommittedBatches.toLocaleString(),
                    },
                    { label: 'Quarantine files', value: String(r.totalQuarantineFiles) },
                ]);
                this.loading.set(false);
            },
            error: () => {
                this.report.set(null);
                this.cards.set([]);
                this.loading.set(false);
            },
        });
    }
}
