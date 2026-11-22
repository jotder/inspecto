import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { MatIconTestingModule } from '@angular/material/icon/testing';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { InspectoFieldHelpComponent } from './field-help.component';

@Component({
    standalone: true,
    imports: [InspectoFieldHelpComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `<div data-testid="row"><inspecto-field-help [text]="text" label="Join keys" /></div>`,
})
class HostComponent {
    text = 'Column(s) equated between the rows and the Reference.';
}

function render() {
    TestBed.configureTestingModule({ imports: [HostComponent, MatIconTestingModule] });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    return fixture;
}

describe('InspectoFieldHelpComponent', () => {
    it('renders a "?" named for its field, and no description line', async () => {
        const fixture = render();
        const el = fixture.nativeElement as HTMLElement;
        const btn = el.querySelector<HTMLButtonElement>('[data-testid="field-help"]');
        expect(btn).not.toBeNull();
        expect(btn!.getAttribute('aria-label')).toBe('About Join keys');
        expect(el.textContent).not.toContain('equated'); // the text lives in the tooltip, not on the page
        await expectNoA11yViolations(el);
    });

    it('renders nothing for an empty description', () => {
        const fixture = render();
        fixture.componentInstance.text = '';
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).querySelector('[data-testid="field-help"]')).toBeNull();
    });

    it('a click shows the text without reaching the row (a row click opens its editor)', () => {
        const fixture = render();
        const el = fixture.nativeElement as HTMLElement;
        let rowClicks = 0;
        el.querySelector('[data-testid="row"]')!.addEventListener('click', () => rowClicks++);
        el.querySelector<HTMLButtonElement>('[data-testid="field-help"]')!.click();
        fixture.detectChanges();
        expect(rowClicks).toBe(0);
    });
});
