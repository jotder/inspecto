import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ActionRequest, ActionRequestsService, LensService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { ActionRequestsPanelComponent } from './action-requests-panel.component';

const FAILED: ActionRequest = {
    id: 'ar-20260927120000-abcdef',
    status: 'failed',
    connection: 'ticketing',
    targetUrl: 'https://tickets.example.test/api',
    method: 'POST',
    idempotencyKey: 'ar-20260927120000-abcdef',
    incidentId: 'INCIDENT-1',
    caseId: null,
    origin: 'manual',
    author: 'author-1',
    reason: null,
    createdAt: '2026-09-27T12:00:00Z',
    expiresAt: '2026-10-04T12:00:00Z',
    approver: 'checker-1',
    approvedAt: '2026-09-27T12:00:30Z',
    attempts: 3,
    lastResponse: { status: 500, bodyExcerpt: 'upstream down', error: null, attempt: 3, at: '2026-09-27T12:01:00Z' },
    history: [],
};

@Component({
    standalone: true,
    imports: [ActionRequestsPanelComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `<main>
        <h1>Incident</h1>
        <app-action-requests-panel [objectId]="id" [objectType]="type" />
    </main>`,
})
class HostComponent {
    id = 'INCIDENT-1';
    type = 'INCIDENT';
}

async function create(canApprove = true) {
    const api = {
        list: vi.fn(() => of({ items: [FAILED], total: 1, truncated: false })),
        retry: vi.fn(() => of({ ...FAILED, status: 'succeeded', attempts: 4, payload: {} })),
    };
    const toastr = { success: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [
            provideRouter([]),
            { provide: ActionRequestsService, useValue: api },
            { provide: ToastrService, useValue: toastr },
            { provide: LensService, useValue: { canApproveChanges: () => canApprove } },
        ],
    });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    return { fixture, api, toastr };
}

describe('ActionRequestsPanelComponent', () => {
    it("lists the Incident's requests with status, attempts and the last response", async () => {
        const { fixture, api } = await create();
        expect(api.list).toHaveBeenCalledWith({ incidentId: 'INCIDENT-1' });
        const el: HTMLElement = fixture.nativeElement;
        expect(el.textContent).toContain('POST → ticketing');
        expect(el.textContent).toContain('3 attempt(s)');
        expect(el.textContent).toContain('500');
        expect(el.textContent).toContain('upstream down');
        await expectNoA11yViolations(el);
    });

    it('Retry on a failed request re-dispatches it and reloads', async () => {
        const { fixture, api, toastr } = await create();
        const retry = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
            (b) => (b.textContent ?? '').trim() === 'Retry',
        );
        retry!.click();
        expect(api.retry).toHaveBeenCalledWith(FAILED.id);
        expect(toastr.success).toHaveBeenCalledWith('Delivered');
        expect(api.list).toHaveBeenCalledTimes(2);
    });

    it('offers no Retry without canApproveChanges', async () => {
        const { fixture } = await create(false);
        const labels = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).map((b) =>
            (b.textContent ?? '').trim(),
        );
        expect(labels).not.toContain('Retry');
    });
});
