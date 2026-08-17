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
import { CdkDragDrop, DragDropModule, moveItemInArray } from '@angular/cdk/drag-drop';
import {
    AbstractControl,
    FormBuilder,
    FormsModule,
    ReactiveFormsModule,
    ValidatorFn,
    Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage } from 'app/inspecto/api';
import { getViz } from 'app/inspecto/viz';
import { Condition, ColumnMeta, ConditionGroup, QueryConditionGroupComponent, emptyGroup } from 'app/inspecto/query';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { ComponentHistoryDialog } from 'app/inspecto/components/component-history.dialog';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { TransferMenuComponent } from 'app/inspecto/transfer';
import { DrillEvent } from '../widgets/widget-host.component';
import { Widget, WidgetOptions, buildWidget } from '../widgets/widget-types';
import { WidgetsService } from '../widgets/widgets.service';
import { ControlValues } from 'app/inspecto/viz';
import { AiAssistComponent } from 'app/inspecto/ai-assist/ai-assist.component';
import { AiDraft } from 'app/inspecto/ai-assist/ai-draft';
import { concatMap, from, tap } from 'rxjs';
import { Dataset } from '../datasets/dataset-types';
import { DatasetsService } from '../datasets/datasets.service';
import { DatasetRowsService, RowSourceRef } from 'app/inspecto/viz/dataset-rows.service';
import { Dashboard, DashboardTile, buildDashboard } from './dashboard-types';
import { DashboardsService } from './dashboards.service';
import { ShareDashboardDialog } from './share-dashboard.dialog';
import { DashboardTileComponent } from './dashboard-tile.component';
import { DashboardFilterBarComponent } from './dashboard-filter-bar.component';
import { DashboardDrillDrawerComponent } from './dashboard-drill-drawer.component';
import { uniqueNameValidator } from 'app/inspecto/investigation/unique-name';
import '../widgets/widget.kind'; // register widget kind + viz plugins (tiles call getViz)
import './dashboard.kind'; // register the dashboard kind

/**
 * Dashboard editor — compose saved widgets into a grid. Add widget tiles, drag to reorder (CDK), toggle each
 * tile's width, and set a dashboard **cross-filter** (Query Core condition group over the union of the tiles'
 * dataset columns) that re-renders every tile live. Save persists a `dashboard` component. Mock-first.
 */
@Component({
    selector: 'app-dashboard-editor',
    standalone: true,
    imports: [
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
        AiAssistComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './dashboard-editor.component.html',
})
export class DashboardEditorComponent implements OnInit {
    private fb = inject(FormBuilder);
    private dashboardsApi = inject(DashboardsService);
    private widgetsApi = inject(WidgetsService);
    private datasetsApi = inject(DatasetsService);
    private datasetRows = inject(DatasetRowsService);
    private router = inject(Router);
    private elementRef = inject<ElementRef<HTMLElement>>(ElementRef);
    private toastr = inject(ToastrService);
    private dialog = inject(MatDialog);

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

    readonly widgets = signal<Widget[]>([]);
    readonly datasets = signal<Dataset[]>([]);
    readonly tiles = signal<DashboardTile[]>([]);
    readonly filter = signal<ConditionGroup>(emptyGroup('AND'));
    readonly exposedFields = signal<string[]>([]);
    /** Index of the tile whose underlying rows are open in the drill-through drawer (null = closed). */
    readonly drillTileIndex = signal<number | null>(null);
    readonly editing = signal(false);
    readonly saving = signal(false);
    readonly writesDisabled = signal(false);

    readonly form = this.fb.group({
        name: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/)]],
    });

    private readonly widgetsById = computed(() => new Map(this.widgets().map((w) => [w.id, w])));
    private readonly datasetsById = computed(() => new Map(this.datasets().map((d) => [d.id, d])));

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

    /** Value suggestions per exposed field — the quick-filter pickers' choices, read off one PAGE of each
     *  tiled dataset (so they are offers, not the column's full domain) and capped so a high-cardinality
     *  column doesn't flood the select. */
    readonly exposedValues = signal<Record<string, string[]>>({});

    /** The drill-through drawer's contents — the open tile's rows with the live cross-filter applied. */
    readonly drillView = signal<DrillView | null>(null);

    constructor() {
        // Value suggestions: one page per distinct tiled store, re-read when the tiles or the exposed
        // field list change. The pickers offer what the page holds — never a claim about the column.
        effect(() => {
            const exposed = this.exposedFields();
            const sources = new Map<string, Dataset>();
            for (const tile of this.tiles()) {
                const ds = this.datasetOf(tile);
                if (ds && !sources.has(ds.sourceName)) sources.set(ds.sourceName, ds);
            }
            if (!exposed.length || !sources.size) {
                this.exposedValues.set({});
                return;
            }
            void this.loadExposedValues(exposed, [...sources.values()]);
        });

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

    private async loadExposedValues(exposed: string[], datasets: Dataset[]): Promise<void> {
        const out: Record<string, Set<string>> = Object.fromEntries(exposed.map((f) => [f, new Set<string>()]));
        for (const ds of datasets) {
            const page = await this.datasetRows.rows(ds);
            for (const row of page.rows) {
                for (const f of exposed) {
                    const v = row[f];
                    if (v != null && out[f].size < 20) out[f].add(String(v));
                }
            }
        }
        this.exposedValues.set(Object.fromEntries(Object.entries(out).map(([f, set]) => [f, [...set].sort()])));
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
        return this.widgetsById().get(tile.widgetId);
    }
    datasetOf(tile: DashboardTile): Dataset | undefined {
        const widget = this.widgetOf(tile);
        return widget ? this.datasetsById().get(widget.datasetId) : undefined;
    }
    /** View-bound widget (geo-map / link-analysis) — no dataset; the cross-filter/drill don't apply. */
    isViewBound(widget: Widget): boolean {
        return !!getViz(widget.vizType)?.meta.viewKind;
    }

    ngOnInit(): void {
        this.widgetsApi.list().subscribe({
            next: (w) => this.widgets.set(w),
            error: () => this.toastr.warning('Could not load widgets.'),
        });
        this.datasetsApi.list().subscribe({ next: (d) => this.datasets.set(d), error: () => undefined });
        if (this.id) {
            this.editing.set(true);
            this.form.controls.name.setValue(this.id);
            this.form.controls.name.disable();
            this.dashboardsApi.get(this.id).subscribe({
                next: (d) => this.seed(d),
                error: (e) => this.toastr.error(apiErrorMessage(e, `Could not load dashboard "${this.id}"`)),
            });
        } else {
            // Product-wide rule: block a duplicate id inline on create rather than relying on the server 409.
            this.dashboardsApi.list().subscribe((all) => {
                this.form.controls.name.addValidators(uniqueNameValidator(() => all.map((d) => d.id)));
                this.form.controls.name.updateValueAndValidity({ emitEvent: false });
            });
        }
    }

    private seed(d: Dashboard): void {
        this.tiles.set(d.tiles);
        this.filter.set(d.filter ?? emptyGroup('AND'));
        this.exposedFields.set(d.exposedFields ?? []);
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
        this.tiles.update((t) => [...t, { widgetId, span: 1 }]);
    }
    removeTile(index: number): void {
        this.tiles.update((t) => t.filter((_, i) => i !== index));
    }
    toggleSpan(index: number): void {
        this.tiles.update((t) => t.map((tile, i) => (i === index ? { ...tile, span: tile.span === 1 ? 2 : 1 } : tile)));
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

    /** A tile's drill-down click — toggle `field = value` in the cross-filter: add it if absent, remove it
     *  if the same value is clicked again. */
    onDrill({ field, value }: DrillEvent): void {
        const current = this.filter();
        const matches = (item: Condition | ConditionGroup): boolean =>
            item.kind === 'condition' && item.field === field && item.operator === '=' && item.value === value;
        const idx = current.items.findIndex(matches);
        const items =
            idx >= 0
                ? current.items.filter((_, i) => i !== idx)
                : [...current.items, { kind: 'condition', field, operator: '=', value } as Condition];
        this.filter.set({ ...current, items });
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
        this.dashboardsApi.save(dashboard, { update: this.editing() }).subscribe({
            next: () => {
                this.saving.set(false);
                this.toastr.success(`Dashboard "${name}" saved`);
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
