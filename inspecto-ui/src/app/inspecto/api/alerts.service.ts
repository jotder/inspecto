import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import type { ConditionGroup } from '../query/query-types';
import { apiUrl, toParams } from './api-base';

/** One fired alert from the core alert engine (GET /alerts). */
export interface FiredAlert {
    rule: string;
    severity: 'INFO' | 'WARNING' | 'CRITICAL' | string;
    pipeline: string;
    metric: string;
    value: number;
    comparator: string;
    threshold: number;
    window: string;
    epochMillis: number;
    message: string;
}

/** One armed alert rule (GET /alerts/rules). */
export interface AlertRule {
    name: string;
    /** Optional human title (`alert.description`) — titles the rule's fired Alerts and Incidents. */
    description?: string | null;
    /** Ledger-metric rules only — a measure / freshness rule has none (GET omits it). */
    metric?: string;
    comparator: string;
    threshold: number;
    /** Ledger-metric rules only — a measure / freshness rule has none (GET omits it). */
    window?: string;
    severity: string;
    onPipeline?: string;
    /**
     * Row-scoping condition tree (2026-07-18, the same `query-types` shape Decision Rules author):
     * restricts the metric math to ledger rows matching it, applied after the window selects rows
     * and before the metric aggregates them. Ledger-metric rules only (no `dataset`/`measure` rule).
     */
    when?: ConditionGroup | null;
    /** A Dataset-scoped rule's Dataset id (measure or freshness rule). */
    dataset?: string | null;
    /** Measure rule (BI-5): the Measure over `dataset` — `count` or `agg(field)`, e.g. `sum(exposure_sar)`. */
    measure?: string | null;
    /** Per-entity measure rule: the key columns the Measure is evaluated per — each breaching key raises its own Alert. */
    by?: string[] | null;
    /** With `by`: above this many breaching keys one storm Alert replaces the per-key ones (server default 100). */
    stormCap?: number | null;
    /** Freshness rule (DUCKLE-C1): the Dataset must have published within this (`Ns|Nm|Nh|Nd`). */
    maximumAge?: string | null;
    /** Investigation rule (LA-23): the Investigation whose Working Set `relation` the `measure` reads. */
    investigation?: string | null;
    relation?: string | null;
}

/** Create/update body — the whole rule is authorable; `name` is the identity (immutable on edit). */
export type AlertRuleUpsert = AlertRule;

/**
 * The core alert execution engine (v4.1, B5; CONTROL scope). Rules are authored objects persisted
 * as `alert-rule` components (2026-07-18 — the same ComponentStore CRUD contract Expectation/
 * Decision Rule use); evaluation is event-driven off the batch bus, with a manual sweep via
 * evaluate().
 */
@Injectable({ providedIn: 'root' })
export class AlertsService {
    private http = inject(HttpClient);

    recent(limit = 50): Observable<FiredAlert[]> {
        return this.http.get<FiredAlert[]>(apiUrl('/alerts'), { params: toParams({ limit }) });
    }

    rules(): Observable<AlertRule[]> {
        return this.http.get<AlertRule[]>(apiUrl('/alerts/rules'));
    }

    // Rule authoring (audit C3; mirrors /decision-rules). Mock-served today — a live server
    // without the write endpoints answers 503, which the form surfaces as writes-disabled.
    createRule(body: AlertRuleUpsert): Observable<AlertRule> {
        return this.http.post<AlertRule>(apiUrl('/alerts/rules'), body);
    }

    updateRule(name: string, body: AlertRuleUpsert): Observable<AlertRule> {
        return this.http.put<AlertRule>(apiUrl(`/alerts/rules/${encodeURIComponent(name)}`), body);
    }

    removeRule(name: string): Observable<void> {
        return this.http.delete<void>(apiUrl(`/alerts/rules/${encodeURIComponent(name)}`));
    }

    /** Manual evaluation sweep; returns the alerts fired by this pass. */
    evaluate(): Observable<FiredAlert[]> {
        return this.http.post<FiredAlert[]>(apiUrl('/alerts/evaluate'), {});
    }
}
