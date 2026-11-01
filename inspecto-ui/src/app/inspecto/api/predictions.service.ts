import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';

/** The Predictive model kinds the read route serves (the component kind names). */
export type PredictionKind = 'segmentation-model' | 'churn-model' | 'forecast-model';

/** One run in a segment / churn history: newest first, at most 30. */
export interface PredictionHistoryPoint {
    asOf: string;
    runId: string;
    segment?: string;
    score?: number | null;
    band?: string;
}

/** One future bucket of a forecast; an `insufficient` series is one point with null values. */
export interface ForecastPoint {
    status: 'ok' | 'insufficient';
    step: number | null;
    bucket: string | null;
    forecast: number | null;
    lower: number | null;
    upper: number | null;
    backtestMae?: number | null;
    backtestSmape?: number | null;
    backtestRelMae?: number | null;
    historyBuckets?: number | null;
}

/** `GET /predictions/{kind}/{model}/{entityKey}` — the latest prediction; the kind's own fields ride alongside. */
export interface Prediction {
    kind: PredictionKind;
    model: string;
    entityType: string;
    /** Raw for a caller holding `canRevealLinkEntities`, else the Space `masked:<16 hex>` token. */
    entityKey: string;
    keyMasked: boolean;
    asOf: string;
    modelVersion: string;
    runId: string;
    scoredAt: string;
    /** segmentation-model */
    segment?: string;
    recencyScore?: number;
    frequencyScore?: number;
    monetaryScore?: number;
    /** churn-model: null when insufficient */
    score?: number | null;
    band?: string;
    historyDays?: number;
    history?: PredictionHistoryPoint[];
    /** forecast-model: the latest run, in step order */
    points?: ForecastPoint[];
}

/** Reads stored Predictions (PREDICTIVE-SURFACE-1). Model config lives in the component registry. */
@Injectable({ providedIn: 'root' })
export class PredictionsService {
    private http = inject(HttpClient);

    latest(kind: PredictionKind, model: string, entityKey: string): Observable<Prediction> {
        return this.http.get<Prediction>(
            apiUrl(
                `/predictions/${encodeURIComponent(kind)}/${encodeURIComponent(model)}/${encodeURIComponent(entityKey)}`,
            ),
        );
    }
}
