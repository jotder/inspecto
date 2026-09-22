import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { apiUrl } from './api-base';
import { SpacesService } from './spaces.service';
import {
    ANALYSIS_NODE_CAP_DEFAULT,
    analysisNodeCapValue,
    configureGraphLimits,
    resetGraphLimits,
} from 'app/inspecto/graph';
import {
    PROJECTION_NODE_CAP_DEFAULT,
    configureProjectionLimits,
    projectionNodeCapValue,
    resetProjectionLimits,
} from 'app/modules/admin/studio/link-analysis/entity-projection';

/**
 * Per-space Link Analysis tuning limits. Each field is `null` when unset, meaning **use the shipped
 * default** — never "unbounded". Persisted via `GET|PUT /settings/link-analysis`.
 */
export interface LinkAnalysisLimits {
    /** Nodes admitted to one projection; `null` = the shipped default. */
    projectionNodeCap: number | null;
    /** Nodes above which the browser-side algorithms refuse; `null` = the shipped default. */
    analysisNodeCap: number | null;
}

const UNSET: LinkAnalysisLimits = { projectionNodeCap: null, analysisNodeCap: null };

/**
 * Holds the active space's graph limits and applies them to the two pure graph modules.
 *
 * <p><b>Why this exists.</b> The shipped caps were measured on ONE host, ONE browser and ONE synthetic
 * graph shape (plan §1.7): the default layered layout draws 500 nodes in ~0.9 s but 750 in ~10.7 s, and
 * 25 of 27 algorithms are trivial at 2 000 nodes while suspicion score alone takes 6.7 s. An analyst's
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
    readonly defaults = { projectionNodeCap: PROJECTION_NODE_CAP_DEFAULT, analysisNodeCap: ANALYSIS_NODE_CAP_DEFAULT };

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
    effective(): { projectionNodeCap: number; analysisNodeCap: number } {
        return { projectionNodeCap: projectionNodeCapValue(), analysisNodeCap: analysisNodeCapValue() };
    }

    private apply(l: LinkAnalysisLimits): void {
        this.limits.set(l ?? UNSET);
        // Reset first: an absent field means "inherit the default", and without the reset it would
        // instead mean "keep whatever the previous space set", which is a different and wrong answer.
        resetGraphLimits();
        resetProjectionLimits();
        if (l?.analysisNodeCap != null) configureGraphLimits({ analysisNodeCap: l.analysisNodeCap });
        if (l?.projectionNodeCap != null) configureProjectionLimits({ projectionNodeCap: l.projectionNodeCap });
    }
}
