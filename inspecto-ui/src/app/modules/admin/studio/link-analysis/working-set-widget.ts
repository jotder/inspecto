import { WorkingSetRelation, WorkingSetRelationName } from 'app/inspecto/api';
import type { WorkingSetBinding } from '../widgets/widget-types';

/**
 * LA-21 — the pure half of the **Working Set Widget** (decision D-E6): reading a binding, measuring drift between the
 * pinned relation and the head, and the tile's words. No Angular, no HTTP — the component only fetches and renders.
 */

const RELATIONS: readonly WorkingSetRelationName[] = ['entities', 'links', 'excluded'];

/** How a relation's rows are counted on the tile. */
export const RELATION_NOUN: Record<WorkingSetRelationName, { one: string; many: string; title: string }> = {
    entities: { one: 'entity', many: 'entities', title: 'Entities' },
    links: { one: 'link', many: 'links', title: 'Links' },
    excluded: { one: 'exclusion', many: 'exclusions', title: 'Exclusions' },
};

export function countOf(relation: WorkingSetRelationName, n: number): string {
    return `${n} ${n === 1 ? RELATION_NOUN[relation].one : RELATION_NOUN[relation].many}`;
}

/** A stored binding as the tile can use it, or null — a tile never guesses a mode or a pin it was not given. */
export function asWorkingSetBinding(x: unknown): WorkingSetBinding | null {
    if (!x || typeof x !== 'object') return null;
    const b = x as Partial<WorkingSetBinding>;
    const pin = b.pin as Partial<WorkingSetBinding['pin']> | undefined;
    if (!RELATIONS.includes(b.relation as WorkingSetRelationName)) return null;
    if (b.mode !== 'frozen' && b.mode !== 'live') return null;
    if (!pin || !Number.isInteger(pin.step) || (pin.step as number) < 0 || !pin.workingSetHash) return null;
    return b as WorkingSetBinding;
}

/** The binding a Widget saved now carries: the relation's current head IS the pin (Frozen unless Live is chosen). */
export function pinBinding(
    relation: WorkingSetRelationName,
    mode: 'frozen' | 'live',
    head: WorkingSetRelation['head'],
    now: Date = new Date(),
): WorkingSetBinding {
    return {
        relation,
        mode,
        pin: { step: head.step, workingSetHash: head.workingSetHash, pinnedAt: now.toISOString() },
    };
}

/** A row's identity within its relation — what "the same row" means when two heads are compared. */
export function rowKey(relation: WorkingSetRelationName, row: Record<string, unknown>): string {
    return relation === 'links'
        ? `${String(row['source'])}\u0001${String(row['target'])}\u0001${String(row['kind'] ?? '')}`
        : String(row['entityId']);
}

/** What changed in one relation between the pin and the head. */
export interface WorkingSetDrift {
    pinStep: number;
    nowStep: number;
    pinTotal: number;
    nowTotal: number;
    added: number;
    removed: number;
    /** The Working Set hash is the same — nothing moved at all. */
    unchanged: boolean;
    /** A side was truncated, so `added`/`removed` count only the rows that were read (the totals are still true). */
    partial: boolean;
}

export function workingSetDrift(pinned: WorkingSetRelation, now: WorkingSetRelation): WorkingSetDrift {
    const rel = now.relation;
    const before = new Set(pinned.rows.map((r) => rowKey(rel, r)));
    const after = new Set(now.rows.map((r) => rowKey(rel, r)));
    let added = 0;
    let removed = 0;
    for (const k of after) if (!before.has(k)) added++;
    for (const k of before) if (!after.has(k)) removed++;
    return {
        pinStep: pinned.head.step,
        nowStep: now.head.step,
        pinTotal: pinned.total,
        nowTotal: now.total,
        added,
        removed,
        unchanged: pinned.head.workingSetHash === now.head.workingSetHash,
        partial: pinned.truncated || now.truncated,
    };
}

/** §2.7's drift line: "pinned step 4: 12 entities · now step 9: 19 · 7 added · 0 removed since the pin". */
export function driftLine(relation: WorkingSetRelationName, d: WorkingSetDrift): string {
    if (d.unchanged) return `No change since the pin at step ${d.pinStep}.`;
    const line =
        `Pinned step ${d.pinStep}: ${countOf(relation, d.pinTotal)} · now step ${d.nowStep}: ${d.nowTotal}` +
        ` · ${d.added} added · ${d.removed} removed since the pin`;
    return d.partial ? `${line} (added/removed counted over the rows read — the relation is larger)` : line;
}

/** How a failed read renders. 404 is the D-E7 gate's answer to anyone but the owner — and to absence, on purpose. */
export function readFailure(err: unknown): 'unavailable' | 'pin-gone' | 'error' {
    const status = (err as { status?: number } | null)?.status;
    if (status === 404) return 'unavailable';
    if (status === 422) return 'pin-gone';
    return 'error';
}

export const NOT_AVAILABLE_MESSAGE =
    'This Widget reads an Investigation’s Working Set, which only the Investigation’s owner can read — or the ' +
    'Investigation no longer exists. Nothing from it is shown.';

export const BROKEN_PIN_MESSAGE =
    'The pinned step no longer evaluates to what was saved — the Investigation’s log changed on disk. Its rows are ' +
    'not shown, because they are not the evidence that was pinned.';
