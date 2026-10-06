import { HttpClient } from '@angular/common/http';
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

@Injectable({ providedIn: 'root' })
export class RiskScoresService {
    private http = inject(HttpClient);

    latest(model: string, entityKey: string): Observable<RiskScore> {
        return this.http.get<RiskScore>(
            apiUrl(`/risk-scores/${encodeURIComponent(model)}/${encodeURIComponent(entityKey)}`),
        );
    }

    /** Preview a SAVED model for one entity (needs only the read capability). */
    preview(model: string, entityKey: string): Observable<RiskScorePreview> {
        return this.http.post<RiskScorePreview>(apiUrl('/risk-scores/preview'), { model, entityKey });
    }
}
