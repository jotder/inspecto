import { HttpErrorResponse } from '@angular/common/http';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import type { ConditionGroup } from '@inspecto/core/query/query-types';
import type {
    ExclusionListSummary,
    GrowPreview,
    InvestigationLogEntry,
    InvService,
    NeighborsRequest,
    WorkingSet,
} from '@inspecto/link-analysis/api/inv.service';
import { EntityProjectionGraphSource } from './entity-projection';
import {
    drawnValues,
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
    frontierCapStop,
    withUndrawnLinks,
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

describe('grow-exclude — the N-degree walk and the preview failure', () => {
    it('stops the walk only when the next degree would grow from more than the frontier cap, naming the degree reached', () => {
        expect(frontierCapStop(2000, 2000, 1, TELECOM)).toBeNull();
        expect(frontierCapStop(3400, 2000, 2, TELECOM)).toBe(
            'Stopped after degree 2: the next degree would grow from 3,400 numbers, above the frontier cap of 2,000. Select fewer, or remove busy numbers first.',
        );
    });

    it('the preview line covers degree 1 and says later degrees are estimated after it', () => {
        const p = preview({ addsNodes: 240, addsLinks: 300, heldCount: 3, excludedByLists: [] });
        expect(previewSentence(p, TELECOM, 2)).toBe(
            'Degree 1 adds about 240 numbers and 300 calls; 3 busy numbers will be held; degree 2 is estimated after degree 1 runs.',
        );
        expect(previewSentence(p, TELECOM, 4)).toContain('degrees 2–4 are estimated after degree 1 runs.');
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
    const req = { direction: 'out' as const, degrees: 1, linkKinds: null, hold: true, threshold: 300 };

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

    it('grows through the held edge (a recorded includeHubs override) when Hold is off; the Advanced rung wins over the presets', () => {
        const off = { ...req, hold: false, linkKinds: ['voice'], rung: { window: 'full' as const, minEvents: 2, budget: 50 } };
        expect(growExpandBody(growIds(ctx, off), off, { window: { from: 'x' }, budget: 2000 })).toEqual({
            op: 'expand',
            ids: ['a', 'b', 'hub'],
            direction: 'out',
            linkKinds: ['voice'],
            window: 'full',
            minEvents: 2,
            budget: 50,
            includeHubs: true,
        });
        // typed link kinds (no legend) come from the rung
        const typed = { ...req, rung: { linkKinds: ['sms'] } };
        expect(growExpandBody(['a'], typed, {})).toMatchObject({ linkKinds: ['sms'] });
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

    it('the explorer preview is ONE body over the whole frontier: ids, drawn, direction, hold threshold, kinds only with a kind column', () => {
        const p = { datasetId: 'links-ds', sourceCol: 'source', targetCol: 'target', linkKindCol: 'kind' };
        const req = { direction: 'out' as const, linkKinds: ['call'], threshold: 250 };
        expect(neighboursPreviewBody(p, ['A', 'B'], req, ['C'])).toEqual({
            dataset: 'links-ds',
            sourceCol: 'source',
            targetCol: 'target',
            linkKindCol: 'kind',
            ids: ['A', 'B'],
            drawn: ['C'],
            direction: 'out',
            linkKinds: ['call'],
            hubThreshold: 250,
        });
        const bare = neighboursPreviewBody({ ...p, linkKindCol: '' }, ['A'], req, []);
        expect(bare).not.toHaveProperty('linkKindCol');
        expect(bare).not.toHaveProperty('linkKinds'); // the server refuses kinds without a kind column (422)
        expect(bare).not.toHaveProperty('value'); // the neighbours read's one-entity form is gone
        expect(bare).not.toHaveProperty('excludeBy'); // the explorer graph has no list in force
        expect(bare).not.toHaveProperty('drawn'); // nothing else on the graph
        expect(bare).not.toHaveProperty('filter');
        expect(bare).not.toHaveProperty('attrCols');
    });

    it('the explorer preview reads with the binding the Grow reads with: columns, attribute columns, the view filter', async () => {
        // The defect (LA-GROW-EXCLUDE-1): the preview counted the whole Dataset while the Grow read under the view's filter,
        // and folded one link per pair while the Grow draws one per attribute value. Same binding, same counts.
        const p = { datasetId: 'pay', sourceCol: 'payer', targetCol: 'payee', linkKindCol: 'channel', attrCols: ['amount'] };
        const filter: ConditionGroup = {
            kind: 'group',
            op: 'AND',
            items: [{ kind: 'condition', field: 'amount', operator: '>=', value: '5000' }],
        };
        const neighbors = vi.fn((_body: NeighborsRequest) => of({ rows: [], truncated: false }));
        const source = new EntityProjectionGraphSource({} as never, { neighbors } as unknown as InvService);
        await source.expand('account:a', 'A', { projection: p, filter });
        const { value, ...read } = neighbors.mock.calls[0][0];
        expect(value).toBe('A');
        const req = { direction: 'either' as const, linkKinds: null, threshold: 500 };
        const preview = neighboursPreviewBody(p, ['A'], req, ['B'], filter);
        const defined = Object.entries(read).filter(([, v]) => v !== undefined);
        const keys = ['attrCols', 'dataset', 'filter', 'linkKindCol', 'sourceCol', 'targetCol'];
        expect(defined.map(([k]) => k).sort()).toEqual(keys);
        for (const [k, v] of defined) expect(preview[k as keyof typeof preview]).toEqual(v);
    });

    it('drawn: every node on the graph outside the frontier, by its spellings, the frontier neighbours first, capped', () => {
        const node = (id: string, label: string, spellings?: string[]) => ({ id, data: { label, spellings } });
        const g = {
            nodes: [node('a', 'A'), node('far', 'FAR'), node('b', 'B', ['B', 'b ']), node('c', 'C'), node('blank', ' ')],
            edges: [
                { source: 'a', target: 'b' },
                { source: 'c', target: 'a' },
            ],
        };
        expect(drawnValues(g, ['a'], 1000, 512)).toEqual(['B', 'b ', 'C', 'FAR']); // never a blank value (the server refuses one)
        expect(drawnValues(g, ['a'], 2, 512)).toEqual(['B', 'b ']);
        expect(drawnValues(g, ['a'], 1000, 2)).toEqual(['B', 'b ', 'C']); // nor one over input.id_length
        expect(drawnValues(g, ['a', 'b', 'c', 'far', 'blank'], 1000, 512)).toEqual([]);
    });

    it('the explorer preview adds the links between shown nodes that the graph does not draw yet (a graph cut by a limit)', () => {
        // The server: 2 links to new neighbours, 3 links whose both ends are on the graph. The graph draws 2 of those 3.
        const p = preview({ addsLinks: 2, linksWithinGraph: 3 });
        const edge = (source: string, target: string, kind = 'call') => ({ source, target, data: { kind } });
        const g = { edges: [edge('a', 'b'), edge('c', 'a', 'sms · 2'), edge('x', 'y')] };
        const either = { direction: 'either' as const, linkKinds: null };
        expect(withUndrawnLinks(p, g, ['a'], either).addsLinks).toBe(3); // a-b and c-a drawn: 1 more
        expect(withUndrawnLinks(p, g, ['a'], { direction: 'out', linkKinds: null }).addsLinks).toBe(4); // only a-b counts out
        expect(withUndrawnLinks(p, g, ['a'], { direction: 'in', linkKinds: ['sms'] }).addsLinks).toBe(4); // c-a, by its base kind
        expect(withUndrawnLinks(p, g, ['a', 'b', 'c', 'x'], either).addsLinks).toBe(2); // never below the server's own count
        expect(withUndrawnLinks(preview({ addsLinks: 2 }), g, ['a'], either).addsLinks).toBe(2); // an older server: unchanged
        expect(withUndrawnLinks(p, g, ['a'], either)).toMatchObject({ addsNodes: 342, heldCount: 3 }); // the rest as served
    });
});
