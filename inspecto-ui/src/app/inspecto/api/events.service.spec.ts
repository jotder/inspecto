import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { EventRow, EventsService } from './events.service';
import { SpacesService } from './spaces.service';
import { environment } from '../../../environments/environment';

const base = environment.apiBaseUrl + '/v1'; // W7: apiUrl() builds /api/v1 paths

/** A minimal stand-in for the browser's EventSource, so the stream's frame handling is testable in jsdom. */
class FakeEventSource {
    static last?: FakeEventSource;
    onmessage: ((e: MessageEvent<string>) => void) | null = null;
    onerror: ((e: Event) => void) | null = null;
    closed = false;
    constructor(readonly url: string) {
        FakeEventSource.last = this;
    }
    close(): void {
        this.closed = true;
    }
    emit(data: string): void {
        this.onmessage?.({ data } as MessageEvent<string>);
    }
    fail(): void {
        this.onerror?.(new Event('error'));
    }
}

/** `globalThis.EventSource` is typed as the real constructor, so the stub is installed through one
 *  deliberate widening rather than a cast at each call site. */
const G = globalThis as unknown as Record<string, unknown>;
const setEventSource = (v: unknown): void => {
    G['EventSource'] = v;
};
const clearEventSource = (): void => {
    delete G['EventSource'];
};

describe('EventsService.stream (GET /signals/stream, CLIENT-HALVES-1 part c)', () => {
    let svc: EventsService;
    let httpMock: HttpTestingController;
    let hadEventSource: boolean;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [EventsService, SpacesService, provideHttpClient(withXhr()), provideHttpClientTesting()],
        });
        svc = TestBed.inject(EventsService);
        httpMock = TestBed.inject(HttpTestingController);
        hadEventSource = 'EventSource' in G;
        localStorage.clear();
        FakeEventSource.last = undefined;
    });

    afterEach(() => {
        httpMock.verify();
        if (!hadEventSource) clearEventSource();
        localStorage.clear();
    });

    it('errors immediately when EventSource is unavailable, so the caller can fall back to polling', () => {
        clearEventSource();
        let errored: unknown;
        let frames = 0;
        svc.stream().subscribe({ next: () => frames++, error: (e) => (errored = e) });
        expect(errored, 'the observable must error rather than go quiet').toBeInstanceOf(Error);
        // ⚠ Assert the GUARD's own message, not merely that something threw. Mutation-tested: deleting the
        // `typeof EventSource === 'undefined'` guard still errors — via the constructor's ReferenceError
        // caught by the try/catch — so an `toBeInstanceOf(Error)` assertion alone passed over a removed
        // guard. This test used to do exactly that.
        expect((errored as Error).message).toBe('EventSource unavailable');
        expect(frames).toBe(0);
    });

    it('projects a Signal frame onto the EventRow view', () => {
        setEventSource(FakeEventSource);
        const rows: EventRow[] = [];
        svc.stream().subscribe((r) => rows.push(r));
        FakeEventSource.last!.emit(
            JSON.stringify({
                signalId: 's-1',
                type: 'BATCH_COMMITTED',
                at: 1757500000000,
                source: { kind: 'pipeline', id: 'orders', rel: 'emits' },
                correlationId: 'corr-7',
                severity: 'warn',
                payload: { message: 'committed 3 files', pipeline: 'orders' },
            }),
        );
        expect(rows).toHaveLength(1);
        expect(rows[0].eventId).toBe('s-1');
        expect(rows[0].type).toBe('BATCH_COMMITTED');
        expect(rows[0].level, 'severity maps onto the level ladder').toBe('WARN');
        expect(rows[0].message).toBe('committed 3 files');
        expect(rows[0].pipeline).toBe('orders');
        expect(rows[0].correlationId).toBe('corr-7');
        expect(rows[0].source).toBe('pipeline/orders');
    });

    it('ignores a malformed frame instead of ending the tail', () => {
        setEventSource(FakeEventSource);
        const rows: EventRow[] = [];
        let errored = false;
        svc.stream().subscribe({ next: (r) => rows.push(r), error: () => (errored = true) });
        FakeEventSource.last!.emit('} not json {');
        expect(errored, 'one bad frame must not tear the stream down').toBe(false);
        FakeEventSource.last!.emit(
            JSON.stringify({
                signalId: 's-2',
                type: 'AUDIT',
                at: 1,
                source: { kind: 'system', id: 'system', rel: 'emits' },
                severity: 'info',
                payload: {},
            }),
        );
        expect(rows).toHaveLength(1);
    });

    it('errors on a transport failure and closes the source', () => {
        setEventSource(FakeEventSource);
        let errored = false;
        svc.stream().subscribe({ error: () => (errored = true) });
        FakeEventSource.last!.fail();
        expect(errored).toBe(true);
        expect(FakeEventSource.last!.closed, 'a failed stream must not leak its connection').toBe(true);
    });

    it('closes the source on unsubscribe', () => {
        setEventSource(FakeEventSource);
        const sub = svc.stream().subscribe();
        expect(FakeEventSource.last!.closed).toBe(false);
        sub.unsubscribe();
        expect(FakeEventSource.last!.closed).toBe(true);
    });

    it('sends the route filters as query params', () => {
        setEventSource(FakeEventSource);
        svc.stream({ type: 'AUDIT', correlationId: 'corr-7' }).subscribe();
        const url = FakeEventSource.last!.url;
        expect(url).toContain('type=AUDIT');
        expect(url).toContain('correlationId=corr-7');
    });

    it('SPACE-SCOPES the stream URL by hand, because EventSource skips the interceptor', () => {
        setEventSource(FakeEventSource);
        TestBed.inject(SpacesService).selectSpace('acme');
        svc.stream().subscribe();
        expect(FakeEventSource.last!.url).toBe(`${base}/spaces/acme/signals/stream`);
    });

    it('leaves the URL unscoped in a single-space deployment', () => {
        setEventSource(FakeEventSource);
        svc.stream().subscribe();
        expect(FakeEventSource.last!.url).toBe(`${base}/signals/stream`);
    });
});
