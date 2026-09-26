import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { Router } from '@angular/router';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ReconApiService } from 'app/inspecto/api';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { escapeHtml } from 'app/inspecto/components/status-badge.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime, InspectoRowAction } from 'app/inspecto/grid';
import {
    buildReconciliation,
    Reconciliation,
    reconciliationTitle,
    ReconciliationsService,
} from 'app/inspecto/reconciliation';
import { ReconciliationFormDialog, ReconciliationFormResult } from './reconciliation-form.dialog';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';

/** A list row: the Reconciliation plus its last RECORDED run — `null` never run, `undefined` not known. */
type ReconciliationRow = Reconciliation & { lastRunAt: string | null | undefined };

/**
 * Reconciliation (C9) — the list of Dataset-vs-Dataset reconciliations; open one to run it and drill its
 * breaks. Authoring (create) is a Business surface per the plan; open to every lens (no identity model).
 */
@Component({
    selector: 'app-reconciliations',
    standalone: true,
    imports: [
        InspectoPageHeaderComponent,
        MatButtonModule,
        MatIconModule,
        DataTableComponent,
        InspectoEmptyStateComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './reconciliations.component.html',
})
export class ReconciliationsComponent implements OnInit {
    private api = inject(ReconciliationsService);
    private reconApi = inject(ReconApiService);
    private dialog = inject(MatDialog);
    private router = inject(Router);
    private toastr = inject(ToastrService);

    readonly reconciliations = signal<Reconciliation[]>([]);
    readonly loading = signal(false);
    /** Reconciliation id → its last recorded run (R2-03, server-side state); null until read or on a failed read. */
    private readonly lastRuns = signal<Record<string, string | null> | null>(null);

    /**
     * The rows with their last recorded run. ⚠ A failed state read leaves the column `—`, never "never" —
     * that would claim nothing ever ran.
     */
    readonly rows = computed<ReconciliationRow[]>(() => {
        const runs = this.lastRuns();
        return this.reconciliations().map((r) => ({ ...r, lastRunAt: runs ? (runs[r.id] ?? null) : undefined }));
    });

    readonly columns: ColDef<ReconciliationRow>[] = [
        {
            // R2-16: the readable title leads (sorted on it); the id follows as secondary text, and the quick
            // search matches either.
            field: 'name',
            headerName: 'Reconciliation',
            flex: 2,
            valueGetter: (p) => (p.data ? reconciliationTitle(p.data) : ''),
            getQuickFilterText: (p) => (p.data ? `${reconciliationTitle(p.data)} ${p.data.id}` : ''),
            cellRenderer: (p: ICellRendererParams<ReconciliationRow>) => {
                if (!p.data) return '';
                const title = reconciliationTitle(p.data);
                const id =
                    title === p.data.id
                        ? ''
                        : `<span class="text-secondary ml-2 font-mono text-xs">${escapeHtml(p.data.id)}</span>`;
                return `<span>${escapeHtml(title)}</span>${id}`;
            },
        },
        { field: 'leftDataset', headerName: 'Left', flex: 1 },
        { field: 'rightDataset', headerName: 'Right', flex: 1 },
        { headerName: 'Keys', width: 140, valueGetter: (p) => (p.data?.keyColumns ?? []).join(', ') },
        {
            field: 'lastRunAt',
            headerName: 'Last run',
            width: 180,
            valueFormatter: (p) => (p.value === undefined ? '—' : p.value ? fmtDateTime(p.value) : 'never'),
        },
    ];

    readonly rowActions: InspectoRowAction<ReconciliationRow>[] = [
        { icon: 'heroicons_outline:arrow-right', hint: 'Open', onClick: (r) => this.open(r) },
        { icon: 'heroicons_outline:document-duplicate', hint: 'Duplicate', onClick: (r) => this.duplicate(r) },
    ];

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading.set(true);
        // Independent of the list: a failed state read degrades the "Last run" column only.
        this.reconApi.states().subscribe({
            next: (res) =>
                this.lastRuns.set(Object.fromEntries(res.states.map((s) => [s.reconciliation, s.lastRunAt]))),
            error: () => this.lastRuns.set(null),
        });
        this.api.list().subscribe({
            next: (r) => {
                this.reconciliations.set(r);
                this.loading.set(false);
            },
            error: () => {
                this.reconciliations.set([]);
                this.loading.set(false);
            },
        });
    }

    open(r: Reconciliation): void {
        this.router.navigate(['/reconciliation', r.id]);
    }

    create(): void {
        this.openForm({});
    }

    /** The template flow (design §8): prefill from an existing recon, create a fresh one (no run state). */
    duplicate(source: Reconciliation): void {
        this.openForm({ recon: source, duplicate: true });
    }

    private openForm(data: object): void {
        this.dialog
            .open(ReconciliationFormDialog, { width: '640px', maxHeight: '85vh', data })
            .afterClosed()
            .subscribe((result?: ReconciliationFormResult) => {
                if (!result) return;
                const r: Reconciliation = {
                    ...buildReconciliation(
                        result.name,
                        result.leftDataset,
                        result.rightDataset,
                        result.keyColumns,
                        result.compareColumns,
                    ),
                    thirdDataset: result.thirdDataset,
                    bands: result.bands,
                };
                this.api.create(r).subscribe({
                    next: () => this.router.navigate(['/reconciliation', r.id]),
                    error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not create the reconciliation')),
                });
            });
    }
}
