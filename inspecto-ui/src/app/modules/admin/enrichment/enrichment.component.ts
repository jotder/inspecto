import { ChangeDetectionStrategy, Component, inject, OnInit, signal, ViewEncapsulation } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { AuditRow, EnrichmentJobView, EnrichmentRunReport, EnrichmentService } from 'app/inspecto/api';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { FmtPercentPipe } from 'app/inspecto/format';
import { fmtDateTime } from 'app/inspecto/grid';
import { ToastrService } from 'ngx-toastr';

type EnrTab = 'runs' | 'lineage' | 'report';

/**
 * Enrichment — Stage-2 jobs with a detail panel (runs / lineage filtered by runId / rollup report)
 * for the selected job (ported from inspector-ui onto the gamma shell). Generic audit rows render
 * as dynamic-column grids; the report tab takes a date range and shows percentile stats.
 */
@Component({
    selector: 'app-enrichment',
    standalone: true,
    imports: [
        FormsModule,
        MatButtonModule,
        MatDatepickerModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        MatTabsModule,
        MatTooltipModule,
        DataTableComponent,
        InspectoEmptyStateComponent,
        FmtPercentPipe,
    ],
    templateUrl: './enrichment.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
    encapsulation: ViewEncapsulation.None,
})
export class EnrichmentComponent implements OnInit {
    private toastr = inject(ToastrService);
    private api = inject(EnrichmentService);

    readonly jobs = signal<EnrichmentJobView[]>([]);
    readonly loading = signal(false);
    readonly unavailable = signal(false);

    readonly selected = signal<EnrichmentJobView | null>(null);

    readonly tabs: { id: EnrTab; label: string }[] = [
        { id: 'runs', label: 'Runs' },
        { id: 'lineage', label: 'Lineage' },
        { id: 'report', label: 'Report' },
    ];
    selectedIndex = 0;
    get activeTab(): EnrTab {
        return this.tabs[this.selectedIndex].id;
    }

    readonly rows = signal<AuditRow[]>([]);
    readonly detailLoading = signal(false);
    lineageRunId = '';

    from: Date | null = null;
    to: Date | null = null;
    readonly report = signal<EnrichmentRunReport | null>(null);

    readonly jobColumns: ColDef<EnrichmentJobView>[] = [
        { field: 'name', headerName: 'Job', flex: 1 },
        { field: 'onPipeline', headerName: 'On pipeline', flex: 1 },
        { field: 'eventTriggered', headerName: 'Event', width: 90 },
        { field: 'scheduleTriggered', headerName: 'Scheduled', width: 110 },
        { field: 'runCount', headerName: 'Runs', width: 90 },
        {
            field: 'lastRunStatus',
            headerName: 'Last status',
            width: 120,
            cellRenderer: (p: ICellRendererParams<EnrichmentJobView>) =>
                p.value ? statusBadgeHtml(p.value as string) : '—',
        },
        {
            field: 'lastRunTime',
            headerName: 'Last run',
            width: 170,
            valueFormatter: (p) => fmtDateTime(p.value),
        },
    ];

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.unavailable.set(false);
        this.api.list().subscribe({
            next: (j) => {
                this.jobs.set(j);
                this.loading.set(false);
            },
            error: (e) => {
                this.loading.set(false);
                this.jobs.set([]);
                // See collectors: only a 404 means "no such surface here". Anything else is a failure and
                // must not render as the affirmative "no enrichment jobs are registered".
                this.unavailable.set(e?.status === 404);
                if (e?.status !== 404) this.toastr.warning('Could not load enrichment jobs — is ControlApi running?');
            },
        });
    }

    onRowClick(row: EnrichmentJobView): void {
        if (!row) return;
        this.selected.set(row);
        this.selectedIndex = 0;
        this.report.set(null);
        this.lineageRunId = '';
        this.loadTab();
    }

    onTabChange(): void {
        this.loadTab();
    }

    loadTab(): void {
        if (!this.selected()) return;
        const job = this.selected().name;
        if (this.activeTab === 'report') {
            this.loadReport();
            return;
        }
        this.detailLoading.set(true);
        const call =
            this.activeTab === 'runs'
                ? this.api.runs(job)
                : this.api.lineage(job, this.lineageRunId.trim() || undefined);
        call.subscribe({
            next: (r) => {
                this.rows.set(r);
                this.detailLoading.set(false);
            },
            error: () => {
                this.rows.set([]);
                this.detailLoading.set(false);
            },
        });
    }

    loadReport(): void {
        if (!this.selected()) return;
        this.detailLoading.set(true);
        const window = {
            from: this.from ? this.from.toISOString() : undefined,
            to: this.to ? this.to.toISOString() : undefined,
        };
        this.api.report(this.selected().name, window).subscribe({
            next: (r) => {
                this.report.set(r);
                this.detailLoading.set(false);
            },
            error: () => {
                this.report.set(null);
                this.detailLoading.set(false);
            },
        });
    }
}
