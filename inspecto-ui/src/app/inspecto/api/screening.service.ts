import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';

/** A Screening Hit's review state (SCREENING-1). `confirmed` and `dismissed` are final. */
export type ScreeningHitState = 'open' | 'escalated' | 'confirmed' | 'dismissed';

/** A reviewer's verdict on a hit. */
export type ScreeningDecision = 'confirm' | 'dismiss' | 'escalate';

export interface ScreeningHitStep {
    state: ScreeningHitState;
    by: string;
    at: string;
    reason?: string;
}

/** One match at or above the threshold, raised by a `screening.run` Job. */
export interface ScreeningHit {
    id: string;
    state: ScreeningHitState;
    version: number;
    listId: string;
    purpose: string;
    /** The Entity List entry as the list renders it (a mask token for a masked list). */
    entry: string;
    method: 'name' | 'identifier';
    /** The Match Score, 0..1. */
    score: number;
    threshold: number;
    subjectKey: string;
    subjectName: string | null;
    subjectIdentifier: string | null;
    source: { job?: string; runId?: string; dataset?: string } | null;
    raisedAt: string;
    raisedBy: string;
    decidedBy?: string;
    decidedAt?: string;
    reason?: string;
    history: ScreeningHitStep[];
    /** `invalid` when the stored record failed its integrity check: shown, never decidable. */
    integrity?: 'invalid';
}

/** The Screening Hits of the active Space (`/screening/hits*`, optional module `inspecto-screening`). */
@Injectable({ providedIn: 'root' })
export class ScreeningService {
    private readonly http = inject(HttpClient);

    hits(state?: ScreeningHitState): Observable<{ hits: ScreeningHit[] }> {
        return this.http.get<{ hits: ScreeningHit[] }>(apiUrl('/screening/hits'), { params: toParams({ state }) });
    }

    hit(id: string): Observable<ScreeningHit> {
        return this.http.get<ScreeningHit>(apiUrl(`/screening/hits/${encodeURIComponent(id)}`));
    }

    decide(id: string, decision: ScreeningDecision, reason: string, version: number): Observable<ScreeningHit> {
        return this.http.post<ScreeningHit>(apiUrl(`/screening/hits/${encodeURIComponent(id)}/decide`), {
            decision,
            reason,
            version,
        });
    }
}
