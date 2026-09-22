import {
    AfterViewInit,
    booleanAttribute,
    ChangeDetectionStrategy,
    Component,
    DestroyRef,
    ElementRef,
    EventEmitter,
    inject,
    Input,
    OnChanges,
    OnDestroy,
    Output,
    ViewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { GammaConfigService } from '@gamma/services/config';
import { Chart, ChartConfiguration, ChartData, ChartOptions, ChartType, registerables } from 'chart.js';
import { canvasTheme } from 'app/inspecto/theme/chart-tokens';
import { InspectoEmptyStateComponent } from './empty-state.component';

Chart.register(...registerables);

/** One data point as alt-text: numbers as-is, `{x,y}` points as a pair, anything else stringified. */
function summarize(v: unknown): string {
    if (v == null) return '—';
    if (typeof v === 'object') {
        const p = v as { x?: unknown; y?: unknown };
        if (p.x !== undefined || p.y !== undefined) return `(${String(p.x ?? '—')}, ${String(p.y ?? '—')})`;
    }
    return String(v);
}

/**
 * Thin theme-aware Chart.js host. Recreates the chart when data changes and
 * restyles axis/legend colors when the gamma scheme flips between light/dark.
 */
@Component({
    selector: 'inspecto-chart',
    standalone: true,
    imports: [InspectoEmptyStateComponent],
    template: `
        @if (hasData) {
            <canvas #canvas role="img" [attr.aria-label]="altText"></canvas>
        } @else {
            <inspecto-empty-state
                class="block h-full"
                icon="heroicons_outline:chart-bar"
                [message]="emptyMessage"
            ></inspecto-empty-state>
        }
    `,
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: { class: 'block relative h-64 w-full' },
})
export class InspectoChartComponent implements AfterViewInit, OnChanges, OnDestroy {
    @Input({ required: true }) type: ChartType = 'bar';
    @Input({ required: true }) data: ChartData | null = null;
    @Input() options: ChartOptions = {};
    /** Shown in place of the canvas when there is nothing to plot. */
    @Input() emptyMessage = 'No data to chart yet.';
    /**
     * The HOST's verdict that this series means nothing yet, for the case the component cannot see:
     * a series of explicit zeros is indistinguishable from a real measurement of zero, and only the
     * pane knows which it is (no Consignment has ever run vs. every Consignment succeeded). Pass
     * `[empty]="!report()?.totalBatches"` and the empty state renders instead of a 0-to-1 axis.
     */
    @Input({ transform: booleanAttribute }) empty = false;
    /** The clicked data point's index, for drill-down — no-op cost when nobody listens. */
    @Output() elementClick = new EventEmitter<number>();

    /**
     * 🔴 Whether there is anything to plot. Chart.js draws a perfectly convincing EMPTY CHART — axes
     * labelled 0 to 1.0, a legend, gridlines — for an empty series, which reads as "measured, and the
     * answer is zero" when the truth is "nothing has run yet". The 2026-09-22 UI walk found that on
     * Collectors and the Overview dashboard. Below, an empty series renders the shared empty state
     * instead (UI-09).
     *
     * Empty means: no labels, or no dataset carries a single point. A dataset of explicit zeros is NOT
     * empty — that is a real measurement and must still draw.
     */
    get hasData(): boolean {
        if (this.empty) return false;
        const d = this.data;
        if (!d?.labels?.length) return false;
        return (d.datasets ?? []).some((ds) => (ds.data as unknown[] | undefined)?.length);
    }

    /** Text alternative for the canvas (WCAG 1.1.1 — canvases are invisible to screen readers): the chart
     *  type plus each category's first-series value, capped so long series don't produce an essay. */
    get altText(): string {
        const d = this.data;
        if (!d?.labels?.length) return `${this.type} chart`;
        const values = (d.datasets?.[0]?.data ?? []) as unknown[];
        const parts = d.labels.slice(0, 10).map((l, i) => `${String(l)}: ${summarize(values[i])}`);
        const more = d.labels.length > 10 ? `, and ${d.labels.length - 10} more` : '';
        return `${this.type} chart. ${parts.join(', ')}${more}.`;
    }

    @ViewChild('canvas') private canvas?: ElementRef<HTMLCanvasElement>;
    private chart: Chart | null = null;
    private dark = false;
    private ready = false;
    private resizeObserver: ResizeObserver | null = null;
    private destroyRef = inject(DestroyRef);
    private hostEl = inject(ElementRef<HTMLElement>);

    constructor() {
        inject(GammaConfigService)
            .config$.pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((config) => {
                this.dark =
                    config?.scheme === 'dark' ||
                    (config?.scheme === 'auto' && window.matchMedia('(prefers-color-scheme: dark)').matches);
                if (this.ready) this.rebuild();
            });
    }

    ngAfterViewInit(): void {
        this.ready = true;
        this.rebuild();
        // Chart.js `responsive` reacts to window resize but not container-only changes (dashboard tile
        // span toggle, side-pane collapse, flex reflow). Observe the host box and resize the chart to it.
        // Observe the HOST, not the <canvas> — with maintainAspectRatio:false Chart.js sizes the canvas,
        // so observing it would feedback-loop. Guarded for jsdom (tests have no ResizeObserver).
        if (typeof ResizeObserver !== 'undefined') {
            this.resizeObserver = new ResizeObserver(() => this.chart?.resize());
            this.resizeObserver.observe(this.hostEl.nativeElement);
        }
    }

    ngOnChanges(): void {
        // The canvas is created/destroyed by the `@if` above, so let this change-detection pass render
        // the new branch before attaching Chart.js to it.
        if (this.ready) queueMicrotask(() => this.rebuild());
    }

    ngOnDestroy(): void {
        this.resizeObserver?.disconnect();
        this.chart?.destroy();
    }

    private rebuild(): void {
        this.chart?.destroy();
        this.chart = null;
        // ⚠ Also guards the canvas itself: with nothing to plot the template renders the empty state
        // instead, so there is no <canvas> for Chart.js to attach to.
        if (!this.data || !this.hasData || !this.canvas) return;
        const { fg, grid } = canvasTheme(this.dark);
        // Deep-merge (not replace) legend/scales so a caller's override (e.g. Widget renderOptions — legend
        // position, an axis title, stacked) composes with the theme's fg/grid colors instead of losing them.
        const axisDefaults = this.type === 'bar' ? { ticks: { color: fg }, grid: { color: grid } } : {};
        const config: ChartConfiguration = {
            type: this.type,
            data: this.data,
            options: {
                responsive: true,
                maintainAspectRatio: false,
                onClick: (_event, elements) => {
                    const index = elements[0]?.index;
                    if (index != null) this.elementClick.emit(index);
                },
                ...this.options,
                plugins: {
                    ...this.options.plugins,
                    legend: { labels: { color: fg }, ...this.options.plugins?.legend },
                },
                scales:
                    this.type === 'bar' || this.options.scales
                        ? {
                              x: { ...axisDefaults, ...this.options.scales?.['x'] },
                              y: { ...axisDefaults, ...this.options.scales?.['y'] },
                          }
                        : undefined,
            },
        };
        this.chart = new Chart(this.canvas.nativeElement, config);
    }
}
