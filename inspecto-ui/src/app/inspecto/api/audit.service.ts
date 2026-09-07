import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';
import { EventRow } from './events.service';

/**
 * The two event types the audit projection serves. Closed on purpose — it mirrors the backend's
 * `AuditLogRoutes.AUDITABLE`, which refuses anything else with a 400.
 */
export type AuditEventType = 'AUDIT' | 'ACCESS_DENIED';

/** Filter for the audit read. `type` is REQUIRED — the backend refuses a blank or absent one. */
export interface AuditFilter {
    type: AuditEventType;
    pipeline?: string;
    correlationId?: string;
    q?: string;
    from?: string;
    to?: string;
    limit?: number;
    offset?: number;
}

/**
 * The audit trail read (`GET /audit/*`) — CORE, served by every edition.
 *
 * <p>Separate from {@link EventsService} on purpose (EDG-01 cell 6, 2026-09-08). The full `/events*` feed
 * is the optional `inspecto-events` module and 503s on a Personal build, but `EDITIONS.md` §Audit promises
 * Personal "local append-only logs" — so the audit projection stayed in core behind its own narrow routes.
 * The Audit log screen therefore reads THIS service and must NOT gate on `SessionService.eventsEnabled`.
 *
 * ⛔ Do not add a general event search here, and do not widen {@link AuditEventType}: the backend refuses
 * any other type by design, because a permissive `/audit/search` would just be the gated feed under a new
 * name. Browsing the whole feed belongs to {@link EventsService}, which is edition-gated.
 */
@Injectable({ providedIn: 'root' })
export class AuditService {
    private http = inject(HttpClient);

    /** Audit rows of one type, newest-first (`GET /audit/search`). Offset-paged, no cursor. */
    search(filter: AuditFilter): Observable<EventRow[]> {
        return this.http.get<EventRow[]>(apiUrl('/audit/search'), {
            params: toParams(filter as unknown as Record<string, unknown>),
        });
    }

    /**
     * The auditor's evidence CSV (`GET /audit/export?format=csv`) — audit-shaped columns, i.e. the base
     * seven plus one per audit attribute (AUDIT-CSV-1 / compliance G10; the plain projection dropped
     * actor/action/target/ip/policy). Fetched via `HttpClient` so the bearer token is sent.
     */
    exportCsv(filter: AuditFilter): Observable<string> {
        return this.http.get(apiUrl('/audit/export'), {
            params: toParams({ ...filter, format: 'csv' } as unknown as Record<string, unknown>),
            responseType: 'text',
        });
    }
}
