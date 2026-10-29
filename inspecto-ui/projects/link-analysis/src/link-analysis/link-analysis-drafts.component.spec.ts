import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { InvService, InvestigationDraft } from '@inspecto/link-analysis/api/inv.service';
import { LensService, SessionService } from '@inspecto/core/api';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { LinkAnalysisDraftsComponent } from './link-analysis-drafts.component';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisDraftsComponent],
    template: `<inspecto-link-analysis-drafts
        investigationId="inv-1"
        [activeDraftId]="active"
        [refreshOn]="tick"
        (scope)="active = $event"
        (promoted)="promoted = promoted + 1"
    ></inspecto-link-analysis-drafts>`,
})
class Host {
    promoted = 0;
    active: string | null = null;
    tick: unknown = null;
}

const DRAFT: InvestigationDraft = {
    draftId: 'd-1',
    investigationId: 'inv-1',
    actor: 'ana-1',
    createdAt: '2026-10-10T00:00:00Z',
    baseStep: 3,
    state: 'open',
    steps: 2,
    headStep: 5,
    behind: 0,
    stale: false,
    pinWarning: false,
};
const confirm = { answer: true, confirmDestructive: vi.fn(async (..._a: unknown[]) => confirm.answer) };

const REPORT = {
    draftId: 'd-1',
    baseStep: 3,
    mainHead: 5,
    behind: 2,
    effectiveOps: 2,
    carried: 1,
    conflicts: [
        { step: 4, op: 'exclude', kind: 'superseded', detail: 'already excluded on main', requiresConfirm: true },
    ],
};
const rel = (total: number) => of({ total });

const http = (status: number, message: string) => new HttpErrorResponse({ status, error: { error: { message } } });

async function create(
    overrides: Partial<Record<keyof InvService, unknown>> = {},
    items = [DRAFT],
    actor: string | null = 'ana-1',
) {
    const inv = {
        investigationMembers: vi.fn(() =>
            of({ investigationId: 'inv-1', owner: 'ana-1', members: [], history: [], you: 'lead' }),
        ),
        investigationDrafts: vi.fn(() => of({ investigationId: 'inv-1', items, total: items.length })),
        forkDraft: vi.fn(() => of(DRAFT)),
        draftWorkingSet: vi.fn((_i: string, _d: string, of_: string) =>
            rel(of_ === 'entities' ? 7 : of_ === 'links' ? 9 : 0),
        ),
        draftConflicts: vi.fn(() => of(REPORT)),
        rebaseDraft: vi.fn(() => of({})),
        promoteDraft: vi.fn(() => of({ promoted: true })),
        discardDraft: vi.fn(() => of({ discarded: true, alreadyDiscarded: false })),
        draftLog: vi.fn(() => throwError(() => http(404, 'no log'))),
        ...overrides,
    };
    confirm.confirmDestructive.mockClear();
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [
            provideNoopAnimations(),
            { provide: InvService, useValue: inv },
            { provide: SessionService, useValue: { actor: signal(actor) } },
            { provide: LensService, useValue: { canManageIncidents: signal(true) } },
            { provide: InspectoConfirmService, useValue: confirm },
        ],
    });
    const f = TestBed.createComponent(Host);
    f.detectChanges();
    await f.whenStable();
    f.detectChanges();
    return { f, el: f.nativeElement as HTMLElement, inv };
}

function button(el: HTMLElement, text: string): HTMLButtonElement {
    return Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === text)!;
}

async function click(f: { detectChanges(): void; whenStable(): Promise<unknown> }, b: HTMLButtonElement) {
    b.click();
    await f.whenStable();
    f.detectChanges();
}

describe('LinkAnalysisDraftsComponent (DR-U2)', () => {
    it('lists Drafts with a state badge and how far main has moved', async () => {
        const { el } = await create({}, [{ ...DRAFT, state: 'hibernated', behind: 2, stale: true }]);
        expect(el.textContent).toContain('ana-1');
        expect(el.textContent).toContain('hibernated');
        expect(el.textContent).toContain('main moved 2 step(s)');
        await expectNoA11yViolations(el);
    });

    it('LA-A11Y-AUDIT-1: every per-Draft action names its Draft, and the Draft row is a named group', async () => {
        const { el } = await create({}, [DRAFT, { ...DRAFT, draftId: 'd-2' }]);
        const groups = Array.from(el.querySelectorAll('[role="group"][aria-label^="Draft "]'));
        expect(groups.map((g) => g.getAttribute('aria-label'))).toEqual(['Draft d-1', 'Draft d-2']);
        for (const g of groups) {
            const id = g.getAttribute('aria-label')!.slice('Draft '.length);
            for (const b of Array.from(g.querySelectorAll('button'))) {
                const visible = b.textContent!.trim();
                const name = b.getAttribute('aria-label')!;
                expect(name, visible).toContain(id);
                expect(name.toLowerCase(), visible).toContain(visible.toLowerCase().split(' ')[0]); // label in name (2.5.3)
            }
        }
        await expectNoA11yViolations(el);
    });

    it('LA-UI-DRAFT-OPS-1: makes a Draft the working scope and goes back to the main log', async () => {
        const { f, el } = await create();
        await click(f, button(el, 'Work on this Draft'));
        expect(f.componentInstance.active).toBe('d-1');
        expect(el.textContent).toContain('working scope');
        await expectNoA11yViolations(el);
        await click(f, button(el, 'Back to the Investigation'));
        expect(f.componentInstance.active).toBeNull();
    });

    it('LA-UI-DRAFT-OPS-1: discarding the working-scope Draft returns the scope to the main log', async () => {
        const { f, el } = await create();
        await click(f, button(el, 'Work on this Draft'));
        await click(f, button(el, 'Discard'));
        expect(f.componentInstance.active).toBeNull();
    });

    it('LA-UI-DRAFT-FIX-1: re-reads the Drafts (own-step count) after each step or undo on the scope', async () => {
        const list = (steps: number) => of({ investigationId: 'inv-1', items: [{ ...DRAFT, steps }], total: 1 });
        const investigationDrafts = vi.fn(() => list(3)).mockReturnValueOnce(list(2));
        const { f, el, inv } = await create({ investigationDrafts });
        expect(el.textContent).toContain('2 own step(s)');
        f.componentInstance.tick = { step: 6 };
        f.detectChanges();
        await f.whenStable();
        f.detectChanges();
        expect(inv.investigationDrafts).toHaveBeenCalledTimes(2);
        expect(el.textContent).toContain('3 own step(s)');
    });

    it('LA-UI-DRAFT-POLISH-1: counts EFFECTIVE own steps (an undo and the op it undid fold away)', async () => {
        const entries = [
            { step: 3, kind: 'op', undoneBy: null },
            { step: 4, kind: 'op', undoneBy: 5 },
            { step: 5, kind: 'undo', undoes: 4, undoneBy: null },
        ];
        const draftLog = vi.fn(() => of({ entries, total: 3, truncated: false }));
        const { el, inv } = await create({ draftLog });
        expect(inv.draftLog).toHaveBeenCalledWith('inv-1', 'd-1');
        expect(el.textContent).toContain('0 own step(s)');
    });

    it('LA-UI-DRAFT-POLISH-1: keeps the raw count when the Draft log cannot be read', async () => {
        const { el } = await create();
        expect(el.textContent).toContain('2 own step(s)');
    });

    it('forks a Draft', async () => {
        const { f, el, inv } = await create({}, []);
        expect(el.textContent).toContain('No Drafts');
        await click(f, button(el, 'Fork a Draft'));
        expect(inv.forkDraft).toHaveBeenCalledWith('inv-1', undefined, undefined);
    });

    it('forks with the typed label (trimmed, capped at 80) and clears the field', async () => {
        const { f, el, inv } = await create({}, []);
        const input = el.querySelector('input[aria-label="Draft label (optional)"]') as HTMLInputElement;
        expect(input.getAttribute('maxlength')).toBe('80');
        expect(input.placeholder).toBe('Name this Draft (optional)');
        input.value = '  hub of erin-005?  ';
        input.dispatchEvent(new Event('input'));
        f.detectChanges(); // as a real keystroke would, before the click
        await click(f, button(el, 'Fork a Draft'));
        expect(inv.forkDraft).toHaveBeenCalledWith('inv-1', undefined, 'hub of erin-005?');
        expect(input.value).toBe('');
    });

    it('shows a Draft label, and falls back to the id when there is none', async () => {
        const { el } = await create({}, [
            { ...DRAFT, label: 'Hub check' },
            { ...DRAFT, draftId: 'd-2' },
        ]);
        expect(el.querySelectorAll('.draft-label').length).toBe(1);
        expect(el.querySelector('.draft-label')?.textContent).toBe('Hub check');
        expect(el.textContent).toContain('d-2');
    });

    it('escapes a label: markup is text, never an element', async () => {
        const { el } = await create({}, [{ ...DRAFT, label: '<img src=x onerror=alert(1)>' }]);
        expect(el.querySelector('img')).toBeNull();
        expect(el.querySelector('.draft-label')?.textContent).toBe('<img src=x onerror=alert(1)>');
    });

    it('words the Space limit of 50 Drafts readably', async () => {
        const { f, el } = await create(
            {
                forkDraft: vi.fn(() =>
                    throwError(() => http(409, 'this Space already holds 50 open Drafts (the limit is 50)')),
                ),
            },
            [],
        );
        await click(f, button(el, 'Fork a Draft'));
        expect(el.textContent).toContain('limit of 50 open Drafts');
        await expectNoA11yViolations(el); // axe, LA-A11Y-AUDIT-1
    });

    it('words the one-live-Draft refusal readably', async () => {
        const { f, el } = await create(
            {
                forkDraft: vi.fn(() => throwError(() => http(409, 'you already have a live draft on investigation'))),
            },
            [],
        );
        await click(f, button(el, 'Fork a Draft'));
        expect(el.textContent).toContain('already have a Draft on this Investigation');
        await expectNoA11yViolations(el); // axe, LA-A11Y-AUDIT-1
    });

    it("shows the Draft's Working Set sizes", async () => {
        const { f, el, inv } = await create();
        await click(f, button(el, 'Working Set'));
        expect(inv.draftWorkingSet).toHaveBeenCalledWith('inv-1', 'd-1', 'entities');
        expect(el.textContent).toContain('7 entities');
        expect(el.textContent).toContain('9 links');
    });

    it('shows conflicts and what a rebase would do about them', async () => {
        const { f, el } = await create();
        await click(f, button(el, 'Conflicts'));
        expect(el.textContent).toContain('already excluded on main');
        expect(el.textContent).toContain('superseded');
        expect(el.textContent).toContain('asks you to confirm');
        await expectNoA11yViolations(el); // axe, LA-A11Y-AUDIT-1
    });

    it('rebases onto the head and confirms exactly the superseded steps it showed', async () => {
        const { f, el, inv } = await create();
        await click(f, button(el, 'Rebase'));
        expect(inv.rebaseDraft).toHaveBeenCalledWith('inv-1', 'd-1', [4], 5);
        expect(el.textContent).toContain('Rebased onto step 5; 1 conflicting step(s) were dropped');
    });

    it('promotes at the main head and tells the host', async () => {
        const stale = { ...DRAFT, behind: 0 };
        const { f, el, inv } = await create({}, [stale]);
        await click(f, button(el, 'Promote'));
        expect(inv.promoteDraft).toHaveBeenCalledWith('inv-1', 'd-1', 3);
        expect(f.componentInstance.promoted).toBe(1);
    });

    it('says a sensitive promote is held for four-eyes approval and does not tell the host', async () => {
        const { f, el } = await create({
            promoteDraft: vi.fn(() => of({ status: 'pending', pending: { id: 'p1' } })),
        });
        await click(f, button(el, 'Promote'));
        expect(el.textContent).toContain('held for four-eyes approval');
        await expectNoA11yViolations(el); // axe, LA-A11Y-AUDIT-1
        expect(f.componentInstance.promoted).toBe(0);
    });

    it('says to rebase when promote is refused as stale', async () => {
        const { f, el } = await create({
            promoteDraft: vi.fn(() => throwError(() => http(409, 'must rebase: the draft is based on step 3'))),
        });
        await click(f, button(el, 'Promote'));
        expect(el.textContent).toContain('The Investigation has moved on. Rebase this Draft');
    });

    it('LA-UI-DRAFT-POLISH-1: words a promote refused for undone steps from the server reason', async () => {
        const msg = 'must rebase: the draft holds undone steps - rebase it (which compacts them away), then promote';
        const { f, el } = await create({ promoteDraft: vi.fn(() => throwError(() => http(409, msg))) });
        await click(f, button(el, 'Promote'));
        expect(el.textContent).toContain('The Draft holds undone steps');
        expect(el.textContent).not.toContain('main log has moved on');
    });

    it('LA-UI-DRAFT-POLISH-1: words an expired index pin', async () => {
        const msg = 'must rebase: the index version this draft pinned expired';
        const { f, el } = await create({ promoteDraft: vi.fn(() => throwError(() => http(409, msg))) });
        await click(f, button(el, 'Promote'));
        expect(el.textContent).toContain('link index version this Draft uses has expired');
    });

    it('LA-UI-DRAFT-POLISH-1: falls back to a generic rebase wording for an unknown reason', async () => {
        const { f, el } = await create({
            promoteDraft: vi.fn(() => throwError(() => http(409, 'must rebase: something new'))),
        });
        await click(f, button(el, 'Promote'));
        expect(el.textContent).toContain('Rebase this Draft before you promote it.');
    });

    it('discards a Draft', async () => {
        const { f, el, inv } = await create();
        await click(f, button(el, 'Discard'));
        expect(inv.discardDraft).toHaveBeenCalledWith('inv-1', 'd-1');
    });

    it('LA-REVOKE-DISCARD-NO-CONFIRM-1: discards only after a confirm naming the Draft', async () => {
        confirm.answer = false;
        const { f, el, inv } = await create(undefined, [{ ...DRAFT, label: 'hub check' }]);
        await click(f, button(el, 'Discard'));
        expect(confirm.confirmDestructive).toHaveBeenCalledTimes(1);
        expect(String(confirm.confirmDestructive.mock.calls[0][0])).toContain('hub check');
        expect(inv.discardDraft).not.toHaveBeenCalled();
        confirm.answer = true;
    });

    it('LA-UI-DRAFT-ACTS-GATE-1: a reviewer cannot rebase, promote or discard another actor’s Draft', async () => {
        const reviewer = {
            investigationMembers: vi.fn(() =>
                of({ investigationId: 'inv-1', owner: 'ana-1', members: [], history: [], you: 'reviewer' }),
            ),
        };
        const { el } = await create(reviewer, [DRAFT], 'bob-2');
        for (const t of ['Rebase', 'Promote', 'Discard']) expect(button(el, t).disabled).toBe(true);
    });

    it('LA-UI-DRAFT-ACTS-GATE-1: a lead promotes or discards another actor’s Draft but never rebases it', async () => {
        const { el } = await create({}, [DRAFT], 'bob-2'); // the default mock answers lead
        expect(button(el, 'Rebase').disabled).toBe(true);
        expect(button(el, 'Promote').disabled).toBe(false);
        expect(button(el, 'Discard').disabled).toBe(false);
    });

    it('LA-UI-DRAFT-ACTS-GATE-1: the Draft’s own actor can rebase, promote and discard it', async () => {
        const { el } = await create({}, [DRAFT], 'ana-1');
        for (const t of ['Rebase', 'Promote', 'Discard']) expect(button(el, t).disabled).toBe(false);
    });
});
