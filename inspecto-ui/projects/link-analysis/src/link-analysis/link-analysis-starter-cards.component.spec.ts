import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { LinkAnalysisStarterCardsComponent } from './link-analysis-starter-cards.component';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisStarterCardsComponent],
    template: `<inspecto-link-analysis-starter-cards
        (investigateNumber)="numbers.push($event)"
    ></inspecto-link-analysis-starter-cards>`,
})
class Host {
    readonly numbers: string[] = [];
}

describe('LinkAnalysisStarterCardsComponent - Investigate a number', () => {
    it('refuses an invalid MSISDN inline and submits a valid one, separators dropped', async () => {
        TestBed.configureTestingModule({ imports: [Host], providers: [provideNoopAnimations()] });
        const fixture = TestBed.createComponent(Host);
        fixture.detectChanges();
        const el: HTMLElement = fixture.nativeElement;
        const form = el.querySelector('[data-testid="starter-investigate"]') as HTMLFormElement;
        const input = form.querySelector('input') as HTMLInputElement;
        expect(form.querySelector('mat-error')).toBeNull(); // nothing before a submit
        await expectNoA11yViolations(el);

        form.dispatchEvent(new Event('submit'));
        fixture.detectChanges();
        expect(fixture.componentInstance.numbers).toEqual([]);
        expect(form.querySelector('.mat-mdc-form-field-subscript-wrapper mat-error')?.textContent).toContain(
            'Enter a number.',
        );

        input.value = '12-34';
        input.dispatchEvent(new Event('input'));
        form.dispatchEvent(new Event('submit'));
        fixture.detectChanges();
        expect(fixture.componentInstance.numbers).toEqual([]);
        expect(form.querySelector('mat-error')?.textContent).toContain('6 to 15 digits');
        await expectNoA11yViolations(el);

        input.value = '+966 50 123 4567';
        input.dispatchEvent(new Event('input'));
        form.dispatchEvent(new Event('submit'));
        fixture.detectChanges();
        expect(fixture.componentInstance.numbers).toEqual(['+966501234567']);
        expect(form.querySelector('mat-error')).toBeNull();
    });
});
