import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';
import type { Signal } from '../signal/signal';

/** `POST /space-comparisons` body — `spaces` names at least two hosted Space ids, no duplicates. */
export interface SpaceComparisonRequest {
    spaces: string[];
    /** Trend window in days (server default 30). */
    window_days?: number;
    /** Report the N storage axes with the widest spread (server default 5). */
    top?: number;
    /** Only these storage axes; every axis when omitted. */
    axes?: string[];
}

/** The `202` answer: the admitted run to poll. */
export interface SpaceComparisonAdmitted {
    runId: string;
    spaces: string[];
    status: string;
}

/** `GET /jobs/runs/{runId}` — one run's status, `RUNNING` until it lands on a terminal status. */
export interface SpaceComparisonRun {
    runId: string;
    job: string;
    type: string;
    trigger: string;
    status: string;
    startedAt: string | null;
    finishedAt: string | null;
    durationMs: number;
    message: string | null;
}

/** One Space's figures on one axis. */
export interface SpaceAxisFigures {
    bytes: number;
    bytesPerDay: number;
}

/** One compared storage axis, widest spread first. */
export interface SpaceComparisonAxis {
    axis: string;
    spreadBytes: number;
    fastest: string | null;
    spaces: Record<string, SpaceAxisFigures>;
}

/** The `space.comparison.completed` Signal payload (`SpaceComparisonJob`). */
export interface SpaceComparisonResult {
    spaces: string[];
    windowDays: number;
    comparable: string[];
    /** Space id → why it could not be compared. */
    notComparable: Record<string, string>;
    axes: SpaceComparisonAxis[];
}

/** The Signal type a comparison run emits in the requesting Space. */
export const SPACE_COMPARISON_SIGNAL = 'space.comparison.completed';

/**
 * The storage-growth comparison across Spaces (`space.comparison` Job Type). The trigger is gated on
 * `canAdminister` server-side; nothing is persisted beyond the Run message and one Signal, so the
 * structured result is read back from the Signal ledger by the run's id (an ad-hoc run's
 * correlation id IS its run id).
 */
@Injectable({ providedIn: 'root' })
export class SpaceComparisonService {
    private http = inject(HttpClient);

    /** Admit one comparison run — `202` + `runId`. 403 / 422 / 404 / 409 per the route's gates. */
    compare(body: SpaceComparisonRequest): Observable<SpaceComparisonAdmitted> {
        return this.http.post<SpaceComparisonAdmitted>(apiUrl('/space-comparisons'), body);
    }

    /** Poll one run; 404 once it is evicted. */
    run(runId: string): Observable<SpaceComparisonRun> {
        return this.http.get<SpaceComparisonRun>(apiUrl(`/jobs/runs/${encodeURIComponent(runId)}`));
    }

    /** The finished run's structured result, or `null` when the ledger holds no Signal for it. */
    result(runId: string): Observable<SpaceComparisonResult | null> {
        return this.http
            .get<Signal[]>(apiUrl('/signals'), {
                params: toParams({ type: SPACE_COMPARISON_SIGNAL, correlationId: runId, limit: 1 }),
            })
            .pipe(
                map((rows) => {
                    const p = Array.isArray(rows) && rows.length ? rows[0].payload : null;
                    return p ? (p as unknown as SpaceComparisonResult) : null;
                }),
            );
    }
}
