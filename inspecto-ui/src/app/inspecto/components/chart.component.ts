import {
    AfterViewInit,
    booleanAttribute,
    ChangeDetectionStrategy,
    Component,
    ElementRef,
    EventEmitter,
    inject,
    Input,
    OnChanges,
    OnDestroy,
    Output,
    ViewChild,
} from '@angular/core';
import { Chart, ChartData, ChartOptions, ChartType, registerables } from 'chart.js';
import { resolveChartTokens, themedChartConfig } from 'app/inspecto/theme/chart-theme';
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
 * Thin theme-aware Chart.js host. Recreates the chart when data changes, styled by the ONE shared chart theme
 * (`theme/chart-theme.ts`), and re-resolves that theme's tokens when the scheme class on `<body>` flips.
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
    /** The host's own text alternative, for a chart whose data points do not read as values (the Waterfall's
     *  `[low, high]` bars, the Combo's two kinds). Absent: {@link altText} is derived from the first series. */
    @Input() ariaLabel: string | null = null;
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
        if (this.ariaLabel) return this.ariaLabel;
        const d = this.data;
        if (!d?.labels?.length) return `${this.type} chart`;
        const values = (d.datasets?.[0]?.data ?? []) as unknown[];
        const parts = d.labels.slice(0, 10).map((l, i) => `${String(l)}: ${summarize(values[i])}`);
        const more = d.labels.length > 10 ? `, and ${d.labels.length - 10} more` : '';
        return `${this.type} chart. ${parts.join(', ')}${more}.`;
    }

    @ViewChild('canvas') private canvas?: ElementRef<HTMLCanvasElement>;
    private chart: Chart | null = null;
    private ready = false;
    private resizeObserver: ResizeObserver | null = null;
    private schemeObserver: MutationObserver | null = null;
    private hostEl = inject(ElementRef<HTMLElement>);

    ngAfterViewInit(): void {
        this.ready = true;
        this.rebuild();
        // The layout sets the scheme as a class on <body> (light/dark, 'auto' already decided), and the theme's
        // tokens are the CSS variables that class selects — so watch the class itself and rebuild on a flip.
        if (typeof MutationObserver !== 'undefined') {
            // Only a scheme flip rebuilds — other body classes (is-mobile, the colour theme) come and go too.
            let dark = document.body.classList.contains('dark');
            this.schemeObserver = new MutationObserver(() => {
                if (dark === document.body.classList.contains('dark')) return;
                dark = !dark;
                this.rebuild();
            });
            this.schemeObserver.observe(document.body, { attributes: true, attributeFilter: ['class'] });
        }
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
        this.schemeObserver?.disconnect();
        this.chart?.destroy();
    }

    private rebuild(): void {
        this.chart?.destroy();
        this.chart = null;
        // ⚠ Also guards the canvas itself: with nothing to plot the template renders the empty state
        // instead, so there is no <canvas> for Chart.js to attach to.
        if (!this.data || !this.hasData || !this.canvas) return;
        // The caller's options (Widget renderOptions: legend, axis titles, stacked, formats) deep-merge OVER the
        // shared theme, so an override composes with the theme's typography/colours instead of losing them.
        const config = themedChartConfig(
            this.type,
            this.data,
            {
                responsive: true,
                maintainAspectRatio: false,
                onClick: (_event, elements) => {
                    const index = elements[0]?.index;
                    if (index != null) this.elementClick.emit(index);
                },
                ...this.options,
            },
            resolveChartTokens(),
        );
        this.chart = new Chart(this.canvas.nativeElement, config);
    }
}
