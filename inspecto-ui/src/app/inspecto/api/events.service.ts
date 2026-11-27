import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { apiUrl, toParams } from './api-base';
import { spaceScopedUrl } from './space-scope';
import { SessionService } from '../auth/session.service';
import { SpacesService } from './spaces.service';
import type { Ref } from '../component-model/component-types';
import { signalToEvent, type Signal, type SignalSeverity } from '../signal/signal';

/**
 * One immutable operational fact from the Operational Intelligence event engine (GET /events*). Mirrors
 * `com.gamma.audit.Event#toMap()`: `ts` is epoch millis, `timestamp` the ISO-8601 UTC string, `level` an
 * {@link EVENT_LEVELS} member, `type` a {@link EVENT_TYPES} constant or any custom string, and `attributes`
 * the structured detail bag (never row content). `pipeline`/`correlationId` may be null (service-wide).
 *
 * Since R4 the `/events` surface is a **projection** of the unified Signal ledger (`inspecto/signal`), so a
 * row also carries the signal's `severity` and `sourceRef` (the emitting component). `level` remains the
 * min-level filter's backward-compatible view of `severity`. Both extras are display-only and optional.
 */
export interface EventRow {
    eventId: string;
    ts: number;
    timestamp: string;
    level: string;
    type: string;
    source: string;
    pipeline: string | null;
    correlationId: string | null;
    message: string;
    attributes: Record<string, string>;
    severity?: SignalSeverity;
    sourceRef?: Ref;
    /** The Signal's subject — what it is about (a `dataset.write` names its store here). Absent on raw Events. */
    subjectRef?: { kind: string; id: string; rel?: string; via?: string };
}

/** Filter + page over the event store (the `?…` query of GET /events/search); every field is optional. */
export interface EventFilter {
    level?: string;
    type?: string;
    pipeline?: string;
    correlationId?: string;
    q?: string;
    from?: string;
    to?: string;
    limit?: number;
    offset?: number;
}

/** An operator-saved filter set (GET/POST /events/views) — name + the set search params. */
export interface SavedEventView {
    name: string;
    filters: Record<string, string>;
    createdAt: number;
}

/** Severity ladder (`EventLevel`), least → most severe; the level filter is a *minimum* (`level >= minLevel`). */
export const EVENT_LEVELS = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR'] as const;

/** Well-known `Event.type` constants for the type filter — the model is extensible, so any string is valid too. */
export const EVENT_TYPES: string[] = [
    'LOG',
    'SERVICE_STARTED',
    'PIPELINE_REGISTERED',
    'PIPELINE_PAUSED',
    'PIPELINE_RESUMED',
    'PIPELINE_RENAMED',
    'BATCH_COMMITTED',
    'BATCH_FAILED',
    'FILE_RECEIVED',
    'FILE_QUARANTINED',
    'FILE_DISCOVERED',
    'FILE_FETCHED',
    'FILE_VALIDATED',
    'FILE_FETCH_FAILED',
    'FILE_ARCHIVED',
    'SOURCE_CIRCUIT_OPEN',
    'FILE_STABLE',
    'FILE_CHANGED',
    'SEQUENCE_GAP',
    'JOB_STARTED',
    'JOB_SUCCEEDED',
    'JOB_FAILED',
    'ENRICHMENT_RUN',
    'ALERT_FIRED',
    'CONFIG_VALIDATED',
    'OBJECT_OPENED',
    'OBJECT_ACTIVITY',
    'OBJECT_SLA_BREACH',
    'OBJECT_LINKED',
    'OBJECT_NOTE',
];

/** One payload field of a Signal Type, as `GET /signals/types` serves it. */
export interface SignalTypeField {
    name: string;
    kind: 'string' | 'integer' | 'number' | 'boolean' | 'list' | 'map';
    always: boolean;
    description: string;
}

/**
 * One Signal Type (SIGNAL-BOARD-1): a dotted name or a `prefix.*` family, what posts it, its payload fields, and where it
 * is declared (`core`, `module:<id>`, `pack:<file>`). `fieldsDeclared` is false for a Job Pack's emit and for a family
 * whose payload is chosen at run time.
 */
export interface SignalTypeInfo {
    type: string;
    title: string;
    poster: string;
    fields: SignalTypeField[];
    since: string | null;
    source: string;
    fieldsDeclared: boolean;
}

/** A Job listening to a Signal Type: through `on_signal`, or `on_pipeline` (short for `on_signal: pipeline.commit`). */
export interface SignalBoardListener {
    job: string;
    key: 'on_signal' | 'on_pipeline';
    value: string;
    enabled: boolean;
}

/** One row of the Signal Board (SIGNAL-BOARD-1 SB-3): a type, who posts it, who listens and how listening went. */
export interface SignalBoardType {
    type: string;
    title: string | null;
    poster: string | null;
    source: string | null;
    catalogued: boolean;
    family: boolean;
    posted: number;
    lastPosted: string | null;
    /** Emitter refs seen (at most 10), with the true count. */
    postedBy: string[];
    postedByTotal: number;
    postedByTruncated: boolean;
    listeners: SignalBoardListener[];
    listenersTotal: number;
    listenersTruncated: boolean;
    runsStarted: number;
    guardsSaidNo: number;
    refused: number;
    chainCuts: number;
    /** `null` until missed Signals are recorded (SB-2b); then a count. */
    missed: number | null;
}

/** `GET /signals/board`: the board of the current Space over `hours`. Every list carries its true total. */
export interface SignalBoard {
    hours: number;
    since: string;
    until: string;
    /** The ledger page was full: posting, Run, refusal and chain-cut counts are floors. */
    capped: boolean;
    /** The guard count reads each Job's Run history in memory, which does not reach back over the whole window
     *  (the server started inside it, or the history is full): it is a floor. */
    guardsCapped: boolean;
    missedTracked: boolean;
    types: SignalBoardType[];
    typesTotal: number;
    typesTruncated: boolean;
    /** Per posted type (a family member is listed on its own). */
    unheard: {
        type: string;
        posted: number;
        lastPosted: string | null;
        postedBy: string[];
        postedByTotal: number;
        postedByTruncated: boolean;
    }[];
    unheardTotal: number;
    unheardTruncated: boolean;
    unposted: { job: string; onSignal: string; enabled: boolean }[];
    unpostedTotal: number;
    unpostedTruncated: boolean;
}

/** Operational event stream + saved views (CONTROL scope). */
@Injectable({ providedIn: 'root' })
export class EventsService {
    private http = inject(HttpClient);
    private spaces = inject(SpacesService);
    private session = inject(SessionService);

    /** Filtered events, newest-first (GET /events/search). An empty filter returns the newest `limit` events. */
    /**
     * The Signal ledger view (`GET /signals?type=&limit=`) as rows — the one place a Signal's `subject`
     * survives, which `search()` over raw Events cannot give (an Event has no subject). Used by the
     * stale-tile resolver to see `dataset.write` at STORE granularity (STALE-TILES-PRECISION-1).
     */
    /**
     * HOME-TILES-1: how many Signals of `type` landed since `sinceMs`, and how many distinct datasets they name.
     * `capped` means the ledger page was full and the figure is a lower bound.
     */
    signalCount(type: string, sinceMs?: number): Observable<{ count: number; datasets: number; capped: boolean }> {
        return this.http.get<{ count: number; datasets: number; capped: boolean }>(apiUrl('/signals/count'), {
            params: toParams({ type, ...(sinceMs ? { since: sinceMs } : {}) } as Record<string, unknown>),
        });
    }

    /**
     * SIGNAL-BOARD-1 SB-1c: every Signal Type that can be posted in this Space (`GET /signals/types`), each with its
     * declared payload fields. Gated on canAuthorWorkbench: it feeds the Job form's `on_signal` suggestions.
     */
    signalTypes(): Observable<SignalTypeInfo[]> {
        return this.http
            .get<{ types?: SignalTypeInfo[] }>(apiUrl('/signals/types'))
            .pipe(map((r) => (Array.isArray(r?.types) ? r.types : [])));
    }

    /** SIGNAL-BOARD-1 SB-3: the Signal Board of this Space over the last `hours` (`GET /signals/board`, canAuthorWorkbench). */
    signalBoard(hours: number): Observable<SignalBoard> {
        return this.http.get<SignalBoard>(apiUrl('/signals/board'), { params: toParams({ hours }) });
    }

    signals(filter: { type?: string; source?: string; limit?: number } = {}): Observable<EventRow[]> {
        return this.http
            .get<Signal[]>(apiUrl('/signals'), { params: toParams(filter as Record<string, unknown>) })
            .pipe(map((rows) => (Array.isArray(rows) ? rows : []).map(signalToEvent)));
    }

    search(filter: EventFilter = {}): Observable<EventRow[]> {
        return this.http.get<EventRow[]>(apiUrl('/events/search'), {
            params: toParams(filter as Record<string, unknown>),
        });
    }

    /** One event by id (GET /events/{id}); 404 when not found in the newest window. */
    get(id: string): Observable<EventRow> {
        return this.http.get<EventRow>(apiUrl(`/events/${encodeURIComponent(id)}`));
    }

    /** Matching events as CSV text (GET /events/export?format=csv) — fetched via HttpClient so the bearer token is sent. */
    exportCsv(filter: EventFilter = {}): Observable<string> {
        return this.http.get(apiUrl('/events/export'), {
            params: toParams({ ...filter, format: 'csv' } as Record<string, unknown>),
            responseType: 'text',
        });
    }

    /** Operator-saved views (GET /events/views). */
    views(): Observable<SavedEventView[]> {
        return this.http.get<SavedEventView[]>(apiUrl('/events/views'));
    }

    /** Upsert a saved view (POST /events/views) — the body is {name, …set filter keys}. */
    saveView(name: string, filters: Record<string, string>): Observable<SavedEventView> {
        return this.http.post<SavedEventView>(apiUrl('/events/views'), { name, ...filters });
    }

    /** Delete a saved view (POST /events/views/{name}/delete). */
    deleteView(name: string): Observable<unknown> {
        return this.http.post(apiUrl(`/events/views/${encodeURIComponent(name)}/delete`), {});
    }

    /**
     * Live signals as `EventRow`s (`GET /signals/stream`, server-sent events). The `/events` surface is a
     * projection of the same unified Signal ledger, so each frame is mapped through
     * {@link signalToEvent} — the sanctioned projection — rather than a second shape.
     *
     * The route supports the same filters as `GET /signals` (`type`, `severity`, `source`,
     * `correlationId`) and carries **no historical replay**: a caller does its initial `search()` and then
     * opens this, exactly as the route's own contract states.
     *
     * ⚠ **Three things a caller must know.**
     * 1. It **errors immediately when `EventSource` is unavailable** (jsdom, and any environment without
     *    it) so the caller falls back to polling rather than silently going quiet.
     * 2. A transport error errors the observable **once**; it does not retry. A dropped stream is not
     *    "backend down", so it must NOT be routed to the connectivity banner — the caller falls back to
     *    its visibility-aware poll, the same posture the notifications stream takes.
     * 3. Malformed frames are **ignored**, not surfaced: one bad frame must not tear down a live tail.
     *
     * 🔴 The URL is space-scoped **by hand** through {@link spaceScopedUrl}, because `EventSource` does not
     * pass through `spaceInterceptor`. Without it a multi-space deployment subscribes to the default
     * space's stream while showing another space's rows.
     */
    stream(
        filter: Pick<EventFilter, 'type' | 'correlationId'> & { severity?: string; source?: string } = {},
    ): Observable<EventRow> {
        return new Observable<EventRow>((subscriber) => {
            if (typeof EventSource === 'undefined') {
                subscriber.error(new Error('EventSource unavailable'));
                return;
            }
            // R2-12: a signed-in edition authenticates with a bearer header `EventSource` cannot send, so the
            // stream could only answer 401 (and audit an access.denied) — error at once so the caller polls.
            if (this.session.authMode() === 'oidc') {
                subscriber.error(new Error('signal stream cannot carry the bearer token'));
                return;
            }
            const params = toParams(filter as Record<string, unknown>).toString();
            const base = spaceScopedUrl(apiUrl('/signals/stream'), this.spaces.currentSpaceId());
            let source: EventSource;
            try {
                source = new EventSource(params ? `${base}?${params}` : base);
            } catch (err) {
                subscriber.error(err);
                return;
            }
            source.onmessage = (e: MessageEvent<string>) => {
                try {
                    subscriber.next(signalToEvent(JSON.parse(e.data) as Signal));
                } catch {
                    /* ignore a malformed frame — one must not end the tail */
                }
            };
            source.onerror = () => {
                source.close();
                subscriber.error(new Error('signal stream closed'));
            };
            return () => source.close();
        });
    }
}
