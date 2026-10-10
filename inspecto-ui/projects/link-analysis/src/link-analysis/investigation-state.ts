import { HttpErrorResponse } from '@angular/common/http';
import { EntityProjection, EntityTypeRef, G6Node, typedEntityKey } from '@inspecto/core/graph';
import { InvestigationLogEntry, WorkingSet } from '@inspecto/link-analysis/api/inv.service';
import { apiErrorMessage } from '@inspecto/core/api';
import { resolveEntityId } from '@inspecto/core/graph';
import { ProjectedGraph, projectTriples } from './entity-projection';
import { readSourceNote } from './index-source';
import { limitRefusalMessage } from './limit-refusal';

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
    /** LA-17 D-M6: the projection's server-resolved column types, so Working Set ids are the query graph's. */
    sourceType?: EntityTypeRef;
    targetType?: EntityTypeRef;
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
    // DR-D2: the query graph shows the Space's alias, the Working Set this Investigation's own - `exploreAliases` pairs them.
    const viaExploration = new Map(Object.entries(ws.exploreAliases ?? {}).map(([inv, explore]) => [explore, inv]));
    return rawIdsOf(node).map((id) => (members.has(id) ? id : (viaExploration.get(id) ?? id))).filter((id) => members.has(id));
}

/**
 * The most Working Set links the canvas draws. The Working Set's entities are bounded server-side, so the
 * query graph's node cap does not apply; this is the one RENDER limit (G6 with many thousands of edges). Above
 * it the heaviest links are drawn and the footer states how many were left out — never a silent cut.
 */
export const WORKING_SET_LINK_RENDER_CEILING = 5000;

const weightOf = (e: { data: object }): number => (e.data as { count?: number }).count ?? 0;

/**
 * The Working Set as a graph, through the SAME fold as the query graph ({@link projectTriples} →
 * {@link entityId}), so an entity has one node id on both canvases. Seeded entities with no link yet are added
 * as isolated nodes. HIDDEN entities are left off the canvas (hide is display-only; they still count and are
 * listed by the panel). Every entity and every link is drawn up to {@link WORKING_SET_LINK_RENDER_CEILING}
 * links (heaviest first, then by id); `omittedLinks` says how many the ceiling left out.
 */
export function workingSetToGraph(ws: WorkingSet, projection: EntityProjection): ProjectedGraph {
    const g = projectTriples(
        ws.links.map((l) => ({ source: l.source, target: l.target, kind: l.kind, count: l.count })),
        false,
        projection,
        Infinity,
    );
    const byId = new Map(g.nodes.map((n) => [n.id, n]));
    for (const e of ws.entities) {
        // A Working Set entity records no column (D-M6): take the id a link already drew, else mint it as a source.
        const id = resolveEntityId([projection], e.id, (x) => byId.has(x));
        if (!id) continue; // an empty typed key mints no node, like a blank value
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
    const done = (out: ProjectedGraph): ProjectedGraph => {
        if (out.edges.length <= WORKING_SET_LINK_RENDER_CEILING) return out;
        const kept = [...out.edges]
            .sort((a, b) => weightOf(b) - weightOf(a) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0))
            .slice(0, WORKING_SET_LINK_RENDER_CEILING);
        return { ...out, edges: kept, omittedLinks: out.edges.length - kept.length };
    };
    if (!hidden.size) return done(g);
    const members = new Set(ws.entities.map((e) => e.id));
    const gone = new Set(
        g.nodes
            .filter((n) => {
                const own = rawIdsOf(n).filter((raw) => members.has(raw));
                return own.length > 0 && own.every((raw) => hidden.has(raw));
            })
            .map((n) => n.id),
    );
    return done({
        nodes: g.nodes.filter((n) => !gone.has(n.id)),
        edges: g.edges.filter((e) => !gone.has(e.source) && !gone.has(e.target)),
        truncated: g.truncated,
    });
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
    const limit = limitRefusalMessage(err);
    if (limit) return limit;
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
 * LA-17 — a readable message for an Entity List failure: the `/entity-lists*` routes, and (`onInvestigation`)
 * an `excludeBy` / `seedBy` step, where a 404 may be the list OR the Investigation. 409 is a retired list or one
 * whose Entity Type is no longer in force; 422 carries the server's reason (a blank reason, a value empty after
 * normalising, a list over 5 000 members …). 503 is a deployment state (no module / no write root), not a fault.
 */
export function entityListErrorMessage(
    err: unknown,
    fallback: string,
    onInvestigation = false,
    forbidden = 'You are not allowed to change Entity Lists (it needs the Incident-management capability).',
): string {
    const limit = limitRefusalMessage(err);
    if (limit) return limit;
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
            return forbidden + ' Server: ' + server;
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

/**
 * LA-17 slice 2 — the typed key the identity routes are sent: `<type>:<value normalised by the type's rule>`. The
 * group read matches EXACTLY, so the SPA must send the key as the server stores it. `null` when the type is unknown
 * or the value is empty after normalising (the server would 422 it; the group read would find nothing).
 */
export function identityKeyOf(type: EntityTypeRef | undefined, value: string): string | null {
    if (!type) return null;
    const key = typedEntityKey(type.id, value, type.normaliser);
    return key.length > type.id.length + 1 ? key : null;
}

/**
 * LA-17 slice 2 — a readable message for an `/inv/entity-identities*` failure. Reads need the Incident-management
 * capability too (under masking the group read is a membership oracle), so a 403 may come from a read. 409 is a
 * retract of an unknown or already-retracted assertion; 422 carries the server's reason. 503 is a deployment state.
 */
export function identityErrorMessage(err: unknown, fallback: string): string {
    const limit = limitRefusalMessage(err);
    if (limit) return limit;
    const status = err instanceof HttpErrorResponse ? err.status : (err as { status?: number } | null)?.status;
    const server = apiErrorMessage(err, fallback);
    switch (status) {
        case 403:
            return 'Identity resolution needs the Incident-management capability. Server: ' + server;
        case 409:
            return 'Refused — ' + server;
        case 422:
            return 'The server refused this: ' + server;
        case 503:
            return 'Identity resolution is not available here — the link-analysis module or a write root is missing.';
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

/** One sentence for a sealed expand's source: `Answered from the link index vN` or `Answered from the Dataset because ...`; null when the server said nothing. */
export function expandSourceNote(
    read: { index?: { version: number; stale: boolean }; fallback?: { reason: string; details?: string } } | undefined,
): string | null {
    return readSourceNote(read);
}
