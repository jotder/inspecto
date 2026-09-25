import { afterEach, describe, expect, it, vi } from 'vitest';
import { ChartData } from 'chart.js';
import { color } from 'chart.js/helpers';
import { CHART_TONE, canvasTheme } from './chart-tokens';
import { ChartThemeTokens, chartThemeOptions, resolveChartTokens, themedChartConfig } from './chart-theme';

const TOKENS: ChartThemeTokens = {
    font: 'Inter var',
    text: 'TEXT',
    muted: 'MUTED',
    grid: 'GRID',
    surface: 'SURFACE',
    border: 'BORDER',
};

const ONE: ChartData = { labels: ['a', 'b'], datasets: [{ label: 'Count', data: [1, 2] }] };
const TWO: ChartData = { labels: ['a', 'b'], datasets: [{ data: [1, 2] }, { data: [3, 4] }] };

/** A loose view of the merged options, so a spec can walk into it without Chart.js's deep generics. */
type Loose = Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any -- spec-only walk

function opts(type: 'bar' | 'line' | 'doughnut', data: ChartData, options: object = {}): Loose {
    return themedChartConfig(type, data, options, TOKENS).options as Loose;
}

describe('resolveChartTokens', () => {
    afterEach(() => vi.restoreAllMocks());

    it('reads the --gamma-* design tokens and the font family from the computed style', () => {
        const vars: Record<string, string> = {
            '--gamma-text-default': ' ink ',
            '--gamma-text-secondary': 'slate',
            '--gamma-divider': 'hairline',
            '--gamma-bg-card': 'paper',
            '--gamma-border': 'edge',
        };
        vi.spyOn(window, 'getComputedStyle').mockReturnValue({
            fontFamily: '"Inter var", sans-serif',
            getPropertyValue: (name: string) => vars[name] ?? '',
        } as unknown as CSSStyleDeclaration);

        expect(resolveChartTokens(document.body)).toEqual({
            font: '"Inter var", sans-serif',
            text: 'ink',
            muted: 'slate',
            grid: 'hairline',
            surface: 'paper',
            border: 'edge',
        });
    });

    it('falls back to the scheme canvas tokens when a variable resolves blank (dark scheme on <body>)', () => {
        vi.spyOn(window, 'getComputedStyle').mockReturnValue({
            fontFamily: '',
            getPropertyValue: () => '',
        } as unknown as CSSStyleDeclaration);
        const el = document.createElement('div');
        el.classList.add('dark');
        const dark = canvasTheme(true);

        const t = resolveChartTokens(el);
        expect(t.text).toBe(dark.fg);
        expect(t.grid).toBe(dark.grid);
        expect(t.surface).toBe(dark.surface);
        expect(t.font).not.toBe('');
    });
});

describe('themedChartConfig', () => {
    it('hides the legend for a single series and shows it for two or more', () => {
        expect(opts('bar', ONE)['plugins'].legend.display).toBe(false);
        expect(opts('bar', TWO)['plugins'].legend.display).toBe(true);
        // a donut's slices always need their key, even though it is one dataset
        expect(opts('doughnut', ONE)['plugins'].legend.display).toBe(true);
    });

    it('respects an explicit legend override and keeps the themed label styling under it', () => {
        const shown = opts('bar', ONE, { plugins: { legend: { display: true, position: 'bottom' } } });
        expect(shown['plugins'].legend.display).toBe(true);
        expect(shown['plugins'].legend.position).toBe('bottom');
        expect(shown['plugins'].legend.labels.color).toBe('MUTED');
        expect(shown['plugins'].legend.labels.usePointStyle).toBe(true);

        expect(opts('bar', TWO, { plugins: { legend: { display: false } } })['plugins'].legend.display).toBe(false);
    });

    it('caps bar thickness, rounds bar tops and draws gridlines on the value axis only', () => {
        const o = opts('bar', ONE);
        expect(o['datasets'].bar.maxBarThickness).toBeGreaterThan(0);
        expect(o['datasets'].bar.borderRadius).toBeGreaterThan(0);
        expect(o['scales'].x.grid.display).toBe(false);
        expect(o['scales'].y.grid.display).toBe(true);
        expect(o['scales'].y.grid.color).toBe('GRID');
        expect(o['scales'].y.border.display).toBe(false);
        expect(o['scales'].x.ticks.color).toBe('MUTED');
        expect(o['scales'].x.ticks.font.family).toBe('Inter var');
    });

    it('moves the gridlines to x for a horizontal (indexAxis y) bar, and squares stacked segments', () => {
        const o = opts('bar', TWO, { indexAxis: 'y', scales: { x: { stacked: true }, y: { stacked: true } } });
        expect(o['scales'].x.grid.display).toBe(true);
        expect(o['scales'].y.grid.display).toBe(false);
        expect(o['scales'].x.stacked).toBe(true);
        expect(o['datasets'].bar.borderRadius).toBe(0);
    });

    it("keeps the caller's axis options, and an undefined title does not erase the themed title style", () => {
        const tick = (v: number | string) => `#${v}`;
        const o = opts('bar', ONE, {
            scales: {
                y: { ticks: { callback: tick }, title: undefined },
                x: { title: { display: true, text: 'Day' } },
            },
        });
        expect(o['scales'].y.ticks.callback).toBe(tick);
        expect(o['scales'].y.ticks.color).toBe('MUTED');
        expect(o['scales'].y.title.color).toBe('MUTED');
        expect(o['scales'].x.title.text).toBe('Day');
    });

    it('styles the tooltip as a card from the surface/border/text tokens', () => {
        const t = opts('bar', ONE)['plugins'].tooltip;
        expect(t).toMatchObject({
            backgroundColor: 'SURFACE',
            borderColor: 'BORDER',
            titleColor: 'TEXT',
            bodyColor: 'TEXT',
        });
        expect(t.borderWidth).toBe(1);
        expect(t.cornerRadius).toBeGreaterThan(0);
    });

    it('gives a donut a cutout and surface-coloured segment borders, and lets the caller override the cutout', () => {
        expect(opts('doughnut', ONE)['cutout']).toBe('62%');
        expect(opts('doughnut', ONE)['elements'].arc.borderColor).toBe('SURFACE');
        expect(opts('doughnut', ONE, { cutout: '70%' })['cutout']).toBe('70%');
        expect(opts('doughnut', ONE)['scales']).toBeUndefined();
    });

    it('draws an area fill as a faint tint of the series colour without touching the input data', () => {
        const [filled, plain] = [CHART_TONE.info, CHART_TONE.error];
        const data: ChartData = {
            labels: ['a'],
            datasets: [
                { data: [1], borderColor: filled, backgroundColor: filled, fill: true },
                { data: [2], borderColor: plain, backgroundColor: plain, fill: false },
            ],
        };
        const out = themedChartConfig('line', data, {}, TOKENS).data.datasets;
        expect(out[0].backgroundColor).toBe(color(filled).alpha(0.14).rgbString());
        expect(out[1].backgroundColor).toBe(plain);
        expect(data.datasets[0].backgroundColor).toBe(filled);
    });

    it('shows line points on hover only and lists every series in the tooltip', () => {
        const o = chartThemeOptions('line', TWO, {}, TOKENS) as Loose;
        expect(o['datasets'].line.pointRadius).toBe(0);
        expect(o['datasets'].line.pointHoverRadius).toBeGreaterThan(0);
        expect(o['plugins'].tooltip.mode).toBe('index');
    });
});
