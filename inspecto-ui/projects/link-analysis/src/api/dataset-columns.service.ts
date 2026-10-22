import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl } from '@inspecto/core/api/api-base';

/** `PUT /datasets/{id}/columns/{column}`: the column's classification now, and the entry's columns as written. */
export interface ColumnEntityTypeChange {
    dataset: string;
    column: string;
    classification: string | null;
    entityType?: string;
    columns: { name: string; classification?: string }[];
}

/**
 * What a Dataset column holds (LA-ONBOARD-1 OB-8, UC-07): one column's Entity Type, written as the column's
 * classification on the Dataset registry entry. Needs `canAuthorWorkbench` and edit access to the Dataset; the
 * Entity Types are re-assessed on the next read of `GET /inv/entity-types`.
 */
@Injectable({ providedIn: 'root' })
export class DatasetColumnsService {
    private http = inject(HttpClient);

    /** Set (an Entity Type id) or clear (`null`) what `column` of `dataset` holds. 404 no Dataset; 422 no such column/type. */
    setEntityType(dataset: string, column: string, entityType: string | null): Observable<ColumnEntityTypeChange> {
        return this.http.put<ColumnEntityTypeChange>(
            apiUrl(`/datasets/${encodeURIComponent(dataset)}/columns/${encodeURIComponent(column)}`),
            { entityType },
        );
    }
}
