import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { LineDiff } from '../components/line-diff.component';
import { apiUrl, toParams } from './api-base';

/** Where a Pending Change is in its life (`ASSURE-MAKER-CHECKER-1`). */
export type PendingChangeStatus = 'pending' | 'approved' | 'declined' | 'withdrawn' | 'expired' | 'stale';

/**
 * A **Pending Change** — a human config change a Space's Approval Policy held for approval instead of writing
 * (`GET /pending-changes`). The list view omits the content; {@link PendingChangesService.get} adds it.
 */
export interface PendingChange {
    id: string;
    kind: string;
    name: string;
    operation: 'create' | 'update' | 'delete';
    status: PendingChangeStatus;
    author: string;
    reason: string | null;
    createdAt: string;
    expiresAt: string;
    approverCapability: string;
    fourEyes: boolean;
    baseVersion: string;
    proposedVersion: string;
    decidedBy?: string;
    decidedAt?: string;
    decisionReason?: string | null;
}

/** One Pending Change with the content it replaces and the content it proposes. */
export interface PendingChangeDetail extends PendingChange {
    current: Record<string, unknown> | null;
    proposed: Record<string, unknown> | null;
    request: { method: string; path: string };
}

/** `GET /pending-changes/{id}/diff` — the Pipeline history's line diff over the two contents. */
export interface PendingChangeDiff extends LineDiff {
    id: string;
    kind: string;
    name: string;
    operation: string;
}

/** The outcome of a decision: the closed change, and for an approval the route's own answer. */
export interface PendingChangeDecision {
    pendingChange: PendingChange;
    applied: boolean;
    result?: { status: number; body: unknown };
}

/** One kind's rule in the Approval Policy. */
export interface ApprovalRule {
    required: boolean;
    approverCapability: string;
    fourEyes: boolean;
}

/** `GET /settings/approval`. */
export interface ApprovalPolicy {
    approval: Record<string, ApprovalRule>;
    expiresAfterHours: number;
    failedClosed: boolean;
    governable: string[];
    defaultApproverCapability: string;
}

/**
 * The Pending Change inbox (`ASSURE-MAKER-CHECKER-1`): list, read and diff held config changes, approve or
 * decline one, or withdraw your own. Approve applies the change through the route it was proposed through, as the approver.
 * ⚠ Not the agent approvals inbox ({@link ApprovalsService}) — that one governs what the assistant may do.
 */
@Injectable({ providedIn: 'root' })
export class PendingChangesService {
    private http = inject(HttpClient);

    list(status?: PendingChangeStatus): Observable<{ items: PendingChange[]; total: number; truncated: boolean }> {
        return this.http.get<{ items: PendingChange[]; total: number; truncated: boolean }>(
            apiUrl('/pending-changes'),
            { params: toParams({ status }) },
        );
    }

    get(id: string): Observable<PendingChangeDetail> {
        return this.http.get<PendingChangeDetail>(apiUrl(`/pending-changes/${encodeURIComponent(id)}`));
    }

    diff(id: string): Observable<PendingChangeDiff> {
        return this.http.get<PendingChangeDiff>(apiUrl(`/pending-changes/${encodeURIComponent(id)}/diff`));
    }

    approve(id: string, reason?: string): Observable<PendingChangeDecision> {
        return this.http.post<PendingChangeDecision>(
            apiUrl(`/pending-changes/${encodeURIComponent(id)}/approve`),
            reason ? { reason } : {},
        );
    }

    decline(id: string, reason?: string): Observable<PendingChangeDecision> {
        return this.http.post<PendingChangeDecision>(
            apiUrl(`/pending-changes/${encodeURIComponent(id)}/decline`),
            reason ? { reason } : {},
        );
    }

    /** The AUTHOR takes back their own still-pending change (the server refuses anyone else, 403). */
    withdraw(id: string, reason?: string): Observable<PendingChangeDecision> {
        return this.http.post<PendingChangeDecision>(
            apiUrl(`/pending-changes/${encodeURIComponent(id)}/withdraw`),
            reason ? { reason } : {},
        );
    }

    policy(): Observable<ApprovalPolicy> {
        return this.http.get<ApprovalPolicy>(apiUrl('/settings/approval'));
    }
}
