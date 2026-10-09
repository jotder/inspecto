import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { RouterLink } from '@angular/router';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import {
    apiErrorMessage,
    LensService,
    RegulatoryReport,
    RegulatoryReportDetail,
    RegulatoryReportsService,
    RegulatoryReportStatus,
    ReportTemplate,
    ReportTemplateProblem,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';
import { StatusBadgeComponent, statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime } from 'app/inspecto/grid';
import { RegulatoryReportDraftDialog, RegulatoryReportDraftData } from './regulatory-report-draft.dialog';

/** What the pane lists: the reports waiting for a decision, the submission log, or everything. */
export type RegulatoryReportsFilter = 'waiting' | 'submitted' | 'all';

type Verb = 'request-approval' | 'approve' | 'decline' | 'retry';

/**
 * The **Regulatory Reports** pane (`REGULATORY-REPORTING-1`) — the Action Requests inbox pattern for filings:
 * draft a report from a Case or Incident with a Report Template, read the exact content that will be submitted,
 * send it for approval, and (a different person) approve it — which submits it to the template's file drop — or
 * decline it; retry a failed submission. The *Submitted* view is the submission log.
 *
 * Buttons show only with the matching capability ({@link LensService.canWorkIncidents} to draft and send for
 * approval, {@link LensService.canApproveChanges} to decide); the server is the boundary and adds four-eyes (a
 * maker deciding their own report is refused, and the message is shown) and the record's integrity check.
 */
@Component({
    selector: 'app-regulatory-reports',
    standalone: true,
    imports: [
        DatePipe,
        ReactiveFormsModule,
        RouterLink,
        MatButtonModule,
        MatButtonToggleModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        DataTableComponent,
        InspectoAlertComponent,
        InspectoPageHeaderComponent,
        StatusBadgeComponent,
    ],
    templateUrl: './regulatory-reports.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RegulatoryReportsComponent implements OnInit {
    private readonly api = inject(RegulatoryReportsService);
    private readonly toastr = inject(ToastrService);
    private readonly confirm = inject(InspectoConfirmService);
    private readonly dialog = inject(MatDialog);
    protected readonly lens = inject(LensService);

    readonly filter = signal<RegulatoryReportsFilter>('waiting');
    readonly reports = signal<RegulatoryReport[]>([]);
    readonly loading = signal(false);
    readonly error = signal<string | null>(null);
    readonly templates = signal<ReportTemplate[]>([]);
    readonly templateProblems = signal<ReportTemplateProblem[]>([]);
    readonly selected = signal<RegulatoryReportDetail | null>(null);
    readonly acting = signal(false);
    readonly actError = signal<string | null>(null);
    readonly reason = new FormControl('', { nonNullable: true, validators: [Validators.maxLength(500)] });

    readonly canDraft = computed(() => this.lens.canWorkIncidents() && this.templates().length > 0);
    readonly canRequestApproval = computed(() => this.lens.canWorkIncidents() && this.selected()?.status === 'draft');
    readonly canDecide = computed(() => this.lens.canApproveChanges() && this.selected()?.status === 'pending');
    readonly canRetry = computed(() => this.lens.canApproveChanges() && this.selected()?.status === 'failed');

    // DataTable's default flex overrides a bare width, so widths here are floors (minWidth); fixed badges also cap (maxWidth).
    readonly columnDefs: ColDef<RegulatoryReport>[] = [
        {
            field: 'createdAt',
            headerName: 'Drafted',
            minWidth: 180,
            sort: 'desc',
            valueFormatter: (p) => fmtDateTime(p.value),
        },
        { field: 'templateTitle', headerName: 'Report Template', flex: 1 },
        {
            headerName: 'From',
            minWidth: 220,
            valueGetter: (p) => p.data?.subjectTitle ?? p.data?.caseId ?? p.data?.incidentId ?? '',
        },
        { field: 'author', headerName: 'Author', minWidth: 140 },
        {
            headerName: 'Submitted',
            minWidth: 180,
            valueGetter: (p) => p.data?.submission?.at ?? null,
            valueFormatter: (p) => (p.value ? fmtDateTime(p.value) : ''),
        },
        {
            field: 'status',
            headerName: 'Status',
            minWidth: 130,
            maxWidth: 130,
            cellRenderer: (p: ICellRendererParams<RegulatoryReport>) => statusBadgeHtml(p.value as string),
        },
    ];

    ngOnInit(): void {
        this.load();
        this.loadTemplates();
    }

    setFilter(f: RegulatoryReportsFilter): void {
        this.filter.set(f);
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.error.set(null);
        const f = this.filter();
        const status: RegulatoryReportStatus | undefined = f === 'submitted' ? 'submitted' : undefined;
        this.api.list({ status }).subscribe({
            next: (r) => {
                // "Waiting" = everything a person still has to act on: drafts to send, pending to decide, failures to retry.
                this.reports.set(
                    f === 'waiting'
                        ? r.items.filter((x) => x.status === 'draft' || x.status === 'pending' || x.status === 'failed')
                        : r.items,
                );
                this.loading.set(false);
            },
            error: (err) => {
                this.reports.set([]);
                this.loading.set(false);
                this.error.set(apiErrorMessage(err, 'The regulatory reports could not be read'));
            },
        });
    }

    private loadTemplates(): void {
        this.api.templates().subscribe({
            next: (t) => {
                this.templates.set(t.items);
                this.templateProblems.set(t.problems);
            },
            error: () => this.templates.set([]),
        });
    }

    newReport(): void {
        const data: RegulatoryReportDraftData = { templates: this.templates() };
        this.dialog
            .open(RegulatoryReportDraftDialog, { data, width: '40rem' })
            .afterClosed()
            .subscribe((d?: RegulatoryReportDetail) => {
                if (!d) return;
                this.toastr.success('Regulatory report drafted');
                this.filter.set('waiting');
                this.load();
                this.show(d);
            });
    }

    open(row: Record<string, unknown>): void {
        this.api.get(String(row['id'])).subscribe({
            next: (d) => this.show(d),
            error: (err) => this.toastr.error(apiErrorMessage(err, 'The regulatory report could not be read')),
        });
    }

    private show(d: RegulatoryReportDetail): void {
        this.actError.set(null);
        this.reason.reset('');
        this.selected.set(d);
    }

    close(): void {
        this.selected.set(null);
    }

    /** The linked object's detail route. */
    link(r: RegulatoryReport): string[] {
        return r.caseId ? ['/cases', r.caseId] : ['/incidents', r.incidentId ?? ''];
    }

    async requestApproval(): Promise<void> {
        const r = this.selected();
        if (
            r &&
            (await this.confirm.confirm(
                `Send "${r.templateTitle}" for approval? Its content is fixed now; a different person must approve it before it is submitted.`,
                'Send for approval?',
            ))
        )
            this.act('request-approval');
    }

    async approve(): Promise<void> {
        const r = this.selected();
        if (
            r &&
            (await this.confirm.confirm(
                `Submit the content shown, drafted by ${r.author}, as ${r.delivery.fileName} to the template's drop folder? Approving submits it now.`,
                'Approve and submit?',
            ))
        )
            this.act('approve');
    }

    async decline(): Promise<void> {
        const r = this.selected();
        if (!r) return;
        const ok = await this.confirm.confirmDestructive(`Decline "${r.templateTitle}"? Nothing is submitted.`, {
            title: 'Decline the regulatory report?',
            confirmText: 'Decline',
        });
        if (ok) this.act('decline');
    }

    retry(): void {
        this.act('retry');
    }

    private act(verb: Verb): void {
        const r = this.selected();
        if (!r) return;
        if (this.reason.invalid) {
            this.reason.markAsTouched();
            return;
        }
        const reason = this.reason.value.trim() || undefined;
        this.acting.set(true);
        this.actError.set(null);
        const call =
            verb === 'request-approval'
                ? this.api.requestApproval(r.id, reason)
                : verb === 'approve'
                  ? this.api.approve(r.id, reason)
                  : verb === 'decline'
                    ? this.api.decline(r.id, reason)
                    : this.api.retry(r.id);
        call.subscribe({
            next: (d) => {
                this.acting.set(false);
                this.toastr.success(`Regulatory report ${d.status}`);
                this.show(d);
                this.load();
            },
            error: (err) => {
                this.acting.set(false);
                // The server's own words: four-eyes, an integrity failure, a status that moved on.
                this.actError.set(apiErrorMessage(err, 'The regulatory report could not be updated'));
                this.load();
            },
        });
    }
}
