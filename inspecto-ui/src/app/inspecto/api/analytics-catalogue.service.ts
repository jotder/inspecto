import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';

/** One parameter of a catalogued Analytics function (GET /analytics/functions). */
export interface AnalyticsParam {
    name: string;
    type: 'integer' | 'decimal' | 'text' | 'choice' | 'boolean' | 'column' | 'columns';
    default: string | null;
    min?: number;
    max?: number;
    choices?: string[];
    description: string;
}

/** One function of the Analytics Kernel: what it is called, what it takes, what it costs, what it returns. */
export interface AnalyticsFunction {
    id: string;
    label: string;
    kernel: string;
    cost: 'constant' | 'linear' | 'linearithmic' | 'quadratic';
    result: string;
    forms: string[];
    /** The pipeline Step id when the function is also a Step; null otherwise. */
    step: string | null;
    summary: string;
    params: AnalyticsParam[];
}

export interface AnalyticsCatalogue {
    total: number;
    kernels: { id: string; label: string; count: number }[];
    functions: AnalyticsFunction[];
}

/** The read-only Analytics function catalogue (ANALYTICS-CORE-1 C8). Functions register server-side. */
@Injectable({ providedIn: 'root' })
export class AnalyticsCatalogueService {
    private http = inject(HttpClient);

    catalogue(): Observable<AnalyticsCatalogue> {
        return this.http.get<AnalyticsCatalogue>(apiUrl('/analytics/functions'));
    }
}
