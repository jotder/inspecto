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
    return { el, toggle };
}

describe('LinkAnalysisCapsComponent', () => {
    it('is closed until opened, then states the projection and analysis caps', () => {
        const { el, toggle } = make();
        expect(el.querySelector('[data-testid=caps-list]')).toBeNull();
        expect(el.querySelector('[data-testid=caps-toggle]')!.getAttribute('aria-expanded')).toBe('false');
        toggle();
        const text = el.querySelector('[data-testid=caps-list]')!.textContent!;
        expect(text).toContain('Projection: 1000 nodes (server)');
        expect(text).toContain('Analysis: 2500 nodes (browser)');
        expect(text).not.toContain('Graph run');
    });

    it('adds the graph-run ceiling once the server has stated it', () => {
        const { el, toggle } = make(5000);
        toggle();
        expect(el.querySelector('[data-testid=caps-list]')!.textContent).toContain('Graph run: 5000 nodes (server)');
    });

    it('opens as an opaque card above the canvas, anchored to the left edge', () => {
        const { el, toggle } = make();
        toggle();
        const list = el.querySelector<HTMLElement>('[data-testid=caps-list]')!;
        expect(list.getAttribute('style')).toContain('--gamma-bg-card');
        expect(list.className).toContain('bottom-full');
        expect(list.className).toContain('left-0');
        expect(list.classList.contains('z-30')).toBe(true);
    });

    it('has no axe violations open', async () => {
        const { el, toggle } = make(5000);
        toggle();
        await expectNoA11yViolations(el);
    });
});
