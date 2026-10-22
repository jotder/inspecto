import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from '@inspecto/core/api/api-base';

/**
 * The Entity profile reads (la-entity-profile-plan section 4.5, the contract fixed 2026-10-15). Every section carries
 * `assessed` + `reason`: an unassessed section was not measured, so it is never a zero. `approximate` marks a number
 * the server estimated (distinct counts). A masked Entity Type never returns a raw identifier: a key is the
 * exploration alias (`masked:<16 hex>`).
 */

/** Whether a section was measured, and why not. */
export interface Assessed {
    assessed: boolean;
    reason: string | null;
}

/** `source` / `target` = a link end of a Dataset's Link Analysis mapping; `attribute` otherwise. */
export type EntityBindingRole = 'source' | 'target' | 'attribute';

export interface EntityTypeBinding {
    dataset: string;
    column: string;
    role: EntityBindingRole;
}

export interface EntityTypeHeadline extends Assessed {
    distinct: number | null;
    approximate: boolean;
    rows: number | null;
    dataAsOf: string | null;
    computedAt: string | null;
}

/** One Entity Type in force, with its bindings and its cached headline (`GET /inv/entity-types`). */
export interface EntityTypeSummary {
    id: string;
    label: string;
    normaliser: string;
    masked: boolean;
    classifications: string[];
    bindings: EntityTypeBinding[];
    headline: EntityTypeHeadline;
}

export interface EntityTypeList {
    items: EntityTypeSummary[];
}

export interface EntityTypeIdentity {
    id: string;
    label: string;
    normaliser: string;
    masked: boolean;
    classifications: string[];
}

export interface ProfileBinding extends EntityTypeBinding, Assessed {
    timeColumn: string | null;
    rows: number;
    nonNull: number;
    fillRate: number;
    distinct: number;
    approximate: boolean;
    failNormaliser: number;
}

/** `builtin` sentinels are shown by value; a `dominant` one never is (`value:null`, aggregates only). */
export interface ProfileSentinel {
    dataset: string;
    column: string;
    kind: 'builtin' | 'dominant';
    value: string | null;
    rows: number;
    share: number;
}

/** A profile section the server cut at a limit says `truncated: true`. */
export interface Truncatable {
    truncated?: boolean;
}

export interface ProfileVolume extends Assessed, Truncatable {
    distinct: number;
    approximate: boolean;
    rows: number;
    sentinels: ProfileSentinel[];
}

export interface ProfileShapes extends Assessed, Truncatable {
    length: { length: number; rows: number }[];
    prefix: { prefix: string; rows: number }[];
    /** The safe prefix the normaliser declares; 0 = no prefix histogram. */
    prefixLength: number;
    charClass: { digits: number; letters: number; mixed: number };
    sampled: boolean;
}

export interface DatasetAsOf {
    dataset: string;
    dataAsOf: string | null;
}

export interface ProfileTime extends Assessed, Truncatable {
    earliest: string | null;
    latest: string | null;
    perDay: { day: string; rows: number; distinct: number }[];
    missingDays: string[];
    dataAsOf: DatasetAsOf[];
}

export interface ProfileLineage extends Assessed {
    dataset: string;
    pipelines: string[];
    collectors: string[];
    files: number;
    rows: number;
    latestIngestedAt: string | null;
    waitingForIndex: number;
    problemFiles: number;
}

export interface DegreeQuantiles {
    p50: number;
    p90: number;
    p99: number;
    max: number;
}

export interface ProfileLinks extends Assessed, Truncatable {
    /** `index` = the link index manifest; `dataset` = the fallback Dataset query. */
    from: string | null;
    kinds: { kind: string; edges: number }[];
    degree: DegreeQuantiles | null;
    hubThreshold: number | null;
    hubs: number | null;
    singleLink: number | null;
}

export interface ProfileRelationship extends Assessed {
    otherType: string;
    dataset: string;
    oneToOneShare: number;
    maxPerEntity: number;
    threshold: number;
    entitiesAtOrAboveThreshold: number;
}

/** `GET /inv/entity-types/{id}/profile`: a section absent from `sections=` is absent here. */
export interface EntityTypeProfile {
    entityType: EntityTypeIdentity;
    computedAt: string | null;
    cached: boolean;
    truncated: boolean;
    bindings?: ProfileBinding[];
    volume?: ProfileVolume;
    shapes?: ProfileShapes;
    time?: ProfileTime;
    lineage?: ProfileLineage[];
    links?: ProfileLinks;
    relationships?: ProfileRelationship[];
    /** The Space's masking mode in force and whether THIS type is masked under it (the effective answer). */
    masking?: { mode: string; masked?: boolean };
}

export type EntityTypeProfileSection =
    | 'bindings'
    | 'volume'
    | 'shapes'
    | 'time'
    | 'lineage'
    | 'links'
    | 'relationships';

export interface EntityLinks extends Assessed {
    degree: number | null;
    kinds: { kind: string; count: number }[];
    /** Where the degree sits in its type's distribution (97 = p97). */
    percentile: number | null;
    from: string | null;
}

/** `GET /inv/entities/{type}/{key}/profile`: one Entity. */
export interface EntityProfile extends Assessed {
    entity: { type: string; key: string; masked: boolean };
    firstSeen: string | null;
    lastSeen: string | null;
    activeDays: number | null;
    coveredDays: number | null;
    perDay: { day: string; rows: number }[];
    where: { dataset: string; column: string; rows: number }[];
    links: EntityLinks;
    related: { type: string; count: number; keys: string[] }[];
    dataAsOf: DatasetAsOf[];
    /** Echo of the `from` / `to` the profile was scoped to; absent for the whole history. */
    window?: { from: string | null; to: string | null };
}

/**
 * `POST /inv/entities/{type}/resolve`: a typed or pasted value as the key the Entity page is addressed by - the alias for
 * a masked type, the normalised value otherwise. Raw identifiers never go in a URL (operator, 2026-10-15).
 */
export interface EntityResolution {
    type: string;
    key: string;
    masked: boolean;
}

@Injectable({ providedIn: 'root' })
export class EntityProfileService {
    private http = inject(HttpClient);

    /** The Entity Types in force with their cached headline; never runs a profile query. */
    entityTypes(): Observable<EntityTypeList> {
        return this.http.get<EntityTypeList>(apiUrl('/inv/entity-types'));
    }

    /** One Entity Type's profile; every section when `sections` is absent. */
    entityTypeProfile(id: string, sections?: EntityTypeProfileSection[]): Observable<EntityTypeProfile> {
        return this.http.get<EntityTypeProfile>(apiUrl(`/inv/entity-types/${encodeURIComponent(id)}/profile`), {
            params: toParams({ sections: sections?.length ? sections.join(',') : undefined }),
        });
    }

    /** Resolve a typed value (raw or alias) to the key a URL may carry. The raw value travels in the body only. */
    resolveEntity(type: string, key: string): Observable<EntityResolution> {
        return this.http.post<EntityResolution>(apiUrl(`/inv/entities/${encodeURIComponent(type)}/resolve`), { key });
    }

    /**
     * One Entity's profile; `key` is the alias for a masked type (a raw value is refused 422), else the value. A `window`
     * (a deep link's `from` / `to`) scopes the rows, days and span to it; the answer echoes it.
     */
    entityProfile(type: string, key: string, window?: { from?: string; to?: string }): Observable<EntityProfile> {
        return this.http.get<EntityProfile>(
            apiUrl(`/inv/entities/${encodeURIComponent(type)}/${encodeURIComponent(key)}/profile`),
            { params: toParams({ from: window?.from, to: window?.to }) },
        );
    }
}
