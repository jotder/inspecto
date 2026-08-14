import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';
import { GraphQuery, KpiCatalog, MetadataGraph, MetadataNode, NodeDetail } from './models';

/** Metadata graph / data catalog (ASSIST_READ scope; CONTROL satisfies it). */
@Injectable({ providedIn: 'root' })
export class CatalogService {
    private http = inject(HttpClient);

    tables(): Observable<MetadataNode[]> {
        return this.http.get<MetadataNode[]>(apiUrl('/catalog'));
    }
    kpis(): Observable<KpiCatalog> {
        return this.http.get<KpiCatalog>(apiUrl('/catalog/kpis'));
    }
    /** Streams — the Catalog's event/fact data-origin lens (a Collector + its Connection, browsed by name). */
    streams(): Observable<MetadataNode[]> {
        return this.http.get<MetadataNode[]>(apiUrl('/catalog/streams'));
    }
    /** References — the Catalog's dimension data-origin lens (REFERENCE_DATASET nodes, browsed by name). */
    references(): Observable<MetadataNode[]> {
        return this.http.get<MetadataNode[]>(apiUrl('/catalog/references'));
    }
    /**
     * The catalog node a batch row's `output_table` names. 404s when the store name resolves to no node
     * or to several — callers must treat that as "no link", never as a reason to guess.
     */
    resolveTable(table: string): Observable<{ id: string; label: string }> {
        return this.http.get<{ id: string; label: string }>(apiUrl('/catalog/resolve'), {
            params: toParams({ table }),
        });
    }
    node(id: string): Observable<NodeDetail> {
        return this.http.get<NodeDetail>(apiUrl(`/catalog/tables/${encodeURIComponent(id)}`));
    }
    graph(q: GraphQuery): Observable<MetadataGraph> {
        return this.http.get<MetadataGraph>(apiUrl('/catalog/graph'), {
            params: toParams({
                from: q.from,
                depth: q.depth,
                direction: q.direction,
                kinds: q.kinds,
                edgeKinds: q.edgeKinds,
                overlay: q.overlay,
            }),
        });
    }
}
