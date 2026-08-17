import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { AuditRow, CatalogService, RunsService } from 'app/inspecto/api';
import { DataTableComponent } from 'app/inspecto/data-table';

/** Batch detail — summary + member files + input→output lineage for one batch. */
@Component({
    selector: 'app-batch-detail-dialog',
    standalone: true,
    imports: [MatDialogModule, MatButtonModule, MatProgressSpinnerModule, DataTableComponent, RouterLink],
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

    /** The store this batch wrote, and the catalog node it resolved to — blank/null when unresolvable. */
    readonly outputTable = signal('');
    readonly catalogNodeId = signal<string | null>(null);

    get batchSummary(): { key: string; value: string }[] {
        return this.batchRow() ? Object.entries(this.batchRow()).map(([key, value]) => ({ key, value })) : [];
    }

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
            },
            error: () => {
                this.loading.set(false);
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
