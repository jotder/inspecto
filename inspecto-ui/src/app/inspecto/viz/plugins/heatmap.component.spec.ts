import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { statusBadgeClasses } from 'app/inspecto/components/status-badge.component';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { HeatmapOptions } from '../heatmap';
import { VizRenderComponent } from '../viz-render.component';
import { HeatmapCellClick, HeatmapComponent, HeatmapData } from './heatmap.component';
import { HEATMAP_PLUGIN } from './heatmap.plugin';

const NUMERIC: HeatmapData = {
    rows: ['RA-C02', 'RA-C10'],
    columns: ['2026-09-01', '2026-09-02'],
    cells: [
        [1, 2500],
        [null, 40],
    ],
    rowLabel: 'Control',
    columnLabel: 'Event date',
    valueLabel: 'Breaks (total)',
};

const STATUS: HeatmapData = {
    ...NUMERIC,
    cells: [
        ['Pass', 'Fail'],
        ['Warning', 'Pass'],
    ],
    valueLabel: 'Status',
};

/** Every class of the status badge's tone pair is on the cell (Angular may reorder the class list). */
function expectTone(el: Element, word: string): void {
    for (const cls of statusBadgeClasses(word).split(' ')) expect(el.classList).toContain(cls);
}

function create(data: HeatmapData, options?: HeatmapOptions, extra: Record<string, unknown> = {}) {
    TestBed.configureTestingModule({ imports: [HeatmapComponent], providers: [provideNoopAnimations()] });
    const fixture = TestBed.createComponent(HeatmapComponent);
    fixture.componentRef.setInput('data', data);
    if (options) fixture.componentRef.setInput('options', options);
    for (const [k, v] of Object.entries(extra)) fixture.componentRef.setInput(k, v);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const cells = () => [...el.querySelectorAll<HTMLButtonElement>('[data-testid="heat-cell"]')];
    return { fixture, el, cells, c: fixture.componentInstance };
}

describe('HeatmapComponent', () => {
    it('renders a real table: column headers, row headers, a caption summarising the data', async () => {
        const { el, cells } = create(NUMERIC);
        const colHeads = [...el.querySelectorAll('thead th[scope="col"]')].map((t) => t.textContent?.trim());
        // The corner names the row dimension; ISO-date columns read like a calendar (date-labels.ts).
        expect(colHeads).toEqual(['Control', '1 Sep', '2 Sep']);
        expect(el.querySelector('thead th[title="1 Sep 2026"]')).not.toBeNull();
        expect([...el.querySelectorAll('tbody th[scope="row"]')].map((t) => t.textContent?.trim())).toEqual([
            'RA-C02',
            'RA-C10',
        ]);
        expect(el.querySelector('caption')?.textContent?.trim()).toBe(
            'Heatmap of Breaks (total) by Control and Event date: 2 rows by 2 columns; values from 1 to 2,500.',
        );
        // Values print through formatNumber; the accessible name leads with the value, then where it is.
        expect(cells().map((b) => b.textContent?.trim())).toEqual(['1', '2,500', '40']);
        expect(cells()[1].getAttribute('aria-label')).toBe('2,500 — Control RA-C02, Event date 2 Sep');
        await expectNoA11yViolations(el);
    });

    it('a missing cell is empty — no button, no colour, but announced as having no value', () => {
        const { el, cells } = create(NUMERIC);
        expect(cells()).toHaveLength(3);
        const empty = el.querySelectorAll('tbody tr')[1].querySelectorAll('td')[0];
        expect(empty.querySelector('button')).toBeNull();
        expect(empty.textContent?.trim()).toBe('No value — Control RA-C10, Event date 1 Sep');
    });

    it('sequential cells are shaded light → strong from design tokens, with on-colour ink on strong fills', () => {
        const { cells, el } = create(NUMERIC);
        const [low, high] = cells();
        expect(low.style.background).toContain('var(--gamma-primary-rgb)');
        expect(low.className).not.toContain('text-on-primary');
        expect(high.className).toContain('text-on-primary');
        const legend = [...el.querySelectorAll('[data-testid="heat-legend"] span')].map((x) => x.textContent?.trim());
        expect(legend).toEqual(['1', '', '2,500']); // low, the ramp swatch, high
    });

    it('status scale: cells take the status badge tone and the legend names the words present', async () => {
        const { cells, el } = create(STATUS, { scale: 'status' });
        const [pass, fail, warn] = cells();
        expectTone(pass, 'Pass');
        expectTone(fail, 'Fail');
        expectTone(warn, 'Warning');
        expect(fail.style.background).toBe('');
        expect(fail.textContent?.trim()).toBe('Fail'); // the word is printed, so tone is not the only signal
        expect(el.querySelector('caption')?.textContent).toContain('1 Fail, 2 Pass, 1 Warning');
        expect(
            [...el.querySelectorAll('[data-testid="heat-legend"] inspecto-status-badge')].map((b) =>
                b.textContent?.trim(),
            ),
        ).toEqual(['Fail', 'Pass', 'Warning']);
        await expectNoA11yViolations(el);
    });

    it('status scale on numbers colours good / bad against the target', () => {
        const { cells, el } = create(NUMERIC, { scale: 'status' }, { target: 100, better: 'lower' });
        expectTone(cells()[0], 'PASS'); // 1 ≤ 100
        expectTone(cells()[1], 'FAIL'); // 2,500 > 100
        expect(el.querySelector('[data-testid="heat-legend"]')?.textContent).toContain('Target 100');
    });

    it('a wide matrix shrinks to swatches: the value leaves the face but stays in the name and tooltip', () => {
        const columns = Array.from({ length: 14 }, (_, i) => `2026-09-${String(i + 1).padStart(2, '0')}`);
        const { cells } = create({ ...NUMERIC, rows: ['A'], columns, cells: [columns.map((_, i) => i)] });
        const first = cells()[3];
        expect(first.querySelector('span')?.classList).toContain('sr-only');
        expect(first.getAttribute('aria-label')).toBe('3 — Control A, Event date 4 Sep');
        expect(first.getAttribute('title')).toBe('A · 4 Sep: 3');
    });

    it('clicking a cell emits its raw row and column values', () => {
        const { cells, c } = create(NUMERIC);
        let clicked: HeatmapCellClick | undefined;
        c.cellClick.subscribe((v) => (clicked = v));
        cells()[1].click();
        expect(clicked).toEqual({ row: 'RA-C02', column: '2026-09-02' });
    });
});

describe('VizRenderComponent — heatmap', () => {
    it('renders the heatmap and re-emits a cell click as its raw row AND column (the two-field drill)', () => {
        TestBed.configureTestingModule({
            imports: [VizRenderComponent],
            providers: [
                provideNoopAnimations(),
                provideRouter([]),
                { provide: GammaConfigService, useValue: { config$: of({ scheme: 'light' }) } },
            ],
        });
        const fixture = TestBed.createComponent(VizRenderComponent);
        fixture.componentRef.setInput('plugin', HEATMAP_PLUGIN);
        fixture.componentRef.setInput('props', { labels: NUMERIC.rows, series: [], heatmap: NUMERIC });
        fixture.componentRef.setInput('renderOptions', { heatmap: { scale: 'diverging', midpoint: 20 } });
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        const cells = [...el.querySelectorAll<HTMLButtonElement>('[data-testid="heat-cell"]')];
        expect(cells[0].style.background).toContain('var(--gamma-warn-rgb)'); // 1 is below the midpoint 20
        let emitted: { row: string; column: string } | undefined;
        let category: string | undefined;
        fixture.componentInstance.cellClick.subscribe((v) => (emitted = v));
        fixture.componentInstance.categoryClick.subscribe((v) => (category = v));
        cells[2].click();
        expect(emitted).toEqual({ row: 'RA-C10', column: '2026-09-02' });
        expect(category).toBeUndefined();
    });
});
