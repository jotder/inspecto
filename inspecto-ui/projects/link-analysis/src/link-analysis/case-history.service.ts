import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { apiErrorMessage } from '@inspecto/core/api';
import { apiUrl } from '@inspecto/core/api/api-base';

/** One Incident or Case that references an Entity (`GET /objects/by-entity`). */
export interface EntityHistoryItem {
    kind: 'incident' | 'case';
    id: string;
    title: string;
    status: string;
    severity?: string;
    createdAt: string;
    matchedOn: string;
    /** A Case matched through an Incident it contains: that Incident's id. */
    via?: string;
}

export type EntityHistoryStatus = 'open' | 'closed' | 'all';

/** The history of one Entity, or why it could not be read (in words an analyst can act on). */
export interface EntityHistory {
    items: EntityHistoryItem[];
    /** The server scanned only its newest candidates: older references may be missing. */
    truncated: boolean;
    error?: string;
}

/**
 * The node detail dialog's Case history read. The envelope's `metadata` is unwrapped away by the v1 interceptor, so
 * the truncation flag is read from the `X-Inspecto-Truncated` header the route sets alongside it.
 */
@Injectable({ providedIn: 'root' })
export class CaseHistoryService {
    private readonly http = inject(HttpClient);

    async byEntity(type: string, id: string, status: EntityHistoryStatus): Promise<EntityHistory> {
        try {
            const r = await firstValueFrom(
                this.http.get<EntityHistoryItem[]>(apiUrl('/objects/by-entity'), {
                    params: { type, id, status },
                    observe: 'response',
                }),
            );
            return { items: r.body ?? [], truncated: r.headers.get('X-Inspecto-Truncated') === 'true' };
        } catch (e) {
            return { items: [], truncated: false, error: historyErrorMessage(e) };
        }
    }
}

export function historyErrorMessage(e: unknown): string {
    if (e instanceof HttpErrorResponse) {
        if (e.status === 422)
            return `This entity cannot be looked up: ${apiErrorMessage(e, 'the id is too short or not of this Entity Type')}.`;
        if (e.status === 429) return 'Too many lookups just now. Wait a moment and open the section again.';
    }
    return `Case history could not be read: ${apiErrorMessage(e, 'the server did not answer')}.`;
}
