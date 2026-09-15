import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Router } from '@angular/router';
import { SessionService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { describe, expect, it, vi } from 'vitest';
import { SignInComponent } from './sign-in.component';

function create(
    branding: { logoDataUrl?: string; caption?: string; footerText?: string } = {},
    version: string | null = null,
) {
    TestBed.resetTestingModule();
    const session = {
        loginRequired: () => true,
        beginLogin: vi.fn(),
        version: signal<string | null>(version),
        branding: signal({
            logoDataUrl: branding.logoDataUrl ?? null,
            caption: branding.caption ?? null,
            footerText: branding.footerText ?? null,
        }),
    };
    TestBed.configureTestingModule({
        imports: [SignInComponent],
        providers: [
            provideNoopAnimations(),
            { provide: SessionService, useValue: session },
            { provide: Router, useValue: { navigate: vi.fn() } },
        ],
    });
    const fixture = TestBed.createComponent(SignInComponent);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement, session };
}

describe('SignInComponent (W6d)', () => {
    it('renders with no accessibility violations, and exactly one h1', async () => {
        const { el } = create();
        // ⚠ The page's h1 is the headline, not the card. "Sign in" is the card's h2 — two h1s on one page
        // is the violation this asserts against, and the restyle (2026-09-15) is what introduced the risk.
        expect(el.querySelectorAll('h1')).toHaveLength(1);
        expect(el.querySelector('h2')?.textContent).toContain('Sign in');
        await expectNoA11yViolations(el);
    });

    it('starts the login redirect on click', () => {
        const { el, session } = create();
        (el.querySelector('button') as HTMLButtonElement).click();
        expect(session.beginLogin).toHaveBeenCalledOnce();
    });

    // Branding rides the bootstrap payload because /settings/branding is auth-gated: BrandingService
    // would 401 on the one screen shown before anyone has signed in.
    it('shows the deployment branding the bootstrap payload carried', () => {
        const { el } = create({
            logoDataUrl: 'data:image/svg+xml;base64,PHN2Zy8+',
            caption: 'Finance data operations',
            footerText: '© Gamma Analytics 2026',
        });
        expect(el.querySelector('img[src^="data:image"]')).toBeTruthy();
        expect(el.textContent).toContain('Finance data operations');
        expect(el.textContent).toContain('© Gamma Analytics 2026');
    });

    it('falls back to the shipped defaults when no branding is authored', () => {
        const { el } = create();
        expect(el.querySelector('img[src^="data:image"]')).toBeNull();
        // The product mark always renders; only the operator's own logo is conditional.
        expect(el.querySelector('img[src*="inspecto-logo"]')).toBeTruthy();
    });

    /** HOME-VERSION-1: the version is shown only once the backend has reported it — never a scaffold number. */
    it('shows the product version the backend reports, and nothing until it does', () => {
        const unknown = create({});
        expect(unknown.el.querySelector('[data-testid="product-version"]')).toBeNull();

        const known = create({}, '4.0.0-SNAPSHOT');
        expect(known.el.querySelector('[data-testid="product-version"]')?.textContent?.trim()).toBe('v4.0.0-SNAPSHOT');
    });
});
