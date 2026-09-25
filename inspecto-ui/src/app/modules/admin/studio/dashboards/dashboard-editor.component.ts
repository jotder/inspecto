import {
    ChangeDetectionStrategy,
    Component,
    ElementRef,
    Input,
    OnInit,
    computed,
    effect,
    inject,
    signal,
} from '@angular/core';
import { nextSpan, spanLabel, tileBasis } from 'app/inspecto/viz/dashboard-grid';
import { CdkDragDrop, DragDropModule, moveItemInArray } from '@angular/cdk/drag-drop';
import { FormBuilder, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ComponentsService, EventsService, PipelinesService } from 'app/inspecto/api';
import {
    CommitEvent,
    DISRUPTION_TYPES,
    DisruptionEvent,
    ProducingPipeline,
    StaleMark,
    staleWidgets,
} from 'app/inspecto/signal/stale-tiles';
import { ColumnMeta, ConditionGroup, QueryConditionGroupComponent } from 'app/inspecto/query';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { ComponentHistoryDialog } from 'app/inspecto/components/component-history.dialog';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import {
    BundleTransferService,
    ImportDraft,
    ImportDraftBannerComponent,
    ImportDraftHandoff,
    TransferMenuComponent,
    draftPlacement,
    draftSaveWarning,
} from 'app/inspecto/transfer';
import { DrillEvent } from '../widgets/widget-host.component';
import { Widget, WidgetOptions, buildWidget } from '../widgets/widget-types';
import { WidgetsService } from '../widgets/widgets.service';
import { ControlValues } from 'app/inspecto/viz';
import { AiAssistComponent } from 'app/inspecto/ai-assist/ai-assist.component';
import { AiDraft } from 'app/inspecto/ai-assist/ai-draft';
import { concatMap, from, map, of, tap } from 'rxjs';
import { Dataset } from '../datasets/dataset-types';
import { DatasetRowsService, RowSourceRef } from 'app/inspecto/viz/dataset-rows.service';
import { DashboardViewStore } from './dashboard-view.store';
import { Dashboard, DashboardTile, buildDashboard } from './dashboard-types';
import { DashboardsService } from './dashboards.service';
import { ShareDashboardDialog } from './share-dashboard.dialog';
import { DashboardTileComponent } from './dashboard-tile.component';
import { DashboardFilterBarComponent } from './dashboard-filter-bar.component';
import { DashboardDrillDrawerComponent } from './dashboard-drill-drawer.component';
import { uniqueNameValidator } from 'app/inspecto/investigation/unique-name';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';
import {
    InspectoOptionPickerComponent,
    PickerOption,
    pickerOptions,
} from 'app/inspecto/components/option-picker.component';
import '../widgets/widget.kind'; // register widget kind + viz plugins (tiles call getViz)
import './dashboard.kind'; // register the dashboard kind

/**
 * Dashboard editor — compose saved widgets into a grid. Add widget tiles, drag to reorder (CDK), toggle each
 * tile's width, and set a dashboard **cross-filter** (Query Core condition group over the union of the tiles'
 * dataset columns) that re-renders every tile live. Save persists a `dashboard` component. Mock-first.
 */
/**
 * How many of each event type the stale resolver reads. Bounded on purpose: the resolver compares only
 * the NEWEST disruption against the NEWEST commit per pipeline, so a longer history cannot change the
 * answer — it would only cost bandwidth.
 */
const STALE_EVENT_WINDOW = 200;

/** `SEQUENCE_GAP.attributes.stores` is comma-joined by the emitter; absent or blank ⇒ undefined (fall back to produces[]). */
function splitStores(v: string | undefined): string[] | undefined {
    const parts = (v ?? '')
        .split(',')
        .map((s) => s.trim())
        .filter(Boolean);
    return parts.length ? parts : undefined;
}

@Component({
    selector: 'app-dashboard-editor',
    standalone: true,
    imports: [
        InspectoOptionPickerComponent,
        InspectoPageHeaderComponent,
        DragDropModule,
        ReactiveFormsModule,
        FormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        MatTooltipModule,
        RouterLink,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        QueryConditionGroupComponent,
        DashboardTileComponent,
        DashboardFilterBarComponent,
        DashboardDrillDrawerComponent,
        TransferMenuComponent,
        ImportDraftBannerComponent,
        AiAssistComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    providers: [DashboardViewStore],
    templateUrl: './dashboard-editor.component.html',
})
export class DashboardEditorComponent implements OnInit {
    /** UIE-3: tile width on the four-column grid, and how it is said. */
    readonly tileBasis = tileBasis;
    readonly spanLabel = spanLabel;

    private fb = inject(FormBuilder);
    private dashboardsApi = inject(DashboardsService);
    private widgetsApi = inject(WidgetsService);
    /** The viewer-facing state (lookups, tiles, cross-filter, quick filters, drill) — shared with the Menu viewer. */
    private view = inject(DashboardViewStore);
    private datasetRows = inject(DatasetRowsService);
    private events = inject(EventsService);
    private pipelinesApi = inject(PipelinesService);
    private router = inject(Router);
    private elementRef = inject<ElementRef<HTMLElement>>(ElementRef);
    private toastr = inject(ToastrService);
    private dialog = inject(MatDialog);
    private components = inject(ComponentsService);
    private draftHandoff = inject(ImportDraftHandoff);
    private transfer = inject(BundleTransferService);

    /** Route param — the dashboard id to edit; absent on the `new` route. */
    @Input() id?: string;

    /** This saved dashboard as a transfer reference — export is offered only in edit mode. */
    get transferItems(): { kind: 'dashboard'; id: string }[] {
        return this.id ? [{ kind: 'dashboard', id: this.id }] : [];
    }

    /** Mint + show a public share link for this saved dashboard (BI-6). Edit mode only. */
    share(): void {
        if (!this.id) return;
        this.dialog.open(ShareDashboardDialog, { data: { id: this.id } });
    }

    /** Show version history for this saved dashboard; reload its state after a restore (MET-5). Edit mode only. */
    history(): void {
        if (!this.id) return;
        const id = this.id;
        this.dialog
            .open(ComponentHistoryDialog, { data: { type: 'dashboard', id, label: id } })
            .afterClosed()
            .subscribe((restored) => {
                if (restored) this.dashboardsApi.get(id).subscribe({ next: (d) => this.seed(d) });
            });
    }

    readonly widgets = this.view.widgets;
    readonly datasets = this.view.datasets;
    readonly widgetOptions = computed<PickerOption[]>(() =>
        this.widgets().map((w) => ({ value: w.id, label: w.name, hint: w.vizType })),
    );
    readonly datasetOptions = computed<PickerOption[]>(() => pickerOptions(this.datasets().map((d) => d.id)));
    /** Inputs to the stale resolver — see `staleByWidget`. Empty until the three reads land. */
    private readonly disruptions = signal<DisruptionEvent[]>([]);
    private readonly commits = signal<CommitEvent[]>([]);
    private readonly pipelines = signal<ProducingPipeline[]>([]);
    readonly tiles = this.view.tiles;
    readonly filter = this.view.filter;
    readonly exposedFields = this.view.exposedFields;
    /** Index of the tile whose underlying rows are open in the drill-through drawer (null = closed). */
    readonly drillTileIndex = signal<number | null>(null);
    readonly editing = signal(false);
    readonly saving = signal(false);
    readonly writesDisabled = signal(false);

    /** An imported draft this editor holds UNSAVED (Import as draft, D1–D8) — in memory only. */
    readonly importDraft = signal<ImportDraft | null>(null);
    /** The stored copy the draft replaces (D6 diff baseline); null when the id is new here. */
    readonly draftStored = signal<Record<string, unknown> | null>(null);
    /** The stored copy's hash, sent as `If-Match` on the draft's Save so it cannot clobber a concurrent edit. */
    private draftIfMatch: string | undefined;

    readonly form = this.fb.group({
        name: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/)]],
    });

    /**
     * `SIGNAL-STALE-TILES-1` — widget id → why its data is stale, resolved ONCE for the whole dashboard.
     *
     * ⚠ Resolved here rather than per tile on purpose: a 20-tile dashboard would otherwise make 20 round
     * trips to answer one question. The three feeds it needs are fetched once in `ngOnInit`; the chain
     * itself is pure and lives in `inspecto/signal/stale-tiles`.
     *
     * ⚠ Degrades to "nothing is stale" when any feed fails. That is the right failure direction for an
     * ADVISORY badge — an unreachable events feed must not paint a whole dashboard as suspect — and it is
     * why the fetches below swallow their errors instead of toasting.
     */
    readonly staleByWidget = computed(() =>
        staleWidgets(this.disruptions(), this.commits(), this.pipelines(), this.datasets(), this.widgets()),
    );

    /** The stale mark for one tile's widget, or null. */
    readonly staleOf = (tile: DashboardTile): StaleMark | null => this.staleByWidget().get(tile.widgetId) ?? null;

    /** Union of column metadata across the tiled widgets' datasets — the cross-filter's field choices. */
    readonly filterColumns = computed<ColumnMeta[]>(() => {
        const seen = new Map<string, ColumnMeta>();
        for (const tile of this.tiles()) {
            for (const col of this.datasetOf(tile)?.columns ?? []) {
                if (!seen.has(col.name)) seen.set(col.name, { name: col.name, type: col.type });
            }
        }
        return [...seen.values()];
    });

    /** Value suggestions per exposed field (see {@link DashboardViewStore.exposedValues}). */
    readonly exposedValues = this.view.exposedValues;

    /** The drill-through drawer's contents — the open tile's rows with the live cross-filter applied. */
    readonly drillView = signal<DrillView | null>(null);

    constructor() {
        // Drill-through: the open tile's rows. The cross-filter travels IN the request (composed with the
        // dataset's own model), so the server filters — a page filtered afterwards would be the wrong rows.
        effect(() => {
            const index = this.drillTileIndex();
            const tile = index == null ? undefined : this.tiles()[index];
            const dataset = tile ? this.datasetOf(tile) : undefined;
            const filter = this.filter();
            const title = tile ? (this.widgetOf(tile)?.name ?? dataset?.name ?? '') : '';
            if (!dataset) {
                this.drillView.set(null);
                return;
            }
            void this.loadDrillView(dataset, filter, title);
        });
    }

    private async loadDrillView(dataset: Dataset, filter: ConditionGroup, title: string): Promise<void> {
        const page = await this.datasetRows.rows(filtered(dataset, filter));
        this.drillView.set({
            title,
            sourceName: dataset.sourceName,
            rows: page.rows,
            truncated: page.truncated,
        });
    }

    widgetOf(tile: DashboardTile): Widget | undefined {
        return this.view.widgetOf(tile);
    }
    datasetOf(tile: DashboardTile): Dataset | undefined {
        return this.view.datasetOf(tile);
    }
    isViewBound(widget: Widget): boolean {
        return this.view.isViewBound(widget);
    }

    ngOnInit(): void {
        this.view.loadLookups(() => this.toastr.warning('Could not load widgets.'));
        this.loadStaleness();
        // A draft routed here from another editor (Import as draft). Taking it replaces the stored load,
        // so the stored copy can never land AFTER the draft and silently overwrite it.
        const pending = this.draftHandoff.take('dashboard', this.id);
        if (this.id) {
            this.editing.set(true);
            this.form.controls.name.setValue(this.id);
            this.form.controls.name.disable();
            if (pending) this.adoptDraft(pending);
            else
                this.dashboardsApi.get(this.id).subscribe({
                    next: (d) => this.seed(d),
                    error: (e) => this.toastr.error(apiErrorMessage(e, `Could not load dashboard "${this.id}"`)),
                });
        } else {
            if (pending) this.adoptDraft(pending);
            // Product-wide rule: block a duplicate id inline on create rather than relying on the server 409.
            this.dashboardsApi.list().subscribe((all) => {
                this.form.controls.name.addValidators(uniqueNameValidator(() => all.map((d) => d.id)));
                this.form.controls.name.updateValueAndValidity({ emitEvent: false });
            });
        }
    }

    /**
     * Fetch the three feeds the stale badge derives from (`SIGNAL-STALE-TILES-1`). Errors are swallowed:
     * the badge is advisory, and a dashboard whose events feed is briefly unreachable must render its
     * numbers rather than paint every tile as suspect. A bounded window is deliberate — the resolver only
     * ever compares the NEWEST disruption against the NEWEST commit per pipeline, so an unbounded history
     * would cost bandwidth for an answer it cannot change.
     */
    private loadStaleness(): void {
        for (const type of DISRUPTION_TYPES) {
            this.events.search({ type, limit: STALE_EVENT_WINDOW }).subscribe({
                // STALE-TILES-PRECISION-1: a SEQUENCE_GAP names the stores it reaches (comma-joined attr).
                next: (rows) =>
                    this.disruptions.update((all) => [
                        ...all,
                        ...rows.map((r) => ({ ...r, stores: splitStores(r.attributes?.['stores']) })),
                    ]),
                error: () => undefined,
            });
        }
        this.events.search({ type: 'BATCH_COMMITTED', limit: STALE_EVENT_WINDOW }).subscribe({
            next: (rows) => this.commits.update((all) => [...all, ...rows]),
            error: () => undefined,
        });
        // STALE-TILES-PRECISION-1: a `dataset.write` Signal is a commit at STORE granularity — its subject
        // names the store — so a pipeline that refreshed one of its stores clears only that one.
        this.events.signals({ type: 'dataset.write', limit: STALE_EVENT_WINDOW }).subscribe({
            next: (rows) =>
                this.commits.update((all) => [
                    ...all,
                    ...rows
                        .filter((r) => r.subjectRef?.kind === 'dataset' && !!r.subjectRef.id)
                        .map((r) => ({ pipeline: r.pipeline, ts: r.ts, stores: [r.subjectRef!.id] })),
                ]),
            error: () => undefined,
        });
        this.pipelinesApi.list().subscribe({ next: (p) => this.pipelines.set(p), error: () => undefined });
    }

    private seed(d: Dashboard): void {
        this.view.seed(d);
    }

    // ── Import as draft (operator decisions 2026-09-25) ──────────────────────────────────────────
    // The dialog imported any missing prerequisites write-through already (D4); the dashboard itself is
    // adopted UNSAVED and reaches the store only through this pane's own Save (D2).

    /** The transfer menu's draft: adopt it here, or route to the editor that must show it. */
    onDraftImported(draft: ImportDraft): void {
        if (draftPlacement(this.id, draft) === 'here') {
            this.adoptDraft(draft);
            return;
        }
        this.draftHandoff.open(
            draft,
            ['/studio/dashboards', draft.targetExists ? draft.id : 'new'],
            '/studio/dashboards',
            !!this.id,
        );
    }

    /** Take the incoming content as unsaved edits; for an existing id, read the stored copy first (D6). */
    adoptDraft(draft: ImportDraft): void {
        const apply = () => {
            // Re-list: the prerequisite import may just have created widgets these tiles reference.
            this.widgetsApi.list().subscribe({ next: (w) => this.widgets.set(w), error: () => undefined });
            this.seed(this.dashboardsApi.fromContent(draft.id, draft.content));
            if (!this.editing()) this.form.controls.name.setValue(draft.id);
            this.importDraft.set(draft);
        };
        if (!draft.targetExists) {
            this.draftStored.set(null);
            this.draftIfMatch = undefined;
            apply();
            return;
        }
        this.components.get('dashboard', draft.id).subscribe({
            next: (def) => {
                this.draftStored.set(def.content);
                this.draftIfMatch = def.contentHash;
                apply();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, `Could not load dashboard "${draft.id}"`)),
        });
    }

    /** Drop the draft: back to the stored dashboard, or out of a create that only the draft started. */
    discardDraft(): void {
        this.importDraft.set(null);
        this.draftStored.set(null);
        this.draftIfMatch = undefined;
        if (!this.id) {
            this.router.navigate(['/studio/dashboards']);
            return;
        }
        this.dashboardsApi.get(this.id).subscribe({ next: (d) => this.seed(d) });
    }

    // ── AGT-6a: kpi_report_builder host ──────────────────────────────────────────
    // This is the tool's host pane because it is the only one that can perform the multi-component
    // write the tool implies (N widgets THEN the dashboard that tiles them) and end in a reviewable,
    // not-yet-saved state. `studio/widgets/explore` builds measures but is a single-widget editor by
    // construction — one `save()` that then routes away. Here the tool builds the measures instead,
    // so the pane only has to supply a dataset.

    /** The dataset the drafted report reads — the one thing the tool needs that this pane doesn't already know. */
    readonly kpiDataset = signal('');

    /**
     * Pane args for the assist panel. ⚠ Identity/context ONLY: pane args are applied AFTER the model's
     * and win, so anything derivable from the sentence (measures, groupBy, filter) must NOT appear here
     * or the derived value is silently overwritten and the feature no-ops while looking like it worked.
     */
    readonly aiKpiArgs = computed(() => {
        const title = String(this.form.controls.name.value ?? '').trim();
        return { dataset: this.kpiDataset(), ...(title ? { title } : {}) };
    });

    /**
     * Apply a `kpi_report_builder` draft: create each prerequisite widget, then tile them here and leave
     * the dashboard UNSAVED so the human still presses Save (the draft-only invariant — the pane writes
     * through its own validated routes, and the operator remains the audited actor).
     *
     * ⚠ The widget creates are N separate non-atomic POSTs against single-component routes; no batch
     * route exists. On a partial failure we STOP, keep the already-created widgets, and do **not** tile —
     * a dashboard referencing widgets that were never created renders as broken tiles, which is worse
     * than no dashboard. The orphans are named in the error and are visible in the widget library, so the
     * operator can delete or reuse them. Deliberately NOT compensated by deleting them: an id collision
     * means the "orphan" may be a pre-existing widget someone else owns.
     */
    applyKpiReport(draft: AiDraft): void {
        const tiles = Array.isArray(draft.config['tiles']) ? (draft.config['tiles'] as DashboardTile[]) : [];
        const widgets = (draft.prerequisites ?? []).map((p) => {
            const c = p.config;
            return buildWidget(
                p.label,
                String(c['datasetId'] ?? this.kpiDataset()),
                String(c['vizType'] ?? 'kpi'),
                (c['controls'] ?? {}) as ControlValues,
                { options: c['options'] as WidgetOptions | undefined },
            );
        });
        if (!widgets.length || !tiles.length) {
            this.toastr.warning('That draft carried no widgets to place.');
            return;
        }
        const created: string[] = [];
        this.saving.set(true);
        from(widgets)
            .pipe(concatMap((w) => this.widgetsApi.save(w, { update: false }).pipe(tap(() => created.push(w.id)))))
            .subscribe({
                complete: () => {
                    this.saving.set(false);
                    // Re-list so the new widgets resolve through `widgetsById` and the tiles can render.
                    this.widgetsApi.list().subscribe({
                        next: (w) => {
                            this.widgets.set(w);
                            this.tiles.set(tiles);
                            if (draft.config['filter']) this.filter.set(draft.config['filter'] as ConditionGroup);
                            this.toastr.success(
                                `Created ${created.length} widget${created.length === 1 ? '' : 's'} and laid out the dashboard — review it, then Save.`,
                            );
                        },
                        error: () =>
                            this.toastr.warning('Widgets were created but could not be reloaded; refresh the page.'),
                    });
                },
                error: (e) => {
                    this.saving.set(false);
                    if (e?.status === 503) this.writesDisabled.set(true);
                    const done = created.length ? ` Already created: ${created.join(', ')}.` : '';
                    this.toastr.error(
                        `${apiErrorMessage(e, 'Could not create the report widgets')}. The dashboard was NOT laid out.${done}`,
                    );
                },
            });
    }

    addWidget(widgetId: string): void {
        if (!widgetId) return;
        this.tiles.update((t) => [...t, { widgetId, span: 2 }]);
    }
    removeTile(index: number): void {
        this.tiles.update((t) => t.filter((_, i) => i !== index));
    }
    toggleSpan(index: number): void {
        this.tiles.update((t) => t.map((tile, i) => (i === index ? { ...tile, span: nextSpan(tile.span) } : tile)));
    }
    drop(event: CdkDragDrop<DashboardTile[]>): void {
        this.tiles.update((t) => {
            const next = [...t];
            moveItemInArray(next, event.previousIndex, event.currentIndex);
            return next;
        });
    }

    /** New root ref so the tiles' `filter` input changes and they re-query. */
    onFilterChanged(): void {
        this.filter.update((f) => ({ ...f }));
    }

    /** A tile's drill-down click — toggle `field = value` in the cross-filter (the shared viewer rule). */
    onDrill(event: DrillEvent): void {
        this.view.onDrill(event);
    }

    /** Download every rendered tile canvas as a PNG — the offline "export dashboard" (chart tiles only;
     *  table/KPI tiles have no canvas and export via their own surfaces). */
    exportPngs(): void {
        const canvases: NodeListOf<HTMLCanvasElement> =
            this.elementRef.nativeElement.querySelectorAll('app-dashboard-tile canvas');
        if (!canvases.length) {
            this.toastr.info('No chart tiles to export.');
            return;
        }
        const name = String(this.form.controls.name.value ?? 'dashboard');
        canvases.forEach((canvas, i) => {
            const link = document.createElement('a');
            link.href = canvas.toDataURL('image/png');
            link.download = `${name}-tile-${i + 1}.png`;
            link.click();
        });
    }

    save(): void {
        const ctrl = this.form.controls.name;
        const name = String(ctrl.value ?? '').trim() || (this.id ?? '');
        if (!name || (ctrl.enabled && ctrl.invalid)) {
            this.form.markAllAsTouched();
            return;
        }
        if (!this.tiles().length) {
            this.toastr.warning('Add at least one widget.');
            return;
        }
        const dashboard = buildDashboard(name, this.tiles(), this.filter(), this.exposedFields());
        this.saving.set(true);
        const draft = this.importDraft();
        const ifMatch = draft ? this.draftIfMatch : undefined;
        // An imported draft's references are re-checked against what is about to be WRITTEN (D3) — advisory:
        // the findings go to the banner and the Save goes ahead either way.
        const recheck$ = draft
            ? this.transfer
                  .draftIntegrity('dashboard', name, this.dashboardsApi.toContent(dashboard))
                  .pipe(tap((integrity) => this.importDraft.set({ ...draft, integrity })))
            : of(null);
        recheck$
            .pipe(
                concatMap((integrity) =>
                    this.dashboardsApi
                        .save(dashboard, { update: this.editing(), ...(ifMatch ? { ifMatch } : {}) })
                        .pipe(map(() => integrity)),
                ),
            )
            .subscribe({
                next: (integrity) => {
                    this.saving.set(false);
                    const warning = draft ? draftSaveWarning(name, integrity) : null;
                    if (warning) this.toastr.warning(warning);
                    else this.toastr.success(`Dashboard "${name}" saved`);
                    this.router.navigate(['/studio/dashboards']);
                },
                error: (e) => {
                    this.saving.set(false);
                    if (e?.status === 503) this.writesDisabled.set(true);
                    this.toastr.error(
                        e?.status === 503 ? 'Writes are disabled.' : apiErrorMessage(e, `Could not save "${name}"`),
                    );
                },
            });
    }
}

/** What the drill-through drawer renders — a PAGE of the tile's rows, and whether the store held more. */
interface DrillView {
    title: string;
    sourceName: string;
    rows: Record<string, unknown>[];
    truncated: boolean;
}

/**
 * The dataset as the drill-through wants to read it: its own model AND the dashboard's cross-filter, so
 * the filter is applied where the rows are (server-side live), not to a page that was already cut.
 */
function filtered(ds: Dataset, filter: ConditionGroup): RowSourceRef {
    const own = ds.query?.where;
    const where: ConditionGroup = own ? { kind: 'group', op: 'AND', items: [own, filter] } : filter;
    return {
        sourceName: ds.sourceName,
        columns: ds.columns,
        query: { projection: ds.query?.projection ?? '*', where },
    };
}
