import { beforeEach, describe, expect, it } from 'vitest';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { SessionService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { NotificationBellComponent } from './notifications.component';

describe('NotificationBellComponent', () => {
    beforeEach(() => {
        TestBed.configureTestingModule({
            imports: [NotificationBellComponent],
            // provideRouter: the delete gate reads LensService → SessionService, which needs the router.
            providers: [
                provideHttpClient(withXhr()),
                provideHttpClientTesting(),
                provideNoopAnimations(),
                provideRouter([]),
            ],
        });
    });

    it('offers delete only to an administrator — it archives the shared feed for everyone', () => {
        // Read/unread is each user's own (2026-09-25); DELETE /notifications/{id} stays canAdminister.
        const session = TestBed.inject(SessionService);
        session.authMode.set('oidc');
        session.capabilities.set([]);
        const cmp = TestBed.createComponent(NotificationBellComponent).componentInstance;
        expect(cmp.canDelete()).toBe(false);

        session.capabilities.set(['canAdminister']);
        expect(cmp.canDelete()).toBe(true);
    });

    function flushInitialLoad(): void {
        const http = TestBed.inject(HttpTestingController);
        for (const req of http.match(() => true)) {
            req.flush(req.request.url.includes('unread-count') ? { count: 0 } : []);
        }
    }

    it('renders the bell with no accessibility violations', async () => {
        const fixture = TestBed.createComponent(NotificationBellComponent);
        fixture.detectChanges(); // ngOnInit → refresh()
        flushInitialLoad();
        fixture.detectChanges();

        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('lets Escape reach the menu but keeps every other key inside the panel', () => {
        // UIB-24 (2026-09-22): the panel stopped propagation of EVERY keydown so MatMenu's type-ahead would
        // not hijack its buttons — which also swallowed Escape, the key that closes the menu. Asserted at
        // the handler boundary rather than by driving Material's overlay in jsdom.
        const fixture = TestBed.createComponent(NotificationBellComponent);
        const cmp = fixture.componentInstance;

        const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
        const arrow = new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true });
        let escapeReachedParent = false;
        let arrowReachedParent = false;
        const parent = document.createElement('div');
        const panel = document.createElement('div');
        parent.appendChild(panel);
        parent.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') escapeReachedParent = true;
            if (e.key === 'ArrowDown') arrowReachedParent = true;
        });
        panel.addEventListener('keydown', (e) => cmp.keepPanelKeys(e));

        panel.dispatchEvent(escape);
        panel.dispatchEvent(arrow);

        expect(escapeReachedParent).toBe(true);
        expect(arrowReachedParent).toBe(false);
    });

    it('shows the unread count in the bell aria-label', () => {
        const fixture = TestBed.createComponent(NotificationBellComponent);
        const cmp = fixture.componentInstance;
        fixture.detectChanges();
        const http = TestBed.inject(HttpTestingController);
        for (const req of http.match(() => true)) {
            req.flush(req.request.url.includes('unread-count') ? { count: 3 } : []);
        }
        fixture.detectChanges();

        expect(cmp.ariaLabel()).toBe('Notifications, 3 unread');
        expect(cmp.badge()).toBe('3');
    });
});
