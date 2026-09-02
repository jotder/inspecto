import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { AuditRow, CatalogService, ConsignmentOutputRow, JobRunRow, RunsService } from 'app/inspecto/api';
import { DataTableComponent } from 'app/inspecto/data-table';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';

/** Batch detail — summary + member files + input→output lineage for one batch. */
@Component({
    selector: 'app-batch-detail-dialog',
    standalone: true,
    imports: [
        MatDialogModule,
        MatButtonModule,
        MatProgressSpinnerModule,
        DataTableComponent,
        InspectoAlertComponent,
        StatusBadgeComponent,
        RouterLink,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Batch {{ data.batchId }}</h2>
        <mat-dialog-content>
            @if (loading()) {
                <mat-progress-spinner diameter="24" mode="indeterminate"></mat-progress-spinner>
            } @else {
                <div class="font-semibold">Summary</div>
                @if (!batchRow()) {
                    <p class="text-secondary text-sm">No batch-summary row found for this id.</p>
                } @else {
                    <table class="mt-1 text-sm">
                        <tbody>
                            @for (kv of batchSummary; track kv.key) {
                                <tr>
                                    <th scope="row" class="pr-4 text-left align-top">
                                        {{ kv.key }}
                                    </th>
                                    <td>{{ kv.value }}</td>
                                </tr>
                            }
                        </tbody>
                    </table>
                    @if (catalogNodeId()) {
                        <a
                            class="mt-2 inline-block text-primary hover:underline"
                            [routerLink]="['/catalog']"
                            [queryParams]="{ tab: 'graph', from: catalogNodeId() }"
                            mat-dialog-close
                        >
                            View {{ outputTable() }} in the Catalog
                        </a>
                    }
                }

                <!-- PARK-1(a): a PARKED Consignment says WHICH Step it parked at and WHERE its rows sit,
                     straight from the manifest the drain will read — before this the dialog could only
                     show the word PARKED. Absent on every non-parked row, so nothing else changes. -->
                @if (parked().length) {
                    <div class="mt-4 font-semibold">Parked at ({{ parked().length }})</div>
                    <inspecto-alert variant="warning">
                        This Consignment is uncommitted: its rows for the Steps below are held durably in the
                        park tables listed. Re-enable the Step, then drain it from the Batches tab.
                    </inspecto-alert>
                    <table class="mt-1 text-sm" data-testid="parked-table">
                        <thead>
                            <tr>
                                <th scope="col" class="pr-4 text-left">Step</th>
                                <th scope="col" class="text-left">Park table</th>
                            </tr>
                        </thead>
                        <tbody>
                            @for (p of parked(); track p.step) {
                                <tr>
                                    <td class="pr-4 align-top font-mono">{{ p.step }}</td>
                                    <td class="break-all font-mono">{{ p.table || '—' }}</td>
                                </tr>
                            }
                        </tbody>
                    </table>
                }

                <div class="mt-4 font-semibold">Member files ({{ batchFiles().length }})</div>
                <inspecto-data-table
                    tier="mini"
                    sourceName="files"
                    [rows]="batchFiles()"
                    height="14rem"
                    noRowsTitle="No member files"
                />

                <div class="mt-4 font-semibold">Lineage ({{ batchLineage().length }})</div>
                <inspecto-data-table
                    tier="mini"
                    sourceName="lineage"
                    [rows]="batchLineage()"
                    height="14rem"
                    noRowsTitle="No lineage"
                />

                <!-- Registered outputs: what SYNC wrote plus what any post-sync step derived onto this
                     Consignment. Fetched separately from the core forkJoin so a registry that is off, or
                     a route an older backend has not got, degrades to an explanation instead of blanking
                     the dialog. -->
                <div class="mt-4 font-semibold">Registered outputs ({{ batchOutputs().length }})</div>
                @if (outputsNote(); as note) {
                    <inspecto-alert variant="info">{{ note }}</inspecto-alert>
                } @else {
                    <inspecto-data-table
                        tier="mini"
                        sourceName="outputs"
                        [rows]="batchOutputs()"
                        height="14rem"
                        noRowsTitle="No registered outputs"
                    />
                }

                <!-- X2 cross-lane provenance, reverse half: which at-rest job runs READ this Consignment.
                     Rendered only when the backend could answer — an absent list is "unknown", and unknown
                     must never look like "no run ever consumed this". -->
                @if (derivedRuns(); as runs) {
                    <div class="mt-4 font-semibold" data-testid="derived-runs">Derived runs ({{ runs.length }})</div>
                    @if (runs.length) {
                        <ul class="mt-1 space-y-1 text-xs">
                            @for (run of runs; track run.runId) {
                                <li class="flex flex-wrap items-baseline gap-x-2">
                                    <inspecto-status-badge [value]="run.status" />
                                    <span class="font-mono">{{ run.job }}</span>
                                    <span class="text-secondary font-mono">{{ run.runId }}</span>
                                    <span class="text-secondary">{{ run.startTime }}</span>
                                </li>
                            }
                        </ul>
                    } @else {
                        <p class="text-secondary text-sm">No at-rest run has read this Consignment.</p>
                    }
                }
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button mat-dialog-close>Close</button>
        </mat-dialog-actions>
    `,
})
export class BatchDetailDialog implements OnInit {
    readonly data = inject<{ pipeline: string; batchId: string }>(MAT_DIALOG_DATA);
    private api = inject(RunsService);
    private catalog = inject(CatalogService);

    readonly loading = signal(true);
    readonly batchRow = signal<AuditRow | null>(null);
    readonly batchFiles = signal<AuditRow[]>([]);
    readonly batchLineage = signal<AuditRow[]>([]);
    /** The Consignment's registered outputs — sync's own files AND any post-sync step's derived tables. */
    readonly batchOutputs = signal<ConsignmentOutputRow[]>([]);
    /**
     * Why there is no table to show, or null when there is one.
     *
     * ⚠ "off" and "empty" are DIFFERENT and must not render the same: an empty list with the registry
     * disabled would read as "this Consignment wrote nothing", which is false — the manifest, not this
     * table, is authoritative for a file's existence.
     */
    readonly outputsNote = signal<string | null>(null);
    /** The at-rest runs that read this Consignment; `null` = the backend could not say (no run store). */
    readonly derivedRuns = signal<JobRunRow[] | null>(null);

    /** The store this batch wrote, and the catalog node it resolved to — blank/null when unresolvable. */
    readonly outputTable = signal('');
    readonly catalogNodeId = signal<string | null>(null);

    /** The two keys `/runs/{name}/batches` adds to a PARKED row. They are STRUCTURED (a list and a map),
     *  not ledger strings, so they render in their own table below rather than as `[object Object]`. */
    private static readonly PARK_KEYS = new Set(['parkedAt', 'parkedTables']);

    get batchSummary(): { key: string; value: string }[] {
        const row = this.batchRow();
        return row
            ? Object.entries(row)
                  .filter(([key]) => !BatchDetailDialog.PARK_KEYS.has(key))
                  .map(([key, value]) => ({ key, value: String(value) }))
            : [];
    }

    /**
     * One row per Step this Consignment parked at, with the durable park table holding its rows.
     * ⚠ `AuditRow` is typed `Record<string, string>` because a ledger row IS all strings; these two
     * keys are the deliberate exception (route-merged from the manifest), hence the local narrowing.
     */
    readonly parked = computed<{ step: string; table: string }[]>(() => {
        const row = this.batchRow() as Record<string, unknown> | null;
        if (!row || row['status'] !== 'PARKED') return [];
        const at = Array.isArray(row['parkedAt']) ? (row['parkedAt'] as string[]) : [];
        const tables = (row['parkedTables'] ?? {}) as Record<string, string>;
        return at.map((step) => ({ step, table: tables[step] ?? '' }));
    });

    ngOnInit(): void {
        const { pipeline, batchId } = this.data;
        forkJoin({
            batches: this.api.batches(pipeline),
            files: this.api.files(pipeline),
            lineage: this.api.lineage(pipeline, batchId),
        }).subscribe({
            next: ({ batches, files, lineage }) => {
                this.batchRow.set(batches.find((b) => b['consignment_id'] === batchId) || null);
                this.batchFiles.set(files.filter((f) => f['consignment_id'] === batchId));
                this.batchLineage.set(lineage);
                this.loading.set(false);
                this.resolveCatalogNode();
                this.loadOutputs(pipeline, batchId);
            },
            error: () => {
                this.loading.set(false);
            },
        });
    }

    /** Independent of the core fetch: this one failing must not blank a dialog that already has data. */
    private loadOutputs(pipeline: string, batchId: string): void {
        this.api.consignmentOutputs(pipeline, batchId).subscribe({
            next: (page) => {
                this.batchOutputs.set(page.outputs ?? []);
                this.derivedRuns.set(page.derivedRuns ?? null);
                this.outputsNote.set(
                    page.enabled
                        ? null
                        : 'The Consignment output registry is switched off, so this list cannot be shown. ' +
                              'That is not the same as this Consignment having written nothing.',
                );
            },
            error: () => {
                this.batchOutputs.set([]);
                this.outputsNote.set('This backend does not serve registered outputs.');
            },
        });
    }

    /**
     * Offer the Catalog jump only when the backend can prove which node this batch's store is. A 404
     * (unknown store, or a name several stores answer to) leaves the link off — a lineage link to the
     * wrong store is worse than no link at all.
     */
    private resolveCatalogNode(): void {
        this.outputTable.set(this.batchRow()?.['output_table'] || '');
        if (!this.outputTable()) return;
        this.catalog
            .resolveTable(this.outputTable())
            .pipe(catchError(() => of(null)))
            .subscribe((hit) => this.catalogNodeId.set(hit?.id ?? null));
    }
}
