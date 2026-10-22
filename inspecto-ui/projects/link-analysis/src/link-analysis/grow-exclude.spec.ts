import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it } from 'vitest';
import type { ExclusionListSummary, GrowPreview, InvestigationLogEntry, WorkingSet } from '@inspecto/link-analysis/api/inv.service';
import {
    filterExpansion,
    growExpandBody,
    growIds,
    growKeepsEdge,
    heldReason,
    hiddenByListsLine,
    listTargets,
    listsInForce,
    packTerms,
    previewFailure,
    previewSentence,
    removedRows,
    roundRestricted,
    neighboursPreviewBody,
    secondStepWhyNot,
} from './grow-exclude';

const TELECOM = packTerms({ labels: { nodes: 'Numbers', links: 'Calls', rows: 'Rows' } });
const GENERIC = packTerms({ labels: { nodes: 'Nodes', links: 'Links', rows: 'Rows' } });

const preview = (p: Partial<GrowPreview> = {}): GrowPreview => ({
    addsNodes: 342,
    addsLinks: 1210,
    held: [{ id: 'cc', contacts: 9000 }],
    heldCount: 3,
    excludedByLists: [{ listId: 'call-centres', title: 'Call centres', removed: 41 }],
    truncated: false,
    readPath: 'index',
    ...p,
});

const entry = (step: number, op: InvestigationLogEntry['op'], params: InvestigationLogEntry['params'] = {}, undoneBy: number | null = null) =>
    ({ step, kind: 'op', op, params, undoneBy, author: null, at: '', workingSetHash: '', text: '' }) as InvestigationLogEntry;

const list = (id: string, over: Partial<ExclusionListSummary> = {}): ExclusionListSummary => ({
    id,
    title: id.toUpperCase(),
    entityType: 'msisdn',
    ownerKind: 'analyst',
    autoApply: false,
    visibility: 'open',
    size: 3,
    ...over,
});

describe('grow-exclude — wording', () => {
    it('reads the pack words, singular and plural, lower case', () => {
        expect(TELECOM).toEqual({ node: 'number', nodes: 'numbers', link: 'call', links: 'calls' });
        expect(GENERIC).toEqual({ node: 'node', nodes: 'nodes', link: 'link', links: 'links' });
        expect(packTerms({ labels: { nodes: 'Accounts', links: 'Transfers', rows: '' } }).node).toBe('account');
        expect(packTerms({ labels: { nodes: 'Parties', links: 'Links', rows: '' } }).node).toBe('party');
        expect(packTerms(null).nodes).toBe('nodes');
    });

    it('says the preview in one plain sentence with the pack words', () => {
        expect(previewSentence(preview(), TELECOM)).toBe(
            'This step adds about 342 numbers and 1,210 calls; 3 busy numbers will be held; 41 numbers are removed by your exclusion lists.',
        );
        expect(previewSentence(preview({ addsNodes: 0, addsLinks: 0, heldCount: 0, excludedByLists: [] }), GENERIC)).toBe(
            'This step adds nothing new.',
        );
        const flagged = previewSentence(preview({ truncated: true, needsApproval: true, reason: 'budget 5000 > 1000' }), GENERIC);
        expect(flagged).toContain('incomplete');
        expect(flagged).toContain('A second person must approve this step before it runs (budget 5000 > 1000).');
        expect(previewSentence(preview({ addsNodes: 1, addsLinks: 1, heldCount: 1, excludedByLists: [{ listId: 'x', title: 'X', removed: 1 }] }), TELECOM)).toBe(
            'This step adds about 1 number and 1 call; 1 busy number will be held; 1 number is removed by your exclusion lists.',
        );
    });

    it('rounds a restricted count to the nearest 5, at least 5, and keeps 0 at 0', () => {
        expect([0, 1, 2, 3, 7, 8, 12, 13, 41].map(roundRestricted)).toEqual([0, 5, 5, 5, 5, 10, 10, 15, 40]);
    });

    it('seals the held reason in the pack words', () => {
        expect(heldReason(500, TELECOM)).toBe('busy number above 500 contacts');
    });
});

describe('grow-exclude — two steps and the preview failure', () => {
    it('offers 2 steps only when the previewed first step stays under the cap, and says why otherwise', () => {
        expect(secondStepWhyNot(preview({ addsNodes: 10 }), 'ready', 2000, TELECOM)).toBeNull();
        expect(secondStepWhyNot(preview({ addsNodes: 3400 }), 'ready', 2000, TELECOM)).toBe(
            '2 steps is offered only when the first step adds at most 2,000 numbers; this one adds about 3,400.',
        );
        expect(secondStepWhyNot(null, 'loading', 2000, TELECOM)).toContain('Waiting');
        expect(secondStepWhyNot(null, 'unavailable', 2000, TELECOM)).toContain('none is available here');
        expect(secondStepWhyNot(preview({ needsApproval: true }), 'ready', 2000, TELECOM)).toContain('approval');
    });

    it('treats a missing or switched-off preview route as "no preview", and a refusal as a reason', () => {
        for (const status of [0, 404, 405, 501, 503])
            expect(previewFailure(new HttpErrorResponse({ status })).unavailable).toBe(true);
        expect(previewFailure(new TypeError('x is not a function')).unavailable).toBe(true);
        const refused = previewFailure(new HttpErrorResponse({ status: 422, error: { error: { message: 'frontier too large' } } }));
        expect(refused).toEqual({ unavailable: false, absent: false, message: 'frontier too large' });
        expect(previewFailure(new HttpErrorResponse({ status: 404 })).absent).toBe(true);
        expect(previewFailure(new HttpErrorResponse({ status: 503 })).absent).toBe(false); // switched off now, maybe not later
    });
});

describe('grow-exclude — the expand a Grow sends', () => {
    const ctx = { frontier: ['a', 'b'], heldFrontier: ['hub'] };
    const req = { direction: 'out' as const, steps: 1 as const, linkKinds: null, window: 'inherit' as const, hold: true, threshold: 300 };

    it('names the frontier, the direction and the hub threshold over the presets', () => {
        const presetWindow = { from: '2026-01-01T00:00:00Z', to: '2026-02-01T00:00:00Z' };
        expect(growExpandBody(growIds(ctx, req), req, { budget: 2000, window: presetWindow })).toEqual({
            op: 'expand',
            ids: ['a', 'b'],
            budget: 2000,
            direction: 'out',
            window: presetWindow,
            hubThreshold: 300,
        });
    });

    it('grows through the held edge (a recorded includeHubs override) when Hold is off, and reads all time on request', () => {
        const off = { ...req, hold: false, window: 'full' as const, linkKinds: ['voice'] };
        expect(growExpandBody(growIds(ctx, off), off, { window: { from: 'x' } })).toEqual({
            op: 'expand',
            ids: ['a', 'b', 'hub'],
            direction: 'out',
            linkKinds: ['voice'],
            window: 'full',
            includeHubs: true,
        });
    });
});

describe('grow-exclude — lists in force', () => {
    const ws: WorkingSet = {
        entities: [],
        links: [],
        excluded: [
            { id: 'x1', step: 2, reason: 'call centres' },
            { id: 'x2', step: 2, reason: 'call centres' },
            { id: 'v1', step: 4, reason: 'vip' },
            { id: 'h1', step: 5, reason: 'busy number above 500 contacts' },
        ],
        hash: 'h',
    };
    const entries = [
        entry(1, 'seed'),
        entry(2, 'excludeBy', { listId: 'cc' }),
        entry(3, 'expand'),
        entry(4, 'excludeBy', { listId: 'vip' }),
        entry(5, 'exclude', { ids: ['h1'] }),
        entry(6, 'excludeBy', { listId: 'gone' }, 7),
    ];
    const lists = [list('cc', { title: 'Call centres' }), list('vip', { title: 'VIPs', ownerKind: 'admin', visibility: 'restricted' })];

    it('lists every effective excludeBy with its removed count; only the latest step can be switched off', () => {
        const all = [...entries.slice(0, 4)];
        const out = listsInForce(all, ws, lists, true);
        expect(out.map((l) => [l.title, l.ownerKind, l.removed, l.step])).toEqual([
            ['Call centres', 'analyst', 2, 2],
            ['VIPs', 'admin', 1, 4],
        ]);
        expect(out[0].switchOffWhyNot).toContain('only the latest step');
        expect(out[1].switchOffWhyNot).toBeNull();
        // an undone excludeBy is not in force; a later step makes the VIP list switch-off-able no more
        expect(listsInForce(entries, ws, lists, true).map((l) => l.listId)).toEqual(['cc', 'vip']);
        expect(listsInForce(entries, ws, lists, true)[1].switchOffWhyNot).not.toBeNull();
    });

    it('a list applied twice: every application stays hidden when restricted, and Switch off says why it cannot', () => {
        const twice = [...entries.slice(0, 4), entry(5, 'excludeBy', { listId: 'vip' })];
        const ws2: WorkingSet = { ...ws, excluded: [...ws.excluded.filter((x) => x.id !== 'h1'), { id: 'v2', step: 5, reason: 'vip' }] };
        const out = listsInForce(twice, ws2, lists, false);
        expect(out[1]).toMatchObject({ steps: [4, 5], step: 5 });
        expect(out[1].switchOffWhyNot).toContain('Applied in 2 steps (4, 5)');
        const { rows } = removedRows(ws2, out, false);
        expect(rows.map((r) => r.id)).toEqual(['x1', 'x2']); // neither v1 (step 4) nor v2 (step 5)
    });

    it('rounds a restricted list for a viewer who may not see its members, and never lists them', () => {
        const out = listsInForce(entries, ws, lists, false);
        expect(out[1]).toMatchObject({ restricted: true, removed: 5 });
        const { rows, restrictedCount } = removedRows(ws, out, false);
        expect(rows.map((r) => r.id)).toEqual(['x1', 'x2', 'h1']);
        expect(rows[0].why).toBe('List: Call centres');
        expect(rows[2].why).toBe('busy number above 500 contacts');
        expect(restrictedCount).toBe(5);
        expect(removedRows(ws, listsInForce(entries, ws, lists, true), true).rows.map((r) => r.id)).toContain('v1');
    });

    it('falls back to the list id for a list it cannot name, and says the count in plain words', () => {
        const out = listsInForce(entries, ws, null, true);
        expect(out[0].title).toBe('cc');
        expect(hiddenByListsLine(out, TELECOM)).toBe('3 numbers hidden by 2 lists');
        expect(hiddenByListsLine([], TELECOM)).toBe('No exclusion list is in force.');
    });

    it('offers analyst lists of the Entity Type, and an admin list only with the category capability', () => {
        const all = [list('a'), list('b', { ownerKind: 'admin' }), list('c', { entityType: 'imei' })];
        expect(listTargets(all, 'msisdn', false).map((t) => [t.list.id, !!t.whyNot])).toEqual([
            ['a', false],
            ['b', true],
        ]);
        expect(listTargets(all, 'msisdn', true).every((t) => !t.whyNot)).toBe(true);
        expect(listTargets(null, null, true)).toEqual([]);
    });
});

describe('grow-exclude — the explorer (Basic)', () => {
    it('keeps the links of the chosen direction and kinds', () => {
        const e = { source: 'a', target: 'b' };
        expect(growKeepsEdge('a', { direction: 'either', linkKinds: null }, e, 'voice')).toBe(true);
        expect(growKeepsEdge('a', { direction: 'out', linkKinds: null }, e, 'voice')).toBe(true);
        expect(growKeepsEdge('a', { direction: 'in', linkKinds: null }, e, 'voice')).toBe(false);
        expect(growKeepsEdge('b', { direction: 'in', linkKinds: ['sms'] }, e, 'voice')).toBe(false);
    });

    it('cuts a neighbours read to the kept links and the nodes they touch, keeping its other fields', () => {
        const g = {
            nodes: [{ id: 'a' }, { id: 'b' }, { id: 'c' }],
            edges: [
                { source: 'a', target: 'b' },
                { source: 'c', target: 'a' },
            ],
            truncated: true,
        };
        const cut = filterExpansion(g, 'a', (x) => x.source === 'a');
        expect(cut).toEqual({ nodes: [{ id: 'a' }, { id: 'b' }], edges: [{ source: 'a', target: 'b' }], truncated: true });
    });

    it('the explorer preview is ONE body over the whole frontier: ids, direction, hold threshold, kinds only with a kind column', () => {
        const p = { datasetId: 'links-ds', sourceCol: 'source', targetCol: 'target', linkKindCol: 'kind' };
        const req = { direction: 'out' as const, linkKinds: ['call'], threshold: 250 };
        expect(neighboursPreviewBody(p, ['A', 'B'], req)).toEqual({
            dataset: 'links-ds',
            sourceCol: 'source',
            targetCol: 'target',
            linkKindCol: 'kind',
            ids: ['A', 'B'],
            direction: 'out',
            linkKinds: ['call'],
            hubThreshold: 250,
        });
        const bare = neighboursPreviewBody({ ...p, linkKindCol: '' }, ['A'], req);
        expect(bare).not.toHaveProperty('linkKindCol');
        expect(bare).not.toHaveProperty('linkKinds'); // the server refuses kinds without a kind column (422)
        expect(bare).not.toHaveProperty('value'); // the neighbours read's one-entity form is gone
        expect(bare).not.toHaveProperty('excludeBy'); // the explorer graph has no list in force
    });
});
