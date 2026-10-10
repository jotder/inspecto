import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import {
    InvService,
    InvestigationHeader,
    InvestigationLog,
    InvestigationLogEntry,
    WorkingSet,
} from '@inspecto/link-analysis/api/inv.service';
import { EntityProjection } from '@inspecto/core/graph';
import { entityId } from '@inspecto/core/graph';
import { InvestigationSessionStore } from './link-analysis-investigation.store';

const P: EntityProjection = { datasetId: 'calls', sourceCol: 'A', targetCol: 'B', entityType: 'msisdn' };

function header(id: string, parent: InvestigationHeader['parent'] = null): InvestigationHeader {
    return {
        id,
        title: 'Burners',
        owner: 'alice',
        dataset: 'calls',
        sourceCol: 'A',
        targetCol: 'B',
        linkKindCol: null,
        createdAt: '2026-09-23T10:00:00Z',
        datasetVersion: null,
        parent,
    };
}

function entry(step: number, extra: Partial<InvestigationLogEntry>): InvestigationLogEntry {
    return {
        step,
        kind: 'op',
        author: 'alice',
        at: '',
        undoneBy: null,
        workingSetHash: '',
        text: `${step}.`,
        ...extra,
    };
}

function ws(ids: string[]): WorkingSet {
    return {
        entities: ids.map((id) => ({
            id,
            type: 'msisdn',
            hop: 0,
            seed: id,
            admittedBy: 1,
            hidden: false,
            kept: false,
        })),
        links: [],
        excluded: [],
        hash: ids.join(),
    };
}

/**
 * A fake server holding ONE mutable log per id — enough to assert what the store shows after each call.
 * `replay` answers the Working Set the test put in `sets`.
 */
function setup() {
    const logs: Record<string, InvestigationLog> = {};
    const sets: Record<string, WorkingSet> = {};
    const bases: Record<string, number> = {};
    const inv = {
        createInvestigation: vi.fn(() => {
            logs['inv-1'] = { header: header('inv-1'), entries: [], total: 0, truncated: false };
            return of(header('inv-1'));
        }),
        // A copy per call, as HTTP gives — the same reference twice would be skipped by signal equality.
        investigationLog: vi.fn((id: string) => of(structuredClone(logs[id]))),
        replayInvestigation: vi.fn((id: string, req: { reread?: boolean }) =>
            of({
                id,
                at: 0,
                workingSet: sets[id],
                equivalent: true,
                mismatches: [],
                reread: !!req.reread,
                drift: [],
                diverged: false,
            }),
        ),
        appendInvestigationOp: vi.fn(),
        undoInvestigation: vi.fn(),
        draftLog: vi.fn((id: string, d: string) => of(structuredClone(logs[id + '/' + d]))),
        draftReplay: vi.fn((id: string, d: string) =>
            of({
                baseStep: bases[id + '/' + d] ?? 0,
                at: 0,
                workingSet: sets[id + '/' + d],
                equivalent: true,
                mismatches: [],
                setMismatches: [] as number[],
            }),
        ),
        appendDraftOp: vi.fn(),
        undoDraft: vi.fn(),
        reorderInvestigation: vi.fn(),
    };
    TestBed.configureTestingModule({ providers: [InvestigationSessionStore, { provide: InvService, useValue: inv }] });
    return { store: TestBed.inject(InvestigationSessionStore), inv, logs, sets, bases };
}

const step = (n: number, op: string) => ({
    step: n,
    op,
    delta: { admitted: [], removed: [], linksAdded: 0, linksRemoved: 0, hidden: [], kept: [], excluded: [] },
    truncated: false,
    workingSet: { entities: 0, links: 0, excluded: 0, hash: '' },
});

describe('InvestigationSessionStore (LA-10)', () => {
    it('start sends the time column, so the coverage read has one (LA-SPA-OWED-SURFACES-1 defect)', async () => {
        const { store, inv } = setup();
        expect(await store.start(P, 'warrant 7', '', 'call_ts')).toBe(true);
        expect((inv.createInvestigation.mock.calls[0] as unknown[])[0]).toMatchObject({ timeCol: 'call_ts' });
    });

    it('start sends the time column zone only together with its column (the server refuses a lone zone)', async () => {
        const { store, inv } = setup();
        await store.start(P, 'warrant 7', '', 'call_ts', 'Asia/Riyadh');
        expect((inv.createInvestigation.mock.calls[0] as unknown[])[0]).toMatchObject({
            timeCol: 'call_ts',
            timeColZone: 'Asia/Riyadh',
        });
        await store.start(P, 'warrant 7', '', '', 'Asia/Riyadh');
        expect((inv.createInvestigation.mock.calls[1] as unknown[])[0]).not.toHaveProperty(
            'timeColZone',
            'Asia/Riyadh',
        );
    });

    it('start → seed → undo: the op log and the Working Set follow each step', async () => {
        const { store, inv, logs, sets } = setup();

        expect(await store.start(P, 'warrant 7', 'Burners')).toBe(true);
        expect(inv.createInvestigation).toHaveBeenCalledWith({
            title: 'Burners',
            purpose: 'warrant 7',
            dataset: 'calls',
            sourceCol: 'A',
            targetCol: 'B',
            linkKindCol: undefined,
        });
        expect(store.refs()).toEqual([{ id: 'inv-1', title: 'Burners', entityType: 'msisdn' }]);
        expect(store.activeId()).toBe('inv-1');
        expect(store.effectiveSteps()).toEqual([]);
        expect(store.canUndo()).toBe(false);

        // seed: the server appends step 1; the store re-reads log + replay
        inv.appendInvestigationOp.mockImplementation(() => {
            logs['inv-1'].entries.push(entry(1, { op: 'seed', params: { ids: ['4471'] } }));
            sets['inv-1'] = ws(['4471']);
            return of(step(1, 'seed'));
        });
        expect(await store.apply({ op: 'seed', ids: ['4471'], entityType: 'msisdn' })).toBe(true);
        expect(inv.appendInvestigationOp).toHaveBeenCalledWith('inv-1', {
            op: 'seed',
            ids: ['4471'],
            entityType: 'msisdn',
        });
        expect(store.effectiveSteps().map((e) => e.step)).toEqual([1]);
        expect(store.canUndo()).toBe(true);
        expect(store.canvas()?.nodes.map((n) => n.id)).toEqual([entityId('msisdn', '4471')]);

        // undo: a recorded log edit — step 1 is marked undone, the Working Set empties
        inv.undoInvestigation.mockImplementation(() => {
            logs['inv-1'].entries[0].undoneBy = 2;
            logs['inv-1'].entries.push(entry(2, { kind: 'undo', undoes: 1 }));
            sets['inv-1'] = ws([]);
            return of(step(2, 'undo'));
        });
        expect(await store.undo()).toBe(true);
        expect(store.log()?.entries.map((e) => e.step)).toEqual([1, 2]);
        expect(store.effectiveSteps()).toEqual([]);
        expect(store.canUndo()).toBe(false);
        expect(store.canvas()?.nodes).toEqual([]);
    });

    it('LA-UI-DRAFT-OPS-1: with a Draft as the working scope, steps and undo go to the Draft, never the main log', async () => {
        const { store, inv, logs, sets } = setup();
        await store.start(P, 'warrant 7');
        logs['inv-1/d-1'] = { header: header('inv-1'), entries: [], total: 0, truncated: false };
        sets['inv-1/d-1'] = ws([]);
        expect(await store.useDraft('d-1')).toBe(true);
        expect(inv.draftLog).toHaveBeenCalledWith('inv-1', 'd-1');

        inv.appendDraftOp.mockImplementation(() => {
            logs['inv-1/d-1'].entries.push(entry(1, { op: 'seed', params: { ids: ['4471'] } }));
            sets['inv-1/d-1'] = ws(['4471']);
            return of(step(1, 'seed'));
        });
        expect(await store.apply({ op: 'seed', ids: ['4471'] })).toBe(true);
        expect(inv.appendDraftOp).toHaveBeenCalledWith('inv-1', 'd-1', { op: 'seed', ids: ['4471'] });
        expect(inv.appendInvestigationOp).not.toHaveBeenCalled();
        expect(store.canvas()?.nodes.map((n) => n.id)).toEqual([entityId('msisdn', '4471')]);

        inv.undoDraft.mockImplementation(() => {
            sets['inv-1/d-1'] = ws([]);
            return of(step(2, 'undo'));
        });
        expect(await store.undo()).toBe(true);
        expect(inv.undoDraft).toHaveBeenCalledWith('inv-1', 'd-1');
        expect(inv.undoInvestigation).not.toHaveBeenCalled();
        expect(store.canvas()?.nodes).toEqual([]);

        // back to the main log; closing (e.g. re-open after promote) also leaves the Draft
        expect(await store.useDraft(null)).toBe(true);
        expect(store.activeDraftId()).toBeNull();
        await store.useDraft('d-1');
        store.close();
        expect(store.activeDraftId()).toBeNull();
    });

    it('LA-UI-DRAFT-FIX-1: a Draft that cannot be loaded never becomes the scope; the error is shown', async () => {
        const { store, inv, logs, sets } = setup();
        await store.start(P, 'warrant 7');
        sets['inv-1'] = ws(['4471']);
        logs['inv-1'].entries = [entry(1, { op: 'seed' })];
        await store.open('inv-1');
        inv.draftLog.mockReturnValueOnce(
            throwError(() => new HttpErrorResponse({ status: 404, error: { error: { message: 'no such draft' } } })),
        );
        expect(await store.useDraft('gone')).toBe(false);
        expect(store.activeDraftId()).toBeNull();
        expect(store.error()).not.toBe('');
        expect(store.log()?.entries.length).toBe(1);
        expect(store.workingSet()).toEqual(ws(['4471']));
    });

    it('LA-UI-DRAFT-FIX-1: Undo counts only the Draft’s OWN steps, never the main-log prefix', async () => {
        const { store, logs, sets, bases } = setup();
        await store.start(P, 'warrant 7');
        logs['inv-1/d-1'] = {
            header: header('inv-1'),
            entries: [entry(1, { op: 'seed' }), entry(2, { op: 'expand' })],
            total: 2,
            truncated: false,
        };
        sets['inv-1/d-1'] = ws(['4471']);
        bases['inv-1/d-1'] = 2;
        await store.useDraft('d-1');
        expect(store.canUndo()).toBe(false);

        logs['inv-1/d-1'].entries.push(entry(3, { op: 'exclude' }));
        logs['inv-1/d-1'].total = 3;
        await store.useDraft('d-1');
        expect(store.canUndo()).toBe(true);
    });

    it('LA-UI-DRAFT-FIX-1: Replay with a Draft as the scope replays the Draft, never the main log', async () => {
        const { store, inv, logs, sets } = setup();
        await store.start(P, 'warrant 7');
        logs['inv-1/d-1'] = { header: header('inv-1'), entries: [], total: 0, truncated: false };
        sets['inv-1/d-1'] = ws(['77']);
        await store.useDraft('d-1');
        inv.replayInvestigation.mockClear();
        inv.draftReplay.mockReturnValueOnce(
            of({
                baseStep: 0,
                at: 1,
                workingSet: ws(['77']),
                equivalent: false,
                mismatches: [],
                setMismatches: [1],
            }),
        );
        expect(await store.replay(false)).toBe(true);
        expect(inv.replayInvestigation).not.toHaveBeenCalled();
        expect(store.replayResult()?.equivalent).toBe(false);
        expect(store.replayResult()?.mismatches).toEqual([1]);
        expect(store.replayResult()?.reread).toBe(false);
    });

    it('LA-UI-DRAFT-FIX-1: re-opening after a promote leaves the Draft scope and reads the main log', async () => {
        const { store, inv, logs, sets } = setup();
        await store.start(P, 'warrant 7');
        sets['inv-1'] = ws([]);
        logs['inv-1/d-1'] = { header: header('inv-1'), entries: [], total: 0, truncated: false };
        sets['inv-1/d-1'] = ws([]);
        await store.useDraft('d-1');
        inv.investigationLog.mockClear();
        expect(await store.open('inv-1')).toBe(true); // what the host does on (promoted)
        expect(store.activeDraftId()).toBeNull();
        expect(inv.investigationLog).toHaveBeenCalledWith('inv-1');
    });

    it('re-ordering FORKS: the fork is remembered with its parent and becomes the open Investigation', async () => {
        const { store, inv, logs, sets } = setup();
        store.restore([{ id: 'inv-1', title: 'Burners', entityType: 'msisdn' }]);
        logs['inv-1'] = {
            header: header('inv-1'),
            entries: [entry(1, { op: 'seed' }), entry(2, { op: 'expand' })],
            total: 2,
            truncated: false,
        };
        sets['inv-1'] = ws(['a', 'b']);
        await store.open('inv-1');

        const lineage = { id: 'inv-1', order: [2, 1], parentSteps: 2 };
        inv.reorderInvestigation.mockImplementation(() => {
            logs['inv-fork'] = {
                header: header('inv-fork', lineage),
                entries: [entry(1, { op: 'expand' }), entry(2, { op: 'seed' })],
                total: 2,
                truncated: false,
            };
            sets['inv-fork'] = ws(['a']);
            return of({
                id: 'inv-fork',
                parent: lineage,
                steps: 2,
                workingSet: { entities: 1, links: 0, excluded: 0, hash: '' },
            });
        });

        expect(await store.fork([2, 1])).toBe(true);
        expect(inv.reorderInvestigation).toHaveBeenCalledWith('inv-1', { order: [2, 1] });
        expect(store.activeId()).toBe('inv-fork');
        expect(store.header()?.parent).toEqual(lineage);
        expect(store.refs().map((r) => [r.id, r.parentId])).toEqual([
            ['inv-1', undefined],
            ['inv-fork', 'inv-1'],
        ]);
        expect(store.activeRef()?.entityType).toBe('msisdn');
        expect(store.workingSet()?.entities.map((e) => e.id)).toEqual(['a']);
    });

    it('a 409 on undo and a 404 on open surface as readable messages, never as a silent success', async () => {
        const { store, inv } = setup();
        store.restore([{ id: 'inv-gone' }]);
        inv.investigationLog.mockImplementation(() =>
            throwError(
                () =>
                    new HttpErrorResponse({
                        status: 404,
                        error: { error: { message: "no investigation 'inv-gone'" } },
                    }),
            ),
        );
        expect(await store.open('inv-gone')).toBe(false);
        expect(store.error()).toContain('not yours');
        expect(store.busy()).toBe(false);

        inv.undoInvestigation.mockImplementation(() =>
            throwError(() => new HttpErrorResponse({ status: 409, error: { error: { message: 'nothing to undo' } } })),
        );
        expect(await store.undo()).toBe(false);
        expect(store.error()).toBe('Refused — nothing to undo');

        store.forget('inv-gone');
        expect(store.refs()).toEqual([]);
        expect(store.active()).toBe(false);
    });

    it('replay with reread keeps the drift answer and requests reread from the server', async () => {
        const { store, inv, logs, sets } = setup();
        logs['inv-1'] = { header: header('inv-1'), entries: [], total: 0, truncated: false };
        sets['inv-1'] = ws([]);
        store.restore([{ id: 'inv-1' }]);
        await store.open('inv-1');
        expect(await store.replay(true)).toBe(true);
        expect(inv.replayInvestigation).toHaveBeenLastCalledWith('inv-1', { reread: true });
        expect(store.replayResult()?.reread).toBe(true);
    });
});
