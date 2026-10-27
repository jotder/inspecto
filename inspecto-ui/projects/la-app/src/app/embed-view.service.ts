import { HttpBackend, HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { apiUrl } from '@inspecto/core/api/api-base';
import { isV1Envelope } from '@inspecto/core/api/v1';
import type { G6GraphData } from '@inspecto/core/graph';

/** The masked, read-only view `GET /public/investigations/{token}` answers (LA-EMBED-VIEW-1). */
export interface EmbedView {
    investigation: { id: string; title: string | null; purpose: string | null; createdAt?: string };
    head: { step: number; workingSetHash?: string };
    workingSet: {
        entities: { entityId: string; type?: string; hop?: number; hidden?: boolean; kept?: boolean }[];
        links: { source: string; target: string; kind: string; count?: number }[];
        excludedCount: number;
    };
    annotations: { step: number; note: string; confidence?: number }[];
    references: unknown[];
    link: { expiresAt: string; scope?: string };
    masking?: { mode?: string };
}

/**
 * The anonymous embed read. It uses an `HttpClient` built on the raw `HttpBackend`, so NO interceptor runs: no
 * `Authorization` header, no refresh or sign-in redirect on a 401, no Space rewrite, and no error tracker that could log
 * the URL (the token is the credential and lives only in the path and in memory). The v1 envelope is unwrapped here.
 */
@Injectable({ providedIn: 'root' })
export class EmbedViewService {
    private readonly http = new HttpClient(inject(HttpBackend));

    view(token: string): Observable<EmbedView> {
        return this.http
            .get<unknown>(apiUrl('/public/investigations/' + encodeURIComponent(token)))
            .pipe(map((body) => (isV1Envelope(body) ? (body.data as EmbedView) : (body as EmbedView))));
    }
}

/**
 * The shared Working Set as the graph the shared G6 host draws (LA-STUDIO-UI-REORG-1 §5.13): one node per entity, labelled
 * with its id exactly as returned (already masked), and one edge per link. Hidden entities are skipped and a link to a skipped
 * one is dropped. No services and no call.
 */
export function embedGraphData(ws: Pick<EmbedView['workingSet'], 'entities' | 'links'>): G6GraphData {
    const nodes = ws.entities
        .filter((e) => !e.hidden)
        .map((e) => ({ id: e.entityId, data: { label: e.entityId, kind: e.type || 'entity' } }));
    const ids = new Set(nodes.map((n) => n.id));
    const edges = ws.links
        .filter((l) => ids.has(l.source) && ids.has(l.target))
        .map((l, i) => ({ id: 'l' + i, source: l.source, target: l.target, data: { kind: l.kind } }));
    return { nodes, edges };
}
