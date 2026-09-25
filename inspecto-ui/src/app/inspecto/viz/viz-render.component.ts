import { ChangeDetectionStrategy, Component, Type, computed, effect, input, output, signal } from '@angular/core';
import { NgComponentOutlet } from '@angular/common';
import { ColDef } from 'ag-grid-community';
import { ChartData, ChartOptions, ChartType } from 'chart.js';
import { InspectoChartComponent } from 'app/inspecto/components/chart.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { tableColDefs } from './table-columns';
import { words } from './column-label';
import { formatAxisTick, formatNumber } from './number-format';
import { seriesColors } from './series-colors';
import { dateAxisLabels, fullDateLabel } from './date-labels';
import { targetStatus } from './target-status';
import { CHART_CATEGORICAL, CHART_PALETTES, CHART_TONE, GAUGE_TRACK } from 'app/inspecto/theme/chart-tokens';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { KpiComponent, KpiMode } from './plugins/kpi.component';
import { getVizComponentLoader } from './viz-components';
import { VizPlugin, VizProps, VizRenderOptions, VizSeries } from './viz-types';

/** componentKey → Angular component, for plugins that render via the escape hatch (`render.kind:'component'`).
 *  Only lightweight components belong here — heavy hosts register an async loader instead (`viz-components.ts`). */
const COMPONENT_BY_KEY: Record<string, Type<unknown>> = { kpi: KpiComponent };

/**
 * Render host — dispatches a {@link VizPlugin}'s `render.kind` to the right shared surface: `chartjs` →
 * `<inspecto-chart>`, `aggrid` → `<inspecto-data-table>`, `component` → `NgComponentOutlet` (KPI), `g6` →
 * placeholder for now. The plugins stay framework-free; this thin component is the only Angular glue.
 * `renderOptions` (a Studio Widget's advanced/cog config, or any caller's) applies uniformly across chartjs
 * plugins — palette, sort/limit, axis titles, legend, stacked — without each plugin needing to know about it.
 */
@Component({
    selector: 'inspecto-viz-render',
    standalone: true,
    imports: [NgComponentOutlet, InspectoChartComponent, DataTableComponent, StatusBadgeComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        @switch (renderKind()) {
            @case ('chartjs') {
                @if (isGauge()) {
                    <!-- UIE-8: the gauge states its value (and its target, in words) under the arc, not just a fill. -->
                    @if (chartData(); as data) {
                        <div class="relative">
                            <inspecto-chart
                                class="!h-40"
                                [type]="chartType()"
                                [data]="data"
                                [options]="chartJsOptions()"
                            />
                            <div
                                class="pointer-events-none absolute inset-x-0 bottom-0 text-center text-3xl font-extrabold tabular-nums leading-none"
                                data-testid="gauge-value"
                            >
                                {{ gaugeDisplay() }}
                            </div>
                        </div>
                    }
                    @if (gaugeTarget(); as t) {
                        <div class="mt-2 flex justify-center" data-testid="gauge-target">
                            <inspecto-status-badge [value]="t.met ? 'PASS' : 'FAIL'" [label]="t.text" />
                        </div>
                    }
                } @else if (chartData(); as data) {
                    <inspecto-chart
                        [type]="chartType()"
                        [data]="data"
                        [options]="chartJsOptions()"
                        (elementClick)="onElementClick($event)"
                    />
                }
            }
            @case ('aggrid') {
                <inspecto-data-table
                    tier="standard"
                    [rows]="props().rows ?? []"
                    [columns]="colDefs()"
                    [sourceName]="title()"
                />
            }
            @case ('component') {
                @if (outletComponent(); as cmp) {
                    <!-- UIE-1: a KPI is a number, not a canvas - it gets a compact box; maps and graphs keep h-64. -->
                    <div [class.h-36]="isKpi()" [class.h-64]="!isKpi()">
                        <ng-container *ngComponentOutlet="cmp; inputs: outletInputs()" />
                    </div>
                }
            }
            @default {
                <div class="text-secondary p-4 text-sm">This visualization type isn't available yet.</div>
            }
        }
    `,
})
export class VizRenderComponent {
    readonly plugin = input.required<VizPlugin>();
    readonly props = input.required<VizProps>();
    /** Display/source name (table FROM, KPI caption). */
    readonly title = input('data');
    /** The advanced/cog render options (palette, sort/limit, axis titles, legend, stacked) — all optional. */
    readonly renderOptions = input<VizRenderOptions | undefined>(undefined);
    /** For view-bound plugins (`meta.viewKind`): the saved view id the outlet component renders. */
    readonly viewId = input<string | undefined>(undefined);
    /** For a view-bound plugin whose binding is more than an id (LA-21's Working Set Widget): handed to the outlet
     *  component as its `binding` input. Pass a STABLE reference — a new object per change detection re-runs its reads. */
    readonly viewBinding = input<unknown>(undefined);
    /** KPI only: the in-place size, when the HOST owns the size control (a Dashboard tile's action set). Absent ⇒
     *  the KPI keeps its own card and size button. */
    readonly kpiSize = input<KpiMode | undefined>(undefined);
    /** Emits the clicked category's label (bar/line/area/pie/bubble) — the drill-down seam. Gauge has no
     *  filterable categories, so it never emits. */
    readonly categoryClick = output<string>();

    readonly renderKind = computed(() => this.plugin().render.kind);

    readonly isGauge = computed(() => this.plugin().meta.type === 'gauge');

    /** UIE-8: the gauge's value as a reader sees it — the widget's format, not the clamped 0–100 fill. */
    readonly gaugeDisplay = computed(() => formatNumber(this.props().value as number, this.renderOptions()?.format));

    /** UIE-8: the gauge's target (`options.kpi`, the same option the KPI tile reads), stated in words. */
    readonly gaugeTarget = computed(() => {
        const o = this.renderOptions();
        return targetStatus(this.props().value ?? 0, o?.kpi?.target, o?.kpi?.better ?? 'higher', o?.format);
    });

    readonly isKpi = computed(() => {
        const r = this.plugin().render;
        return r.kind === 'component' && r.componentKey === 'kpi';
    });

    /** A lazily-loaded component-render host (from a registered loader), once its import resolves. */
    private readonly loadedComponent = signal<Type<unknown> | null>(null);

    /** The Angular component for a `component`-render plugin — static map first, then the loader registry. */
    readonly outletComponent = computed<Type<unknown> | null>(() => {
        const r = this.plugin().render;
        return r.kind === 'component' ? (COMPONENT_BY_KEY[r.componentKey] ?? this.loadedComponent()) : null;
    });

    constructor() {
        // Resolve a registered async loader when the plugin needs one (keeps MapLibre/G6 out of eager bundles).
        // The stale-key guard drops a resolution that lands after the plugin has already changed.
        effect(() => {
            const r = this.plugin().render;
            if (r.kind !== 'component' || COMPONENT_BY_KEY[r.componentKey]) return;
            const loader = getVizComponentLoader(r.componentKey);
            this.loadedComponent.set(null);
            if (!loader) return;
            const key = r.componentKey;
            loader().then((cmp) => {
                const current = this.plugin().render;
                if (current.kind === 'component' && current.componentKey === key) this.loadedComponent.set(cmp);
            });
        });
    }

    readonly chartType = computed<ChartType>(() => {
        const r = this.plugin().render;
        return (r.kind === 'chartjs' ? r.chartType : 'bar') as ChartType;
    });

    /** Props without blank categories (`hideBlank`), reordered by `sort` (on the first series' value) and trimmed to
     *  `limit` categories. Chart.js only — table/KPI ignore these (rows already have their own grid sort; KPI has no
     *  categories). Blanks drop BEFORE the limit, so a "top 10" is ten real categories. */
    private readonly sortedProps = computed<VizProps>(() => {
        const p = this.props();
        const opts = this.renderOptions();
        if (!opts?.sort && !opts?.limit && !opts?.hideBlank) return p;
        let order = p.labels.map((_, i) => i);
        if (opts.hideBlank) order = order.filter((i) => (p.labels[i] ?? '').trim() !== '');
        if (opts.sort) {
            const dir = opts.sort === 'asc' ? 1 : -1;
            const value = (i: number): number => p.series[0]?.data[i] ?? 0;
            order = [...order].sort((a, b) => dir * (value(a) - value(b)));
        }
        if (opts.limit) order = order.slice(0, opts.limit);
        return {
            ...p,
            labels: order.map((i) => p.labels[i]),
            series: p.series.map((s): VizSeries => ({ ...s, data: order.map((i) => s.data[i]) })),
        };
    });

    readonly chartData = computed<ChartData | null>(() => {
        const plugin = this.plugin();
        const r = plugin.render;
        if (r.kind !== 'chartjs') return null;
        const p = this.sortedProps();
        const palette = CHART_PALETTES[this.renderOptions()?.palette ?? ''] ?? CHART_CATEGORICAL;
        const color = (i: number): string => palette[i % palette.length];
        // UIE-2: colour by meaning (status tones) then by name, so "Fail" is red everywhere and a series keeps its
        // colour across widgets. An explicitly chosen non-default palette (monochrome) keeps positional colours.
        const positional = !!this.renderOptions()?.palette && this.renderOptions()?.palette !== 'categorical';
        const colorsFor = (labels: readonly string[]): string[] => (positional ? labels.map((_, i) => color(i)) : seriesColors(labels));
        // Display only: a NULL/empty category drew as an unlabelled bar. Clicks still emit the raw label
        // (onElementClick reads sortedProps), so drill-down keeps filtering on the real value.
        const shown = p.labels.map(categoryLabel);
        // A category axis over ISO dates reads like a calendar ("Oct 2025", "21 Sep"); the tooltip keeps the full date.
        const axisLabels = (dateAxisLabels(p.labels) ?? p.labels).map(categoryLabel);

        if (plugin.meta.type === 'gauge') {
            const clamp = (n: number): number => Math.max(0, Math.min(100, n));
            const value = clamp(this.props().value ?? 0);
            const valueRing = { data: [value, 100 - value], backgroundColor: [color(0), GAUGE_TRACK], weight: 3 };
            // UIE-8: with a target, a thin inner ring splits the scale into its bad and good zones at the target —
            // below it is bad when higher is better, good when lower is better. Status tones, never literals.
            const kpi = this.renderOptions()?.kpi;
            if (kpi?.target == null || !Number.isFinite(kpi.target))
                return { labels: ['Value', 'Remaining'], datasets: [valueRing] };
            const target = clamp(kpi.target);
            const [below, above] =
                (kpi.better ?? 'higher') === 'higher'
                    ? [CHART_TONE.error, CHART_TONE.success]
                    : [CHART_TONE.success, CHART_TONE.error];
            return {
                labels: ['Value', 'Remaining'],
                datasets: [valueRing, { data: [target, 100 - target], backgroundColor: [below, above], weight: 1 }],
            };
        }
        if (plugin.meta.type === 'scatter') {
            const [xs, ys] = p.series;
            const points = (xs?.data ?? []).map((x, i) => ({ x, y: ys?.data[i] ?? 0 }));
            return {
                labels: shown,
                datasets: [{ label: 'Scatter', data: points, backgroundColor: colorsFor(p.labels) }],
            };
        }
        if (plugin.meta.type === 'bubble') {
            const [xs, ys, sizes] = p.series;
            const maxSize = Math.max(1, ...(sizes?.data ?? [0]));
            const toRadius = (v: number): number => 4 + (Math.max(0, v) / maxSize) * 20; // 4–24px, relative to the largest point
            const points = (xs?.data ?? []).map((x, i) => ({
                x,
                y: ys?.data[i] ?? 0,
                r: toRadius(sizes?.data[i] ?? 0),
            }));
            return {
                labels: shown,
                datasets: [{ label: 'Bubble', data: points, backgroundColor: colorsFor(p.labels) }],
            };
        }

        const isPie = r.chartType === 'pie' || r.chartType === 'doughnut';
        if (isPie) {
            return {
                labels: shown,
                datasets: [{ data: p.series[0]?.data ?? [], backgroundColor: colorsFor(p.labels) }],
            };
        }
        const colors = colorsFor(p.series.map((s) => s.label));
        return {
            labels: axisLabels,
            datasets: p.series.map((s, i) => ({
                label: categoryLabel(seriesLabel(s.label)),
                data: s.data,
                backgroundColor: colors[i],
                borderColor: colors[i],
                fill: (s['fill'] as boolean) ?? false,
                stack: this.renderOptions()?.stacked ? 'stack' : undefined,
            })),
        };
    });

    /** Chart.js overrides derived from `renderOptions` — legend show/position, axis titles, stacked scales —
     *  plus the gauge's fixed half-circle styling (an explicit `renderOptions.legend` still overrides its
     *  hidden-by-default legend). `<inspecto-chart>` deep-merges these under its theme defaults, so omitted
     *  fields keep their styling. */
    readonly chartJsOptions = computed<ChartOptions>(() => {
        const isGauge = this.plugin().meta.type === 'gauge';
        const opts = this.renderOptions();
        const legend = opts?.legend;
        // `display` only when the Widget says so: left unset, the chart theme shows a legend for 2+ series only.
        const pluginsOverride = legend
            ? {
                  legend: {
                      ...(legend.show === undefined ? {} : { display: legend.show }),
                      position: legend.position ?? 'top',
                  },
              }
            : isGauge
              ? { legend: { display: false }, tooltip: { enabled: false } }
              : undefined;
        const axis = opts?.axis;
        const stacked = opts?.stacked;
        const type = this.plugin().meta.type;
        const isFunnel = type === 'funnel';
        const r = this.plugin().render;
        const isPie = r.kind === 'chartjs' && (r.chartType === 'pie' || r.chartType === 'doughnut');
        // UIE-4: the value axis reads compact ("2.5M") and tooltips read the widget's format, not raw floats.
        const fmt = opts?.format;
        const cartesian = !isGauge && !isPie && type !== 'scatter' && type !== 'bubble';
        const valueTicks = cartesian ? { ticks: { callback: (v: string | number) => formatAxisTick(Number(v), fmt) } } : {};
        const tooltip = isGauge
            ? undefined
            : {
                  callbacks: {
                      // A date category's full date ("1 Oct 2025") — the axis shows the short form.
                      ...(cartesian
                          ? {
                                title: (items: { dataIndex: number; label?: string }[]) => {
                                    const item = items[0];
                                    if (!item) return '';
                                    const raw = this.sortedProps().labels[item.dataIndex];
                                    return (raw != null ? fullDateLabel(raw) : null) ?? item.label ?? '';
                                },
                            }
                          : {}),
                      label: (ctx: { dataset: { label?: string }; parsed: unknown; label?: string }) => {
                          const parsed = ctx.parsed as number | { x?: number; y?: number } | null;
                          const n = typeof parsed === 'number' ? parsed : isFunnel ? parsed?.x : parsed?.y;
                          const name = isPie ? ctx.label : ctx.dataset.label;
                          const value = n == null ? '' : formatNumber(n, fmt);
                          return name ? `${name}: ${value}` : value;
                      },
                  },
              };
        const plugins = pluginsOverride ?? (tooltip ? {} : undefined);
        if (plugins && tooltip && !isGauge) (plugins as Record<string, unknown>)['tooltip'] = tooltip;
        const valueAxis = isFunnel ? 'x' : 'y';
        return {
            ...(isGauge ? { circumference: 180, rotation: 270, cutout: '70%' } : {}),
            ...(isFunnel ? { indexAxis: 'y' as const } : {}),
            plugins,
            scales:
                axis?.xTitle || axis?.yTitle || stacked || cartesian
                    ? {
                          x: {
                              ...(valueAxis === 'x' ? valueTicks : {}),
                              stacked: !!stacked,
                              title: axis?.xTitle ? { display: true, text: axis.xTitle } : undefined,
                          },
                          y: {
                              ...(valueAxis === 'y' ? valueTicks : {}),
                              stacked: !!stacked,
                              title: axis?.yTitle ? { display: true, text: axis.yTitle } : undefined,
                          },
                      }
                    : undefined,
        } as ChartOptions;
    });

    /** Table columns: readable headers (or the widget's `columnLabels`) and status columns as badges. */
    readonly colDefs = computed<ColDef[] | undefined>(() => {
        const cols = this.props().columns;
        return cols?.length ? tableColDefs(cols, this.renderOptions()) : undefined;
    });

    /** Inputs for the outlet component — the KPI's value/label, or a view-bound wrapper's saved-view id. */
    readonly outletInputs = computed<Record<string, unknown>>(() => {
        const r = this.plugin().render;
        return r.kind === 'component' && r.componentKey === 'kpi'
            ? {
                  value: this.props().value ?? 0,
                  compare: this.props().compare,
                  format: this.renderOptions()?.format,
                  target: this.renderOptions()?.kpi?.target,
                  better: this.renderOptions()?.kpi?.better ?? 'higher',
                  size: this.kpiSize(),
              }
            : this.viewBinding() === undefined
              ? { viewId: this.viewId() }
              : { viewId: this.viewId(), binding: this.viewBinding() };
    });

    /** Resolve the clicked point's index to its category label (from the same, possibly sorted/limited,
     *  labels the chart actually rendered) and emit it — skipped for gauge, whose slices aren't categories. */
    onElementClick(index: number): void {
        if (this.plugin().meta.type === 'gauge') return;
        const label = this.sortedProps().labels[index];
        if (label != null) this.categoryClick.emit(label);
    }
}

/** A category/series value as a reader sees it — an empty value reads "(blank)", never an unlabelled mark. */
export function categoryLabel(label: string): string {
    return label.trim() === '' ? '(blank)' : label;
}

/** UIE-4: a measure's generated label (`sum(value_at_risk_sar)`) as a reader says it ("Value at risk (SAR)"). A
 *  series named by a data value (a `series` field) is already readable and passes through. */
export function seriesLabel(label: string): string {
    if (label === 'count' || label === 'count(*)') return 'Count';
    const m = /^(\w+)\((\w+)\)$/.exec(label);
    return m ? words(m[2]) : label;
}
