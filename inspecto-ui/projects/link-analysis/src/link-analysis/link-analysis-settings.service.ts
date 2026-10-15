import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { SpacesService, apiUrl } from '@inspecto/core/api';
import { type EntityNormaliser } from '@inspecto/core/graph';

/**
 * Per-space Link Analysis tuning limits. Each field is `null` when unset, meaning **use the shipped
 * default** — never "unbounded". Persisted via `GET|PUT /settings/link-analysis`.
 */
export interface LinkAnalysisLimits {
    /** Nodes admitted to one projection; `null` = the shipped default. */
    projectionNodeCap: number | null;
    /** Nodes above which the browser-side algorithms refuse; `null` = the shipped default. */
    analysisNodeCap: number | null;
    /**
     * Nodes above which **suspicion score alone** refuses; `null` = the shipped default. It has its own,
     * lower ceiling because its cost is quadratic while the other 26 algorithms are trivial at the shared
     * cap — one slow algorithm should not set the limit for the 25 that finish in under 60 ms (D-S3).
     */
    suspicionNodeCap: number | null;
    /** LA-17: the Space's stated Entity Types; `null` = inherit the seeded defaults. A stated list REPLACES them. */
    entityTypes: EntityTypeConfig[] | null;
    /** LA-17: the Entity Types actually in force (stated, else the defaults) — server-computed, read-only. */
    entityTypesInForce: EntityTypeConfig[];
    /** LA-19 (D-U7): the masking mode stated; `null` = the default (`typed`). Round-tripped by a save. */
    maskingMode?: string | null;
    /** The masking mode actually in force (stated, else `typed`) - server-computed, read-only. */
    maskingModeInForce?: string;
    /** LA-19 (D-U7): an expand whose budget is above this needs a second pair of eyes; `null` = no threshold. */
    fourEyesBudgetAbove?: number | null;
    /** LA-19 (D-U7): an expand whose fan-out is above this needs a second pair of eyes; `null` = no threshold. */
    fourEyesFanOutAbove?: number | null;
    /** Supernode suppression (`hub_threshold`): distinct contacts above which an expand flags an entity
     *  `highConnectivity` — shown, not expanded further; `null` = the shipped default (500). */
    hubThreshold?: number | null;
    /** The hub threshold actually in force — server-computed, read-only. */
    hubThresholdInForce?: number;
    /** LA-17: distinct values per bound column a merged `expand` may scan; `null` = the shipped default. */
    mergedDistinctCap?: number | null;
    /** LA-17: the merged-expand cap actually in force — server-computed, read-only. */
    mergedDistinctCapInForce?: number;
    /** Parallel backend lane (may be absent): the `seedBy` distinct cap stated, and the one in force. */
    seedByDistinctCap?: number | null;
    seedByDistinctCapInForce?: number;
    /** Per-set size limit of a sealed Working Set in bytes (1024..1073741824); `null` = 64 MiB. A larger set is refused 413. */
    maxSetBytes?: number | null;
    /** The size limit actually in force (bytes) - server-computed, read-only. */
    maxSetBytesInForce?: number;
    /** Per-Investigation TOTAL of sealed Working Sets in bytes (1048576..1099511627776); `null` = 4 GiB. A write over it is refused 413. */
    maxInvestigationBytes?: number | null;
    /** The total budget actually in force (bytes) - server-computed, read-only. */
    maxInvestigationBytesInForce?: number;
    /** D7-6: the stated Draft settings; `null`/absent = every key inherits. */
    drafts?: Partial<DraftSettings> | null;
    /** D7-6: the Draft settings actually in force - server-computed, read-only. */
    draftsInForce?: DraftSettings;
    /**
     * D-4: the server-side graph-run knobs stated (default budget, workers, waiting line); `null` = every one inherits.
     * The form edits `maxNodes`, `maxEdges` and `timeoutMs`; the rest is round-tripped by a save (the PUT replaces).
     */
    graphRun?: {
        maxNodes: number | null;
        maxEdges: number | null;
        timeoutMs: number | null;
        threads: number | null;
        queue: number | null;
        /** Items per list a run's result may carry (absent/null = 10 000). */
        maxResultItems?: number | null;
        /** `propagatedRisk`'s default per-hop weights (1..6 numbers in [0,1]); absent/null = [1.0, 0.6, 0.35, 0.15]. */
        propagatedRiskWeights?: number[] | null;
    } | null;
    /** The `propagatedRisk` weights a run without `weights` takes - server-computed, read-only. */
    propagatedRiskWeightsInForce?: number[];
    /** The graph-run worker / waiting-line / result-list values in force - server-computed, read-only. */
    graphRunInForce?: { threads: number; queue: number; maxResultItems: number };
    /**
     * D-3: the edge/node index knobs stated (`enabled` default false, `maxDiskBytes` 0 = no limit, versions kept, build
     * workers, waiting line, maxAgeHours); `null` = every one inherits. The form edits `enabled`, `threads`, `queue` and
     * `maxDiskBytes`; `keepVersions` is round-tripped by a save (the PUT replaces).
     */
    index?: {
        enabled: boolean | null;
        maxDiskBytes: number | null;
        keepVersions: number | null;
        threads: number | null;
        queue: number | null;
        /** Hours an index may age and still serve a Dataset whose input files cannot be fingerprinted (LA-INDEX-MAX-AGE-1). */
        maxAgeHours?: number | null;
    } | null;
    /** The index knobs actually in force (shipped default or stated) - server-computed, read-only. `maxDiskBytes` 0 = no limit. */
    indexInForce?: { enabled: boolean; maxDiskBytes: number; keepVersions: number; threads: number; queue: number; maxAgeHours: number };
    /** LA-EMBED-VIEW-1: the embed-link knobs stated (lifetimes in hours, classifications minting is refused for); `null` = every one inherits. */
    embed?: { defaultTtlHours?: number | null; maxTtlHours?: number | null; refuseClassifications?: string[] | null; frameAncestors?: string[] | null } | null;
    /** The embed knobs a mint applies now - server-computed, read-only. */
    embedInForce?: { defaultTtlHours: number; maxTtlHours: number; refuseClassifications: string[]; frameAncestors?: string[] };
    /** The active InvestigationStore backend (`-Dinvestigations.backend`): `fs` = filesystem, `db` = PostgreSQL. Read-only, not a setting. */
    investigationStoreInForce?: 'fs' | 'db';
}

/** D7-6: the per-Space Draft admission and idle periods; a `null` key inherits the shipped default (50 / 60 / 30). */
export interface DraftSettings {
    /** Open Drafts per Space, 1..1000. */
    maxOpen: number | null;
    /** Idle minutes before a Draft hibernates, 1..10080. */
    hibernateAfterMinutes: number | null;
    /** Idle days before a Draft expires, 1..3650; must be longer than the hibernation. */
    expireAfterDays: number | null;
}

/** LA-17: one Entity Type (entity-model design §4.1) — `classifications` are the Dataset column classifications it claims. */
export interface EntityTypeConfig {
    id: string;
    label: string;
    normaliser: EntityNormaliser;
    masked: boolean;
    classifications: string[];
}

const UNSET: LinkAnalysisLimits = {
    projectionNodeCap: null,
    analysisNodeCap: null,
    suspicionNodeCap: null,
    entityTypes: null,
    entityTypesInForce: [],
};

/**
 * Holds the active space's Link Analysis settings document (`GET|PUT /settings/link-analysis`).
 *
 * <p>⚠ The numeric limits the canvas and the algorithms obey are NOT applied from here any more: they are items of
 * `GET /settings/link-analysis/limits`, read by `LaLimitsService` (LA-CONFIG-LIMITS-1). This document still carries
 * their stated values, so a save here round-trips them.
 *
 * <p>⚠ The settings are per space, so an `effect` re-reads them whenever the active space changes; a failed fetch
 * RESETS to the unset document rather than leaving the previous space's values in force.
 */
@Injectable({ providedIn: 'root' })
export class LinkAnalysisSettingsService {
    private http = inject(HttpClient);
    private spaces = inject(SpacesService);

    /** What the server last said, `null` fields meaning "inherit". */
    readonly limits = signal<LinkAnalysisLimits>(UNSET);

    constructor() {
        effect(() => {
            this.spaces.currentSpaceId(); // track
            this.get().subscribe({
                next: (l) => this.apply(l),
                error: () => this.apply(UNSET),
            });
        });
    }

    get(): Observable<LinkAnalysisLimits> {
        return this.http.get<LinkAnalysisLimits>(apiUrl('/settings/link-analysis'));
    }

    save(limits: LinkAnalysisLimits): Observable<LinkAnalysisLimits> {
        return this.http
            .put<LinkAnalysisLimits>(apiUrl('/settings/link-analysis'), limits)
            .pipe(tap((l) => this.apply(l)));
    }

    private apply(l: LinkAnalysisLimits): void {
        this.limits.set(l ?? UNSET);
    }
}
