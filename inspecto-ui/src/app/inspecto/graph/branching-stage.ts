/**
 * The branching-motif STAGE types, split out of {@link ./branching-pattern-engine} so the shared API client
 * (`api/inv.service`) can name them without importing the Link Analysis engine (D-5 step 1: core must not
 * import library code). Re-exported by the engine, so every existing import path still resolves.
 */

/**
 * A per-leg numeric range — the pack's VISIBLE threshold, e.g. `900 ≤ AMOUNT < 1000`. `min` is inclusive and
 * `max` exclusive, because a reporting threshold is crossed AT its value: a 1 000 deposit is reported, a
 * 999.99 one is not. Either bound may be absent. A band (both bounds) is what "just under the threshold"
 * means, and it is what keeps ordinary small payments — the long tail under any threshold — out of the motif.
 */
export interface LegThreshold {
    /** The edge attribute column (entity-projection `attrCols`), parsed with `Number`. */
    attr: string;
    min?: number;
    max?: number;
}

/** One stage of a branching motif. The temporal fields are LA-14a's, with the same meaning. */
export interface BranchStage {
    shape: 'fan-in' | 'fan-out';
    /** Minimum DISTINCT counterparties at this stage (senders for fan-in, receivers for fan-out). ≥ 1. */
    minBranches: number;
    /** The kind each leg must be (base kind); wildcard when absent. */
    edgeKind?: string;
    /** The kind of the node(s) this stage reaches (the collector for fan-in, each branch for fan-out). */
    nodeKind?: string;
    /** Each counted leg must pass this — legs that fail it are not part of the motif. */
    threshold?: LegThreshold;
    /** All counted legs of this stage fall within this many hours of each other (per collector/splitter). */
    windowHours?: number;
    /** Each leg is strictly after the match reached its tail node. Ignored on stage 0. */
    afterPrevious?: boolean;
    /** Upper bound on that gap, in hours. Only meaningful with {@link afterPrevious}. */
    maxGapHours?: number;
}
