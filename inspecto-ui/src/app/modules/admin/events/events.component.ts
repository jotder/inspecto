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
    SessionService,
    SavedEventView,
    visibleInterval,
} from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
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
        InspectoAlertComponent,
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
    private session = inject(SessionService);

    /**
     * `bootstrap.features.events` — this whole screen is the optional `inspecto-events` module (EDITIONS
     * CP-13, EDG-01 cell 6). The nav entry is hidden when false, but a bookmark or a typed URL still lands
     * here, so the screen explains itself instead of toasting "Failed to load events" nine times.
     */
    readonly eventsEnabled = this.session.eventsEnabled;

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
    /** True while the live tail is served by the SSE stream rather than the poll fallback. */
    readonly streaming = signal(false);
    private liveSub?: Subscription;
    private streamSub?: Subscription;

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
        this.stopLiveTail();
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
        if (!this.eventsEnabled()) return;
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
        // ⚠ An absent optional module is an expected deployment state, not a failure: never call, never
        // toast. The template renders the explained alert in place of the grid.
        if (!this.eventsEnabled()) {
            this.loading.set(false);
            return;
        }
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

    /**
     * Re-arm the live tail — called on toggle and when the cadence select changes.
     *
     * Prefers the **server-sent signals stream** (`GET /signals/stream`) and falls back to the
     * visibility-aware poll. The stream has no historical replay, so the current query is re-run first and
     * each frame is then prepended, keeping the newest-first order the grid already assumes.
     *
     * ⚠ **A stream error falls back to polling and must NOT reach the connectivity banner** — only a
     * status-0 HTTP failure means "backend down", and a dropped SSE connection does not. The fallback is
     * why a buffering proxy, or any environment without `EventSource`, still gets a live tail.
     */
    restartLiveTail(): void {
        this.stopLiveTail();
        if (!this.live()) return;
        // ⚠ An absent events module is an expected deployment state, and this pane's rule is "never call".
        // The toggle is in the header, OUTSIDE the region the explained alert replaces, so an operator can
        // still flip it — without this gate the pane would open a stream whose rows it cannot display.
        // Measured against a running backend whose `/bootstrap` reports `features.events: false` while
        // `GET /signals/stream` answers 200.
        if (!this.eventsEnabled()) return;
        // No replay on the stream: re-run the query, then tail forward from now.
        this.load(true);
        // ⚠ Set the flag BEFORE subscribing: a stream that fails synchronously — which is exactly what
        // an environment without `EventSource` does — runs the error handler during `subscribe()`, so
        // setting it afterwards would overwrite the handler's `false` and the pane would report
        // "streaming" while it was really polling.
        this.streaming.set(true);
        this.streamSub = this.api.stream({ correlationId: this.fCorrelation() || undefined }).subscribe({
            next: (row) => this.appendStreamed(row),
            error: () => {
                this.streamSub = undefined;
                this.streaming.set(false);
                this.startPolling();
            },
        });
    }

    /** Tear down whichever live-tail transport is armed. */
    private stopLiveTail(): void {
        this.streamSub?.unsubscribe();
        this.streamSub = undefined;
        this.liveSub?.unsubscribe();
        this.liveSub = undefined;
        this.streaming.set(false);
    }

    /** The fallback transport: the pre-existing visibility-aware poll at the selected cadence. */
    private startPolling(): void {
        this.liveSub?.unsubscribe();
        this.liveSub = visibleInterval(this.liveSeconds * 1000).subscribe(() => this.load(true));
    }

    /**
     * Prepend one streamed row, newest-first, and hold the grid at the operator's page size so a busy
     * stream cannot grow the table without bound.
     *
     * ⚠ It respects the **minimum-level** filter client-side, because the stream filters on `severity`
     * server-side and the toolbar's control is `level` — projecting the frame and then dropping it here
     * keeps one definition of the ladder (`EVENT_LEVELS` order) instead of mirroring it into a second
     * severity mapping. A row already present is ignored, so a re-run overlapping the tail cannot double.
     */
    private appendStreamed(row: EventRow): void {
        if (!this.matchesLevel(row)) return;
        const rows = this.events();
        if (rows.some((r) => r.eventId === row.eventId)) return;
        this.events.set([row, ...rows].slice(0, this.fLimit));
    }

    /** True when the row is at or above the toolbar's minimum level (blank = no minimum). */
    private matchesLevel(row: EventRow): boolean {
        if (!this.fLevel) return true;
        const order = EVENT_LEVELS as readonly string[];
        const min = order.indexOf(this.fLevel);
        const at = order.indexOf(row.level);
        return min < 0 || at < 0 || at >= min;
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
        // ⚠ Same rule as `load()`: an absent events module is an expected deployment state, so never call.
        // Found 2026-09-10 while verifying the live tail in the preview — this was the ONE call in the pane
        // that still fired with the module absent, producing two 503 console errors on every visit while
        // the screen itself was correctly explaining the absence.
        if (!this.eventsEnabled()) return;
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
