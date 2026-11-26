import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ActionRequestsService } from 'app/inspecto/api/action-requests.service';
import { InsightFeed, InsightsService } from 'app/inspecto/api/insights.service';
import { LensService } from 'app/inspecto/api/lens.service';
import { ObjectsService } from 'app/inspecto/api/objects.service';
import { SessionService } from 'app/inspecto/auth/session.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { InsightFeedComponent } from './insight-feed.component';

const FEED: InsightFeed = {
    profile: 'sales',
    runs: 1,
    latestRun: '2026-11-25T06:00:00Z',
    total: 1,
    insights: [
        {
            insight_id: 'i1',
            insight_type: 'anomaly',
            dimension: 'customer',
            // A masked alias, as stored — it must travel as is.
            slice_value: 'masked:ab12',
            period: '2026-11-17',
            value: 140,
            expected: 100,
            delta: 40,
            score: 82,
            band: 'high',
            status: 'new',
            explanation: { sentence: 'Sales for masked:ab12 were 40% above usual.' },
        },
    ],
};

interface Opts {
    features?: Record<string, boolean>;
    manage?: boolean;
    work?: boolean;
    dialogResult?: { connection: string; reason: string };
    proposeFails?: boolean;
}

function create(opts: Opts = {}) {
    const openCaseFromEntities = vi.fn(() => of({ case: { id: 'CASE-7' }, members: [] }));
    const propose = vi.fn(() =>
        opts.proposeFails ? throwError(() => ({ status: 422, error: { message: 'no' } })) : of({ id: 'AR-3' }),
    );
    const open = vi.fn(() => ({ afterClosed: () => of(opts.dialogResult) }));
    const toastr = { success: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
        imports: [InsightFeedComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: InsightsService, useValue: { list: () => of(FEED), feedback: vi.fn() } },
            {
                provide: SessionService,
                useValue: { features: signal(opts.features ?? { insights: true, cases: true, actionRequests: true }) },
            },
            {
                provide: LensService,
                useValue: {
                    canAuthorWorkbench: signal(true),
                    canManageIncidents: signal(opts.manage ?? true),
                    canWorkIncidents: signal(opts.work ?? true),
                },
            },
            { provide: ObjectsService, useValue: { openCaseFromEntities } },
            { provide: ActionRequestsService, useValue: { propose } },
            { provide: MatDialog, useValue: { open } },
            { provide: ToastrService, useValue: toastr },
        ],
    });
    const fixture = TestBed.createComponent(InsightFeedComponent);
    fixture.componentRef.setInput('profile', 'sales');
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const btn = (id: string) => el.querySelector<HTMLButtonElement>(`[data-testid="${id}"]`);
    return { fixture, el, btn, openCaseFromEntities, propose, open, toastr };
}

describe('InsightFeedComponent — Case / Action Request (I-17)', () => {
    it('opens a Case carrying the evidence, the slice as stored, and links to it', async () => {
        const { fixture, el, btn, openCaseFromEntities } = create();
        btn('open-case')!.click();
        fixture.detectChanges();
        const [title, description, members] = openCaseFromEntities.mock.calls[0] as unknown as [
            string,
            string,
            { id: string; dataset: string }[],
        ];
        expect(title).toBe('Sales for masked:ab12 were 40% above usual.');
        expect(description).toContain('Slice: customer = masked:ab12');
        expect(description).toContain('Value: 140 · Expected: 100 · Delta: 40');
        expect(description).toContain('Score: 82 · Band: High');
        expect(description).toContain('Insight Type: anomaly');
        expect(description).toContain('Period: 2026-11-17');
        expect(description).toContain('/studio/insights?profile=sales&insight=i1');
        expect(members).toEqual([expect.objectContaining({ id: 'insight:i1', dataset: 'insights_sales' })]);
        const link = el.querySelector<HTMLAnchorElement>('[data-testid="opened"] a')!;
        expect(link.textContent?.trim()).toBe('CASE-7');
        expect(link.getAttribute('href')).toBe('/cases/CASE-7');
        await expectNoA11yViolations(el);
    });

    it('requests action: opens the Case, then raises the Action Request from it with the evidence', () => {
        const { fixture, el, btn, propose } = create({ dialogResult: { connection: 'ticketing', reason: 'Check it' } });
        btn('request-action')!.click();
        fixture.detectChanges();
        const body = (propose.mock.calls[0] as unknown as [Record<string, unknown>])[0];
        expect(body['caseId']).toBe('CASE-7');
        expect(body['connection']).toBe('ticketing');
        expect(body['reason']).toBe('Check it');
        const payload = body['payloadTemplate'] as { insight: Record<string, unknown> };
        expect(payload.insight).toEqual(
            expect.objectContaining({
                insightId: 'i1',
                profileId: 'sales',
                slice: 'customer = masked:ab12',
                score: 82,
            }),
        );
        const links = Array.from(el.querySelectorAll<HTMLAnchorElement>('[data-testid="opened"] a'));
        expect(links.map((a) => a.textContent?.trim())).toEqual(['CASE-7', 'AR-3']);
        expect(links[1].getAttribute('href')).toBe('/action-requests?id=AR-3');
    });

    it('a dismissed dialog opens nothing', () => {
        const { fixture, btn, openCaseFromEntities } = create();
        btn('request-action')!.click();
        fixture.detectChanges();
        expect(openCaseFromEntities).not.toHaveBeenCalled();
    });

    it('a refused request still shows the Case it opened', () => {
        const { fixture, el, btn, toastr } = create({
            dialogResult: { connection: 'ticketing', reason: '' },
            proposeFails: true,
        });
        btn('request-action')!.click();
        fixture.detectChanges();
        expect(toastr.error).toHaveBeenCalled();
        expect(el.querySelector('[data-testid="opened"]')?.textContent).toContain('CASE-7');
    });

    it('hides each action when its module is not installed', () => {
        const { btn } = create({ features: { insights: true } });
        expect(btn('open-case')).toBeNull();
        expect(btn('request-action')).toBeNull();
    });

    it('hides Request action without the Action Requests module', () => {
        const { btn } = create({ features: { insights: true, cases: true } });
        expect(btn('open-case')).not.toBeNull();
        expect(btn('request-action')).toBeNull();
    });

    it('disables the actions with the permission they need', () => {
        const { el, btn } = create({ manage: true, work: false });
        expect(btn('open-case')!.disabled).toBe(false);
        expect(btn('request-action')!.disabled).toBe(true);
        expect(btn('request-action')!.title).toBe('Needs Incident work.');
        expect(el.textContent).toContain('Request action: Needs Incident work.');
    });

    it('without Manage Incidents and Cases, both are disabled', () => {
        const { btn } = create({ manage: false });
        expect(btn('open-case')!.disabled).toBe(true);
        expect(btn('open-case')!.title).toBe('Needs the Manage Incidents and Cases permission.');
        expect(btn('request-action')!.disabled).toBe(true);
    });
});
