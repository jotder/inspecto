import {
    ChangeDetectionStrategy,
    Component,
    inject,
    OnDestroy,
    OnInit,
    signal,
    ViewEncapsulation,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ColDef } from 'ag-grid-community';
import { Subscription } from 'rxjs';
import {
    EVENT_LEVELS,
    EVENT_TYPES,
    EventFilter,
    EventRow,
    EventsService,
    SavedEventView,
    visibleInterval,
} from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime, InspectoRowAction } from 'app/inspecto/grid';
import { ToastrService } from 'ngx-toastr';
import { AiStatusData, AiStatusDialog } from 'app/inspecto/ai-assist/ai-status.dialog';
import { EventDetailDialog, EventDrilldown } from './event-detail.dialog';

/** Selectable live-tail poll cadences (seconds) — polling pauses while the tab is hidden via {@link visibleInterval}. */
const LIVE_TAIL_SECONDS = [2, 5, 10, 30, 60] as const;

/**
 * Events / Activity — the Operational Intelligence event stream (GET /events/search), newest-first. A
 * filter toolbar (minimum level, type, pipeline, free-text) drives the query; a live-tail toggle polls
 * while the tab is visible; matching rows export to CSV (GET /events/export); and operator-saved views
 * persist filter sets (GET/POST /events/views). Row detail opens the full event + attributes, from which
 * an operator can drill into a correlation id or event type.
 */
@Component({
    selector: 'app-events',
    standalone: true,
    imports: [
        FormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatMenuModule,
        MatSelectModule,
        MatSlideToggleModule,
        MatTooltipModule,
        ChipComponent,
        InspectoEmptyStateComponent,
        DataTableComponent,
    ],
    templateUrl: './events.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
    encapsulation: ViewEncapsulation.None,
})
export class EventsComponent implements OnInit, OnDestroy {
    private api = inject(EventsService);
    private dialog = inject(MatDialog);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);

    readonly levels = EVENT_LEVELS;
    readonly types = EVENT_TYPES;
    readonly limitOptions = [50, 100, 250, 500, 1000];

    readonly events = signal<EventRow[]>([]);
    readonly loading = signal(false);
    /** True when the last fetched page came back full — there may be more (R6). */
    readonly hasMore = signal(false);
    readonly live = signal(false);
    /** Live-tail cadence in seconds (operator-selectable); the toggle uses whatever is chosen here. */
    readonly liveSecondsOptions = LIVE_TAIL_SECONDS;
    liveSeconds: number = 5;
    private liveSub?: Subscription;

    // ── filter toolbar ─────────────────────────────────────────────────────────
    fLevel = '';
    /** Signal, not a plain field: openDetail()'s afterClosed() callback pivots it by type, which
     * zonelessly left the <mat-select> showing the OLD value while the list filtered by the new. */
    readonly fType = signal('');
    fPipeline = '';
    fq = '';
    fLimit = 100;
    /** Set only via the detail dialog "View related" drill-down; surfaced as a removable chip. */
    readonly fCorrelation = signal('');

    // ── saved views ────────────────────────────────────────────────────────────
    readonly views = signal<SavedEventView[]>([]);
    readonly selectedView = signal('');
    saveName = '';

    readonly columnDefs: ColDef<EventRow>[] = [
        {
            headerName: 'Time',
            width: 180,
            valueGetter: (p) => p.data?.ts,
            valueFormatter: (p) => fmtDateTime(p.value),
        },
        {
            field: 'severity',
            headerName: 'Severity',
            width: 110,
            // The signal's severity (`critical` surfaces distinctly); falls back to the legacy level. A
            // valueFormatter (not a badge cellRenderer) — the pro-tier grid renders formatters reliably.
            valueFormatter: (p) => String(p.value ?? p.data?.level ?? '').toUpperCase(),
        },
        { field: 'type', headerName: 'Type', width: 180 },
        {
            field: 'pipeline',
            headerName: 'Pipeline',
            width: 140,
            valueFormatter: (p) => p.value ?? '—',
        },
        {
            field: 'correlationId',
            headerName: 'Correlation',
            width: 150,
            valueFormatter: (p) => p.value ?? '—',
        },
        // Since R4 this is the signal's emitting producer (`<kind>/<id>`), e.g. pipeline/cdr_ingest, alert-rule/high_error_rate.
        { field: 'source', headerName: 'Source', flex: 1, minWidth: 160 },
        { field: 'message', headerName: 'Message', flex: 2, minWidth: 220 },
    ];

    readonly actions: InspectoRowAction<EventRow>[] = [
        {
            icon: 'heroicons_outline:information-circle',
            hint: 'Details',
            onClick: (e) => this.openDetail(e),
        },
        {
            // "Why is this red" (AGT-6a A4-status). A signal is the one row that usually carries a
            // `correlationId`, so this is the pane where the dialog answers with the exact causal chain
            // rather than a window — the whole point of the chain path. Hidden on a row that has
            // neither a correlation id nor a pipeline: there would be nothing to address.
            icon: 'heroicons_outline:link',
            hint: 'What led to this',
            visible: (e) => !!(e.correlationId || e.pipeline),
            onClick: (e) =>
                this.dialog.open(AiStatusDialog, {
                    data: {
                        label: e.correlationId ?? e.pipeline ?? e.type,
                        pipelineId: e.pipeline ?? undefined,
                        correlationId: e.correlationId ?? undefined,
                    } satisfies AiStatusData,
                }),
        },
    ];

    ngOnInit(): void {
        this.load();
        this.loadViews();
    }

    ngOnDestroy(): void {
        this.liveSub?.unsubscribe();
    }

    private buildFilter(): EventFilter {
        return {
            level: this.fLevel || undefined,
            type: this.fType() || undefined,
            pipeline: this.fPipeline.trim() || undefined,
            correlationId: this.fCorrelation() || undefined,
            q: this.fq.trim() || undefined,
            limit: this.fLimit,
        };
    }

    /** Fetch the NEXT offset page and append — true offset paging (R6; no refetch from 0).
     *  Any full refetch (filter change, refresh, live-tail tick) resets back to page 0. */
    loadMore(): void {
        this.loading.set(true);
        this.api.search({ ...this.buildFilter(), offset: this.events().length }).subscribe({
            next: (rows) => {
                this.events.set([...this.events(), ...rows]);
                this.hasMore.set(rows.length >= this.fLimit);
                this.loading.set(false);
            },
            error: () => {
                this.loading.set(false);
                this.toastr.error('Failed to load more events');
            },
        });
    }

    /** Run the current query. `silent` (live-tail tick) keeps the grid visible instead of flashing the loader. */
    load(silent = false): void {
        if (!silent) this.loading.set(true);
        this.api.search(this.buildFilter()).subscribe({
            next: (rows) => {
                this.events.set(rows);
                this.hasMore.set(rows.length >= this.fLimit);
                this.loading.set(false);
            },
            error: () => {
                this.loading.set(false);
                if (!silent) {
                    this.events.set([]);
                    this.hasMore.set(false);
                    this.toastr.error('Failed to load events');
                }
            },
        });
    }

    resetFilters(): void {
        this.fLevel = '';
        this.fType.set('');
        this.fPipeline = '';
        this.fq = '';
        this.fLimit = 100;
        this.fCorrelation.set('');
        this.selectedView.set('');
        this.load();
    }

    clearCorrelation(): void {
        this.fCorrelation.set('');
        this.load();
    }

    toggleLive(on: boolean): void {
        this.live.set(on);
        this.restartLiveTail();
    }

    /** Re-arm the poll at the current cadence — called on toggle and when the cadence select changes. */
    restartLiveTail(): void {
        this.liveSub?.unsubscribe();
        this.liveSub = undefined;
        if (this.live()) this.liveSub = visibleInterval(this.liveSeconds * 1000).subscribe(() => this.load(true));
    }

    openDetail(row: EventRow): void {
        this.dialog
            .open(EventDetailDialog, { data: row, width: '640px', maxHeight: '85vh' })
            .afterClosed()
            .subscribe((d?: EventDrilldown) => {
                if (!d) return;
                if (d.correlationId) this.fCorrelation.set(d.correlationId);
                if (d.type) this.fType.set(d.type);
                this.load();
            });
    }

    exportCsv(): void {
        this.api.exportCsv(this.buildFilter()).subscribe({
            next: (csv) => {
                const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = `events-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.csv`;
                a.click();
                URL.revokeObjectURL(url);
            },
            error: () => this.toastr.error('Export failed'),
        });
    }

    // ── saved views ────────────────────────────────────────────────────────────

    private loadViews(): void {
        this.api.views().subscribe({
            next: (v) => this.views.set(v),
            error: () => this.views.set([]),
        });
    }

    applyView(name: string): void {
        this.selectedView.set(name);
        const v = this.views().find((x) => x.name === name);
        if (!v) return;
        const f = v.filters ?? {};
        this.fLevel = f['level'] ?? '';
        this.fType.set(f['type'] ?? '');
        this.fPipeline = f['pipeline'] ?? '';
        this.fCorrelation.set(f['correlationId'] ?? '');
        this.fq = f['q'] ?? '';
        this.load();
    }

    saveView(): void {
        const name = this.saveName.trim();
        if (!name) {
            this.toastr.error('Enter a view name');
            return;
        }
        const filters: Record<string, string> = {};
        for (const [k, v] of Object.entries(this.buildFilter())) {
            if (v !== undefined && k !== 'limit' && k !== 'offset') filters[k] = String(v);
        }
        this.api.saveView(name, filters).subscribe({
            next: () => {
                this.toastr.success(`Saved view "${name}"`);
                this.saveName = '';
                this.selectedView.set(name);
                this.loadViews();
            },
            error: () => this.toastr.error('Save failed'),
        });
    }

    async deleteView(): Promise<void> {
        if (!this.selectedView()) {
            this.toastr.error('Select a saved view first');
            return;
        }
        const name = this.selectedView();
        if (
            !(await this.confirm.confirmDestructive(`Delete saved view "${name}"?`, {
                title: 'Delete view',
            }))
        )
            return;
        this.api.deleteView(name).subscribe({
            next: () => {
                this.toastr.success(`Deleted "${name}"`);
                this.selectedView.set('');
                this.loadViews();
            },
            error: () => this.toastr.error('Delete failed'),
        });
    }
}
