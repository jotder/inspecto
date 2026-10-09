import type { G6GraphData } from '@inspecto/core/graph';
import { LinkAnalysisView } from './link-analysis.service';
import { listableIds } from './investigation-state';

/**
 * Starting points for a first-time analyst (operator 2026-10-10): which Dataset looks like a set of
 * links, which saved view is the "follow the money" example, and which ranked nodes seed an
 * Investigation. Pure functions over what the screen already holds - no route, no service.
 */

/** A column as the heuristic reads it. `type` is absent for a Dataset that declares names only. */
export interface ColumnLike {
    name: string;
    type?: string;
}

/** A Dataset as the heuristic reads it (the `LaDataset` fields it needs). */
export interface DatasetLike {
    id: string;
    name: string;
    description?: string;
    columns?: readonly ColumnLike[];
}

/** The two ends of a relationship a Dataset's columns suggest. */
export interface LinkShape {
    source: string;
    target: string;
    /** Higher = a more telling pair (`payer`/`payee` beats a bare `from`/`to`). */
    score: number;
    /** One plain line: why these two columns. */
    reason: string;
}

/** Role words, source side then target side. A column names a role when one of its words equals the role. */
const ROLE_PAIRS: { source: string[]; target: string[]; score: number }[] = [
    { source: ['payer'], target: ['payee'], score: 6 },
    { source: ['sender'], target: ['receiver', 'recipient'], score: 6 },
    { source: ['caller', 'calling'], target: ['callee', 'called'], score: 6 },
    { source: ['debtor'], target: ['creditor'], score: 6 },
    { source: ['originator', 'initiator'], target: ['beneficiary', 'recipient'], score: 6 },
    { source: ['src'], target: ['dst', 'dest'], score: 5 },
    { source: ['origin', 'orig'], target: ['destination', 'dest'], score: 5 },
    { source: ['parent'], target: ['child'], score: 4 },
    { source: ['source'], target: ['target'], score: 3 },
    { source: ['from'], target: ['to'], score: 3 },
    { source: ['a'], target: ['b'], score: 2 },
];

/** A column that can name an entity: text (or a Dataset that has not typed its columns), never a number, date or flag. */
export function isStringLike(c: ColumnLike): boolean {
    return !c.type || c.type === 'string';
}

/** `PAYER_ACCOUNT` / `payerAccount` / `payer-account` -> `['payer', 'account']`. */
export function wordsOf(name: string): string[] {
    return name
        .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
        .toLowerCase()
        .split(/[^a-z0-9]+/)
        .filter(Boolean);
}

/** The most telling from/to pair among the columns, or null when there are not two string-like ends. */
export function suggestLinkColumns(columns: readonly ColumnLike[]): LinkShape | null {
    const text = columns.filter(isStringLike);
    if (text.length < 2) return null;
    let best: LinkShape | null = null;
    for (const pair of ROLE_PAIRS) {
        for (const s of text) {
            const sw = wordsOf(s.name);
            const sRole = sw.find((w) => pair.source.includes(w));
            if (!sRole) continue;
            for (const t of text) {
                if (t.name === s.name) continue;
                const tw = wordsOf(t.name);
                const tRole = tw.find((w) => pair.target.includes(w));
                if (!tRole) continue;
                // `PAYER_ACCOUNT` + `PAYEE_ACCOUNT` share the rest of the name; that is a stronger match than a lone pair.
                const sameRest = sw.filter((w) => w !== sRole).join(' ') === tw.filter((w) => w !== tRole).join(' ');
                const score = pair.score + (sameRest ? 2 : 0);
                if (!best || score > best.score) {
                    best = {
                        source: s.name,
                        target: t.name,
                        score,
                        reason: `${s.name} and ${t.name} look like the two ends of a link`,
                    };
                }
            }
        }
    }
    return best;
}

/** How many column names the picker previews before "+N more". */
export const COLUMN_PREVIEW = 6;

/** `a, b, c +2 more`, or '' when nothing is declared. */
export function columnPreview(columns: readonly ColumnLike[], max = COLUMN_PREVIEW): string {
    if (!columns.length) return '';
    const shown = columns.slice(0, max).map((c) => c.name);
    const more = columns.length - shown.length;
    return more > 0 ? `${shown.join(', ')} +${more} more` : shown.join(', ');
}

/** The first sentence of a description, cut to a one-line length. */
export function oneLine(text: string | undefined, max = 90): string {
    const first = (text ?? '').trim().split(/(?<=[.!?])\s/)[0] ?? '';
    return first.length > max ? `${first.slice(0, max - 1).trimEnd()}…` : first;
}

/** One Dataset with what the picker shows for it. */
export interface DatasetSuggestion {
    id: string;
    name: string;
    /** The guessed Source/Target pair - null when the Dataset does not look link-shaped. */
    shape: LinkShape | null;
    /** The one-line explanation shown under the name in the picker. */
    hint: string;
}

function hintFor(ds: DatasetLike, shape: LinkShape | null): string {
    const cols = columnPreview(ds.columns ?? []);
    const lead = shape
        ? `Suggested: ${shape.reason}.`
        : (ds.columns?.length ?? 0) > 0
          ? 'No obvious from/to column pair.'
          : 'Columns are read when you pick it.';
    const about = oneLine(ds.description);
    return [lead, about, cols ? `Columns: ${cols}.` : ''].filter(Boolean).join(' ');
}

/**
 * Every Dataset, link-shaped ones first (best pair first), the rest after in name order. `shapes` carries
 * the columns probed for a Dataset that declares none, keyed by id.
 */
export function rankDatasets(
    datasets: readonly DatasetLike[],
    probed: ReadonlyMap<string, readonly ColumnLike[]> = new Map(),
): DatasetSuggestion[] {
    return datasets
        .map((ds) => {
            const withColumns = ds.columns?.length ? ds : { ...ds, columns: probed.get(ds.id) ?? [] };
            const shape = suggestLinkColumns(withColumns.columns ?? []);
            return { id: ds.id, name: ds.name, shape, hint: hintFor(withColumns, shape) };
        })
        .sort((a, b) => {
            if (!!a.shape !== !!b.shape) return a.shape ? -1 : 1;
            if (a.shape && b.shape && a.shape.score !== b.shape.score) return b.shape.score - a.shape.score;
            return a.name.localeCompare(b.name);
        });
}

/** Words that mark a saved view as the "follow the money" example, with how strongly. */
const MONEY_WORDS: [RegExp, number][] = [
    [/layering/i, 3],
    [/launder/i, 3],
    [/\bmule/i, 2],
    [/money/i, 2],
    [/\bring\b/i, 1],
    [/transfer|payment|cash/i, 1],
];

/**
 * The ready-made saved view that best fits "follow the money": an Entity/Link view over a Dataset this Space
 * has, named or described in money terms. Null when the Space has none - the card is then hidden.
 */
export function pickFollowTheMoney(
    views: readonly LinkAnalysisView[],
    datasets: readonly { id: string }[],
): LinkAnalysisView | null {
    const known = new Set(datasets.map((d) => d.id));
    let best: { view: LinkAnalysisView; score: number } | null = null;
    for (const v of views) {
        if (v.sourceId !== 'entity-projection') continue;
        const mappings = v.query.projections?.length
            ? v.query.projections
            : v.query.projection
              ? [v.query.projection]
              : [];
        if (!mappings.length || !mappings.every((m) => known.has(m.datasetId))) continue;
        const text = `${v.name} ${v.description ?? ''}`;
        const score = MONEY_WORDS.reduce((s, [re, w]) => s + (re.test(text) ? w : 0), 0);
        if (score === 0) continue;
        if (!best || score > best.score || (score === best.score && v.name < best.view.name)) best = { view: v, score };
    }
    return best?.view ?? null;
}

/** How many top-ranked nodes "Start an Investigation from the top results" carries over. */
export const TOP_SEED_COUNT = 5;

/** A ranked node queued to seed an Investigation. */
export interface QueuedSeed {
    /** The canvas node id. */
    id: string;
    label: string;
    /** The raw values a seed step names (a masked pseudonym is left out - the server resolves those itself). */
    ids: string[];
}

/** The top `n` ranked nodes as queued seeds; a node with no seedable raw id is skipped. */
export function topSeeds(
    ranked: readonly { id: string; label: string }[],
    graph: G6GraphData | null,
    n = TOP_SEED_COUNT,
): QueuedSeed[] {
    if (!graph) return [];
    const byId = new Map(graph.nodes.map((node) => [node.id, node]));
    const out: QueuedSeed[] = [];
    for (const r of ranked) {
        if (out.length >= n) break;
        const node = byId.get(r.id);
        if (!node) continue;
        const { ids } = listableIds(node);
        if (ids.length) out.push({ id: r.id, label: r.label, ids });
    }
    return out;
}
