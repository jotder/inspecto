import { ChangeDetectionStrategy, Component } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { InvService, InvestigationMembers } from '@inspecto/link-analysis/api/inv.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
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

async function create(overrides: Partial<Record<keyof InvService, unknown>> = {}) {
    const inv = {
        investigationMembers: vi.fn(() => of(AS_LEAD)),
        grantInvestigationMember: vi.fn(() => of(AS_LEAD)),
        revokeInvestigationMember: vi.fn(() => of(AS_LEAD)),
        ...overrides,
    };
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [provideNoopAnimations(), { provide: InvService, useValue: inv }],
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
        expect(el.textContent).toContain('only a lead can grant or revoke');
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

    it('revokes a member', async () => {
        const { f, el, inv } = await create();
        (el.querySelector('[aria-label="Revoke ana-1"]') as HTMLButtonElement).click();
        await f.whenStable();
        expect(inv.revokeInvestigationMember).toHaveBeenCalledWith('inv-1', 'ana-1');
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
    });

    it('shows an honest message, no controls, when the server answers 404 (not a member)', async () => {
        const { el } = await create({
            investigationMembers: vi.fn(() => throwError(() => http(404, 'no investigation'))),
        });
        expect(el.textContent).toContain('not available to you');
        expect(button(el, 'Grant')).toBeUndefined();
    });
});
