import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { catchError, forkJoin, map, Observable, of, switchMap } from 'rxjs';
import { apiUrl } from '../api/api-base';
import { ComponentDef, ComponentsService } from '../api/components.service';
import { ShortReads } from '../api/short-reads';
import { SpacesService } from '../api/spaces.service';
import { datasetDescribesColumns, NO_SPACE, SpaceGuideKind, SpaceGuideState } from './space-guide';

/**
 * Loads the {@link SpaceGuideState} of the current Space from the server: the hosted Spaces (`/spaces`), the
 * Datasets and saved Link Analysis views (`/components/{dataset|link-analysis-view}`) and the Entity Lists
 * (`/entity-lists`); for the console guide, the Datasets, saved queries and dashboards
 * (`/components/{dataset|query|dashboard}`). Each read degrades on its own: a read that fails counts as "not yet", never as an error page
 * (an edition without Entity Lists simply never ticks *A list*).
 */
@Injectable({ providedIn: 'root' })
export class SpaceGuideService {
    private readonly http = inject(HttpClient);
    private readonly spaces = inject(SpacesService);
    private readonly components = inject(ComponentsService);
    private readonly reads = inject(ShortReads);

    load(kind: SpaceGuideKind = 'la'): Observable<SpaceGuideState> {
        return this.spaces.refresh().pipe(
            switchMap(() => {
                // A multi-Space server with no active Space has nothing to read: every per-Space call would miss.
                const hasSpace = !this.spaces.multiSpace() || this.spaces.currentSpace() !== null;
                if (!hasSpace) return of(NO_SPACE);
                const list = (
                    type: 'dataset' | 'link-analysis-view' | 'query' | 'dashboard',
                ): Observable<ComponentDef[]> =>
                    this.components.list(type).pipe(
                        map((d) => (Array.isArray(d) ? d : [])),
                        catchError(() => of([] as ComponentDef[])),
                    );
                if (kind === 'console')
                    return forkJoin({
                        datasets: list('dataset'),
                        queries: list('query'),
                        dashboards: list('dashboard'),
                    }).pipe(
                        map(
                            ({ datasets, queries, dashboards }): SpaceGuideState => ({
                                ...NO_SPACE,
                                hasSpace: true,
                                datasets: datasets.length,
                                columnsDescribed: datasets.some((d) => datasetDescribesColumns(d.content)),
                                queries: queries.length,
                                dashboards: dashboards.length,
                            }),
                        ),
                    );
                return forkJoin({
                    datasets: list('dataset'),
                    views: list('link-analysis-view'),
                    lists: this.reads
                        .read(apiUrl('/entity-lists'), () =>
                            this.http.get<{ lists?: unknown[] }>(apiUrl('/entity-lists')),
                        )
                        .pipe(
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
