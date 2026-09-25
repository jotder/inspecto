import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import type { TileShape } from 'app/inspecto/viz/dashboard-grid';
import { InspectoTileCardComponent, TileState } from './tile-card.component';

@Component({
    standalone: true,
    imports: [InspectoTileCardComponent],
    template: `
        <inspecto-tile-card [title]="title()" [subtitle]="subtitle()" [state]="state()" [shape]="shape()">
            <span tileStatus data-testid="status">Stale</span>
            <button tileActions type="button" aria-label="Export as PNG">E</button>
            <p data-testid="body">the chart</p>
        </inspecto-tile-card>
    `,
})
class HostComponent {
    readonly title = signal('Revenue at risk');
    readonly subtitle = signal<string | undefined>('Last 30 days');
    readonly state = signal<TileState>('ready');
    readonly shape = signal<TileShape>('chart');
}

function create(patch: Partial<{ state: TileState; shape: TileShape; subtitle: string | undefined }> = {}) {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    const fixture = TestBed.createComponent(HostComponent);
    const host = fixture.componentInstance;
    if (patch.state) host.state.set(patch.state);
    if (patch.shape) host.shape.set(patch.shape);
    if ('subtitle' in patch) host.subtitle.set(patch.subtitle);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    return { fixture, host, el, q: (s: string) => el.querySelector(s) };
}

describe('InspectoTileCardComponent', () => {
    it('renders the header: a titled heading that labels the card, a muted subtitle, status and actions', async () => {
        const { el, q } = create();
        const h = q('h2')!;
        expect(h.textContent?.trim()).toBe('Revenue at risk');
        expect(h.className).toContain('font-semibold');
        expect(q('section')!.getAttribute('aria-labelledby')).toBe(h.id);
        const sub = q('header p')!;
        expect(sub.textContent?.trim()).toBe('Last 30 days');
        expect(sub.className).toContain('text-secondary');
        expect(q('header [data-testid="status"]')).toBeTruthy();
        expect(q('[data-testid="tile-actions"] button')).toBeTruthy();
        expect(el.querySelector('[data-testid="body"]')).toBeTruthy();
        await expectNoA11yViolations(el);
    });

    it('omits the subtitle line when there is none', () => {
        expect(create({ subtitle: undefined }).q('header p')).toBeNull();
    });

    it('tile actions stay in the keyboard order — revealed by focus, never removed or aria-hidden', () => {
        const { q } = create();
        const actions = q('[data-testid="tile-actions"]') as HTMLElement;
        const button = actions.querySelector('button') as HTMLButtonElement;
        expect(actions.hidden).toBe(false);
        expect(actions.getAttribute('aria-hidden')).toBeNull();
        expect(button.tabIndex).toBe(0);
        button.focus();
        expect(document.activeElement).toBe(button);
        // The reveal rule covers keyboard focus, not only hover (jsdom does not compute it, so read the rule).
        const css = Array.from(document.querySelectorAll('style'))
            .map((s) => s.textContent ?? '')
            .join('\n');
        expect(css).toMatch(/\.tile\[[^\]]+\]:focus-within\s+\.tile-actions\[[^\]]+\]/);
    });

    it.each<[TileShape, string]>([
        ['kpi', 'kpi'],
        ['chart', 'chart'],
        ['table', 'table'],
    ])('loading a %s tile shows its matching skeleton and marks the card busy', async (shape) => {
        const { el, q } = create({ state: 'loading', shape });
        const sk = q('[data-testid="tile-skeleton"]')!;
        expect(sk.getAttribute('data-shape')).toBe(shape);
        expect(sk.querySelectorAll('inspecto-skeleton').length).toBeGreaterThan(0);
        expect(q('section')!.getAttribute('aria-busy')).toBe('true');
        expect(q('[role="status"]')!.textContent).toContain('Loading Revenue at risk');
        await expectNoA11yViolations(el);
    });

    it('empty is one quiet line sized to the tile — not the dashed empty-state box', async () => {
        const { el, q } = create({ state: 'empty' });
        const empty = q('[data-testid="tile-empty"]')!;
        expect(empty.textContent?.trim()).toBe('No data for this selection');
        expect(empty.getAttribute('role')).toBe('status');
        expect(empty.querySelector('mat-icon')).toBeTruthy();
        expect(el.querySelector('inspecto-empty-state')).toBeNull();
        expect(el.querySelector('.border-dashed')).toBeNull();
        expect(q('[data-testid="tile-skeleton"]')).toBeNull();
        await expectNoA11yViolations(el);
    });

    it('ready shows neither skeleton nor empty line', () => {
        const { q } = create();
        expect(q('[data-testid="tile-skeleton"]')).toBeNull();
        expect(q('[data-testid="tile-empty"]')).toBeNull();
        expect(q('section')!.getAttribute('aria-busy')).toBeNull();
    });
});
