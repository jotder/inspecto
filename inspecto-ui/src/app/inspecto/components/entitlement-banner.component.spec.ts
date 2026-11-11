import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { EntitlementSummary, SessionService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { EntitlementBannerComponent, entitlementNotice } from './entitlement-banner.component';

const admin = (e: Partial<EntitlementSummary>): EntitlementSummary => ({
    installed: true,
    state: 'ok',
    forAdministrator: true,
    ...e,
});

describe('entitlementNotice (PRODUCT-ASSEMBLY-1 PA-7b)', () => {
    it('says nothing without an entitlement summary, while ok, or with no file (Home says that)', () => {
        expect(entitlementNotice(null)).toBeNull();
        expect(entitlementNotice(admin({ state: 'ok', expires: '2028-01-01', daysLeft: 400 }))).toBeNull();
        expect(entitlementNotice(admin({ installed: false, state: 'none' }))).toBeNull();
    });

    it('tells everyone when the install is read-only, and the administrator how to resume', () => {
        const user = entitlementNotice({ installed: true, state: 'read-only', readOnlyFrom: '2027-07-15' });
        expect(user?.variant).toBe('error');
        expect(user?.text).toContain('until an administrator installs a renewed entitlement');
        const adm = entitlementNotice(
            admin({
                state: 'read-only',
                reason: "this installation's entitlement expired on 2027-06-30 and its grace ended",
            }),
        );
        expect(adm?.text).toBe(
            "This installation's entitlement expired on 2027-06-30 and its grace ended. Nothing was removed.",
        );
        const trial = entitlementNotice(
            admin({ installed: false, state: 'read-only', reason: 'this trial build has no entitlement' }),
        );
        expect(trial?.text).toBe('This trial build has no entitlement. Nothing was removed.');
    });

    it('warns only an administrator before read-only: expiring, the grace, and a renewal not taken', () => {
        expect(entitlementNotice({ installed: true, state: 'grace', readOnlyFrom: '2027-07-15' })).toBeNull();
        expect(entitlementNotice({ installed: true, state: 'expiring' })).toBeNull();
        const grace = entitlementNotice(admin({ state: 'grace', expires: '2027-06-30', readOnlyFrom: '2027-07-15' }));
        expect(grace?.variant).toBe('warning');
        expect(grace?.text).toContain('until 2027-07-15');
        expect(entitlementNotice(admin({ state: 'expiring', expires: '2027-06-30', daysLeft: 1 }))?.text).toContain(
            '(1 day left)',
        );
        expect(entitlementNotice(admin({ state: 'expiring', expires: '2027-06-30', daysLeft: 12 }))?.text).toContain(
            '(12 days left)',
        );
        const problem = entitlementNotice(admin({ state: 'ok', problem: 'the signature does not match' }));
        expect(problem?.title).toBe('Entitlement not renewed');
    });
});

describe('EntitlementBannerComponent', () => {
    it('renders the read-only alert from the session and nothing when there is nothing to say', async () => {
        const entitlement = signal<EntitlementSummary | null>(null);
        TestBed.configureTestingModule({
            imports: [EntitlementBannerComponent],
            providers: [{ provide: SessionService, useValue: { entitlement } }],
        });
        const fixture = TestBed.createComponent(EntitlementBannerComponent);
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.textContent?.trim()).toBe('');

        entitlement.set({ installed: true, state: 'read-only' });
        fixture.detectChanges();
        expect(el.querySelector('inspecto-alert')).not.toBeNull();
        expect(el.textContent).toContain('Read-only');
        await expectNoA11yViolations(el);
    });
});
