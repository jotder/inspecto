import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { EntityListsPageComponent } from './entity-lists.page';
import { provideLaAppHostServices } from './la-host.providers';

/** Rendering the page is the proof it provides everything the library section injects (a missing store is NG0201). */
describe('EntityListsPageComponent', () => {
    it('renders the Entity Lists section on its own, without an Investigation', () => {
        TestBed.configureTestingModule({
            providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(), ...provideLaAppHostServices()],
        });
        const fixture = TestBed.createComponent(EntityListsPageComponent);
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('h1')?.textContent).toContain('Entity Lists');
        expect(el.querySelector('inspecto-link-analysis-entity-lists')).toBeTruthy();
        expect(el.querySelector('inspecto-link-analysis-identity')).toBeTruthy();
    }, 30_000);

    it('has no axe violations and exactly one h1 that names the section (axe, LA-A11Y-AUDIT-1)', async () => {
        TestBed.configureTestingModule({
            providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(), ...provideLaAppHostServices()],
        });
        const fixture = TestBed.createComponent(EntityListsPageComponent);
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelectorAll('h1')).toHaveLength(1);
        // landmark-unique (real axe, 2026-10-13): the page wrapper was a second region also named "Entity Lists"
        const names = Array.from(el.querySelectorAll('section[aria-label], section[aria-labelledby], form[aria-label]')).map(
            (r) => r.getAttribute('aria-label') ?? el.querySelector(`#${r.getAttribute('aria-labelledby')}`)?.textContent?.trim(),
        );
        expect(new Set(names).size).toBe(names.length);
        await expectNoA11yViolations(el);
    }, 30_000);

    it('LA-NO-LONG-PAGES-1: the lists and the Identity Fact log are two tabs over panels that scroll on their own; both stay mounted', async () => {
        TestBed.configureTestingModule({
            providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(), ...provideLaAppHostServices()],
        });
        const fixture = TestBed.createComponent(EntityListsPageComponent);
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        const tab = (id: string) => el.querySelector(`[data-testid="la-lists-tab-${id}"]`) as HTMLButtonElement;
        const panel = (id: string) => el.querySelector(`#la-lists-panel-${id}`) as HTMLElement;
        expect((el.firstElementChild as HTMLElement).className).toContain('md:overflow-hidden');
        expect(tab('lists').getAttribute('aria-selected')).toBe('true');
        expect(panel('lists').hidden).toBe(false);
        expect(panel('identity').hidden).toBe(true);
        expect(panel('lists').classList).toContain('overflow-y-auto');

        tab('identity').click();
        fixture.detectChanges();
        expect(tab('identity').getAttribute('aria-selected')).toBe('true');
        expect(panel('identity').hidden).toBe(false);
        expect(panel('lists').hidden).toBe(true);
        // both sections stay mounted, so a switch loses nothing either holds
        expect(panel('lists').querySelector('inspecto-link-analysis-entity-lists')).toBeTruthy();

        tab('identity').dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft' }));
        fixture.detectChanges();
        expect(tab('lists').getAttribute('aria-selected')).toBe('true');
        await expectNoA11yViolations(el);
    }, 30_000);
});
