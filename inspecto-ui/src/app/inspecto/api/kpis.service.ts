import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';

/** A KPI definition's content — the `kpi` registry component (ASSURE-KPI-DEFINITIONS-1, WS-20). */
export interface KpiDefinition {
    title?: string;
    description?: string;
    dataset: string;
    /** `count` | `agg(field)` — the Measure shorthand. */
    measure: string;
    /** The column a period is cut on. */
    timeField: string;
    grain: 'day' | 'week' | 'month' | 'quarter' | 'year';
    /** IANA zone the periods are cut in (`Asia/Kolkata`); absent ⇒ UTC. */
    timezone?: string;
    comparison?: 'previous' | 'last-year' | 'none';
    direction?: 'up' | 'down' | 'band';
    target?: number;
    /** up/down: `{green, amber}` thresholds; band: `{green: [lo, hi], amber: [lo, hi]}`. */
    bands?: { green: number | [number, number]; amber: number | [number, number] };
    unit?: string;
    format?: Record<string, unknown>;
}

/** `GET /kpis/{id}/value` — one evaluation, as of a date. */
export interface KpiValue {
    kpi: string;
    title?: string | null;
    grain: string;
    comparison: string;
    direction: 'up' | 'down' | 'band';
    asOf: string;
    /** The zone the periods were cut in. */
    timezone: string;
    period: { from: string; to: string };
    value: number | null;
    comparisonPeriod: { from: string; to: string } | null;
    comparisonValue: number | null;
    delta: number | null;
    deltaPct: number | null;
    target: number | null;
    /** RAG: `GREEN` | `AMBER` | `RED` — `statusTone` maps it; `null` = no status. */
    band: 'GREEN' | 'AMBER' | 'RED' | null;
    tone: string;
    unit?: string | null;
    format?: Record<string, unknown> | null;
    owner?: string | null;
}

/** KPI definitions: authored through `/components/kpi` (ComponentsService), evaluated here. */
@Injectable({ providedIn: 'root' })
export class KpisService {
    private http = inject(HttpClient);

    value(id: string, asOf?: string): Observable<KpiValue> {
        return this.http.get<KpiValue>(apiUrl(`/kpis/${encodeURIComponent(id)}/value`), {
            params: toParams({ asOf }),
        });
    }
}
