import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, LensService, PendingChangesService } from 'app/inspecto/api';
import { statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime, InspectoRowAction } from 'app/inspecto/grid';
import { buildRequirement, Requirement, RequirementKpiResult, RequirementsService } from 'app/inspecto/requirement';
import { RequirementFormDialog, RequirementFormResult } from './requirement-form.dialog';
import { RequirementDecisionDialog, RequirementDecisionResult, RequirementDetail } from './requirement-decision.dialog';
import { RequirementKpiDialog } from './requirement-kpi.dialog';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';

/**
 * Requirements intake (C1) — Business authors KPI/Report/Reconciliation/Rule requirements; Builder (and
 * Ops) triage the same list as a queue (accept/reject, then deliver). One shared view, action visibility
 * gated by lens — submitting is open to every lens (no auth/identity to restrict "who is Business"), only
 * the decide/deliver actions are gated per the Wave-3 interview decision (2026-07-03).
 */
@Component({
    selector: 'app-requirements',
    standalone: true,
    imports: [
        InspectoPageHeaderComponent,
        MatButtonModule,
        MatIconModule,
        DataTableComponent,
        InspectoEmptyStateComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './requirements.component.html',
})
export class RequirementsComponent implements OnInit {
    private api = inject(RequirementsService);
    private dialog = inject(MatDialog);
    private toastr = inject(ToastrService);
    private pendingChanges = inject(PendingChangesService);
    /** Business lens = read-only — hides the decide/deliver actions in the detail dialog. */
    protected lens = inject(LensService);

    readonly requirements = signal<Requirement[]>([]);
    readonly loading = signal(false);
    /** KPI ids with a create held for approval — the Requirement is stamped only once it is approved, so without
     *  this a held create would offer Create KPI again and 409. Read from `GET /pending-changes` on every load; a
     *  hold answered here is added at once, and kept if that read fails. The create's KPI id is the Requirement's. */
    readonly pendingKpis = signal<ReadonlySet<string>>(new Set());

    // DataTable's default flex overrides a bare width, so widths here are floors (minWidth); fixed badges also cap (maxWidth).
    readonly columns: ColDef<Requirement>[] = [
        { field: 'title', headerName: 'Title', flex: 1 },
        { field: 'kind', headerName: 'Kind', minWidth: 140 },
        {
            field: 'status',
            headerName: 'Status',
            minWidth: 130,
            maxWidth: 130,
            cellRenderer: (p: ICellRendererParams<Requirement>) => statusBadgeHtml(p.value as string),
        },
        {
            field: 'deliveredNote',
            headerName: 'Linked Component / Target',
            minWidth: 220,
            cellRenderer: (p: ICellRendererParams<Requirement>) => {
                const val = p.value as string;
                if (!val) return '<span class="text-hint text-xs">—</span>';
                return `<span class="inline-flex items-center gap-1 font-mono text-xs px-2 py-0.5 rounded bg-gray-100 text-gray-800 dark:bg-gray-800 dark:text-gray-200">🔗 ${val}</span>`;
            },
        },
        { field: 'submittedAt', headerName: 'Submitted', minWidth: 170, valueFormatter: (p) => fmtDateTime(p.value) },
    ];

    readonly rowActions: InspectoRowAction<Requirement>[] = [
        { icon: 'heroicons_outline:eye', hint: 'View', onClick: (r) => this.openDetail(r) },
    ];

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.pendingChanges.list('pending').subscribe({
            next: (res) => {
                const held = res.items.filter((c) => c.kind === 'kpi' && c.operation === 'create').map((c) => c.name);
                this.pendingKpis.set(new Set(held)); // the server's list is the truth once it answers
            },
            error: () => undefined, // degrade: only this session's holds are known
        });
        this.api.list().subscribe({
            next: (r) => {
                this.requirements.set(r);
                this.loading.set(false);
            },
            error: () => {
                this.requirements.set([]);
                this.loading.set(false);
            },
        });
    }

    submit(): void {
        this.dialog
            .open(RequirementFormDialog, { width: '480px' })
            .afterClosed()
            .subscribe((result?: RequirementFormResult) => {
                if (!result) return;
                const r = buildRequirement(result.title, result.kind, result.description);
                if (result.targetComponent) {
                    r.deliveredNote = result.targetComponent;
                }
                this.api.create(r).subscribe({
                    next: () => {
                        this.toastr.success(`Requirement "${r.title}" submitted`);
                        this.load();
                    },
                    error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not submit the requirement')),
                });
            });
    }

    openDetail(r: Requirement): void {
        this.dialog
            .open(RequirementDecisionDialog, {
                data: { ...r, kpiPending: this.pendingKpis().has(r.id) } satisfies RequirementDetail,
                width: '520px',
            })
            .afterClosed()
            .subscribe((result?: RequirementDecisionResult) => {
                if (result?.action === 'createKpi') {
                    this.createKpi(r);
                    return;
                }
                if (!result || !this.lens.canTriageRequirements()) return;
                const updated$ =
                    result.action === 'decide'
                        ? this.api.decide(r.id, result.accept, result.note)
                        : this.api.deliver(r.id, result.note);
                updated$.subscribe({
                    next: (updated) => {
                        this.toastr.success(`"${r.title}" ${updated.status}`);
                        this.load();
                    },
                    error: (e) => this.toastr.error(apiErrorMessage(e, `Could not update "${r.title}"`)),
                });
            });
    }

    /** Ask the Measure and period; the dialog makes the `POST /requirements/{id}/kpi` call (server: `canAuthorWorkbench`). */
    createKpi(r: Requirement): void {
        if (!this.lens.canAuthorWorkbench()) return;
        this.dialog
            .open(RequirementKpiDialog, { data: r, width: '560px' })
            .afterClosed()
            .subscribe((res?: RequirementKpiResult) => {
                if (!res) return;
                if ('pendingChange' in res) {
                    this.pendingKpis.update((s) => new Set([...s, r.id]));
                    this.toastr.info(`The KPI for "${r.title}" is waiting for approval in Pending Changes`);
                } else this.toastr.success(`KPI "${res.name}" created — find it under KPI & Reports`);
                this.load();
            });
    }
}
