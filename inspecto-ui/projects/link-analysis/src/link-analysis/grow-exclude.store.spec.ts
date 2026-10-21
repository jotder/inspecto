import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import {
    InvService,
    InvestigationLog,
    InvestigationLogEntry,
    InvestigationOpRequest,
    InvestigationStepResult,
    WorkingSet,
} from '@inspecto/link-analysis/api/inv.service';
import { LensService } from '@inspecto/core/api';
import { GrowContext, GrowRequest } from './grow-exclude';
import { GrowExcludeStore } from './grow-exclude.store';
import { InvestigationSessionStore } from './link-analysis-investigation.store';

const TERMS = { node: 'number', nodes: 'numbers', link: 'call', links: 'calls' };
const CTX: GrowContext = {
    mode: 'investigation',
    frontier: ['a'],
    heldFrontier: [],
    from: 'x',
    blocked: null,
    linkKinds: [],
    hubThreshold: 500,
    terms: TERMS,
    secondStepCap: 2000,
    hasWindow: false,
};
const REQ: GrowRequest = { direction: 'either', steps: 1, linkKinds: null, window: 'inherit', hold: true, threshold: 500 };

const step = (over: Partial<InvestigationStepResult> = {}): InvestigationStepResult =>
    ({
        step: 3,
        op: 'expand',
        delta: { admitted: ['b', 'c'], removed: [], linksAdded: 4, linksRemoved: 0, hidden: [], kept: [], excluded: [] },
        truncated: false,
        workingSet: { entities: 3, links: 4, excluded: 0, hash: 'h' },
        ...over,
    }) as InvestigationStepResult;

const http = (status: number) => () => throwError(() => new HttpErrorResponse({ status }));

function create(inv: Partial<Record<keyof InvService, unknown>> = {}, steps: InvestigationStepResult[] = [step()]) {
    const applied: InvestigationOpRequest[] = [];
    let at = 0;
    const session = {
        activeId: signal('inv-1'),
        log: signal<InvestigationLog | null>(null),
        workingSet: signal<WorkingSet | null>(null),
        lastStep: signal<InvestigationStepResult | null>(null),
        error: signal(''),
        entityListsChanged: signal(0),
        withPresets: (_p: object, r: object) => ({ budget: 100, ...r }),
        apply: vi.fn(async (op: InvestigationOpRequest) => {
            applied.push(op);
            const answer = steps[Math.min(at++, steps.length - 1)];
            session.lastStep.set(answer.status === 'pending' ? answer : { ...answer, step: record(op.op) });
            return true;
        }),
        // the real undo takes back the NEWEST effective op step
        undo: vi.fn(async () => {
            const all = session.log()?.entries ?? [];
            const last = [...all].reverse().find((e) => e.kind === 'op' && e.undoneBy == null);
            if (!last) return false;
            const at2 = Math.max(...all.map((e) => e.step)) + 1;
            session.log.set({
                ...session.log()!,
                entries: [...all.map((e) => (e === last ? { ...e, undoneBy: at2 } : e)), { ...last, step: at2, kind: 'undo', undoneBy: null }],
            });
            return true;
        }),
        expandHub: vi.fn(async () => {
            session.lastStep.set({ ...step(), step: record('expand') });
            return true;
        }),
    };
    /** Append an effective op entry to the log (what the real store's refresh would read back). */
    function record(op: string): number {
        const all = session.log()?.entries ?? [];
        const n = Math.max(0, ...all.map((e) => e.step)) + 1;
        session.log.set({
            header: {} as never,
            total: n,
            truncated: false,
            ...session.log(),
            entries: [...all, { step: n, kind: 'op', op: op as InvestigationLogEntry['op'], undoneBy: null, author: null, at: '', workingSetHash: '', text: '' }],
        });
        return n;
    }
    TestBed.configureTestingModule({
        providers: [
            GrowExcludeStore,
            { provide: InvService, useValue: inv },
            { provide: InvestigationSessionStore, useValue: session },
            {
                provide: LensService,
                useValue: { canManageExclusionCategories: signal(false), canRevealLinkEntities: signal(false) },
            },
        ],
    });
    return { store: TestBed.inject(GrowExcludeStore), session, applied };
}

describe('GrowExcludeStore (LA-GROW-EXCLUDE-1)', () => {
    it('reads /inv/exclusion-lists with owner kind, auto-apply and visibility', async () => {
        const lane = create({
            exclusionLists: () => of({ lists: [{ id: 'cc', title: 'CC', entityType: 'msisdn', ownerKind: 'analyst', autoApply: false, visibility: 'open', size: 1 }] }),
        });
        await lane.store.loadLists();
        expect(lane.store.lists()?.map((l) => l.id)).toEqual(['cc']);

    });

    it('falls back to the plain Entity Lists when the route is absent (404), keeping only live exclusion lists', async () => {
        const { store } = create({
            exclusionLists: http(404),
            listEntityLists: () =>
                of({
                    lists: [
                        { id: 'x', title: 'X', purpose: 'exclusion', entityType: 'msisdn', size: 2, retired: false },
                        { id: 'w', title: 'W', purpose: 'watch', entityType: 'msisdn', size: 2, retired: false },
                        { id: 'r', title: 'R', purpose: 'exclusion', entityType: 'msisdn', size: 2, retired: true },
                    ],
                    headSeq: 1,
                    headHash: null,
                }),
        });
        await store.loadLists();
        expect(store.lists()).toEqual([
            { id: 'x', title: 'X', entityType: 'msisdn', ownerKind: 'analyst', autoApply: false, visibility: 'open', size: 2 },
        ]);
    });

    it('shows no lists (null, never an error) when neither route answers', async () => {
        const { store } = create({ exclusionLists: http(503), listEntityLists: http(503) });
        await store.loadLists();
        expect(store.lists()).toBeNull();
    });

    it('Grow 2 steps: two recorded expands, the second from what the first admitted minus the held; Undo reverts both', async () => {
        const first = step({ read: { rowCount: 1, fingerprint: '', readAt: '', hubs: [{ id: 'c', degree: 900 }] } });
        const { store, applied, session } = create({}, [first, step({ delta: { ...first.delta, admitted: ['d'], linksAdded: 1 } })]);
        expect(await store.growInvestigation(CTX, { ...REQ, steps: 2 }, {})).toBe(true);
        expect(applied).toEqual([
            { op: 'expand', ids: ['a'], budget: 100, direction: 'either', hubThreshold: 500 },
            { op: 'expand', ids: ['b'], budget: 100, direction: 'either', hubThreshold: 500 },
        ]);
        expect(store.lastChange()?.text).toBe('Grew 2 steps: 3 numbers and 5 calls added; 1 busy number held.');
        expect(await store.undoLast()).toBe(true);
        expect(session.undo).toHaveBeenCalledTimes(2);
        expect(store.lastChange()?.text).toBe('Undone (2 steps).');
        expect(await store.undoLast()).toBe(false); // nothing of ours left: the screen's own Undo runs
    });

    it('Undo is offered only while the change is still the newest: a later step takes it away', async () => {
        const { store, session } = create();
        await store.growInvestigation(CTX, REQ, {});
        expect(store.undoable()).toBe(true);
        await session.apply({ op: 'expand', ids: ['z'] }); // e.g. a double-click expand after the Grow
        expect(store.undoable()).toBe(false);
        expect(await store.undoLast()).toBe(false); // the screen's own Undo runs instead
        expect(session.undo).not.toHaveBeenCalled();
        expect(store.lastChange()?.text).toContain('Grew one step');
    });

    it('a Grow held for four-eyes says so and offers no Undo', async () => {
        const { store } = create({}, [step({ status: 'pending', pending: { id: 'r1' } })]);
        await store.growInvestigation(CTX, REQ, {});
        expect(store.lastChange()).toEqual({ text: expect.stringContaining('second person') });
    });

    it('Remove held is ONE exclude with the busy reason; Undo is the undo op', async () => {
        const { store, applied, session } = create({}, [step({ op: 'exclude', delta: { ...step().delta, excluded: ['h1', 'h2'] } })]);
        await store.removeHeld(['h1', 'h2'], 500, TERMS);
        expect(applied).toEqual([{ op: 'exclude', ids: ['h1', 'h2'], reason: 'busy number above 500 contacts' }]);
        expect(store.lastChange()?.text).toBe('Removed 2 busy numbers.');
        await store.undoLast();
        expect(session.undo).toHaveBeenCalledTimes(1);
    });

    it('Add to a list: the members write, then excludeBy in the Investigation; a held (202) write says it waits', async () => {
        const changeEntityListMembers = vi.fn(() => of({ id: 'cc', changed: 1 }));
        const { store, applied } = create({ changeEntityListMembers });
        store.lists.set([{ id: 'cc', title: 'Call centres', entityType: 'msisdn', ownerKind: 'analyst', autoApply: false, visibility: 'open', size: 1 }]);
        const r = await store.addToList({ id: 'cc', entityType: 'msisdn' }, ['+4477'], 'switchboard', TERMS, true);
        expect(r.ok).toBe(true);
        expect(changeEntityListMembers).toHaveBeenCalledWith('cc', { add: ['+4477'], reason: 'switchboard' });
        expect(applied).toEqual([{ op: 'excludeBy', listId: 'cc', reason: 'switchboard' }]);
        expect(store.lastChange()?.text).toContain('Added to “Call centres” and applied');

    });

    it('Add to a NEW list creates an exclusion list first; a governed write is held, nothing applied', async () => {
        const createEntityList = vi.fn(() => of({ id: 'new-1', title: 'Shops' }));
        const changeEntityListMembers = vi.fn(() => of({ status: 'pending', written: false, pendingChange: { id: 'p1' } }));
        const { store, applied } = create({ createEntityList, changeEntityListMembers, exclusionLists: () => of({ lists: [] }) });
        const r = await store.addToList({ newTitle: 'Shops', entityType: 'msisdn' }, ['+44'], 'shops', TERMS, true);
        expect(createEntityList).toHaveBeenCalledWith({ title: 'Shops', purpose: 'exclusion', entityType: 'msisdn', reason: 'shops' });
        expect(r).toMatchObject({ ok: true, listId: 'new-1' });
        expect(r.message).toContain('Waiting for approval');
        expect(applied).toEqual([]);
    });

    it('a refused list write is a message, not a throw', async () => {
        const { store } = create({
            changeEntityListMembers: () => throwError(() => new HttpErrorResponse({ status: 403, error: { error: { message: 'no' } } })),
        });
        const r = await store.addToList({ id: 'vip', entityType: 'msisdn' }, ['+44'], 'x', TERMS, true);
        expect(r.ok).toBe(false);
        expect(r.message).toContain('not allowed');
    });

    it('switches a list off only when its step is the latest (the undo op), and on with excludeBy', async () => {
        const { store, session, applied } = create({}, [step({ op: 'excludeBy', list: { listId: 'cc', removed: 4 } })]);
        session.log.set({
            header: {} as never,
            entries: [
                { step: 1, kind: 'op', op: 'excludeBy', params: { listId: 'old' }, undoneBy: null, author: null, at: '', workingSetHash: '', text: '' },
                { step: 2, kind: 'op', op: 'excludeBy', params: { listId: 'cc' }, undoneBy: null, author: null, at: '', workingSetHash: '', text: '' },
            ],
            total: 2,
            truncated: false,
        });
        expect(await store.switchOff('old')).toBe(false);
        expect(session.undo).not.toHaveBeenCalled();
        expect(await store.switchOff('cc')).toBe(true);
        expect(session.undo).toHaveBeenCalledTimes(1);
        expect(await store.switchOn('test', TERMS)).toBe(true);
        expect(applied).toEqual([{ op: 'excludeBy', listId: 'test', reason: 'Switched on from Hidden by lists' }]);
        expect(store.lastChange()?.text).toBe('Switched “test” on: 4 numbers removed.');
    });

    it('the preview goes to the expand preview route with the same body; a 404 latches "unavailable"', async () => {
        const previewExpand = vi.fn(http(404));
        const { store } = create({ previewExpand });
        await store.runPreview({ ...CTX, heldFrontier: ['hub'] }, { ...REQ, hold: false }, {});
        expect(previewExpand).toHaveBeenCalledWith('inv-1', { op: 'expand', ids: ['a', 'hub'], budget: 100, direction: 'either', includeHubs: true });
        expect(store.preview().state).toBe('unavailable');
        // an absent route is not asked again this session
        await store.runPreview(CTX, REQ, {});
        expect(previewExpand).toHaveBeenCalledTimes(1);
        expect(store.preview().state).toBe('unavailable');
    });
    it('an Undo belongs to its Investigation: opening another drops it; reset() clears panels and held items', async () => {
        const { store, session } = create();
        TestBed.tick();
        await store.growInvestigation(CTX, REQ, {});
        TestBed.tick();
        expect(store.lastChange()?.undo).toBeDefined();
        session.activeId.set('inv-2');
        TestBed.tick();
        expect(store.lastChange()).toBeNull();
        store.growOpen.set(true);
        store.basicHeld.set(new Map([['x', 900]]));
        store.reset();
        expect([store.growOpen(), store.basicHeld().size, store.lastChange()]).toEqual([false, 0, null]);
    });
});
