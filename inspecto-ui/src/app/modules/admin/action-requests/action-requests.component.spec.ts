import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ActionRequest, ActionRequestDetail, ActionRequestsService, LensService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { ActionRequestsComponent } from './action-requests.component';

const REQ: ActionRequest = {
    id: 'ar-20260927120000-abcdef',
    status: 'pending',
    connection: 'ticketing',
    targetUrl: 'https://tickets.example.test/api',
    method: 'POST',
    idempotencyKey: 'ar-20260927120000-abcdef',
    incidentId: 'INCIDENT-1',
    caseId: null,
    origin: 'decision-rule:leak',
    author: 'author-1',
    reason: null,
    createdAt: '2026-09-27T12:00:00Z',
    expiresAt: '2026-10-04T12:00:00Z',
    approver: null,
    approvedAt: null,
    attempts: 0,
    lastResponse: null,
    history: [],
};
const DETAIL: ActionRequestDetail = {
    ...REQ,
    payload: { ticket: 'INCIDENT-1', rule: 'leak' },
    egress: { scheme: 'https', host: 'tickets.example.test', port: 443, path: '/api', allowlisted: false },
};

async function create(overrides: Partial<Record<keyof ActionRequestsService, unknown>> = {}, canApprove = true) {
    const toastr = { info: vi.fn(), error: vi.fn(), warning: vi.fn(), success: vi.fn() };
    const api = {
        list: vi.fn(() => of({ items: [REQ], total: 1, truncated: false })),
        get: vi.fn(() => of(DETAIL)),
        approve: vi.fn(() => of({ ...DETAIL, status: 'succeeded', approver: 'checker-1', attempts: 1 })),
        decline: vi.fn(() => of({ ...DETAIL, status: 'declined' })),
        retry: vi.fn(() => of({ ...DETAIL, status: 'succeeded', attempts: 4 })),
        markFailed: vi.fn(() => of({ ...DETAIL, status: 'failed' })),
        ...overrides,
    } as unknown as ActionRequestsService;
    TestBed.configureTestingModule({
        imports: [ActionRequestsComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: ActionRequestsService, useValue: api },
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
    const fixture = TestBed.createComponent(ActionRequestsComponent);
    fixture.detectChanges();
    return { fixture, api, toastr };
}

const buttons = (el: HTMLElement): string[] =>
    Array.from(el.querySelectorAll('button')).map((b) => (b.textContent ?? '').trim());

describe('ActionRequestsComponent', () => {
    it('a request stuck in dispatched offers Mark as failed', async () => {
        const { fixture, api } = await create({ get: vi.fn(() => of({ ...DETAIL, status: 'dispatched' })) });
        const c = fixture.componentInstance;
        c.open({ id: REQ.id });
        fixture.detectChanges();
        expect(buttons(fixture.nativeElement)).toContain('Mark as failed');
        await c.markFailed();
        expect(api.markFailed).toHaveBeenCalledWith(REQ.id);
        expect(c.selected()?.status).toBe('failed');
    });

    it('lists the waiting requests on init', async () => {
        const { fixture, api } = await create();
        expect(api.list).toHaveBeenCalledWith({ status: 'pending' });
        expect(fixture.componentInstance.requests()).toEqual([REQ]);
    });

    it('opening one shows the target, the key and the exact payload, with the decision buttons', async () => {
        const { fixture } = await create();
        fixture.componentInstance.open({ id: REQ.id });
        fixture.detectChanges();
        const el: HTMLElement = fixture.nativeElement;
        expect(el.textContent).toContain('https://tickets.example.test/api');
        expect(el.textContent).toContain(REQ.idempotencyKey);
        expect(el.querySelector('pre')?.textContent).toContain('"rule": "leak"');
        expect(el.querySelector('#ar-egress')?.parentElement?.textContent).toContain('tickets.example.test');
        expect(el.textContent).toContain('Not on the egress allowlist');
        expect(buttons(el)).toEqual(expect.arrayContaining(['Approve', 'Decline']));
        await expectNoA11yViolations(el);
    });

    it('offers no decision without canApproveChanges', async () => {
        const { fixture } = await create({}, false);
        fixture.componentInstance.open({ id: REQ.id });
        fixture.detectChanges();
        expect(buttons(fixture.nativeElement)).not.toContain('Approve');
        expect(buttons(fixture.nativeElement)).not.toContain('Retry');
    });

    it('approve sends only the reason and shows the outcome', async () => {
        const { fixture, api } = await create();
        const c = fixture.componentInstance;
        c.open({ id: REQ.id });
        c.reason.setValue('ticket confirmed');
        await c.approve();
        fixture.detectChanges();
        expect(api.approve).toHaveBeenCalledWith(REQ.id, 'ticket confirmed');
        expect(c.selected()?.status).toBe('succeeded');
        expect(buttons(fixture.nativeElement)).not.toContain('Approve');
    });

    it("the server's four-eyes refusal is shown in place", async () => {
        const refusal = { status: 403, error: { error: { message: "four-eyes: 'author-1' proposed this" } } };
        const { fixture } = await create({ approve: vi.fn(() => throwError(() => refusal)) });
        const c = fixture.componentInstance;
        c.open({ id: REQ.id });
        await c.approve();
        fixture.detectChanges();
        expect(c.decideError()).toBeTruthy();
        expect(c.selected()?.status).toBe('pending');
    });

    it('a failed request offers Retry, which re-dispatches it', async () => {
        const failed: ActionRequestDetail = {
            ...DETAIL,
            status: 'failed',
            attempts: 3,
            lastResponse: { status: 500, bodyExcerpt: 'boom', error: null, attempt: 3, at: '2026-09-27T12:01:00Z' },
        };
        const { fixture, api } = await create({ get: vi.fn(() => of(failed)) });
        const c = fixture.componentInstance;
        c.open({ id: REQ.id });
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('boom');
        expect(buttons(fixture.nativeElement)).toContain('Retry');
        c.retry();
        expect(api.retry).toHaveBeenCalledWith(REQ.id);
        expect(c.selected()?.status).toBe('succeeded');
    });
});
