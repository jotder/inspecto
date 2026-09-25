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
import { KpiTrendComponent } from './plugins/kpi-trend.component';
import { ProgressListComponent } from './plugins/progress-list.component';
import { TreemapChannel, TreemapComponent } from './plugins/treemap.component';
import { HeatmapCellClick, HeatmapComponent } from './plugins/heatmap.component';
import { getVizComponentLoader } from './viz-components';
import {
    signed,
    WATERFALL_KIND_LABEL,
    waterfallAltText,
    waterfallChartData,
    WaterfallStep,
    waterfallSteps,
    waterfallTones,
} from './waterfall-chart';
import { comboAltText, comboChartData, comboSeriesFormat, comboUsesSecondaryAxis } from './combo-chart';
import { ChannelId, VizPlugin, VizProps, VizRenderOptions, VizSeries } from './viz-types';

/** componentKey → Angular component, for plugins that render via the escape hatch (`render.kind:'component'`).
 *  Only lightweight components belong here — heavy hosts register an async loader instead (`viz-components.ts`). */
const COMPONENT_BY_KEY: Record<string, Type<unknown>> = {
    kpi: KpiComponent,
    'kpi-trend': KpiTrendComponent,
    'progress-list': ProgressListComponent,
    treemap: TreemapComponent,
};

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
    imports: [NgComponentOutlet, InspectoChartComponent, DataTableComponent, StatusBadgeComponent, HeatmapComponent],
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
                        [ariaLabel]="chartAriaLabel()"
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
                @if (isHeatmap()) {
                    <!-- Rendered directly, not through the outlet: a cell click is the drill-down seam, and an
                         outlet component cannot emit. It sizes itself (max-h-80) and scrolls inside the tile. -->
                    @if (props().heatmap; as m) {
                        <inspecto-heatmap
                            [data]="m"
                            [options]="renderOptions()?.heatmap"
                            [format]="renderOptions()?.format"
                            [target]="renderOptions()?.kpi?.target"
                            [better]="renderOptions()?.kpi?.better ?? 'higher'"
                            (cellClick)="cellClick.emit($event)"
                        />
                    }
                } @else if (outletComponent(); as cmp) {
                    <!-- UIE-1: a KPI (and a KPI trend) is a number, not a canvas - it gets a compact box; a progress
                         list is as tall as its rows; maps and graphs keep h-64. -->
                    <div [class.h-36]="outletBox() === 'compact'" [class.h-64]="outletBox() === 'fixed'">
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
    /** Emits the clicked category's label (bar/line/area/pie/bubble) — the drill-down seam. Gauge has no filterable
     *  categories, so it never emits. */
    readonly categoryClick = output<string>();
    /** Emits a clicked heatmap cell's raw row AND column values — the host drills on both dimensions at once. */
    readonly cellClick = output<HeatmapCellClick>();
    /** Emits a clicked mark that knows its own channel — the treemap, whose group cells drill on `group` and whose
     *  subgroup cells drill on `subgroup`. The host resolves the channel to its field. */
    readonly channelClick = output<{ channel: ChannelId; value: string }>();

    /** Handed to the treemap as its `select` input: one stable reference, so the outlet never re-binds it. */
    private readonly treemapSelect = (channel: TreemapChannel, value: string): void =>
        this.channelClick.emit({ channel, value });

    readonly renderKind = computed(() => this.plugin().render.kind);

    readonly isGauge = computed(() => this.plugin().meta.type === 'gauge');

    /** UIE-8: the gauge's value as a reader sees it — the widget's format, not the clamped 0–100 fill. */
    readonly gaugeDisplay = computed(() => formatNumber(this.props().value as number, this.renderOptions()?.format));

    /** UIE-8: the gauge's target (`options.kpi`, the same option the KPI tile reads), stated in words. */
    readonly gaugeTarget = computed(() => {
        const o = this.renderOptions();
        return targetStatus(this.props().value ?? 0, o?.kpi?.target, o?.kpi?.better ?? 'higher', o?.format);
    });

    readonly isHeatmap = computed(() => {
        const r = this.plugin().render;
        return r.kind === 'component' && r.componentKey === 'heatmap';
    });

    /** The outlet's box: compact for the KPI tiles, the content's own height for a progress list, else h-64. */
    readonly outletBox = computed<'compact' | 'auto' | 'fixed'>(() => {
        const r = this.plugin().render;
        const key = r.kind === 'component' ? r.componentKey : '';
        return key === 'kpi' || key === 'kpi-trend' ? 'compact' : key === 'progress-list' ? 'auto' : 'fixed';
    });

    /** The drill-down callback a component-render plugin (the progress list) calls with a row's label. Stable, so
     *  the outlet's inputs do not change identity on every run. */
    private readonly emitCategory = (label: string): void => this.categoryClick.emit(label);

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
     *  categories). Blanks drop BEFORE the limit, so a "top 10" is ten real categories. A Waterfall honours only
     *  `hideBlank`: its step order is its meaning (`options.waterfall.order` reorders it) and a trim would falsify the total. */
    private readonly sortedProps = computed<VizProps>(() => {
        const p = this.props();
        const opts = this.renderOptions();
        const ordered = this.plugin().meta.type === 'waterfall';
        const sort = ordered ? undefined : opts?.sort;
        const limit = ordered ? undefined : opts?.limit;
        if (!sort && !limit && !opts?.hideBlank) return p;
        let order = p.labels.map((_, i) => i);
        if (opts?.hideBlank) order = order.filter((i) => (p.labels[i] ?? '').trim() !== '');
        if (sort) {
            const dir = sort === 'asc' ? 1 : -1;
            const value = (i: number): number => p.series[0]?.data[i] ?? 0;
            order = [...order].sort((a, b) => dir * (value(a) - value(b)));
        }
        if (limit) order = order.slice(0, limit);
        return {
            ...p,
            labels: order.map((i) => p.labels[i]),
            series: p.series.map((s): VizSeries => ({ ...s, data: order.map((i) => s.data[i]) })),
        };
    });

    /** The Waterfall's steps (running totals, opening and closing bars) — empty for every other type. */
    readonly waterfall = computed<WaterfallStep[]>(() => {
        if (this.plugin().meta.type !== 'waterfall') return [];
        const p = this.sortedProps();
        return waterfallSteps(p.labels, p.series[0]?.data ?? [], this.renderOptions()?.waterfall);
    });

    /** A text alternative for the canvas when the generic one (first series, value per category) would mislead. */
    readonly chartAriaLabel = computed<string | null>(() => {
        const type = this.plugin().meta.type;
        const opts = this.renderOptions();
        if (type === 'waterfall') return waterfallAltText(this.waterfall(), opts?.format);
        if (type === 'combo') {
            const p = this.sortedProps();
            return comboAltText(p, p.labels.map(categoryLabel), opts, (l) => categoryLabel(seriesLabel(l)));
        }
        return null;
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
        if (plugin.meta.type === 'waterfall') {
            const steps = this.waterfall();
            return waterfallChartData(
                steps,
                steps.map((s) => categoryLabel(s.label)),
                this.renderOptions()?.kpi?.better,
            );
        }
        if (plugin.meta.type === 'combo') {
            const names = (l: string): string => categoryLabel(seriesLabel(l));
            return comboChartData(p, axisLabels, colorsFor(p.series.map((s) => s.label)), this.renderOptions(), names);
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
                      label: (ctx: { dataset: { label?: string; type?: string }; parsed: unknown; label?: string }) => {
                          const parsed = ctx.parsed as number | { x?: number; y?: number } | null;
                          const n = typeof parsed === 'number' ? parsed : isFunnel ? parsed?.x : parsed?.y;
                          const name = isPie ? ctx.label : ctx.dataset.label;
                          // A Combo's line measure reads in its own format (`format2`).
                          const f = type === 'combo' ? comboSeriesFormat(ctx.dataset.type === 'line', opts) : fmt;
                          const value = n == null ? '' : formatNumber(n, f);
                          return name ? `${name}: ${value}` : value;
                      },
                  },
              };
        const plugins = pluginsOverride ?? (tooltip ? {} : undefined);
        if (plugins && tooltip && !isGauge) (plugins as Record<string, unknown>)['tooltip'] = tooltip;
        const valueAxis = isFunnel ? 'x' : 'y';
        const result = {
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
        if (type === 'waterfall') return this.waterfallOptions(result);
        if (type === 'combo') return this.comboOptions(result);
        return result;
    });

    /** The Waterfall's overrides on the shared cartesian options: a legend naming the step kinds (so colour is not the
     *  only signal), tooltips giving the change and the running total, the connector line kept out of both, and never
     *  stacked (the bars already float). */
    private waterfallOptions(base: ChartOptions): ChartOptions {
        const opts = this.renderOptions();
        const fmt = opts?.format;
        const steps = this.waterfall();
        const tones = waterfallTones(opts?.kpi?.better);
        const kinds = (['start', 'increase', 'decrease', 'total'] as const).filter((k) =>
            steps.some((s) => s.kind === k),
        );
        const scales = base.scales as Record<string, Record<string, unknown>> | undefined;
        return {
            ...base,
            plugins: {
                ...base.plugins,
                legend: {
                    display: opts?.legend?.show ?? true,
                    position: opts?.legend?.position ?? 'top',
                    // A kind is not a dataset — there is nothing to toggle.
                    onClick: () => undefined,
                    labels: {
                        // A custom label carries its own text colour: take the themed one the chart resolved.
                        generateLabels: (chart: {
                            options: { plugins?: { legend?: { labels?: { color?: unknown } } } };
                        }) =>
                            kinds.map((k) => ({
                                text: WATERFALL_KIND_LABEL[k],
                                fontColor: chart.options.plugins?.legend?.labels?.color,
                                fillStyle: tones[k],
                                strokeStyle: tones[k],
                                lineWidth: 0,
                                pointStyle: 'rect',
                                hidden: false,
                                datasetIndex: 0,
                            })),
                    },
                },
                tooltip: {
                    filter: (item: { datasetIndex: number }) => item.datasetIndex === 0,
                    callbacks: {
                        title: (items: { dataIndex: number }[]) => {
                            const s = steps[items[0]?.dataIndex ?? -1];
                            return s ? categoryLabel(s.label) : '';
                        },
                        label: (ctx: { dataIndex: number }) => {
                            const s = steps[ctx.dataIndex];
                            if (!s) return '';
                            if (s.kind === 'start' || s.kind === 'total')
                                return `${WATERFALL_KIND_LABEL[s.kind]}: ${formatNumber(s.to, fmt)}`;
                            return [
                                `${WATERFALL_KIND_LABEL[s.kind]}: ${signed(s.delta, fmt)}`,
                                `Running total: ${formatNumber(s.to, fmt)}`,
                            ];
                        },
                    },
                },
            },
            scales: {
                ...scales,
                x: { ...scales?.['x'], stacked: false },
                y: { ...scales?.['y'], stacked: false },
            },
        } as ChartOptions;
    }

    /** The Combo's overrides: the secondary right axis `y2` for the line measures (its own ticks format and title), and
     *  a tooltip listing every measure at the hovered category. */
    private comboOptions(base: ChartOptions): ChartOptions {
        const opts = this.renderOptions();
        const plugins = base.plugins as Record<string, Record<string, unknown>> | undefined;
        const withTooltip = {
            ...plugins,
            tooltip: { ...plugins?.['tooltip'], mode: 'index', intersect: false },
        };
        if (!comboUsesSecondaryAxis(this.sortedProps(), opts)) return { ...base, plugins: withTooltip } as ChartOptions;
        const fmt2 = comboSeriesFormat(true, opts);
        const y2Title = opts?.axis?.y2Title;
        return {
            ...base,
            plugins: withTooltip,
            scales: {
                ...base.scales,
                y2: {
                    position: 'right',
                    ticks: { callback: (v: string | number) => formatAxisTick(Number(v), fmt2) },
                    title: y2Title ? { display: true, text: y2Title } : undefined,
                },
            },
        } as ChartOptions;
    }

    /** Table columns: readable headers (or the widget's `columnLabels`) and status columns as badges. */
    readonly colDefs = computed<ColDef[] | undefined>(() => {
        const cols = this.props().columns;
        return cols?.length ? tableColDefs(cols, this.renderOptions()) : undefined;
    });

    /** Inputs for the outlet component — the KPI's value/label, the KPI trend's / progress list's series, or a
     *  view-bound wrapper's saved-view id. */
    readonly outletInputs = computed<Record<string, unknown>>(() => {
        const r = this.plugin().render;
        if (r.kind === 'component' && r.componentKey === 'treemap') {
            return {
                rows: this.props().treemap ?? [],
                format: this.renderOptions()?.format,
                limit: this.renderOptions()?.treemap?.limit,
                select: this.treemapSelect,
            };
        }
        const o = this.renderOptions();
        if (r.kind === 'component' && (r.componentKey === 'kpi-trend' || r.componentKey === 'progress-list')) {
            const series = {
                labels: this.props().labels,
                values: this.props().series[0]?.data ?? [],
                format: o?.format,
                target: o?.kpi?.target,
                better: o?.kpi?.better ?? 'higher',
            };
            return r.componentKey === 'kpi-trend'
                ? { ...series, compareBack: o?.trend?.compareBack }
                : {
                      ...series,
                      limit: o?.progress?.limit ?? o?.limit,
                      sort: o?.sort,
                      max: o?.progress?.max,
                      select: this.emitCategory,
                  };
        }
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
        // A Waterfall's bars are its steps (reordered, plus a computed total that is no category): emit the step's source.
        if (this.plugin().meta.type === 'waterfall') {
            const source = this.waterfall()[index]?.source;
            if (source != null) this.categoryClick.emit(source);
            return;
        }
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
