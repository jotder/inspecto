import { ChangeDetectionStrategy, Component } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { InvService, InvestigationDraft } from '@inspecto/link-analysis/api/inv.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { LinkAnalysisDraftsComponent } from './link-analysis-drafts.component';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisDraftsComponent],
    template: `<inspecto-link-analysis-drafts
        investigationId="inv-1"
        (promoted)="promoted = promoted + 1"
    ></inspecto-link-analysis-drafts>`,
})
class Host {
    promoted = 0;
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

async function create(overrides: Partial<Record<keyof InvService, unknown>> = {}, items = [DRAFT]) {
    const inv = {
        investigationDrafts: vi.fn(() => of({ investigationId: 'inv-1', items, total: items.length })),
        forkDraft: vi.fn(() => of(DRAFT)),
        draftWorkingSet: vi.fn((_i: string, _d: string, of_: string) =>
            rel(of_ === 'entities' ? 7 : of_ === 'links' ? 9 : 0),
        ),
        draftConflicts: vi.fn(() => of(REPORT)),
        rebaseDraft: vi.fn(() => of({})),
        promoteDraft: vi.fn(() => of({ promoted: true })),
        discardDraft: vi.fn(() => of({ discarded: true, alreadyDiscarded: false })),
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

    it('forks a Draft', async () => {
        const { f, el, inv } = await create({}, []);
        expect(el.textContent).toContain('No Drafts');
        await click(f, button(el, 'Fork a Draft'));
        expect(inv.forkDraft).toHaveBeenCalledWith('inv-1');
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
        expect(el.textContent).toContain('Drafts limit (50 open Drafts)');
    });

    it('words the one-live-Draft refusal readably', async () => {
        const { f, el } = await create(
            {
                forkDraft: vi.fn(() => throwError(() => http(409, 'you already have a live draft on investigation'))),
            },
            [],
        );
        await click(f, button(el, 'Fork a Draft'));
        expect(el.textContent).toContain('already have a live Draft');
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
        expect(f.componentInstance.promoted).toBe(0);
    });

    it('says to rebase when promote is refused as stale', async () => {
        const { f, el } = await create({
            promoteDraft: vi.fn(() => throwError(() => http(409, 'must rebase: the draft is based on step 3'))),
        });
        await click(f, button(el, 'Promote'));
        expect(el.textContent).toContain('rebase this Draft onto the current head');
    });

    it('discards a Draft', async () => {
        const { f, el, inv } = await create();
        await click(f, button(el, 'Discard'));
        expect(inv.discardDraft).toHaveBeenCalledWith('inv-1', 'd-1');
    });
});
