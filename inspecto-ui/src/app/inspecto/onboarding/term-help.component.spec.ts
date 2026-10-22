import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from '../testing/a11y';
import { TERM_HELP } from './term-help';
import { TermHelpComponent } from './term-help.component';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [TermHelpComponent],
    template: `
        <p>
            Add a file: it becomes a <inspecto-term term="dataset"></inspecto-term>. Each card is an
            <inspecto-term term="entity-type" label="Entity Types"></inspecto-term>; and
            <inspecto-term term="no-such-term" label="plain words"></inspecto-term>.
        </p>
    `,
})
class Host {}

describe('TermHelpComponent - a product term with its plain explanation (LA-ONBOARD-1 UC-15 rule 3)', () => {
    it('shows the word with a help button, and the explanation one click away (no violations)', async () => {
        TestBed.configureTestingModule({ imports: [Host] });
        const f = TestBed.createComponent(Host);
        f.detectChanges();
        const el = f.nativeElement as HTMLElement;
        const buttons = Array.from(el.querySelectorAll<HTMLButtonElement>('[data-testid="term-help"]'));
        expect(buttons.length).toBe(2); // an unknown term is just its word
        expect(el.textContent).toContain('plain words');
        expect(buttons[0].getAttribute('aria-label')).toBe('What is a Dataset?');
        expect(buttons[1].getAttribute('aria-label')).toBe('What is an Entity Type?');
        expect(el.textContent).toContain('Entity Types');

        const text = el.querySelectorAll<HTMLElement>('[data-testid="term-help-text"]')[0];
        expect(text.hidden).toBe(true);
        expect(buttons[0].getAttribute('aria-expanded')).toBe('false');
        buttons[0].click();
        f.detectChanges();
        expect(text.hidden).toBe(false);
        expect(text.textContent).toContain(TERM_HELP.dataset.text);
        expect(buttons[0].getAttribute('aria-expanded')).toBe('true');
        expect(buttons[0].getAttribute('aria-controls')).toBe(text.id);
        await expectNoA11yViolations(el);
    });

    it('every explanation is one short plain sentence', () => {
        for (const h of Object.values(TERM_HELP)) {
            expect(h.text.length).toBeLessThan(130);
            expect(h.text).not.toMatch(/Pipeline|Collector|schema/);
        }
    });
});
