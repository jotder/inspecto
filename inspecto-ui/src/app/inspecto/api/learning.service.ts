import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { map, Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';

/** The stored verdict on a Triage Run (GET /agent/feedback) — the learning corpus. */
export interface TriageRunFeedback {
    id: string;
    triageRunId: string;
    rating: 'HELPFUL' | 'NOT_HELPFUL';
    note: string | null;
    submittedBy: string | null;
    at: string;
}

/** What an operator submits (POST /agent/triage-runs/{id}/feedback). */
export type FeedbackRating = 'helpful' | 'not_helpful';

/**
 * The AGT-5 P5 learning surface: read the Triage Run feedback corpus that drives tuning, submit a rating
 * on a Triage Run, and recall prior Triage Runs similar to one. Reads degrade to empty when the
 * intelligence module is absent (the dashboard handles it); the corpus accrues durably server-side.
 */
@Injectable({ providedIn: 'root' })
export class LearningService {
    private http = inject(HttpClient);

    /** Recent Triage Run feedback, newest-first, capped at `limit`. */
    feedback(limit = 200): Observable<TriageRunFeedback[]> {
        return this.http
            .get<{ feedback: TriageRunFeedback[] }>(apiUrl('/agent/feedback'), { params: toParams({ limit }) })
            .pipe(map((r) => r.feedback ?? []));
    }

    /** Rate a Triage Run helpful / not-helpful (+ optional note). 404 when the id is unknown. */
    rateTriageRun(triageRunId: string, rating: FeedbackRating, note?: string): Observable<TriageRunFeedback> {
        return this.http.post<TriageRunFeedback>(
            apiUrl(`/agent/triage-runs/${encodeURIComponent(triageRunId)}/feedback`),
            { rating, note },
        );
    }

    /** Prior Triage Runs similar to `triageRunId` (recall), each with a `similarity` score. */
    similarTriageRuns(triageRunId: string, k = 5): Observable<Record<string, unknown>[]> {
        return this.http
            .get<{
                similar: Record<string, unknown>[];
            }>(apiUrl(`/agent/triage-runs/${encodeURIComponent(triageRunId)}/similar`), { params: toParams({ k }) })
            .pipe(map((r) => r.similar ?? []));
    }
}
