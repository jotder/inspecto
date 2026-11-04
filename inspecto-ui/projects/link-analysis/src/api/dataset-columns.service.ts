import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { apiUrl } from '@inspecto/core/api/api-base';
import { ShortReads } from '@inspecto/core/api/short-reads';

/** `PUT /datasets/{id}/columns/{column}`: the column's classification now, and the entry's columns as written. */
export interface ColumnEntityTypeChange {
    dataset: string;
    column: string;
    classification: string | null;
    entityType?: string;
    columns: { name: string; classification?: string }[];
}

/** The body of `PUT /datasets/{id}/columns/{column}`: what to change; a key left out is kept, `null` clears it. */
export interface ColumnChange {
    entityType?: string | null;
    role?: string | null;
    unit?: string | null;
}

/**
 * What a Dataset column holds (LA-ONBOARD-1 OB-8, UC-07): one column's Entity Type, written as the column's
 * classification on the Dataset registry entry. Needs `canAuthorWorkbench` and edit access to the Dataset; the
 * Entity Types are re-assessed on the next read of `GET /inv/entity-types`.
 */
@Injectable({ providedIn: 'root' })
export class DatasetColumnsService {
    private http = inject(HttpClient);
    private reads = inject(ShortReads);

    /** Set (an Entity Type id) or clear (`null`) what `column` of `dataset` holds. 404 no Dataset; 422 no such column/type. */
    setEntityType(dataset: string, column: string, entityType: string | null): Observable<ColumnEntityTypeChange> {
        return this.setColumn(dataset, column, { entityType });
    }

    /**
     * Change a column's role (LA-COLUMN-ROLES-1: `entity | temporal | measure | dimension | detail`), what it holds and a
     * measure's unit, in one write; a key left out is kept, `null` clears it. 422 when the Dataset rules refuse the result.
     */
    setColumn(dataset: string, column: string, change: ColumnChange): Observable<ColumnEntityTypeChange> {
        return this.http
            .put<ColumnEntityTypeChange>(
                apiUrl(`/datasets/${encodeURIComponent(dataset)}/columns/${encodeURIComponent(column)}`),
                change,
            )
            .pipe(tap(() => this.reads.clear())); // it rewrites the Dataset entry: a replayed Dataset list would be stale
    }

    /**
     * Set (a record type id of the Space's packs) or clear (`null`) what one row of `dataset` is (ENTITY-TYPE-CATALOG-1 P2):
     * `PUT /datasets/{id}/record-type`. It only orders the "what does this column hold" choices; 422 for an unknown id.
     */
    setRecordType(
        dataset: string,
        recordType: string | null,
    ): Observable<{ dataset: string; recordType: string | null }> {
        return this.http
            .put<{
                dataset: string;
                recordType: string | null;
            }>(apiUrl(`/datasets/${encodeURIComponent(dataset)}/record-type`), { recordType })
            .pipe(tap(() => this.reads.clear()));
    }
}
