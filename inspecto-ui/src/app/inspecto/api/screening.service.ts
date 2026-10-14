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
    /** Set by a confirm: the Case (and its Incident) the confirmed match opened. */
    caseId?: string;
    incidentId?: string;
    history: ScreeningHitStep[];
    /** `invalid` when the stored record failed its integrity check: shown, never decidable. */
    integrity?: 'invalid';
}

/** The request of `POST /screening/check`: 1..1000 subjects, 1..20 Entity List ids, optional limits. */
export interface ScreeningCheckRequest {
    subjects: { key?: string; name?: string; identifier?: string }[];
    lists: string[];
    /** 0.5..1 (server default 0.85). */
    threshold?: number;
    /** 1..20 (server default 5). */
    maxMatches?: number;
}

/** One scored match of a check; the Entity List entry is a mask token for a masked list. */
export interface ScreeningCheckMatch {
    listId: string;
    purpose: string;
    entry: string;
    method: 'name' | 'identifier';
    score: number;
}

export interface ScreeningCheckResponse {
    threshold: number;
    results: { key: string; name: string | null; identifier: string | null; matches: ScreeningCheckMatch[] }[];
}

/** The server's bounds for a check (`ScreeningRoutes` / `Screener`). */
export const SCREENING_CHECK_LIMITS = {
    maxSubjects: 1000,
    maxLists: 20,
    maxValueLength: 512,
    minThreshold: 0.5,
    defaultThreshold: 0.85,
    maxMatchesCeiling: 20,
    defaultMaxMatches: 5,
} as const;

/** An Entity List as `GET /entity-lists` summarises it (the fields the check picker needs). */
export interface ScreeningListOption {
    id: string;
    title: string;
    purpose: string;
    retired: boolean;
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

    /** Scores subjects against Entity Lists. Read-only: persists nothing and raises no Screening Hit. */
    check(req: ScreeningCheckRequest): Observable<ScreeningCheckResponse> {
        return this.http.post<ScreeningCheckResponse>(apiUrl('/screening/check'), req);
    }

    /** The Entity Lists a check can screen against (`GET /entity-lists`, optional module `inspecto-entity-list`). */
    entityLists(): Observable<{ lists: ScreeningListOption[] }> {
        return this.http.get<{ lists: ScreeningListOption[] }>(apiUrl('/entity-lists'));
    }
}
