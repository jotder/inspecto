import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { of, throwError } from 'rxjs';
import { GammaConfigService } from '@gamma/services/config';
import { ComponentsService } from 'app/inspecto/api/components.service';
import { BAR_PLUGIN, LINE_PLUGIN, PIE_PLUGIN } from './plugins';
import { COMBO_PLUGIN } from './plugins/combo.plugin';
import { VizPlugin, VizProps, VizRenderOptions } from './viz-types';
import { VizRenderComponent } from './viz-render.component';

/** BI-INSIGHTS-1 I-2: the render host resolves a Widget's Chart Markers and hands them to the chart. */
function create(
    plugin: VizPlugin,
    props: VizProps,
    options: VizRenderOptions,
    components?: Partial<ComponentsService>,
) {
    TestBed.configureTestingModule({
        imports: [VizRenderComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'light' }) } },
            ...(components ? [{ provide: ComponentsService, useValue: components }] : []),
        ],
    });
    const fixture = TestBed.createComponent(VizRenderComponent);
    fixture.componentRef.setInput('plugin', plugin);
    fixture.componentRef.setInput('props', props);
    fixture.componentRef.setInput('renderOptions', options);
    fixture.detectChanges();
    return fixture;
}

const PROPS: VizProps = { labels: ['Jan', 'Feb', 'Mar'], series: [{ label: 'sum(revenue)', data: [10, 20, 60] }] };

describe('VizRenderComponent — Chart Markers', () => {
    it('resolves the markers on a line chart and states them in the canvas text alternative', () => {
        const f = create(LINE_PLUGIN, PROPS, { markers: [{ kind: 'average' }, { kind: 'constant', value: 50 }] });
        expect(f.componentInstance.markers().map((m) => m.text)).toEqual(['Average 30', 'Reference 50']);
        const canvas = f.nativeElement.querySelector('canvas') as HTMLCanvasElement;
        expect(canvas.getAttribute('aria-label')).toContain('Markers: Average 30; Reference 50.');
    });

    it('reads the category totals when the series are stacked', () => {
        const props: VizProps = {
            labels: ['a', 'b'],
            series: [
                { label: 'x', data: [1, 3] },
                { label: 'y', data: [1, 5] },
            ],
        };
        const f = create(BAR_PLUGIN, props, { stacked: true, markers: [{ kind: 'average' }] });
        expect(f.componentInstance.markers()[0]).toMatchObject({ value: 5 });
    });

    it("reads only a Combo's bars while its lines sit on the right axis", () => {
        const props: VizProps = {
            labels: ['a', 'b'],
            series: [
                { label: 'bars', data: [10, 30], kind: 'bar' },
                { label: 'rate', data: [0.1, 0.9], kind: 'line' },
            ],
        };
        const f = create(COMBO_PLUGIN, props, { markers: [{ kind: 'average' }] });
        expect(f.componentInstance.markers()[0]).toMatchObject({ value: 20 });
    });

    it("stacks a Combo's bars and lines apart when both sit on the left axis (as Chart.js does)", () => {
        const props: VizProps = {
            labels: ['a', 'b'],
            series: [
                { label: 'bars', data: [10, 30], kind: 'bar' },
                { label: 'more', data: [10, 30], kind: 'bar' },
                { label: 'rate', data: [1, 3], kind: 'line' },
            ],
        };
        const f = create(COMBO_PLUGIN, props, {
            stacked: true,
            combo: { secondaryAxis: false },
            markers: [{ kind: 'percentile', p: 99 }],
        });
        // Bar totals 20, 60 and line totals 1, 3 — never 21 / 63, which no mark reaches.
        const m = f.componentInstance.markers()[0];
        expect(m.type === 'line' ? m.value : NaN).toBeCloseTo(58.8, 6);
    });

    it('ignores markers on a type that does not draw them (pie)', () => {
        const f = create(PIE_PLUGIN, PROPS, { markers: [{ kind: 'constant', value: 5 }] });
        expect(f.componentInstance.markers()).toEqual([]);
    });

    it("reads the bound KPI's definition for a target line and its RAG bands", () => {
        const get = vi.fn(() =>
            of({
                type: 'kpi',
                name: 'sla',
                ref: 'kpi/sla',
                content: { direction: 'up', target: 95, bands: { green: 95, amber: 90 } },
            }),
        );
        const f = create(
            LINE_PLUGIN,
            PROPS,
            { kpi: { kpiId: 'sla', target: 1 }, markers: [{ kind: 'target' }, { kind: 'rag' }] },
            { get } as unknown as Partial<ComponentsService>,
        );
        TestBed.tick();
        expect(get).toHaveBeenCalledWith('kpi', 'sla');
        expect(f.componentInstance.markers().map((m) => m.text)).toEqual([
            'Target 95',
            'Red below 90',
            'Amber 90–95',
            'Green at or above 95',
        ]);
    });

    it("falls back to the Widget's own target when the KPI read fails, and draws no bands", () => {
        const get = vi.fn(() => throwError(() => new Error('403')));
        const f = create(
            LINE_PLUGIN,
            PROPS,
            { kpi: { kpiId: 'sla', target: 7 }, markers: [{ kind: 'target' }, { kind: 'rag' }] },
            { get } as unknown as Partial<ComponentsService>,
        );
        TestBed.tick();
        expect(f.componentInstance.markers().map((m) => m.text)).toEqual(['Target 7']);
    });

    it('never reads a KPI when no marker needs it', () => {
        const get = vi.fn();
        create(LINE_PLUGIN, PROPS, { kpi: { kpiId: 'sla' }, markers: [{ kind: 'average' }] }, {
            get,
        } as unknown as Partial<ComponentsService>);
        TestBed.tick();
        expect(get).not.toHaveBeenCalled();
    });
});
