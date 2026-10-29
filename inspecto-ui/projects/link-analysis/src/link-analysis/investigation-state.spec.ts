import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it } from 'vitest';
import { InvestigationLogEntry, WorkingSet } from '@inspecto/link-analysis/api/inv.service';
import { EntityProjection } from '@inspecto/core/graph';
import { entityId } from '@inspecto/core/graph';
import {
    refusal403,
    entityListErrorMessage,
    identityErrorMessage,
    stepName,
    effectiveOpSteps,
    idsInWorkingSet,
    investigationErrorMessage,
    mergePairOf,
    moveStep,
    rawIdsOf,
    truncatedSteps,
    WORKING_SET_LINK_RENDER_CEILING,
    workingSetToGraph,
} from './investigation-state';

const P: EntityProjection = { datasetId: 'calls', sourceCol: 'A', targetCol: 'B', entityType: 'msisdn' };

function entity(id: string, extra: Partial<WorkingSet['entities'][number]> = {}) {
    return { id, type: null, hop: 0, seed: id, admittedBy: 1, hidden: false, kept: false, ...extra };
}

function entry(step: number, extra: Partial<InvestigationLogEntry> = {}): InvestigationLogEntry {
    return { step, kind: 'op', author: null, at: '', undoneBy: null, workingSetHash: '', text: `${step}.`, ...extra };
}

describe('Working Set entity types on the canvas (ONB-LA-ENDPOINT-TYPE-1)', () => {
    const ws = (entities: WorkingSet['entities']): WorkingSet => ({ entities, links: [], excluded: [], hash: 'h' });

    it('draws a typed entity with its Entity Type as the node kind, an untyped one as entity', () => {
        const g = workingSetToGraph(
            ws([entity('99979100001', { type: 'msisdn' }), entity('SHELL-A', { type: 'account' }), entity('x')]),
            P,
        );
        const kindOf = (raw: string) => g.nodes.find((n) => rawIdsOf(n).includes(raw))!.data.kind;
        expect(kindOf('99979100001')).toBe('msisdn');
        expect(kindOf('SHELL-A')).toBe('account');
        expect(kindOf('x')).toBe('entity');
    });

    it('keeps a masked id masked while typing its node', () => {
        const g = workingSetToGraph(ws([entity('masked:0123456789abcdef', { type: 'msisdn' })]), P);
        expect(g.nodes[0].data.kind).toBe('msisdn');
        expect(g.nodes[0].data.label).toBe('masked:0123456789abcdef');
    });

    it('builds the merge pair from the Working Set types for bare ids', () => {
        const set = ws([entity('99979100001', { type: 'msisdn' }), entity('SHELL-A', { type: 'account' }), entity('plain')]);
        expect(mergePairOf(['99979100001', 'SHELL-A'], set)).toEqual({
            pair: { a: { type: 'msisdn', value: '99979100001' }, b: { type: 'account', value: 'SHELL-A' } },
            reason: '',
        });
        expect(mergePairOf(['99979100001', 'plain'], set).reason).toContain('typed entities');
        const masked = ws([entity('masked:0123456789abcdef', { type: 'msisdn' }), entity('1', { type: 'imsi' })]);
        expect(mergePairOf(['masked:0123456789abcdef', '1'], masked).reason).toContain('masked entity cannot be merged');
    });
});

describe('LA-10 investigation-state', () => {
    it('draws typed Working Set ids that agree with the typed query graph (LA-17 D-M6)', () => {
        const typed: EntityProjection = { ...P, sourceType: { id: 'msisdn', normaliser: 'e164' } };
        const ws: WorkingSet = {
            entities: [entity('0044 1'), entity('Bob'), entity('+44 2')],
            links: [{ source: '0044 1', target: 'Bob', kind: 'call', count: 1, admittedBy: 2 }],
            excluded: [],
            hash: 'h',
        };
        const g = workingSetToGraph(ws, typed);
        // Bob is a TARGET (untyped column) — the link drew it, and the seed reuses that id, never re-minting it typed.
        expect(g.nodes.map((n) => n.id)).toEqual(['msisdn:+441', 'entity:msisdn:bob', 'msisdn:+442']);
    });

    it('draws the Working Set with entityId() ids — the SAME ids the query graph uses (D-S4)', () => {
        const ws: WorkingSet = {
            entities: [entity('Alice '), entity('Bob'), entity('Carol')],
            links: [{ source: 'Alice ', target: 'Bob', kind: 'call', count: 3, admittedBy: 2 }],
            excluded: [],
            hash: 'h',
        };
        const g = workingSetToGraph(ws, P);
        expect(g.nodes.map((n) => n.id).sort()).toEqual(
            [entityId('msisdn', 'Alice '), entityId('msisdn', 'Bob'), entityId('msisdn', 'Carol')].sort(),
        );
        // the seeded-but-unlinked entity is drawn as an isolated node carrying its raw value
        expect(g.nodes.find((n) => n.id === entityId('msisdn', 'Carol'))?.data.spellings).toEqual(['Carol']);
        expect(g.edges).toHaveLength(1);
        // the fold trims, but an op must send the id exactly as the server holds it
        const alice = g.nodes.find((n) => n.id === entityId('msisdn', 'Alice'))!;
        expect(idsInWorkingSet(alice, ws)).toEqual(['Alice ']);
    });

    it('marks a high-connectivity entity on the canvas, and only that one (telecom S3)', () => {
        const ws: WorkingSet = {
            entities: [entity('a'), entity('hub', { hop: 1, highConnectivity: true })],
            links: [{ source: 'a', target: 'hub', kind: 'call', count: 1, admittedBy: 2 }],
            excluded: [],
            hash: 'h',
        };
        const g = workingSetToGraph(ws, P);
        expect(g.nodes.filter((n) => n.data.highConnectivity).map((n) => n.id)).toEqual([entityId('msisdn', 'hub')]);
    });

    it('marks only the entities no expand has followed yet as unexpanded (LA-UNEXPANDED-MARKER-1)', () => {
        const ws: WorkingSet = {
            entities: [
                entity('a', { expanded: true }),
                entity('b', { expanded: false }),
                entity('x', { hop: 1, expanded: false }),
                entity('hub', { hop: 1, highConnectivity: true, expanded: false }),
            ],
            links: [
                { source: 'a', target: 'x', kind: 'call', count: 1, admittedBy: 2 },
                { source: 'a', target: 'hub', kind: 'call', count: 1, admittedBy: 2 },
            ],
            excluded: [],
            hash: 'h',
        };
        const g = workingSetToGraph(ws, P);
        const un = g.nodes.filter((n) => n.data.unexpanded).map((n) => n.id);
        expect(un.sort()).toEqual([entityId('msisdn', 'b'), entityId('msisdn', 'x')].sort());
        expect(g.nodes.find((n) => n.id === entityId('msisdn', 'a'))!.data.unexpanded).toBeUndefined();
        expect(g.nodes.find((n) => n.id === entityId('msisdn', 'hub'))!.data.unexpanded).toBeUndefined();
    });

    it('marks nothing when the server sends no expanded flag at all', () => {
        const ws: WorkingSet = { entities: [entity('a'), entity('b')], links: [], excluded: [], hash: 'h' };
        expect(workingSetToGraph(ws, P).nodes.some((n) => n.data.unexpanded)).toBe(false);
    });

    it("carries a flagged entity's contacts onto its canvas node; null contacts carry no count", () => {
        const ws: WorkingSet = {
            entities: [
                entity('a'),
                entity('hub', { hop: 1, highConnectivity: true, contacts: 650 }),
                entity('hub2', { hop: 1, highConnectivity: true, contacts: null }),
            ],
            links: [
                { source: 'a', target: 'hub', kind: 'call', count: 1, admittedBy: 2 },
                { source: 'a', target: 'hub2', kind: 'call', count: 1, admittedBy: 2 },
            ],
            excluded: [],
            hash: 'h',
        };
        const g = workingSetToGraph(ws, P);
        const byId = (raw: string) => g.nodes.find((n) => n.id === entityId('msisdn', raw))!.data;
        expect(byId('hub').contacts).toBe(650);
        expect(byId('hub2').highConnectivity).toBe(true);
        expect(byId('hub2').contacts).toBeUndefined();
        expect(byId('a').contacts).toBeUndefined();
    });

    it('draws EVERY link among a Working Set above the projection node cap (1 200 entities / 1 501 links)', () => {
        const ids = Array.from({ length: 1200 }, (_, i) => `e${i}`);
        const links = [
            ...ids.slice(1).map((t, i) => ({ source: ids[i], target: t, kind: 'k', count: 1, admittedBy: 2 })),
            ...ids.slice(2, 303).map((t, i) => ({ source: ids[i], target: t, kind: 'k', count: 1, admittedBy: 2 })),
            { source: 'e5', target: 'e900', kind: 'k', count: 1, admittedBy: 2 },
        ];
        expect(links).toHaveLength(1501);
        const ws: WorkingSet = { entities: ids.map((i) => entity(i)), links, excluded: [], hash: 'h' };
        const g = workingSetToGraph(ws, P);
        expect(g.nodes).toHaveLength(1200);
        expect(g.edges).toHaveLength(1501);
        expect(g.omittedLinks).toBeUndefined();
    });

    it('above the render ceiling draws exactly the ceiling, heaviest then id first, and counts what it left out', () => {
        const ids = Array.from({ length: 200 }, (_, i) => `n${i}`);
        const links = Array.from({ length: WORKING_SET_LINK_RENDER_CEILING + 20 }, (_, k) => ({
            source: ids[Math.floor(k / 199)],
            target: ids[(Math.floor(k / 199) + 1 + (k % 199)) % 200],
            kind: 'k',
            count: 1 + (k % 7),
            admittedBy: 2,
        }));
        const ws: WorkingSet = { entities: ids.map((i) => entity(i)), links, excluded: [], hash: 'h' };
        const g = workingSetToGraph(ws, P);
        expect(g.edges).toHaveLength(WORKING_SET_LINK_RENDER_CEILING);
        expect(g.omittedLinks).toBe(20);
        expect(g.nodes).toHaveLength(200); // every entity is still drawn
        const order = g.edges.map((e) => [(e.data as { count?: number }).count ?? 0, e.id] as const);
        const canonical = [...order].sort((a, b) => b[0] - a[0] || (a[1] < b[1] ? -1 : 1));
        expect(order).toEqual(canonical);
        // the lightest kept link weighs 3: every count-1/2 link is left out first (20 of the 2 857 lightest)
        expect(order[order.length - 1][0]).toBeLessThanOrEqual(3);
        expect(workingSetToGraph(ws, P).edges.map((e) => e.id)).toEqual(g.edges.map((e) => e.id));
    });

    it('leaves HIDDEN entities (and their links) off the canvas — hide is display-only', () => {
        const ws: WorkingSet = {
            entities: [entity('a'), entity('b', { hidden: true })],
            links: [{ source: 'a', target: 'b', kind: null, count: 1, admittedBy: 2 }],
            excluded: [],
            hash: 'h',
        };
        const g = workingSetToGraph(ws, P);
        expect(g.nodes.map((n) => n.id)).toEqual([entityId('msisdn', 'a')]);
        expect(g.edges).toEqual([]);
    });

    it('an op sends RAW spellings, and hide/keep/expand only the ones in the Working Set', () => {
        const node = { id: 'entity:x', data: { label: 'X', kind: 'entity', spellings: ['X', 'x.'] } };
        expect(rawIdsOf(node)).toEqual(['X', 'x.']);
        expect(rawIdsOf({ id: 'n', data: { label: 'plain', kind: 'dataset' } })).toEqual(['plain']);
        const ws: WorkingSet = { entities: [entity('x.')], links: [], excluded: [], hash: '' };
        expect(idsInWorkingSet(node, ws)).toEqual(['x.']);
        expect(idsInWorkingSet(node, null)).toEqual([]);
    });

    it('effective steps skip undo entries and undone ops; truncation counts only effective expands', () => {
        const read = (truncated: boolean) => ({
            dataset: 'calls',
            readAt: '',
            rowCount: 1,
            truncated,
            fingerprint: '',
        });
        const log = [
            entry(1, { op: 'seed' }),
            entry(2, { op: 'expand', read: read(true), undoneBy: 3 }),
            entry(3, { kind: 'undo', undoes: 2 }),
            entry(4, { op: 'expand', read: read(true) }),
        ];
        expect(effectiveOpSteps(log).map((e) => e.step)).toEqual([1, 4]);
        expect(truncatedSteps(log)).toEqual([4]);
    });

    it('moveStep moves one step and clamps at the ends', () => {
        expect(moveStep([1, 2, 3], 2, -1)).toEqual([1, 3, 2]);
        expect(moveStep([1, 2, 3], 0, -1)).toEqual([1, 2, 3]);
        expect(moveStep([1, 2, 3], 0, 1)).toEqual([2, 1, 3]);
    });

    it('maps 404/403/409/422 to readable messages; 409/422 carry the server reason, a 404 never prints it (X5)', () => {
        const err = (status: number, message: string) =>
            new HttpErrorResponse({ status, error: { error: { message } } });
        expect(investigationErrorMessage(err(404, "no investigation 'x'"), 'f')).toContain('not yours');
        expect(investigationErrorMessage(err(404, "no investigation 'x'"), 'f')).not.toContain("no investigation 'x'");
        expect(investigationErrorMessage(err(404, "no investigation 'x'"), 'f')).not.toContain('Server:');
        expect(investigationErrorMessage(err(403, "missing capability 'canManageIncidents'"), 'f')).toBe('Needs Incident management.');
        const heldRead = investigationErrorMessage(err(403, 'a neighbours read of one entity (degree 60) is a sensitive read [fan-out 60 > 1] - four-eyes applies'), 'f');
        expect(heldRead).toContain('four-eyes applies');
        expect(heldRead).not.toContain('capability');
        expect(investigationErrorMessage(err(409, 'nothing to undo'), 'f')).toBe('Refused: nothing to undo');
        expect(investigationErrorMessage(err(422, "'exclude' requires a 'reason'"), 'f')).toContain(
            "'exclude' requires a 'reason'",
        );
        expect(investigationErrorMessage(new Error('boom'), 'fallback')).toBe('fallback');
    });
});

describe('idsInWorkingSet through an exploration alias (DR-D2)', () => {
    const node = { id: 'n', data: { label: 'masked:aaaa', kind: 'entity', spellings: ['masked:aaaa'] } } as never;

    it('finds the Working Set entity whose exploreAliases entry is the alias the query graph shows', () => {
        const ws: WorkingSet = {
            entities: [entity('masked:bbbb')],
            links: [],
            excluded: [],
            exploreAliases: { 'masked:bbbb': 'masked:aaaa' },
            hash: '',
        };
        expect(idsInWorkingSet(node, ws)).toEqual(['masked:bbbb']);
    });

    it('is not in the Working Set when no pairing names it (positive twin: the same node WITH the pairing is)', () => {
        const ws: WorkingSet = { entities: [entity('masked:bbbb')], links: [], excluded: [], hash: '' };
        expect(idsInWorkingSet(node, ws)).toEqual([]);
    });
});

describe('stepName: plain step names, never an op id (LA-UI-TEXT-PASS-1 X9)', () => {
    it.each([
        ['seed', 'Start'],
        ['seedBy', 'Start from list'],
        ['expand', 'Expand'],
        ['excludeBy', 'Exclude by list'],
        ['hide', 'Hide'],
        ['keep', 'Keep'],
        ['undo', 'Undo'],
        ['someNewOp', 'Some new op'],
        [undefined, 'Step'],
    ])('%s reads as %s', (op, name) => {
        expect(stepName(op)).toBe(name);
    });
});

describe('refusal403: a true plain reason for every 403 (LA-UI-TEXT-PASS-1 X5 review)', () => {
    const e403 = (message: string) => new HttpErrorResponse({ status: 403, error: { error: { message } } });

    it('a member whose role is too low is told that, never that a permission is missing', () => {
        const msg = investigationErrorMessage(e403("your role on investigation 'inv-1' is analyst — only a lead changes it"), 'f');
        expect(msg).toBe('Only a lead can change this Investigation.');
        expect(investigationErrorMessage(e403("your role on investigation 'inv-1' is analyst — only a lead or reviewer decides this"), 'f')).toBe(
            'Only a lead or reviewer can decide this.',
        );
    });

    it('a restricted Entity List says so, on the Investigation route and the list routes', () => {
        const restricted = e403("entity list 'l-1' is restricted: only a holder of canManageExclusionCategories or canRevealLinkEntities may seed from it");
        expect(investigationErrorMessage(restricted, 'f')).toBe('This Entity List is restricted.');
        expect(entityListErrorMessage(restricted, 'f', true)).toBe('This Entity List is restricted.');
    });

    it('a policy deny says so', () => {
        expect(investigationErrorMessage(e403('denied by access policy'), 'f')).toBe('An access policy denies this.');
    });

    it('a missing capability is the permission, worded by the screen where it knows better', () => {
        expect(investigationErrorMessage(e403("missing capability 'canManageIncidents'"), 'f')).toBe('Needs Incident management.');
        expect(identityErrorMessage(e403("missing capability 'canManageIncidents'"), 'f')).toBe(
            'Identities need Incident management, also to read.',
        );
        // a restricted list on the identity route is still a restricted list, not the identity wording
        expect(identityErrorMessage(e403("entity list 'x' is restricted: …"), 'f')).toBe('This Entity List is restricted.');
    });

    it('a four-eyes decision keeps the server sentence', () => {
        expect(investigationErrorMessage(e403("four-eyes: 'a' requested this expand and cannot approve it"), 'f')).toBe(
            "Refused: four-eyes: 'a' requested this expand and cannot approve it",
        );
    });

    it('an unknown 403 is never guessed at: a generic reason and a Details line', () => {
        expect(investigationErrorMessage(e403('something new the server says'), 'f')).toBe(
            'This was refused. Details: something new the server says',
        );
        expect(refusal403('')).toBe('This was refused.');
    });
});
