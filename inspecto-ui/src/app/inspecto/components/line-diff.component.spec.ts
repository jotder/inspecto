import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { InspectoLineDiffComponent } from './line-diff.component';

describe('InspectoLineDiffComponent', () => {
    it('draws removed lines as <del>, added as <ins> and the rest as context, in a labelled region', async () => {
        TestBed.configureTestingModule({ imports: [InspectoLineDiffComponent] });
        const fixture = TestBed.createComponent(InspectoLineDiffComponent);
        fixture.componentRef.setInput('diff', {
            added: 1,
            removed: 1,
            lines: [
                { op: 'context', text: 'id: orders' },
                { op: 'remove', text: 'name: Orders' },
                { op: 'add', text: 'name: Orders (EU)' },
            ],
        });
        fixture.componentRef.setInput('label', 'Diff of the change');
        fixture.detectChanges();
        const el: HTMLElement = fixture.nativeElement;
        expect(el.querySelector('[role="region"]')?.getAttribute('aria-label')).toBe('Diff of the change');
        expect(el.querySelector('del')?.textContent).toContain('name: Orders');
        expect(el.querySelector('ins')?.textContent).toContain('name: Orders (EU)');
        expect(el.querySelectorAll('.line').length).toBe(3);
        await expectNoA11yViolations(el);
    });
});
