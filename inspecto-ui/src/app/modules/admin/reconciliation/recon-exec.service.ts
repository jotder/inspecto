import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ReconApiService, ReconServerConfig } from 'app/inspecto/api';
import { Reconciliation, ReconBreakSets, ReconRunResult, SideKey } from 'app/inspecto/reconciliation';

/**
 * Reconciliation execution seam — the recon analogue of `DatasetResultService`. The comparison executes
 * server-side in DuckDB via `POST /recon/run` / `/recon/breaks`; the Board and the Breaks page read the
 * result. This replaces the C9 review-sheet's `datasetRows()` mock seam.
 */
@Injectable({ providedIn: 'root' })
export class ReconExecService {
    private api = inject(ReconApiService);

    /** Run the Board aggregate comparison. */
    async run(recon: Reconciliation): Promise<ReconRunResult> {
        return firstValueFrom(this.api.run(serverConfig(recon)));
    }

    /**
     * The Break sets at the recon grain for one anchor-relative pair, optionally scoped to a Board
     * dimension path. {@code side} picks the compared side ('b' default, or 'c' on a 3-way recon).
     */
    async breaks(
        recon: Reconciliation,
        path?: Record<string, string> | null,
        type?: 'missing_left' | 'missing_right' | 'value_break' | null,
        side: SideKey = 'b',
    ): Promise<ReconBreakSets> {
        return firstValueFrom(this.api.breaks(serverConfig(recon), path, type, side));
    }
}

/** Map the UI model to the server config (`/recon/*` accepts the v1 left/right form too — send v2). */
export function serverConfig(recon: Reconciliation): ReconServerConfig {
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
        includeRecordCount: true,
    };
}
