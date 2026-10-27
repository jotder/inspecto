import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';

/** One factor of a Risk Score: `contribution = weight × value`, held to `cap` (ASSURE-RISK-SCORE-1). */
export interface RiskFactor {
    indicator: string;
    label?: string;
    value: number | null;
    missing: boolean;
    weight: number;
    cap?: number;
    capped: boolean;
    contribution: number;
    evidence: Record<string, unknown>[];
}

/** `GET /risk-scores/{model}/{entityKey}` — an entity's latest Risk Score with its factors. */
export interface RiskScore {
    model: string;
    entityType: string;
    entityKey: string;
    score: number;
    high: boolean;
    highThreshold: number;
    modelVersion: string;
    runId: string;
    scoredAt: string;
    factors: RiskFactor[];
}

/** `POST /risk-scores/preview` (S3) — one entity scored now, nothing written; the key may come back masked. */
export interface RiskScorePreview {
    model: string;
    entityType: string;
    entityKey: string;
    keyMasked: boolean;
    found: boolean;
    score: number;
    high: boolean;
    highThreshold: number;
    saved: boolean;
    factors: RiskFactor[];
}

/** One row of `GET /risk-score-lists/{model}`: an entity's latest score and its segment values (the key may be masked). */
export interface RiskScoreListRow {
    entityKey: string;
    keyMasked?: boolean;
    score: number;
    high: boolean;
    scoredAt: string;
    segments: Record<string, string | null>;
}

/** `GET /risk-score-lists/{model}` — the latest run's scores, highest first, filtered by the model's segments. */
export interface RiskScoreList {
    model: string;
    entityType: string;
    highThreshold: number;
    /** The model's declared segments (evidence columns) with the values found in the latest run. */
    segments: { column: string; values: string[] }[];
    limit: number;
    /** The list was cut at `limit` (the Space's list limit). */
    truncated: boolean;
    rows: RiskScoreListRow[];
}

@Injectable({ providedIn: 'root' })
export class RiskScoresService {
    private http = inject(HttpClient);

    latest(model: string, entityKey: string): Observable<RiskScore> {
        return this.http.get<RiskScore>(
            apiUrl(`/risk-scores/${encodeURIComponent(model)}/${encodeURIComponent(entityKey)}`),
        );
    }

    /** The latest scores of a model; `segments` maps a declared segment column to the value to keep (blank = all). */
    list(model: string, segments: Record<string, string> = {}): Observable<RiskScoreList> {
        let params = new HttpParams();
        for (const [column, value] of Object.entries(segments)) if (value) params = params.set(column, value);
        return this.http.get<RiskScoreList>(apiUrl(`/risk-score-lists/${encodeURIComponent(model)}`), { params });
    }

    /** Preview a SAVED model for one entity (needs only the read capability). */
    preview(model: string, entityKey: string): Observable<RiskScorePreview> {
        return this.http.post<RiskScorePreview>(apiUrl('/risk-scores/preview'), { model, entityKey });
    }
}
