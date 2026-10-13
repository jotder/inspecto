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

export interface EmbedGraphNode {
    id: string;
    type?: string;
    hop: number;
    x: number;
    y: number;
}

export interface EmbedGraph {
    /** Half the side of the square the drawing fits in, centred on (0, 0). */
    extent: number;
    nodes: EmbedGraphNode[];
    edges: { source: string; target: string; kind: string; count: number; x1: number; y1: number; x2: number; y2: number }[];
}

/**
 * A static layout of the shared Working Set: one ring per hop from the seeds (hop 0 in the middle), each ring wide enough for its
 * entities. No physics and no services; hidden entities are skipped and an edge to a skipped one is dropped.
 */
export function layoutEmbedGraph(ws: Pick<EmbedView['workingSet'], 'entities' | 'links'>): EmbedGraph {
    const byHop = new Map<number, EmbedView['workingSet']['entities']>();
    for (const e of ws.entities) {
        if (e.hidden) continue;
        const hop = Math.max(0, e.hop ?? 0);
        byHop.set(hop, [...(byHop.get(hop) ?? []), e]);
    }
    const pos = new Map<string, EmbedGraphNode>();
    let radius = 0;
    let first = true;
    for (const hop of [...byHop.keys()].sort((a, b) => a - b)) {
        const ring = byHop.get(hop)!;
        const needed = (ring.length * 18) / (2 * Math.PI);
        const r = first && ring.length === 1 ? 0 : Math.max(radius + 90, needed);
        ring.forEach((e, i) => {
            const a = (2 * Math.PI * i) / ring.length - Math.PI / 2;
            pos.set(e.entityId, { id: e.entityId, type: e.type, hop, x: r * Math.cos(a), y: r * Math.sin(a) });
        });
        radius = r;
        first = false;
    }
    const edges = ws.links.flatMap((l) => {
        const s = pos.get(l.source);
        const t = pos.get(l.target);
        return s && t ? [{ source: l.source, target: l.target, kind: l.kind, count: l.count ?? 1, x1: s.x, y1: s.y, x2: t.x, y2: t.y }] : [];
    });
    return { extent: Math.max(radius + 60, 200), nodes: [...pos.values()], edges };
}
