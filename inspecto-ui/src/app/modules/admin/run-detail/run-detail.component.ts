import {
    ChangeDetectionStrategy,
    Component,
    effect,
    inject,
    input,
    OnInit,
    output,
    signal,
    ViewEncapsulation,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { forkJoin, Observable } from 'rxjs';
import { apiErrorMessage, AuditRow, BatchAuditReport, InboxStatus, LensService, RunsService } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { DataTableComponent } from 'app/inspecto/data-table';
import { FmtPercentPipe } from 'app/inspecto/format';
import { InspectoRowAction } from 'app/inspecto/grid';
import { BatchDetailDialog } from './batch-detail.dialog';
import { RejectedRowsDialog } from './rejected-rows.dialog';

type TabKey = 'batches' | 'files' | 'lineage' | 'quarantine' | 'commits' | 'report';
type FileFilter = 'ALL' | 'SUCCESS' | 'REJECTED' | 'ERRORED';

/**
 * Run detail — tabbed view over the audit endpoints for a single run (ported from
 * inspector-ui onto the gamma shell): batches / files / lineage (filterable by batchId) /
 * quarantine / commits, plus a Report tab with a date-range producing percentile + throughput
 * stats. Audit rows are loose string maps, so grid columns are derived from the row keys.
 *
 * Hosted two ways (ui-design-review R5): standalone full page (route-snapshot `name`, breadcrumb
 * header) or embedded as the Runs side panel (`[name]` + `[embedded]` inputs, compact header with
 * an X that emits `(closed)`).
 */
@Component({
    selector: 'app-run-detail',
    standalone: true,
    imports: [
        FormsModule,
        MatButtonModule,
        MatDatepickerModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        MatSelectModule,
        MatTabsModule,
        MatTooltipModule,
        DataTableComponent,
        FmtPercentPipe,
        InspectoAlertComponent,
        RouterLink,
    ],
    templateUrl: './run-detail.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
    encapsulation: ViewEncapsulation.None,
})
export class RunDetailComponent implements OnInit {
    private api = inject(RunsService);
    private route = inject(ActivatedRoute);
    private router = inject(Router);
    private dialog = inject(MatDialog);
    private toastr = inject(ToastrService);
    private confirm = inject(InspectoConfirmService);
    /** Business lens = read-only observe on Runs (plan §1) — hides the reprocess row action. */
    protected lens = inject(LensService);

    /** Run name when embedded as a side panel; the route-snapshot param is the full-page fallback. */
    readonly nameInput = input<string | undefined>(undefined, { alias: 'name' });
    /** Embedded (side-panel) mode — compact header with a close button instead of breadcrumb chrome. */
    readonly embedded = input(false);
    readonly closed = output<void>();

    name = '';
    readonly loading = signal(false);

    /** The panel stays mounted while the user clicks through runs — reload when the bound name changes. */
    private readonly reloadOnName = effect(() => {
        const n = this.nameInput();
        if (n === undefined || n === this.name) return;
        this.name = n;
        this.rows.set([]);
        this.allFiles = [];
        this.inbox.set(null);
        this.stepAgeText.set('');
        this.report.set(null);
        this.lineageBatchId = '';
        this.loadTab();
    });

    readonly tabs: { id: TabKey; label: string }[] = [
        { id: 'batches', label: 'Batches' },
        { id: 'files', label: 'Files' },
        { id: 'lineage', label: 'Lineage' },
        { id: 'quarantine', label: 'Quarantine' },
        { id: 'commits', label: 'Commits' },
        { id: 'report', label: 'Report' },
    ];
    selectedIndex = 0;
    get activeTab(): TabKey {
        return this.tabs[this.selectedIndex].id;
    }

    readonly rows = signal<AuditRow[]>([]); // generic grid (batches/lineage/quarantine/commits)
    /** Batches tab only: whether any consignment is FAILED — gates the retry-is-automatic notice. */
    readonly hasFailedBatches = signal(false);
    lineageBatchId = '';

    // files tab
    allFiles: AuditRow[] = [];
    readonly inbox = signal<InboxStatus | null>(null);
    /** Age of the step gauge's `startedAt`, computed ONCE per load — a template call to a
     *  Date.now()-based formatter changes between change-detection passes and throws NG0100. */
    readonly stepAgeText = signal('');
    fileStatus: FileFilter = 'ALL';
    readonly fileFilters: FileFilter[] = ['ALL', 'SUCCESS', 'REJECTED', 'ERRORED'];

    // report tab
    from: Date | null = null;
    to: Date | null = null;
    readonly report = signal<BatchAuditReport | null>(null);

    ngOnInit(): void {
        this.name = this.nameInput() ?? this.route.snapshot.paramMap.get('name') ?? '';
        this.loadTab();
    }

    onTabChange(): void {
        this.loadTab();
    }

    loadTab(): void {
        const tab = this.activeTab;
        if (tab === 'report') {
            this.loadReport();
            return;
        }
        if (tab === 'files') {
            this.loadFiles();
            return;
        }
        this.loading.set(true);
        const call: Observable<AuditRow[] | string[]> =
            tab === 'batches'
                ? this.api.batches(this.name)
                : tab === 'lineage'
                  ? this.api.lineage(this.name, this.lineageBatchId.trim() || undefined)
                  : tab === 'quarantine'
                    ? this.api.quarantine(this.name)
                    : this.api.commits(this.name);

        call.subscribe({
            next: (data: AuditRow[] | string[]) => {
                this.rows.set(
                    (data as unknown[]).map((d) =>
                        typeof d === 'string' ? ({ commit: d } as AuditRow) : (d as AuditRow),
                    ),
                );
                this.hasFailedBatches.set(tab === 'batches' && this.rows().some((r) => r['status'] === 'FAILED'));
                this.loading.set(false);
            },
            error: () => {
                this.loading.set(false);
                this.rows.set([]);
            },
        });
    }

    loadFiles(): void {
        this.loading.set(true);
        // processed history (audit) + live inbox/processing status, together.
        forkJoin({
            files: this.api.files(this.name),
            inbox: this.api.pending(this.name),
        }).subscribe({
            next: ({ files, inbox }) => {
                this.allFiles = files;
                this.inbox.set(inbox);
                this.stepAgeText.set(inbox?.step ? this.stepAge(inbox.step.startedAt) : '');
                this.loading.set(false);
            },
            error: () => {
                this.allFiles = [];
                this.inbox.set(null);
                this.stepAgeText.set('');
                this.loading.set(false);
            },
        });
    }

    loadReport(): void {
        this.loading.set(true);
        const window = {
            from: this.from ? this.from.toISOString() : undefined,
            to: this.to ? this.to.toISOString() : undefined,
        };
        this.api.report(this.name, window).subscribe({
            next: (r) => {
                this.report.set(r);
                this.loading.set(false);
            },
            error: () => {
                this.loading.set(false);
                this.report.set(null);
            },
        });
    }

    // ── row actions (audit rows are loose maps; columns are auto-derived by the data table) ──
    /** Batch rows can be reprocessed by batch id; lineage/commits are read-only. Reprocess is
     *  hidden in the Business lens (read-only observe, plan §1) — Lineage & details stays available. */
    get auditRowActions(): InspectoRowAction<AuditRow>[] {
        if (this.activeTab !== 'batches' && this.activeTab !== 'quarantine') return [];
        // Quarantine rows are synthesized off the on-disk layout (file/reason/path/size_bytes) and never
        // carry a batch id — a quarantined file was rejected before any batch existed — so both
        // batch-keyed actions hide themselves on rows without one.
        const details: InspectoRowAction<AuditRow> = {
            icon: 'heroicons_outline:rectangle-group',
            hint: 'Lineage & details',
            visible: (r) => !!r['consignment_id'],
            onClick: (r) => this.openBatchById(r['consignment_id']),
        };
        const rejects = this.rejectedRowsAction;
        if (!this.lens.canOperateRuns()) return this.activeTab === 'quarantine' ? [details, rejects] : [details];
        const operate: InspectoRowAction<AuditRow>[] = [
            details,
            {
                icon: 'heroicons_outline:arrow-path',
                hint: 'Reprocess this batch',
                visible: (r) => !!r['consignment_id'],
                onClick: (r) => this.reprocessRow(r),
            },
        ];
        if (this.activeTab === 'quarantine') return [...operate, rejects];
        // Phase 4 S4 / D-13: draining is a BATCH-tab action, and only on a PARKED row — a quarantined
        // file was rejected before any Consignment existed, and the engine refuses every other state
        // with a 409. Offering it elsewhere would be an affordance that predictably fails.
        operate.push({
            icon: 'heroicons_outline:play',
            hint: 'Drain this parked Consignment',
            visible: (r) => !!r['consignment_id'] && r['status'] === 'PARKED',
            onClick: (r) => this.drainRow(r),
        });
        return operate;
    }

    readonly fileRowActions: InspectoRowAction<AuditRow>[] = [
        {
            icon: 'heroicons_outline:rectangle-group',
            hint: 'Open the batch this file belongs to',
            onClick: (r) => this.openBatchById(r['consignment_id']),
        },
        {
            icon: 'heroicons_outline:exclamation-triangle',
            hint: 'View the rejected rows',
            // Only where rows were actually rejected — the ledger's own count decides, so the
            // affordance never promises detail that was never written.
            visible: (r) => this.errorRows(r) > 0,
            onClick: (r) => this.openRejectedRows(r),
        },
    ];

    /** Quarantine rows carry no count (the whole file was rejected), so the action always shows. */
    private get rejectedRowsAction(): InspectoRowAction<AuditRow> {
        return {
            icon: 'heroicons_outline:exclamation-triangle',
            hint: 'View the rejected rows',
            onClick: (r) => this.openRejectedRows(r),
        };
    }

    // ── file-processing status ───────────────────────────────────────────────────
    /**
     * Age of the step gauge's `startedAt`, e.g. "12s" / "3m 05s" / "2h 14m". The AGE is the gauge's
     * hang signal by design (consignment-status-flow OKF): the snapshot is in-memory and always
     * "present", so a step that stopped advancing shows only as a growing age.
     */
    stepAge(startedAt: string): string {
        const ms = Date.now() - Date.parse(startedAt);
        if (!Number.isFinite(ms) || ms < 0) return '0s';
        const s = Math.floor(ms / 1000);
        if (s < 60) return `${s}s`;
        const m = Math.floor(s / 60);
        if (m < 60) return `${m}m ${String(s % 60).padStart(2, '0')}s`;
        return `${Math.floor(m / 60)}h ${String(m % 60).padStart(2, '0')}m`;
    }

    private isSuccess(f: AuditRow): boolean {
        return (f['status'] || '').toUpperCase() === 'SUCCESS';
    }
    private errorRows(f: AuditRow): number {
        return parseInt(f['error_rows'] || '0', 10) || 0;
    }

    get fileStats(): {
        total: number;
        success: number;
        rejected: number;
        errored: number;
        rows: number;
    } {
        let success = 0,
            rejected = 0,
            errored = 0,
            rows = 0;
        for (const f of this.allFiles) {
            if (this.isSuccess(f)) success++;
            else rejected++;
            if (this.errorRows(f) > 0) errored++;
            rows += parseInt(f['parsed_rows'] || '0', 10) || 0;
        }
        return { total: this.allFiles.length, success, rejected, errored, rows };
    }

    get filteredFiles(): AuditRow[] {
        return this.allFiles.filter((f) => {
            switch (this.fileStatus) {
                case 'SUCCESS':
                    return this.isSuccess(f);
                case 'REJECTED':
                    return !this.isSuccess(f);
                case 'ERRORED':
                    return this.errorRows(f) > 0;
                default:
                    return true;
            }
        });
    }

    // ── batch detail dialog ─────────────────────────────────────────────────────
    openBatchById(batchId?: string): void {
        const id = (batchId || '').trim();
        if (!id) {
            this.toastr.warning('No batch id on this row');
            return;
        }
        this.dialog.open(BatchDetailDialog, {
            data: { pipeline: this.name, batchId: id },
            width: '880px',
            maxHeight: '85vh',
        });
    }

    /**
     * Open the rejected-row detail for an audit row. The file NAME is the route's key, and the two
     * surfaces spell it differently (`filename` in the status ledger, `file` in the quarantine
     * listing), so take the first one present rather than assuming.
     */
    openRejectedRows(r: AuditRow): void {
        const file = (r['filename'] || r['file'] || '').trim();
        if (!file) {
            this.toastr.warning('No file name on this row');
            return;
        }
        this.dialog.open(RejectedRowsDialog, {
            data: { pipeline: this.name, file },
            width: '900px',
            maxHeight: '85vh',
        });
    }

    /**
     * Complete a Consignment that PARKED at a disabled route branch. The Step must already be switched
     * back on — the engine refuses otherwise (409), and that reason is what the operator sees, because
     * "re-enable it first" is the actual next step, not a generic failure.
     */
    async drainRow(r: AuditRow): Promise<void> {
        if (!this.lens.canOperateRuns()) return; // Business lens: read-only observe
        const id = r['consignment_id'];
        if (!id) {
            this.toastr.warning('No Consignment id on this row');
            return;
        }
        const ok = await this.confirm.confirm(
            `Drain parked Consignment "${id}" of "${this.name}"? Its parked rows are written to their ` +
                `destination and the Consignment is committed.`,
            'Drain Consignment',
        );
        if (!ok) return;
        this.api.drain(this.name, id).subscribe({
            next: (res) => {
                this.toastr.success(
                    `Drained ${id} — ${res.branches.length} branch(es), ${res.rows.toLocaleString()} row(s)`,
                );
                this.loadTab();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, `Drain failed for ${id}`)),
        });
    }

    async reprocessRow(r: AuditRow): Promise<void> {
        if (!this.lens.canOperateRuns()) return; // Business lens: read-only observe
        const id = r['consignment_id'];
        if (!id) {
            this.toastr.warning('No batch id on this row');
            return;
        }
        if (!(await this.confirm.confirm(`Reprocess batch "${id}" of "${this.name}"?`, 'Reprocess batch'))) return;
        this.api.reprocess(this.name, id).subscribe({
            next: () => {
                this.toastr.success(`Reprocess requested for ${id}`);
                if (this.activeTab === 'quarantine') this.loadTab();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, `Reprocess failed for ${id}`)),
        });
    }

    back(): void {
        this.router.navigate(['/runs']);
    }
}
