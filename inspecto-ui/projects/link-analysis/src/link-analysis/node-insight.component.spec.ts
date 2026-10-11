import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { GraphRunView } from '@inspecto/link-analysis/api/graph-runs.service';
import { EntityListSummary } from '@inspecto/link-analysis/api/inv.service';
import { NodeInsightComponent, NodeInsightContext } from './node-insight.component';
import { NodeRiskService, ReferenceTable } from './node-risk.service';
import { LinkAnalysisSettingsService } from './link-analysis-settings.service';
import { InvService } from '@inspecto/link-analysis/api/inv.service';
import { signal } from '@angular/core';

const list = (id: string, purpose: EntityListSummary['purpose'], over: Partial<EntityListSummary> = {}) =>
    ({
        id,
        title: id.toUpperCase(),
        purpose,
        entityType: 'msisdn',
        size: 1,
        retired: false,
        createdAt: 't',
        createdBy: null,
        lastSeq: 1,
        ...over,
    }) as EntityListSummary;

const INDICATORS: ReferenceTable = {
    byKey: new Map([
        ['999', { msisdn: '999', indicator_score: 40, on_blocklist: 1, alarms_recent: 2 }],
        ['111', { msisdn: '111', indicator_score: 80 }],
    ]),
    columns: ['msisdn', 'indicator_score', 'on_blocklist', 'alarms_recent'],
    truncated: false,
};
const CRM: ReferenceTable = {
    byKey: new Map([['999', { msisdn: '999', subscriber_type: 'prepaid', kyc_complete: false }]]),
    columns: [],
    truncated: false,
};

const CTX: NodeInsightContext = {
    nodeId: 'c-999',
    label: '999',
    key: '999',
    profileId: 'telecom',
    entityType: 'msisdn',
    investigationId: 'inv',
    graphKey: 'inv:h1',
    serverId: '999',
    serverNodeIds: ['999', '111', '222'],
    originLabel: (id) => 'L' + id,
    canRunGraph: true,
    canManageLists: true,
    canHide: true,
};

function riskView(): GraphRunView {
    return {
        runId: 'r',
        status: 'COMPLETED',
        investigationId: 'inv',
        algorithm: 'propagatedRisk',
        engine: 'memory',
        budget: { maxNodes: 1, maxEdges: 1, timeoutMs: 1 },
        budgetClamped: false,
        consumed: { nodes: 3, edges: 2, elapsedMs: 1, work: 0 },
        progress: { work: 0, fraction: 1 },
        cancelRequested: false,
        cached: false,
        createdAt: 't',
        result: {
            algorithm: 'propagatedRisk',
            kind: 'PROPAGATED_RISK',
            dropped: 0,
            elapsedMs: 1,
            scores: [
                {
                    id: '999',
                    label: '999',
                    score: 68,
                    raw: 68,
                    own: 40,
                    contributors: 1,
                    factors: [{ origin: '111', distance: 3, weight: 0.35, contribution: 28 }],
                },
            ],
        },
    };
}

function make(
    ctx: Partial<NodeInsightContext> = {},
    opts: {
        add?: () => unknown;
        reason?: string | undefined;
        matched?: string[];
        masking?: string;
        truncated?: boolean;
    } = {},
) {
    const close = vi.fn();
    const computeRisk = vi.fn(() => of({ ...riskView(), status: 'RUNNING' } as GraphRunView, riskView()));
    const addMember = vi.fn(opts.add ?? (() => of({ changed: 1 })));
    const svc = {
        table: vi.fn((ref: { dataset: string }) =>
            Promise.resolve({
                ...(ref.dataset === 'crm_kyc' ? CRM : INDICATORS),
                truncated: !!opts.truncated,
            }),
        ),
        cachedRisk: vi.fn(() => null),
        computeRisk,
        entityLists: vi.fn(() =>
            of({
                lists: [
                    list('wl', 'watch'),
                    list('bl', 'block'),
                    list('ok', 'allow'),
                    list('old', 'block', { retired: true }),
                    list('imsi', 'block', { entityType: 'imsi' }),
                ],
                headSeq: 1,
                headHash: null,
            }),
        ),
        match: vi.fn((id: string, values: string[]) =>
            of({ matches: [{ value: values[0], matched: (opts.matched ?? []).includes(id) }] }),
        ),
        addMember,
    };
    const dialog = { open: vi.fn(() => ({ afterClosed: () => of('reason' in opts ? opts.reason : 'fraud ring') })) };
    TestBed.configureTestingModule({
        imports: [NodeInsightComponent],
        providers: [
            provideNoopAnimations(),
            { provide: NodeRiskService, useValue: svc },
            { provide: MatDialog, useValue: dialog },
            { provide: MatDialogRef, useValue: { close } },
            {
                provide: LinkAnalysisSettingsService,
                useValue: {
                    limits: signal({
                        maskingModeInForce: opts.masking ?? 'none',
                        propagatedRiskWeightsInForce: [1, 0.5],
                    }),
                },
            },
            { provide: InvService, useValue: { masking: signal(null) } },
        ],
    });
    TestBed.overrideComponent(NodeInsightComponent, { set: { changeDetection: ChangeDetectionStrategy.Eager } });
    const fixture = TestBed.createComponent(NodeInsightComponent);
    fixture.componentRef.setInput('ctx', { ...CTX, ...ctx });
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, el: fixture.nativeElement as HTMLElement, svc, dialog, close };
}

const settle = async (f: { detectChanges(): void; whenStable(): Promise<unknown> }) => {
    for (let i = 0; i < 3; i++) {
        await f.whenStable();
        await Promise.resolve();
        f.detectChanges();
    }
};

describe('NodeInsightComponent (telecom demo S2 UI)', () => {
    it('shows the own indicator score + factors, CRM/KYC enrichment and list membership; computes propagated risk on demand', async () => {
        const { fixture, el, svc } = make({}, { matched: ['bl'] });
        await settle(fixture);
        expect(el.querySelector('[data-testid=risk-own]')!.textContent).toContain('40');
        expect(el.querySelector('[data-testid=risk-own]')!.textContent).toContain('on_blocklist');
        expect(el.querySelector('[data-testid=enrich-rows]')!.textContent).toContain('prepaid');
        const lists = el.querySelector('[data-testid=lists]')!.textContent!;
        expect(lists).toContain('block: BL');
        expect(lists).not.toContain('IMSI'); // another Entity Type
        expect(svc.match).not.toHaveBeenCalledWith('old', expect.anything()); // retired

        el.querySelector<HTMLButtonElement>('[data-testid=compute-risk]')!.click();
        await settle(fixture);
        // seeded with every drawn node's positive indicator score; 222 has none
        // the cache key carries the weights in force, so a weights change never reads a stale answer
        expect(svc.computeRisk).toHaveBeenCalledWith('inv:h1|w=1,0.5', 'inv', { '999': 40, '111': 80 });
        expect(svc.cachedRisk).toHaveBeenCalledWith('inv:h1|w=1,0.5');
        const p = el.querySelector('[data-testid=risk-propagated]')!.textContent!;
        expect(p).toContain('Propagated score 68');
        expect(p).toContain('+28 from L111, 3 hops, weight 0.35');
        await expectNoA11yViolations(el);
    });

    it('holds Compute risk with the reason when there is no Investigation', async () => {
        const { fixture, el } = make({ investigationId: null, graphKey: null });
        await settle(fixture);
        expect(el.querySelector<HTMLButtonElement>('[data-testid=compute-risk]')!.disabled).toBe(true);
        expect(el.querySelector('[data-testid=compute-blocked]')!.textContent).toContain('open one');
    });

    it('adds to a list with the prompted reason; a four-eyes 202 reads as waiting for approval', async () => {
        const { fixture, el, svc, dialog } = make(
            {},
            { add: () => of({ status: 'pending', written: false, pendingChange: { id: 'p1' } }) },
        );
        await settle(fixture);
        expect(el.querySelector('[data-testid=add-allow-ok]')).toBeNull(); // only watch / block
        el.querySelector<HTMLButtonElement>('[data-testid=add-block-bl]')!.click();
        await settle(fixture);
        expect(dialog.open).toHaveBeenCalled();
        expect(svc.addMember).toHaveBeenCalledWith('bl', '999', 'fraud ring');
        expect(el.textContent).toContain('Waiting for approval');
    });

    it('does nothing when the reason prompt is cancelled, and says why a 403 failed', async () => {
        const { fixture, el, svc, c } = make(
            {},
            { add: () => throwError(() => new HttpErrorResponse({ status: 403 })), reason: undefined },
        );
        await settle(fixture);
        await c.addTo(c.addTargets()[0]);
        expect(svc.addMember).not.toHaveBeenCalled();
        TestBed.inject(MatDialog).open = vi.fn(() => ({ afterClosed: () => of('r') })) as never;
        await c.addTo(c.addTargets()[0]);
        fixture.detectChanges();
        expect(el.textContent).toContain('You are not allowed to change Entity Lists');
    });

    it('disables list actions with the stated reason when the user cannot manage incidents; Hide closes with hide', async () => {
        const { fixture, el, close } = make({ canManageLists: false });
        await settle(fixture);
        expect(el.querySelector<HTMLButtonElement>('[data-testid=add-watch-wl]')!.disabled).toBe(true);
        expect(el.querySelector('[data-testid=lists-forbidden]')!.textContent).toContain('Manage incidents');
        el.querySelector<HTMLButtonElement>('[data-testid=hide-node]')!.click();
        expect(close).toHaveBeenCalledWith('hide');
    });

    it('reads no reference Dataset while entity masking is on, and holds Compute risk', async () => {
        const { fixture, el, svc } = make({}, { masking: 'typed' });
        await settle(fixture);
        expect(svc.table).not.toHaveBeenCalled();
        expect(el.querySelector('[data-testid=risk-masked]')!.textContent).toContain('entity masking is on');
        expect(el.querySelector('[data-testid=enrich-masked]')).not.toBeNull();
        expect(el.querySelector('[data-testid=compute-risk]')).toBeNull();
    });

    it('warns in both sections on a truncated read and blocks Compute risk with the reason', async () => {
        const { fixture, el } = make({}, { truncated: true });
        await settle(fixture);
        expect(el.querySelector('[data-testid=risk-truncated]')).not.toBeNull();
        expect(el.querySelector('[data-testid=enrich-truncated]')).not.toBeNull();
        expect(el.querySelector<HTMLButtonElement>('[data-testid=compute-risk]')!.disabled).toBe(true);
        expect(el.querySelector('[data-testid=compute-blocked]')!.textContent).toContain('understate risk');
    });

    it('says when the profile maps no indicators Dataset', async () => {
        const { fixture, el, svc } = make({ profileId: 'generic' });
        await settle(fixture);
        expect(el.querySelector('[data-testid=risk-unmapped]')).not.toBeNull();
        expect(svc.table).not.toHaveBeenCalled();
    });
});
