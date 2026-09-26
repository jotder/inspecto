import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';

/** Where an Action Request is in its life (`ASSURE-ACTION-REQUESTS-1`). `invalid` = failed its integrity check. */
export type ActionRequestStatus =
    | 'draft'
    | 'pending'
    | 'approved'
    | 'dispatched'
    | 'succeeded'
    | 'failed'
    | 'declined'
    | 'expired'
    | 'invalid';

/** The last answer the target gave (or why nothing was sent). */
export interface ActionResponse {
    status: number | null;
    bodyExcerpt: string | null;
    error: string | null;
    attempt: number;
    at: string;
}

/**
 * An **Action Request** — an outbound API call raised from an Incident or Case, held for a second person's
 * approval, then sent to its Connection (`GET /action-requests`). The list view omits the payload.
 */
export interface ActionRequest {
    id: string;
    status: ActionRequestStatus;
    connection: string;
    targetUrl: string;
    method: string;
    idempotencyKey: string;
    incidentId: string | null;
    caseId: string | null;
    origin: string;
    author: string;
    reason: string | null;
    createdAt: string;
    expiresAt: string;
    approver: string | null;
    approvedAt: string | null;
    decidedBy?: string;
    decisionReason?: string | null;
    attempts: number;
    lastResponse: ActionResponse | null;
    history: { status: string; by: string; at: string }[];
}

/** One Action Request with the rendered payload that is (or was) sent. */
/** Where the request goes, as the server parsed it, and whether the Space's egress allowlist names it. */
export interface ActionEgress {
    scheme: string;
    host: string;
    port: number;
    path: string;
    allowlisted: boolean;
}

/** One attempt: the checked address it connected to and what came back. */
export interface ActionAttempt {
    attempt: number;
    address: string | null;
    status: number | null;
    error: string | null;
    at: string;
}

export interface ActionRequestDetail extends ActionRequest {
    payload: Record<string, unknown> | null;
    egress?: ActionEgress;
    attemptLog?: ActionAttempt[];
}

export interface ActionRequestFilter {
    status?: ActionRequestStatus;
    incidentId?: string;
    caseId?: string;
}

/**
 * Action Requests (`ASSURE-ACTION-REQUESTS-1`): list and read them, and approve, decline or retry one. The
 * server enforces everything — `canApproveChanges`, four-eyes (an author deciding their own request is 403) and
 * the record's integrity; a decision carries only a reason, never the target or the payload.
 */
@Injectable({ providedIn: 'root' })
export class ActionRequestsService {
    private http = inject(HttpClient);

    list(filter: ActionRequestFilter = {}): Observable<{ items: ActionRequest[]; total: number; truncated: boolean }> {
        return this.http.get<{ items: ActionRequest[]; total: number; truncated: boolean }>(
            apiUrl('/action-requests'),
            { params: toParams({ ...filter }) },
        );
    }

    get(id: string): Observable<ActionRequestDetail> {
        return this.http.get<ActionRequestDetail>(apiUrl(`/action-requests/${encodeURIComponent(id)}`));
    }

    approve(id: string, reason?: string): Observable<ActionRequestDetail> {
        return this.decide(id, 'approve', reason);
    }

    decline(id: string, reason?: string): Observable<ActionRequestDetail> {
        return this.decide(id, 'decline', reason);
    }

    retry(id: string): Observable<ActionRequestDetail> {
        return this.decide(id, 'retry');
    }

    private decide(
        id: string,
        verb: 'approve' | 'decline' | 'retry',
        reason?: string,
    ): Observable<ActionRequestDetail> {
        return this.http.post<ActionRequestDetail>(
            apiUrl(`/action-requests/${encodeURIComponent(id)}/${verb}`),
            reason ? { reason } : {},
        );
    }
}
