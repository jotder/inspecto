import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom, tap } from 'rxjs';
import { apiErrorMessage } from '@inspecto/core/api';
import { apiUrl } from '@inspecto/core/api/api-base';
import { GraphRunResult, GraphRunView, GraphRunsService } from '@inspecto/link-analysis/api/graph-runs.service';
import {
    EntityListHeld,
    EntityListIndex,
    EntityListMembersResult,
    InvService,
} from '@inspecto/link-analysis/api/inv.service';
import { KeyedDataset, indexByKey, keyChunks } from './node-risk';

/** `POST /inv/reference-rows` → the rows of the keys asked, masked under the Space's masking mode. */
export interface ReferenceRowsResult {
    dataset: string;
    keyCol: string;
    columns: string[];
    rows: { key: string; row: Record<string, unknown> }[];
    matchedKeys: number;
    unresolved: string[];
    rawKeysRefused: number;
    truncated: boolean;
    masking: { mode: string; masked: boolean; basis: string; maskedColumns: string[] };
}

/** A reference lookup for a set of keys: their rows by the key as asked, or why it could not be read. */
export interface ReferenceTable {
    byKey: Map<string, Record<string, unknown>>;
    columns: string[];
    /** A chunk matched more rows than the server returns: some keys' rows may be missing. */
    truncated: boolean;
    /** Keys the server could not resolve (an alias it no longer knows, or a raw id masking hides). */
    unresolved: number;
    /** Some value was masked (the Space masks entity ids or a column is classified sensitive). */
    masked: boolean;
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
 * indicators". Reference rows are looked up for the keys needed only (`POST /inv/reference-rows`, chunked at the
 * server's key cap): the server resolves aliases / pseudonyms, binds the keys and masks the answer as every other Link
 * Analysis read does. A propagated-risk answer is cached per graph (Investigation + Working Set hash).
 */
@Injectable({ providedIn: 'root' })
export class NodeRiskService {
    private readonly runs = inject(GraphRunsService);
    private readonly inv = inject(InvService);
    private readonly http = inject(HttpClient);

    private readonly risk = new Map<string, GraphRunResult>();

    /** One reference-rows call (at most the key cap). */
    referenceRows(ref: KeyedDataset, keys: string[], investigationId?: string | null): Observable<ReferenceRowsResult> {
        return this.http.post<ReferenceRowsResult>(apiUrl('/inv/reference-rows'), {
            dataset: ref.dataset,
            keyCol: ref.keyCol,
            keys,
            ...(investigationId ? { investigationId } : {}),
        });
    }

    /** The rows of `keys` in a reference Dataset, chunk by chunk; any failed chunk makes the whole lookup an error. */
    async lookup(ref: KeyedDataset, keys: string[], investigationId?: string | null): Promise<ReferenceTable> {
        const out: ReferenceTable = { byKey: new Map(), columns: [], truncated: false, unresolved: 0, masked: false };
        try {
            for (const chunk of keyChunks(keys)) {
                const r = await firstValueFrom(this.referenceRows(ref, chunk, investigationId));
                for (const [k, row] of indexByKey(r.rows)) if (!out.byKey.has(k)) out.byKey.set(k, row);
                out.columns = r.columns;
                out.truncated ||= r.truncated;
                out.unresolved += r.unresolved.length + r.rawKeysRefused;
                out.masked ||= r.masking.masked;
            }
        } catch (e) {
            out.error = apiErrorMessage(e, 'the reference rows could not be read');
        }
        return out;
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
