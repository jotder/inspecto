import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { Router } from '@angular/router';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, LensService, ReconApiService } from 'app/inspecto/api';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { escapeHtml } from 'app/inspecto/components/status-badge.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime, InspectoRowAction } from 'app/inspecto/grid';
import {
    buildReconciliation,
    datasetLabels,
    Reconciliation,
    reconciliationTitle,
    ReconciliationsService,
} from 'app/inspecto/reconciliation';
import { ReconciliationFormDialog, ReconciliationFormResult } from './reconciliation-form.dialog';
import { DatasetsService } from '../studio/datasets/datasets.service';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';

/**
 * A list row: the Reconciliation plus its last RECORDED run — `null` never run, `undefined` not known — and each
 * side's readable Dataset label (the id until the Dataset list arrives).
 */
type ReconciliationRow = Reconciliation & {
    lastRunAt: string | null | undefined;
    leftLabel: string;
    rightLabel: string;
};

/**
 * Reconciliation (C9) — the list of Dataset-vs-Dataset reconciliations; open one to run it and drill its
 * breaks. Creating one (New / Duplicate) writes a `reconciliation` Component, which the server gates on
 * `canAuthorWorkbench` — so both actions show only with that capability (R3-05).
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
    private datasetsApi = inject(DatasetsService);
    protected lens = inject(LensService);

    readonly reconciliations = signal<Reconciliation[]>([]);
    readonly loading = signal(false);
    /** Reconciliation id → its last recorded run (R2-03, server-side state); null until read or on a failed read. */
    private readonly lastRuns = signal<Record<string, string | null> | null>(null);
    /** Dataset id → readable label (description → name → id), as the Board and Breaks page name sides (R3-02). */
    private readonly datasetNames = signal<Record<string, string>>({});

    /**
     * The rows with their last recorded run. ⚠ A failed state read leaves the column `—`, never "never" —
     * that would claim nothing ever ran.
     */
    readonly rows = computed<ReconciliationRow[]>(() => {
        const runs = this.lastRuns();
        const names = this.datasetNames();
        return this.reconciliations().map((r) => ({
            ...r,
            lastRunAt: runs ? (runs[r.id] ?? null) : undefined,
            leftLabel: names[r.leftDataset] || r.leftDataset,
            rightLabel: names[r.rightDataset] || r.rightDataset,
        }));
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
        sideColumn('leftDataset', 'leftLabel', 'Left'),
        sideColumn('rightDataset', 'rightLabel', 'Right'),
        { headerName: 'Keys', width: 140, valueGetter: (p) => (p.data?.keyColumns ?? []).join(', ') },
        {
            field: 'lastRunAt',
            headerName: 'Last run',
            width: 180,
            valueFormatter: (p) => (p.value === undefined ? '—' : p.value ? fmtDateTime(p.value) : 'never'),
        },
    ];

    /** Duplicate creates a Reconciliation, so it is an authoring action like New (R3-05). */
    readonly rowActions = computed<InspectoRowAction<ReconciliationRow>[]>(() => [
        { icon: 'heroicons_outline:arrow-right', hint: 'Open', onClick: (r) => this.open(r) },
        ...(this.lens.canAuthorWorkbench()
            ? [
                  {
                      icon: 'heroicons_outline:document-duplicate',
                      hint: 'Duplicate',
                      onClick: (r: ReconciliationRow) => this.duplicate(r),
                  },
              ]
            : []),
    ]);

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading.set(true);
        // Labels only: a failed read leaves the sides named by their ids.
        this.datasetsApi
            .list()
            .subscribe({ next: (d) => this.datasetNames.set(datasetLabels(d)), error: () => undefined });
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

/** A side named by its Dataset's readable label, the id in the tooltip and the quick search (R3-02). */
function sideColumn(
    field: 'leftDataset' | 'rightDataset',
    label: 'leftLabel' | 'rightLabel',
    headerName: string,
): ColDef<ReconciliationRow> {
    return {
        field: label,
        headerName,
        flex: 1,
        tooltipValueGetter: (p) => p.data?.[field] ?? '',
        getQuickFilterText: (p) => (p.data ? `${p.data[label]} ${p.data[field]}` : ''),
    };
}
