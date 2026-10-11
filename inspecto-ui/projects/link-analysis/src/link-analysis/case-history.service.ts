import { HttpClient, HttpContext, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { V1_FULL_ENVELOPE, apiErrorMessage, isV1Envelope } from '@inspecto/core/api';
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
    /** The server's own truncation message (`metadata.pagination.message`), when it sent one. */
    truncatedMessage?: string;
    /** How many references the server counted in all (`metadata.pagination.total`), when it sent it. */
    total?: number;
    error?: string;
}

/**
 * The node detail dialog's Case history read. It opts into the whole v1 envelope ({@link V1_FULL_ENVELOPE}) so the
 * server's `metadata.pagination` (`truncated` / `message` / `total`) reaches the dialog; the `X-Inspecto-Truncated`
 * header stays the fallback for a reply that carries no pagination (or no envelope at all).
 */
@Injectable({ providedIn: 'root' })
export class CaseHistoryService {
    private readonly http = inject(HttpClient);

    async byEntity(type: string, id: string, status: EntityHistoryStatus): Promise<EntityHistory> {
        try {
            const r = await firstValueFrom(
                this.http.get<unknown>(apiUrl('/objects/by-entity'), {
                    params: { type, id, status },
                    observe: 'response',
                    context: new HttpContext().set(V1_FULL_ENVELOPE, true),
                }),
            );
            const env = isV1Envelope(r.body) ? r.body : null;
            const items = ((env ? env.data : r.body) as EntityHistoryItem[] | null) ?? [];
            const page = env?.metadata.pagination;
            const out: EntityHistory = {
                items,
                truncated: page?.truncated ?? r.headers.get('X-Inspecto-Truncated') === 'true',
            };
            if (page?.message) out.truncatedMessage = page.message;
            if (typeof page?.total === 'number') out.total = page.total;
            return out;
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
