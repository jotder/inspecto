import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import {
    LensService,
    PendingChange,
    PendingChangeDetail,
    PendingChangeDiff,
    PendingChangesService,
} from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { PendingChangesComponent } from './pending-changes.component';

const CHANGE: PendingChange = {
    id: 'pc-20260926120000-abcdef',
    kind: 'pipeline',
    name: 'orders',
    operation: 'update',
    status: 'pending',
    author: 'author-1',
    reason: 'rename the display label',
    createdAt: '2026-09-26T12:00:00Z',
    expiresAt: '2026-10-03T12:00:00Z',
    approverCapability: 'canApproveChanges',
    fourEyes: true,
    baseVersion: 'b',
    proposedVersion: 'p',
};
const DETAIL: PendingChangeDetail = {
    ...CHANGE,
    current: { name: 'Orders' },
    proposed: { name: 'Orders (EU)' },
    request: { method: 'POST', path: '/pipelines/orders/label' },
};
const DIFF: PendingChangeDiff = {
    id: CHANGE.id,
    kind: 'pipeline',
    name: 'orders',
    operation: 'update',
    added: 1,
    removed: 1,
    lines: [
        { op: 'remove', text: 'name: Orders' },
        { op: 'add', text: 'name: Orders (EU)' },
    ],
};

async function create(overrides: Partial<Record<keyof PendingChangesService, unknown>> = {}, canApprove = true) {
    const toastr = { info: vi.fn(), error: vi.fn(), warning: vi.fn(), success: vi.fn() };
    const api = {
        list: vi.fn(() => of({ items: [CHANGE], total: 1, truncated: false })),
        get: vi.fn(() => of(DETAIL)),
        diff: vi.fn(() => of(DIFF)),
        approve: vi.fn(() =>
            of({ pendingChange: { ...CHANGE, status: 'approved', decidedBy: 'checker-1' }, applied: true }),
        ),
        decline: vi.fn(() => of({ pendingChange: { ...CHANGE, status: 'declined' }, applied: false })),
        ...overrides,
    } as unknown as PendingChangesService;
    TestBed.configureTestingModule({
        imports: [PendingChangesComponent],
        providers: [
            provideNoopAnimations(),
            { provide: PendingChangesService, useValue: api },
            { provide: ToastrService, useValue: toastr },
            {
                provide: InspectoConfirmService,
                useValue: { confirm: vi.fn(async () => true), confirmDestructive: vi.fn(async () => true) },
            },
            { provide: LensService, useValue: { canApproveChanges: () => canApprove } },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    await TestBed.compileComponents(); // data-table @defer block
    const fixture = TestBed.createComponent(PendingChangesComponent);
    fixture.detectChanges();
    return { fixture, api, toastr };
}

const buttons = (el: HTMLElement): string[] =>
    Array.from(el.querySelectorAll('button')).map((b) => (b.textContent ?? '').trim());

describe('PendingChangesComponent', () => {
    it('lists the waiting changes on init', async () => {
        const { fixture, api } = await create();
        expect(api.list).toHaveBeenCalledWith('pending');
        expect(fixture.componentInstance.changes()).toEqual([CHANGE]);
        expect(fixture.nativeElement.textContent).toContain('Pending Changes');
    });

    it('opening a change shows who proposed it, why, and the diff of what it changes', async () => {
        const { fixture } = await create();
        fixture.componentInstance.open({ id: CHANGE.id });
        fixture.detectChanges();
        const el: HTMLElement = fixture.nativeElement;
        expect(el.textContent).toContain('author-1');
        expect(el.textContent).toContain('rename the display label');
        expect(el.querySelector('del')?.textContent).toContain('name: Orders');
        expect(el.querySelector('ins')?.textContent).toContain('name: Orders (EU)');
        expect(el.textContent).toContain('1 line(s) added, 1 removed.');
        expect(buttons(el)).toEqual(expect.arrayContaining(['Approve', 'Decline']));
        await expectNoA11yViolations(el);
    });

    it('offers no decision without canApproveChanges', async () => {
        const { fixture } = await create({}, false);
        fixture.componentInstance.open({ id: CHANGE.id });
        fixture.detectChanges();
        expect(buttons(fixture.nativeElement)).not.toContain('Approve');
        expect(buttons(fixture.nativeElement)).not.toContain('Decline');
    });

    it('approve sends the reason and reflects the closed change', async () => {
        const { fixture, api, toastr } = await create();
        const c = fixture.componentInstance;
        c.open({ id: CHANGE.id });
        c.reason.setValue('checked against the runbook');
        await c.approve();
        fixture.detectChanges();
        expect(api.approve).toHaveBeenCalledWith(CHANGE.id, 'checked against the runbook');
        expect(c.selected()?.status).toBe('approved');
        expect(toastr.success).toHaveBeenCalled();
        expect(buttons(fixture.nativeElement)).not.toContain('Approve');
    });

    it("the server's refusal (four-eyes, a stale base) is shown in place and the change stays open", async () => {
        const refusal = { status: 403, error: { error: { message: "four-eyes: 'author-1' proposed this change" } } };
        const { fixture } = await create({ approve: vi.fn(() => throwError(() => refusal)) });
        const c = fixture.componentInstance;
        c.open({ id: CHANGE.id });
        await c.approve();
        fixture.detectChanges();
        expect(c.decideError()).toBeTruthy();
        expect(fixture.nativeElement.querySelector('[role="alert"], inspecto-alert')).toBeTruthy();
        expect(c.selected()?.status).toBe('pending');
    });

    it('decline goes through the destructive confirm', async () => {
        const { fixture, api } = await create();
        const c = fixture.componentInstance;
        c.open({ id: CHANGE.id });
        await c.decline();
        expect(api.decline).toHaveBeenCalledWith(CHANGE.id, undefined);
        expect(c.selected()?.status).toBe('declined');
    });

    it('the All filter lists every status', async () => {
        const { fixture, api } = await create();
        fixture.componentInstance.setFilter('all');
        expect(api.list).toHaveBeenLastCalledWith(undefined);
    });
});
