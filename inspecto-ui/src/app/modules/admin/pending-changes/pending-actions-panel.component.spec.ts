import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { LensService, PendingAction, PendingActionsService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { PendingActionsPanelComponent } from './pending-actions-panel.component';

const ACTION: PendingAction = {
    id: 'pa-20261125120000-abcdef',
    action: 'POST /runs/orders/resume',
    status: 'pending',
    author: 'client:ops-bot',
    agentClient: 'ops-bot',
    reason: 'the source is back',
    createdAt: '2026-11-25T12:00:00Z',
    expiresAt: '2026-12-02T12:00:00Z',
    approverCapability: 'canApproveChanges',
    fourEyes: true,
};

async function create(overrides: Partial<Record<keyof PendingActionsService, unknown>> = {}, canApprove = true) {
    const toastr = { info: vi.fn(), error: vi.fn(), warning: vi.fn(), success: vi.fn() };
    const api = {
        list: vi.fn(() => of({ items: [ACTION], total: 1, truncated: false })),
        get: vi.fn(() =>
            of({
                ...ACTION,
                request: { method: 'POST', path: '/runs/orders/resume?dryRun=false', body: '{"batchId":"b-42"}' },
            }),
        ),
        approve: vi.fn(() =>
            of({
                pendingAction: { ...ACTION, status: 'approved', decidedBy: 'checker-1', appliedStatus: 200 },
                ran: true,
            }),
        ),
        decline: vi.fn(() => of({ pendingAction: { ...ACTION, status: 'declined' }, ran: false })),
        ...overrides,
    } as unknown as PendingActionsService;
    const confirm = { confirm: vi.fn(async () => true), confirmDestructive: vi.fn(async () => true) };
    TestBed.configureTestingModule({
        imports: [PendingActionsPanelComponent],
        providers: [
            provideNoopAnimations(),
            { provide: PendingActionsService, useValue: api },
            { provide: ToastrService, useValue: toastr },
            { provide: InspectoConfirmService, useValue: confirm },
            { provide: LensService, useValue: { canApproveChanges: () => canApprove } },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    await TestBed.compileComponents(); // data-table @defer block
    const fixture = TestBed.createComponent(PendingActionsPanelComponent);
    fixture.detectChanges();
    return { fixture, api, toastr, confirm };
}

const buttons = (el: HTMLElement): string[] =>
    Array.from(el.querySelectorAll('button')).map((b) => (b.textContent ?? '').trim());

describe('PendingActionsPanelComponent', () => {
    it('lists the waiting actions on init', async () => {
        const { fixture, api } = await create();
        expect(api.list).toHaveBeenCalledWith('pending');
        expect(fixture.componentInstance.actions()).toEqual([ACTION]);
    });

    it('opening an action shows the machine that asked and why, and an approver can approve it', async () => {
        const { fixture, api, toastr } = await create();
        fixture.componentInstance.open(ACTION as unknown as Record<string, unknown>);
        fixture.detectChanges();
        const el: HTMLElement = fixture.nativeElement;
        expect(el.textContent).toContain('POST /runs/orders/resume');
        expect(el.textContent).toContain('client:ops-bot');
        expect(el.textContent).toContain('the source is back');
        // the whole request an approval replays — query and body — is on screen before anyone approves
        expect(api.get).toHaveBeenCalledWith(ACTION.id);
        expect(el.textContent).toContain('/runs/orders/resume?dryRun=false');
        expect(el.querySelector('pre[aria-label="Request body"]')?.textContent).toContain('"batchId":"b-42"');
        expect(buttons(el)).toEqual(expect.arrayContaining(['Approve', 'Decline']));
        await fixture.componentInstance.approve();
        expect(api.approve).toHaveBeenCalledWith(ACTION.id, undefined);
        expect(toastr.success).toHaveBeenCalledWith('Action approved and run');
        expect(fixture.componentInstance.selected()?.status).toBe('approved');
        await expectNoA11yViolations(el);
    });

    it('a non-approver sees the action but no decision buttons', async () => {
        const { fixture } = await create({}, false);
        fixture.componentInstance.open(ACTION as unknown as Record<string, unknown>);
        fixture.detectChanges();
        expect(buttons(fixture.nativeElement)).not.toContain('Approve');
        expect(buttons(fixture.nativeElement)).not.toContain('Decline');
    });

    it("a refused decision shows the server's words in place", async () => {
        const { fixture } = await create({
            decline: vi.fn(() =>
                throwError(() => ({
                    status: 403,
                    error: { error: { message: 'four-eyes: a different person must' } },
                })),
            ),
        });
        fixture.componentInstance.open(ACTION as unknown as Record<string, unknown>);
        await fixture.componentInstance.decline();
        fixture.detectChanges();
        expect(fixture.componentInstance.decideError()).toBeTruthy();
        expect(fixture.nativeElement.textContent).toContain('Not decided');
    });

    it('a failed read shows an explained alert instead of the grid', async () => {
        const { fixture } = await create({ list: vi.fn(() => throwError(() => ({ status: 500 }))) });
        expect(fixture.nativeElement.textContent).toContain('Could not load the Pending Actions');
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
