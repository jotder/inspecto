import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';

/** An Anomaly Score's band: `high` ≥ highThreshold, `elevated` ≥ elevatedThreshold, else `normal`. */
export type AnomalyBand = 'high' | 'elevated' | 'normal';

/** One feature's baseline (self or peer) — the numbers the `reason` line is generated from. */
export interface AnomalyBaseline {
    kind?: string;
    basis?: string;
    median?: number | null;
    mad?: number | null;
    points?: number | null;
    season?: string | null;
    cohort?: string | null;
    size?: number | null;
    fellBack?: boolean;
    insufficient?: boolean;
}

/** One element of a score's explanation record (design §5); the panel reads only these fields. */
export interface AnomalyFeature {
    feature: string;
    label?: string;
    observed?: number | null;
    baseline?: AnomalyBaseline | null;
    peerBaseline?: AnomalyBaseline | null;
    direction?: string;
    contribution: number;
    insufficient?: boolean;
    reason?: string;
}

/** One run in the sparkline: newest first, at most 30 runs. */
export interface AnomalyHistoryPoint {
    periodStart: string;
    score: number;
    band: AnomalyBand;
    runId: string;
}

/** `GET /anomaly-scores/{model}/{entityKey}` — the latest score with its explanation, plus the run history. */
export interface AnomalyScore {
    model: string;
    entityType: string;
    /** Raw for a caller holding `canRevealLinkEntities`, else the Space `masked:<16 hex>` token. */
    entityKey: string;
    keyMasked: boolean;
    score: number;
    band: AnomalyBand;
    raw?: number;
    elevatedThreshold: number;
    highThreshold: number;
    periodStart: string;
    insufficientCount?: number;
    modelVersion: string;
    runId: string;
    scoredAt: string;
    features: AnomalyFeature[];
    history: AnomalyHistoryPoint[];
}

/** Reads stored Anomaly Scores (ANOMALY-DETECTION-1 S5). Model config + preview live in `anomaly-models.service`. */
@Injectable({ providedIn: 'root' })
export class AnomalyScoresService {
    private http = inject(HttpClient);

    latest(model: string, entityKey: string): Observable<AnomalyScore> {
        return this.http.get<AnomalyScore>(
            apiUrl(`/anomaly-scores/${encodeURIComponent(model)}/${encodeURIComponent(entityKey)}`),
        );
    }
}
