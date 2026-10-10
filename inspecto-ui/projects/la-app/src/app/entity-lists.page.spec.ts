import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { EntityListsPageComponent } from './entity-lists.page';

/** Rendering the page is the proof it provides everything the library section injects (a missing store is NG0201). */
describe('EntityListsPageComponent', () => {
    it('renders the Entity Lists section on its own, without an Investigation', () => {
        TestBed.configureTestingModule({
            providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
        });
        const fixture = TestBed.createComponent(EntityListsPageComponent);
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('h1')?.textContent).toContain('Entity Lists');
        expect(el.querySelector('inspecto-link-analysis-entity-lists')).toBeTruthy();
        expect(el.querySelector('inspecto-link-analysis-identity')).toBeTruthy();
    }, 30_000);
});
