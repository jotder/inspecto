import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import {
    apiErrorMessage,
    ComponentDef,
    ComponentsService,
    JobDetail,
    JobsService,
    LensService,
} from 'app/inspecto/api';
import { KpiDefinitionDialog } from './kpi-definition.dialog';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { fmtDateTime } from 'app/inspecto/grid';
import { Dashboard, dashboardTitle } from '../studio/dashboards/dashboard-types';
import { DashboardsService } from '../studio/dashboards/dashboards.service';
import { ScheduleExportData, ScheduleExportDialog, ScheduleExportResult } from './schedule-export.dialog';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';

/** A `type:'report'` job's export params — no new entity, params carry the shape. */
interface ReportJobParams {
    /** Set only on a legacy dashboard schedule — unsupported: `ReportJob` refuses to run it. */
    dashboardId: string;
    scope: string;
    dataset: string;
    format: string;
    recipients: string[];
}

/**
 * KPI & Reports — the read-only landing gallery over the Studio dashboards, extended (C6) with
 * **scheduled Dataset exports**: an export IS a Job (`type: 'report'`, reusing the existing
 * scheduler/run-history/live-tail wholesale) so this pane only adds the "Schedule Dataset export" action and
 * a status list — authoring stays here, execution stays in Jobs.
 */
@Component({
    standalone: true,
    imports: [
        InspectoPageHeaderComponent,
        MatButtonModule,
        MatIconModule,
        MatTooltipModule,
        RouterLink,
        InspectoEmptyStateComponent,
        InspectoSkeletonComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: `./kpi-reports.component.html`,
})
export class KpiReportsComponent implements OnInit {
    private dashboardsApi = inject(DashboardsService);
    private jobsApi = inject(JobsService);
    private componentsApi = inject(ComponentsService);
    private dialog = inject(MatDialog);
    private toastr = inject(ToastrService);
    private confirm = inject(InspectoConfirmService);
    private router = inject(Router);
    protected lens = inject(LensService);

    readonly dashboards = signal<Dashboard[]>([]);
    readonly reportJobs = signal<JobDetail[]>([]);
    readonly loading = signal(true);
    /** KPI definitions (ASSURE-KPI-DEFINITIONS-1) — a KPI tile binds to one by `kpiId`. A failed read is an empty list. */
    readonly kpiDefinitions = signal<ComponentDef[]>([]);
    /** True when the dashboards fetch itself failed — distinguishes a load error from a genuinely
     *  empty gallery so the template can offer Retry instead of the "create one" empty state. */
    readonly loadError = signal(false);
    readonly statusBadgeHtml = statusBadgeHtml;
    readonly fmtDateTime = fmtDateTime;
    readonly dashboardTitle = dashboardTitle;
    /** A legacy `dashboardId` schedule: `ReportJob` refuses to run it (SCHEDULE-EXPORT-DASHBOARD-SCOPE-1 declined). */
    readonly legacyUnsupportedReason = 'Dashboard export is not supported — recreate this schedule for a Dataset';

    createDashboard(): void {
        this.router.navigate(['/studio/dashboards/new']);
    }

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.loadError.set(false);
        this.dashboardsApi.list().subscribe({
            next: (d) => {
                // R2-16: cards are titled by the Dashboard's readable title, so they are ordered by it too.
                this.dashboards.set([...d].sort((a, b) => dashboardTitle(a).localeCompare(dashboardTitle(b))));
                this.loading.set(false);
            },
            error: () => {
                this.loadError.set(true);
                this.loading.set(false);
            },
        });
        this.loadReportDetails();
        this.componentsApi.list('kpi').subscribe({
            next: (k) => this.kpiDefinitions.set([...k].sort((a, b) => a.name.localeCompare(b.name))),
            error: () => this.kpiDefinitions.set([]),
        });
    }

    /** Create (no argument) or edit a KPI definition; a save reloads the list. */
    openKpiDefinition(existing?: ComponentDef): void {
        this.dialog
            .open(KpiDefinitionDialog, { data: { existing }, width: '640px' })
            .afterClosed()
            .subscribe((saved) => {
                if (saved) this.load();
            });
    }

    kpiTitle(k: ComponentDef): string {
        return String(k.content['title'] ?? k.name);
    }

    /** The list projection omits `params` (dashboardId/format/recipients) — fetch full detail per job. */
    private loadReportDetails(): void {
        this.jobsApi.list().subscribe((jobs) => {
            const names = jobs.filter((j) => j.type === 'report').map((j) => j.name);
            if (!names.length) {
                this.reportJobs.set([]);
                return;
            }
            Promise.all(names.map((n) => this.jobsApi.get(n).toPromise())).then((details) => {
                this.reportJobs.set(details.filter((d): d is JobDetail => !!d));
            });
        });
    }

    /** The card's count line: "8 tiles · 1 quick filter" — singular for one, never "tile(s)". */
    cardSummary(d: Dashboard): string {
        const tiles = d.tiles.length;
        const filters = d.exposedFields?.length ?? 0;
        const parts = [`${tiles} tile${tiles === 1 ? '' : 's'}`];
        if (filters) parts.push(`${filters} quick filter${filters === 1 ? '' : 's'}`);
        return parts.join(' · ');
    }

    /** Scheduled Dataset exports — the ones a Run actually delivers. */
    datasetExports(): JobDetail[] {
        return this.reportJobs().filter((j) => this.paramsOf(j).scope === 'dataset');
    }

    jobsFor(dashboardId: string): JobDetail[] {
        return this.reportJobs().filter((j) => this.paramsOf(j).dashboardId === dashboardId);
    }

    paramsOf(j: JobDetail): ReportJobParams {
        const p = j.params ?? {};
        return {
            dashboardId: String(p['dashboardId'] ?? ''),
            scope: String(p['scope'] ?? ''),
            dataset: String(p['dataset'] ?? ''),
            format: String(p['format'] ?? 'csv'),
            recipients: (p['recipients'] as string[]) ?? [],
        };
    }

    scheduleExport(job?: JobDetail): void {
        const data: ScheduleExportData = { job, existingNames: this.reportJobs().map((j) => j.name) };
        this.dialog
            .open(ScheduleExportDialog, { data, width: '560px', maxHeight: '88vh' })
            .afterClosed()
            .subscribe((r?: ScheduleExportResult) => {
                if (r?.saved) {
                    this.toastr.success(`Scheduled export "${r.saved.name}" ${job ? 'saved' : 'created'}`);
                    this.loadReportDetails();
                }
            });
    }

    /** Run now: triggers the underlying job — a successful run produces a downloadable artifact. */
    runNow(job: JobDetail): void {
        this.jobsApi.trigger(job.name).subscribe({
            next: () => {
                this.toastr.success(`Export "${job.name}" ran.`);
                this.loadReportDetails();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, `Could not run "${job.name}".`)),
        });
    }

    /** Download the latest run's artifact (mirrors the events CSV-export client pattern). */
    downloadLatest(job: JobDetail): void {
        if (!job.lastRunTime) {
            this.toastr.warning(`"${job.name}" has not run yet.`);
            return;
        }
        this.jobsApi.runs(job.name).subscribe({
            next: (runs) => {
                const latest = runs[0];
                if (!latest) return;
                this.jobsApi.runArtifact(job.name, latest.runId).subscribe({
                    next: (a) => {
                        const blob = new Blob([a.content], { type: a.mime });
                        const url = URL.createObjectURL(blob);
                        const link = document.createElement('a');
                        link.href = url;
                        link.download = a.filename;
                        link.click();
                        URL.revokeObjectURL(url);
                    },
                    error: (e) => this.toastr.error(apiErrorMessage(e, 'No artifact for the latest run.')),
                });
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not load run history.')),
        });
    }

    async removeSchedule(job: JobDetail): Promise<void> {
        if (!(await this.confirm.confirmDestructive(`Delete scheduled export "${job.name}"?`))) return;
        this.jobsApi.remove(job.name).subscribe({
            next: () => {
                this.toastr.success(`Scheduled export "${job.name}" deleted`);
                this.reportJobs.set(this.reportJobs().filter((j) => j.name !== job.name));
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, `Could not delete "${job.name}".`)),
        });
    }
}
