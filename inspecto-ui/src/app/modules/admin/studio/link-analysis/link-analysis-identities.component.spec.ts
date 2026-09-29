import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { IdentityGroup, InvService, LensService, SessionService } from 'app/inspecto/api';
import { LinkAnalysisSettingsService } from 'app/inspecto/api/link-analysis-settings.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { identityKeyOf } from './investigation-state';
import { LinkAnalysisIdentitiesComponent } from './link-analysis-identities.component';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisIdentitiesComponent],
    template: `<inspecto-link-analysis-identities></inspecto-link-analysis-identities>`,
})
class Host {}

const TYPES = [
    { id: 'msisdn', label: 'MSISDN', normaliser: 'e164' as const, masked: false, classifications: [] },
    { id: 'imsi', label: 'IMSI', normaliser: 'digits' as const, masked: true, classifications: [] },
];

const GROUP: IdentityGroup = {
    id: 'masked:0123456789abcdef',
    members: ['masked:0123456789abcdef', 'msisdn:+447700900123'],
    assertions: [
        {
            seq: 3,
            a: 'msisdn:+447700900123',
            b: 'masked:0123456789abcdef',
            via: 'analyst',
            actor: 'alice',
            at: '2026-09-27T10:00:00Z',
            reason: 'same SIM',
        },
    ],
};

const http = (status: number, message = 'server says no') =>
    throwError(() => new HttpErrorResponse({ status, error: { error: { message } } }));

interface Options {
    groups?: IdentityGroup[];
    canManage?: boolean;
    listError?: number;
}

function create({ groups = [GROUP], canManage = true, listError }: Options = {}) {
    const inv = {
        listIdentityGroups: vi.fn(() =>
            listError ? http(listError, 'no capability') : of({ groups, atSeq: 3, headSeq: 3, headHash: 'h' }),
        ),
        identityGroup: vi.fn(() => of({ group: GROUP, atSeq: 3, headHash: 'h' })),
        assertIdentity: vi.fn(() => of({ assertion: GROUP.assertions[0], group: GROUP, atSeq: 3, headHash: 'h' })),
        retractIdentity: vi.fn(() => of({ retracted: 3, groups: [GROUP, GROUP], atSeq: 4, headHash: 'h' })),
    };
    const next = { result: undefined as unknown };
    const dialog = { open: vi.fn(() => ({ afterClosed: () => of(next.result) })) };
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [
            provideNoopAnimations(),
            { provide: InvService, useValue: inv },
            { provide: MatDialog, useValue: dialog },
            { provide: SessionService, useValue: { geoLinkEnabled: signal(true) } },
            { provide: LensService, useValue: { canManageIncidents: signal(canManage) } },
            { provide: LinkAnalysisSettingsService, useValue: { limits: signal({ entityTypesInForce: TYPES }) } },
        ],
    });
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    const cmp = fixture.debugElement.children[0].componentInstance as LinkAnalysisIdentitiesComponent;
    const el = fixture.nativeElement as HTMLElement;
    const settle = async () => {
        await fixture.whenStable();
        fixture.detectChanges();
    };
    return { fixture, cmp, inv, dialog, next, el, settle };
}

describe('identityKeyOf (LA-17 slice 2)', () => {
    it("normalises with the type's own rule — the group read matches exactly", () => {
        expect(identityKeyOf(TYPES[0], ' 00 44 7700-900123 ')).toBe('msisdn:+447700900123');
        expect(identityKeyOf(TYPES[1], '2341-5000 0000001')).toBe('imsi:234150000000001');
        expect(identityKeyOf(TYPES[1], 'N/A')).toBeNull();
        expect(identityKeyOf(undefined, '123')).toBeNull();
    });
});

describe('LinkAnalysisIdentitiesComponent (LA-17 slice 2)', () => {
    it('lists groups with masked members VERBATIM and the joining assertions, and passes axe', async () => {
        const { el, inv, settle } = create();
        await settle();
        expect(inv.listIdentityGroups).toHaveBeenCalledTimes(1);
        const members = Array.from(el.querySelectorAll('[aria-label="Members"] li')).map((l) => l.textContent?.trim());
        expect(members).toEqual(['masked:0123456789abcdef', 'msisdn:+447700900123']);
        const joins = el.querySelector('[aria-label="Assertions that joined it"]')?.textContent ?? '';
        expect(joins).toContain('#3 msisdn:+447700900123 = masked:0123456789abcdef — same SIM');
        expect(el.querySelector('button[aria-label="Retract assertion 3"]')).not.toBeNull();
        await expectNoA11yViolations(el);
    });

    it('renders the empty state with no groups, and passes axe', async () => {
        const { el, settle } = create({ groups: [] });
        await settle();
        expect(el.querySelector('inspecto-empty-state')?.textContent).toContain('No identifiers have been asserted');
        await expectNoA11yViolations(el);
    });

    it('asserts a cross-type pair with NORMALISED typed keys', async () => {
        const { cmp, inv, el, settle } = create();
        await settle();
        cmp.assertForm.setValue({
            typeA: 'msisdn',
            valueA: '0044 7700 900123',
            typeB: 'imsi',
            valueB: '23415-0000000001',
            reason: ' same SIM ',
        });
        await cmp.assertSame();
        await settle();
        expect(inv.assertIdentity).toHaveBeenCalledWith({
            a: 'msisdn:+447700900123',
            b: 'imsi:234150000000001',
            reason: 'same SIM',
        });
        expect(el.textContent).toContain('Asserted #3');
        expect(inv.listIdentityGroups).toHaveBeenCalledTimes(2);
    });

    it('refuses a self-assertion and a missing type client-side, with a rendered alert', async () => {
        const { cmp, inv, el, settle } = create();
        await settle();
        cmp.assertForm.setValue({ typeA: 'msisdn', valueA: '+44 1', typeB: 'msisdn', valueB: '0044 1', reason: 'x' });
        await cmp.assertSame();
        await settle();
        expect(el.querySelector('form [role="alert"]')?.textContent).toContain('cannot be asserted with itself');
        cmp.assertForm.setValue({ typeA: '', valueA: '1', typeB: 'imsi', valueB: '2', reason: 'x' });
        await cmp.assertSame();
        await settle();
        expect(el.querySelector('form [role="alert"]')?.textContent).toContain('Choose an identifier type');
        expect(inv.assertIdentity).not.toHaveBeenCalled();
    });

    it('looks a group up by the normalised key', async () => {
        const { cmp, inv, el, settle } = create({ groups: [] });
        await settle();
        cmp.lookupForm.setValue({ type: 'msisdn', value: '0044 7700 900123' });
        await cmp.lookup();
        await settle();
        expect(inv.identityGroup).toHaveBeenCalledWith('msisdn:+447700900123');
        expect(el.querySelector('[aria-label="Group found"]')?.textContent).toContain('2 identifiers');
    });

    it('retracts with the reason the dialog asked for, and says when the group split', async () => {
        const { inv, dialog, next, el, settle } = create();
        await settle();
        next.result = 'wrong SIM';
        (el.querySelector('button[aria-label="Retract assertion 3"]') as HTMLButtonElement).click();
        await settle();
        await settle();
        expect(dialog.open).toHaveBeenCalledTimes(1);
        expect(inv.retractIdentity).toHaveBeenCalledWith(3, 'wrong SIM');
        expect(el.textContent).toContain('Retracted #3 — the group split in two.');
    });

    it("shows a 403 with the server's message", async () => {
        const { el, settle } = create({ listError: 403 });
        await settle();
        const alert = el.querySelector('inspecto-alert')?.textContent ?? '';
        expect(alert).toContain('Incident-management capability');
        expect(alert).toContain('no capability');
    });

    it('explains a 503 in an info alert and hides the forms, and passes axe', async () => {
        const { el, settle } = create({ listError: 503 });
        await settle();
        expect(el.querySelector('inspecto-alert')?.textContent).toContain('not available here');
        expect(el.querySelector('form')).toBeNull();
        await expectNoA11yViolations(el);
    });

    it('without the capability makes no call and says why', async () => {
        const { el, inv, settle } = create({ canManage: false });
        await settle();
        expect(inv.listIdentityGroups).not.toHaveBeenCalled();
        expect(el.textContent).toContain('needs the Incident-management capability');
        expect(el.querySelector('form')).toBeNull();
    });
});
