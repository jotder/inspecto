import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { AddDataPageComponent } from './add-data.page';

describe('AddDataPageComponent (placeholder until LA-ONBOARD-1 phase 4)', () => {
    it('says the screen is being built and links back (no violations)', async () => {
        TestBed.configureTestingModule({ imports: [AddDataPageComponent], providers: [provideRouter([])] });
        const fixture = TestBed.createComponent(AddDataPageComponent);
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('h1')!.textContent).toContain('Onboard a file');
        expect(el.textContent).toContain('being built');
        expect(el.querySelector('a')!.getAttribute('href')).toBe('/');
        await expectNoA11yViolations(el);
    });
});
