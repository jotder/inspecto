import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ColDef } from 'ag-grid-community';
import { CatalogService, MetadataNode, NodeDetail } from 'app/inspecto/api';
import { DataTableComponent } from 'app/inspecto/data-table';
import { AssistPanelComponent } from 'app/inspecto/components/assist-panel.component';
import { StoreLineageComponent } from './store-lineage.component';
import { nodeKindLabel } from 'app/inspecto/graph/catalog-graph';

/**
 * Catalog node inspector: node facts + attrs, neighbour grid (click to walk the graph
 * in place — the dialog reloads and the assist panel re-keys), explain-entity assist.
 */
@Component({
    standalone: true,
    imports: [
        MatDialogModule,
        MatButtonModule,
        MatProgressSpinnerModule,
        DataTableComponent,
        AssistPanelComponent,
        StoreLineageComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>{{ detail()?.node?.label || 'Node' }}</h2>
        <mat-dialog-content>
            @if (loading()) {
                <div class="flex items-center gap-3 py-6">
                    <mat-progress-spinner diameter="24" mode="indeterminate"></mat-progress-spinner>
                    <span class="text-secondary">Loading node…</span>
                </div>
            }
            @if (!loading() && detail(); as d) {
                <table class="text-sm">
                    <tr>
                        <th scope="row" class="pr-4 text-left align-top">Id</th>
                        <td>{{ d.node.id }}</td>
                    </tr>
                    <tr>
                        <th scope="row" class="pr-4 text-left align-top">Kind</th>
                        <td>{{ nodeKindLabel(d.node.kind) }}</td>
                    </tr>
                    @if (d.node.description) {
                        <tr>
                            <th scope="row" class="pr-4 text-left align-top">Description</th>
                            <td>{{ d.node.description?.text }}</td>
                        </tr>
                    }
                    @if (d.node.overlay) {
                        <tr>
                            <th scope="row" class="pr-4 text-left align-top">Freshness</th>
                            <td>{{ d.node.overlay?.freshness || '—' }}</td>
                        </tr>
                        <tr>
                            <th scope="row" class="pr-4 text-left align-top">Row count</th>
                            <td>{{ d.node.overlay?.rowCount ?? '—' }}</td>
                        </tr>
                    }
                </table>

                <div class="mt-4 font-semibold">Attributes</div>
                <pre class="mt-1 max-h-40 overflow-auto rounded bg-gray-100 p-2 text-xs dark:bg-gray-800">{{
                    pretty(d.node.attrs || {})
                }}</pre>

                <div class="mt-4 font-semibold">
                    Neighbours ({{ d.neighbors.nodes.length }} nodes, {{ d.neighbors.edges.length }} edges)
                </div>
                <inspecto-data-table
                    tier="mini"
                    sourceName="neighbours"
                    [rows]="d.neighbors.nodes"
                    [columns]="neighbourColumns"
                    noRowsTitle="No neighbours"
                    (rowClick)="onNeighbourClicked($any($event))"
                />

                @if (isStore(d.node.kind)) {
                    <div class="mt-4 font-semibold">Lineage</div>
                    <app-store-lineage [store]="d.node.label" />
                }

                <div class="mt-4 font-semibold">Explain this entity</div>
                <!-- re-keyed on node id so the panel resets while walking neighbours -->
                @for (n of [d.node]; track n.id) {
                    <app-assist-panel
                        intent="explain-entity"
                        [screenContext]="{ entityType: 'table', id: n.id }"
                        [userText]="'Explain ' + n.label"
                        placeholder="ask a question about this entity…"
                    >
                    </app-assist-panel>
                }
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button mat-dialog-close>Close</button>
        </mat-dialog-actions>
    `,
})
export class NodeDetailDialog {
    private api = inject(CatalogService);

    readonly loading = signal(false);
    readonly detail = signal<NodeDetail | null>(null);

    /** Exposed for the template — see {@link nodeKindLabel}. */
    readonly nodeKindLabel = nodeKindLabel;

    readonly neighbourColumns: ColDef[] = [
        {
            field: 'kind',
            headerName: 'Kind',
            width: 120,
            valueFormatter: (p) => nodeKindLabel(p.value),
        },
        { field: 'label', headerName: 'Label', flex: 1 },
        { field: 'id', headerName: 'Id', flex: 1 },
    ];

    constructor() {
        const data = inject<{ id: string }>(MAT_DIALOG_DATA);
        this.load(data.id);
    }

    load(id: string): void {
        this.loading.set(true);
        this.detail.set(null);
        this.api.node(id).subscribe({
            next: (d) => {
                this.detail.set(d);
                this.loading.set(false);
            },
            error: () => {
                this.loading.set(false);
            },
        });
    }

    onNeighbourClicked(row: MetadataNode): void {
        if (row) this.load(row.id);
    }

    pretty(v: unknown): string {
        try {
            return JSON.stringify(v, null, 2);
        } catch {
            return String(v);
        }
    }

    /** Table-kind nodes (a store) carry cross-engine lineage; SOURCE/SCHEMA/COLUMN/KPI/REPORT do not. */
    isStore(kind: string): boolean {
        return kind === 'TABLE' || kind === 'DERIVED_TABLE' || kind === 'REFERENCE_DATASET';
    }
}
