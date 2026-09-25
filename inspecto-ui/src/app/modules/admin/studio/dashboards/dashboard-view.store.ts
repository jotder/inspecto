import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { getViz } from 'app/inspecto/viz';
import { Condition, ConditionGroup, emptyGroup } from 'app/inspecto/query';
import { DatasetRowsService } from 'app/inspecto/viz/dataset-rows.service';
import { DrillEvent } from '../widgets/widget-host.component';
import { Widget } from '../widgets/widget-types';
import { WidgetsService } from '../widgets/widgets.service';
import { Dataset } from '../datasets/dataset-types';
import { DatasetsService } from '../datasets/datasets.service';
import { Dashboard, DashboardTile } from './dashboard-types';

/**
 * The viewer-facing state of ONE rendered Dashboard — shared by the Dashboard editor and every read-only
 * Dashboard renderer (the Menu viewer), so both apply the same quick filters, cross-filter and drill rule.
 * Provide it per component (`providers: [DashboardViewStore]`); it is never root state.
 *
 * Holds: the widget/dataset lookup a tile resolves through, the tiles, the live cross-filter (seeded from
 * the Dashboard's saved `filter`), the exposed quick-filter fields and their value suggestions, and the
 * `field = value` drill toggle. Authoring (reorder, span, remove, the exposed-field picker, save) stays in
 * the editor. In a viewer the filter is transient — nothing here writes.
 */
@Injectable()
export class DashboardViewStore {
    private widgetsApi = inject(WidgetsService);
    private datasetsApi = inject(DatasetsService);
    private datasetRows = inject(DatasetRowsService);

    readonly widgets = signal<Widget[]>([]);
    readonly datasets = signal<Dataset[]>([]);
    readonly tiles = signal<DashboardTile[]>([]);
    readonly filter = signal<ConditionGroup>(emptyGroup('AND'));
    readonly exposedFields = signal<string[]>([]);

    /** Value suggestions per exposed field — the quick-filter pickers' choices, read off one PAGE of each
     *  tiled dataset (so they are offers, not the column's full domain) and capped so a high-cardinality
     *  column doesn't flood the select. */
    readonly exposedValues = signal<Record<string, string[]>>({});

    private readonly widgetsById = computed(() => new Map(this.widgets().map((w) => [w.id, w])));
    private readonly datasetsById = computed(() => new Map(this.datasets().map((d) => [d.id, d])));

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
    }

    /** Fetch the widget + dataset lookups. `onWidgetsError` lets the editor warn; a viewer degrades silently. */
    loadLookups(onWidgetsError: () => void = () => undefined): void {
        this.widgetsApi.list().subscribe({ next: (w) => this.widgets.set(w), error: onWidgetsError });
        this.datasetsApi.list().subscribe({ next: (d) => this.datasets.set(d), error: () => undefined });
    }

    /** Show a Dashboard: its tiles, its saved filter as the base cross-filter, and its exposed fields. */
    seed(d: Dashboard): void {
        this.tiles.set(d.tiles);
        // A stored empty `filter:` key arrives as `{}` — not nullish, but no group either.
        this.filter.set(d.filter?.items ? d.filter : emptyGroup('AND'));
        this.exposedFields.set(d.exposedFields ?? []);
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

    /** A tile's drill-down click (or a quick-filter pick) — toggle `field = value` in the cross-filter: add
     *  it if absent, remove it if the same value is clicked again. A multi-pair drill (a heatmap cell's row AND
     *  column, via `and`) toggles as ONE unit: when every pair is already present all of them are removed;
     *  otherwise the pair REPLACES any `=` condition on the same fields, so clicking another cell moves the
     *  selection instead of stacking two cells into a filter that matches nothing. Single-field drills keep the
     *  plain toggle (a second bar adds beside the first). Conditions on other fields are left alone. */
    onDrill(event: DrillEvent): void {
        const pairs = [event, ...(event.and ?? [])];
        const current = this.filter();
        const found = pairs.map(({ field, value }) =>
            current.items.findIndex(
                (item) =>
                    item.kind === 'condition' && item.field === field && item.operator === '=' && item.value === value,
            ),
        );
        const multi = pairs.length > 1;
        const fields = new Set(pairs.map((p) => p.field));
        const isEqOnPairField = (item: ConditionGroup['items'][number]): boolean =>
            item.kind === 'condition' && item.operator === '=' && fields.has(item.field);
        const items = found.every((i) => i >= 0)
            ? current.items.filter((_, i) => !found.includes(i))
            : multi
              ? [
                    ...current.items.filter((item) => !isEqOnPairField(item)),
                    ...pairs.map(({ field, value }) => ({ kind: 'condition', field, operator: '=', value }) as Condition),
                ]
              : [
                    ...current.items,
                    ...pairs
                        .filter((_, k) => found[k] < 0)
                        .map(({ field, value }) => ({ kind: 'condition', field, operator: '=', value }) as Condition),
                ];
        this.filter.set({ ...current, items });
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
}
