import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { SpacesService, apiUrl } from '@inspecto/core/api';
import {
    ANALYSIS_NODE_CAP_DEFAULT,
    SUSPICION_NODE_CAP_DEFAULT,
    analysisNodeCapValue,
    configureGraphLimits,
    resetGraphLimits,
    suspicionNodeCapValue,
} from '@inspecto/link-analysis/graph/graph-analysis';
import { type EntityNormaliser } from '@inspecto/core/graph';
import {
    PROJECTION_NODE_CAP_DEFAULT,
    configureProjectionLimits,
    projectionNodeCapValue,
    resetProjectionLimits,
} from './entity-projection';

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
    /** LA-19 (D-U7): an expand whose budget is above this needs a second pair of eyes; `null` = no threshold. */
    fourEyesBudgetAbove?: number | null;
    /** LA-19 (D-U7): an expand whose fan-out is above this needs a second pair of eyes; `null` = no threshold. */
    fourEyesFanOutAbove?: number | null;
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
    /** D7-6: the stated Draft settings; `null`/absent = every key inherits. */
    drafts?: Partial<DraftSettings> | null;
    /** D7-6: the Draft settings actually in force - server-computed, read-only. */
    draftsInForce?: DraftSettings;
    /**
     * D-4: the server-side graph-run knobs stated (default budget, workers, waiting line); `null` = every one inherits.
     * No form field yet - it is only round-tripped by a save, because the PUT replaces the whole document.
     */
    graphRun?: {
        maxNodes: number | null;
        maxEdges: number | null;
        timeoutMs: number | null;
        threads: number | null;
        queue: number | null;
        /** Items per list a run's result may carry (absent/null = 10 000). */
        maxResultItems?: number | null;
    } | null;
    /**
     * D-3: the edge/node index knobs stated (`enabled` default false, `maxDiskBytes` 0 = no limit, versions kept, build
     * workers, waiting line); `null` = every one inherits. No form field yet - round-tripped by a save, the PUT replaces.
     */
    index?: {
        enabled: boolean | null;
        maxDiskBytes: number | null;
        keepVersions: number | null;
        threads: number | null;
        queue: number | null;
    } | null;
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
 * Holds the active space's graph limits and applies them to the two pure graph modules.
 *
 * <p><b>Why this exists.</b> The shipped caps were measured on ONE host, ONE browser and ONE synthetic
 * graph shape (plan §1.7): the default layered layout draws 500 nodes in ~0.9 s but 750 in ~10.7 s, and
 * 25 of 27 algorithms are trivial at 2 000 nodes while suspicion score alone takes ~7 s — which is why it
 * now carries its OWN lower cap (750, the last measured point under a second; the curve is quadratic).
 * An analyst's
 * laptop, a denser graph or a different edge-to-node ratio all move those numbers, so a single
 * compiled-in constant is necessarily someone else's wrong answer. The measured values stay as
 * DEFAULTS; a deployment tunes them per space.
 *
 * <p>The graph modules are pure libraries with no dependency injection, so the values are pushed into
 * them ({@link configureGraphLimits}, {@link configureProjectionLimits}) rather than read out — and both
 * setters refuse a value that is not a finite integer ≥ 1, so a bad setting leaves the default standing
 * instead of turning the analysis toolbox off.
 *
 * <p>⚠ The limits are per space, so an `effect` re-applies them whenever the active space changes; a
 * failed fetch RESETS to the shipped defaults rather than leaving the previous space's override in
 * force, which would silently tune one space by another's settings.
 */
@Injectable({ providedIn: 'root' })
export class LinkAnalysisSettingsService {
    private http = inject(HttpClient);
    private spaces = inject(SpacesService);

    /** What the server last said, `null` fields meaning "inherit". */
    readonly limits = signal<LinkAnalysisLimits>(UNSET);
    /** The shipped defaults, for a settings form to show as placeholder text. */
    readonly defaults = {
        projectionNodeCap: PROJECTION_NODE_CAP_DEFAULT,
        analysisNodeCap: ANALYSIS_NODE_CAP_DEFAULT,
        suspicionNodeCap: SUSPICION_NODE_CAP_DEFAULT,
    };

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

    /** The values actually in force right now — what the footer publishes to the analyst. */
    effective(): { projectionNodeCap: number; analysisNodeCap: number; suspicionNodeCap: number } {
        return {
            projectionNodeCap: projectionNodeCapValue(),
            analysisNodeCap: analysisNodeCapValue(),
            suspicionNodeCap: suspicionNodeCapValue(),
        };
    }

    private apply(l: LinkAnalysisLimits): void {
        this.limits.set(l ?? UNSET);
        // Reset first: an absent field means "inherit the default", and without the reset it would
        // instead mean "keep whatever the previous space set", which is a different and wrong answer.
        resetGraphLimits();
        resetProjectionLimits();
        if (l?.analysisNodeCap != null) configureGraphLimits({ analysisNodeCap: l.analysisNodeCap });
        if (l?.suspicionNodeCap != null) configureGraphLimits({ suspicionNodeCap: l.suspicionNodeCap });
        if (l?.projectionNodeCap != null) configureProjectionLimits({ projectionNodeCap: l.projectionNodeCap });
    }
}
