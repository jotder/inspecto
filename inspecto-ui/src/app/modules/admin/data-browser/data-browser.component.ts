import { computed, ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { FormsModule } from '@angular/forms';
import { ColDef } from 'ag-grid-community';

import { apiErrorMessage, DbBrowserService, DbGroup, DbResult, DbTable } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { InspectoSplitDirective } from 'app/inspecto/components/split.directive';
import { DataTableComponent } from 'app/inspecto/data-table';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';

/**
 * Data Browser — a per-space "database client": browse the raw stores (business Parquet/CSV + operational
 * DB tables) and their rows. The Pro {@link DataTableComponent}'s own toggleable SQL editor drives both a
 * client-side Run (AlaSQL over the loaded page) and "Run on server" ({@code (runOnServer)} → {@code /db/query}
 * over the full dataset). Backed by {@code /db/*} ({@link DbBrowserService}); the active space is applied by
 * the global space interceptor. Design: {@code docs/superpower/db-browser-design.md}.
 */
@Component({
    selector: 'app-data-browser',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        InspectoPageHeaderComponent,
        DataTableComponent,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        InspectoSkeletonComponent,
        InspectoSplitDirective,
        MatButtonModule,
        MatFormFieldModule,
        MatInputModule,
        MatIconModule,
        FormsModule,
    ],
    templateUrl: './data-browser.component.html',
})
export class DataBrowserComponent {
    private api = inject(DbBrowserService);
    private destroyRef = inject(DestroyRef);

    private static readonly PAGE = 200;
    /** Widened by Load more (R6a); reset to PAGE whenever a different table is selected. */
    readonly limit = signal(DataBrowserComponent.PAGE);
    /** The last "Run on server" SQL, so Load more re-runs the right query at the wider limit. */
    private lastSql: string | null = null;

    readonly groups = signal<DbGroup[]>([]);
    /** Type-to-narrow over the store names (UIB-12: 30+ stores ran off the bottom of the page with no filter). */
    readonly storeFilter = signal('');
    readonly filteredGroups = computed<DbGroup[]>(() => {
        const q = this.storeFilter().trim().toLowerCase();
        if (!q) return this.groups();
        return this.groups()
            .map((g) => ({ ...g, tables: g.tables.filter((t) => t.name.toLowerCase().includes(q)) }))
            .filter((g) => g.tables.length > 0);
    });
    readonly storeCount = computed(() => this.groups().reduce((n, g) => n + g.tables.length, 0));
    readonly shownStoreCount = computed(() => this.filteredGroups().reduce((n, g) => n + g.tables.length, 0));
    readonly loadingCatalog = signal(true);
    readonly catalogError = signal<string | null>(null);

    readonly selected = signal<{ group: string; table: DbTable } | null>(null);
    readonly columns = signal<ColDef[]>([]);
    readonly rows = signal<Record<string, unknown>[]>([]);
    readonly stats = signal<DbResult['statistics'] | null>(null);
    readonly loadingRows = signal(false);
    readonly rowsError = signal<string | null>(null);

    constructor() {
        this.loadCatalog();
    }

    loadCatalog(): void {
        this.loadingCatalog.set(true);
        this.catalogError.set(null);
        this.api
            .catalog()
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (c) => {
                    this.groups.set(c.groups ?? []);
                    this.loadingCatalog.set(false);
                },
                error: (err) => {
                    this.catalogError.set(apiErrorMessage(err, 'Could not load the catalog'));
                    this.loadingCatalog.set(false);
                },
            });
    }

    isSelected(groupId: string, table: string): boolean {
        const s = this.selected();
        return !!s && s.group === groupId && s.table.name === table;
    }

    select(group: DbGroup, table: DbTable): void {
        this.selected.set({ group: group.id, table });
        this.rowsError.set(null);
        this.limit.set(DataBrowserComponent.PAGE);
        this.lastSql = null;
        this.loadRows();
    }

    loadRows(): void {
        const s = this.selected();
        if (!s) return;
        this.loadingRows.set(true);
        this.rowsError.set(null);
        this.api
            .table({ group: s.group, name: s.table.name, limit: this.limit() })
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (r) => this.applyResult(r),
                error: (err) => this.onRowsError(err),
            });
    }

    /** "Run on server" from the data-table's SQL editor → full query via /db/query (replaces the loaded page). */
    onServerSql(sql: string): void {
        const s = this.selected();
        if (!s || !sql.trim()) return;
        this.lastSql = sql;
        this.loadingRows.set(true);
        this.rowsError.set(null);
        this.api
            .query({ group: s.group, table: s.table.name, sql, limit: this.limit() })
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (r) => this.applyResult(r),
                error: (err) => this.onRowsError(err),
            });
    }

    /** Widen the page and re-run the last read (table browse or server SQL) — the honest alternative
     *  to the previous static "(truncated)" text with no way to see more (R6a). */
    loadMore(): void {
        this.limit.update((n) => n + DataBrowserComponent.PAGE);
        if (this.lastSql) this.onServerSql(this.lastSql);
        else this.loadRows();
    }

    private applyResult(r: DbResult): void {
        this.columns.set((r.columns ?? []).map((c) => ({ field: c.name, headerName: c.name })));
        this.rows.set(r.rows ?? []);
        this.stats.set(r.statistics ?? null);
        this.loadingRows.set(false);
    }

    private onRowsError(err: unknown): void {
        this.columns.set([]);
        this.rows.set([]);
        this.stats.set(null);
        this.loadingRows.set(false);
        this.rowsError.set(apiErrorMessage(err, 'Query failed'));
    }
}
