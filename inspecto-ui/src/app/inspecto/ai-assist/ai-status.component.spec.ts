import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { AiStatusComponent } from './ai-status.component';
import { AiStatusDialog } from './ai-status.dialog';

describe('AiStatusComponent', () => {
    it('opens the status dialog addressed to the entity the pane named', async () => {
        const open = vi.fn();
        TestBed.configureTestingModule({
            imports: [AiStatusComponent],
            providers: [provideNoopAnimations(), { provide: MatDialog, useValue: { open } }],
        });
        const fixture = TestBed.createComponent(AiStatusComponent);
        fixture.componentRef.setInput('label', 'orders');
        fixture.componentRef.setInput('pipelineId', 'orders');
        fixture.detectChanges();

        const button = (fixture.nativeElement as HTMLElement).querySelector('button') as HTMLButtonElement;
        // Ungated, exactly like its vocabulary sibling: reading why something is red is not authoring.
        expect(button.disabled).toBe(false);
        // Icon-only, so the accessible name has to come from aria-label.
        expect(button.getAttribute('aria-label')).toBe('What happened to orders');
        button.click();

        expect(open).toHaveBeenCalledWith(AiStatusDialog, {
            data: { label: 'orders', pipelineId: 'orders', correlationId: undefined, windowMinutes: undefined },
        });
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('passes a correlationId through when the pane has one instead of a pipeline', () => {
        const open = vi.fn();
        TestBed.configureTestingModule({
            imports: [AiStatusComponent],
            providers: [provideNoopAnimations(), { provide: MatDialog, useValue: { open } }],
        });
        const fixture = TestBed.createComponent(AiStatusComponent);
        fixture.componentRef.setInput('label', 'INC-42');
        fixture.componentRef.setInput('correlationId', 'b1');
        fixture.detectChanges();

        ((fixture.nativeElement as HTMLElement).querySelector('button') as HTMLButtonElement).click();

        expect(open).toHaveBeenCalledWith(AiStatusDialog, {
            data: { label: 'INC-42', pipelineId: undefined, correlationId: 'b1', windowMinutes: undefined },
        });
    });
});
