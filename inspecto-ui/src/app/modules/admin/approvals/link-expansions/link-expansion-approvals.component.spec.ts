import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { LinkExpansionApprovalsComponent } from './link-expansion-approvals.component';
import {
    LinkExpansionApprovalsService,
    LinkExpansionInbox,
    LinkExpansionRequest,
} from './link-expansion-approvals.service';

const HELD: LinkExpansionRequest = {
    investigationId: 'case-a',
    title: 'Fraud ring',
    dataset: 'calls_ds',
    purpose: 'Fraud referral FR-7',
    requestId: 'p1',
    kind: 'expand',
    status: 'pending',
    requestedBy: 'analyst-1',
    requestedAt: '2026-10-11T09:00:00Z',
    params: { budget: 500 },
    sensitivity: { exceeded: ['budget 500 > 100'] },
    summary: 'Expand one hop from the current Working Set over calls_ds, reading at most 500 link rows.',
};

function inbox(items: LinkExpansionRequest[]): LinkExpansionInbox {
    return { items, total: items.length, limit: 100, truncated: false, status: 'pending' };
}

async function create(overrides: Partial<Record<keyof LinkExpansionApprovalsService, unknown>> = {}, confirmed = true) {
    const toastr = { info: vi.fn(), error: vi.fn(), warning: vi.fn(), success: vi.fn() };
    const api = {
        inbox: vi.fn(() => of(inbox([HELD]))),
        approve: vi.fn(() => of({})),
        deny: vi.fn(() => of({})),
        ...overrides,
    } as unknown as LinkExpansionApprovalsService;
    TestBed.configureTestingModule({
        imports: [LinkExpansionApprovalsComponent],
        providers: [
            provideNoopAnimations(),
            { provide: LinkExpansionApprovalsService, useValue: api },
            { provide: ToastrService, useValue: toastr },
            { provide: InspectoConfirmService, useValue: { confirm: vi.fn(async () => confirmed) } },
        ],
    });
    const fixture = TestBed.createComponent(LinkExpansionApprovalsComponent);
    fixture.detectChanges();
    return { fixture, api, toastr, el: fixture.nativeElement as HTMLElement };
}

function button(el: HTMLElement, text: string): HTMLButtonElement | undefined {
    return Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === text);
}

describe('LinkExpansionApprovalsComponent', () => {
    it('lists what each held request will read, who asked, and why it was held', async () => {
        const { el, api } = await create();
        expect(api.inbox).toHaveBeenCalledWith('pending');
        expect(el.textContent).toContain('Fraud ring');
        expect(el.textContent).toContain('reading at most 500 link rows');
        expect(el.textContent).toContain('analyst-1');
        expect(el.textContent).toContain('budget 500 > 100');
        expect(button(el, 'Approve')).toBeTruthy();
        await expectNoA11yViolations(el);
    });

    it('approve confirms, calls the decision route, then reloads', async () => {
        const { fixture, api, toastr } = await create();
        await fixture.componentInstance.approve(HELD);
        expect(api.approve).toHaveBeenCalledWith(HELD);
        expect(toastr.success).toHaveBeenCalled();
        expect(api.inbox).toHaveBeenCalledTimes(2);
    });

    it('a declined confirmation approves nothing', async () => {
        const { fixture, api } = await create({}, false);
        await fixture.componentInstance.approve(HELD);
        expect(api.approve).not.toHaveBeenCalled();
    });

    it('deny sends the typed reason', async () => {
        const { fixture, api, el } = await create();
        button(el, 'Deny…')!.click();
        fixture.detectChanges();
        fixture.componentInstance.reason.setValue('  disproportionate ');
        fixture.componentInstance.deny(HELD);
        expect(api.deny).toHaveBeenCalledWith(HELD, 'disproportionate');
        await expectNoA11yViolations(el);
    });

    it('a reason over 200 characters is refused in place, never sent', async () => {
        const { fixture, api } = await create();
        fixture.componentInstance.openDeny(HELD);
        fixture.componentInstance.reason.setValue('x'.repeat(201));
        fixture.componentInstance.deny(HELD);
        expect(api.deny).not.toHaveBeenCalled();
    });

    // The server never lists the caller's own request (four-eyes); if one is decided elsewhere or the requester
    // somehow reaches it, the decision route's 403 is surfaced, and the list is reloaded from the server's truth.
    it("a refused decision (e.g. the requester's own, 403) toasts the server message and reloads", async () => {
        const { fixture, toastr, api } = await create({
            approve: () =>
                throwError(() => ({ status: 403, error: { error: { message: 'a different person must' } } })),
        });
        await fixture.componentInstance.approve(HELD);
        expect(toastr.error).toHaveBeenCalled();
        expect(api.inbox).toHaveBeenCalledTimes(2);
    });

    it('an empty inbox (e.g. only your own requests are held) shows the empty state, no buttons', async () => {
        const { el } = await create({ inbox: () => of(inbox([])) });
        expect(el.textContent).toContain('No link expansions waiting');
        expect(button(el, 'Approve')).toBeUndefined();
        await expectNoA11yViolations(el);
    });

    it('a decided request shows who decided it and offers no buttons', async () => {
        const denied = {
            ...HELD,
            status: 'denied',
            decidedBy: 'lead-1',
            decidedAt: '2026-10-11T10:00:00Z',
            reason: 'no basis',
        };
        const { el } = await create({ inbox: () => of(inbox([denied])) });
        expect(el.textContent).toContain('lead-1');
        expect(el.textContent).toContain('no basis');
        expect(button(el, 'Approve')).toBeUndefined();
    });

    it('the filter reloads with the chosen status', async () => {
        const { fixture, api } = await create();
        fixture.componentInstance.setFilter('all');
        expect(api.inbox).toHaveBeenLastCalledWith('all');
    });

    it('a 503 is explained in place, without an error toast', async () => {
        const { fixture, toastr, el } = await create({ inbox: () => throwError(() => ({ status: 503 })) });
        fixture.detectChanges();
        expect(el.textContent).toContain('Link expansion approvals are not available here');
        expect(toastr.error).not.toHaveBeenCalled();
        await expectNoA11yViolations(el);
    });
});
