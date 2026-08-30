import { NodeKind } from 'app/inspecto/api';
import {
    AuthoredPipeline,
    AuthoredNode,
    AuthoredEdge,
    ComponentType,
    PipelineCombined,
    PipelineGraph,
    PipelineNode,
    PipelineNodeType,
    IconMap,
    ProvenanceCount,
} from 'app/inspecto/api';
import { pipelineHome, storeDirs } from 'app/inspecto/component-model/pipeline-scaffold';
import { GLYPH_LIBRARY, G6GraphData, iconDataUri, nodeColor, nodeIcon } from 'app/modules/admin/catalog/catalog-graph';

/**
 * Pure mappers that turn the flow-graph projection (GET /pipelines/{id}/graph) into AntV G6 data for the
 * shared {@link GraphViewComponent}, plus palette grouping. Kept free of Angular/G6 imports so they
 * unit-test without TestBed.
 *
 * <p>The G6 host keys shape + outline colour off a catalog {@link NodeKind}; a flow node's
 * {@link PipelineNode.category} is mapped onto a NodeKind purely for that visual reuse (so colours come
 * from the existing token palette, never a hardcoded value here).
 */

/**
 * The registry component a node category binds — `grammar` for a parser, null for everything else.
 *
 * ⚠ It used to answer `transform` for TRANSFORM and `sink` for SINK, which is what put a component
 * picker on those nodes. Nothing resolves `transform/<id>` or `sink/<id>` in either language, so since
 * AUTHOR-1(b) the server REFUSES such a ref (`UNSUPPORTED_BINDING`): every option the picker offered
 * bought the author a failed save. It is keyed on a node's CATEGORY while the server's homes
 * (`PipelineEditable.USE_HOME`) are keyed on its TYPE — they agree only because PARSE holds the one
 * type, `parser`. A new binding here must have a home there first.
 *
 * <p>A source's `connection/<name>` is deliberately absent: a Connection is not a `ComponentType` (no
 * `GET /components/connection` route), so the collector component owns that picker — see
 * `NodeConfigDialog.isAcquisition`.
 */
export function bindKindFor(category: string): ComponentType | null {
    return category === 'PARSE' ? 'grammar' : null;
}

/**
 * Categories whose nodes must be configured somehow — by a ref or inline — to count as authored.
 * Kept SEPARATE from {@link bindKindFor}: a transform still needs config after losing its picker, and
 * deriving "needs configuration" from "binds a component" is what coupled the two.
 */
const NEEDS_CONFIG = new Set(['SOURCE', 'PARSE', 'TRANSFORM', 'SINK']);

// ── Node status (canvas state) + flow validation (Stages 2 & 4) ──

/** A node's authoring status, shown on the canvas + inspector. */
export type NodeStatus = 'unconfigured' | 'dangling' | 'disabled' | 'configured' | 'tested' | 'rejects';

/** A test outcome recorded for a node after a run-to-here (drives `tested`/`rejects`). */
export type TestOutcome = 'tested' | 'rejects';

/** Glyph prefixed to a node's canvas label so status reads as text, not colour alone ('' = none). */
export function statusGlyph(status: NodeStatus): string {
    switch (status) {
        case 'unconfigured':
            return '⚠ ';
        case 'dangling':
            return '⚠ ';
        case 'disabled':
            return '⏸ ';
        case 'tested':
            return '✓ ';
        case 'rejects':
            return '✕ ';
        default:
            return '';
    }
}

/** Human label for a node status (the inspector chip). */
export function statusLabel(status: NodeStatus): string {
    switch (status) {
        case 'unconfigured':
            return 'Needs config';
        case 'dangling':
            return 'Missing component';
        case 'disabled':
            return 'Disabled — Consignments park here';
        case 'configured':
            return 'Configured';
        case 'tested':
            return 'Tested';
        case 'rejects':
            return 'Has rejects';
    }
}

/**
 * Compute a node's status. A node that binds a component (or a source's connection) but has no `use` ref is
 * `unconfigured`; a bound registry ref absent from `validRefs` is `dangling` (only checked when
 * `checkDangling`, so the canvas doesn't false-flag before the registry has loaded); a recorded test outcome
 * wins over the otherwise-`configured` baseline.
 */
export function computeNodeStatus(
    node: AuthoredNode,
    category: string,
    validRefs: ReadonlySet<string>,
    tested: ReadonlyMap<string, TestOutcome>,
    checkDangling = true,
): NodeStatus {
    // An explicit author decision outranks every derived state: a Step switched off is switched off,
    // however well it is configured (Phase 4 S4 / D-13 — at rest its Consignments PARK here).
    if (node.config?.['enabled'] === false) return 'disabled';
    const bindKind = bindKindFor(category);
    const ref = node.use?.trim();
    const hasInlineConfig = !!node.config && Object.keys(node.config).length > 0;
    // Unconfigured only when it needs settings but has neither a ref nor inline config.
    if (NEEDS_CONFIG.has(category) && !ref && !hasInlineConfig) return 'unconfigured';
    if (checkDangling && ref && bindKind && !validRefs.has(ref)) return 'dangling';
    return tested.get(node.id) ?? 'configured';
}

/** One validation finding for the editor's Validate panel; `error` blocks activation. */
export interface PipelineFinding {
    severity: 'error' | 'warning' | 'info';
    nodeId?: string;
    message: string;
}

/**
 * Validate an authored flow for activation: every node configured + its refs resolvable, a source feeding it,
 * a sink draining it, and no orphan (non-source node with no input). `error`-severity findings block Activate.
 */
export function validatePipeline(
    flow: AuthoredPipeline,
    typeCat: ReadonlyMap<string, string>,
    validRefs: ReadonlySet<string>,
    tested: ReadonlyMap<string, TestOutcome>,
): PipelineFinding[] {
    const findings: PipelineFinding[] = [];
    if (!flow.nodes.length) {
        return [{ severity: 'error', message: 'The pipeline has no Steps.' }];
    }
    const incoming = new Set(flow.edges.map((e) => e.to));
    let hasSource = false;
    let hasSink = false;
    for (const n of flow.nodes) {
        const cat = typeCat.get(n.type) ?? 'TRANSFORM';
        if (cat === 'SOURCE') hasSource = true;
        if (cat === 'SINK') hasSink = true;
        const name = n.name || n.id;
        const status = computeNodeStatus(n, cat, validRefs, tested);
        if (status === 'unconfigured') {
            findings.push({ severity: 'error', nodeId: n.id, message: `${name}: needs configuration.` });
        } else if (status === 'dangling') {
            findings.push({
                severity: 'error',
                nodeId: n.id,
                message: `${name}: references a missing ${bindKindFor(cat)} (${n.use}).`,
            });
        } else if (status === 'configured') {
            findings.push({ severity: 'info', nodeId: n.id, message: `${name}: not yet tested.` });
        } else if (status === 'disabled') {
            findings.push({
                severity: 'info',
                nodeId: n.id,
                message: `${name}: disabled — Consignments reaching it park until it is re-enabled and drained.`,
            });
        } else if (status === 'rejects') {
            findings.push({
                severity: 'warning',
                nodeId: n.id,
                message: `${name}: last run had unmatched/dropped rows.`,
            });
        }
        if (cat !== 'SOURCE' && !incoming.has(n.id)) {
            findings.push({ severity: 'warning', nodeId: n.id, message: `${name}: has no input connection.` });
        }
    }
    if (!hasSource) findings.push({ severity: 'warning', message: 'No source Step — nothing feeds the pipeline.' });
    if (!hasSink) findings.push({ severity: 'warning', message: 'No writer/sink — the pipeline produces no output.' });
    return findings;
}

/**
 * Resolve a node's configurable icon + colour: an exact `type` rule wins over a `category` rule, and
 * anything unmapped falls back to the built-in per-kind glyph. Returns the data-URI icon + the stroke colour
 * embedded into the G6 node data so the host renders it without knowing the map.
 */
export function resolveNodeIcon(
    type: string | undefined,
    category: string,
    map: IconMap | undefined,
): { iconSrc: string; color: string } {
    const rule = (type ? map?.[type] : undefined) ?? map?.[category];
    if (rule && GLYPH_LIBRARY[rule.glyph]) {
        return { iconSrc: iconDataUri(GLYPH_LIBRARY[rule.glyph], rule.color), color: rule.color };
    }
    const kind = categoryVisualKind(category);
    return { iconSrc: nodeIcon(kind), color: nodeColor(kind) };
}

/** Map a flow node category onto a catalog NodeKind for shape/colour reuse (cosmetic only). */
export function categoryVisualKind(category: string): NodeKind {
    switch (category) {
        case 'SOURCE':
            return 'STREAM';
        case 'PARSE':
            return 'SCHEMA';
        case 'TRANSFORM':
            return 'ENRICHMENT';
        case 'SINK':
            return 'TABLE';
        case 'CONTROL':
            return 'KPI';
        case 'STORE':
            return 'TABLE'; // the synthetic shared-store join node (combined view) reads as a table
        default:
            return category as NodeKind; // NodeKind includes string ⇒ falls back gracefully
    }
}

/** The accent colour for a category (via the catalog token palette) — for the legend / palette dot. */
export function categoryColor(category: string): string {
    return nodeColor(categoryVisualKind(category));
}

/** Friendly palette group name per category — the user-facing processor taxonomy. */
export function categoryLabel(category: string): string {
    switch (category) {
        // GLOSSARY §2/§3 is binding: the acquisition entity is a Collector ('Source' is banned), and the
        // write end is a Sink ('Writer' was never canonical). The served node-type label agrees — it is
        // 'Collect', not 'Acquisition' — so a card's caption and its category group name one concept once.
        case 'SOURCE':
            return 'Collector';
        case 'PARSE':
            return 'Parser';
        case 'TRANSFORM':
            return 'Transformer';
        case 'SINK':
            return 'Sink';
        case 'CONTROL':
            return 'Control';
        case 'STORE':
            return 'Store';
        default:
            return category;
    }
}

/** A flow node's display label: the user-given name if set, else the type label. */
export function nodeDisplayLabel(n: PipelineNode): string {
    return n.name && n.name.trim() ? n.name : n.label;
}

/**
 * Map the flow-graph projection to G6 data (reusing the catalog G6 host). When {@code counts} is supplied
 * (the data-plane provenance overlay, T22), each edge's label gains the record count its source emitted on
 * that relationship and a {@code weight} drives the line width — the structure plane painted with quantities
 * (§11). Edges with no recorded count are left at their default style.
 */
export function toPipelineG6Data(g: PipelineGraph, counts?: Map<string, number>, iconMap?: IconMap): G6GraphData {
    return {
        nodes: g.nodes.map((n) => ({
            id: n.id,
            data: {
                label: nodeDisplayLabel(n),
                kind: categoryVisualKind(n.category),
                ...(iconMap ? resolveNodeIcon(n.type, n.category, iconMap) : {}),
            },
        })),
        // a flow can carry several edges between the same pair (e.g. data + a route branch), so the id
        // folds in the relationship + row index to stay unique.
        edges: g.edges.map((e, i) => {
            const rel = e.kind === 'route' && e.routeKey ? `route:${e.routeKey}` : e.rel;
            const count = counts?.get(`${e.from}|${rel}`);
            return {
                id: `${e.from}->${e.to}:${e.rel}:${i}`,
                source: e.from,
                target: e.to,
                data: count == null ? { kind: rel } : { kind: `${rel} · ${count.toLocaleString()}`, weight: count },
            };
        }),
    };
}

/** Build the {@code <nodeId>|<rel>} → rowCount lookup the overlay paints onto edges. */
export function provenanceCounts(rows: ProvenanceCount[]): Map<string, number> {
    return new Map(rows.map((r) => [`${r.nodeId}|${r.rel}`, r.rowCount]));
}

/**
 * Map the combined pipeline+job topology (GET /pipelines/combined) to G6 data: flow nodes (namespaced ids)
 * plus the synthetic `STORE` join nodes, with the store-join edges ({@code produces}/{@code consumes})
 * drawn alongside the intra-flow edges. Node ids are already unique (flow nodes `<flow>/<node>`, store
 * nodes `store:<name>`), so they're used verbatim.
 */
export function toCombinedG6Data(c: PipelineCombined, iconMap?: IconMap): G6GraphData {
    return {
        nodes: c.nodes.map((n) => ({
            id: n.id,
            data: {
                label: n.category === 'STORE' ? (n.store ?? n.label) : nodeDisplayLabel(n),
                kind: categoryVisualKind(n.category),
                ...(iconMap ? resolveNodeIcon(n.type, n.category, iconMap) : {}),
            },
        })),
        edges: c.edges.map((e, i) => ({
            id: `${e.from}->${e.to}:${e.rel}:${i}`,
            source: e.from,
            target: e.to,
            data: { kind: e.kind === 'route' && e.routeKey ? `route:${e.routeKey}` : e.rel },
        })),
    };
}

/** A node-type → category lookup built from the palette catalog (authored nodes carry only their type). */
export function typeCategoryMap(types: PipelineNodeType[]): Map<string, string> {
    return new Map(types.map((t) => [t.type, t.category]));
}

/**
 * A node-type → per-TYPE display label ('Join', 'Filter') from the palette catalog. Distinct from
 * {@link categoryLabel}, which names the whole group ('Transformer') and so reads identically for every
 * transform. An authored node carries only its `type`, so a card that wants to say what a Step actually
 * IS has to resolve it through this map.
 */
export function typeLabelMap(types: PipelineNodeType[]): Map<string, string> {
    return new Map(types.map((t) => [t.type, t.label]));
}

/**
 * Map an authored flow (config-bearing, from GET …/raw) to G6 data for the editor host. A node's category —
 * which drives shape + outline colour — is resolved from the palette ({@link typeCategoryMap}); an unknown
 * type falls back to TRANSFORM so a plugin/unknown node still renders. When {@code lastRunCounts} is supplied
 * (T17's live last-run overlay — the flow's most recent {@code /provenance} read), each edge's label gains the
 * record count its source emitted on that relationship during the real last run, same painting rule as
 * {@link toPipelineG6Data}'s {@code counts} (edges with no recorded count are left at their default style).
 */
export function authoredToG6(
    flow: AuthoredPipeline,
    typeCat: Map<string, string>,
    statusOf?: (node: AuthoredNode) => NodeStatus,
    iconMap?: IconMap,
    lastRunCounts?: Map<string, number>,
): G6GraphData {
    return {
        nodes: flow.nodes.map((n) => {
            const category = typeCat.get(n.type) ?? 'TRANSFORM';
            return {
                id: n.id,
                data: {
                    label: n.name && n.name.trim() ? n.name : n.id,
                    kind: categoryVisualKind(category),
                    status: statusOf ? statusOf(n) : 'configured',
                    ...(iconMap ? resolveNodeIcon(n.type, category, iconMap) : {}),
                },
            };
        }),
        edges: flow.edges.map((e, i) => {
            const count = lastRunCounts?.get(`${e.from}|${e.rel}`);
            return {
                id: `${e.from}->${e.to}:${e.rel}:${i}`,
                source: e.from,
                target: e.to,
                data: count == null ? { kind: e.rel } : { kind: `${e.rel} · ${count.toLocaleString()}`, weight: count },
            };
        }),
    };
}

/**
 * A node's total last-run output (the sum of every relationship it emitted, e.g. {@code data}+{@code dropped})
 * from the {@code nodeId|rel} → rowCount lookup built by {@link provenanceCounts}. {@code null} when the node
 * recorded nothing in that run (not the same as a real {@code 0} — the inspector should read that as "no data").
 */
export function nodeLastRunTotal(nodeId: string, counts: ReadonlyMap<string, number>): number | null {
    let total: number | null = null;
    for (const [key, count] of counts) {
        if (key.startsWith(`${nodeId}|`)) total = (total ?? 0) + count;
    }
    return total;
}

/** The stable category order for the palette (unknown/plugin categories fall after, in first-seen order). */
export const CATEGORY_ORDER: readonly string[] = ['SOURCE', 'PARSE', 'TRANSFORM', 'SINK', 'CONTROL'];

/** The legend categories for the combined view — the flow categories plus the synthetic shared store. */
export const COMBINED_CATEGORY_ORDER: readonly string[] = [...CATEGORY_ORDER, 'STORE'];

export interface NodeTypeGroup {
    category: string;
    types: PipelineNodeType[];
}

/** Group node types by category for the palette, in {@link CATEGORY_ORDER} (unknown categories last). */
export function groupByCategory(types: PipelineNodeType[]): NodeTypeGroup[] {
    const byCat = new Map<string, PipelineNodeType[]>();
    for (const t of types) {
        const arr = byCat.get(t.category) ?? [];
        arr.push(t);
        byCat.set(t.category, arr);
    }
    const ordered: NodeTypeGroup[] = [];
    for (const c of CATEGORY_ORDER) {
        const ts = byCat.get(c);
        if (ts) {
            ordered.push({ category: c, types: ts });
            byCat.delete(c);
        }
    }
    for (const [category, ts] of byCat) ordered.push({ category, types: ts });
    return ordered;
}

/** Heroicon per palette category for the compact toolbar chips / palette buttons. */
export function paletteHeroIcon(category: string): string {
    switch (category) {
        case 'SOURCE':
            return 'heroicons_outline:arrow-down-on-square';
        case 'PARSE':
            return 'heroicons_outline:document-text';
        case 'TRANSFORM':
            return 'heroicons_outline:arrows-right-left';
        case 'SINK':
            return 'heroicons_outline:circle-stack';
        case 'CONTROL':
            return 'heroicons_outline:bell-alert';
        default:
            return 'heroicons_outline:cube';
    }
}

/**
 * Heroicon per NODE TYPE — every palette item carries its own glyph (operator ask, 2026-08-21:
 * "choose an individual icon for each item, group with color"): the icon identifies the Step, the
 * CATEGORY is conveyed by tinting it with `categoryColor`, never by sharing one glyph per group.
 * Unknown/plugin-served types fall back to the category glyph, so a new served type is never blank.
 */
export function typeHeroIcon(type: string, category: string): string {
    const icon = TYPE_HERO_ICONS[type];
    return icon ? `heroicons_outline:${icon}` : paletteHeroIcon(category);
}

/** The per-type glyphs behind {@link typeHeroIcon}. Bare heroicon ids (v2 outline sprite). */
const TYPE_HERO_ICONS: Record<string, string> = {
    acquisition: 'arrow-down-on-square',
    // Parsers: one glyph per FORMAT — the format IS the type (B6), so the icon can say which.
    'parser.delimited': 'table-cells',
    'parser.fixedwidth': 'view-columns',
    'parser.asn1': 'cpu-chip',
    'parser.json': 'code-bracket',
    'parser.text_regex': 'variable',
    'parser.xlsx': 'document-chart-bar',
    'parser.plugin': 'puzzle-piece',
    'transform.map': 'arrows-right-left',
    'transform.filter': 'funnel',
    'transform.dedup.record': 'document-duplicate',
    'transform.route': 'share',
    'transform.join': 'link',
    'transform.summarize': 'calculator',
    enrichment: 'squares-plus',
    'sink.persistent': 'circle-stack',
    'sink.view': 'eye',
    'control.gap': 'signal-slash',
};

/** Heroicon for a node status (text + icon + colour → never colour alone). */
export function statusIcon(s: NodeStatus): string {
    switch (s) {
        case 'unconfigured':
            return 'heroicons_outline:exclamation-triangle';
        case 'dangling':
            return 'heroicons_outline:x-circle';
        case 'disabled':
            return 'heroicons_outline:pause-circle';
        case 'tested':
            return 'heroicons_outline:check-circle';
        case 'rejects':
            return 'heroicons_outline:exclamation-triangle';
        default:
            return 'heroicons_outline:check';
    }
}

/** Token colour for a node status ('' = inherit, for the neutral `configured` state). */
export function statusTint(s: NodeStatus): string {
    switch (s) {
        case 'tested':
            return 'var(--gamma-primary)';
        case 'configured':
            return '';
        default:
            return 'var(--gamma-warn)';
    }
}

/** Icon for a validation finding's severity. */
export function findingIcon(sev: PipelineFinding['severity']): string {
    switch (sev) {
        case 'error':
            return 'heroicons_outline:x-circle';
        case 'warning':
            return 'heroicons_outline:exclamation-triangle';
        default:
            return 'heroicons_outline:information-circle';
    }
}

/** Token colour for a validation finding's severity ('' = inherit, for `info`). */
export function findingTint(sev: PipelineFinding['severity']): string {
    return sev === 'info' ? '' : 'var(--gamma-warn)';
}

/** A node's config as display rows, for the inspector summary / hover tooltip. */
export function nodeConfigEntries(n: AuthoredNode): { k: string; v: string }[] {
    return Object.entries(n.config ?? {}).map(([k, v]) => ({
        k,
        v: typeof v === 'string' ? v : JSON.stringify(v),
    }));
}

// ── Authored-model reducers (pure) + the canvas edge-id codec ──
//
// The G6 host has no concept of an edge's semantic identity, so the editor encodes
// `<from>-><to>:<rel>:<nonce>` as the canvas edge id and decodes it back to look up/mutate the
// authored model. These reducers return a NEW `AuthoredPipeline` (or `null` for a no-op, e.g. a
// duplicate edge) — the editor component only owns applying the result + syncing the canvas.

/** Monotonic tail for {@link encodeEdgeId} — `Date.now()` alone can collide when two edges are
 *  encoded in the same millisecond (e.g. programmatic bulk-add), so a counter guarantees uniqueness. */
let edgeIdSeq = 0;

/** Encode a canvas edge id for one `(from, to, rel)` triple (nonce keeps ids unique after a re-label). */
export function encodeEdgeId(from: string, to: string, rel: string): string {
    return `${from}->${to}:${rel}:${Date.now()}-${++edgeIdSeq}`;
}

/** Decode a canvas edge id back to its `(from, to, rel)` triple, or `null` if malformed. */
export function decodeEdgeId(g6EdgeId: string): { from: string; to: string; rel: string } | null {
    const m = /^(.*)->(.*):([^:]*):[^:]*$/.exec(g6EdgeId);
    return m ? { from: m[1], to: m[2], rel: m[3] } : null;
}

/** A fresh node id for `type`: its sanitized type name, deduplicated against the model's existing ids. */
export function uniqueNodeId(model: AuthoredPipeline | null, type: string): string {
    const base = type.replace(/[^A-Za-z0-9]+/g, '_').replace(/^_+|_+$/g, '') || 'node';
    const ids = new Set(model?.nodes.map((n) => n.id));
    let i = 1;
    let id = `${base}_${i}`;
    while (ids.has(id)) id = `${base}_${++i}`;
    return id;
}

/** Append a node. */
export function addNodeToModel(model: AuthoredPipeline, node: AuthoredNode): AuthoredPipeline {
    return { ...model, nodes: [...model.nodes, node] };
}

/** Append an edge, or `null` if an identical `(from, to, rel)` edge already exists. */
export function addEdgeToModel(
    model: AuthoredPipeline,
    from: string,
    to: string,
    rel: string,
): AuthoredPipeline | null {
    if (model.edges.some((e) => e.from === from && e.to === to && e.rel === rel)) return null;
    return { ...model, edges: [...model.edges, { from, rel, to }] };
}

/** Drop a node and every edge touching it. */
export function removeNodeFromModel(model: AuthoredPipeline, id: string): AuthoredPipeline {
    return {
        ...model,
        nodes: model.nodes.filter((n) => n.id !== id),
        edges: model.edges.filter((e) => e.from !== id && e.to !== id),
    };
}

/** Drop one `(from, to, rel)` edge. */
export function removeEdgeFromModel(model: AuthoredPipeline, from: string, to: string, rel: string): AuthoredPipeline {
    return { ...model, edges: model.edges.filter((e) => !(e.from === from && e.to === to && e.rel === rel)) };
}

/** Re-label an edge's relationship, or `null` if unchanged / would collide with an existing edge. */
export function setEdgeRelInModel(
    model: AuthoredPipeline,
    from: string,
    to: string,
    oldRel: string,
    newRel: string,
): AuthoredPipeline | null {
    if (oldRel === newRel) return null;
    if (model.edges.some((e) => e.from === from && e.to === to && e.rel === newRel)) return null;
    return {
        ...model,
        edges: model.edges.map((e) => (e.from === from && e.to === to && e.rel === oldRel ? { ...e, rel: newRel } : e)),
    };
}

/** Replace a node in the model with its edited version (by id). */
export function applyNodePatchInModel(model: AuthoredPipeline, updated: AuthoredNode): AuthoredPipeline {
    return { ...model, nodes: model.nodes.map((n) => (n.id === updated.id ? updated : n)) };
}

// ── Recipe view (ELT amendment UI plan §1): chain detection over the same AuthoredPipeline model ──

/** One Step's place in the read-only recipe chain — a node plus, only at a branch point, its branches. */
export interface StepChain {
    /** Nodes from the entry Step to (and including) the last node before a fan-out. */
    trunk: AuthoredNode[];
    /** Present only when the trunk's last node is a content-routing branch point (§2.6 — `route`). */
    branches?: StepBranch[];
}

/** One named branch off a route Step: its predicate/default flag (from the route node's own config)
 *  plus its own recursively-detected chain — a branch may itself end in another route. */
export interface StepBranch {
    /** The route node this branch hangs off — the id branch edits (predicate/default/remove) target. */
    routeId: string;
    key: string;
    where?: string;
    isDefault: boolean;
    chain: StepChain;
}

/**
 * Guarantee side-relationships (§2.4/§2.6): a `gap`/`unmatched` edge to a LEAF node is housekeeping
 * hanging off the trunk (the gap-watch node, the quarantine sink) — never a Step, never a branch.
 * The chain walk skips these (the Guarantees panel renders them instead), and {@link insertStepAfter}
 * rewires around them.
 */
function isGuaranteeSideEdge(e: AuthoredEdge, outBy: Map<string, AuthoredEdge[]>): boolean {
    return (e.rel === 'gap' || e.rel === 'unmatched') && (outBy.get(e.to) ?? []).length === 0;
}

/**
 * Detect the linear-chain/tree shape the recipe view renders (UI plan §1): walk from the single entry
 * node (no incoming edges) via `data` edges; a node whose only outgoing edges are all named
 * `route:<key>` branches into one {@link StepBranch} per edge — the one user-visible branching
 * construct (§2.6). Guarantee side-nodes (a `gap`/`unmatched` edge to a leaf — gap watch, the
 * quarantine sink) are tolerated and skipped: they are housekeeping, not Steps, and forcing Canvas
 * over them would contradict the Guarantees doctrine (§2.4). Returns `null` when the graph is
 * genuinely not expressible: no single entry, fan-in (`merge`), or a fan-out mixing `data` with other
 * relationships — the recipe view then falls back to Canvas mode with an explanatory alert.
 */
export function detectStepChain(model: AuthoredPipeline): StepChain | null {
    const nodeById = new Map(model.nodes.map((n) => [n.id, n]));
    const outBy = new Map<string, AuthoredEdge[]>();
    const inCount = new Map<string, number>();
    for (const n of model.nodes) inCount.set(n.id, 0);
    for (const e of model.edges) {
        if (!nodeById.has(e.from) || !nodeById.has(e.to)) return null;
        outBy.set(e.from, [...(outBy.get(e.from) ?? []), e]);
        inCount.set(e.to, (inCount.get(e.to) ?? 0) + 1);
    }
    // guarantee side-nodes are not entry candidates and never part of the walk
    const sideNodes = new Set<string>();
    for (const edges of outBy.values()) for (const e of edges) if (isGuaranteeSideEdge(e, outBy)) sideNodes.add(e.to);
    const roots = model.nodes.filter((n) => (inCount.get(n.id) ?? 0) === 0 && !sideNodes.has(n.id));
    if (roots.length !== 1) return null;
    return walkStepChain(roots[0], nodeById, outBy, inCount, new Set());
}

function walkStepChain(
    start: AuthoredNode,
    nodeById: Map<string, AuthoredNode>,
    outBy: Map<string, AuthoredEdge[]>,
    inCount: Map<string, number>,
    seen: Set<string>,
): StepChain | null {
    const trunk: AuthoredNode[] = [];
    let cur: AuthoredNode | undefined = start;
    while (cur) {
        if (seen.has(cur.id)) return null; // a cycle — defensive; inCount already rules out most shapes
        seen.add(cur.id);
        trunk.push(cur);
        const outs = (outBy.get(cur.id) ?? []).filter((e) => !isGuaranteeSideEdge(e, outBy));
        if (outs.length === 0) return { trunk };
        if (outs.length === 1 && outs[0].rel === 'data') {
            const next = nodeById.get(outs[0].to);
            if (!next || (inCount.get(next.id) ?? 0) !== 1) return null; // fan-in into next — not a tree
            cur = next;
            continue;
        }
        if (outs.every((e) => e.rel.startsWith('route:'))) {
            const cfg = (cur.config ?? {}) as { branches?: { key?: string; where?: string }[]; default?: string };
            const branches: StepBranch[] = [];
            for (const e of outs) {
                const key = e.rel.slice('route:'.length);
                const next = nodeById.get(e.to);
                if (!next || (inCount.get(next.id) ?? 0) !== 1) return null;
                const sub = walkStepChain(next, nodeById, outBy, inCount, seen);
                if (!sub) return null;
                const bc = cfg.branches?.find((b) => b?.key === key);
                branches.push({ routeId: cur.id, key, where: bc?.where, isDefault: key === cfg.default, chain: sub });
            }
            return { trunk, branches };
        }
        return null; // mixed / unrecognized fan-out — not recipe-expressible
    }
    return { trunk };
}

/**
 * The recipe verbs the Add-Step palette offers, mapped client-side onto the lowerable node types
 * (UI plan §2.1). Since S4 the server publishes this same table on `GET /pipelines/step-types`
 * (`PipelineProjection.stepCatalog()`, pinned to `step-types.contract.json`) and the editor
 * dual-reads: served palette when available, this map as the old-server fallback — never a second
 * vocabulary, and a drift between the two fails the contract spec. `route` (S3) inserts via
 * {@link insertRouteAfter}, not {@link insertStepAfter}: a branch point rewires its downstream
 * edge as its first branch.
 *
 * An entry is keyed by `type`, and only by `type`: two entries can author the same recipe verb, since
 * the recipe spells a join `transform: {join: …}` and `RecipeCompiler` has no `join` verb. The verb
 * itself is the server's business (it stays on the wire type `RecipeStepType`), so it is deliberately
 * absent here — nothing client-side may key on a value that isn't unique.
 */
export const RECIPE_VERBS: readonly { type: string; label: string }[] = [
    { type: 'acquisition', label: 'Collect' },
    // 🔴 One entry PER FORMAT (pipeline spec gap 2, decision D3). The generic `parser` type is
    // READ_COMPAT_ONLY server-side, so the canvas palette never offered it — but this fallback did,
    // and so did the served catalogue, which is how a recipe author ended up with an untyped Parse
    // Step to convert through a custody dialog. A parser is always FORMAT-SPECIFIC.
    { type: 'parser.delimited', label: 'Parse (delimited)' },
    { type: 'parser.fixedwidth', label: 'Parse (fixed-width)' },
    { type: 'parser.json', label: 'Parse (JSON)' },
    { type: 'parser.text_regex', label: 'Parse (regex)' },
    { type: 'parser.xlsx', label: 'Parse (Excel)' },
    { type: 'parser.asn1', label: 'Parse (ASN.1)' },
    { type: 'parser.plugin', label: 'Parse (custom)' },
    { type: 'transform.map', label: 'Map' },
    { type: 'transform.dedup', label: 'Dedup' },
    { type: 'transform.filter', label: 'Transform (filter)' },
    { type: 'transform.join', label: 'Transform (join)' },
    { type: 'transform.summarize', label: 'Summarize' },
    { type: 'transform.route', label: 'Route' },
    { type: 'sink.persistent', label: 'Sink' },
];

/**
 * Splice a new node into the trunk after `afterId` (or as the new entry when `null`), rewiring the
 * `data` edges: `after → next` becomes `after → node → next`. Returns `null` when `afterId` names a
 * node with anything other than exactly one outgoing `data` edge (a route/branch point — S2 edits the
 * trunk only) — the caller treats that as "not insertable here", never a silent no-op.
 */
export function insertStepAfter(
    model: AuthoredPipeline,
    node: AuthoredNode,
    afterId: string | null,
): AuthoredPipeline | null {
    if (model.nodes.some((n) => n.id === node.id)) return null;
    const outBy = new Map<string, AuthoredEdge[]>();
    for (const e of model.edges) outBy.set(e.from, [...(outBy.get(e.from) ?? []), e]);
    if (afterId === null) {
        const inCount = new Map<string, number>();
        for (const e of model.edges) inCount.set(e.to, (inCount.get(e.to) ?? 0) + 1);
        const entry = model.nodes.find((n) => (inCount.get(n.id) ?? 0) === 0);
        return {
            ...model,
            nodes: [node, ...model.nodes],
            edges: entry ? [{ from: node.id, rel: 'data', to: entry.id }, ...model.edges] : [...model.edges],
        };
    }
    // guarantee side-edges (gap watch / quarantine hanging off this node) stay attached where they are
    const outs = (outBy.get(afterId) ?? []).filter((e) => !isGuaranteeSideEdge(e, outBy));
    if (outs.length > 1 || (outs.length === 1 && outs[0].rel !== 'data')) return null;
    const next = outs[0]?.to;
    return {
        ...model,
        nodes: [...model.nodes, node],
        edges: [
            ...model.edges.filter((e) => !(e.from === afterId && e.rel === 'data')),
            { from: afterId, rel: 'data', to: node.id },
            ...(next ? [{ from: node.id, rel: 'data', to: next }] : []),
        ],
    };
}

/**
 * Remove a trunk node, reconnecting its predecessor to its successor with a `data` edge. Returns
 * `null` when the node is a branch point or branch target (more than one edge in either direction,
 * or a non-`data` edge touches it) — removing those is canvas work, not a recipe-card action.
 */
export function removeStepFromChain(model: AuthoredPipeline, id: string): AuthoredPipeline | null {
    const ins = model.edges.filter((e) => e.to === id);
    const outs = model.edges.filter((e) => e.from === id);
    if (ins.length > 1 || outs.length > 1) return null;
    if (ins.some((e) => e.rel !== 'data') || outs.some((e) => e.rel !== 'data')) return null;
    const prev = ins[0]?.from;
    const next = outs[0]?.to;
    return {
        ...model,
        nodes: model.nodes.filter((n) => n.id !== id),
        edges: [
            ...model.edges.filter((e) => e.from !== id && e.to !== id),
            ...(prev && next ? [{ from: prev, rel: 'data', to: next }] : []),
        ],
    };
}

/**
 * Swap a trunk node with its `data`-edge neighbour (`up` = with its predecessor). Returns `null`
 * when either node is not strictly linear (branch points and branch targets don't reorder) or the
 * node is already at that end of the trunk.
 */
export function moveStepInChain(model: AuthoredPipeline, id: string, dir: 'up' | 'down'): AuthoredPipeline | null {
    const target =
        dir === 'up'
            ? model.edges.find((e) => e.to === id && e.rel === 'data')?.from
            : model.edges.find((e) => e.from === id && e.rel === 'data')?.to;
    if (!target) return null;
    const first = dir === 'up' ? target : id;
    const second = dir === 'up' ? id : target;
    // both must be strictly linear: exactly the edges the swap rewrites, nothing else touching them
    for (const n of [first, second]) {
        if (model.edges.filter((e) => e.from === n).length > 1) return null;
        if (model.edges.filter((e) => e.to === n).length > 1) return null;
        if (model.edges.some((e) => (e.from === n || e.to === n) && e.rel !== 'data')) return null;
    }
    const before = model.edges.find((e) => e.to === first)?.from; // may be undefined (entry)
    const after = model.edges.find((e) => e.from === second)?.to; // may be undefined (tail)
    const untouched = model.edges.filter(
        (e) => e.from !== first && e.to !== first && e.from !== second && e.to !== second,
    );
    return {
        ...model,
        edges: [
            ...untouched,
            ...(before ? [{ from: before, rel: 'data', to: second }] : []),
            { from: second, rel: 'data', to: first },
            ...(after ? [{ from: first, rel: 'data', to: after }] : []),
        ],
    };
}

/** One row of a flattened {@link StepChain} for a simple indented list render (no recursive component). */
export type StepRow =
    | { kind: 'node'; rowId: string; node: AuthoredNode; depth: number }
    | {
          kind: 'branch';
          rowId: string;
          routeId: string;
          key: string;
          where?: string;
          isDefault: boolean;
          depth: number;
      };

/** Flatten a {@link StepChain} into an ordered, indented row list — depth-first, branches after their trunk. */
export function flattenStepChain(chain: StepChain, depth = 0): StepRow[] {
    const rows: StepRow[] = chain.trunk.map((node) => ({ kind: 'node', rowId: node.id, node, depth }));
    if (chain.branches) {
        for (const b of chain.branches) {
            rows.push({
                kind: 'branch',
                rowId: `branch:${b.key}:${depth}`,
                routeId: b.routeId,
                key: b.key,
                where: b.where,
                isDefault: b.isDefault,
                depth,
            });
            rows.push(...flattenStepChain(b.chain, depth + 1));
        }
    }
    return rows;
}

// ── Route branch reducers (S3, §2.6): branch edits are node-config + edge rewrites on the SAME model ──

/** The route node's `branches` list, cloned for mutation (`[]` when absent/malformed). */
function routeBranchesOf(node: AuthoredNode): { key?: string; where?: string; [k: string]: unknown }[] {
    const b = node.config?.['branches'];
    return Array.isArray(b) ? structuredClone(b) : [];
}

/**
 * Where a newly-added branch writes.
 *
 * <p>🔴 **The destination is not cosmetic — `database` is the branch↔sink JOIN KEY on BOTH halves of the
 * round-trip.** Lowering stamps each branch entry with the database of the node its `route:<key>` edge
 * feeds (`routeSection`), the plural `sinks:` list is keyed by distinct database, and the lift pairs
 * branches back to sinks by that same value. A branch sink created with an EMPTY config therefore
 * lowered to nothing at all: the save answered success and cleared the dirty flag, and reopening the
 * pipeline showed the branch's destination gone — found by adding a branch in the Recipe editor and
 * saving (2026-08-17).
 *
 * <p>Derived from the primary sink's own home so the branch store lands BESIDE it, falling back to the
 * same `data/<name>` convention {@link pipelineScaffold} writes. Derive-instead-of-ask, as the
 * enrichment wiring does.
 */
function branchDestination(model: AuthoredPipeline, key: string): Record<string, unknown> {
    // ⚠ A `sink.persistent` that declares a string `database` — which excludes a quarantine sink, since
    // that one carries only `dir`. This module is pure and has no node-type catalog, so it cannot ask by
    // CATEGORY the way the editor's `enrichmentHost()` does; the type test is the available equivalent.
    const primary = model.nodes.find((n) => n.type === 'sink.persistent' && typeof n.config?.['database'] === 'string');
    const home = pipelineHome(model.name, primary?.config?.['database'] as string | undefined);
    return storeDirs(`${home}/${key}`);
}

/**
 * Add a named branch to a route Step: the branch entry lands in the node's `branches` list and a new
 * persistent sink is wired via a `route:<key>` edge — RecipeCompiler's rule (a branch compiles as
 * exactly one sink step for now), so the graph never holds a dangling branch entry. The sink carries a
 * DERIVED destination ({@link branchDestination}); everything else about it is still the author's.
 * Returns `null` for a missing/non-route node, a blank key, or a duplicate key (the recipe `branches`
 * map would silently collapse duplicates server-side — the UI refuses instead).
 */
export function addRouteBranch(model: AuthoredPipeline, routeId: string, key: string): AuthoredPipeline | null {
    const route = model.nodes.find((n) => n.id === routeId);
    if (!route || route.type !== 'transform.route' || !key.trim()) return null;
    const k = key.trim();
    const branches = routeBranchesOf(route);
    if (branches.some((b) => b?.key === k)) return null;
    if (model.edges.some((e) => e.from === routeId && e.rel === `route:${k}`)) return null;
    branches.push({ key: k });
    const sink: AuthoredNode = {
        id: uniqueNodeId(model, 'sink.persistent'),
        type: 'sink.persistent',
        name: k,
        config: branchDestination(model, k),
    };
    return {
        ...model,
        nodes: [...applyNodePatchInModel(model, { ...route, config: { ...route.config, branches } }).nodes, sink],
        edges: [...model.edges, { from: routeId, rel: `route:${k}`, to: sink.id }],
    };
}

/**
 * Remove a route branch: its entry, its `route:<key>` edge, and the branch's whole downstream
 * subtree. Returns `null` when any subtree node is reachable from outside the branch (fan-in — the
 * removal would orphan wiring the recipe view can't see; that's canvas work).
 */
export function removeRouteBranch(model: AuthoredPipeline, routeId: string, key: string): AuthoredPipeline | null {
    const route = model.nodes.find((n) => n.id === routeId);
    const rel = `route:${key}`;
    const start = model.edges.find((e) => e.from === routeId && e.rel === rel)?.to;
    if (!route || route.type !== 'transform.route' || !start) return null;
    // collect the branch subtree
    const doomed = new Set<string>([start]);
    let grew = true;
    while (grew) {
        grew = false;
        for (const e of model.edges)
            if (doomed.has(e.from) && !doomed.has(e.to)) {
                doomed.add(e.to);
                grew = true;
            }
    }
    // refuse when the subtree is reachable from outside it (other than the branch edge itself)
    for (const e of model.edges)
        if (doomed.has(e.to) && !doomed.has(e.from) && !(e.from === routeId && e.rel === rel)) return null;
    const branches = routeBranchesOf(route).filter((b) => b?.key !== key);
    const cfg = { ...route.config, branches };
    if (cfg['default'] === key) delete cfg['default'];
    const withPatch = applyNodePatchInModel(model, { ...route, config: cfg });
    return {
        ...withPatch,
        nodes: withPatch.nodes.filter((n) => !doomed.has(n.id)),
        edges: withPatch.edges.filter((e) => !doomed.has(e.from) && !doomed.has(e.to)),
    };
}

/** Set (or clear, with `''`) a branch's `when`/`where` predicate on the route node's config. */
export function setRouteBranchWhere(
    model: AuthoredPipeline,
    routeId: string,
    key: string,
    where: string,
): AuthoredPipeline | null {
    const route = model.nodes.find((n) => n.id === routeId);
    if (!route || route.type !== 'transform.route') return null;
    const branches = routeBranchesOf(route);
    const b = branches.find((x) => x?.key === key);
    if (!b) return null;
    if (where.trim()) b.where = where.trim();
    else delete b.where;
    return applyNodePatchInModel(model, { ...route, config: { ...route.config, branches } });
}

/**
 * Mark `key` the route's default branch (`null` clears it). Zero-or-one default is the engine's real
 * contract (RowShaper's case-mode `ELSE`; no exactly-one refusal exists server-side) — the scalar
 * `default` key enforces at-most-one structurally.
 */
export function setRouteDefault(model: AuthoredPipeline, routeId: string, key: string | null): AuthoredPipeline | null {
    const route = model.nodes.find((n) => n.id === routeId);
    if (!route || route.type !== 'transform.route') return null;
    if (key !== null && !routeBranchesOf(route).some((b) => b?.key === key)) return null;
    const cfg = { ...route.config };
    if (key === null) delete cfg['default'];
    else cfg['default'] = key;
    return applyNodePatchInModel(model, { ...route, config: cfg });
}

/**
 * Splice a route Step in after `afterId`, rewiring the single downstream `data` edge as the route's
 * FIRST branch (`route:<branches[0].key>`) — a branch point routes *to* something, so unlike
 * {@link insertStepAfter} a downstream is required, and the node must arrive carrying at least one
 * branch entry. Returns `null` otherwise, or when `afterId` isn't strictly linear.
 */
export function insertRouteAfter(
    model: AuthoredPipeline,
    node: AuthoredNode,
    afterId: string,
): AuthoredPipeline | null {
    if (model.nodes.some((n) => n.id === node.id)) return null;
    const key = (node.config?.['branches'] as { key?: string }[] | undefined)?.[0]?.key;
    if (node.type !== 'transform.route' || !key) return null;
    const outBy = new Map<string, AuthoredEdge[]>();
    for (const e of model.edges) outBy.set(e.from, [...(outBy.get(e.from) ?? []), e]);
    const outs = (outBy.get(afterId) ?? []).filter((e) => !isGuaranteeSideEdge(e, outBy));
    if (outs.length !== 1 || outs[0].rel !== 'data') return null;
    return {
        ...model,
        nodes: [...model.nodes, node],
        edges: [
            ...model.edges.filter((e) => !(e.from === afterId && e.rel === 'data')),
            { from: afterId, rel: 'data', to: node.id },
            { from: node.id, rel: `route:${key}`, to: outs[0].to },
        ],
    };
}

/**
 * The wiring rules for one node type, exactly as `GET /pipelines/node-types` publishes them.
 *
 * 🔴 `accepts` has been served all along and the editor simply never read it — which is why a canvas
 * edge could be drawn that the save then 422s (pipeline spec gap 3).
 */
export interface EdgeRules {
    accepts: string[];
    emits: string[];
    emitsNamedRoutes: boolean;
}

/**
 * Why `(from) --rel--> (to)` cannot be wired, or `null` when it can — a faithful mirror of the SERVER's
 * `PipelineValidator` edge checks (`ILLEGAL_EMIT` / `ILLEGAL_ACCEPT` / `ILLEGAL_PAIRING`).
 *
 * <p>⚠ The mirror must be exact in BOTH directions. Looser and the canvas builds a graph the save
 * refuses; stricter and it greys out work the backend would happily store — the same class of bug
 * pointing the other way. Three details carry that:
 * <ul>
 *   <li>an <b>unknown type</b> is exempt, because the server's checks sit inside
 *       `PipelineNodeTypes.get(...).ifPresent(...)` — a served or plugin type this map has not seen
 *       must not be refused here;</li>
 *   <li><b>`on_commit` is exempt</b> — it is a cross-pipeline trigger whose target is not a local node;</li>
 *   <li>an outcome/route edge passes if the target accepts that rel <b>or accepts `data`</b> (the
 *       handler exemption: a reject or route stream is rows to a row-consumer).</li>
 * </ul>
 */
export function edgeRefusal(
    model: AuthoredPipeline | null,
    from: string,
    to: string,
    rel: string,
    rules: ReadonlyMap<string, EdgeRules>,
): string | null {
    const src = model?.nodes.find((n) => n.id === from);
    const dst = model?.nodes.find((n) => n.id === to);
    const srcRules = src ? rules.get(src.type) : undefined;
    const dstRules = dst ? rules.get(dst.type) : undefined;

    if (srcRules && !(srcRules.emits.includes(rel) || (isRouteRel(rel) && srcRules.emitsNamedRoutes)))
        return (
            `${src?.type} does not emit '${rel}' — it emits ${srcRules.emits.join(', ') || 'nothing'}` +
            `${srcRules.emitsNamedRoutes ? ' + route:*' : ''}.`
        );

    if (!dstRules) return null;
    if (rel === 'data')
        return dstRules.accepts.includes('data')
            ? null
            : `${dst?.type} does not accept data — it accepts ${dstRules.accepts.join(', ') || 'nothing'}.`;
    if (rel === 'on_commit') return null;
    return dstRules.accepts.includes(rel) || dstRules.accepts.includes('data')
        ? null
        : `${dst?.type} cannot be wired after '${from}' via '${rel}' — it accepts ` +
              `${dstRules.accepts.join(', ') || 'nothing'} and does not consume rows.`;
}

/** Whether `rel` is an operator-defined content route (`route:<key>`), mirroring `PipelineRel.isRoute`. */
function isRouteRel(rel: string): boolean {
    return rel.startsWith('route:') && rel.length > 'route:'.length;
}

/**
 * Relationships a canvas edge may carry: the source node's emitted rels, `data`, and the edge's current
 * rel — minus any the TARGET refuses, so the picker cannot offer a relabel the save would 422.
 */
export function candidateRelsFor(
    model: AuthoredPipeline | null,
    g6EdgeId: string,
    typeEmits: ReadonlyMap<string, string[]>,
    rules?: ReadonlyMap<string, EdgeRules>,
): string[] {
    const p = decodeEdgeId(g6EdgeId);
    if (!p) return [];
    const src = model?.nodes.find((n) => n.id === p.from);
    const emits = src ? (typeEmits.get(src.type) ?? []) : [];
    const all = [...new Set(['data', ...emits, p.rel])];
    if (!rules) return all;
    // ⚠ The edge's CURRENT rel always stays offered even when refused: a stored graph may already carry
    // a pairing this deployment's types no longer allow, and dropping it from the list would silently
    // re-label the edge the moment the picker is opened.
    return all.filter((rel) => rel === p.rel || !edgeRefusal(model, p.from, p.to, rel, rules));
}
