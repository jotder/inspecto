import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';
import { HELP_HOME, helpChapterFor, helpUrlFor } from './help-chapter';
import { HelpLinkComponent } from './help-link.component';

describe('help chapter map', () => {
    it('ties each screen family to its Handbook chapter by the first path segment', () => {
        expect(helpChapterFor('/pipelines')).toBe('build_shape');
        expect(helpChapterFor('/pipelines/orders/edit?stage=parse#x')).toBe('build_shape');
        expect(helpChapterFor('/collectors')).toBe('build_bring_data_in');
        expect(helpChapterFor('/add-data')).toBe('build_bring_data_in');
        expect(helpChapterFor('/expectations')).toBe('build_trust');
        expect(helpChapterFor('/studio/dashboards/d1')).toBe('build_present');
        expect(helpChapterFor('/alerts')).toBe('build_respond');
        expect(helpChapterFor('/incidents/i-1')).toBe('build_respond');
        expect(helpChapterFor('/cases')).toBe('build_respond');
        expect(helpChapterFor('/settings/access')).toBe('manage_platform');
        expect(helpChapterFor('/link-analysis')).toBe('build_analyse');
    });

    it('opens the Handbook home for an unmapped, empty or prototype-named route', () => {
        expect(helpChapterFor('/')).toBeNull();
        expect(helpChapterFor('/agent-chat')).toBeNull();
        expect(helpChapterFor('/constructor')).toBeNull();
        expect(helpChapterFor('/toString')).toBeNull();
        expect(helpUrlFor('/agent-chat')).toBe(HELP_HOME);
        expect(helpUrlFor('/')).toBe('/help/');
        expect(helpUrlFor('/expectations')).toBe('/help/build_trust.html');
    });
});

@Component({
    standalone: true,
    imports: [InspectoPageHeaderComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `<inspecto-page-header title="Expectations" subtitle="Checks on your data." />`,
})
class ExpectationsPage {}

@Component({
    standalone: true,
    imports: [InspectoPageHeaderComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `<inspecto-page-header title="A section" [headingLevel]="2" />`,
})
class SectionPage {}

describe('HelpLinkComponent', () => {
    async function at(url: string) {
        TestBed.configureTestingModule({
            providers: [
                provideNoopAnimations(),
                provideRouter([
                    { path: 'expectations', component: ExpectationsPage },
                    { path: 'pipelines', component: ExpectationsPage },
                    { path: 'section', component: SectionPage },
                    { path: '**', component: ExpectationsPage },
                ]),
            ],
        });
        const harness = await RouterTestingHarness.create(url);
        harness.detectChanges();
        return harness;
    }

    it('every page header links to its screen chapter, in a new tab, accessibly', async () => {
        const harness = await at('/expectations');
        const el = harness.routeNativeElement as HTMLElement;
        const a = el.querySelector<HTMLAnchorElement>('[data-testid="help-link"]');
        expect(a?.getAttribute('href')).toBe('/help/build_trust.html');
        expect(a?.getAttribute('target')).toBe('_blank');
        expect(a?.getAttribute('rel')).toBe('noopener');
        expect(a?.getAttribute('aria-label')).toContain('Help for this screen');
        await expectNoA11yViolations(el);
    });

    it('follows navigation, and an unmapped screen opens the Handbook home', async () => {
        const harness = await at('/expectations');
        await harness.navigateByUrl('/pipelines');
        harness.detectChanges();
        const href = () =>
            (harness.routeNativeElement as HTMLElement)
                .querySelector('[data-testid="help-link"]')
                ?.getAttribute('href');
        expect(href()).toBe('/help/build_shape.html');
        await harness.navigateByUrl('/agent-chat');
        harness.detectChanges();
        expect(href()).toBe('/help/');
    });

    it('a section header (heading level 2) inside a page carries no second Help link', async () => {
        const harness = await at('/section');
        expect((harness.routeNativeElement as HTMLElement).querySelector('[data-testid="help-link"]')).toBeNull();
    });

    it('outside any routed page it points at the Handbook home', () => {
        TestBed.configureTestingModule({ providers: [provideNoopAnimations()] });
        const fixture = TestBed.createComponent(HelpLinkComponent);
        fixture.detectChanges();
        const a = (fixture.nativeElement as HTMLElement).querySelector('a');
        expect(a?.getAttribute('href')).toBe('/help/');
    });
});
