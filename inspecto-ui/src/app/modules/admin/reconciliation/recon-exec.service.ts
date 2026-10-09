import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';
import { ReconApiService, ReconServerConfig } from 'app/inspecto/api';
import { ReconRowsResult } from 'app/inspecto/api/recon.service';
import { Reconciliation, ReconBreakSets, ReconRunQuery, ReconRunResult, SideKey } from 'app/inspecto/reconciliation';

/**
 * Reconciliation execution seam — the recon analogue of `DatasetResultService`. The comparison executes
 * server-side in DuckDB via `POST /recon/run` / `/recon/breaks`; the Board and the Breaks page read the
 * result. This replaces the C9 review-sheet's `datasetRows()` mock seam.
 */
@Injectable({ providedIn: 'root' })
export class ReconExecService {
    private api = inject(ReconApiService);

    /** One day's page of the Board comparison — an Observable so a newer request can cancel it (switchMap). */
    page(recon: Reconciliation, query: ReconRunQuery): Observable<ReconRunResult> {
        return this.api.run(serverConfig(recon), query);
    }

    /**
     * The Break sets at the recon grain for one anchor-relative pair, optionally scoped to a Board
     * dimension path. {@code side} picks the compared side ('b' default, or 'c' on a 3-way recon).
     */
    /** The raw rows behind one key, both sides (RECON-CARDINALITY-2). */
    async rows(
        recon: Reconciliation,
        key: Record<string, string>,
        side: SideKey = 'b',
        day?: string | null,
    ): Promise<ReconRowsResult> {
        return firstValueFrom(this.api.rows(serverConfig(recon), key, side, undefined, day));
    }

    async breaks(
        recon: Reconciliation,
        path?: Record<string, string> | null,
        type?: 'missing_left' | 'missing_right' | 'value_break' | null,
        side: SideKey = 'b',
        day?: string | null,
    ): Promise<ReconBreakSets> {
        return firstValueFrom(this.api.breaks(serverConfig(recon), path, type, side, undefined, undefined, day));
    }
}

/** Map the UI model to the server config (`/recon/*` accepts the v1 left/right form too — send v2). */
export function serverConfig(recon: Reconciliation): ReconServerConfig {
    const raw = recon.raw ?? {};
    return {
        datasets: recon.thirdDataset
            ? [recon.leftDataset, recon.rightDataset, recon.thirdDataset]
            : [recon.leftDataset, recon.rightDataset],
        keyColumns: recon.keyColumns,
        compareColumns: recon.compareColumns.map((c) => ({
            column: c.column,
            agg: c.agg ?? 'sum',
            toleranceType: c.toleranceType,
            tolerance: c.tolerance,
        })),
        // An authored recon's stored settings the SPA does not model travel as stored (`raw`): dropping them here
        // hid a declared cardinality's duplicate-key Breaks and ignored column maps / filters on the Board + Breaks.
        includeRecordCount: typeof raw['includeRecordCount'] === 'boolean' ? raw['includeRecordCount'] : true,
        ...(isRecord(raw['columnMap']) ? { columnMap: raw['columnMap'] as ReconServerConfig['columnMap'] } : {}),
        ...(isRecord(raw['filters']) ? { filters: raw['filters'] as ReconServerConfig['filters'] } : {}),
        ...(typeof raw['cardinality'] === 'string' && raw['cardinality'] ? { cardinality: raw['cardinality'] } : {}),
        // A non-compared impact column is carried on each Break only if the server is told about it.
        ...(recon.impact ? { impact: recon.impact } : {}),
    };
}

function isRecord(v: unknown): v is Record<string, unknown> {
    return typeof v === 'object' && v !== null && !Array.isArray(v);
}
