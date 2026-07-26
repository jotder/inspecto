import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { AiExplainComponent } from './ai-explain.component';
import { AiExplainDialog } from './ai-explain.dialog';

describe('AiExplainComponent', () => {
    it('opens the explain dialog with the screen name and the pane-declared terms', async () => {
        const open = vi.fn();
        TestBed.configureTestingModule({
            imports: [AiExplainComponent],
            providers: [provideNoopAnimations(), { provide: MatDialog, useValue: { open } }],
        });
        const fixture = TestBed.createComponent(AiExplainComponent);
        fixture.componentRef.setInput('screen', 'Pipelines');
        fixture.componentRef.setInput('terms', ['Pipeline', 'Step']);
        fixture.detectChanges();

        const button = (fixture.nativeElement as HTMLElement).querySelector('button') as HTMLButtonElement;
        // Read-only and ungated: unlike <inspecto-ai-assist> there is no lens or capability that can
        // disable this — a Business-lens user is exactly who needs the vocabulary explained.
        expect(button.disabled).toBe(false);
        // Icon-only, so the accessible name has to come from aria-label.
        expect(button.getAttribute('aria-label')).toBe('About Pipelines');
        button.click();

        expect(open).toHaveBeenCalledWith(AiExplainDialog, {
            data: { screen: 'Pipelines', terms: ['Pipeline', 'Step'] },
        });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
