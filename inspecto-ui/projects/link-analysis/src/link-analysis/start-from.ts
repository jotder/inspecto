import type { EntityProjection, G6GraphData } from '@inspecto/core/graph';
import type { EntityTypeSummary } from '@inspecto/link-analysis/api/entity-profile.service';
import { DEFAULT_MAX_DEGREE, DomainProfileId, domainProfiles } from '@inspecto/link-analysis/graph/domain-profile';
import type { LaDataset } from '@inspecto/link-analysis/la-host';
import { PendingInvestigation, dayLabel } from './investigate-number';
import type { LinkAnalysisView } from './link-analysis.service';

/**
 * LA-ONBOARD-1 phase 6 (OB-9) "Start from..." - the pure half: which Datasets can be drawn from a start and over which
 * mapping, which Entity Types can start one, a pasted list read into values, and what a start did in words. No Angular, no
 * HTTP. A start draws the chosen entities plus ONE ring of neighbours, so Grow continues from there; the whole Dataset is
 * a start too, with a warning above the node cap.
 */

/** What the Start box can start from. */
export type StartKind = 'entity' | 'list' | 'paste' | 'dataset';

/** The Start box's choice, carried out by the page. */
export type StartRequest =
    | { kind: 'entity'; datasetId: string; entityType: string; value: string }
    | { kind: 'list'; datasetId: string; listId: string }
    | { kind: 'paste'; datasetId: string; entityType: string; values: string[] }
    | { kind: 'dataset'; datasetId: string };

/** One Dataset a start can draw: the mapping every start over it uses, and the Entity Types its link ends hold. */
export interface StartDataset {
    id: string;
    name: string;
    /** The Dataset's primary Entity Type (OB-8), preselected in the Start box; null when it names none. */
    primaryEntityType: string | null;
    mapping: EntityProjection;
    /** The time column the mapping names, for an Investigation bound from it. */
    timeCol?: string;
    /** Entity Type ids its link ends hold, primary first. */
    types: string[];
    /** Where the mapping came from, said on the box: `default link`, `saved view <name>`, `column types`, `profile`. */
    from: string;
}

/** One Entity Type a start can begin from. */
export interface StartType {
    id: string;
    label: string;
}

export interface StartCatalogue {
    datasets: StartDataset[];
    types: StartType[];
}

/**
 * The mapping a start over `ds` draws, in precedence: the Dataset's own `defaultLinkMapping` (OB-8); a Domain Profile's
 * `investigate` mapping over it (the override); the first saved view whose single mapping reads it; else its two
 * classified link-end columns (`source` and `target` bindings). Null when none - the Dataset cannot start a graph yet.
 */
export function startMapping(
    ds: LaDataset,
    views: readonly LinkAnalysisView[],
    bindings: readonly { column: string; role: string }[],
): { mapping: EntityProjection; timeCol?: string; from: string } | null {
    const m = ds.defaultLinkMapping;
    if (m?.from && m.to)
        return {
            mapping: {
                datasetId: ds.id,
                sourceCol: m.from,
                targetCol: m.to,
                ...(m.kind ? { linkKindCol: m.kind } : {}),
            },
            ...(m.time ? { timeCol: m.time } : {}),
            from: 'default link',
        };
    const profile = domainProfiles().find((p) => p.investigate?.mapping.dataset === ds.id)?.investigate?.mapping;
    if (profile)
        return {
            mapping: {
                datasetId: ds.id,
                sourceCol: profile.sourceCol,
                targetCol: profile.targetCol,
                ...(profile.linkKindCol ? { linkKindCol: profile.linkKindCol } : {}),
            },
            ...(profile.timeCol ? { timeCol: profile.timeCol } : {}),
            from: 'profile',
        };
    const view = views.find(
        (v) =>
            v.sourceId === 'entity-projection' &&
            !v.query.projections?.length &&
            v.query.projection?.datasetId === ds.id,
    );
    const p = view?.query.projection;
    if (view && p?.sourceCol && p.targetCol)
        return {
            mapping: {
                datasetId: ds.id,
                sourceCol: p.sourceCol,
                targetCol: p.targetCol,
                ...(p.linkKindCol ? { linkKindCol: p.linkKindCol } : {}),
                ...(p.attrCols?.length ? { attrCols: p.attrCols } : {}),
            },
            from: `saved view ${view.name}`,
        };
    const source = bindings.find((b) => b.role === 'source')?.column;
    const target = bindings.find((b) => b.role === 'target' && b.column !== source)?.column;
    return source && target
        ? { mapping: { datasetId: ds.id, sourceCol: source, targetCol: target }, from: 'column types' }
        : null;
}

/**
 * Every Dataset a start can draw, and every Entity Type that can start one (OB-9): a type held by a link end of some
 * Dataset (`GET /inv/entity-types` binds it `source` / `target`), or a Dataset's primary type when the Dataset has a default
 * link. `types` null = the registry could not be read: only the primary types are known then, labelled by id.
 */
export function startCatalogue(
    datasets: readonly LaDataset[],
    views: readonly LinkAnalysisView[],
    types: readonly EntityTypeSummary[] | null,
): StartCatalogue {
    const known = new Map((types ?? []).map((t) => [t.id, t]));
    const out: StartDataset[] = [];
    for (const ds of datasets) {
        const bindings = (types ?? []).flatMap((t) =>
            t.bindings.filter((b) => b.dataset === ds.id).map((b) => ({ ...b, type: t.id })),
        );
        const s = startMapping(ds, views, bindings);
        if (!s) continue;
        const ends = new Set([s.mapping.sourceCol.toLowerCase(), s.mapping.targetCol.toLowerCase()]);
        const held = bindings.filter((b) => ends.has(b.column.toLowerCase())).map((b) => b.type);
        const primary =
            ds.primaryEntityType && (!types || known.has(ds.primaryEntityType)) ? ds.primaryEntityType : null;
        const ids = [...new Set([...(primary ? [primary] : []), ...held])];
        out.push({
            id: ds.id,
            name: ds.name || ds.id,
            primaryEntityType: primary,
            mapping: { ...s.mapping, ...(primary ? { entityType: primary } : {}) },
            ...(s.timeCol ? { timeCol: s.timeCol } : {}),
            types: ids,
            from: s.from,
        });
    }
    const typeIds = [...new Set(out.flatMap((d) => d.types))];
    return {
        datasets: out,
        types: typeIds.map((id) => {
            const t = known.get(id);
            return { id, label: t?.label || id };
        }),
    };
}

/** The Dataset a start over `entityType` uses: `wanted` when it can draw that type, else the first that can; null = none. */
export function datasetForType(cat: StartCatalogue, entityType: string, wanted?: string | null): StartDataset | null {
    const named = wanted ? cat.datasets.find((d) => d.id === wanted) : undefined;
    if (named && (named.types.includes(entityType) || !named.types.length)) return named;
    return cat.datasets.find((d) => d.types.includes(entityType)) ?? named ?? null;
}

/** Pasted values: one per line, or separated by commas, semicolons or tabs; trimmed, blanks and repeats dropped. */
export function parsePastedValues(text: string): string[] {
    return [
        ...new Set(
            text
                .split(/[\n\r,;\t]+/)
                .map((v) => v.trim())
                .filter((v) => !!v),
        ),
    ];
}

/** The most entities one start draws a ring around (one neighbours read each) - the Grow's own per-step bound. */
export const START_MAX_SEEDS = 25;

/** The node of a neighbours read that started it: an end of every row it returned (raw value or alias, masked or not). */
export function seedNodeOf(read: Pick<G6GraphData, 'nodes' | 'edges'>, value: string): string | null {
    const byLabel = read.nodes.find((n) => n.data.label === value || n.data.spellings?.includes(value));
    if (byLabel) return byLabel.id;
    const touches = new Map<string, number>();
    for (const e of read.edges)
        for (const id of new Set([e.source, e.target])) touches.set(id, (touches.get(id) ?? 0) + 1);
    let best: string | null = null;
    for (const [id, n] of touches) if (n === read.edges.length && (best === null || id < best)) best = id;
    return best;
}

const plural = (n: number, one: string, many = `${one}s`): string =>
    `${n.toLocaleString('en')} ${n === 1 ? one : many}`;

/** A short list of values for a sentence: the first few, then "and N more". */
export function someValues(values: readonly string[], max = 5): string {
    return values.length <= max
        ? values.join(', ')
        : `${values.slice(0, max).join(', ')} and ${values.length - max} more`;
}

/** What an explorer start drew, in words (the box's notice after it). */
export function startOutcome(o: {
    drawn: number;
    nodes: number;
    held: number;
    unknown: readonly string[];
    notDrawn: number;
    dataset: string;
    terms: { node: string; nodes: string };
}): string {
    const parts = [
        `Drew ${plural(o.drawn, 'start')} and ${plural(Math.max(0, o.nodes - o.drawn), 'linked ' + o.terms.node, 'linked ' + o.terms.nodes)} from ${o.dataset}.`,
    ];
    if (o.held)
        parts.push(
            `${plural(o.held, 'busy ' + o.terms.node, 'busy ' + o.terms.nodes)} held - Grow does not go through them.`,
        );
    if (o.unknown.length) parts.push(`Not in ${o.dataset}: ${someValues(o.unknown)}.`);
    if (o.notDrawn)
        parts.push(`${plural(o.notDrawn, 'more value')} not drawn: one start draws at most ${START_MAX_SEEDS}.`);
    parts.push('Grow (G) continues one step at a time.');
    return parts.join(' ');
}

/** The whole-Dataset warning, or null when the count is unknown or within the node cap. */
export function wholeDatasetWarning(
    distinct: number | null,
    cap: number,
    dataset: string,
    typeLabel: string,
): string | null {
    if (distinct == null || distinct <= cap) return null;
    return (
        `${dataset} holds about ${distinct.toLocaleString('en')} ${typeLabel} entities, more than the ${cap.toLocaleString('en')}-node cap: ` +
        `the graph is cut to the busiest ${cap.toLocaleString('en')}. Starting from one entity or a list is usually clearer.`
    );
}

/** A type-ahead row in words: `MULE-HUB-01 - 10 links, first seen 3 Sep 2026, last seen 30 Sep 2026`. */
export function suggestionLine(s: { links: number; firstSeen: string | null; lastSeen: string | null }): string {
    const seen = s.firstSeen
        ? `, first seen ${dayLabel(s.firstSeen)}` +
          (s.lastSeen && s.lastSeen !== s.firstSeen ? `, last seen ${dayLabel(s.lastSeen)}` : '')
        : '';
    return `${plural(s.links, 'link')}${seen}`;
}

/** Whether the router has a top-level (or shell-child) route at `path` - the Add data offer shows only where it exists. */
export function hasRoute(config: readonly { path?: string; children?: readonly unknown[] }[], path: string): boolean {
    return config.some(
        (r) =>
            r.path === path ||
            (!!r.children && hasRoute(r.children as readonly { path?: string; children?: readonly unknown[] }[], path)),
    );
}

/**
 * OB-9's generic Investigation binding (no Domain Profile investigates the type): the start Dataset's mapping, typed by the
 * seed's Entity Type, its time column when it names one, the server's default rung, the default degree limit.
 */
export function genericBinding(
    seed: string,
    entityType: string,
    ds: StartDataset,
    profileId: DomainProfileId,
): PendingInvestigation {
    return {
        seed,
        profileId,
        projection: { ...ds.mapping, entityType },
        ...(ds.timeCol ? { timeCol: ds.timeCol } : {}),
        presets: {},
        maxDegree: DEFAULT_MAX_DEGREE,
    };
}
