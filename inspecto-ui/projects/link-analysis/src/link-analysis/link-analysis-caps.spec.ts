import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { LinkAnalysisCapsComponent } from './link-analysis-caps.component';

function make(graphRun: number | null = null) {
    TestBed.configureTestingModule({ imports: [LinkAnalysisCapsComponent], providers: [provideNoopAnimations()] });
    const fixture = TestBed.createComponent(LinkAnalysisCapsComponent);
    fixture.componentRef.setInput('projection', 1000);
    fixture.componentRef.setInput('analysis', 2500);
    fixture.componentRef.setInput('graphRun', graphRun);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const toggle = () => {
        el.querySelector<HTMLButtonElement>('[data-testid=caps-toggle]')!.click();
        fixture.detectChanges();
    };
    return { el, toggle, fixture };
}

describe('LinkAnalysisCapsComponent', () => {
    it('is closed until opened, then states the projection and analysis caps', () => {
        const { el, toggle } = make();
        expect(el.querySelector('[data-testid=caps-list]')).toBeNull();
        expect(el.querySelector('[data-testid=caps-toggle]')!.getAttribute('aria-expanded')).toBe('false');
        toggle();
        const text = el.querySelector('[data-testid=caps-list]')!.textContent!;
        expect(el.querySelector('[data-testid=caps-toggle]')!.getAttribute('aria-label')).toBe('Limits'); // an icon button
        expect(el.querySelector('[data-testid=caps-toggle] mat-icon')!.getAttribute('svgIcon')).toBe('heroicons_outline:scale');
        expect(el.querySelector('[data-testid=caps-toggle]')!.getAttribute('aria-expanded')).toBe('true');
        expect(text).toContain('Graph drawn: up to 1,000 nodes');
        expect(text).toContain('On screen tools: up to 2,500 nodes');
        expect(text).not.toContain('Whole Dataset tools');
    });

    it('adds the graph-run ceiling once the server has stated it', () => {
        const { el, toggle } = make(5000);
        toggle();
        expect(el.querySelector('[data-testid=caps-list]')!.textContent).toContain('Whole Dataset tools: up to 5,000 nodes');
    });

    it('opens as an opaque card under its icon, anchored to the right edge (the Graph counts card’s bottom right)', () => {
        const { el, toggle } = make();
        toggle();
        const list = el.querySelector<HTMLElement>('[data-testid=caps-list]')!;
        expect(list.getAttribute('style')).toContain('--gamma-bg-card');
        expect(list.className).toContain('top-full');
        expect(list.className).toContain('right-0');
        expect(list.classList.contains('z-30')).toBe(true);
    });

    it('Escape closes it and puts focus back on the Limits button; a second press toggles it closed too', () => {
        const { el, toggle, fixture } = make();
        toggle();
        const list = el.querySelector<HTMLElement>('[data-testid=caps-list]')!;
        const button = el.querySelector<HTMLButtonElement>('[data-testid=caps-toggle]')!;
        expect(button.getAttribute('aria-controls')).toBe(list.id);
        const esc = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true });
        list.dispatchEvent(esc);
        fixture.detectChanges();
        expect(el.querySelector('[data-testid=caps-list]')).toBeNull();
        expect(button.getAttribute('aria-expanded')).toBe('false');
        expect(document.activeElement).toBe(button);
        toggle();
        toggle();
        expect(el.querySelector('[data-testid=caps-list]')).toBeNull();
    });

    it('has no axe violations closed', async () => {
        const { el } = make();
        await expectNoA11yViolations(el);
    });

    it('has no axe violations open', async () => {
        const { el, toggle } = make(5000);
        toggle();
        await expectNoA11yViolations(el);
    });
});
