import { installTelecomFixture } from '@inspecto/link-analysis/graph/domain-profile.testing';
import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
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
import { Router } from '@angular/router';
import { degreeOutcomeMessage, degreeState } from './investigate-number';
import { LA_DATASETS } from '@inspecto/link-analysis/la-host';
import { DatasetRowsService } from '@inspecto/core/viz/dataset-rows.service';

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
function setup(extra: unknown[] = []) {
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
        deleteInvestigation: vi.fn(() => of({ id: 'x', deleted: true, steps: 0, drafts: 0 })),
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
        workingSetRelation: vi.fn((): Observable<{ rows: Record<string, unknown>[] }> => of({ rows: [] })),
    };
    TestBed.configureTestingModule({
        providers: [InvestigationSessionStore, { provide: InvService, useValue: inv }, ...(extra as never[])],
    });
    return { store: TestBed.inject(InvestigationSessionStore), inv, logs, sets, bases };
}

const step = (n: number, op: string) => ({
    step: n,
    op,
    delta: { admitted: [], removed: [], linksAdded: 0, linksRemoved: 0, hidden: [], kept: [], excluded: [] },
    truncated: false,
    workingSet: { entities: 0, links: 0, excluded: 0, hash: '' },
});

// The SPA holds no industry profile of its own (LA-EDITIONS-1): the telecom pack arrives from the server.
installTelecomFixture();

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

    it('LA-GROW-EXCLUDE-UNDO-RACE-1: undo names its step; a 409 (a newer step by a team member) re-reads the log', async () => {
        const { store, inv, logs, sets } = setup();
        await store.start(P, 'warrant 7');
        logs['inv-1'].entries.push(entry(1, { op: 'seed', params: { ids: ['4471'] } }));
        sets['inv-1'] = ws(['4471']);
        await store.open('inv-1');
        expect(store.undoTarget()).toBe(1); // the op log's Undo means step 1, as this browser read the log

        // meanwhile a team member records step 2; the server refuses the undo of step 1 and undoes nothing
        logs['inv-1'].entries.push(entry(2, { op: 'keep', params: { ids: ['4471'] } }));
        inv.undoInvestigation.mockImplementation(() =>
            throwError(
                () =>
                    new HttpErrorResponse({
                        status: 409,
                        error: { error: { message: 'step 1 is not the newest step any more: step 2 (keep by lead-1) is' } },
                    }),
            ),
        );
        const reads = inv.investigationLog.mock.calls.length;
        expect(await store.undo(1)).toBe(false);
        expect(inv.undoInvestigation).toHaveBeenCalledWith('inv-1', 1);
        expect(store.error()).toContain('step 1 is not the newest step any more');
        expect(inv.investigationLog.mock.calls.length).toBeGreaterThan(reads); // re-read: the screen now shows step 2
        expect(store.undoTarget()).toBe(2);
        expect(store.error()).toContain('step 1 is not the newest step any more'); // ...and the refusal stays readable

        logs['inv-1'] = { ...logs['inv-1'], truncated: true };
        await store.open('inv-1');
        expect(store.undoTarget()).toBeUndefined(); // a cut log read names no step rather than a wrong one
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
        expect(inv.undoDraft).toHaveBeenCalledWith('inv-1', 'd-1', undefined);
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
        expect(inv.undoInvestigation).toHaveBeenLastCalledWith('inv-gone', undefined);

        store.forget('inv-gone');
        expect(store.refs()).toEqual([]);
        expect(store.active()).toBe(false);
    });

    it('deleteInvestigation removes it server-side and forgets it; a 409 keeps it remembered and says why', async () => {
        const { store, inv, logs, sets } = setup();
        logs['inv-1'] = { header: header('inv-1'), entries: [], total: 0, truncated: false };
        sets['inv-1'] = ws([]);
        store.restore([{ id: 'inv-1', title: 'Burners' }]);
        await store.open('inv-1');

        inv.deleteInvestigation.mockImplementationOnce(() =>
            throwError(() => new HttpErrorResponse({ status: 409, error: { error: { message: "linked to open case 'CASE-1'" } } })),
        );
        expect(await store.deleteInvestigation('inv-1')).toBe(false);
        expect(store.error()).toContain('CASE-1');
        expect(store.refs().map((r) => r.id)).toEqual(['inv-1']);
        expect(store.active()).toBe(true);

        expect(await store.deleteInvestigation('inv-1')).toBe(true);
        expect(inv.deleteInvestigation).toHaveBeenLastCalledWith('inv-1');
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

    it('investigate a number: create → seed → degree 1 → degree 2, each from the outermost hop with the presets', async () => {
        const { store, inv, logs, sets } = setup();
        inv.createInvestigation.mockImplementation(() => {
            logs['inv-1'] = {
                header: { ...header('inv-1'), timeCol: 'last_seen' },
                entries: [],
                total: 0,
                truncated: false,
            };
            return of(header('inv-1'));
        });
        // A fake server over s—a, s—b, a—c, b—d, c—e: an expand admits each named entity's new neighbours one hop out.
        const adj: Record<string, string[]> = {
            s: ['a', 'b'],
            a: ['s', 'c'],
            b: ['s', 'd'],
            c: ['a', 'e'],
            d: ['b'],
            e: ['c'],
        };
        let n = 0;
        inv.appendInvestigationOp.mockImplementation(((_id: string, op: { op: string; ids?: string[] }) => {
            const cur = sets['inv-1'] ?? ws([]);
            const admitted: string[] = [];
            if (op.op === 'seed') {
                cur.entities.push({ ...ws(op.ids!).entities[0], hop: 0 });
                admitted.push(...op.ids!);
            } else {
                for (const id of op.ids!) {
                    const hop = cur.entities.find((e) => e.id === id)!.hop;
                    for (const nb of adj[id])
                        if (!cur.entities.some((e) => e.id === nb) && !admitted.includes(nb)) {
                            admitted.push(nb);
                            cur.entities.push({ ...ws([nb]).entities[0], hop: hop + 1 });
                        }
                }
            }
            sets['inv-1'] = { ...cur, entities: [...cur.entities] };
            const r = step(++n, op.op);
            return of({
                ...r,
                delta: { ...r.delta, admitted, linksAdded: admitted.length },
                truncated: n === 3,
                read: { rowCount: 1, fingerprint: '', readAt: '', fanOutCapped: n === 3 ? 4 : 0 },
            });
        }) as never);

        const ok = await store.investigateNumber({
            projection: { ...P, datasetId: 'telecom_links_dataset' },
            purpose: 'fraud alert 12',
            title: 'Suspect s',
            timeCol: 'last_seen',
            eventsCol: 'events',
            seed: 's',
            presets: { windowDays: 30, minEvents: 2, maxFanOut: 50, budget: 2000 },
            maxDegree: 4,
            degrees: 2,
            windowEnd: '2026-09-29T18:30:00.000Z',
        });
        expect(ok).toBe(true);
        expect(inv.createInvestigation).toHaveBeenCalledWith(
            expect.objectContaining({ title: 'Suspect s', purpose: 'fraud alert 12', timeCol: 'last_seen', eventsCol: 'events' }),
        );
        const ops = inv.appendInvestigationOp.mock.calls.map((c) => (c as unknown[])[1] as Record<string, unknown>);
        expect(ops.map((o) => [o['op'], o['ids']])).toEqual([
            ['seed', ['s']],
            ['expand', ['s']],
            ['expand', ['a', 'b']],
        ]);
        expect(ops[0]).toMatchObject({ entityType: 'msisdn' });
        expect(ops[1]).toMatchObject({ budget: 2000, minEvents: 2, maxFanOut: 50 });
        // the window ends at the Dataset's latest event time, not today
        expect(ops[1]['window']).toEqual({ from: '2026-08-30T18:30:00.000Z', to: '2026-09-29T18:30:01.000Z' });
        expect(store.degreeOutcomes()[0].range).toBe('30 days to 29 Sep 2026');
        expect(store.degreeOutcomes().map((o) => [o.degree, o.admitted, o.truncated, o.fanOutCapped])).toEqual([
            [1, 2, false, 0],
            [2, 2, true, 4],
        ]);

        // "Expand next degree" continues from the outermost hop: (c, d) admits e at degree 3
        expect(await store.expandNextDegree({ budget: 2000 }, 4)).toBe(true);
        expect((inv.appendInvestigationOp.mock.calls.at(-1) as unknown[])[1]).toEqual({
            op: 'expand',
            ids: ['c', 'd'],
            budget: 2000,
        });
        expect(await store.expandNextDegree({}, 4)).toBe(true); // degree 4: e has no new neighbour
        expect(store.degreeOutcomes().at(-1)).toMatchObject({ degree: 4, admitted: 0 });
        expect(await store.expandNextDegree({}, 4)).toBe(false);
        expect(store.error()).toBe('Degree 4 found no new entities — there is nothing further to expand.');
        // D4 (live run 5): one-hop / Expand anyway carry the preset window + budget too; All time and the
        // analyst's own fields win, and the hub opt-ins are passed through untouched.
        const presets = { windowDays: 30, budget: 10_000 };
        expect(store.withPresets(presets, { expandHubs: ['h'] })).toEqual({
            budget: 10_000,
            window: { from: '2026-08-30T18:30:00.000Z', to: '2026-09-29T18:30:01.000Z' },
            expandHubs: ['h'],
        });
        expect(store.withPresets(presets, { window: 'full', budget: 500, includeHubs: true })).toEqual({
            budget: 500,
            window: 'full',
            includeHubs: true,
        });
        store.close();
        expect(store.degreeOutcomes()).toEqual([]);
    });

    it('investigate a number stops at the first refusal and says why', async () => {
        const { store, inv } = setup();
        inv.appendInvestigationOp.mockImplementation((() =>
            throwError(
                () => new HttpErrorResponse({ status: 422, error: { error: { message: 'bad seed' } } }),
            )) as never);
        const ok = await store.investigateNumber({
            projection: P,
            purpose: 'p',
            title: 't',
            seed: 's',
            presets: {},
            maxDegree: 4,
            degrees: 2,
        });
        expect(ok).toBe(false);
        expect(inv.appendInvestigationOp).toHaveBeenCalledTimes(1);
        expect(store.error()).toBe('The server refused this step: bad seed');
        expect(store.active()).toBe(true); // the Investigation was created — it stays open
    });

    it('a held (four-eyes pending) degree is a waiting state, not an error, and the auto-expand stops there', async () => {
        const { store, inv, sets } = setup();
        let n = 0;
        inv.appendInvestigationOp.mockImplementation(((_id: string, op: { op: string; ids?: string[] }) => {
            n++;
            if (op.op === 'seed') {
                sets['inv-1'] = ws(['s']);
                return of({ ...step(n, 'seed'), delta: { ...step(n, 'seed').delta, admitted: ['s'] } });
            }
            return of({
                status: 'pending',
                pending: { id: 'p1' },
                workingSet: { entities: 1, links: 0, excluded: 0, hash: '' },
            });
        }) as never);
        const ok = await store.investigateNumber({
            projection: P,
            purpose: 'p',
            title: 't',
            seed: 's',
            presets: { maxFanOut: 50 },
            maxDegree: 4,
            degrees: 2,
        });
        expect(ok).toBe(true);
        expect(store.error()).toBe('');
        expect(inv.appendInvestigationOp).toHaveBeenCalledTimes(2); // seed + ONE held expand, no retry
        expect(store.degreeOutcomes()).toEqual([expect.objectContaining({ degree: 1, awaitingApproval: 'p1' })]);
        expect(degreeOutcomeMessage(store.degreeOutcomes()[0])).toContain('waiting for approval');
        expect(degreeState(store.workingSet(), 4, store.degreeOutcomes()).blocked).toContain(
            'waiting for approval (request p1)',
        );
    });

    it('in-flight guard: a second chain or expand while one runs is ignored; closing mid-chain stops it', async () => {
        const { store, inv, sets } = setup();
        let release!: () => void;
        const gate = new Promise<void>((r) => (release = r));
        let n = 0;
        inv.appendInvestigationOp.mockImplementation(((_id: string, op: { op: string }) => {
            n++;
            if (op.op === 'seed') {
                sets['inv-1'] = ws(['s']);
                return of({ ...step(n, 'seed'), delta: { ...step(n, 'seed').delta, admitted: ['s'] } });
            }
            // the first expand hangs until released; the Investigation is closed meanwhile
            return new Observable((sub) => {
                void gate.then(() => {
                    sets['inv-1'] = {
                        ...ws(['s', 'a']),
                        entities: [...ws(['s']).entities, { ...ws(['a']).entities[0], hop: 1 }],
                    };
                    sub.next({ ...step(n, 'expand'), delta: { ...step(n, 'expand').delta, admitted: ['a'] } });
                    sub.complete();
                });
            });
        }) as never);
        const req = { projection: P, purpose: 'p', title: 't', seed: 's', presets: {}, maxDegree: 4, degrees: 2 };
        const first = store.investigateNumber(req);
        await new Promise((r) => setTimeout(r));
        expect(store.chainRunning()).toBe(true);
        expect(await store.investigateNumber(req)).toBe(false); // the chain is already running
        expect(await store.expandNextDegree({}, 4)).toBe(false); // a degree is already in flight
        store.close();
        release();
        expect(await first).toBe(false);
        expect(store.chainRunning()).toBe(false);
        expect(inv.appendInvestigationOp).toHaveBeenCalledTimes(2); // seed + ONE expand: no second degree after close
        expect(store.degreeOutcomes()).toEqual([]);
    });
});

describe('InvestigationSessionStore - live-run fixes (la-showcase telecom)', () => {
    const pendingReq = (status: 'pending' | 'approved' | 'denied', extra: Record<string, unknown> = {}) => ({
        id: 'p2',
        investigationId: 'inv-1',
        op: 'expand' as const,
        params: {},
        sensitivity: { exceeded: [], budget: 1, maxFanOut: null, fourEyesBudgetAbove: null, fourEyesFanOutAbove: null },
        status,
        requestedBy: 'fm.analyst',
        requestedAt: '',
        ...extra,
    });

    /** Seed `s`, then hold the first expand as four-eyes request `p2`. */
    async function heldDegree() {
        const ctx = setup();
        const { store, inv, sets, logs } = ctx;
        let n = 0;
        inv.appendInvestigationOp.mockImplementation(((_id: string, op: { op: string }) => {
            n++;
            if (op.op === 'seed') {
                sets['inv-1'] = ws(['s']);
                return of({ ...step(n, 'seed'), delta: { ...step(n, 'seed').delta, admitted: ['s'] } });
            }
            logs['inv-1'].pending = [pendingReq('pending')];
            return of({
                status: 'pending',
                pending: { id: 'p2' },
                workingSet: { entities: 1, links: 0, excluded: 0, hash: '' },
            });
        }) as never);
        await store.investigateNumber({
            projection: P,
            purpose: 'p',
            title: 't',
            seed: 's',
            presets: {},
            maxDegree: 4,
            degrees: 2,
        });
        return ctx;
    }

    it('a held degree is listed once, and reload picks up the approval made elsewhere (N admitted)', async () => {
        const { store, sets, logs } = await heldDegree();
        expect(store.degreeOutcomes()).toHaveLength(1); // the log's pending list did not add a second line
        expect(store.awaitingApproval()).toBe('p2');
        // fm.manager approves in Oversight: the expand ran as step 3 and admitted a, b at hop 1
        logs['inv-1'].pending = [pendingReq('approved', { step: 3 })];
        sets['inv-1'] = {
            ...ws(['s']),
            entities: [
                ...ws(['s']).entities,
                ...['a', 'b'].map((id) => ({ ...ws([id]).entities[0], hop: 1, admittedBy: 3 })),
            ],
        };
        expect(await store.reload()).toBe(true);
        expect(store.awaitingApproval()).toBeNull();
        expect(store.degreeOutcomes()[0]).toMatchObject({ degree: 1, decision: 'approved', admitted: 2 });
        expect(degreeOutcomeMessage(store.degreeOutcomes()[0])).toBe(
            'Degree 1: approved (request p2) — 2 new entities admitted.',
        );
        expect(degreeState(store.workingSet(), 4, store.degreeOutcomes()).blocked).toBeNull();
    });

    it('reopening the same id refreshes in place; a denial unblocks the next degree', async () => {
        const { store, logs, inv } = await heldDegree();
        const reads = inv.investigationLog.mock.calls.length;
        logs['inv-1'].pending = [pendingReq('denied', { reason: 'too wide' })];
        expect(await store.open('inv-1')).toBe(true);
        expect(inv.investigationLog.mock.calls.length).toBe(reads + 1);
        expect(store.degreeOutcomes()[0]).toMatchObject({ decision: 'denied', reason: 'too wide' });
        expect(degreeOutcomeMessage(store.degreeOutcomes()[0])).toBe(
            'Degree 1: request p2 was denied (too wide) — nothing was read.',
        );
        expect(degreeState(store.workingSet(), 4, store.degreeOutcomes()).blocked).toBeNull();
    });

    it('opening an Investigation with a still-pending request shows it as waiting', async () => {
        const { store, logs, sets } = setup();
        logs['inv-9'] = {
            header: header('inv-9'),
            entries: [],
            total: 0,
            truncated: false,
            pending: [pendingReq('pending')],
        };
        sets['inv-9'] = ws(['s']);
        await store.open('inv-9');
        expect(store.degreeOutcomes()).toEqual([expect.objectContaining({ degree: 1, awaitingApproval: 'p2' })]);
    });

    it('the Advanced fields win over the presets on "Expand next degree" (minimum events per link)', async () => {
        const { store, inv, sets } = setup();
        await store.start(P, 'p');
        sets['inv-1'] = ws(['s']);
        await store.reload();
        inv.appendInvestigationOp.mockImplementation((() => of(step(1, 'expand'))) as never);
        await store.expandNextDegree({ minEvents: 2, maxFanOut: 50 }, 4, false, { minEvents: 1, includeHubs: true });
        expect((inv.appendInvestigationOp.mock.calls.at(-1) as unknown[])[1]).toEqual({
            op: 'expand',
            ids: ['s'],
            minEvents: 1,
            maxFanOut: 50,
        });
    });

    it('the scope list sends its supernodes with every expand, and "Only these" limits what is admitted', async () => {
        const { store, inv, sets } = setup();
        await store.start(P, 'p');
        sets['inv-1'] = ws(['s']);
        await store.reload();
        inv.appendInvestigationOp.mockImplementation((() => of(step(1, 'expand'))) as never);
        store.setScope({ include: ['b', 'a', 'a'], only: false });
        expect(store.scope()).toEqual({ include: ['a', 'b'], only: false });
        await store.expandNextDegree({}, 4);
        expect((inv.appendInvestigationOp.mock.calls.at(-1) as unknown[])[1]).toEqual(
            expect.objectContaining({ op: 'expand', expandHubs: ['a', 'b'] }),
        );
        expect((inv.appendInvestigationOp.mock.calls.at(-1) as unknown[])[1]).not.toHaveProperty('only');
        store.setScope({ include: ['a', 'b'], only: true });
        await store.apply({ op: 'expand', ids: ['s'] });
        expect((inv.appendInvestigationOp.mock.calls.at(-1) as unknown[])[1]).toEqual(
            expect.objectContaining({ expandHubs: ['a', 'b'], only: ['a', 'b'] }),
        );
        store.setScope({ include: [], only: true });
        expect(store.scope()).toEqual({ include: [], only: false });
    });

    it('a flagged entity on the scope list is in the next degree’s frontier, not left out', () => {
        const hub = { ...ws(['h']).entities[0], hop: 0, highConnectivity: true };
        const set = { ...ws(['s']), entities: [hub] };
        expect(degreeState(set, 4).heldHubs).toEqual(['h']);
        const through = degreeState(set, 4, [], false, new Set(), new Set(['h']));
        expect(through.frontier).toEqual(['h']);
        expect(through.heldHubs).toEqual([]);
    });

    it('a degree is the outermost hop the step admitted, and the step’s sealed hub counts are kept', async () => {
        const { store, inv, sets } = setup();
        await store.start(P, 'p');
        sets['inv-1'] = ws(['s']);
        await store.reload();
        inv.appendInvestigationOp.mockImplementation((() => {
            sets['inv-1'] = {
                ...ws(['s']),
                entities: [
                    ...ws(['s']).entities,
                    { ...ws(['h']).entities[0], hop: 2, admittedBy: 7, highConnectivity: true },
                ],
            };
            const r = step(7, 'expand');
            return of({
                ...r,
                delta: { ...r.delta, admitted: ['h'] },
                read: { rowCount: 1, fingerprint: '', readAt: '', hubsFlagged: 1, hubs: [{ id: 'h', degree: 650 }] },
            });
        }) as never);
        await store.expandNextDegree({}, 4);
        expect(store.degreeOutcomes().at(-1)).toMatchObject({ degree: 2, admitted: 1 });
        expect(store.hubContacts().get('h')).toBe(650);
    });
});

describe('InvestigationSessionStore - third live run (la-showcase telecom)', () => {
    const hubSet = (): WorkingSet => ({
        ...ws(['s']),
        entities: [...ws(['s']).entities, { ...ws(['h']).entities[0], hop: 1, admittedBy: 2, highConnectivity: true }],
    });

    it("reads a flagged hub's contacts from the Working Set rows when this session did not run its step", async () => {
        const { store, inv, logs, sets } = setup();
        logs['inv-9'] = { header: header('inv-9'), entries: [], total: 0, truncated: false };
        sets['inv-9'] = hubSet();
        inv.workingSetRelation.mockImplementation(() =>
            of({ rows: [{ entityId: 's', contacts: null }, { entityId: 'h', highConnectivity: true, contacts: 650 }] }),
        );
        await store.open('inv-9');
        expect(inv.workingSetRelation).toHaveBeenCalledWith('inv-9', { of: 'entities', limit: 10_000 });
        expect(store.hubContacts().get('h')).toBe(650);
        // the same Working Set head is not read again on a poll
        await store.reload();
        expect(inv.workingSetRelation).toHaveBeenCalledTimes(1);
    });

    it('keeps ?investigation=<id> in the URL while one is open and drops it on close', async () => {
        const { store, logs, sets } = setup();
        const nav = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        logs['inv-9'] = { header: header('inv-9'), entries: [], total: 0, truncated: false };
        sets['inv-9'] = ws(['s']);
        await store.open('inv-9');
        TestBed.tick();
        expect(nav).toHaveBeenLastCalledWith(
            [],
            expect.objectContaining({ queryParams: { investigation: 'inv-9' }, replaceUrl: true }),
        );
        store.close();
        TestBed.tick();
        expect(nav).toHaveBeenLastCalledWith([], expect.objectContaining({ queryParams: { investigation: null } }));
    });

    it('shows the typed title over a masked one and restores the profile from the bound Dataset', async () => {
        const { store, inv, logs, sets } = setup();
        inv.createInvestigation.mockImplementation(() => {
            const h = { ...header('inv-1'), title: 'masked:0a1b', dataset: 'telecom_links_dataset' };
            logs['inv-1'] = { header: h, entries: [], total: 0, truncated: false };
            return of(h);
        });
        sets['inv-1'] = ws([]);
        await store.start(P, 'p', 'Suspect 99979100001');
        expect(store.displayTitle()).toBe('Suspect 99979100001');
        expect(store.boundProfileId()).toBe('telecom');
    });
});

describe('InvestigationSessionStore - preset window recovered on reopen', () => {
    const TELECOM = { expand: { windowDays: 30 } };
    /** A reopened telecom Investigation with a time column; `rows.sql` answers `latest` (or fails). */
    function reopenable(entries: InvestigationLogEntry[], latest: { value?: string; error?: string }) {
        const sql = vi.fn(async () =>
            latest.error
                ? { rows: [], columns: [], truncated: false, error: latest.error }
                : { rows: [{ latest: latest.value }], columns: [], truncated: false },
        );
        const ctx = setup([
            { provide: LA_DATASETS, useValue: { get: () => of({ id: 'calls', name: 'calls', sourceName: 'calls_src' }) } },
            { provide: DatasetRowsService, useValue: { sql } },
        ]);
        ctx.logs['inv-r'] = {
            header: { ...header('inv-r'), timeCol: 'ts' },
            entries,
            total: entries.length,
            truncated: false,
        };
        ctx.sets['inv-r'] = ws(['s']);
        ctx.store.restore([{ id: 'inv-r', profileId: 'telecom' }]);
        ctx.inv.appendInvestigationOp.mockImplementation((() => of(step(9, 'expand'))) as never);
        return { ...ctx, sql };
    }
    const sentWindow = (inv: ReturnType<typeof setup>['inv']) =>
        (inv.appendInvestigationOp.mock.calls.at(-1) as unknown as [string, { window?: unknown }])[1].window;

    it('reopen with a sealed window: the next expand carries the SAME window, the anchor query is not run', async () => {
        const window = { from: '2026-08-30T12:00:00.000Z', to: '2026-09-29T12:00:01.000Z' };
        const { store, inv, sql } = reopenable(
            [entry(1, { op: 'seed' }), entry(2, { op: 'expand', params: { ids: ['s'], window } })],
            { value: '2026-10-05T00:00:00' },
        );
        expect(await store.open('inv-r')).toBe(true);
        expect(sql).not.toHaveBeenCalled();
        expect(store.windowEnd()).toBe('2026-09-29T12:00:00.000Z');
        await store.apply({ op: 'expand', ids: ['s'], ...store.withPresets(TELECOM.expand, {}) });
        expect(sentWindow(inv)).toEqual(window);
    });

    it('reopen with no sealed window: the zone-free anchor query sets the end (read as UTC)', async () => {
        const { store, inv, sql } = reopenable([entry(1, { op: 'seed' })], { value: '2026-09-29T23:30:00' });
        expect(await store.open('inv-r')).toBe(true);
        expect(sql).toHaveBeenCalledWith('calls_src', expect.stringContaining('strftime(MAX("ts")'), 1);
        expect(store.windowEnd()).toBe('2026-09-29T23:30:00.000Z');
        await store.apply({ op: 'expand', ids: ['s'], ...store.withPresets(TELECOM.expand, {}) });
        expect(sentWindow(inv)).toEqual({ from: '2026-08-30T23:30:00.000Z', to: '2026-09-29T23:30:01.000Z' });
    });

    it('when neither works no window is sent (all available data)', async () => {
        const { store, inv } = reopenable([entry(1, { op: 'seed' })], { error: 'refused' });
        expect(await store.open('inv-r')).toBe(true);
        expect(store.windowEnd()).toBeNull();
        await store.apply({ op: 'expand', ids: ['s'], ...store.withPresets(TELECOM.expand, {}) });
        expect(sentWindow(inv)).toBeUndefined();
    });
});

describe('InvestigationSessionStore - walk-through 6', () => {
    it('D3: a reopen rebuilds the per-degree lines from the log, not just "Degree N reached."', async () => {
        const { store, logs, sets } = setup();
        const at = (id: string, hop: number, admittedBy: number) => ({ ...ws([id]).entities[0], hop, admittedBy });
        logs['inv-7'] = {
            header: header('inv-7'),
            entries: [
                entry(1, { op: 'seed', params: { ids: ['s'] } }),
                entry(2, { op: 'expand', params: { ids: ['s'], window: 'full' } }),
            ],
            total: 2,
            truncated: false,
        };
        sets['inv-7'] = { ...ws([]), entities: [at('s', 0, 1), at('a', 1, 2), at('b', 1, 2)] };
        expect(await store.open('inv-7')).toBe(true);
        expect(store.degreeOutcomes().map(degreeOutcomeMessage)).toEqual([
            'Degree 1 (all available data): 2 new entities, 0 links added.',
        ]);
    });

    it('D1: the node dialog / Working Set rows badge names the sealed threshold, the count and the override', async () => {
        const { store, logs, sets } = setup();
        logs['inv-8'] = {
            header: header('inv-8'),
            entries: [
                entry(1, { op: 'seed', params: { ids: ['s'] } }),
                entry(2, { op: 'expand', params: { ids: ['s'], hubThreshold: 500 } }),
                entry(3, { op: 'expand', params: { ids: ['h'], expandHubs: ['h'] } }),
            ],
            total: 3,
            truncated: false,
        };
        sets['inv-8'] = {
            ...ws([]),
            entities: [{ ...ws(['h']).entities[0], hop: 1, admittedBy: 2, highConnectivity: true, contacts: 628 }],
        };
        await store.open('inv-8');
        expect(store.hubBadge('h', null, 999)).toBe(
            'High connectivity — 628 contacts (threshold 500), expanded through override',
        );
    });
});

describe('Neighbours (LA-EXPAND-PICK-NEIGHBOURS-1)', () => {
    const page = (neighbours: { id: string; count: number }[], total = neighbours.length, hasMore = false) =>
        of({ neighbours: neighbours.map((n) => ({ ...n, kinds: ['call'] })), total, offset: 0, limit: 50, hasMore });

    it('reads the selected entity once, ranks its neighbours and ignores a read for another selection', async () => {
        const { store, inv } = setup();
        const neighbors = vi.fn();
        (inv as unknown as { neighbourList: unknown }).neighbourList = neighbors;
        await store.start(P, 'warrant 7', 'Burners');
        neighbors.mockReturnValueOnce(
            page([
                { id: 'c', count: 7 },
                { id: 'b', count: 1 },
            ]),
        );
        await store.loadNeighbours('a');
        expect(neighbors).toHaveBeenCalledWith({
            dataset: 'calls',
            sourceCol: 'A',
            targetCol: 'B',
            linkKindCol: undefined,
            value: 'a',
            offset: 0,
        });
        expect(store.neighbours()).toMatchObject({ of: 'a', loading: false, error: '', total: 2, hasMore: false });
        expect(store.neighbours()?.rows.map((r) => r.id)).toEqual(['c', 'b']);
        store.clearNeighbours();
        expect(store.neighbours()).toBeNull();
    });

    it('on a masked Space sends the Space alias and shows this Investigation alias where the Working Set has one', async () => {
        const { store, inv, logs, sets } = setup();
        const neighbors = vi.fn();
        (inv as unknown as { neighbourList: unknown }).neighbourList = neighbors;
        await store.start(P, 'warrant 7', 'Burners');
        inv.appendInvestigationOp.mockImplementation(() => {
            logs['inv-1'].entries.push(entry(1, { op: 'seed' }));
            sets['inv-1'] = { ...ws(['masked:inv-a', 'masked:inv-b']), exploreAliases: { 'masked:inv-a': 'masked:sp-a', 'masked:inv-b': 'masked:sp-b' } };
            return of(step(1, 'seed'));
        });
        await store.apply({ op: 'seed', ids: ['masked:inv-a'] });
        neighbors.mockReturnValueOnce(
            page([
                { id: 'masked:sp-b', count: 4 },
                { id: 'masked:sp-z', count: 2 },
            ]),
        );
        await store.loadNeighbours('masked:inv-a');
        expect(neighbors).toHaveBeenCalledWith(expect.objectContaining({ value: 'masked:sp-a' }));
        // b is in the Working Set: shown (and sent back) under this Investigation's alias; z is not: the Space's alias stands
        expect(store.neighbours()?.rows.map((r) => r.id)).toEqual(['masked:inv-b', 'masked:sp-z']);
    });

    it('shows the real total, and Show more reads the next window and appends it', async () => {
        const { store, inv } = setup();
        const neighbors = vi.fn();
        (inv as unknown as { neighbourList: unknown }).neighbourList = neighbors;
        await store.start(P, 'warrant 7', 'Burners');
        neighbors.mockReturnValueOnce(page([{ id: 'b', count: 3 }], 3, true));
        await store.loadNeighbours('a');
        expect(store.neighbours()).toMatchObject({ total: 3, hasMore: true });
        neighbors.mockReturnValueOnce(page([{ id: 'c', count: 2 }, { id: 'd', count: 1 }], 3, false));
        await store.moreNeighbours();
        expect(neighbors).toHaveBeenLastCalledWith(expect.objectContaining({ offset: 1 }));
        expect(store.neighbours()?.rows.map((r) => r.id)).toEqual(['b', 'c', 'd']);
        expect(store.neighbours()).toMatchObject({ total: 3, hasMore: false });
    });

    it('states a failed read in words and keeps nothing', async () => {
        const { store, inv } = setup();
        (inv as unknown as { neighbourList: unknown }).neighbourList = vi.fn(() =>
            throwError(() => new HttpErrorResponse({ status: 403, error: { error: { message: 'too many contacts' } } })),
        );
        await store.start(P, 'warrant 7', 'Burners');
        await store.loadNeighbours('a');
        expect(store.neighbours()?.rows).toEqual([]);
        expect(store.neighbours()?.error).not.toBe('');
    });

    it('expandPicked sends ONE expand from the entity with only the chosen ids, and the hub override only when told', async () => {
        const { store, inv, logs, sets } = setup();
        await store.start(P, 'warrant 7', 'Burners');
        inv.appendInvestigationOp.mockImplementation(() => {
            logs['inv-1'].entries.push(entry(1, { op: 'expand' }));
            sets['inv-1'] = ws(['a']);
            return of(step(1, 'expand'));
        });
        expect(await store.expandPicked('a', ['b', 'c'], { budget: 100, includeHubs: true, expandHubs: ['x'] })).toBe(true);
        expect(inv.appendInvestigationOp).toHaveBeenLastCalledWith('inv-1', {
            op: 'expand',
            ids: ['a'],
            budget: 100,
            only: ['b', 'c'],
        });
        await store.expandPicked('h', ['b'], {}, true);
        expect(inv.appendInvestigationOp).toHaveBeenLastCalledWith('inv-1', {
            op: 'expand',
            ids: ['h'],
            only: ['b'],
            expandHubs: ['h'],
        });
    });
});
