import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { IdentityGroup, InvService } from '@inspecto/link-analysis/api/inv.service';
import { LensService } from '@inspecto/core/api';
import { LA_FEATURES } from '@inspecto/link-analysis/la-host';
import { LinkAnalysisSettingsService } from './link-analysis-settings.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { identityKeyOf } from './investigation-state';
import { LinkAnalysisIdentitiesComponent } from './link-analysis-identities.component';
import { InvestigationSessionStore } from './link-analysis-investigation.store';
import { mergePairOf } from './investigation-state';

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
    session?: boolean;
}

function create({ groups = [GROUP], canManage = true, listError, session = false }: Options = {}) {
    const store = { mergeDraft: signal<unknown>(null), canvas: signal<unknown>({}), apply: vi.fn(async () => true), error: signal('') };
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
            { provide: LA_FEATURES, useValue: { ops: signal(false), exchange: signal(false), geoLink: signal(true) } },
            { provide: LensService, useValue: { canManageIncidents: signal(canManage) } },
            { provide: LinkAnalysisSettingsService, useValue: { limits: signal({ entityTypesInForce: TYPES }) } },
            ...(session ? [{ provide: InvestigationSessionStore, useValue: store }] : []),
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
    return { fixture, cmp, inv, dialog, next, el, settle, store };
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
        await expectNoA11yViolations(el); // axe, LA-A11Y-AUDIT-1
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
        expect(alert).toContain('Identities need Incident management, also to read.');
        expect(alert).not.toContain('no capability'); // X5
        await expectNoA11yViolations(el); // axe, LA-A11Y-AUDIT-1
    });

    it('explains a 503 in an info alert and hides the forms, and passes axe', async () => {
        const { el, settle } = create({ listError: 503 });
        await settle();
        expect(el.querySelector('inspecto-alert')?.textContent).toContain('Identities are not available');
        expect(el.querySelector('form')).toBeNull();
        await expectNoA11yViolations(el);
    });

    it('without the capability makes no call and says why', async () => {
        const { el, inv, settle } = create({ canManage: false });
        await settle();
        expect(inv.listIdentityGroups).not.toHaveBeenCalled();
        expect(el.textContent).toContain('needs the Incident-management capability');
        await expectNoA11yViolations(el); // axe, LA-A11Y-AUDIT-1
        expect(el.querySelector('form')).toBeNull();
    });
});

describe('mergePairOf (LA-17 "Merge with...")', () => {
    it('takes exactly two typed, unmasked entities', () => {
        expect(mergePairOf(['msisdn:+447700900123', 'imsi:234150000000001'])).toEqual({
            pair: { a: { type: 'msisdn', value: '+447700900123' }, b: { type: 'imsi', value: '234150000000001' } },
            reason: '',
        });
        expect(mergePairOf(['msisdn:+44']).pair).toBeNull();
        expect(mergePairOf(['a:1', 'b:2', 'c:3']).reason).toContain('exactly two');
        expect(mergePairOf(['plain', 'imsi:1']).reason).toContain('typed entities');
    });

    it('is refused, with the reason, when either node is a masked alias (LA-19)', () => {
        expect(mergePairOf(['msisdn:masked:0123456789abcdef', 'imsi:1'])).toEqual({
            pair: null,
            reason: expect.stringContaining('masked entity cannot be merged'),
        });
        expect(mergePairOf(['imsi:1', 'msisdn:masked:0123456789abcdef']).pair).toBeNull();
    });

    it('is refused for the bare pseudonym id masked:<hex> too (the untyped alias the live graph draws)', () => {
        const refused = {
            pair: null,
            reason: expect.stringContaining('masked entity cannot be merged'),
        };
        expect(mergePairOf(['masked:0123456789abcdef', 'masked:fedcba9876543210'])).toEqual(refused);
        expect(mergePairOf(['imsi:1', 'masked:0123456789abcdef'])).toEqual(refused);
    });
});

describe('LinkAnalysisIdentitiesComponent "Merge with..." hand-off', () => {
    it('prefills the form from the hand-off and clears it, writing nothing', async () => {
        const { cmp, inv, store, settle } = create({ session: true });
        await settle();
        store.mergeDraft.set({ a: { type: 'msisdn', value: '+447700900123' }, b: { type: 'imsi', value: '234150000000001' } });
        await settle();
        expect(cmp.assertForm.getRawValue()).toMatchObject({ typeA: 'msisdn', valueA: '+447700900123', typeB: 'imsi', valueB: '234150000000001' });
        expect(store.mergeDraft()).toBeNull();
        expect(inv.assertIdentity).not.toHaveBeenCalled();
    });

    it('offers, never forces, a resolve step after a successful assert, and seals it on request', async () => {
        const { cmp, el, store, settle } = create({ session: true });
        await settle();
        cmp.assertForm.setValue({ typeA: 'msisdn', valueA: '+44 1', typeB: 'imsi', valueB: '2', reason: 'same SIM' });
        await cmp.assertSame();
        await settle();
        expect(store.apply).not.toHaveBeenCalled();
        (el.querySelector('[data-testid="la-seal-resolve"]') as HTMLButtonElement).click();
        await settle();
        expect(store.apply).toHaveBeenCalledWith({ op: 'resolve' });
        expect(el.querySelector('[data-testid="la-seal-resolve"]')).toBeNull();
        await expectNoA11yViolations(el);
    });

    it('offers nothing without an open Investigation', async () => {
        const { cmp, el, store, settle } = create({ session: true });
        store.canvas.set(null);
        await settle();
        cmp.assertForm.setValue({ typeA: 'msisdn', valueA: '+44 1', typeB: 'imsi', valueB: '2', reason: 'x' });
        await cmp.assertSame();
        await settle();
        expect(el.querySelector('[data-testid="la-seal-resolve"]')).toBeNull();
    });
});
