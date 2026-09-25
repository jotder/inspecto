import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { CHART_TONE } from 'app/inspecto/theme/chart-tokens';
import { NumberFormat } from '../number-format';
import { VizRenderComponent } from '../viz-render.component';
import { VizRenderOptions } from '../viz-types';
import { ProgressListComponent } from './progress-list.component';
import { PROGRESS_LIST_PLUGIN, rankProgress } from './progress-list.plugin';

const LABELS = ['a', 'b', 'c', 'd'];
const VALUES = [10, 40, 20, 30];

describe('Progress list — pure transforms', () => {
    it('ranks largest first and sizes each bar as a share of the largest value', () => {
        const m = rankProgress(LABELS, VALUES);
        expect(m.rows.map((r) => r.label)).toEqual(['b', 'd', 'c', 'a']);
        expect(m.rows.map((r) => r.pct)).toEqual([100, 75, 50, 25]);
        expect(m.max).toBe(40);
        expect(m.more).toBe(0);
    });

    it('keeps the top N and counts the rest, with 10 as the default limit', () => {
        expect(rankProgress(LABELS, VALUES, { limit: 2 })).toMatchObject({
            more: 2,
            rows: [{ label: 'b' }, { label: 'd' }],
        });
        const many = Array.from({ length: 14 }, (_, i) => `r${i}`);
        const m = rankProgress(
            many,
            many.map((_, i) => i),
        );
        expect(m.rows).toHaveLength(10);
        expect(m.more).toBe(4);
    });

    it('honours ascending order and an explicit max (bars clamp at 100 %)', () => {
        const m = rankProgress(LABELS, VALUES, { sort: 'asc', max: 20 });
        expect(m.rows.map((r) => r.label)).toEqual(['a', 'c', 'd', 'b']);
        expect(m.rows.map((r) => r.pct)).toEqual([50, 100, 100, 100]);
    });

    it('places the target tick and tones each row by the good direction', () => {
        const higher = rankProgress(LABELS, VALUES, { target: 25 });
        expect(higher.rows.map((r) => r.met)).toEqual([true, true, false, false]);
        expect(higher.rows[0].targetPct).toBe(62.5);
        const lower = rankProgress(LABELS, VALUES, { target: 25, better: 'lower' });
        expect(lower.rows.map((r) => r.met)).toEqual([false, false, true, true]);
        expect(rankProgress(LABELS, VALUES).rows[0]).not.toHaveProperty('met');
    });

    it('builds the bar chart query: one label dimension, one measure', () => {
        const spec = PROGRESS_LIST_PLUGIN.buildQuery(
            { x: [{ field: 'detector' }], y: [{ field: 'alerts', agg: 'count' }] },
            { datasetId: 'd', sourceName: 's' },
        );
        expect(spec.groupBy).toEqual(['detector']);
        expect(spec.measures).toHaveLength(1);
    });
});

function create(inputs: { format?: NumberFormat; target?: number; better?: 'higher' | 'lower'; limit?: number }) {
    TestBed.configureTestingModule({ imports: [ProgressListComponent] });
    const fixture = TestBed.createComponent(ProgressListComponent);
    fixture.componentRef.setInput('labels', LABELS);
    fixture.componentRef.setInput('values', VALUES);
    for (const k of ['format', 'target', 'better', 'limit'] as const)
        if (inputs[k] !== undefined) fixture.componentRef.setInput(k, inputs[k]);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
}

describe('ProgressListComponent', () => {
    it('renders an ordered list: label, formatted value and a bar per row', () => {
        const el = create({ format: { style: 'percent' } });
        const items = Array.from(el.querySelectorAll('ol > li'));
        expect(items).toHaveLength(4);
        expect(items[0].textContent).toContain('b');
        expect(items[0].querySelector('[data-testid="progress-value"]')?.textContent?.trim()).toBe('40 %');
        const bar = items[1].querySelector('[data-testid="progress-bar"]') as HTMLElement;
        expect(bar.style.width).toBe('75%');
        expect(bar.className).toContain('bg-primary');
        // Without a select callback the rows are not buttons.
        expect(el.querySelector('button')).toBeNull();
    });

    it('with a target: a tick per row, good / bad tones and the count in words', () => {
        const el = create({ target: 25, limit: 3 });
        expect(el.querySelectorAll('[data-testid="progress-target"]')).toHaveLength(3);
        const bars = Array.from(el.querySelectorAll('[data-testid="progress-bar"]')) as HTMLElement[];
        const hexToRgb = (hex: string) => {
            const n = parseInt(hex.slice(1), 16);
            return `rgb(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255})`;
        };
        expect(bars[0].style.background).toBe(hexToRgb(CHART_TONE.success));
        expect(bars[2].style.background).toBe(hexToRgb(CHART_TONE.error));
        expect(el.querySelector('li')?.textContent).toContain('on target');
        expect(el.querySelector('[data-testid="progress-footer"]')?.textContent?.trim()).toBe(
            'Target 25 — 2 of 3 on target · +1 more',
        );
    });

    it('renders with no a11y violations, target included', async () => {
        await expectNoA11yViolations(create({ target: 25 }));
    });
});

describe('Progress list in the render host', () => {
    it('clicking a row emits the drill event for its label', async () => {
        TestBed.configureTestingModule({ imports: [VizRenderComponent] });
        const fixture = TestBed.createComponent(VizRenderComponent);
        fixture.componentRef.setInput('plugin', PROGRESS_LIST_PLUGIN);
        fixture.componentRef.setInput('props', { labels: LABELS, series: [{ label: 'x', data: VALUES }] });
        fixture.componentRef.setInput('renderOptions', { progress: { limit: 3 } } satisfies VizRenderOptions);
        const clicked: string[] = [];
        fixture.componentInstance.categoryClick.subscribe((l) => clicked.push(l));
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        const buttons = Array.from(el.querySelectorAll('ol button')) as HTMLButtonElement[];
        expect(buttons).toHaveLength(3);
        buttons[1].click();
        expect(clicked).toEqual(['d']);
        expect(el.querySelector('[data-testid="progress-footer"]')?.textContent?.trim()).toBe('+1 more');
        await expectNoA11yViolations(el);
    });
});
