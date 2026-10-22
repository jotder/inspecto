import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { catchError, forkJoin, map, Observable, of, switchMap } from 'rxjs';
import { apiUrl } from '../api/api-base';
import { ComponentDef, ComponentsService } from '../api/components.service';
import { SpacesService } from '../api/spaces.service';
import { datasetDescribesColumns, NO_SPACE, SpaceGuideState } from './space-guide';

/**
 * Loads the {@link SpaceGuideState} of the current Space from the server: the hosted Spaces (`/spaces`), the
 * Datasets and saved Link Analysis views (`/components/{dataset|link-analysis-view}`) and the Entity Lists
 * (`/entity-lists`). Each read degrades on its own: a read that fails counts as "not yet", never as an error page
 * (an edition without Entity Lists simply never ticks *A list*).
 */
@Injectable({ providedIn: 'root' })
export class SpaceGuideService {
    private readonly http = inject(HttpClient);
    private readonly spaces = inject(SpacesService);
    private readonly components = inject(ComponentsService);

    load(): Observable<SpaceGuideState> {
        return this.spaces.refresh().pipe(
            switchMap(() => {
                // A multi-Space server with no active Space has nothing to read: every per-Space call would miss.
                const hasSpace = !this.spaces.multiSpace() || this.spaces.currentSpace() !== null;
                if (!hasSpace) return of(NO_SPACE);
                const list = (type: 'dataset' | 'link-analysis-view'): Observable<ComponentDef[]> =>
                    this.components.list(type).pipe(
                        map((d) => (Array.isArray(d) ? d : [])),
                        catchError(() => of([] as ComponentDef[])),
                    );
                return forkJoin({
                    datasets: list('dataset'),
                    views: list('link-analysis-view'),
                    lists: this.http.get<{ lists?: unknown[] }>(apiUrl('/entity-lists')).pipe(
                        map((r) => (Array.isArray(r?.lists) ? r.lists.length : 0)),
                        catchError(() => of(0)),
                    ),
                }).pipe(
                    map(
                        ({ datasets, views, lists }): SpaceGuideState => ({
                            hasSpace: true,
                            datasets: datasets.length,
                            columnsDescribed: datasets.some((d) => datasetDescribesColumns(d.content)),
                            views: views.length,
                            lists,
                        }),
                    ),
                );
            }),
        );
    }
}
