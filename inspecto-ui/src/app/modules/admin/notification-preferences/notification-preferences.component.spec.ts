import { beforeEach, describe, expect, it } from 'vitest';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideToastr } from 'ngx-toastr';
import { SessionService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { NotificationPreferencesComponent } from './notification-preferences.component';

const GRID = [
    {
        category: 'pipeline',
        label: 'Pipeline alerts',
        critical: false,
        available: true,
        channels: { inApp: true, email: false },
    },
    {
        category: 'security',
        label: 'Security',
        critical: true,
        available: false,
        channels: { inApp: true, email: true },
    },
];

/** A signed-in caller's effective grid: pipeline in-app is the caller's own override; no verified email. */
const MINE = [
    {
        category: 'pipeline',
        label: 'Pipeline alerts',
        critical: false,
        available: true,
        channels: { inApp: false, email: false, webhook: false },
        source: { inApp: 'overridden', email: 'inherited', webhook: 'inherited' },
        editable: { inApp: true, email: false, webhook: false },
    },
    {
        category: 'job',
        label: 'Job alerts',
        critical: false,
        available: true,
        channels: { inApp: true, email: false, webhook: false },
        source: { inApp: 'inherited', email: 'inherited', webhook: 'inherited' },
        editable: { inApp: true, email: false, webhook: false },
    },
    {
        category: 'security',
        label: 'Security',
        critical: true,
        available: false,
        channels: { inApp: true, email: true, webhook: true },
        source: { inApp: 'inherited', email: 'inherited', webhook: 'inherited' },
        editable: { inApp: false, email: false, webhook: false },
    },
];

describe('NotificationPreferencesComponent', () => {
    beforeEach(() => {
        TestBed.configureTestingModule({
            imports: [NotificationPreferencesComponent],
            providers: [
                provideHttpClient(withXhr()),
                provideHttpClientTesting(),
                provideNoopAnimations(),
                provideToastr(),
            ],
        });
    });

    function load(grid: unknown = GRID): {
        fixture: ReturnType<typeof TestBed.createComponent<NotificationPreferencesComponent>>;
        http: HttpTestingController;
    } {
        const fixture = TestBed.createComponent(NotificationPreferencesComponent);
        fixture.detectChanges(); // ngOnInit → preferences()
        const http = TestBed.inject(HttpTestingController);
        http.expectOne((r) => r.url.endsWith('/notifications/preferences') && r.method === 'GET').flush(grid);
        fixture.detectChanges();
        return { fixture, http };
    }

    function signIn(capabilities: string[] = []): void {
        const session = TestBed.inject(SessionService);
        session.authMode.set('oidc');
        session.capabilities.set(capabilities);
    }

    it('renders the grid with no accessibility violations', async () => {
        const { fixture } = load();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('locks critical category toggles', () => {
        const { fixture } = load();
        const cmp = fixture.componentInstance;
        const security = cmp.rows.controls[1];
        expect(security.get('inApp')?.disabled).toBe(true);
        expect(security.get('email')?.disabled).toBe(true);
        // non-critical row stays editable
        expect(cmp.rows.controls[0].get('inApp')?.disabled).toBe(false);
    });

    it('Personal: no inherited/overridden markers and no deployment-default editor', () => {
        const { fixture, http } = load();
        const el: HTMLElement = fixture.nativeElement;
        expect(el.querySelectorAll('[data-testid="marker"]').length).toBe(0);
        expect(el.querySelector('app-deployment-default-preferences')).toBeNull();
        http.expectNone((r) => r.url.endsWith('/notifications/preferences/default'));
    });

    it('signed in: marks each cell inherited or overridden, and locks email without a verified address', async () => {
        signIn();
        const { fixture } = load(MINE);
        const el: HTMLElement = fixture.nativeElement;
        const markers = Array.from(el.querySelectorAll('[data-testid="marker"]')).map((m) => m.textContent?.trim());
        expect(markers).toEqual(['Overridden', 'Inherited', 'Inherited', 'Inherited']);
        expect(fixture.componentInstance.rows.controls[0].get('email')?.disabled).toBe(true);
        expect(el.textContent).toContain('Email needs a verified address');
        expect(el.querySelector('app-deployment-default-preferences')).toBeNull();
        await expectNoA11yViolations(el);
    });

    it('Save sends only the cells that changed', () => {
        signIn();
        const { fixture, http } = load(MINE);
        const job = fixture.componentInstance.rows.controls[1].get('inApp')!;
        job.setValue(false);
        job.markAsDirty();
        fixture.componentInstance.save();
        const req = http.expectOne((r) => r.url.endsWith('/notifications/preferences') && r.method === 'PUT');
        expect(req.request.body).toEqual({ preferences: [{ category: 'job', channels: { inApp: false } }] });
        req.flush(MINE);
    });

    it('reset puts a null cell, returning it to the deployment default', () => {
        signIn();
        const { fixture, http } = load(MINE);
        const reset: HTMLButtonElement = fixture.nativeElement.querySelector(
            'button[aria-label="Reset In-app for Pipeline alerts to the deployment default"]',
        );
        reset.click();
        const req = http.expectOne((r) => r.url.endsWith('/notifications/preferences') && r.method === 'PUT');
        expect(req.request.body).toEqual({ preferences: [{ category: 'pipeline', channels: { inApp: null } }] });
        req.flush(MINE);
    });

    it('an administrator also gets the deployment-default editor, which saves to /default', () => {
        signIn(['canAdminister']);
        const { fixture, http } = load(MINE);
        http.expectOne((r) => r.url.endsWith('/notifications/preferences/default') && r.method === 'GET').flush(GRID);
        fixture.detectChanges();
        const el: HTMLElement = fixture.nativeElement;
        expect(el.textContent).toContain('Deployment default');

        const toggle = el.querySelector<HTMLButtonElement>(
            'app-deployment-default-preferences mat-slide-toggle button[role="switch"]',
        )!;
        toggle.click();
        fixture.detectChanges();
        el.querySelector<HTMLButtonElement>('app-deployment-default-preferences button[type="submit"]')!.click();
        const req = http.expectOne((r) => r.url.endsWith('/notifications/preferences/default') && r.method === 'PUT');
        expect(req.request.body).toEqual({ preferences: [{ category: 'pipeline', channels: { inApp: false } }] });
        req.flush(GRID);
        // the personal grid is re-read, since what the admin inherits may have changed
        http.expectOne((r) => r.url.endsWith('/notifications/preferences') && r.method === 'GET').flush(MINE);
    });
});
