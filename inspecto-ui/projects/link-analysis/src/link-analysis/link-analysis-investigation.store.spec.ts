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
import { degreeOutcomeMessage, degreeState } from './investigate-number';

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
        reorderInvestigation: vi.fn(),
    };
    TestBed.configureTestingModule({ providers: [InvestigationSessionStore, { provide: InvService, useValue: inv }] });
    return { store: TestBed.inject(InvestigationSessionStore), inv, logs, sets };
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
            projection: { ...P, datasetId: 'telecom_links' },
            purpose: 'fraud alert 12',
            title: 'Suspect s',
            timeCol: 'last_seen',
            seed: 's',
            presets: { windowDays: 30, minEvents: 2, maxFanOut: 50, budget: 2000 },
            maxDegree: 4,
            degrees: 2,
        });
        expect(ok).toBe(true);
        expect(inv.createInvestigation).toHaveBeenCalledWith(
            expect.objectContaining({ title: 'Suspect s', purpose: 'fraud alert 12', timeCol: 'last_seen' }),
        );
        const ops = inv.appendInvestigationOp.mock.calls.map((c) => (c as unknown[])[1] as Record<string, unknown>);
        expect(ops.map((o) => [o['op'], o['ids']])).toEqual([
            ['seed', ['s']],
            ['expand', ['s']],
            ['expand', ['a', 'b']],
        ]);
        expect(ops[0]).toMatchObject({ entityType: 'msisdn' });
        expect(ops[1]).toMatchObject({ budget: 2000, minEvents: 2, maxFanOut: 50 });
        expect(ops[1]['window']).toEqual({ from: expect.any(String), to: expect.any(String) });
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
});
