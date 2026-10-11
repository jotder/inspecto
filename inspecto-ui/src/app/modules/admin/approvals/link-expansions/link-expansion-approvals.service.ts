import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from 'app/inspecto/api';

/** The inbox filter `GET /inv/pending` accepts. */
export type LinkExpansionStatusFilter = 'pending' | 'approved' | 'denied' | 'all';

/** One held four-eyes request (D-U7), as the approver inbox lists it; entity ids are already masked per the Space. */
export interface LinkExpansionRequest {
    investigationId: string;
    title: string | null;
    dataset: string;
    purpose: string | null;
    requestId: string;
    /** `expand` (a sensitive expand) or `promote` (a Draft carrying one). */
    kind: string;
    status: 'pending' | 'approved' | 'denied' | string;
    requestedBy: string | null;
    requestedAt: string;
    params: Record<string, unknown>;
    sensitivity: { exceeded?: string[] } & Record<string, unknown>;
    /** What will be read, in plain language. */
    summary: string;
    draftId?: string;
    decidedBy?: string;
    decidedAt?: string;
    reason?: string;
}

export interface LinkExpansionInbox {
    items: LinkExpansionRequest[];
    total: number;
    limit: number;
    truncated: boolean;
    status: LinkExpansionStatusFilter;
}

/**
 * The approver's side of Link Analysis four-eyes. `GET /inv/pending` lists only requests the caller may decide (never
 * their own); approve / deny are the per-Investigation decision routes. All three are `canApproveLinkExpansions`; the
 * module is optional, so a 503 means "Link Analysis is not installed / the control plane is read-only".
 */
@Injectable({ providedIn: 'root' })
export class LinkExpansionApprovalsService {
    private http = inject(HttpClient);

    inbox(status: LinkExpansionStatusFilter = 'pending', limit = 100): Observable<LinkExpansionInbox> {
        return this.http.get<LinkExpansionInbox>(apiUrl('/inv/pending'), { params: toParams({ status, limit }) });
    }

    approve(r: LinkExpansionRequest): Observable<unknown> {
        return this.http.post(this.path(r, 'approve'), {});
    }

    deny(r: LinkExpansionRequest, reason?: string): Observable<unknown> {
        return this.http.post(this.path(r, 'deny'), reason ? { reason } : {});
    }

    private path(r: LinkExpansionRequest, verb: 'approve' | 'deny'): string {
        return apiUrl(
            `/inv/investigations/${encodeURIComponent(r.investigationId)}/pending/${encodeURIComponent(r.requestId)}/${verb}`,
        );
    }
}
