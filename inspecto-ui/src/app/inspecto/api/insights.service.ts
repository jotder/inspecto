import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import type { ExplainRequest } from 'app/inspecto/viz/explain-request';
import { apiUrl } from './api-base';

/** One value of a dimension: the Measure before and after, its contribution and share of the change, its surprise. */
export interface ExplainElement {
    value: string | null;
    before: number;
    after: number;
    contribution: number;
    share: number | null;
    surprise: number | null;
}

export interface ExplainDriver {
    dimension: string;
    explains: boolean;
    covered: number | null;
    surprise: number | null;
    cover: (string | null)[];
    elements: ExplainElement[];
    valueCount: number;
}

export interface ExplainMixRate {
    dimension: string;
    before: number;
    after: number;
    mix: number;
    rate: number;
}

export interface ExplainMoved {
    measure: string;
    before: number | null;
    after: number | null;
    pairs: number;
    r: number | null;
    movedWith: boolean;
    reason?: string;
}

/** `POST /insights/explain` (BI-INSIGHTS-1 P2, the Business Insights add-on). */
export interface ExplainResult {
    measure: string;
    kind: 'additive' | 'ratio';
    grain: string;
    previous: string;
    current: string;
    before: number;
    after: number;
    delta: number;
    pct: number | null;
    cover: number;
    drivers: ExplainDriver[];
    mixRate?: ExplainMixRate[];
    refused: { dimension: string; reason: string }[];
    movedWith: ExplainMoved[];
    movedWithNote: string;
}

/** One stored Insight (BI-INSIGHTS-1 P3, a row of `insights_<profile>`), as `GET /insights` serves it. */
export interface Insight {
    insight_id: string;
    profile_id?: string;
    run_id?: string;
    run_at?: string;
    insight_type: string;
    measure?: string;
    /** `'*'` = the whole Measure. */
    dimension: string;
    slice_value: string | null;
    period: string;
    value?: number | null;
    expected?: number | null;
    delta?: number | null;
    impact?: number | null;
    p?: number | null;
    q?: number | null;
    score: number;
    band: 'normal' | 'elevated' | 'high' | string;
    explanation?: { sentence?: string; [k: string]: unknown } | null;
    status?: 'new' | 'useful' | 'not_useful' | 'expected' | string;
    first_seen?: string;
    /** Set on a replay preview: the bucket the scan ran as of. */
    asOf?: string;
}

/** `GET /insights?profile=` — the Insights of the latest run, best score first. */
export interface InsightFeed {
    profile: string;
    runs: number;
    latestRun: string | null;
    total: number;
    insights: Insight[];
}

/** `POST /insights/preview` — a replay of an unsaved Insight Profile; nothing is saved. */
export interface InsightPreview {
    periods: number;
    perPeriod: { period: string; count: number }[];
    total: number;
    perPeriodAverage: number;
    insights: Insight[];
    skipped?: { dimension: string; reason: string }[];
}

export type InsightFeedback = 'useful' | 'not_useful' | 'expected';

@Injectable({ providedIn: 'root' })
export class InsightsService {
    private http = inject(HttpClient);

    explain(body: ExplainRequest): Observable<ExplainResult> {
        return this.http.post<ExplainResult>(apiUrl('/insights/explain'), body);
    }

    /** The latest run's Insights of one Insight Profile (404 when no run exists yet or the reader may not view it). */
    list(profile: string): Observable<InsightFeed> {
        return this.http.get<InsightFeed>(apiUrl('/insights'), { params: { profile } });
    }

    /** Replay UNSAVED Profile content over the last `periods` buckets (4-52). */
    preview(profile: Record<string, unknown>, periods: number): Observable<InsightPreview> {
        return this.http.post<InsightPreview>(apiUrl('/insights/preview'), { profile, periods });
    }

    /** Record a reader's word on an Insight; the next `insight.scan` run applies it (needs canAuthorWorkbench). */
    feedback(profile: string, insightId: string, status: InsightFeedback): Observable<unknown> {
        return this.http.post(
            apiUrl(`/insights/${encodeURIComponent(profile)}/${encodeURIComponent(insightId)}/feedback`),
            { status },
        );
    }
}
