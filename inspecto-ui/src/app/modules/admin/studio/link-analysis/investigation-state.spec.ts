import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it } from 'vitest';
import { InvestigationLogEntry, WorkingSet } from 'app/inspecto/api';
import { EntityProjection } from 'app/inspecto/graph';
import { entityId } from './entity-projection';
import {
    effectiveOpSteps,
    idsInWorkingSet,
    investigationErrorMessage,
    moveStep,
    rawIdsOf,
    truncatedSteps,
    workingSetToGraph,
} from './investigation-state';

const P: EntityProjection = { datasetId: 'calls', sourceCol: 'A', targetCol: 'B', entityType: 'msisdn' };

function entity(id: string, extra: Partial<WorkingSet['entities'][number]> = {}) {
    return { id, type: null, hop: 0, seed: id, admittedBy: 1, hidden: false, kept: false, ...extra };
}

function entry(step: number, extra: Partial<InvestigationLogEntry> = {}): InvestigationLogEntry {
    return { step, kind: 'op', author: null, at: '', undoneBy: null, workingSetHash: '', text: `${step}.`, ...extra };
}

describe('LA-10 investigation-state', () => {
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

    it('maps 404/403/409/422 to readable messages carrying the server reason', () => {
        const err = (status: number, message: string) =>
            new HttpErrorResponse({ status, error: { error: { message } } });
        expect(investigationErrorMessage(err(404, "no investigation 'x'"), 'f')).toContain('not yours');
        expect(investigationErrorMessage(err(404, "no investigation 'x'"), 'f')).toContain("no investigation 'x'");
        expect(investigationErrorMessage(err(403, 'missing canManageIncidents'), 'f')).toContain('not allowed');
        expect(investigationErrorMessage(err(409, 'nothing to undo'), 'f')).toBe('Refused — nothing to undo');
        expect(investigationErrorMessage(err(422, "'exclude' requires a 'reason'"), 'f')).toContain(
            "'exclude' requires a 'reason'",
        );
        expect(investigationErrorMessage(new Error('boom'), 'fallback')).toBe('fallback');
    });
});
