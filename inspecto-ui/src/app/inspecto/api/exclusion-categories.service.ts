import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { apiUrl } from './api-base';
import { ShortReads } from './short-reads';

/** Who owns an exclusion list: a category list is kept by an administrator, an analyst list by analysts. */
export type ExclusionOwnerKind = 'admin' | 'analyst';
/** A `restricted` list shows its name and size, never its members, to a reader without the category or reveal grant. */
export type ExclusionVisibility = 'open' | 'restricted';

/** One row of `GET /inv/exclusion-lists` (LA-GROW-EXCLUDE-1 phase 4): a live Entity List of purpose `exclusion`. */
export interface ExclusionListChip {
    id: string;
    title: string;
    entityType: string;
    ownerKind: ExclusionOwnerKind;
    /** Applied as the first step of every new Investigation in the Space (category lists only). */
    autoApply: boolean;
    visibility: ExclusionVisibility;
    size: number;
    /** Why this auto-apply list would refuse every new Investigation (Entity Type out of force, too many members); absent when it seals. */
    problem?: string;
}

/** `POST /entity-lists` for a category list: purpose `exclusion`, `ownerKind: admin`. */
export interface CategoryListCreate {
    title: string;
    entityType: string;
    autoApply: boolean;
    visibility: ExclusionVisibility;
    reason: string;
}

/** `PUT /entity-lists/{id}/attributes`: at least one attribute and a reason. */
export interface ExclusionAttributesChange {
    ownerKind?: ExclusionOwnerKind;
    autoApply?: boolean;
    visibility?: ExclusionVisibility;
    reason: string;
}

/**
 * Category exclusion lists (LA-GROW-EXCLUDE-1 phase 4) for the Settings ▸ Link Analysis section. Reading needs Space
 * access only; creating a category list and changing any list's attributes need `canManageExclusionCategories` (403).
 */
@Injectable({ providedIn: 'root' })
export class ExclusionCategoriesService {
    private http = inject(HttpClient);
    private reads = inject(ShortReads);

    /** The live exclusion lists, category lists first. Never members. */
    list(): Observable<{ lists: ExclusionListChip[] }> {
        return this.http.get<{ lists: ExclusionListChip[] }>(apiUrl('/inv/exclusion-lists'));
    }

    /** Create a category list (`ownerKind: admin`); members are added on the Entity Lists page. */
    create(req: CategoryListCreate): Observable<unknown> {
        return this.http
            .post(apiUrl('/entity-lists'), { ...req, purpose: 'exclusion', ownerKind: 'admin' })
            .pipe(tap(() => this.reads.clear()));
    }

    /** Change a list's ownership, auto-apply or visibility. */
    setAttributes(id: string, change: ExclusionAttributesChange): Observable<unknown> {
        return this.http
            .put(apiUrl(`/entity-lists/${encodeURIComponent(id)}/attributes`), change)
            .pipe(tap(() => this.reads.clear()));
    }
}
