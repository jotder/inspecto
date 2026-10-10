import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { ComponentsService } from '../api/components.service';
import { expectNoA11yViolations } from '../testing/a11y';
import { RunbookPanelComponent } from './runbook-panel.component';

function mount(get: ComponentsService['get']) {
    TestBed.configureTestingModule({
        imports: [RunbookPanelComponent],
        providers: [provideRouter([]), { provide: ComponentsService, useValue: { get } }],
    });
    const fixture = TestBed.createComponent(RunbookPanelComponent);
    fixture.componentRef.setInput('runbookId', 'fraud_irsf');
    fixture.detectChanges();
    return fixture;
}

describe('RunbookPanelComponent', () => {
    it('renders the linked Runbook: title, ordered steps, the step link, owner role and tags', async () => {
        const asked: string[] = [];
        const fixture = mount((type, id) => {
            asked.push(`${type}/${id}`);
            return of({
                type: 'runbook',
                name: id,
                ref: `runbook/${id}`,
                content: {
                    title: 'IRSF runbook',
                    summary: 'Premium ranges',
                    ownerRole: 'fraud-analyst',
                    tags: ['fraud'],
                    steps: [
                        { text: 'Open the evidence', link: { kind: 'dataset', id: 'fraud_irsf' } },
                        { text: 'Bar the line' },
                    ],
                },
            } as never);
        });
        await fixture.whenStable();
        fixture.detectChanges();
        const el: HTMLElement = fixture.nativeElement;
        expect(asked).toEqual(['runbook/fraud_irsf']);
        expect(el.querySelector('h2')?.textContent).toContain('IRSF runbook');
        const steps = Array.from(el.querySelectorAll('ol > li')).map((li) => li.textContent?.trim());
        expect(steps[0]).toContain('Open the evidence');
        expect(steps[1]).toBe('Bar the line');
        expect(el.querySelector('a')?.getAttribute('href')).toBe('/catalog/datasets/fraud_irsf');
        expect(el.textContent).toContain('Owner role: fraud-analyst');
        await expectNoA11yViolations(el);
    });

    it('says the Runbook is gone on a 404 instead of hiding it', async () => {
        const fixture = mount(() => throwError(() => ({ status: 404 })));
        await fixture.whenStable();
        fixture.detectChanges();
        const el: HTMLElement = fixture.nativeElement;
        expect(el.textContent).toContain('no longer exists');
        expect(el.querySelector('section')).toBeNull();
        await expectNoA11yViolations(el);
    });
});
