import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ReconBreakSets, ReconRunResult } from 'app/inspecto/reconciliation/recon-board';
import { apiUrl, toParams } from './api-base';

/** One dataset's column inventory as `/recon/columns` reports it. */
export interface ReconDatasetColumns {
    dataset: string;
    columns: { name: string; type: string; numeric: boolean }[];
}

/** A column whose normalized name exists on every side — a suggested unified binding. */
export interface ReconColumnMatch {
    name: string;
    numeric: boolean;
    columns: Record<string, string>;
}

export interface ReconColumnsResult {
    datasets: ReconDatasetColumns[];
    matches: ReconColumnMatch[];
}

/** The server-side reconciliation config (`docs/superpower/reconciliation-board-design.md` §3). */
export interface ReconServerConfig {
    datasets: string[];
    keyColumns: string[];
    compareColumns: { column: string; agg?: string; toleranceType?: string; tolerance?: number }[];
    includeRecordCount?: boolean;
    columnMap?: Record<string, Record<string, string>>;
    filters?: Record<string, string>;
}

/**
 * What `POST /recon/promote` reports back (`BREAK-INCIDENT-1`).
 *
 * ⚠ `incidentId` is `null` exactly when `deduped` is true: an active Incident already covers this Break
 * and the server suppressed a second one. It does NOT name the survivor — the dedupe seam reports
 * suppression without returning the id — so a caller must not treat the null as a failure.
 */
export interface ReconPromoteResult {
    incidentId: string | null;
    deduped: boolean;
    reconciliation: string;
    key: string;
}

/**
 * What `GET /recon/promoted` reports (`BREAK-INCIDENT-RESOLVE-1`) — the READ half of promotion.
 *
 * 🔴 `promoted` maps a Break's **`breakId` identity — `(type, key, column)`, the value `breakId()` computes**
 * — to the id of the Incident covering it, and lists **only Breaks whose Incident is still ACTIVE**. ⚠ It was
 * keyed by the bare Break key until `BREAK-DEDUPE-GRAIN-1` (2026-09-15) widened the server's dedupe grain to
 * match the client's identity; look entries up with `breakId(b)`, never `b.key`.
 * That is not a detail: `POST /recon/promote` suppresses a duplicate only while
 * the Incident is non-terminal, so an ARCHIVED one means the Break can be promoted again. The server
 * applies that rule for both halves, which is why this is a route rather than the client filtering
 * `GET /objects` — a client matching on mere existence would call an available action unavailable.
 *
 * ⚠ `total` is the TRUE count; `truncated` says the map was capped. Do not infer the count from the map.
 */
export interface ReconPromotedResult {
    reconciliation: string;
    promoted: Record<string, string>;
    total: number;
    truncated: boolean;
}

/**
 * Reconciliation execution API (DAT-7) — the server-side comparison over two Datasets' relations.
 * Space-agnostic (`spaceInterceptor` scopes `/recon/*`); the offline mirror is
 * `app/inspecto/reconciliation/recon-board.ts` behind `ReconExecService`.
 */
@Injectable({ providedIn: 'root' })
export class ReconApiService {
    private http = inject(HttpClient);

    columns(datasets: string[]): Observable<ReconColumnsResult> {
        return this.http.post<ReconColumnsResult>(apiUrl('/recon/columns'), { datasets });
    }

    run(config: ReconServerConfig, limit?: number): Observable<ReconRunResult> {
        return this.http.post<ReconRunResult>(apiUrl('/recon/run'), { config, ...(limit ? { limit } : {}) });
    }

    breaks(
        config: ReconServerConfig,
        path?: Record<string, string> | null,
        type?: string | null,
        side?: string | null,
        limit?: number,
        offset?: number,
    ): Observable<ReconBreakSets> {
        return this.http.post<ReconBreakSets>(apiUrl('/recon/breaks'), {
            config,
            ...(path ? { path } : {}),
            ...(type ? { type } : {}),
            ...(side ? { side } : {}),
            ...(limit ? { limit } : {}),
            ...(offset ? { offset } : {}),
        });
    }

    /**
     * Promote one Break to an Incident (`BREAK-INCIDENT-1`). Deduped server-side on
     * `(reconciliation, key)`, so calling this twice for the same Break does not open a second Incident.
     *
     * ⚠ Takes the SAVED reconciliation's id, not its config — unlike every other method here, which
     * accept an inline draft. An Incident outlives the session, so it must reference a reconciliation
     * someone can still open; the server answers 404 for an unknown id.
     *
     * ⚠ A **503** here is the expected Personal-edition state (no `inspecto-ops` module), not a failure:
     * render it as an explained panel, never a toast.
     */
    /** Which Breaks of {@link reconciliation} already have an ACTIVE Incident — see {@link ReconPromotedResult}. */
    promoted(reconciliation: string): Observable<ReconPromotedResult> {
        return this.http.get<ReconPromotedResult>(apiUrl('/recon/promoted'), {
            params: toParams({ reconciliation }),
        });
    }

    promote(
        reconciliation: string,
        key: string,
        type?: string | null,
        column?: string | null,
        runId?: string | null,
    ): Observable<ReconPromoteResult> {
        return this.http.post<ReconPromoteResult>(apiUrl('/recon/promote'), {
            reconciliation,
            key,
            ...(type ? { type } : {}),
            ...(column ? { column } : {}),
            ...(runId ? { runId } : {}),
        });
    }
}
