import { InjectionToken, Signal, Type, signal } from '@angular/core';
import { EMPTY, Observable } from 'rxjs';
import type { EntityFamily, EntityTypeGuide, RecordTypeGuide } from '@inspecto/core/api/entity-type-options';

/** The link index's live state for a Dataset, as Link Analysis words it (`datasetIndexStatus` there). */
export type LinkIndexState = 'current' | 'out_of_date' | 'unknown';

/** The Space's Entity Types, as `GET /inv/entity-types` lists them (only what Add data reads). */
export interface AddDataEntityTypes {
    items?: EntityTypeGuide[];
    families?: EntityFamily[];
    recordTypes?: RecordTypeGuide[];
}

/** One Link Analysis limit in force (`GET /settings/link-analysis/limits`; Add data reads the `import.*` keys). */
export interface AddDataLimit {
    key: string;
    inForce: number | null;
}

/**
 * What Add data reads from Link Analysis (BASIC-ELT-PROFILE-1 UI-0). Add data is core, and core never imports the Link
 * Analysis library (the arrow runs core <- library <- shells), so the library hands these in through this token:
 * `provideAddDataLinkAnalysis()` in `@inspecto/link-analysis/link-analysis/add-data-link-analysis.providers`, provided by
 * each page that places Add data or a Dataset card.
 *
 * Without a provider every part is absent: no Entity Types (the draft's proposals stand in), no limits line, no link index
 * status (the arrivals' recorded outcomes decide the Graph fact), and no Rebuild.
 */
export interface AddDataLinkAnalysis {
    /** The Space's Entity Types; a failure or no answer leaves the picker on the draft's proposals. */
    entityTypes(): Observable<AddDataEntityTypes>;
    /** The limits in force, read once per Space. Called in an injection context, outside any reactive read. */
    limits(): Signal<readonly AddDataLimit[]>;
    /** The Dataset's link index status, or null when nothing can be said. Reads a signal: call it inside a `computed`. */
    indexStatus(dataset: string): { state: LinkIndexState } | null;
    /** Read the Space's link indexes once (a no-op once answered). */
    loadIndexes(): void;
    /** Read them again (an import built or skipped an index). */
    reloadIndexes(): void;
    /** The Rebuild action beside an out-of-date index, rendered with the input `dataset`; null when there is none. */
    readonly rebuild: Type<unknown> | null;
}

export const ADD_DATA_LINK_ANALYSIS = new InjectionToken<AddDataLinkAnalysis>('ADD_DATA_LINK_ANALYSIS', {
    providedIn: 'root',
    factory: (): AddDataLinkAnalysis => {
        const none = signal<readonly AddDataLimit[]>([]).asReadonly();
        return {
            entityTypes: () => EMPTY,
            limits: () => none,
            indexStatus: () => null,
            loadIndexes: () => undefined,
            reloadIndexes: () => undefined,
            rebuild: null,
        };
    },
});
