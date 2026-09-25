import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { of } from 'rxjs';
import { GammaConfigService } from '@gamma/services/config';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { CHART_TONE } from 'app/inspecto/theme/chart-tokens';
import { VizRenderComponent } from '../viz-render.component';
import { VizPlugin, VizProps, VizRenderOptions } from '../viz-types';
import { COMBO_PLUGIN, WATERFALL_PLUGIN } from './index';

/** A loose view of the Chart.js options, so a spec can walk into them without Chart.js's deep generics. */
type Loose = Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any -- spec-only walk

function create(plugin: VizPlugin, props: VizProps, renderOptions?: VizRenderOptions) {
    TestBed.configureTestingModule({
        imports: [VizRenderComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'light' }) } },
        ],
    });
    const fixture = TestBed.createComponent(VizRenderComponent);
    fixture.componentRef.setInput('plugin', plugin);
    fixture.componentRef.setInput('props', props);
    if (renderOptions) fixture.componentRef.setInput('renderOptions', renderOptions);
    return fixture;
}

// "Opening exposure → new → recovered → written off → closing" — exposure, so LOWER is better.
const EXPOSURE: VizProps = {
    labels: ['New', 'Opening exposure', 'Recovered', '', 'Written off'],
    series: [{ label: 'Change', data: [400, 2000, -600, 5, -300] }],
};

describe('Waterfall render', () => {
    it('draws floating bars in step order: opening first, a computed closing total last, generic sort/limit ignored', () => {
        const c = create(WATERFALL_PLUGIN, EXPOSURE, {
            waterfall: { start: 'Opening exposure', totalLabel: 'Closing exposure' },
            hideBlank: true,
            sort: 'desc',
            limit: 2,
        }).componentInstance;
        const data = c.chartData()!;
        expect(data.labels).toEqual(['Opening exposure', 'New', 'Recovered', 'Written off', 'Closing exposure']);
        expect(data.datasets[0].data).toEqual([
            [0, 2000],
            [2000, 2400],
            [1800, 2400],
            [1500, 1800],
            [0, 1500],
        ]);
    });

    it('with better=lower tones an increase error and a decrease success', () => {
        const c = create(WATERFALL_PLUGIN, EXPOSURE, {
            waterfall: { start: 'Opening exposure' },
            kpi: { better: 'lower' },
        }).componentInstance;
        const colours = c.chartData()!.datasets[0].backgroundColor as string[];
        expect(colours[1]).toBe(CHART_TONE.error); // New (+400)
        expect(colours[2]).toBe(CHART_TONE.success); // Recovered (−600)
    });

    it('names the step kinds in the legend, and the tooltip gives the change and the running total', () => {
        const c = create(WATERFALL_PLUGIN, EXPOSURE, {
            waterfall: { start: 'Opening exposure' },
            format: { style: 'currency', currency: 'SAR' },
        }).componentInstance;
        const o = c.chartJsOptions() as Loose;
        const chart = { options: { plugins: { legend: { labels: { color: 'MUTED' } } } } };
        const legend = o['plugins'].legend.labels.generateLabels(chart);
        expect(legend.map((l: Loose) => l['text'])).toEqual(['Opening', 'Increase', 'Decrease', 'Total']);
        expect(legend[0].fontColor).toBe('MUTED');
        const tooltip = o['plugins'].tooltip;
        expect(tooltip.filter({ datasetIndex: 1 })).toBe(false); // the connector line never gets a tooltip
        expect(tooltip.callbacks.title([{ dataIndex: 3 }])).toBe('(blank)');
        expect(tooltip.callbacks.label({ dataIndex: 2 })).toEqual([
            'Decrease: −SAR 600.00',
            'Running total: SAR 1,800.00',
        ]);
        expect(tooltip.callbacks.label({ dataIndex: 0 })).toBe('Opening: SAR 2,000.00');
        expect(o['scales'].y.stacked).toBe(false);
    });

    it('a click on a step emits its raw category; the computed total is no category and emits nothing', () => {
        const c = create(WATERFALL_PLUGIN, EXPOSURE, { waterfall: { start: 'Opening exposure' } }).componentInstance;
        const emitted: string[] = [];
        c.categoryClick.subscribe((v) => emitted.push(v));
        c.onElementClick(0); // Opening exposure (moved first from row 2)
        c.onElementClick(3); // the blank step — drill-down filters on the real, empty value
        c.onElementClick(5); // Total
        expect(emitted).toEqual(['Opening exposure', '']);
    });

    it('gives the canvas a step-by-step text alternative, with no a11y violations', async () => {
        const fixture = create(
            WATERFALL_PLUGIN,
            { labels: ['Billed', 'Leak'], series: [{ label: 'Change', data: [1000, -50] }] },
            { waterfall: { start: 'Billed' } },
        );
        fixture.detectChanges();
        const canvas = fixture.nativeElement.querySelector('canvas') as HTMLCanvasElement;
        expect(canvas.getAttribute('aria-label')).toBe(
            'Waterfall chart. Billed: 1,000; Leak: decrease −50, running total 950; Total: 950.',
        );
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

// "Alerts per week (bars) + precision % (line, right axis)".
const ALERTS: VizProps = {
    labels: ['2026-09-01', '2026-09-08'],
    series: [
        { label: 'Alerts', data: [12, 30], kind: 'bar' },
        { label: 'Precision (avg)', data: [81.5, 64], kind: 'line' },
    ],
};

describe('Combo render', () => {
    it('adds a right axis for the line in format2 with its own title, and lists both measures in one tooltip', () => {
        const c = create(COMBO_PLUGIN, ALERTS, {
            axis: { yTitle: 'Alerts', y2Title: 'Precision' },
            format2: { style: 'percent' },
        }).componentInstance;
        const o = c.chartJsOptions() as Loose;
        expect(o['scales'].y2.position).toBe('right');
        expect(o['scales'].y2.title).toEqual({ display: true, text: 'Precision' });
        expect(o['scales'].y2.ticks.callback(80)).toBe('80 %');
        expect(o['scales'].y.title).toEqual({ display: true, text: 'Alerts' });
        expect(o['plugins'].tooltip.mode).toBe('index');
        const label = o['plugins'].tooltip.callbacks.label;
        expect(label({ dataset: { label: 'Precision (avg)', type: 'line' }, parsed: { y: 81.5 } })).toBe(
            'Precision (avg): 81.5 %',
        );
        expect(label({ dataset: { label: 'Alerts', type: 'bar' }, parsed: { y: 12 } })).toBe('Alerts: 12');
        const types = c.chartData()!.datasets.map((d) => (d as Loose)['type']);
        expect(types).toEqual(['bar', 'line']);
    });

    it('draws no right axis when secondaryAxis is false', () => {
        const c = create(COMBO_PLUGIN, ALERTS, { combo: { secondaryAxis: false } }).componentInstance;
        expect((c.chartJsOptions() as Loose)['scales'].y2).toBeUndefined();
    });

    it('honours the generic sort on the first bar measure, and a click emits the category', () => {
        const c = create(COMBO_PLUGIN, ALERTS, { sort: 'desc' }).componentInstance;
        expect(c.chartData()!.datasets[1].data).toEqual([64, 81.5]);
        let emitted: string | undefined;
        c.categoryClick.subscribe((v) => (emitted = v));
        c.onElementClick(0);
        expect(emitted).toBe('2026-09-08');
    });

    it('states which measures are bars and which the line, with no a11y violations', async () => {
        const fixture = create(COMBO_PLUGIN, ALERTS);
        fixture.detectChanges();
        const canvas = fixture.nativeElement.querySelector('canvas') as HTMLCanvasElement;
        expect(canvas.getAttribute('aria-label')).toContain('bars: Alerts; line: Precision (avg) (right axis)');
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
