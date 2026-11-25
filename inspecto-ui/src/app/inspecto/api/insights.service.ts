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

@Injectable({ providedIn: 'root' })
export class InsightsService {
    private http = inject(HttpClient);

    explain(body: ExplainRequest): Observable<ExplainResult> {
        return this.http.post<ExplainResult>(apiUrl('/insights/explain'), body);
    }
}
