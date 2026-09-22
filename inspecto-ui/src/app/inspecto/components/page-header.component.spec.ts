import { describe, expect, it } from 'vitest';
import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideHttpClient } from '@angular/common/http';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { InspectoPageHeaderComponent } from './page-header.component';

@Component({
    standalone: true,
    imports: [InspectoPageHeaderComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `
        <inspecto-page-header
            [title]="title"
            [subtitle]="subtitle"
            [terms]="terms"
            [eyebrow]="eyebrow"
            [backLink]="backLink"
            [hasTabs]="hasTabs"
            [compact]="compact"
        >
            <ng-container actions><button id="primary">New thing</button></ng-container>
            @if (hasTabs) {
                <div tabs id="tabs">tabs</div>
            }
        </inspecto-page-header>
    `,
})
class HostComponent {
    title = 'Alerts';
    subtitle = '';
    terms: string[] | undefined;
    eyebrow = '';
    backLink: string | undefined;
    hasTabs = false;
    compact = false;
}

describe('InspectoPageHeaderComponent', () => {
    function create(inputs: Partial<HostComponent> = {}) {
        TestBed.configureTestingModule({
            imports: [HostComponent],
            providers: [provideNoopAnimations(), provideRouter([]), provideHttpClient()],
        });
        const fixture = TestBed.createComponent(HostComponent);
        Object.assign(fixture.componentInstance, inputs);
        fixture.detectChanges();
        return fixture;
    }

    it('renders exactly one h1 carrying the title, at the shared title size', () => {
        const el: HTMLElement = create().nativeElement;
        const h1s = el.querySelectorAll('h1');
        expect(h1s.length).toBe(1);
        expect(h1s[0].textContent?.trim()).toBe('Alerts');
        expect(h1s[0].className).toContain('text-title');
    });

    it('projects the actions slot to the right of the title', () => {
        const el: HTMLElement = create().nativeElement;
        expect(el.querySelector('#primary')).not.toBeNull();
    });

    it('clamps the subtitle to one line and expands it on click', () => {
        const fixture = create({ subtitle: 'A long explanation of what this pane is for.' });
        const btn = fixture.nativeElement.querySelector('button[aria-expanded]') as HTMLButtonElement;
        expect(btn.className).toContain('truncate');
        expect(btn.getAttribute('aria-expanded')).toBe('false');
        btn.click();
        fixture.detectChanges();
        expect(btn.getAttribute('aria-expanded')).toBe('true');
        expect(btn.className).not.toContain('truncate');
    });

    it('renders no subtitle control and no explain button when neither is given', () => {
        const el: HTMLElement = create().nativeElement;
        expect(el.querySelector('button[aria-expanded]')).toBeNull();
        expect(el.querySelector('inspecto-ai-explain')).toBeNull();
    });

    it('renders the explain affordance when terms are declared', () => {
        const el: HTMLElement = create({ terms: ['Alert', 'Alert Rule'] }).nativeElement;
        expect(el.querySelector('inspecto-ai-explain')).not.toBeNull();
    });

    it('renders eyebrow, back link and the tabs slot', () => {
        const el: HTMLElement = create({ eyebrow: 'default', backLink: '/alerts', hasTabs: true }).nativeElement;
        expect(el.textContent).toContain('default');
        expect(el.querySelector('a[href="/alerts"]')).not.toBeNull();
        expect(el.querySelector('#tabs')).not.toBeNull();
    });

    it('compact mode is one row: same h1 and actions, subtitle inline, no border or padding', () => {
        // The bounded IDE panes (Link Analysis, Geo Map) overflowed under the standard header; compact is
        // the variant designed for them.
        const fixture = create({ subtitle: 'Inline description.', terms: ['Entity'], compact: true });
        const el: HTMLElement = fixture.nativeElement;
        const header = el.querySelector('header')!;
        expect(el.querySelectorAll('h1').length).toBe(1);
        expect(header.className).not.toContain('border-b');
        expect(header.className).not.toContain('pt-4');
        expect(el.querySelector('#primary')).not.toBeNull();
        expect(el.querySelector('inspecto-ai-explain')).not.toBeNull();
        // the subtitle is a plain inline span here, not the expandable button
        expect(el.querySelector('button[aria-expanded]')).toBeNull();
        expect(el.textContent).toContain('Inline description.');
    });

    it('has no axe violations', async () => {
        const fixture = create({ subtitle: 'One line.', terms: ['Alert'], eyebrow: 'default' });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
