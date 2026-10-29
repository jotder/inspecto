import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { beforeEach, describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { HeldItem, LinkAnalysisHeldTrayComponent } from './link-analysis-held-tray.component';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisHeldTrayComponent],
    template: `<inspecto-link-analysis-held-tray
        [held]="held()"
        [threshold]="500"
        [terms]="{ node: 'wallet', nodes: 'wallets', link: 'transfer', links: 'transfers' }"
        [mode]="mode()"
        [whyNot]="whyNot()"
        (removeAll)="removed = removed + 1"
        (expandAnyway)="anyway.push($event)"
        (focusItem)="focused.push($event)"
    ></inspecto-link-analysis-held-tray>`,
})
class Host {
    readonly held = signal<HeldItem[]>([
        { id: 'cc-1', label: 'CC-1', contacts: 9120 },
        { id: 'shop', label: 'SHOP', contacts: null },
    ]);
    readonly mode = signal<'investigation' | 'basic'>('investigation');
    readonly whyNot = signal('');
    removed = 0;
    readonly anyway: string[] = [];
    readonly focused: string[] = [];
}

beforeEach(() => localStorage.clear());

function create() {
    TestBed.configureTestingModule({ imports: [Host], providers: [provideNoopAnimations()] });
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement, host: fixture.componentInstance };
}

const buttons = (el: HTMLElement, text: string) =>
    Array.from(el.querySelectorAll('button')).filter((b) => b.textContent?.includes(text)) as HTMLButtonElement[];

describe('LinkAnalysisHeldTrayComponent (LA-GROW-EXCLUDE-1 §3.2)', () => {
    it('lists each held busy item with its contacts and why, teaches once, and is axe-clean', async () => {
        const { fixture, el } = create();
        const tray = el.querySelector('[data-testid="la-held-tray"]') as HTMLElement;
        expect(tray.textContent).toContain('Held: busy wallets (2)');
        expect(tray.textContent).toContain('9,120 linked');
        expect(tray.textContent).toContain('held'); // no count served: the badge still says held
        expect(tray.textContent).toContain('more than 500 linked entities');
        expect(el.querySelector('[data-testid="la-held-hint"]')?.textContent).toContain('Expand holds them');
        expect(tray.textContent).not.toMatch(/\bGrow\b|contacts|super node/); // T-044, G3, G5
        expect(el.querySelector('[data-testid="la-held-hint"] [data-testid="la-hint-guide"]')?.textContent).toContain(
            'Expand and clean up the graph',
        );
        await expectNoA11yViolations(el);
        buttons(el, 'Got it')[0].click();
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="la-held-hint"]')).toBeNull();
        expect(localStorage.getItem('inspecto.la.hint.busy')).toBe('1');
    });

    it('Remove held, Expand anyway and a click on the name reach the screen', () => {
        const { el, host } = create();
        (el.querySelector('[data-testid="la-held-remove"]') as HTMLButtonElement).click();
        buttons(el, 'Expand anyway')[1].click();
        (el.querySelector('[aria-label="Show CC-1 on the graph"]') as HTMLButtonElement).click();
        expect(host.removed).toBe(1);
        expect(host.anyway).toEqual(['shop']);
        expect(host.focused).toEqual(['cc-1']);
        expect(el.querySelector('[data-testid="la-held-remove"]')?.textContent).toContain('Exclude held (2)');
    });

    it('says why the actions cannot run instead of a silent grey, and the clicks do nothing', () => {
        const { fixture, el, host } = create();
        host.whyNot.set('Needs Incident management to change the step log.');
        fixture.detectChanges();
        const remove = el.querySelector('[data-testid="la-held-remove"]') as HTMLButtonElement;
        expect(remove.getAttribute('aria-disabled')).toBe('true');
        expect(el.querySelector('[data-testid="la-held-why"]')?.textContent).toContain('Incident management');
        remove.click();
        buttons(el, 'Expand anyway')[0].click();
        expect(host.removed).toBe(0);
        expect(host.anyway).toEqual([]);
    });

    it('minimises to a labelled pill and opens again', () => {
        const { fixture, el } = create();
        (el.querySelector('[aria-label="Minimise the Held tray"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        const pill = el.querySelector('[data-testid="la-held-pill"]') as HTMLButtonElement;
        expect(pill.getAttribute('aria-label')).toBe('Show the Held tray: 2 busy wallets');
        pill.click();
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="la-held-tray"]')).not.toBeNull();
    });
});
