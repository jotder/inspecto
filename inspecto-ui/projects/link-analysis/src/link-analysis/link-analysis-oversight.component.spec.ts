import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { InvService, InvestigationLog, PendingExpansion, WorkingSet } from '@inspecto/link-analysis/api/inv.service';
import { LensService, SessionService } from '@inspecto/core/api';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { ledgerTime } from '@inspecto/link-analysis/add-data/add-data.model';
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
        [revealed]="revealed"
        [reveal]="reveal"
        (decided)="decided = decided + 1"
    ></inspecto-link-analysis-oversight>`,
})
class Host {
    log: InvestigationLog | null = LOG;
    ws: WorkingSet | null = WS;
    decided = 0;
    revealed: Record<string, string> = {};
    /** The host store's reveal: it records what the server revealed, which flows back in as `revealed`. */
    reveal = vi.fn(async (tokens: string[]): Promise<number> => {
        this.revealed = { ...this.revealed, ...Object.fromEntries(tokens.map((t) => [t, '27820000001'])) };
        return tokens.length;
    });
}

function create(opts: { approve: boolean; reveal: boolean; approveError?: unknown; confirm?: boolean }) {
    const confirm = { confirmDestructive: vi.fn(async () => opts.confirm ?? true) };
    const inv = {
        approveExpansion: vi.fn(() => (opts.approveError ? throwError(() => opts.approveError) : of({}))),
        denyExpansion: vi.fn(() => of({ id: 'inv-1', pending: { ...PENDING, status: 'denied' } })),
        revealEntities: vi.fn(() =>
            of({ id: 'inv-1', revealed: [{ token: 'masked:abc', id: '27820000001' }], unknown: [], masking: {} }),
        ),
    };
    const lens = { canApproveLinkExpansions: signal(opts.approve), canRevealLinkEntities: signal(opts.reveal) };
    const dialog = { open: vi.fn(() => ({ afterClosed: () => of('FR-7 warrant') })) };
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [
            provideNoopAnimations(),
            { provide: InvService, useValue: inv },
            { provide: LensService, useValue: lens },
            { provide: MatDialog, useValue: dialog },
            { provide: InspectoConfirmService, useValue: confirm },
            // the sign-in's names: a Demo User's display name for analyst-1, any other id as it is
            {
                provide: SessionService,
                useValue: { subjectName: (id: string) => (id === 'analyst-1' ? 'Ann Analyst' : id) },
            },
        ],
    });
    const f = TestBed.createComponent(Host);
    f.detectChanges();
    const c = f.debugElement.children[0].componentInstance as LinkAnalysisOversightComponent;
    return { f, c, el: f.nativeElement as HTMLElement, inv, dialog, host: f.componentInstance, confirm };
}

function button(el: HTMLElement, text: string): HTMLButtonElement | undefined {
    return Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === text);
}

describe('LinkAnalysisOversightComponent (D-U6 / D-U7)', () => {
    it('shows a pending expand with requester, threshold and status, and approves it', async () => {
        const { f, el, inv } = create({ approve: true, reveal: false });
        expect(el.textContent).toContain('Expand request p1');
        expect(el.textContent).toContain('analyst-1');
        expect(el.textContent).toContain('reads up to 500 rows, above the limit of 100');
        expect(el.textContent).not.toContain('budget 500 > 100');
        expect(el.textContent?.toLowerCase()).toContain('pending');
        await expectNoA11yViolations(el);
        button(el, 'Approve')!.click();
        await f.whenStable();
        expect(inv.approveExpansion).toHaveBeenCalledWith('inv-1', 'p1');
        expect(f.componentInstance.decided).toBe(1);
    });

    it('asks before approving (finding 23): a declined confirm sends nothing; the confirm names requester and limit', async () => {
        const { f, c, inv, confirm } = create({ approve: true, reveal: false, confirm: false });
        await c.decide(PENDING, true);
        expect(inv.approveExpansion).not.toHaveBeenCalled();
        expect(f.componentInstance.decided).toBe(0);
        const [message, opts] = confirm.confirmDestructive.mock.calls[0] as unknown as [string, { title: string }];
        expect(opts.title).toBe('Approve this expand?');
        // presentability: the requester's display name, no request id, and the limit in plain words
        expect(message).toContain('Ann Analyst asks to expand.');
        expect(message).not.toContain('p1');
        expect(message).toContain('It would read up to 500 rows, more than the 100 allowed without a second approval.');
        await c.decide(PENDING, false); // a deny is not asked here: the reason box beside it is its own step
        expect(inv.denyExpansion).toHaveBeenCalled();
        expect(confirm.confirmDestructive).toHaveBeenCalledTimes(1);
    });

    it('says no approvals are needed when nothing is held or masked (T-152)', () => {
        const { f, el } = create({ approve: true, reveal: false });
        expect(el.querySelector('[data-testid=oversight-empty]')).toBeNull();
        f.componentInstance.log = null;
        f.componentInstance.ws = null;
        f.detectChanges();
        expect(el.querySelector('[data-testid=oversight-empty]')?.textContent).toContain(
            'No approvals needed for this Investigation.',
        );
    });

    it('names the limit crossed in words and shows the request time in the shared app clock (finding 31)', () => {
        const { c, el } = create({ approve: true, reveal: false });
        const both = {
            ...PENDING,
            sensitivity: {
                ...PENDING.sensitivity,
                exceeded: ['budget 500 > 100', 'maxFanOut unbounded (threshold 2000)'],
            },
        };
        expect(c.heldFor(both)).toBe(
            'reads up to 500 rows, above the limit of 100; keeps every neighbour of each entity, above the limit of 2,000; ' +
                'the limits are in Settings ▸ Link Analysis',
        );
        expect(el.textContent).not.toContain('—');
        // DATES-ONE-ZONE-1: the one format every Link Analysis time uses (`2026-10-03 12:00`, local), never toLocaleString
        expect(el.textContent).toContain(`at ${ledgerTime(PENDING.requestedAt)}`);
        expect(ledgerTime(PENDING.requestedAt)).toMatch(/^2026-10-0[34] [0-9]{2}:[0-9]{2}$/);
        expect(el.textContent).not.toContain('2026-10-03T10:00:00Z');
        expect(el.textContent).not.toMatch(/[AP]M/);
        expect(c.when('2026-10-15T01:36:58.322167800Z')).toBe(ledgerTime('2026-10-15T01:36:58.322167800Z'));
        expect(c.when('2026-10-15T01:36:58.322167800Z')).not.toContain('322167800');
        expect(c.when(undefined)).toBe('an unknown time');
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
        expect(el.querySelector('[role="alert"], inspecto-alert')?.textContent).toContain('Refused: four-eyes');
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

    it('without approve capability shows the waiting line, and reveals every masked entity in one action with a reason', async () => {
        const { f, el, dialog, host } = create({ approve: false, reveal: true });
        expect(button(el, 'Approve')).toBeUndefined();
        expect(el.textContent).toContain('Waiting for a different person');
        expect(el.textContent).toContain('masked:abc');
        expect(el.textContent).not.toContain('27820000002 →');
        (el.querySelector('[data-testid="oversight-reveal"]') as HTMLButtonElement).click();
        await f.whenStable();
        f.detectChanges();
        expect(dialog.open).toHaveBeenCalledTimes(1);
        expect(host.reveal).toHaveBeenCalledWith(['masked:abc'], 'FR-7 warrant');
        expect(el.textContent).toContain('27820000001');
        expect(el.textContent).toContain('reveal audited');
        expect(el.querySelector('[data-testid="oversight-reveal"]')).toBeNull();
        await expectNoA11yViolations(el);
    });

    it('a cancelled reason prompt reveals nothing', async () => {
        const { f, el, dialog, host } = create({ approve: false, reveal: true });
        dialog.open.mockReturnValueOnce({ afterClosed: () => of(undefined as unknown as string) });
        (el.querySelector('[data-testid="oversight-reveal"]') as HTMLButtonElement).click();
        await f.whenStable();
        expect(host.reveal).not.toHaveBeenCalled();
    });
});
