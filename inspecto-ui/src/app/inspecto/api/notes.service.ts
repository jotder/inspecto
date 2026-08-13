import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';
import { ObjectNote } from './objects.service';

/**
 * Kind-addressed notes (`/notes/{targetKind}/{targetId}/…`, BACKLOG D10) — the same {@link ObjectNote}
 * shape as `/objects/{id}/comments`, generalized to any target the backend's `AnnotationKinds` vocabulary
 * accepts (`"object"` + `ComponentStore.WRITABLE_TYPES`, e.g. `link-analysis-view`). The driving case is
 * a per-view comment thread on a saved Link Analysis view — see `okf/frontend/features/link-analysis.md`.
 */
@Injectable({ providedIn: 'root' })
export class NotesService {
    private http = inject(HttpClient);

    comments(targetKind: string, targetId: string): Observable<ObjectNote[]> {
        return this.http.get<ObjectNote[]>(
            apiUrl(`/notes/${encodeURIComponent(targetKind)}/${encodeURIComponent(targetId)}/comments`),
        );
    }

    addComment(targetKind: string, targetId: string, body: string, author?: string): Observable<ObjectNote> {
        return this.http.post<ObjectNote>(
            apiUrl(`/notes/${encodeURIComponent(targetKind)}/${encodeURIComponent(targetId)}/comments`),
            { body, author },
        );
    }
}
