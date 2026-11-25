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

/** A range of calendar days (`YYYY-MM-DD`, both ends inclusive) a KPI is read over. */
export interface KpiRange {
    from: string;
    to: string;
}

/** `GET /kpis/{id}/value` — one evaluation, as of a date or over a range. */
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
    /** `?projection=true` only (BI-INSIGHTS-1 I-12): where the period to date lands, or why it cannot be said. */
    projection?: KpiProjection;
}

/** Where a period in progress lands: the landing, its interval, P(target met) and the RAG of the landing. */
export interface KpiProjection {
    status: 'projected' | 'none';
    /** `none` only: why there is no projection (too little history, a Measure that does not add up, a range). */
    reason?: string;
    value?: number;
    toDate?: number | null;
    lower?: number;
    upper?: number;
    /** The interval's level, e.g. 0.8. */
    level?: number;
    /** P(the target is met), 0–1; `null` when the KPI has no target and no bands. */
    probability?: number | null;
    band?: 'GREEN' | 'AMBER' | 'RED' | null;
    tone?: string;
    daysLeft?: number;
    historyDays?: number;
}

/** KPI definitions: authored through `/components/kpi` (ComponentsService), evaluated here. */
@Injectable({ providedIn: 'root' })
export class KpisService {
    private http = inject(HttpClient);

    /**
     * One evaluation. With a `range` (a Dashboard's picked dates, both ends inclusive) the Measure runs over those
     * days and the comparison is the equal-length period before them; `asOf` is then ignored by the server.
     * `projection` asks for {@link KpiProjection} too (a range always answers `none`).
     */
    value(id: string, asOf?: string, range?: KpiRange | null, projection = false): Observable<KpiValue> {
        const when = range ? { from: range.from, to: range.to } : { asOf };
        return this.http.get<KpiValue>(apiUrl(`/kpis/${encodeURIComponent(id)}/value`), {
            params: toParams(projection ? { ...when, projection: 'true' } : when),
        });
    }
}
