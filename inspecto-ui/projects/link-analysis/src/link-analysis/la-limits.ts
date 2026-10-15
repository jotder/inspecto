import { signal } from '@angular/core';

/**
 * Link Analysis limits (LA-CONFIG-LIMITS-1, plan `docs/superpower/la-configurable-limits-plan.md` §6.3, §7): the ONE
 * place the SPA knows a limit's number before the server has said it.
 *
 * <p>The server is the judge: `GET /settings/link-analysis/limits` publishes every item with the value in force, and
 * {@link LaLimitsService} pushes the values this browser should use (the analyst's own value for a `browser:true`
 * item, else the Space's) into {@link configureLaLimits}. Code reads a limit with {@link laLimit}, never with a
 * constant of its own. The pure modules (graph algorithms, projections, form validators) read it at call time; the
 * store is a signal, so a template or `computed()` that reads it re-renders when the limits arrive or change.
 *
 * <p>⚠ {@link LA_LIMIT_FALLBACKS} is what the server's catalogue ships. It is used only while the GET has not answered,
 * when it failed, or when the server does not publish a key — never as a second opinion beside a served value. Every
 * key here is the backend catalogue's own (`LaLimit`), spelled exactly as served, with the catalogue's default:
 * `LaLimitCatalogueParityTest` (inspecto-la-api) reads this table and fails when the two disagree.
 */
export const LA_LIMIT_FALLBACKS = {
    // SPACE, browser-overridable (§7). P2: canvas 1 000 (CL-1), suspicion 1 000 for browser AND server (CL-2),
    // general browser cap 2 500 (CL-3).
    projection_node_cap: 1000,
    analysis_node_cap: 2500,
    suspicion_node_cap: 1000,
    // CL-3: one cap per browser algorithm the SPA guards - 2 500, or the measured (server inline) ceiling where lower.
    // suspicionScore has none: suspicion_node_cap is its one key.
    'browser_algorithms.betweennessCentrality': 500,
    'browser_algorithms.closenessCentrality': 1000,
    'browser_algorithms.eigenvectorCentrality': 1000,
    'browser_algorithms.katzCentrality': 1000,
    'browser_algorithms.hits': 1000,
    'browser_algorithms.cliques': 1000,
    'browser_algorithms.maxFlow': 1000, // vocab-allow: the max-flow graph algorithm id
    'browser_algorithms.linkPrediction': 1000,
    'browser_algorithms.louvainCommunities': 2500,
    'browser_algorithms.detectCommunities': 2500,
    // the selection algorithms hand over to the server above min(analysis_node_cap, their own cap)
    'browser_algorithms.shortestPath': 2500,
    'browser_algorithms.weightedShortestPath': 2500,
    'browser_algorithms.allPaths': 2500,
    'browser_algorithms.findCycles': 2500,
    'browser_algorithms.articulationPoints': 2500,
    'browser_algorithms.bridges': 2500,
    'browser_algorithms.maximumSpanningForest': 2500,
    'display.lod_label_cap': 300,
    'display.layered_max_nodes': 30,
    'display.force_atlas_max_nodes': 800,
    'display.embed_label_limit': 80,
    'display.embed_edge_limit': 3000,
    'display.geo_point_cap': 5000,
    // SPACE (§7)
    'graph_run.max_result_items': 10_000,
    'investigation.expand_budget_default': 4000,
    'investigation.expand_budget_max': 40_000,
    'investigation.max_frontier': 2000,
    'connect.max_seeds': 40,
    'connect.max_hops': 6,
    'measures.max_window_days': 31,
    'dossier.max_snapshots': 40,
    'input.link_kinds': 100,
    'input.recurring_excludes': 32,
    'input.note_length': 2000,
    'input.purpose_length': 1000,
} as const satisfies Record<string, number>;

/** A limit the SPA itself reads. */
export type LaLimitKey = keyof typeof LA_LIMIT_FALLBACKS;

/** One item of `GET /settings/link-analysis/limits` (plan §6.2). */
export interface LaLimitItem {
    /** The dotted TOON path, also the wire key (`projection_node_cap`, `display.lod_label_cap`, …). */
    key: string;
    /** `space` = the Space admin's; `install` = install-wide, the system admin's. */
    layer: 'space' | 'install';
    /** True when the analyst may set a personal value in their browser, clamped to the Space value. */
    browser: boolean;
    group: string;
    /** `nodes`, `ms`, `mb`, `per_s`, …: every value is a whole number except a `per_s` rate, which is a decimal. */
    unit: string;
    /** The shipped default; null = none (the two four-eyes items and `index.build_memory_pct`). */
    default: number | null;
    min: number;
    max: number;
    /** The stated value; null = inherit the default. */
    value: number | null;
    /** The value in force (stated, else the default); null = none. */
    inForce: number | null;
    /** Whether THIS caller may change it. */
    editable: boolean;
    description: string;
}

/** The answer of the GET and of both PUTs. */
export interface LaLimitsResponse {
    items: LaLimitItem[];
    /** `podScoped`: the install items apply to this server (pod) only, like `/system/scheduler`. */
    metadata?: { podScoped?: boolean };
}

const values = signal<Partial<Record<LaLimitKey, number>>>({});

/** The limit this browser uses now: what the service last published, else the shipped fallback. Read it, never cache it. */
export function laLimit(key: LaLimitKey): number {
    return values()[key] ?? LA_LIMIT_FALLBACKS[key];
}

/**
 * Apply limits on top of the ones in force (a key left out keeps its value).
 *
 * ⛔ **Fails closed on nonsense rather than applying it.** A value that is not a finite number ≥ 1 is REFUSED and the
 * previous value stands: a cap of 0 or NaN would make every graph "over the limit" and turn the analysis toolbox off,
 * or yield an empty projection that reads as "no data" rather than "misconfigured". A fraction is floored.
 */
export function configureLaLimits(limits: Partial<Record<LaLimitKey, number>> | null | undefined): void {
    if (!limits) return;
    const next = { ...values() };
    for (const [k, v] of Object.entries(limits) as [LaLimitKey, number | undefined][])
        if (k in LA_LIMIT_FALLBACKS && typeof v === 'number' && Number.isFinite(v) && v >= 1) next[k] = Math.floor(v);
    values.set(next);
}

/** Back to the shipped fallbacks — for tests, and before the service publishes another Space's values. */
export function resetLaLimits(): void {
    values.set({});
}

/** `v` clamped to `[min, max]`. */
export function clampLimit(v: number, min: number, max: number): number {
    return Math.min(Math.max(v, min), max);
}
