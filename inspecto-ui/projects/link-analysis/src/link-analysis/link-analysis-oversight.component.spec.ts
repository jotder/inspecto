import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { InvService, InvestigationLog, PendingExpansion, WorkingSet } from '@inspecto/link-analysis/api/inv.service';
import { LensService } from '@inspecto/core/api';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { LinkAnalysisOversightComponent } from './link-analysis-oversight.component';

const PENDING: PendingExpansion = {
    id: 'p1',
    investigationId: 'inv-1',
    op: 'expand',
    params: { budget: 500 },
    sensitivity: {
        exceeded: ['budget 500 > 100'],
        budget: 500,
        maxFanOut: null,
        fourEyesBudgetAbove: 100,
        fourEyesFanOutAbove: null,
    },
    status: 'pending',
    requestedBy: 'analyst-1',
    requestedAt: '2026-10-03T10:00:00Z',
};
const LOG = { header: {}, entries: [], total: 0, truncated: false, pending: [PENDING] } as unknown as InvestigationLog;
const WS = {
    entities: [{ id: 'masked:abc' }, { id: '27820000002' }],
    links: [],
    excluded: [],
    hash: 'h',
} as unknown as WorkingSet;

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisOversightComponent],
    template: `<inspecto-link-analysis-oversight
        investigationId="inv-1"
        [log]="log"
        [workingSet]="ws"
        (decided)="decided = decided + 1"
    ></inspecto-link-analysis-oversight>`,
})
class Host {
    log: InvestigationLog | null = LOG;
    ws: WorkingSet | null = WS;
    decided = 0;
}

function create(opts: { approve: boolean; reveal: boolean; approveError?: unknown }) {
    const inv = {
        approveExpansion: vi.fn(() => (opts.approveError ? throwError(() => opts.approveError) : of({}))),
        denyExpansion: vi.fn(() => of({ id: 'inv-1', pending: { ...PENDING, status: 'denied' } })),
        revealEntities: vi.fn(() =>
            of({ id: 'inv-1', revealed: [{ token: 'masked:abc', id: '27820000001' }], unknown: [], masking: {} }),
        ),
    };
    const lens = { canApproveLinkExpansions: signal(opts.approve), canRevealLinkEntities: signal(opts.reveal) };
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [
            provideNoopAnimations(),
            { provide: InvService, useValue: inv },
            { provide: LensService, useValue: lens },
        ],
    });
    const f = TestBed.createComponent(Host);
    f.detectChanges();
    const c = f.debugElement.children[0].componentInstance as LinkAnalysisOversightComponent;
    return { f, c, el: f.nativeElement as HTMLElement, inv };
}

function button(el: HTMLElement, text: string): HTMLButtonElement | undefined {
    return Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === text);
}

describe('LinkAnalysisOversightComponent (D-U6 / D-U7)', () => {
    it('shows a pending expand with requester, threshold and status, and approves it', async () => {
        const { f, el, inv } = create({ approve: true, reveal: false });
        expect(el.textContent).toContain('Expand request p1');
        expect(el.textContent).toContain('analyst-1');
        expect(el.textContent).toContain('budget 500 > 100');
        expect(el.textContent?.toLowerCase()).toContain('pending');
        await expectNoA11yViolations(el);
        button(el, 'Approve')!.click();
        await f.whenStable();
        expect(inv.approveExpansion).toHaveBeenCalledWith('inv-1', 'p1');
        expect(f.componentInstance.decided).toBe(1);
    });

    it('shows the server refusal of a self-approval in place and does not emit', async () => {
        const err = new HttpErrorResponse({
            status: 403,
            error: {
                error: {
                    message:
                        "four-eyes: 'analyst-1' requested this expand and cannot approve it — a different person must",
                },
            },
        });
        const { f, c, el } = create({ approve: true, reveal: false, approveError: err });
        await c.decide(PENDING, true);
        f.detectChanges();
        expect(el.querySelector('[role="alert"], inspecto-alert')?.textContent).toContain('Refused by the server');
        expect(f.componentInstance.decided).toBe(0);
    });

    it('denies with the typed reason', async () => {
        const { f, c, el, inv } = create({ approve: true, reveal: false });
        c.reason.set('disproportionate');
        await c.decide(PENDING, false);
        expect(inv.denyExpansion).toHaveBeenCalledWith('inv-1', 'p1', 'disproportionate');
        f.detectChanges();
        expect(button(el, 'Deny')).toBeTruthy();
    });

    it('without approve capability shows the waiting line, and reveals a masked entity as audited', async () => {
        const { f, el, inv } = create({ approve: false, reveal: true });
        expect(button(el, 'Approve')).toBeUndefined();
        expect(el.textContent).toContain('Waiting for a different person');
        expect(el.textContent).toContain('masked:abc');
        expect(el.textContent).not.toContain('Reveal 27820000002');
        (el.querySelector('button[aria-label="Reveal masked:abc"]') as HTMLButtonElement).click();
        await f.whenStable();
        f.detectChanges();
        expect(inv.revealEntities).toHaveBeenCalledWith('inv-1', ['masked:abc']);
        expect(el.textContent).toContain('27820000001');
        expect(el.textContent).toContain('reveal audited');
        await expectNoA11yViolations(el);
    });
});
