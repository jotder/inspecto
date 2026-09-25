import { Chart, ChartConfiguration, ChartData, ChartOptions, ChartType } from 'chart.js';
import { _merger, color, merge } from 'chart.js/helpers';
import { canvasTheme } from './chart-tokens';

/**
 * The ONE chart theme (typography, grid, bars, lines, tooltip, legend, donut) for every Chart.js chart, applied
 * by `<inspecto-chart>`. Canvas cannot read CSS custom properties, so the design tokens are RESOLVED through
 * `getComputedStyle` at render time — the host re-resolves them when the scheme class on `<body>` flips, so the
 * same code serves the light and the dark theme. A token that resolves blank (jsdom, an unthemed page) falls
 * back to {@link canvasTheme}. Series colours are NOT set here: they come from `series-colors.ts` /
 * `CHART_TONE` / the palettes in `chart-tokens.ts`, on the datasets.
 */
export interface ChartThemeTokens {
    /** The app's font family (`body`'s computed `font-family`). */
    font: string;
    /** Primary text — tooltip title/body. */
    text: string;
    /** Muted text — ticks, legend labels, axis titles. */
    muted: string;
    /** Value-axis gridlines. */
    grid: string;
    /** Card surface — tooltip background, donut segment borders. */
    surface: string;
    /** Hairline border — the tooltip card's outline. */
    border: string;
}

/** Resolve the chart tokens from the `--gamma-*` custom properties in effect on `el` (default: `<body>`). */
export function resolveChartTokens(el: Element = document.body): ChartThemeTokens {
    const style = getComputedStyle(el);
    const read = (name: string): string => style.getPropertyValue(name).trim();
    const fallback = canvasTheme(el.classList.contains('dark') || document.documentElement.classList.contains('dark'));
    return {
        font: style.fontFamily.trim() || String(Chart.defaults.font.family),
        text: read('--gamma-text-default') || fallback.fg,
        muted: read('--gamma-text-secondary') || fallback.fg,
        grid: read('--gamma-divider') || fallback.grid,
        surface: read('--gamma-bg-card') || fallback.surface,
        border: read('--gamma-border') || fallback.edge,
    };
}

const CARTESIAN: readonly string[] = ['bar', 'line', 'scatter', 'bubble'];
const CIRCULAR: readonly string[] = ['pie', 'doughnut', 'polarArea'];

/**
 * The themed Chart.js configuration for `type`/`data`, with the caller's `options` deep-merged OVER the theme —
 * so an explicit legend, axis title, stacked flag, format callback or indexAxis always wins and omitted fields
 * keep the theme. Data semantics are untouched; the one data-side change is a line/area fill drawn as a faint
 * tint of its series colour instead of the solid colour. Never mutates `data` or `options`.
 */
export function themedChartConfig(
    type: ChartType,
    data: ChartData,
    options: ChartOptions,
    tokens: ChartThemeTokens,
): ChartConfiguration {
    const merged: ChartOptions = merge<ChartOptions, ChartOptions, ChartOptions>(
        {},
        [chartThemeOptions(type, data, options, tokens), options],
        {
            merger: skipUndefined,
        },
    );
    return { type, data: tintAreaFills(type, data), options: merged };
}

/** Chart.js's own deep merge, except an explicit `undefined` (e.g. `title: undefined` for "no axis title") keeps
 *  the theme's value instead of erasing it. */
function skipUndefined(key: string, target: Record<string, unknown>, source: Record<string, unknown>, o: object): void {
    if (source[key] !== undefined) _merger(key, target, source, o);
}

/** The theme fragment alone (before the caller's options are merged over it). Exported for the specs. */
export function chartThemeOptions(
    type: ChartType,
    data: ChartData,
    options: ChartOptions,
    t: ChartThemeTokens,
): ChartOptions {
    const font = (size: number, weight?: 'normal' | 'bold' | number) => ({
        family: t.font,
        size,
        ...(weight ? { weight } : {}),
    });
    const circular = CIRCULAR.includes(type);
    // A single series needs no key — its name is already the widget's title. Slices of a pie/donut DO need one.
    const legendDisplay = circular || (data.datasets?.length ?? 0) > 1;
    const theme: Record<string, unknown> = {
        layout: { padding: 4 },
        plugins: {
            legend: {
                display: legendDisplay,
                labels: {
                    color: t.muted,
                    font: font(12),
                    usePointStyle: true,
                    pointStyle: 'circle',
                    boxWidth: 8,
                    boxHeight: 8,
                    padding: 12,
                },
            },
            tooltip: {
                backgroundColor: t.surface,
                titleColor: t.text,
                bodyColor: t.text,
                borderColor: t.border,
                borderWidth: 1,
                padding: 10,
                cornerRadius: 8,
                caretSize: 5,
                boxPadding: 4,
                usePointStyle: true,
                titleFont: font(12, 600),
                bodyFont: font(12),
                // A line/area tooltip lists every series at the hovered category (clicks keep the hover mode).
                ...(type === 'line' ? { mode: 'index', intersect: false } : {}),
            },
        },
    };
    if (CARTESIAN.includes(type)) {
        const opts = options as { indexAxis?: string; scales?: Record<string, { stacked?: boolean }> };
        const horizontal = opts.indexAxis === 'y';
        const ticks = { color: t.muted, font: font(11), padding: 6 };
        const title = { color: t.muted, font: font(11) };
        // Horizontal gridlines only (the value axis; scatter/bubble's y) — none on a category axis, no axis lines.
        const axis = (value: boolean) => ({
            ticks,
            title,
            border: { display: false },
            grid: { display: value, color: t.grid, drawTicks: false },
        });
        theme['scales'] = { x: axis(horizontal), y: axis(!horizontal) };
        const stacked = !!(opts.scales?.['x']?.stacked || opts.scales?.['y']?.stacked);
        theme['datasets'] = {
            bar: { maxBarThickness: 48, categoryPercentage: 0.7, barPercentage: 0.85, borderRadius: stacked ? 0 : 4 },
            line: {
                tension: 0.3,
                borderWidth: 2,
                pointRadius: 0,
                pointHoverRadius: 4,
                pointHitRadius: 10,
                pointBackgroundColor: t.surface,
                pointHoverBorderWidth: 2,
            },
        };
    }
    if (circular) {
        theme['elements'] = { arc: { borderColor: t.surface, borderWidth: 2, hoverOffset: 4 } };
        if (type === 'doughnut') theme['cutout'] = '62%';
    }
    return theme as ChartOptions;
}

/** A filled line (an area) paints a faint tint of its series colour, so overlapping areas stay readable. */
function tintAreaFills(type: ChartType, data: ChartData): ChartData {
    if (type !== 'line') return data;
    return {
        ...data,
        datasets: data.datasets.map((ds) => {
            const d = ds as { fill?: unknown; backgroundColor?: unknown; borderColor?: unknown };
            const base = typeof d.borderColor === 'string' ? d.borderColor : d.backgroundColor;
            if (!d.fill || typeof base !== 'string') return ds;
            return { ...ds, backgroundColor: color(base).alpha(0.14).rgbString() } as typeof ds;
        }),
    };
}
