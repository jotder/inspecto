import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { of } from 'rxjs';
import { GammaConfigService } from '@gamma/services/config';
import { LINE_PLUGIN } from './plugins';
import { OverlaySummary } from './chart-overlays';
import { VizProps, VizRenderOptions } from './viz-types';
import { VizRenderComponent } from './viz-render.component';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';

/** BI-INSIGHTS-1 I-11 / I-7: the render host turns the server's overlay cells into Chart Markers, a caption and a slider. */
function create(options: VizRenderOptions) {
    TestBed.configureTestingModule({
        imports: [VizRenderComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'light' }) } },
        ],
    });
    const fixture = TestBed.createComponent(VizRenderComponent);
    fixture.componentRef.setInput('plugin', LINE_PLUGIN);
    fixture.componentRef.setInput('props', PROPS);
    fixture.componentRef.setInput('renderOptions', options);
    fixture.componentRef.setInput('overlaySummary', SUMMARY);
    fixture.detectChanges();
    return fixture;
}

const cell = (fc: number | null, z: number | null) => ({
    fc,
    lo: fc == null ? null : fc - 2,
    hi: fc == null ? null : fc + 2,
    expected: z == null ? null : 10,
    z,
});

const PROPS: VizProps = {
    labels: ['2026-01-01', '2026-01-02', '2026-01-03'],
    series: [{ label: 'sum(amount)', data: [10, 90, NaN], overlay: [cell(null, 0.1), cell(null, 7), cell(12, null)] }],
};

const SUMMARY: OverlaySummary[] = [
    {
        overlay: 'forecast',
        of: 'sum_amount',
        series: {},
        period: 7,
        periodSource: 'calendar',
        horizon: 1,
        level: 0.8,
        backtest: { reason: 'no bucket after the first two seasons to test against' },
    },
    { overlay: 'anomaly', of: 'sum_amount', series: {}, seasonality: 'none', scored: 2 },
];

const OVERLAY: VizRenderOptions['overlay'] = { forecast: {}, anomaly: {} };

const text = (el: HTMLElement, id: string) =>
    Array.from(el.querySelectorAll(`[data-testid="${id}"]`)).map((n) => n.textContent?.trim());

describe('VizRenderComponent — forecast band and anomaly markers', () => {
    it('draws the band and the flagged point, with the caption and the sensitivity slider', async () => {
        const f = create({ overlay: OVERLAY });
        expect(f.componentInstance.markers().map((m) => m.type)).toEqual(['interval', 'points']);
        const el = f.nativeElement as HTMLElement;
        expect(text(el, 'overlay-note')[0]).toContain('Forecast of the next 1 day');
        expect(text(el, 'overlay-note')[1]).toContain('1 unusual value at sensitivity 3 of 5');
        expect(el.querySelector('[data-testid="anomaly-sensitivity"] input')).toBeTruthy();
        await expectNoA11yViolations(el);
    });

    it('draws neither on a stacked chart and says why', () => {
        const f = create({ overlay: OVERLAY, stacked: true });
        expect(f.componentInstance.markers()).toEqual([]);
        const el = f.nativeElement as HTMLElement;
        expect(text(el, 'overlay-note')).toEqual([
            'The forecast band and anomaly markers are not drawn on a stacked or sorted chart.',
        ]);
        expect(el.querySelector('[data-testid="anomaly-sensitivity"]')).toBeNull();
    });

    it('draws neither on a value-sorted chart', () => {
        const f = create({ overlay: OVERLAY, sort: 'desc' });
        expect(f.componentInstance.markers().filter((m) => m.type === 'interval' || m.type === 'points')).toEqual([]);
    });
});
