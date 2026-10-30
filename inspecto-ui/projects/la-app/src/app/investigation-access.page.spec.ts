import { ChangeDetectionStrategy } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ToastrService } from 'ngx-toastr';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { LensService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { InvService } from '@inspecto/link-analysis/api/inv.service';
import { InvestigationAccessPageComponent } from './investigation-access.page';

const ITEM = (id: string, title: string) => ({
    id,
    title,
    dataset: 'transfers',
    owner: 'ana',
    createdAt: '2026-10-01T00:00:00Z',
    headStep: 3,
    access: 'owner' as const,
    readOnly: false,
});

const MEMBERS = (id: string, you: 'lead' | 'analyst') => ({
    investigationId: id,
    owner: 'ana',
    members: [
        { subject: 'ana', role: 'lead' as const },
        { subject: 'bo', role: 'analyst' as const },
    ],
    history: [],
    you,
});

const LINK = (grantId: string, status: 'active' | 'revoked') => ({
    grantId,
    label: 'portal ' + grantId,
    issuedBy: 'ana',
    issuedAt: '2026-10-01T00:00:00Z',
    expiresAt: '2026-11-01T00:00:00Z',
    status,
    viewCount: 2,
});

describe('InvestigationAccessPageComponent (leads only, LA-PRODUCT-NEXT-1 phase 3)', () => {
    function create(
        opts: {
            failMembersFor?: string;
            canManage?: boolean;
            canShare?: boolean;
            caseShared?: boolean;
            twoLeads?: boolean;
            truncated?: boolean;
        } = {},
    ) {
        let linksRevokedWithMember = false;
        const items = [ITEM('inv-1', 'Mule ring'), ITEM('inv-2', 'Shared one')];
        if (opts.caseShared)
            items.push({ ...ITEM('inv-3', 'Case one'), access: 'case-member', caseRef: 'C-7' } as never);
        const inv = {
            listInvestigations: vi.fn(() =>
                of({
                    items,
                    total: opts.truncated ? 140 : items.length,
                    limit: 100,
                    offset: 0,
                    truncated: !!opts.truncated,
                }),
            ),
            investigationMembers: vi.fn((id: string) => {
                if (id === opts.failMembersFor) return throwError(() => new Error('down'));
                const m = MEMBERS(id, id === 'inv-1' ? 'lead' : 'analyst');
                return of(
                    opts.twoLeads ? { ...m, members: [...m.members, { subject: 'cy', role: 'lead' as const }] } : m,
                );
            }),
            investigationEmbeds: vi.fn((id: string) =>
                of({
                    investigationId: id,
                    enabled: true,
                    max: 5,
                    grants: [LINK('g1', linksRevokedWithMember ? 'revoked' : 'active'), LINK('g2', 'revoked')],
                }),
            ),
            revokeInvestigationMember: vi.fn((id: string) => {
                linksRevokedWithMember = true; // the server revokes the links the member made
                return of({ ...MEMBERS(id, 'lead'), members: [{ subject: 'ana', role: 'lead' as const }] });
            }),
            revokeInvestigationEmbed: vi.fn(() => of(LINK('g1', 'revoked'))),
        };
        const toastr = { success: vi.fn(), error: vi.fn() };
        const confirm = { confirmDestructive: vi.fn(() => Promise.resolve(true)) };
        TestBed.configureTestingModule({
            providers: [
                provideNoopAnimations(),
                { provide: InvService, useValue: inv },
                { provide: ToastrService, useValue: toastr },
                { provide: InspectoConfirmService, useValue: confirm },
                {
                    provide: LensService,
                    useValue: {
                        canManageIncidents: () => opts.canManage ?? true,
                        canShareInvestigation: () => opts.canShare ?? true,
                    },
                },
            ],
        });
        TestBed.overrideComponent(InvestigationAccessPageComponent, {
            set: { changeDetection: ChangeDetectionStrategy.Eager },
        });
        const fixture: ComponentFixture<InvestigationAccessPageComponent> = TestBed.createComponent(
            InvestigationAccessPageComponent,
        );
        fixture.detectChanges();
        return { fixture, c: fixture.componentInstance, el: fixture.nativeElement as HTMLElement, inv, toastr };
    }

    it('lists each readable Investigation with its members and only its ACTIVE links', () => {
        const { c, el } = create();
        expect(c.cards().map((k) => k.inv.id)).toEqual(['inv-1', 'inv-2']);
        expect(c.cards()[0].links?.map((l) => l.grantId)).toEqual(['g1']); // the revoked g2 is not "live"
        expect(el.textContent).toContain('Mule ring');
        expect(el.textContent).toContain('portal g1');
        expect(el.textContent).not.toContain('portal g2');
    });

    it('offers Revoke only on the Investigation the caller leads, and only with the capability', () => {
        const { el } = create();
        const buttons = Array.from(el.querySelectorAll('button[aria-label^="Revoke"]')).map((b) =>
            b.getAttribute('aria-label'),
        );
        expect(buttons).toContain('Revoke bo from Mule ring');
        expect(buttons).toContain('Revoke link portal g1');
        expect(buttons.some((b) => b?.includes('Shared one'))).toBe(false); // an analyst there: no Revoke
    });

    it('hides member Revoke without canManageIncidents, even for a lead', () => {
        const { el } = create({ canManage: false });
        expect(el.querySelector('button[aria-label="Revoke bo from Mule ring"]')).toBeNull();
    });

    it('hides link Revoke without canShareInvestigation, even for a lead', () => {
        const { el } = create({ canShare: false });
        expect(el.querySelector('button[aria-label="Revoke link portal g1"]')).toBeNull();
        expect(el.querySelector('button[aria-label="Revoke bo from Mule ring"]')).not.toBeNull();
    });

    it('never offers Revoke on the last lead, and does once there is a second', () => {
        expect(create().el.querySelector('button[aria-label="Revoke ana from Mule ring"]')).toBeNull();
        TestBed.resetTestingModule();
        const two = create({ twoLeads: true }).el;
        expect(two.querySelector('button[aria-label="Revoke ana from Mule ring"]')).not.toBeNull();
    });

    it('reads an Investigation shared through a Case as such, without asking for its members', () => {
        const { c, el, inv } = create({ caseShared: true });
        expect(inv.investigationMembers).not.toHaveBeenCalledWith('inv-3');
        expect(c.cards()[2].error).toBeNull();
        expect(el.textContent).toContain('Shared with you through Case C-7');
    });

    it('says when the list is cut', () => {
        expect(create({ truncated: true }).el.textContent).toContain('Showing the first 2 of 140 Investigations.');
    });

    it('revokes a member through the Investigation route, and re-reads the links the server revoked with them', async () => {
        const { c, inv, toastr } = create();
        await c.revokeMember(c.cards()[0], 'bo');
        expect(inv.revokeInvestigationMember).toHaveBeenCalledWith('inv-1', 'bo');
        expect(c.cards()[0].members?.members.map((m) => m.subject)).toEqual(['ana']);
        expect(c.cards()[0].links).toEqual([]); // g1 was theirs: no longer shown as live
        expect(toastr.success).toHaveBeenCalled();
    });

    it('revokes a link and drops it from the card', async () => {
        const { c, inv } = create();
        await c.revokeLink(c.cards()[0], c.cards()[0].links![0]);
        expect(inv.revokeInvestigationEmbed).toHaveBeenCalledWith('inv-1', 'g1');
        expect(c.cards()[0].links).toEqual([]);
    });

    it('never calls revoke on an Investigation the caller does not lead', async () => {
        const { c, inv } = create();
        await c.revokeMember(c.cards()[1], 'bo');
        expect(inv.revokeInvestigationMember).not.toHaveBeenCalled();
    });

    it('a failing read degrades its own card, not the page', () => {
        const { c, el } = create({ failMembersFor: 'inv-2' });
        expect(c.cards()).toHaveLength(2);
        expect(c.cards()[1].error).toBe('Could not read its members.');
        expect(el.textContent).toContain('Mule ring');
    });

    it('has no a11y violations', async () => {
        const { el } = create();
        await expectNoA11yViolations(el);
    });
});
