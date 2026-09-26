import { HttpErrorResponse } from '@angular/common/http';
import { EntityProjection, G6Node } from 'app/inspecto/graph';
import { InvestigationLogEntry, WorkingSet, apiErrorMessage } from 'app/inspecto/api';
import { ProjectedGraph, entityId, projectTriples } from './entity-projection';

/**
 * LA-10 — the pure half of the Investigation panel: how a server Working Set becomes the canvas graph, which
 * raw ids an op sends, and what the op log says. No Angular, no HTTP — the store calls these.
 */

/**
 * What the SPA remembers about an Investigation it created. There is NO list or get-one route, so without this
 * the id is lost; it is persisted with the saved Link Analysis view (`LinkAnalysisView.investigations`).
 * `entityType` is the projection's own, so the Working Set's node ids agree with the query graph's (D-S4).
 */
export interface InvestigationRef {
    id: string;
    title?: string;
    entityType?: string;
    /** Set on a fork (D-E4) — the Investigation it was re-ordered from. */
    parentId?: string;
}

/**
 * The RAW Dataset values behind a canvas node. The server compares `CAST(col AS VARCHAR)` exactly, so an op
 * must send the spellings as read — never the normalised key inside the node id. A node folding two
 * spellings (D-S4) sends both.
 */
export function rawIdsOf(node: G6Node): string[] {
    return node.data.spellings?.length ? [...node.data.spellings] : [node.data.label];
}

/** The node's raw ids that are entities of the Working Set (hide/keep/expand name only those — else 422). */
export function idsInWorkingSet(node: G6Node, ws: WorkingSet | null): string[] {
    if (!ws) return [];
    const members = new Set(ws.entities.map((e) => e.id));
    return rawIdsOf(node).filter((id) => members.has(id));
}

/**
 * The Working Set as a graph, through the SAME fold as the query graph ({@link projectTriples} →
 * {@link entityId}), so an entity has one node id on both canvases. Seeded entities with no link yet are added
 * as isolated nodes. HIDDEN entities are left off the canvas (hide is display-only; they still count and are
 * listed by the panel). `truncated` means the browser node cap cut the drawing.
 */
export function workingSetToGraph(ws: WorkingSet, projection: EntityProjection): ProjectedGraph {
    const g = projectTriples(
        ws.links.map((l) => ({ source: l.source, target: l.target, kind: l.kind, count: l.count })),
        false,
        projection,
    );
    const byId = new Map(g.nodes.map((n) => [n.id, n]));
    for (const e of ws.entities) {
        const id = entityId(projection.entityType, e.id);
        const node = byId.get(id);
        if (!node) {
            const added = { id, data: { label: e.id, kind: 'entity', spellings: [e.id] } };
            byId.set(id, added);
            g.nodes.push(added);
        } else if (!node.data.spellings?.includes(e.id)) {
            // projectTriples trims; the server's id is the value as read, and an op must send exactly that.
            node.data.spellings = [...(node.data.spellings ?? []), e.id];
        }
    }
    const hidden = new Set(ws.entities.filter((e) => e.hidden).map((e) => e.id));
    if (!hidden.size) return g;
    const members = new Set(ws.entities.map((e) => e.id));
    const gone = new Set(
        g.nodes
            .filter((n) => {
                const own = rawIdsOf(n).filter((raw) => members.has(raw));
                return own.length > 0 && own.every((raw) => hidden.has(raw));
            })
            .map((n) => n.id),
    );
    return {
        nodes: g.nodes.filter((n) => !gone.has(n.id)),
        edges: g.edges.filter((e) => !gone.has(e.source) && !gone.has(e.target)),
        truncated: g.truncated,
    };
}

/** The op steps still in effect — what undo can revert and what a re-order permutes. */
export function effectiveOpSteps(entries: InvestigationLogEntry[]): InvestigationLogEntry[] {
    return entries.filter((e) => e.kind === 'op' && e.undoneBy == null);
}

/** Effective expands whose read hit the row limit: the Working Set is incomplete while any stands. */
export function truncatedSteps(entries: InvestigationLogEntry[]): number[] {
    return effectiveOpSteps(entries)
        .filter((e) => e.read?.truncated)
        .map((e) => e.step);
}

/** Move `order[index]` by `delta` places (clamped). Returns a new array. */
export function moveStep(order: number[], index: number, delta: number): number[] {
    const to = Math.max(0, Math.min(order.length - 1, index + delta));
    const out = [...order];
    const [s] = out.splice(index, 1);
    out.splice(to, 0, s);
    return out;
}

/**
 * A readable message for an Investigation route failure. 404 is deliberately ambiguous: the server answers it
 * for an unknown id AND for someone else's Investigation (owner-only), and for a bound Dataset the caller can
 * no longer view. 403 is the capability gate (`canManageIncidents`). 409 and 422 carry the server's own reason.
 */
export function investigationErrorMessage(err: unknown, fallback: string): string {
    const status = err instanceof HttpErrorResponse ? err.status : (err as { status?: number } | null)?.status;
    const server = apiErrorMessage(err, fallback);
    switch (status) {
        case 404:
            return (
                'This Investigation is not available — it does not exist, it is not yours, or its Dataset is no ' +
                'longer shared with you (the server answers the same for all three). Server: ' +
                server
            );
        case 403:
            return (
                'You are not allowed to change Investigations (it needs the Incident-management capability). Server: ' +
                server
            );
        case 409:
            return 'Refused — ' + server;
        case 422:
            return 'The server refused this step: ' + server;
        case 503:
            return (
                'Investigations are not available here — the link-analysis module or a write root is missing. Server: ' +
                server
            );
        default:
            return server;
    }
}

/**
 * LA-17 — a readable message for an Entity List failure: the `/inv/entity-lists*` routes, and (`onInvestigation`)
 * an `excludeBy` / `seedBy` step, where a 404 may be the list OR the Investigation. 409 is a retired list or one
 * whose Entity Type is no longer in force; 422 carries the server's reason (a blank reason, a value empty after
 * normalising, a list over 5 000 members …). 503 is a deployment state (no module / no write root), not a fault.
 */
export function entityListErrorMessage(err: unknown, fallback: string, onInvestigation = false): string {
    const status = err instanceof HttpErrorResponse ? err.status : (err as { status?: number } | null)?.status;
    const server = apiErrorMessage(err, fallback);
    switch (status) {
        case 404:
            return onInvestigation
                ? 'The Entity List or the Investigation is not available (the server answers the same when either ' +
                      'does not exist or is not yours). Server: ' +
                      server
                : 'This Entity List does not exist (any more). Server: ' + server;
        case 403:
            return (
                'You are not allowed to change Entity Lists (it needs the Incident-management capability). Server: ' +
                server
            );
        case 409:
            return 'Refused — ' + server;
        case 422:
            return 'The server refused this: ' + server;
        case 503:
            return 'Entity Lists are not available here — the link-analysis module or a write root is missing.';
        default:
            return server;
    }
}

/** The `maskingMode` pseudonym prefix (D-U6) — `masked:<16 hex>`. */
const MASKED_PREFIX = 'masked:';

/**
 * LA-17 — what "add this node to an Entity List" may send. The members route takes RAW values and normalises them
 * with the list's Entity Type, so a masked pseudonym would be stored AS a member (the server resolves pseudonyms
 * only in an Investigation op's `ids`). Those are split out, never sent.
 */
export function listableIds(node: G6Node | null): { ids: string[]; masked: number } {
    if (!node) return { ids: [], masked: 0 };
    const raw = rawIdsOf(node);
    const ids = raw.filter((v) => !v.startsWith(MASKED_PREFIX));
    return { ids, masked: raw.length - ids.length };
}
