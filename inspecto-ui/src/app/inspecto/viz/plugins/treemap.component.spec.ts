import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { TreemapRow } from '../treemap-layout';
import { TreemapChannel, TreemapComponent } from './treemap.component';

const TWO_LEVEL: TreemapRow[] = [
    { group: 'SIM box', subgroup: 'Online', value: 600000 },
    { group: 'SIM box', subgroup: 'Retail', value: 300000 },
    { group: 'IRSF', subgroup: 'Roaming', value: 500000 },
    { group: 'Wangiri', subgroup: 'Online', value: 100000 },
    { group: 'Wangiri', subgroup: 'Retail', value: -2000 },
];

/** jsdom has no layout, so the spec gives the box its size the way the ResizeObserver would. */
function create(rows: TreemapRow[], opts: { limit?: number; size?: { w: number; h: number } } = {}) {
    TestBed.configureTestingModule({ imports: [TreemapComponent] });
    const fixture = TestBed.createComponent(TreemapComponent);
    const picks: [TreemapChannel, string, string | undefined][] = [];
    fixture.componentRef.setInput('rows', rows);
    fixture.componentRef.setInput('format', { style: 'currency', currency: 'SAR', compact: true });
    fixture.componentRef.setInput('select', (c: TreemapChannel, v: string, g?: string) => picks.push([c, v, g]));
    if (opts.limit != null) fixture.componentRef.setInput('limit', opts.limit);
    fixture.detectChanges();
    fixture.componentInstance.size.set(opts.size ?? { w: 800, h: 400 });
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement, picks };
}

function cells(el: HTMLElement): HTMLButtonElement[] {
    return Array.from(el.querySelectorAll<HTMLButtonElement>('button.tm-cell'));
}

describe('TreemapComponent', () => {
    it('draws a focusable, fully-labelled button per group frame and subgroup, tinted from its group colour', () => {
        const { el } = create(TWO_LEVEL);
        const all = cells(el);
        // 3 group frames + 4 subgroups (the negative Retail row is excluded).
        expect(all.length).toBe(7);
        expect(all.every((b) => b.type === 'button' && b.tabIndex === 0)).toBe(true);
        const online = all.find((b) => b.getAttribute('aria-label')?.startsWith('Online in SIM box'))!;
        expect(online.getAttribute('aria-label')).toBe('Online in SIM box: SAR 600K, 40.0 % of total');
        expect(online.title).toBe(online.getAttribute('aria-label'));
        // A subgroup is a tint of its group, mixed toward the card colour (so it follows the theme).
        expect(online.getAttribute('style')).toContain('color-mix');
        expect(online.getAttribute('style')).toContain('--gamma-bg-card');
    });

    it('labels a big cell with name, value and share; the group frame carries its name in a header band', () => {
        const { el } = create(TWO_LEVEL);
        const online = cells(el).find((b) => b.getAttribute('aria-label')?.startsWith('Online in SIM box'))!;
        expect(online.textContent).toContain('Online');
        expect(online.textContent).toContain('SAR 600K');
        expect(online.textContent).toContain('40.0 %');
        const frame = cells(el).find((b) => b.getAttribute('aria-label')?.startsWith('SIM box:'))!;
        expect(frame.querySelector('.tm-header')?.textContent).toContain('SIM box');
    });

    it('hides the visible label on a cell too small to hold it, but keeps the full text as its name', () => {
        const { el } = create(
            [
                { group: 'Big', value: 1000 },
                { group: 'Tiny', value: 1 },
            ],
            { size: { w: 300, h: 150 } },
        );
        const tiny = cells(el).find((b) => b.getAttribute('aria-label')?.startsWith('Tiny'))!;
        expect(tiny.textContent?.trim()).toBe('');
        expect(tiny.getAttribute('aria-label')).toContain('0.1 % of total');
    });

    it('states the data as a text alternative — total, largest items — and says what was left out', () => {
        const { el } = create(TWO_LEVEL);
        const box = el.querySelector('[data-testid="treemap"]')!;
        expect(box.getAttribute('role')).toBe('group');
        const summary = box.getAttribute('aria-label')!;
        expect(summary).toContain('Treemap, two levels: 3 groups totalling SAR 1.5M');
        expect(summary).toContain('Largest: Online (SIM box) SAR 600K (40.0 %); Roaming (IRSF) SAR 500K (33.3 %)');
        expect(summary).toContain('1 item at zero or below is not shown.');
        expect(el.querySelector('[data-testid="treemap-excluded"]')?.textContent?.trim()).toBe(
            '1 item at zero or below is not shown',
        );
    });

    it('a group click reports its group; a subgroup click its subgroup AND parent group — raw values, a blank stays blank', () => {
        const { el, picks } = create([...TWO_LEVEL, { group: '', subgroup: 'Dealer', value: 50000 }]);
        cells(el)
            .find((b) => b.getAttribute('aria-label')?.startsWith('IRSF:'))!
            .click();
        cells(el)
            .find((b) => b.getAttribute('aria-label')?.startsWith('Retail in SIM box'))!
            .click();
        cells(el)
            .find((b) => b.getAttribute('aria-label')?.startsWith('(blank):'))!
            .click();
        cells(el)
            .find((b) => b.getAttribute('aria-label')?.startsWith('Dealer in (blank)'))!
            .click();
        expect(picks).toEqual([
            ['group', 'IRSF', undefined],
            ['subgroup', 'Retail', 'SIM box'],
            ['group', '', undefined],
            ['subgroup', 'Dealer', ''],
        ]);
    });

    it('folds past options.treemap.limit into a neutral "Other" that names its count and never drills', () => {
        const rows: TreemapRow[] = Array.from({ length: 6 }, (_, i) => ({ group: `Region ${i}`, value: 60 - i * 10 }));
        const { el, picks } = create(rows, { limit: 3 });
        const all = cells(el);
        expect(all.length).toBe(3);
        const other = all.find((b) => b.getAttribute('aria-label')?.startsWith('Other'))!;
        expect(other.getAttribute('aria-label')).toContain('Other (4 more groups)');
        expect(other.getAttribute('aria-disabled')).toBe('true');
        expect(other.getAttribute('style')).toContain('--gamma-text-secondary');
        other.click();
        expect(picks).toEqual([]);
    });

    it('recomputes the layout when the container resizes (ResizeObserver wired to the box, disconnected on destroy)', () => {
        let callback: (() => void) | undefined;
        const observe = vi.fn();
        const disconnect = vi.fn();
        vi.stubGlobal(
            'ResizeObserver',
            class {
                constructor(cb: () => void) {
                    callback = cb;
                }
                observe = observe;
                disconnect = disconnect;
                unobserve = vi.fn();
            },
        );
        try {
            TestBed.configureTestingModule({ imports: [TreemapComponent] });
            const fixture = TestBed.createComponent(TreemapComponent);
            fixture.componentRef.setInput('rows', [
                { group: 'a', value: 1 },
                { group: 'b', value: 1 },
            ]);
            fixture.detectChanges();
            const box = fixture.nativeElement.querySelector('[data-testid="treemap"]') as HTMLElement;
            expect(observe).toHaveBeenCalledWith(box);
            // jsdom measures 0 × 0: nothing is laid out yet.
            expect(fixture.componentInstance.cells()).toEqual([]);
            Object.defineProperty(box, 'clientWidth', { configurable: true, value: 400 });
            Object.defineProperty(box, 'clientHeight', { configurable: true, value: 100 });
            callback?.();
            fixture.detectChanges();
            const laid = fixture.componentInstance.cells();
            expect(laid.map((c) => [c.w, c.h])).toEqual([
                [200, 100],
                [200, 100],
            ]);
            fixture.destroy();
            expect(disconnect).toHaveBeenCalled();
        } finally {
            vi.unstubAllGlobals();
        }
    });

    it('shows the shared empty state when nothing is left to draw, saying why', () => {
        const { el } = create([{ group: 'a', value: 0 }]);
        expect(cells(el).length).toBe(0);
        expect(el.querySelector('inspecto-empty-state')?.textContent).toContain('1 item at zero or below is not shown');
    });

    it('counts several left-out items in the plural', () => {
        const { el } = create([
            { group: 'a', value: 5 },
            { group: 'b', value: 0 },
            { group: 'c', value: -2 },
        ]);
        expect(el.querySelector('[data-testid="treemap-excluded"]')?.textContent?.trim()).toBe(
            '2 items at zero or below are not shown',
        );
    });

    it('has no a11y violations', async () => {
        const { el } = create(TWO_LEVEL);
        await expectNoA11yViolations(el);
    });
});
