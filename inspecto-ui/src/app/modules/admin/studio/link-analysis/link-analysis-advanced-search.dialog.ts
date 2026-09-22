import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { EntityProjection } from 'app/inspecto/graph';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoDialogResizeDirective } from 'app/inspecto/components/dialog-resize.directive';
import { DataTableComponent } from 'app/inspecto/data-table';
import { DatasetRowsService } from 'app/inspecto/viz/dataset-rows.service';
import { Dataset } from 'app/modules/admin/studio/datasets/dataset-types';
import { ProjectedGraph, projectEntities } from './entity-projection';

export interface LinkAnalysisAdvancedSearchData {
    dataset: Dataset;
    /** The mapping the resulting rows are folded with when the analyst projects them. */
    projection: EntityProjection;
}

/**
 * **Link Analysis — advanced search** (mockup review point 4). SQL over the projected Dataset with a
 * **tabular** result, beside — not instead of — the predicate builder. It rides the data-table's existing
 * Pro tier: the CodeMirror editor is seeded with `SELECT * FROM "<sourceName>"`, runs offline over the loaded
 * sample, and *Run on server* goes through `DatasetRowsService.sql` (`POST /db/query`, the guarded read-only
 * route). ⚠ Free-text SQL never reaches `/inv/projection` (spec §4.3): **Project result as graph** folds the
 * returned rows client-side with the current mapping, so what gets projected is the result relation.
 *
 * A graph-query dialect (Cypher/GQL) belongs here in a graph-store-backed edition; over a Dataset it is
 * stated as unavailable rather than emulated.
 */
@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        MatButtonModule,
        MatDialogModule,
        MatIconModule,
        MatTooltipModule,
        InspectoAlertComponent,
        InspectoDialogResizeDirective,
        DataTableComponent,
    ],
    template: `
        <div class="flex items-center gap-2 pr-2">
            <h2 mat-dialog-title inspectoDialogResize #chrome="inspectoDialogResize" class="!mb-0 flex-1">
                Advanced search — {{ data.dataset.name }}
            </h2>
            <span class="text-secondary text-xs" aria-live="polite">{{ status() }}</span>
            <button
                mat-icon-button
                type="button"
                (click)="chrome.toggleMaximize()"
                [matTooltip]="chrome.maximized() ? 'Exit full screen' : 'Full screen'"
                [attr.aria-label]="chrome.maximized() ? 'Exit full screen' : 'Full screen'"
            >
                <mat-icon
                    [svgIcon]="
                        chrome.maximized()
                            ? 'heroicons_outline:arrows-pointing-in'
                            : 'heroicons_outline:arrows-pointing-out'
                    "
                ></mat-icon>
            </button>
        </div>
        <mat-dialog-content class="flex flex-col gap-3">
            <inspecto-alert variant="info">
                Open the SQL editor from the table toolbar. It runs over the loaded sample; <b>Run on server</b> sends
                the read-only statement to the Dataset. Projecting folds the <b>result rows</b> with the current mapping
                ({{ data.projection.sourceCol }} → {{ data.projection.targetCol }}); SQL text never reaches the
                projection route.
            </inspecto-alert>
            @if (error(); as e) {
                <inspecto-alert variant="error" title="Query failed">{{ e }}</inspecto-alert>
            }
            <inspecto-data-table
                tier="pro"
                stateKey="link-analysis-advanced"
                [rows]="rows()"
                [loading]="loading()"
                [sourceName]="data.dataset.sourceName"
                [serverRun]="true"
                (runOnServer)="runOnServer($event)"
                (queryStarted)="error.set('')"
                height="22rem"
                [pageSize]="10"
                exportName="link-analysis-advanced-search"
                noRowsTitle="No rows — run a query"
            ></inspecto-data-table>
            <p class="text-secondary text-xs">
                Graph query (Cypher / GQL): available when the projection is backed by a graph store; not emulated over
                a Dataset.
            </p>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button mat-dialog-close type="button">Cancel</button>
            <button
                mat-flat-button
                color="primary"
                type="button"
                (click)="project()"
                [disabled]="!rows().length || loading()"
                aria-label="Project the result rows as a graph"
            >
                Project result as graph
            </button>
        </mat-dialog-actions>
    `,
})
export class LinkAnalysisAdvancedSearchDialog implements OnInit {
    readonly data = inject<LinkAnalysisAdvancedSearchData>(MAT_DIALOG_DATA);
    private readonly ref =
        inject<MatDialogRef<LinkAnalysisAdvancedSearchDialog, ProjectedGraph | undefined>>(MatDialogRef);
    private readonly datasetRows = inject(DatasetRowsService);

    readonly rows = signal<Record<string, unknown>[]>([]);
    readonly truncated = signal(false);
    readonly loading = signal(false);
    readonly error = signal('');
    /** Where the current rows came from — the seed sample or a server-run statement. */
    readonly origin = signal<'sample' | 'server'>('sample');
    readonly status = computed(() => {
        const n = this.rows().length.toLocaleString();
        const src = this.origin() === 'server' ? 'server result' : 'sample';
        return this.loading() ? 'loading…' : `${n} rows · ${src}${this.truncated() ? ' · truncated' : ''}`;
    });

    ngOnInit(): void {
        void this.load(() => this.datasetRows.rows(this.data.dataset, 500), 'sample');
    }

    /** The data-table's "Run on server": the guarded read-only route, results back into the same grid. */
    runOnServer(sql: string): void {
        void this.load(() => this.datasetRows.sql(this.data.dataset.sourceName, sql, 20000), 'server');
    }

    /** Fold the result relation with the current mapping and hand the graph back to the studio. */
    project(): void {
        const g = projectEntities(this.rows(), this.data.projection);
        if ('error' in g) {
            this.error.set(g.error);
            return;
        }
        this.ref.close(g);
    }

    private async load(
        fetch: () => Promise<{ rows: Record<string, unknown>[]; truncated: boolean; error?: string }>,
        origin: 'sample' | 'server',
    ): Promise<void> {
        this.loading.set(true);
        this.error.set('');
        try {
            const page = await fetch();
            this.rows.set(page.rows);
            this.truncated.set(page.truncated);
            this.origin.set(origin);
            if (page.error) this.error.set(page.error);
        } catch (err) {
            this.error.set(err instanceof Error ? err.message : 'The query failed.');
        } finally {
            this.loading.set(false);
        }
    }
}
