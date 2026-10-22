import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { catchError, map, Observable, of } from 'rxjs';
import { apiUrl } from '../api/api-base';

/** The steps a beginner can leave unfinished (the server's closed vocabulary, `OnboardingCounters.STEPS`). */
export type AbandonedStep =
    | 'space'
    | 'data'
    | 'columns'
    | 'graph'
    | 'list'
    | 'add_data:file'
    | 'add_data:columns'
    | 'add_data:create';

/** `GET /onboarding/metrics` (LA-ONBOARD-1 OB-13). Times are ISO instants; `*Seconds` count from the first launch. */
export interface OnboardingMetrics {
    recording: boolean;
    firstLaunch: string | null;
    launches: number;
    spacesCreated: number;
    firstSpace: string | null;
    firstSpaceSeconds: number | null;
    firstDataset: string | null;
    firstDatasetSeconds: number | null;
    firstGraph: string | null;
    firstGraphSeconds: number | null;
    guideCompleted: string | null;
    guideCompletedSeconds: number | null;
    abandoned: Record<string, number>;
}

/**
 * The browser's half of the onboarding counters (LA-ONBOARD-1 OB-13, A-10): always on, local to this installation, never
 * sent anywhere else. The server counts a launch, a Space created, the first Dataset and the first graph where they happen;
 * the browser reports only what it alone sees — the Space guide completed, a step left unfinished. A report is
 * fire-and-forget: a failure (an older server, the rate limit) is silent, never an error on screen.
 */
@Injectable({ providedIn: 'root' })
export class OnboardingEventsService {
    private readonly http = inject(HttpClient);
    private guideReported = false;

    /** The Space guide is done: reported once per load of the app (the server keeps the first one per installation). */
    guideCompleted(): void {
        if (this.guideReported) return;
        this.guideReported = true;
        this.send({ event: 'guide_completed' });
    }

    /** The person left `step` unfinished. */
    stepAbandoned(step: AbandonedStep): void {
        this.send({ event: 'step_abandoned', step });
    }

    /** What the counters say; null when the server cannot answer (an older server, or not allowed). */
    metrics(): Observable<OnboardingMetrics | null> {
        return this.http.get<OnboardingMetrics>(apiUrl('/onboarding/metrics')).pipe(
            map((m) => m ?? null),
            catchError(() => of(null)),
        );
    }

    private send(body: Record<string, string>): void {
        this.http
            .post(apiUrl('/onboarding/events'), body)
            .pipe(catchError(() => of(null)))
            .subscribe();
    }
}
