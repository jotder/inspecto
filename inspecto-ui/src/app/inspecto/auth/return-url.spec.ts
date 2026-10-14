import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { authGuard } from './auth.guard';
import { recordReturnUrl, safeReturnUrl, takePendingReturnUrl } from './return-url';
import { SessionService } from './session.service';

describe('safeReturnUrl (open-redirect defence)', () => {
    it.each(['/link-analysis', '/anomaly-models?tab=2#top', '/a/b%20c?x=%2F'])('keeps the app-relative path %s', (u) =>
        expect(safeReturnUrl(u)).toBe(u),
    );

    it.each([
        '//evil.example',
        '//evil.example/x',
        'https://evil.example',
        'http://evil.example/home',
        'javascript:alert(1)',
        '/\\evil.example',
        '/%5Cevil.example',
        '/%5cevil.example',
        '/%2F%2Fevil.example',
        '/\t/evil.example',
        '/%09/evil.example',
        '/%0d%0a/evil',
        'evil',
        '',
        '/sign-in',
        '/sign-in?x=1',
        '/auth/callback?code=a&state=b',
        '/%zz',
        '/' + 'a'.repeat(2100),
    ])('rejects %s', (u) => expect(safeReturnUrl(u)).toBeNull());

    it('rejects null and undefined', () => {
        expect(safeReturnUrl(null)).toBeNull();
        expect(safeReturnUrl(undefined)).toBeNull();
    });
});

describe('return path record/consume', () => {
    beforeEach(() => sessionStorage.clear());

    it('is consume-once', () => {
        recordReturnUrl('/anomaly-models?x=1#f');
        expect(takePendingReturnUrl()).toBe('/anomaly-models?x=1#f');
        expect(takePendingReturnUrl()).toBeNull();
    });

    it('never stores a malicious target', () => {
        recordReturnUrl('//evil.example');
        expect(takePendingReturnUrl()).toBeNull();
    });
});

describe('authGuard return path', () => {
    beforeEach(() => {
        sessionStorage.clear();
        TestBed.configureTestingModule({
            providers: [provideRouter([]), { provide: SessionService, useValue: { loginRequired: () => true } }],
        });
    });

    it('records the intended URL (path+query+fragment) and redirects to /sign-in', () => {
        const tree = TestBed.runInInjectionContext(() =>
            authGuard({} as never, { url: '/link-analysis?case=7#g' } as never),
        );
        expect(TestBed.inject(Router).serializeUrl(tree as never)).toBe('/sign-in');
        expect(takePendingReturnUrl()).toBe('/link-analysis?case=7#g');
    });
});
