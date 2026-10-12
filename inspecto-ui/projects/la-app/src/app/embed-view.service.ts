import { HttpBackend, HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { apiUrl } from '@inspecto/core/api/api-base';
import { isV1Envelope } from '@inspecto/core/api/v1';

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
    link: { expiresAt: string };
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

export interface EmbedGraph {
    size: number;
    nodes: { id: string; x: number; y: number }[];
    edges: { x1: number; y1: number; x2: number; y2: number }[];
}

/** A static circular layout of the shared working set (no physics, no services): hidden entities are skipped. */
export function layoutEmbedGraph(ws: Pick<EmbedView['workingSet'], 'entities' | 'links'>): EmbedGraph {
    const size = 400;
    const ids = ws.entities.filter((e) => !e.hidden).map((e) => e.entityId);
    const pos = new Map<string, { x: number; y: number }>();
    ids.forEach((id, i) => {
        const a = (2 * Math.PI * i) / ids.length;
        pos.set(id, { x: size / 2 + 160 * Math.cos(a), y: size / 2 + 160 * Math.sin(a) });
    });
    const edges = ws.links.flatMap((l) => {
        const s = pos.get(l.source);
        const t = pos.get(l.target);
        return s && t ? [{ x1: s.x, y1: s.y, x2: t.x, y2: t.y }] : [];
    });
    return { size, nodes: ids.map((id) => ({ id, ...pos.get(id)! })), edges };
}
