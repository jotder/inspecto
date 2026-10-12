import { ChangeDetectionStrategy, Component } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { signal } from '@angular/core';
import { of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LensService } from '@inspecto/core/api';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { EmbedGrantList, EmbedGrantSummary, InvService } from '@inspecto/link-analysis/api/inv.service';
import { LinkAnalysisEmbedComponent } from './link-analysis-embed.component';
import { LinkAnalysisEmbedMintDialog } from './link-analysis-embed-mint.dialog';
import { LinkAnalysisSettingsService } from './link-analysis-settings.service';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisEmbedComponent],
    template: `<inspecto-link-analysis-embed investigationId="inv-1"></inspecto-link-analysis-embed>`,
})
class Host {}

const TOKEN = 'grant123.secretsecretsecret';
const grant = (over: Partial<EmbedGrantSummary> = {}): EmbedGrantSummary => ({
    grantId: 'g1',
    label: 'For the auditor',
    issuedBy: 'lead-1',
    issuedAt: '2026-10-12T10:00:00Z',
    expiresAt: '2026-10-13T10:00:00Z',
    status: 'active',
    viewCount: 3,
    ...over,
});
const list = (grants: EmbedGrantSummary[] = [grant()], enabled = true): EmbedGrantList => ({
    investigationId: 'inv-1',
    enabled,
    grants,
    max: 10,
});
const http = (status: number, message: string) => new HttpErrorResponse({ status, error: { error: { message } } });

async function create(
    opts: {
        can?: boolean;
        you?: string | null;
        embeds?: unknown;
        mint?: unknown;
        confirm?: boolean;
    } = {},
) {
    const inv = {
        investigationEmbeds: vi.fn(() => opts.embeds ?? of(list())),
        investigationMembers: vi.fn(() => of({ investigationId: 'inv-1', owner: 'x', members: [], history: [], you: opts.you === undefined ? 'lead' : opts.you })),
        mintInvestigationEmbed: vi.fn(
            () =>
                opts.mint ??
                of({ grantId: 'g2', investigationId: 'inv-1', token: TOKEN, url: '/public/investigations/' + TOKEN, expiresAt: 'x', label: null, maskingMode: 'typed', note: '' }),
        ),
        revokeInvestigationEmbed: vi.fn(() => of(grant({ status: 'revoked' }))),
    };
    const confirm = { confirmDestructive: vi.fn(async () => opts.confirm ?? true) };
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [
            provideNoopAnimations(),
            { provide: InvService, useValue: inv },
            { provide: LensService, useValue: { canShareInvestigation: signal(opts.can ?? true) } },
            { provide: InspectoConfirmService, useValue: confirm },
            {
                provide: LinkAnalysisSettingsService,
                useValue: {
                    get: () => of({ embedInForce: { defaultTtlHours: 24, maxTtlHours: 48, refuseClassifications: [] }, maskingModeInForce: 'typed' }),
                },
            },
        ],
    });
    const f = TestBed.createComponent(Host);
    f.detectChanges();
    await f.whenStable();
    f.detectChanges();
    return { f, el: f.nativeElement as HTMLElement, inv, confirm };
}

const settle = async (f: { detectChanges(): void; whenStable(): Promise<unknown> }) => {
    f.detectChanges();
    await f.whenStable();
    f.detectChanges();
};
const button = (root: ParentNode, text: string): HTMLButtonElement | undefined =>
    Array.from(root.querySelectorAll('button')).find((b) => b.textContent?.trim() === text);
const dialogEl = () => document.querySelector('mat-dialog-container') as HTMLElement | null;

async function openDialog(c: Awaited<ReturnType<typeof create>>) {
    button(c.el, 'Create link')!.click();
    await new Promise((r) => setTimeout(r));
    await settle(c.f);
    return dialogEl()!;
}

describe('LinkAnalysisEmbedComponent (LA-EMBED-SPA-1)', () => {
    afterEach(() => TestBed.inject(MatDialog).closeAll());

    it('lists the links with status, expiry, views and Revoke, and offers Create link to a lead', async () => {
        const { el } = await create();
        expect(el.textContent).toContain('For the auditor');
        expect(el.textContent).toContain('active');
        expect(el.textContent).toContain('3 views');
        expect(button(el, 'Revoke')).toBeTruthy();
        expect(button(el, 'Create link')).toBeTruthy();
        await expectNoA11yViolations(el);
    });

    it('says plainly that there are no links yet', async () => {
        const { el } = await create({ embeds: of(list([])) });
        expect(el.textContent).toContain('No embed links yet.');
        await expectNoA11yViolations(el);
    });

    it('shows the feature-dark state with the flag hint and disables the controls', async () => {
        const { el } = await create({ embeds: of(list([], false)) });
        expect(el.textContent).toContain('-Dla.embed.enabled=true');
        expect(button(el, 'Create link')!.disabled).toBe(true);
        await expectNoA11yViolations(el);
    });

    it('shows the server message when the list cannot be read', async () => {
        const { el } = await create({ embeds: throwError(() => http(500, 'boom')) });
        expect(el.textContent).toContain('boom');
        await expectNoA11yViolations(el);
    });

    it('is read-only, naming the capability and the lead role, without canShareInvestigation', async () => {
        const { el } = await create({ can: false });
        expect(el.textContent).toContain('canShareInvestigation');
        expect(el.textContent).toContain('lead of this Investigation');
        expect(button(el, 'Create link')).toBeUndefined();
        expect(button(el, 'Revoke')).toBeUndefined();
        await expectNoA11yViolations(el);
    });

    it('is read-only for a member who is not a lead', async () => {
        const { el } = await create({ you: 'analyst' });
        expect(el.textContent).toContain('only a lead of this Investigation');
        expect(button(el, 'Create link')).toBeUndefined();
        expect(button(el, 'Revoke')).toBeUndefined();
    });

    it('revokes only after the confirm, and not when it is declined', async () => {
        const declined = await create({ confirm: false });
        button(declined.el, 'Revoke')!.click();
        await settle(declined.f);
        expect(declined.inv.revokeInvestigationEmbed).not.toHaveBeenCalled();
        TestBed.resetTestingModule();

        const c = await create({ confirm: true });
        button(c.el, 'Revoke')!.click();
        await settle(c.f);
        expect(c.confirm.confirmDestructive).toHaveBeenCalled();
        expect(c.inv.revokeInvestigationEmbed).toHaveBeenCalledWith('inv-1', 'g1');
    });

    it('mint dialog states what the link shows and bounds the lifetime 1..max client-side', async () => {
        const c = await create();
        const d = await openDialog(c);
        expect(d.textContent).toContain('read-only, sealed view');
        expect(d.textContent).toContain('typed');
        expect(d.textContent).toContain('cannot be framed on other sites');
        const ttl = d.querySelector('#embed-ttl') as HTMLInputElement;
        expect(ttl.value).toBe('24');
        const create$ = () => button(d, 'Create link')!;
        for (const bad of ['0', '49', '1.5', '']) {
            ttl.value = bad;
            ttl.dispatchEvent(new Event('input'));
            await settle(c.f);
            expect(create$().disabled, `ttl '${bad}'`).toBe(true);
            expect(d.textContent).toContain('1 to 48');
        }
        for (const good of ['1', '48']) {
            ttl.value = good;
            ttl.dispatchEvent(new Event('input'));
            await settle(c.f);
            expect(create$().disabled, `ttl '${good}'`).toBe(false);
        }
        await expectNoA11yViolations(d);
    });

    it('shows the link once with the copy-now note, copies it, and drops the token on close', async () => {
        const writeText = vi.fn(async () => undefined);
        Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
        const c = await create();
        const d = await openDialog(c);
        (d.querySelector('#embed-label') as HTMLInputElement).value = 'Auditor';
        d.querySelector('#embed-label')!.dispatchEvent(new Event('input'));
        button(d, 'Create link')!.click();
        await new Promise((r) => setTimeout(r));
        await settle(c.f);
        expect(c.inv.mintInvestigationEmbed).toHaveBeenCalledWith('inv-1', { ttl_hours: 24, label: 'Auditor' });
        const field = d.querySelector('#embed-link-field') as HTMLInputElement;
        expect(field.value).toContain('/v1/public/investigations/' + TOKEN);
        expect(d.textContent).toContain('Copy now - it is not shown again');
        await expectNoA11yViolations(d);

        button(d, 'Copy')!.click();
        await new Promise((r) => setTimeout(r));
        await settle(c.f);
        expect(writeText).toHaveBeenCalledWith(field.value);
        expect(d.querySelector('[aria-live="polite"]')!.textContent).toContain('Copied');

        const dialog = TestBed.inject(MatDialog).openDialogs[0].componentInstance as LinkAnalysisEmbedMintDialog;
        button(d, 'Done')!.click();
        await new Promise((r) => setTimeout(r));
        await settle(c.f);
        expect(dialog.link()).toBe('');
        expect(document.body.textContent).not.toContain(TOKEN);
        expect(JSON.stringify(Object.values(localStorage))).not.toContain(TOKEN);
        // the host re-read the list after a mint, and the list never carried the token
        expect(c.inv.investigationEmbeds).toHaveBeenCalledTimes(2);
        expect(c.el.textContent).not.toContain(TOKEN);
    });

    it('shows the feature-dark 503 from a mint in the dialog and keeps no link', async () => {
        const c = await create({ mint: throwError(() => http(503, 'embed links are disabled; set -Dla.embed.enabled=true')) });
        const d = await openDialog(c);
        button(d, 'Create link')!.click();
        await new Promise((r) => setTimeout(r));
        await settle(c.f);
        expect(d.textContent).toContain('switched off');
        expect(d.textContent).toContain('-Dla.embed.enabled=true');
        expect(d.querySelector('#embed-link-field')).toBeNull();
    });

    it('shows a 422 refusal from the server in the dialog', async () => {
        const c = await create({ mint: throwError(() => http(422, "'ttl_hours' must be 1..48 (this Space's maximum)")) });
        const d = await openDialog(c);
        button(d, 'Create link')!.click();
        await new Promise((r) => setTimeout(r));
        await settle(c.f);
        expect(d.textContent).toContain("this Space's maximum");
    });
});
