import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { apiUrl } from '@inspecto/core/api/api-base';
import { DatasetRowsService } from '@inspecto/core/viz/dataset-rows.service';
import { GraphRunResult, GraphRunView, GraphRunsService } from '@inspecto/link-analysis/api/graph-runs.service';
import {
    EntityListHeld,
    EntityListIndex,
    EntityListMembersResult,
    InvService,
} from '@inspecto/link-analysis/api/inv.service';
import { KeyedDataset, REFERENCE_ROW_LIMIT, indexByKey, referenceSql } from './node-risk';

/** A reference Dataset read once: its rows by key, or why it could not be read. */
export interface ReferenceTable {
    byKey: Map<string, Record<string, unknown>>;
    columns: string[];
    truncated: boolean;
    error?: string;
}

/** `POST /entity-lists/{id}/match` → one verdict per value asked. */
export interface EntityListMatch {
    value: string;
    matched: boolean;
    entry?: string;
    match?: string;
}

/**
 * The reads and writes behind the node detail dialog's Risk / Enrichment / actions sections and the panel's "fill from
 * indicators". A reference Dataset is read WHOLE once per session over the analyst-allowed `/db/query` (so no entity
 * value is ever put in SQL) and looked up client-side; a propagated-risk answer is cached per graph (Investigation +
 * Working Set hash).
 */
@Injectable({ providedIn: 'root' })
export class NodeRiskService {
    private readonly rows = inject(DatasetRowsService);
    private readonly runs = inject(GraphRunsService);
    private readonly inv = inject(InvService);
    private readonly http = inject(HttpClient);

    private readonly tables = new Map<string, Promise<ReferenceTable>>();
    private readonly risk = new Map<string, GraphRunResult>();

    /** The whole reference Dataset, indexed by its key column; a failed read is not cached (the next ask retries). */
    table(ref: KeyedDataset): Promise<ReferenceTable> {
        const key = `${ref.dataset}\u0000${ref.keyCol}`;
        let p = this.tables.get(key);
        if (!p) {
            p = this.rows.sql(ref.dataset, referenceSql(ref), REFERENCE_ROW_LIMIT).then((r) => {
                if (r.error) this.tables.delete(key);
                return {
                    byKey: indexByKey(r.rows, ref.keyCol),
                    columns: r.columns.map((c) => c.name),
                    truncated: r.truncated,
                    ...(r.error ? { error: r.error } : {}),
                };
            });
            this.tables.set(key, p);
        }
        return p;
    }

    /** The propagated-risk answer already computed for this graph, or null. */
    cachedRisk(graphKey: string): GraphRunResult | null {
        return this.risk.get(graphKey) ?? null;
    }

    /** Run `propagatedRisk` over the Investigation's Working Set (202 + poll handled by `run`); a completed answer is cached. */
    computeRisk(
        graphKey: string,
        investigationId: string,
        nodeScores: Record<string, number>,
    ): Observable<GraphRunView> {
        return this.runs
            .run({ investigationId, algorithm: 'propagatedRisk', params: { nodeScores } })
            .pipe(tap((v) => v.status === 'COMPLETED' && v.result && this.risk.set(graphKey, v.result)));
    }

    entityLists(): Observable<EntityListIndex> {
        return this.inv.listEntityLists();
    }

    /** Read-shaped membership check (a POST so the key never rides in a URL). */
    match(listId: string, values: string[]): Observable<{ matches: EntityListMatch[] }> {
        return this.http.post<{ matches: EntityListMatch[] }>(
            apiUrl(`/entity-lists/${encodeURIComponent(listId)}/match`),
            { values },
        );
    }

    /** Add one RAW value; a governed list answers 202 `{status: 'pending'}` (four-eyes). */
    addMember(listId: string, value: string, reason: string): Observable<EntityListMembersResult | EntityListHeld> {
        return this.inv.changeEntityListMembers(listId, { add: [value], reason });
    }
}
