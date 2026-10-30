import type { EntityProjection, G6GraphData } from '@inspecto/core/graph';
import type { EntityTypeSummary } from '@inspecto/link-analysis/api/entity-profile.service';
import { DEFAULT_MAX_DEGREE, DomainProfileId, domainProfile, domainProfiles } from '@inspecto/link-analysis/graph/domain-profile';
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
    | { kind: 'dataset'; datasetId: string }
    /** Phase 7 (UC-09 "the busiest / a community"): a *Find something interesting* result's entities; `what` names it. */
    | { kind: 'found'; datasetId: string; values: string[]; what: string };

/**
 * The Investigation tab's start form's answer (LA-UNSAVED-INVESTIGATION-1): what to start from, the time column read
 * settings and how many degrees to draw (the start draws one ring; 0 for a seedless start). No Name or Purpose: the work
 * is unsaved, and `defaults` is what Save's dialog opens with.
 */
export interface UnsavedStartRequest {
    /** `graph` = a seedless start over the graph on screen (its one mapping). */
    start: Exclude<StartRequest, { kind: 'found' }> | { kind: 'graph' };
    defaults: { name: string; purpose: string };
    /** '' = the Dataset's own time column. */
    timeCol: string;
    timeColZone: string;
    degrees: number;
}

/** What the default Name and Purpose are made from: the Start from choice and its current input. */
export interface NewInvestigationInput {
    kind: StartKind | 'graph';
    /** The graph on screen's name: its saved view, else its Dataset. */
    graphName?: string;
    /** The Entity Type's label (one entity, a paste). */
    typeLabel: string;
    /** The one entity's value. */
    value: string;
    /** The Entity List's title. */
    listTitle: string;
    /** How many values the paste holds. */
    pasted: number;
    datasetName: string;
    /** The Space's masking mode: a title is stored and shown like a value, so a value enters it only when this is `none`. */
    maskingMode?: string | null;
}

/** "a Party", "an Account": the article for a label (blank = "an entity"). */
function aOrAn(label: string): string {
    const l = label.trim() || 'entity';
    return `${/^[aeiou]/i.test(l) ? 'an' : 'a'} ${l}`;
}

/**
 * The New form's default Name and Purpose for an input (LA-PANELS-REDESIGN-1; LA-UI-TEXT-PASS-1 X7 and G1). The
 * analyst may change both; the form re-defaults them only until they are edited.
 *
 * - The Name is blank until there is something to name (a value, a list, pasted values); it never carries an
 *   "Investigation" prefix, the field already says what it is.
 * - The Purpose never holds a raw identifier: a value enters only the Name, and only where the Space is known not to
 *   mask (the same rule as `suspectTitle`).
 * - Blank inputs never leave a gap: a blank Entity Type is "entity", a blank Dataset name is "the Dataset".
 *
 * | Start from    | Name                              | Purpose                                                   |
 * |---------------|-----------------------------------|-----------------------------------------------------------|
 * | one entity    | "<Type> <value>" (masked: "<Type>")| "Review of a <Type> and its links"                       |
 * | Entity List   | "Members of “<title>”"            | "Review of the members of “<title>” and their links"      |
 * | pasted values | "N pasted <Type> values"          | "Review of N pasted values and their links"               |
 * | whole Dataset | "<Dataset>"                       | "Review of the links in <Dataset>"                        |
 * | graph         | "<graph name>" (masked: "Graph on screen") | "Review of the graph on screen"                  |
 */
export function newInvestigationDefaults(i: NewInvestigationInput): { name: string; purpose: string } {
    const type = (i.typeLabel ?? '').trim();
    switch (i.kind) {
        case 'graph': {
            const graph = (i.graphName ?? '').trim();
            return {
                name: i.maskingMode === 'none' && graph ? graph : 'Graph on screen',
                purpose: 'Review of the graph on screen',
            };
        }
        case 'list': {
            const title = (i.listTitle ?? '').trim();
            if (!title) return { name: '', purpose: 'Review of the members of an Entity List and their links' };
            return { name: `Members of “${title}”`, purpose: `Review of the members of “${title}” and their links` };
        }
        case 'paste': {
            const n = i.pasted;
            if (!(n > 0)) return { name: '', purpose: '' };
            const values = plural(n, type ? `pasted ${type} value` : 'pasted value', type ? `pasted ${type} values` : 'pasted values');
            return {
                name: values[0].toUpperCase() + values.slice(1),
                purpose: `Review of ${plural(n, 'pasted value', 'pasted values')} and ${n === 1 ? 'its' : 'their'} links`,
            };
        }
        case 'dataset': {
            const ds = (i.datasetName ?? '').trim();
            return { name: ds || 'Whole Dataset', purpose: `Review of the links in ${ds || 'the Dataset'}` };
        }
        default: {
            const value = (i.value ?? '').trim();
            const purpose = `Review of ${aOrAn(type)} and its links`;
            if (!value) return { name: '', purpose };
            const shown = i.maskingMode === 'none' ? value : '';
            const name = [type, shown].filter(Boolean).join(' ') || 'Entity';
            return { name, purpose };
        }
    }
}

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

/**
 * Values a start found nothing for, with the next step (T-016 / T-035): "X is not in Calls. Check the spelling or pick
 * another Dataset."; several: "None of these are in Calls: X, Y. …".
 */
export function notInDatasetMessage(unknown: readonly string[], dataset: string): string {
    const said =
        unknown.length === 1 ? `${unknown[0]} is not in ${dataset}.` : `None of these are in ${dataset}: ${someValues(unknown)}.`;
    return `${said} Check the spelling or pick another Dataset.`;
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
    const linked = Math.max(0, o.nodes - o.drawn);
    const parts = [
        `Drew ${plural(o.drawn, 'entity', 'entities')} and ${plural(linked, 'linked ' + o.terms.node, 'linked ' + o.terms.nodes)}.`,
    ];
    if (o.unknown.length)
        parts.push(`${someValues(o.unknown)} ${o.unknown.length === 1 ? 'is' : 'are'} not in ${o.dataset}.`);
    if (o.notDrawn)
        parts.push(`${plural(o.notDrawn, 'more value')} not drawn: one start draws at most ${START_MAX_SEEDS}.`);
    parts.push(
        o.held
            ? `${plural(o.held, 'busy ' + o.terms.node, 'busy ' + o.terms.nodes)} ${o.held === 1 ? 'is' : 'are'} held; Expand… (G) goes one degree further.`
            : 'Expand… (G) goes one degree further.',
    );
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
        `${dataset} holds about ${distinct.toLocaleString('en-GB')} ${typeLabel || 'linked'} entities: only the busiest ` +
        `${cap.toLocaleString('en-GB')} are drawn. One entity or an Entity List is usually clearer.`
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
        // the same presets as the toolbox expand (LA-INVESTIGATE-CARD-FOUR-EYES-HOLD-1), so the card and the panel agree
        presets: domainProfile(profileId).investigate?.expand ?? {},
        maxDegree: DEFAULT_MAX_DEGREE,
    };
}
