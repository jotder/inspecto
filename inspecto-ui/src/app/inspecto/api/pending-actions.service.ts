import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';

/** Where a Pending Action is in its life (AGENT-KIT-1 AK-2d). */
export type PendingActionStatus =
    | 'pending'
    | 'approved'
    | 'declined'
    | 'withdrawn'
    | 'expired'
    | 'stale'
    | 'failed'
    | 'invalid';

/**
 * A **Pending Action** — an operational action (a run's trigger, pause, resume, reprocess, drain…) an unattended
 * machine agent asked for, held for a person to approve (`GET /pending-actions`). ⛔ Not a Pending Change: that
 * holds config.
 */
export interface PendingAction {
    id: string;
    /** `METHOD path`, e.g. `POST /runs/orders/resume`. */
    action: string;
    status: PendingActionStatus;
    /** The machine that asked, `client:<id>`. */
    author: string;
    agentClient?: string | null;
    mcpTool?: string | null;
    reason: string | null;
    createdAt: string;
    expiresAt: string;
    approverCapability: string;
    fourEyes: boolean;
    decidedBy?: string;
    decidedAt?: string;
    decisionReason?: string | null;
    /** The route's status when an approved action ran. */
    appliedStatus?: number;
}

/** The outcome of a decision: the closed action, and for an approval the route's own answer. */
export interface PendingActionDecision {
    pendingAction: PendingAction;
    ran: boolean;
    result?: { status: number; body: unknown };
}

/**
 * The Pending Action inbox: list and read a machine agent's held actions, approve (it then runs as that machine) or
 * decline one. Decided by the change approvers (`canApproveChanges`, four-eyes), on the Pending Changes screen.
 */
@Injectable({ providedIn: 'root' })
export class PendingActionsService {
    private http = inject(HttpClient);

    list(status?: PendingActionStatus): Observable<{ items: PendingAction[]; total: number; truncated: boolean }> {
        return this.http.get<{ items: PendingAction[]; total: number; truncated: boolean }>(
            apiUrl('/pending-actions'),
            { params: toParams({ status }) },
        );
    }

    approve(id: string, reason?: string): Observable<PendingActionDecision> {
        return this.http.post<PendingActionDecision>(
            apiUrl(`/pending-actions/${encodeURIComponent(id)}/approve`),
            reason ? { reason } : {},
        );
    }

    decline(id: string, reason?: string): Observable<PendingActionDecision> {
        return this.http.post<PendingActionDecision>(
            apiUrl(`/pending-actions/${encodeURIComponent(id)}/decline`),
            reason ? { reason } : {},
        );
    }
}
