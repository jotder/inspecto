import { HttpErrorResponse } from '@angular/common/http';
import { apiErrorMessage } from '@inspecto/core/api';
import type {
    ExclusionListSummary,
    ExpandRung,
    GrowPreview,
    InvestigationLogEntry,
    InvestigationOpRequest,
    NeighborsPreviewRequest,
    WorkingSet,
} from '@inspecto/link-analysis/api/inv.service';
import type { EntityProjection } from '@inspecto/core/graph';
import type { DomainProfile } from '@inspecto/link-analysis/graph/domain-profile';

/**
 * LA-GROW-EXCLUDE-1 — the pure half of Grow, the Held tray and Exclude (plan `docs/superpower/la-grow-exclude-plan.md`
 * §3–§4): the plain-language wording, the request a Grow sends, the lists in force and what they removed. No Angular,
 * no HTTP — the components and `GrowExcludeStore` call these.
 */

/** The three directions the Grow panel offers (`reciprocal` stays in the full expand form). */
export type GrowDirection = 'either' | 'out' | 'in';

export const GROW_DIRECTIONS: readonly { value: GrowDirection; label: string; icon: string; hint: string }[] = [
    {
        value: 'either',
        label: 'Both ways',
        icon: 'heroicons_outline:arrows-right-left',
        hint: 'Links in either direction (expand direction: either)',
    },
    {
        value: 'out',
        label: 'Outgoing',
        icon: 'heroicons_outline:arrow-up-right',
        hint: 'Only links that start at the grown items (expand direction: out)',
    },
    {
        value: 'in',
        label: 'Incoming',
        icon: 'heroicons_outline:arrow-down-left',
        hint: 'Only links that end at the grown items (expand direction: in)',
    },
];

/** What the analyst chose in the Grow panel. */
export interface GrowRequest {
    direction: GrowDirection;
    steps: 1 | 2;
    /** null = every link kind. */
    linkKinds: string[] | null;
    /** `inherit` = the Investigation's own window; `full` = all available data. */
    window: 'inherit' | 'full';
    /** Hold busy items above `threshold` contacts (the hub threshold); false = grow through them (a sealed override). */
    hold: boolean;
    threshold: number;
}

/** What the Grow panel grows from — computed by the host, so the panel never guesses. */
export interface GrowContext {
    mode: 'investigation' | 'basic';
    /** The ids one step names: Working Set ids (Investigation) or raw node values (Basic). */
    frontier: string[];
    /** Busy (held) items at the edge: grown through only when the analyst unticks "Hold" (a recorded override). */
    heldFrontier: string[];
    /** Plain words for the frontier, e.g. "the 3 selected numbers". */
    from: string;
    /** Why Grow cannot run now (said, never a silent grey button); null = it can. */
    blocked: string | null;
    linkKinds: string[];
    /** The Space's hub threshold in force — the default of "Hold … with more than N contacts". */
    hubThreshold: number;
    terms: PackTerms;
    /** The most items a first step may add for a second step to be offered (the frontier cap). */
    secondStepCap: number;
    /** An Investigation with a time column: the window choice is offered. */
    hasWindow: boolean;
}

/** The pack's words for the things on the canvas, lower case: "number"/"numbers", "call"/"calls". */
export interface PackTerms {
    node: string;
    nodes: string;
    link: string;
    links: string;
}

function singular(plural: string): string {
    if (/ies$/.test(plural)) return plural.slice(0, -3) + 'y';
    if (/ss$/.test(plural)) return plural;
    return plural.replace(/s$/, '');
}

/** The active Domain Profile's words (a Telecom pack says numbers and calls; Mobile Money accounts and transfers). */
export function packTerms(profile: Pick<DomainProfile, 'labels'> | null | undefined): PackTerms {
    const nodes = (profile?.labels.nodes || 'Nodes').toLowerCase();
    const links = (profile?.labels.links || 'Links').toLowerCase();
    return { node: singular(nodes), nodes, link: singular(links), links };
}

const fmt = (n: number): string => n.toLocaleString('en');
const plural = (n: number, one: string, many: string): string => `${fmt(n)} ${n === 1 ? one : many}`;

/** A restricted list's count shown to someone who may not see its members: nearest 5, at least 5; 0 stays 0 (plan §7). */
export function roundRestricted(n: number): number {
    if (!(n > 0)) return 0;
    return Math.max(5, Math.round(n / 5) * 5);
}

/** The preview in one plain sentence (§3.1): what the step would add, hold and remove. */
export function previewSentence(p: GrowPreview, t: PackTerms): string {
    const parts: string[] = [];
    parts.push(
        p.addsNodes || p.addsLinks
            ? `This step adds about ${plural(p.addsNodes, t.node, t.nodes)} and ${plural(p.addsLinks, t.link, t.links)}`
            : 'This step adds nothing new',
    );
    const held = p.heldCount ?? p.held?.length ?? 0;
    if (held) parts.push(`${plural(held, `busy ${t.node}`, `busy ${t.nodes}`)} will be held`);
    const removed = (p.excludedByLists ?? []).reduce((s, l) => s + (l.removed || 0), 0);
    if (removed)
        parts.push(`${plural(removed, t.node, t.nodes)} ${removed === 1 ? 'is' : 'are'} removed by your exclusion lists`);
    let text = parts.join('; ') + '.';
    if (p.truncated) text += ' The read would hit its row limit, so the result would be incomplete.';
    if (p.needsApproval)
        text += ` A second person must approve this step before it runs${p.reason ? ` (${p.reason})` : ''}.`;
    return text;
}

/**
 * Why "2 steps" is greyed (§3.1, GE-2): a second step is offered only when the first one stays under the frontier cap,
 * which only a preview can say. Null = it is offered.
 */
export function secondStepWhyNot(
    preview: GrowPreview | null,
    state: 'loading' | 'ready' | 'unavailable' | 'error' | 'idle',
    cap: number,
    t: PackTerms,
): string | null {
    if (state === 'loading' || state === 'idle') return 'Waiting for the preview of the first step.';
    if (state !== 'ready' || !preview)
        return '2 steps needs a preview of the first step, and none is available here — grow one step at a time.';
    if (preview.needsApproval) return 'The first step needs a second person’s approval, so a second step cannot follow it.';
    if (preview.addsNodes > cap)
        return `2 steps is offered only when the first step adds at most ${plural(cap, t.node, t.nodes)}; this one adds about ${fmt(preview.addsNodes)}.`;
    return null;
}

/** Plain words for a preview failure: a missing route degrades quietly; a refusal says why. */
export function previewFailure(err: unknown): { unavailable: boolean; absent: boolean; message: string } {
    const status = err instanceof HttpErrorResponse ? err.status : (err as { status?: number } | null)?.status;
    // absent = this server has no such route (an older build): not worth asking again this session
    const absent = status === 404 || status === 405 || status === 501;
    if (absent || status === undefined || status === 0 || status === 503) return { unavailable: true, absent, message: '' };
    return { unavailable: false, absent: false, message: apiErrorMessage(err, 'The preview was refused.') };
}

/** The `exclude` reason "Remove held" seals (§3.2). */
export function heldReason(threshold: number, t: PackTerms): string {
    return `busy ${t.node} above ${fmt(threshold)} contacts`;
}

/** The ids one step names: the frontier, plus the held edge when the analyst chose not to hold busy items. */
export function growIds(ctx: Pick<GrowContext, 'frontier' | 'heldFrontier'>, req: Pick<GrowRequest, 'hold'>): string[] {
    return req.hold ? ctx.frontier : [...ctx.frontier, ...ctx.heldFrontier];
}

/** The one recorded `expand` a Grow step sends: the profile's presets under the analyst's choices. */
export function growExpandBody(
    frontier: string[],
    req: GrowRequest,
    presets: ExpandRung,
): Extract<InvestigationOpRequest, { op: 'expand' }> {
    const { window: presetWindow, ...rest } = presets;
    return {
        op: 'expand',
        ids: frontier,
        ...rest,
        direction: req.direction,
        ...(req.linkKinds ? { linkKinds: req.linkKinds } : {}),
        ...(req.window === 'full' ? { window: 'full' as const } : presetWindow ? { window: presetWindow } : {}),
        ...(req.hold ? { hubThreshold: req.threshold } : { includeHubs: true }),
    };
}

/** Effective (not undone) op entries, oldest first. */
function effectiveOps(entries: readonly InvestigationLogEntry[]): InvestigationLogEntry[] {
    return entries.filter((e) => e.kind === 'op' && e.undoneBy == null);
}

/** One list in force in an Investigation: what the Exclusions panel shows as a chip. */
export interface ListInForce {
    listId: string;
    title: string;
    ownerKind: 'admin' | 'analyst' | null;
    restricted: boolean;
    /** What the chip shows: exact, or rounded for a restricted list the viewer may not see. */
    removed: number;
    /** The step that applied it (the latest, when applied more than once). */
    step: number;
    /** Every effective step that applied it, oldest first. */
    steps: number[];
    /** Why it cannot be switched off now; null = Undo can switch it off. */
    switchOffWhyNot: string | null;
}

/**
 * The lists in force (§3.3): every effective `excludeBy` step, newest application per list, with how many entities its
 * steps removed from the Working Set. Switching one off is the `undo` op (GE-12), so only the latest step can be.
 */
export function listsInForce(
    entries: readonly InvestigationLogEntry[],
    ws: WorkingSet | null,
    lists: readonly ExclusionListSummary[] | null,
    canSeeRestricted: boolean,
): ListInForce[] {
    const ops = effectiveOps(entries);
    const latest = ops.at(-1)?.step ?? null;
    const byList = new Map<string, number[]>();
    for (const e of ops)
        if (e.op === 'excludeBy' && e.params?.listId) byList.set(e.params.listId, [...(byList.get(e.params.listId) ?? []), e.step]);
    const out: ListInForce[] = [];
    for (const [listId, steps] of byList) {
        const known = lists?.find((l) => l.id === listId);
        const exact = (ws?.excluded ?? []).filter((x) => steps.includes(x.step)).length;
        const restricted = known?.visibility === 'restricted';
        const step = Math.max(...steps);
        out.push({
            listId,
            title: known?.title ?? listId,
            ownerKind: known?.ownerKind ?? null,
            restricted,
            removed: restricted && !canSeeRestricted ? roundRestricted(exact) : exact,
            step,
            steps,
            switchOffWhyNot:
                steps.length > 1
                    ? `Applied in ${steps.length} steps (${steps.join(', ')}); Undo can take back only the latest, so the list would stay in force.`
                    : step === latest
                      ? null
                      : `Applied at step ${step}; only the latest step can be switched off (with Undo). Later steps depend on it.`,
        });
    }
    return out.sort((a, b) => a.step - b.step);
}

/** "41 numbers hidden by 3 lists" (§3.3). */
export function hiddenByListsLine(inForce: readonly ListInForce[], t: PackTerms): string {
    if (!inForce.length) return 'No exclusion list is in force.';
    const total = inForce.reduce((s, l) => s + l.removed, 0);
    return `${plural(total, t.node, t.nodes)} hidden by ${plural(inForce.length, 'list', 'lists')}`;
}

/** One row of "Show what was removed". */
export interface RemovedRow {
    id: string;
    why: string;
    step: number;
}

/**
 * The Working Set's exclusions as rows, minus those a restricted list removed when the viewer may not see its members
 * (GE-8): those are only counted (rounded), never listed.
 */
export function removedRows(
    ws: WorkingSet | null,
    inForce: readonly ListInForce[],
    canSeeRestricted: boolean,
): { rows: RemovedRow[]; restrictedCount: number } {
    const hiddenSteps = new Set<number>();
    let restrictedCount = 0;
    if (!canSeeRestricted)
        for (const l of inForce)
            if (l.restricted) {
                for (const s of l.steps) hiddenSteps.add(s);
                restrictedCount += l.removed;
            }
    const listOf = new Map(inForce.flatMap((l) => l.steps.map((s) => [s, l.title] as const)));
    const rows = (ws?.excluded ?? [])
        .filter((x) => !hiddenSteps.has(x.step))
        .map((x) => ({ id: x.id, why: listOf.has(x.step) ? `List: ${listOf.get(x.step)}` : x.reason, step: x.step }));
    return { rows, restrictedCount };
}

/** The lists an Exclude "Add to a list…" may write: analyst lists, and admin ones only with the category capability. */
export function listTargets(
    lists: readonly ExclusionListSummary[] | null,
    entityType: string | null,
    canManageCategories: boolean,
): { list: ExclusionListSummary; whyNot: string | null }[] {
    return (lists ?? [])
        .filter((l) => !entityType || l.entityType === entityType)
        .map((l) => ({
            list: l,
            whyNot:
                l.ownerKind === 'admin' && !canManageCategories
                    ? 'A category list is kept by an administrator (it needs the Manage exclusion categories capability).'
                    : null,
        }));
}

// ── first-time hints (per browser; a convenience, never state that must survive) ──

/**
 * The user-guide chapter every first-time hint points to. The SPA serves no docs (they ship in the bundle's `docs/`
 * folder), so the hint names the chapter rather than linking to a page that is not there.
 */
export const GROW_GUIDE = { chapter: 'Grow and clean a graph', where: 'Link Analysis manual, §5.4' } as const;

const HINT_PREFIX = 'inspecto.la.hint.';

export function hintSeen(key: string): boolean {
    try {
        return localStorage.getItem(HINT_PREFIX + key) === '1';
    } catch {
        return false;
    }
}

export function markHintSeen(key: string): void {
    try {
        localStorage.setItem(HINT_PREFIX + key, '1');
    } catch {
        // private window / blocked storage: the hint simply shows again next time
    }
}

// ── Basic (the explorer graph, unrecorded — §3.5) ──

/** Which of an expanded node's new links a Grow keeps: its direction and its link kinds. */
export function growKeepsEdge(
    origin: string,
    req: Pick<GrowRequest, 'direction' | 'linkKinds'>,
    edge: { source: string; target: string },
    kind: string,
): boolean {
    if (req.direction === 'out' && edge.source !== origin) return false;
    if (req.direction === 'in' && edge.target !== origin) return false;
    return !req.linkKinds || req.linkKinds.includes(kind);
}

/** A neighbours read cut to the links a Grow keeps (plus the nodes they touch and the origin); every other field stays. */
export function filterExpansion<G extends { nodes: { id: string }[]; edges: { source: string; target: string }[] }>(
    g: G,
    origin: string,
    keep: (e: G['edges'][number]) => boolean,
): G {
    const edges = g.edges.filter(keep);
    const touched = new Set(edges.flatMap((e) => [e.source, e.target]));
    return { ...g, edges, nodes: g.nodes.filter((n) => n.id === origin || touched.has(n.id)) };
}

/**
 * The explorer's ONE Grow preview body (`POST /inv/projection/neighbors/preview`): the whole frontier as `ids`, the
 * direction, the link kinds (only with a link-kind column) and the hold threshold. No `excludeBy` — the explorer graph
 * has no list in force.
 */
export function neighboursPreviewBody(
    p: Pick<EntityProjection, 'datasetId' | 'sourceCol' | 'targetCol' | 'linkKindCol'>,
    ids: string[],
    req: Pick<GrowRequest, 'direction' | 'linkKinds' | 'threshold'>,
): NeighborsPreviewRequest {
    return {
        dataset: p.datasetId,
        sourceCol: p.sourceCol,
        targetCol: p.targetCol,
        ...(p.linkKindCol ? { linkKindCol: p.linkKindCol } : {}),
        ids,
        direction: req.direction,
        ...(req.linkKinds && p.linkKindCol ? { linkKinds: req.linkKinds } : {}),
        hubThreshold: req.threshold,
    };
}
