import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';

/**
 * One edge of the label graph — "this tag is applied to `(targetKind, targetId)`".
 *
 * Edge identity is the triple `(tag, targetKind, targetId)`, so `assign` is idempotent and returns the
 * edge already stored (original `actor`/`createdAt` — re-tagging must not rewrite provenance).
 */
export interface TagAssignment {
    tag: string;
    targetKind: string;
    targetId: string;
    actor?: string;
    createdAt: number;
}

/** Outcome of a vocabulary-wide change — how many edges/objects/rules moved. */
export interface TagVocabularyChange {
    assignments: number;
    objects: number;
    rules: number;
}

/**
 * Cross-entity tags (`/tags/{name}/targets`, `/tags/assignments/…`, BACKLOG D7) — the Gmail-label
 * metaphor generalized off `OperationalObject` onto any annotation target. Targets are addressed with
 * the same `(targetKind, targetId)` vocabulary as D10 notes ({@link NotesService}); the two features
 * deliberately share it so the schemes cannot drift.
 *
 * The **registry** (`GET/POST /tags`) and **Tag Rules** still live on `ObjectsService` where their only
 * callers are; this service is the cross-entity surface those predate. See
 * `okf/backend/control-plane/tags.md`.
 *
 * Two behaviors worth knowing before wiring UI to this:
 * - **Applying an unregistered tag is a 404**, not an implicit create — create it via
 *   `ObjectsService.createTag` first. Silently minting a tag on a typo is how a vocabulary rots.
 * - Results are **filtered to what the caller may see**, so two users can legitimately get different
 *   counts for one tag, and an invisible target answers 404 rather than 403.
 */
@Injectable({ providedIn: 'root' })
export class TagsService {
    private http = inject(HttpClient);

    /** Everything carrying this tag, across kinds — the point of D7. */
    targets(tag: string): Observable<TagAssignment[]> {
        return this.http.get<TagAssignment[]>(apiUrl(`/tags/${encodeURIComponent(tag)}/targets`));
    }

    /** The tags on one thing, alphabetical. */
    assignments(
        targetKind: string,
        targetId: string,
    ): Observable<{ targetKind: string; targetId: string; tags: string[] }> {
        return this.http.get<{ targetKind: string; targetId: string; tags: string[] }>(
            apiUrl(`/tags/assignments/${encodeURIComponent(targetKind)}/${encodeURIComponent(targetId)}`),
        );
    }

    /** Apply a registered tag to a target. Idempotent — safe to fire optimistically and to retry. */
    assign(targetKind: string, targetId: string, tag: string, actor?: string): Observable<TagAssignment> {
        return this.http.post<TagAssignment>(
            apiUrl(`/tags/assignments/${encodeURIComponent(targetKind)}/${encodeURIComponent(targetId)}`),
            { tag, actor },
        );
    }

    /** Remove one label from one target. Idempotent — already-absent is success, not an error. */
    unassign(targetKind: string, targetId: string, tag: string): Observable<{ tag: string; removed: boolean }> {
        return this.http.delete<{ tag: string; removed: boolean }>(
            apiUrl(
                `/tags/assignments/${encodeURIComponent(targetKind)}/${encodeURIComponent(targetId)}/${encodeURIComponent(tag)}`,
            ),
        );
    }

    /**
     * Rename a tag everywhere — registry entry, assignment edges, every affected object's CSV projection
     * and any Tag Rule applying it, all in one move. Renaming **onto an existing tag merges them**
     * (deliberate: two edges collapsing into one is correct under the composite key), and the source tag
     * then stops existing. Requires `canAuthorWorkbench`.
     */
    rename(from: string, to: string): Observable<TagVocabularyChange & { renamed: string; to: string }> {
        return this.http.post<TagVocabularyChange & { renamed: string; to: string }>(
            apiUrl(`/tags/${encodeURIComponent(from)}/rename`),
            { to },
        );
    }

    /**
     * Retire a tag and every assignment of it. **409 while a Tag Rule still applies it** — the rule would
     * silently resurrect the tag on the next matching object, so repoint or delete the rule first.
     * Requires `canAuthorWorkbench`.
     */
    remove(name: string): Observable<TagVocabularyChange & { deleted: string }> {
        return this.http.delete<TagVocabularyChange & { deleted: string }>(apiUrl(`/tags/${encodeURIComponent(name)}`));
    }
}
