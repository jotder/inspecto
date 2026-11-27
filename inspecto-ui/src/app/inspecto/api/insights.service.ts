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
    /** Depth 2 only: the pairs an `insight.scan` stored for a Profile with `pairs: true`, or null with {@link pairsNote}. */
    pairs?: ExplainPairs | null;
    pairsNote?: string;
}

/** Depth 2 (plan §11.12): pairs of dimensions ("product × partner") precomputed by the Profile's scan. */
export interface ExplainPairs {
    profile: string;
    computedAt: string;
    previous: string;
    current: string;
    before: number | null;
    after: number | null;
    /** The stored totals no longer equal today's: the data was restated since the run. */
    stale: boolean;
    drivers: ExplainDriver[];
    refused: { dimension: string; reason: string }[];
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

/** One line of a Dashboard Brief: a stored Insight's templated sentence (`GET /insights/brief`, P4 BR-0). */
export interface BriefLine {
    insightId: string;
    band: 'elevated' | 'high' | string;
    score: number;
    sentence: string;
    type: string;
    measure?: string;
    dimension?: string | null;
    slice?: string | null;
    period?: string;
    link?: string;
}

/** The Brief of one Insight Profile; a Profile the viewer may not view is not in the answer at all. */
export interface BriefProfile {
    profile: string;
    runId?: string;
    runAt?: string;
    period?: string;
    header?: string;
    stale?: boolean;
    /** "Last scan was n days ago", or "No scan has run…". */
    note?: string;
    /** "Nothing unusual in <period>" when no Insight qualifies. */
    summary?: string;
    lines: BriefLine[];
}

/** One narrated sentence: its text carries its `[n]`, `cites` the Insight ids, `source` says who wrote it. */
export interface NarratedSentence {
    n: number;
    text: string;
    cites: string[];
    source: 'model' | 'template' | string;
}

export interface NarratedProfile {
    profile: string;
    runId?: string;
    sentences: NarratedSentence[];
    modelBacked: boolean;
    repaired: boolean;
    /** Why the lines are the Brief's own (e.g. "no model"). */
    reason?: string;
}

/** `POST /insights/brief/narrate` (P4 BR-2): the Brief as the caller may see it, and its narration. */
export interface BriefNarration {
    brief: { profiles: BriefProfile[] };
    narration: { mode: 'model' | 'templated' | string; profiles: NarratedProfile[] };
}

export type InsightBand = 'normal' | 'elevated' | 'high';

/** The feed's filters, all supported by `GET /insights`. */
export interface InsightFeedQuery {
    run?: 'latest' | 'all';
    band?: InsightBand | null;
    limit?: number;
}

export type InsightFeedback = 'useful' | 'not_useful' | 'expected';

@Injectable({ providedIn: 'root' })
export class InsightsService {
    private http = inject(HttpClient);

    explain(body: ExplainRequest): Observable<ExplainResult> {
        return this.http.post<ExplainResult>(apiUrl('/insights/explain'), body);
    }

    /** One Insight Profile's Insights, best score first (404 when no run exists yet or the reader may not view it).
     *  `run`: the latest run (default) or every run kept; `band`: only that band; `limit`: at most this many. */
    list(profile: string, opts: InsightFeedQuery = {}): Observable<InsightFeed> {
        const params: Record<string, string> = { profile };
        if (opts.run === 'all') params['run'] = 'all';
        if (opts.band) params['band'] = opts.band;
        if (opts.limit) params['limit'] = String(opts.limit);
        return this.http.get<InsightFeed>(apiUrl('/insights'), { params });
    }

    /** The templated Dashboard Brief of up to 5 Profiles (404 for a lone Profile that was never scanned). */
    brief(profiles: string[], max?: number): Observable<{ profiles: BriefProfile[] }> {
        const params: Record<string, string> = { profiles: profiles.join(',') };
        if (max) params['max'] = String(max);
        return this.http.get<{ profiles: BriefProfile[] }>(apiUrl('/insights/brief'), { params });
    }

    /** Narrate the Brief (needs AI Assist: 503 without it). The server builds the evidence; the body names only Profiles. */
    narrate(profiles: string[], max?: number): Observable<BriefNarration> {
        return this.http.post<BriefNarration>(apiUrl('/insights/brief/narrate'), max ? { profiles, max } : { profiles });
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
