import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { InvService, InvestigationMembers } from '@inspecto/link-analysis/api/inv.service';
import { LensService } from '@inspecto/core/api';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { LinkAnalysisMembersComponent } from './link-analysis-members.component';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisMembersComponent],
    template: `<inspecto-link-analysis-members investigationId="inv-1"></inspecto-link-analysis-members>`,
})
class Host {}

const AS_LEAD: InvestigationMembers = {
    investigationId: 'inv-1',
    owner: 'lead-1',
    members: [
        { subject: 'lead-1', role: 'lead' },
        { subject: 'ana-1', role: 'analyst' },
    ],
    history: [],
    you: 'lead',
};
const AS_ANALYST: InvestigationMembers = { ...AS_LEAD, you: 'analyst' };

const http = (status: number, message: string) => new HttpErrorResponse({ status, error: { error: { message } } });

const confirm = { answer: true, confirmDestructive: vi.fn(async (..._a: unknown[]) => confirm.answer) };

async function create(overrides: Partial<Record<keyof InvService, unknown>> = {}, canManage = true) {
    confirm.confirmDestructive.mockClear();
    const inv = {
        investigationMembers: vi.fn(() => of(AS_LEAD)),
        grantInvestigationMember: vi.fn(() => of(AS_LEAD)),
        revokeInvestigationMember: vi.fn(() => of(AS_LEAD)),
        ...overrides,
    };
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [
            provideNoopAnimations(),
            { provide: InvService, useValue: inv },
            { provide: LensService, useValue: { canManageIncidents: signal(canManage) } },
            { provide: InspectoConfirmService, useValue: confirm },
        ],
    });
    const f = TestBed.createComponent(Host);
    f.detectChanges();
    await f.whenStable();
    f.detectChanges();
    const c = f.debugElement.children[0].componentInstance as LinkAnalysisMembersComponent;
    return { f, c, el: f.nativeElement as HTMLElement, inv };
}

function button(el: HTMLElement, text: string): HTMLButtonElement | undefined {
    return Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === text);
}

describe('LinkAnalysisMembersComponent (DR-U1)', () => {
    it('lists members with role chips and offers grant and revoke to a lead', async () => {
        const { el } = await create();
        expect(el.textContent).toContain('ana-1');
        expect(el.textContent).toContain('analyst');
        expect(el.textContent).toContain('owner');
        expect(button(el, 'Grant')).toBeTruthy();
        expect(el.querySelector('[aria-label="Revoke ana-1"]')).toBeTruthy();
        await expectNoA11yViolations(el);
    });

    it('hides grant and revoke from a member who is not a lead and says why', async () => {
        const { el } = await create({ investigationMembers: vi.fn(() => of(AS_ANALYST)) });
        expect(el.textContent).toContain('ana-1');
        expect(button(el, 'Grant')).toBeUndefined();
        expect(el.querySelector('[aria-label^="Revoke"]')).toBeNull();
        expect(el.textContent).toContain('Only a lead can grant or revoke members.');
        await expectNoA11yViolations(el); // axe, LA-A11Y-AUDIT-1: the read-only note state
    });

    it('a lead without the Incident management capability sees no write controls and the reason', async () => {
        const { el } = await create({}, false);
        expect(button(el, 'Grant')).toBeUndefined();
        expect(el.querySelector('[aria-label^="Revoke"]')).toBeNull();
        expect(el.querySelector('[data-testid="members-read-only"]')?.textContent).toContain(
            'You cannot change members. Needs Incident management.',
        );
        await expectNoA11yViolations(el);
    });

    it('grants the typed subject with the picked role', async () => {
        const { f, c, el, inv } = await create();
        c.subject.set('new-1');
        c.role.set('reviewer');
        f.detectChanges();
        button(el, 'Grant')!.click();
        await f.whenStable();
        expect(inv.grantInvestigationMember).toHaveBeenCalledWith('inv-1', 'new-1', 'reviewer');
        expect(c.subject()).toBe('');
    });

    it('X6: Grant with no user id is disabled with its reason, and a click grants nothing', async () => {
        const { f, el, inv } = await create();
        const grant = button(el, 'Grant')!;
        expect(grant.getAttribute('aria-disabled')).toBe('true');
        expect(grant.getAttribute('aria-describedby')).toContain('members-grant-why');
        expect(el.querySelector('[data-testid="members-grant-why"]')?.textContent?.trim()).toBe(
            'Fill the required fields to run.',
        );
        grant.click();
        await f.whenStable();
        expect(inv.grantInvestigationMember).not.toHaveBeenCalled();
    });

    it('revokes a member', async () => {
        const { f, el, inv } = await create();
        (el.querySelector('[aria-label="Revoke ana-1"]') as HTMLButtonElement).click();
        await f.whenStable();
        expect(inv.revokeInvestigationMember).toHaveBeenCalledWith('inv-1', 'ana-1');
    });

    it('LA-REVOKE-DISCARD-NO-CONFIRM-1: revokes only after a confirm naming the member', async () => {
        confirm.answer = false;
        const { f, el, inv } = await create();
        (el.querySelector('[aria-label="Revoke ana-1"]') as HTMLButtonElement).click();
        await f.whenStable();
        expect(confirm.confirmDestructive).toHaveBeenCalledTimes(1);
        expect(String(confirm.confirmDestructive.mock.calls[0][0])).toContain('ana-1');
        expect(inv.revokeInvestigationMember).not.toHaveBeenCalled();
        confirm.answer = true;
    });

    it('explains a refused revoke of the last lead (422) in place', async () => {
        const { f, el } = await create({
            revokeInvestigationMember: vi.fn(() => throwError(() => http(422, 'cannot revoke the last lead'))),
        });
        (el.querySelector('[aria-label="Revoke lead-1"]') as HTMLButtonElement).click();
        await f.whenStable();
        f.detectChanges();
        expect(el.textContent).toContain('always keeps at least one lead');
        expect(el.textContent).toContain('cannot revoke the last lead');
        await expectNoA11yViolations(el); // axe, LA-A11Y-AUDIT-1: the refusal state
    });

    it('shows an honest message, no controls, when the server answers 404 (not a member)', async () => {
        const { el } = await create({
            investigationMembers: vi.fn(() => throwError(() => http(404, 'no investigation'))),
        });
        expect(el.textContent).toContain('Only members of this Investigation can see who else is on it.');
        expect(button(el, 'Grant')).toBeUndefined();
        await expectNoA11yViolations(el); // axe, LA-A11Y-AUDIT-1: the not-a-member state
    });
});
