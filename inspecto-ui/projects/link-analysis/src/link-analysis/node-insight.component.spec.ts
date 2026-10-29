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
import { signal } from '@angular/core';
import { Router, provideRouter } from '@angular/router';
import { LA_FEATURES } from '@inspecto/link-analysis/la-host';
import { CaseHistoryService, EntityHistory, EntityHistoryItem } from './case-history.service';

const item = (over: Partial<EntityHistoryItem>): EntityHistoryItem => ({
    kind: 'incident',
    id: 'i1',
    title: 'Wangiri burst',
    status: 'OPEN',
    createdAt: '2026-10-01T10:00:00Z',
    matchedOn: 'key.caller_msisdn',
    ...over,
});

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
    unresolved: 0,
    masked: false,
};
const CRM: ReferenceTable = {
    byKey: new Map([['999', { msisdn: '999', subscriber_type: 'prepaid', kyc_complete: false }]]),
    columns: [],
    truncated: false,
    unresolved: 0,
    masked: false,
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
        masked?: boolean;
        truncated?: boolean;
        /** The host's `investigations` flag (Standard and up); on by default. */
        investigations?: boolean;
        history?: (type: string, id: string, status: string) => Promise<EntityHistory>;
        maskedHistory?: (key: string, inv: string | null, status: string) => Promise<EntityHistory>;
    } = {},
) {
    const byEntity = vi.fn(opts.history ?? (() => Promise.resolve({ items: [], truncated: false } as EntityHistory)));
    const byMaskedKey = vi.fn(
        opts.maskedHistory ?? (() => Promise.resolve({ items: [], truncated: false } as EntityHistory)),
    );
    const close = vi.fn();
    const computeRisk = vi.fn(() => of({ ...riskView(), status: 'RUNNING' } as GraphRunView, riskView()));
    const addMember = vi.fn(opts.add ?? (() => of({ changed: 1 })));
    const svc = {
        lookup: vi.fn((ref: { dataset: string }, _keys: string[], _inv?: string | null) =>
            Promise.resolve({
                ...(ref.dataset === 'crm_kyc_dataset' ? CRM : INDICATORS),
                truncated: !!opts.truncated,
                masked: !!opts.masked,
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
            provideRouter([]),
            { provide: CaseHistoryService, useValue: { byEntity, byMaskedKey } },
            {
                provide: LA_FEATURES,
                useValue: {
                    ops: signal(true),
                    exchange: signal(false),
                    geoLink: signal(true),
                    investigations: signal(opts.investigations ?? true),
                },
            },
            { provide: NodeRiskService, useValue: svc },
            { provide: MatDialog, useValue: dialog },
            { provide: MatDialogRef, useValue: { close } },
            {
                provide: LinkAnalysisSettingsService,
                useValue: {
                    limits: signal({
                        propagatedRiskWeightsInForce: [1, 0.5],
                    }),
                },
            },
        ],
    });
    TestBed.overrideComponent(NodeInsightComponent, { set: { changeDetection: ChangeDetectionStrategy.Eager } });
    const fixture = TestBed.createComponent(NodeInsightComponent);
    fixture.componentRef.setInput('ctx', { ...CTX, ...ctx });
    fixture.detectChanges();
    return {
        fixture,
        c: fixture.componentInstance,
        el: fixture.nativeElement as HTMLElement,
        svc,
        dialog,
        close,
        byEntity,
        byMaskedKey,
    };
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
        expect(svc.lookup).toHaveBeenCalledWith(
            expect.objectContaining({ dataset: 'telecom_msisdn_indicators_dataset' }),
            ['999'],
            'inv',
        );
        expect(svc.lookup).toHaveBeenCalledWith(
            expect.objectContaining({ dataset: 'crm_kyc_dataset' }),
            ['999'],
            'inv',
        );
        expect(svc.lookup).toHaveBeenCalledWith(
            expect.objectContaining({ dataset: 'telecom_msisdn_indicators_dataset' }),
            ['999', '111', '222'],
            'inv',
        );
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

    it('after a direct add, re-checks membership and never claims no list can take the entity', async () => {
        const onListChanged = vi.fn();
        const { fixture, el, svc } = make({ onListChanged }, { matched: ['wl'] });
        await settle(fixture);
        el.querySelector<HTMLButtonElement>('[data-testid=add-block-bl]')!.click();
        await settle(fixture);
        expect(el.textContent).toContain('Added to');
        expect(onListChanged).toHaveBeenCalledTimes(1); // the Entity Lists panel re-reads its counts
        expect(svc.match.mock.calls.filter((a) => a[0] === 'bl').length).toBe(2); // re-checked
        expect(el.querySelector('[data-testid=add-block-bl]')).toBeNull(); // a member now, even if the read lags
        expect(el.querySelector('[data-testid=lists]')!.textContent).toContain('block: BL');
        expect(el.querySelector('[data-testid=lists-none]')).toBeNull();
    });

    it('renders no Actions section when there is nothing to show (no list to add to, no Hide)', async () => {
        const { fixture, el } = make({ canHide: false }, { matched: ['wl', 'bl'] });
        await settle(fixture);
        expect(el.querySelector('[data-testid=ni-actions]')).toBeNull();
        expect(el.querySelector('#ni-actions')).toBeNull();
        await expectNoA11yViolations(el);
    }, 30_000);

    it('says no list can take the entity only when none of its type is a watch / block list', async () => {
        const { fixture, el, svc } = make();
        svc.entityLists.mockReturnValue(of({ lists: [list('ok', 'allow')], headSeq: 1, headHash: null }));
        fixture.componentInstance.ngOnInit();
        await settle(fixture);
        expect(el.querySelector('[data-testid=lists-none]')).not.toBeNull();
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
        expect(el.textContent).toContain('Needs Incident management.');
    });

    it('disables list actions with the stated reason when the user cannot manage incidents; Hide closes with hide', async () => {
        const { fixture, el, close } = make({ canManageLists: false });
        await settle(fixture);
        expect(el.querySelector<HTMLButtonElement>('[data-testid=add-watch-wl]')!.disabled).toBe(true);
        expect(el.querySelector('[data-testid=lists-forbidden]')!.textContent).toContain('needs Incident management');
        el.querySelector<HTMLButtonElement>('[data-testid=hide-node]')!.click();
        expect(close).toHaveBeenCalledWith('hide');
    });

    it('asks Entity Lists with the exploration alias when the node holds an Investigation alias', async () => {
        const { fixture, svc } = make({ key: 'masked:inv0000000000001', listKey: 'masked:exp0000000000001' });
        await settle(fixture);
        expect(svc.match).toHaveBeenCalledWith('bl', ['masked:exp0000000000001']);
        expect(svc.match).not.toHaveBeenCalledWith(expect.anything(), ['masked:inv0000000000001']);
    });

    it('works in a masked Space: reads the node by its key, notes the masking, and still offers Compute risk', async () => {
        const { fixture, el, svc } = make({}, { masked: true });
        await settle(fixture);
        expect(svc.lookup).toHaveBeenCalled();
        expect(el.querySelector('[data-testid=risk-own]')!.textContent).toContain('40');
        expect(el.querySelector('[data-testid=risk-masked]')!.textContent).toContain('Entity masking is on');
        expect(el.querySelector('[data-testid=enrich-masked]')).not.toBeNull();
        expect(el.querySelector<HTMLButtonElement>('[data-testid=compute-risk]')!.disabled).toBe(false);
    });

    it('warns in both sections on a truncated read and blocks Compute risk with the reason', async () => {
        const { fixture, el } = make({}, { truncated: true });
        await settle(fixture);
        expect(el.querySelector('[data-testid=risk-truncated]')).not.toBeNull();
        expect(el.querySelector('[data-testid=enrich-truncated]')).not.toBeNull();
        expect(el.querySelector<HTMLButtonElement>('[data-testid=compute-risk]')!.disabled).toBe(true);
        expect(el.querySelector('[data-testid=compute-blocked]')!.textContent).toContain('understate risk');
    });

    it('reads case history only when opened: Incidents and Cases newest first, linked, with via and the truncation note', async () => {
        const { fixture, el, byEntity } = make(
            {},
            {
                history: () =>
                    Promise.resolve({
                        truncated: true,
                        items: [
                            item({ id: 'i1', severity: 'HIGH' }),
                            item({
                                kind: 'case',
                                id: 'c9',
                                title: 'Ring 7',
                                createdAt: '2026-10-05T10:00:00Z',
                                via: 'i1',
                                matchedOn: 'member',
                            }),
                        ],
                    }),
            },
        );
        await settle(fixture);
        expect(byEntity).not.toHaveBeenCalled();
        el.querySelector<HTMLButtonElement>('[data-testid=history-toggle]')!.click();
        await settle(fixture);
        expect(byEntity).toHaveBeenCalledWith('msisdn', '999', 'all');
        const rows = Array.from(el.querySelectorAll('[data-testid=history-list] li'));
        expect(rows.map((r) => r.textContent)).toEqual([
            expect.stringContaining('Ring 7'),
            expect.stringContaining('Wangiri'),
        ]);
        expect(rows[0].textContent).toContain('Case');
        expect(rows[0].textContent).toContain('via Incident i1');
        expect(rows[1].textContent).toContain('HIGH');
        const hrefs = Array.from(el.querySelectorAll('[data-testid=history-list] a')).map((a) =>
            a.getAttribute('href'),
        );
        expect(hrefs).toEqual(['/cases/c9', '/incidents/i1', '/incidents/i1']);
        expect(el.querySelector('[data-testid=history-truncated]')).not.toBeNull();
        await expectNoA11yViolations(el);

        const select = el.querySelector<HTMLSelectElement>('[data-testid=history-status]')!;
        select.value = 'closed';
        select.dispatchEvent(new Event('change'));
        await settle(fixture);
        expect(byEntity).toHaveBeenLastCalledWith('msisdn', '999', 'closed');
    });

    it('labels a Case row by how it matched: Investigation, sealed snapshot, or Incident member', async () => {
        const { fixture, el } = make(
            {},
            {
                history: () =>
                    Promise.resolve({
                        truncated: false,
                        items: [
                            item({ kind: 'case', id: 'c1', title: 'A', via: 'inv-7', matchedOn: 'investigation' }),
                            item({ kind: 'case', id: 'c2', title: 'B', via: 'snap-3', matchedOn: 'snapshot' }),
                            item({ kind: 'case', id: 'c3', title: 'C', via: 'i1', matchedOn: 'member' }),
                        ],
                    }),
            },
        );
        await settle(fixture);
        el.querySelector<HTMLButtonElement>('[data-testid=history-toggle]')!.click();
        await settle(fixture);
        const via = Array.from(el.querySelectorAll('[data-testid=history-via]')).map((v) => v.textContent!.trim());
        expect(via).toEqual(
            expect.arrayContaining(['via Investigation inv-7', 'via sealed snapshot', 'via Incident i1']),
        );
        expect(via.join(' ')).not.toContain('Incident inv-7');
    });

    it("shows the server's own truncation message and total when sent, else the fixed note", async () => {
        const { fixture, el } = make(
            {},
            {
                history: () =>
                    Promise.resolve({
                        truncated: true,
                        truncatedMessage: 'Scanned the newest 500 objects.',
                        total: 1240,
                        items: [item({ id: 'i1' })],
                    }),
            },
        );
        await settle(fixture);
        el.querySelector<HTMLButtonElement>('[data-testid=history-toggle]')!.click();
        await settle(fixture);
        const note = el.querySelector('[data-testid=history-truncated]')!.textContent!;
        expect(note).toContain('Scanned the newest 500 objects.');
        expect(note).toContain('1,240 in all');
        expect(note).not.toContain('Only the newest objects were searched');
    });

    it('shows the empty state for the chosen status', async () => {
        const { fixture, el, c } = make();
        c.historyStatus.set('open');
        c.toggleHistory();
        await settle(fixture);
        expect(el.querySelector('[data-testid=history-empty]')!.textContent).toContain(
            'No open Incident or Case references 999',
        );
        await expectNoA11yViolations(el);
    });

    it('looks a masked key up on the server (POST /inv/case-history), never through the raw-id route', async () => {
        const { fixture, el, byEntity, byMaskedKey } = make(
            { key: 'masked:0123456789abcdef', label: 'masked:0123…', entityType: undefined, investigationId: 'inv-1' },
            {
                maskedHistory: () =>
                    Promise.resolve({
                        items: [
                            {
                                kind: 'incident',
                                id: 'INC-1',
                                title: 'calls spike masked:0123456789abcdef',
                                status: 'OPEN',
                                createdAt: '2026-10-11T00:00:00Z',
                                matchedOn: 'key.caller_msisdn',
                            },
                        ],
                        truncated: false,
                    } as EntityHistory),
            },
        );
        el.querySelector<HTMLButtonElement>('[data-testid=history-toggle]')!.click();
        await settle(fixture);
        expect(byEntity).not.toHaveBeenCalled();
        expect(byMaskedKey).toHaveBeenCalledWith('masked:0123456789abcdef', 'inv-1', 'all', undefined);
        expect(el.querySelector('[data-testid=history-unavailable]')).toBeNull();
        expect(el.querySelector('[data-testid=history-list]')!.textContent).toContain(
            'calls spike masked:0123456789abcdef',
        );
        await expectNoA11yViolations(el);
    });

    it("passes a masked Entity's own type to the server lookup", async () => {
        const { fixture, el, byMaskedKey } = make({
            key: 'masked:ecc5b07e74c1c0e8',
            label: 'masked:ecc5…',
            entityType: 'account',
            investigationId: null,
        });
        el.querySelector<HTMLButtonElement>('[data-testid=history-toggle]')!.click();
        await settle(fixture);
        expect(byMaskedKey).toHaveBeenCalledWith('masked:ecc5b07e74c1c0e8', null, 'all', 'account');
    });

    it('states an unresolved masked key in words', async () => {
        const { fixture, el } = make(
            { key: 'masked:0123456789abcdef', label: 'masked:0123…' },
            {
                maskedHistory: () =>
                    Promise.resolve({
                        items: [],
                        truncated: false,
                        error: 'The server no longer knows this masked number.',
                    }),
            },
        );
        el.querySelector<HTMLButtonElement>('[data-testid=history-toggle]')!.click();
        await settle(fixture);
        expect(el.querySelector('[data-testid=history-error]')!.textContent).toContain('no longer knows');
        await expectNoA11yViolations(el);
    });

    it('states a failed read in plain language and retries on the next open', async () => {
        const { fixture, el, c, byEntity } = make(
            {},
            { history: () => Promise.resolve({ items: [], truncated: false, error: 'Too many lookups just now.' }) },
        );
        c.toggleHistory();
        await settle(fixture);
        expect(el.querySelector('[data-testid=history-error]')!.textContent).toContain('Too many lookups');
        await expectNoA11yViolations(el);
        c.toggleHistory();
        c.toggleHistory();
        await settle(fixture);
        expect(byEntity).toHaveBeenCalledTimes(2);
    });

    it('offers no Case history on Basic: the host says investigations is off, so the route is never called', async () => {
        const { fixture, el, byEntity, byMaskedKey } = make({}, { investigations: false });
        await settle(fixture);
        expect(el.querySelector('[data-testid=history-toggle]')).toBeNull();
        expect(el.textContent).not.toContain('Case history');
        expect(byEntity).not.toHaveBeenCalled();
        expect(byMaskedKey).not.toHaveBeenCalled();
    });

    it('links the Entity page when the host passes one, and offers nothing otherwise', () => {
        const { fixture, el } = make();
        expect(el.querySelector('[data-testid=entity-page-link]')).toBeNull();
        const page = TestBed.inject(Router).createUrlTree(['/', 'studio', 'entities', 'msisdn', '999']);
        fixture.componentRef.setInput('ctx', { ...CTX, entityPage: page });
        fixture.detectChanges();
        const a = el.querySelector('[data-testid=entity-page-link]')!;
        expect(a.textContent).toContain('Open the Entity page');
        expect(a.getAttribute('href')).toBe('/studio/entities/msisdn/999');
    });

    it('says when the profile maps no indicators Dataset', async () => {
        const { fixture, el, svc } = make({ profileId: 'generic' });
        await settle(fixture);
        expect(el.querySelector('[data-testid=risk-unmapped]')).not.toBeNull();
        expect(svc.lookup).not.toHaveBeenCalled();
    });
});
