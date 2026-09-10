import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, Subject, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { EventFilter, EventRow, EventsService, SavedEventView, SessionService } from 'app/inspecto/api';
import { AiStatusDialog } from 'app/inspecto/ai-assist/ai-status.dialog';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { EventsComponent } from './events.component';

const EVENT: EventRow = {
    eventId: 'evt-1',
    ts: 1,
    timestamp: '2026-01-01T00:00:00Z',
    level: 'ERROR',
    type: 'BATCH_FAILED',
    source: 'engine',
    pipeline: 'cdr_ingest',
    correlationId: 'corr-1',
    message: 'BATCH_FAILED on cdr_ingest',
    attributes: {},
};

const VIEW: SavedEventView = {
    name: 'errors',
    filters: { level: 'ERROR', pipeline: 'cdr_ingest' },
    createdAt: 1,
};

async function create(
    overrides: Partial<Record<keyof EventsService, unknown>> = {},
    dialog: unknown = {},
    eventsEnabled = true,
) {
    const search = vi.fn((_f: EventFilter) => of([EVENT]));
    const api = {
        search,
        views: () => of([VIEW]),
        saveView: vi.fn(() => of(VIEW)),
        deleteView: vi.fn(() => of({})),
        exportCsv: () => of('timestamp,level\n'),
        // Default: the stream is unavailable — which is exactly what the real service reports under jsdom —
        // so the live tail falls back to polling. Tests of the streaming path override this.
        stream: vi.fn(() => throwError(() => new Error('EventSource unavailable'))),
        ...overrides,
    } as unknown as EventsService;
    TestBed.configureTestingModule({
        imports: [EventsComponent],
        providers: [
            provideNoopAnimations(),
            { provide: EventsService, useValue: api },
            { provide: MatDialog, useValue: dialog },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: () => Promise.resolve(true) } },
            { provide: ToastrService, useValue: { success: vi.fn(), error: vi.fn() } },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    // The data-table injects the real MatDialog, so a stub must be OVERRIDDEN, not just provided.
    TestBed.overrideProvider(MatDialog, { useValue: dialog });
    await TestBed.compileComponents(); // data-table @defer block
    // EDG-01 cell 6: SessionService.eventsEnabled defaults to FALSE (the Personal/absent-module state),
    // where this screen renders the explained alert and never calls the API. These specs exercise the
    // INSTALLED path, so arm the flag before the component reads it in ngOnInit.
    // ⚠ AFTER overrideProvider, never before: TestBed.inject() INSTANTIATES the test module, and Angular
    // then refuses any further overrideProvider ("Cannot override provider when the test module has
    // already been instantiated") — which failed all 9 tests in this file, not just the events ones.
    TestBed.inject(SessionService).eventsEnabled.set(eventsEnabled);
    const fixture = TestBed.createComponent(EventsComponent);
    fixture.detectChanges(); // ngOnInit → load() + loadViews()
    return { fixture, api };
}

describe('EventsComponent', () => {
    it('asks "what led to this" by correlation id, preferring the causal chain', async () => {
        const open = vi.fn();
        const { fixture } = await create({}, { open });
        fixture.componentInstance.actions[1].onClick(EVENT);
        expect(open).toHaveBeenCalledWith(AiStatusDialog, {
            data: { label: 'corr-1', pipelineId: 'cdr_ingest', correlationId: 'corr-1' },
        });
    });

    it('hides the status action on a row with neither a correlation id nor a pipeline', async () => {
        const { fixture } = await create();
        const action = fixture.componentInstance.actions[1];
        expect(action.visible?.({ ...EVENT, correlationId: null, pipeline: null })).toBe(false);
        expect(action.visible?.({ ...EVENT, correlationId: null })).toBe(true);
    });

    it('loads events and saved views on init', async () => {
        const { fixture } = await create();
        const c = fixture.componentInstance;
        expect(c.events()).toEqual([EVENT]);
        expect(c.views()).toEqual([VIEW]);
        expect(c.loading()).toBe(false);
    });

    it('sends only the set filters in the search query', async () => {
        const { fixture, api } = await create();
        const c = fixture.componentInstance;
        c.fLevel = 'WARN';
        c.fq = '  batch  ';
        c.load();
        expect(api.search).toHaveBeenLastCalledWith({
            level: 'WARN',
            type: undefined,
            pipeline: undefined,
            correlationId: undefined,
            q: 'batch',
            limit: 100,
        });
    });

    it('applies a saved view back onto the filter toolbar and re-queries', async () => {
        const { fixture, api } = await create();
        const c = fixture.componentInstance;
        c.applyView('errors');
        expect(c.fLevel).toBe('ERROR');
        expect(c.fPipeline).toBe('cdr_ingest');
        expect(api.search).toHaveBeenLastCalledWith(
            expect.objectContaining({ level: 'ERROR', pipeline: 'cdr_ingest' }),
        );
    });

    it('clearing the correlation chip re-runs the query without it', async () => {
        const { fixture, api } = await create();
        const c = fixture.componentInstance;
        c.fCorrelation.set('corr-1');
        c.clearCorrelation();
        expect(c.fCorrelation()).toBe('');
        expect(api.search).toHaveBeenLastCalledWith(expect.objectContaining({ correlationId: undefined }));
    });

    it('degrades to an empty grid + toast when the search fails', async () => {
        const { fixture } = await create({ search: () => throwError(() => ({ status: 500 })) });
        const c = fixture.componentInstance;
        expect(c.events()).toEqual([]);
        expect(c.loading()).toBe(false);
    });

    it('falls back to polling at the selected cadence when the stream is unavailable, and re-arms on a cadence change', async () => {
        const { fixture, api } = await create();
        const c = fixture.componentInstance;
        vi.useFakeTimers();
        try {
            (api.search as ReturnType<typeof vi.fn>).mockClear();

            c.liveSeconds = 2;
            c.toggleLive(true);
            // The stream carries no replay, so arming re-runs the query once before tailing forward.
            expect(api.search).toHaveBeenCalledTimes(1);
            expect(c.streaming(), 'the stream errored, so the tail is polling').toBe(false);
            vi.advanceTimersByTime(2000);
            expect(api.search).toHaveBeenCalledTimes(2); // first poll tick at 2s

            // slow it down — the old 2s timer is torn down, no poll until the new 10s elapses
            c.liveSeconds = 10;
            c.restartLiveTail();
            expect(api.search).toHaveBeenCalledTimes(3); // the re-arm's own initial fetch
            vi.advanceTimersByTime(2000);
            expect(api.search).toHaveBeenCalledTimes(3);
            vi.advanceTimersByTime(8000);
            expect(api.search).toHaveBeenCalledTimes(4);

            c.toggleLive(false); // off → no further polling
            vi.advanceTimersByTime(30000);
            expect(api.search).toHaveBeenCalledTimes(4);
        } finally {
            vi.useRealTimers();
        }
    });

    it('prefers the signals stream, prepends each frame newest-first, and arms NO poll', async () => {
        const frames = new Subject<EventRow>();
        const { fixture, api } = await create({ stream: vi.fn(() => frames.asObservable()) });
        const c = fixture.componentInstance;
        vi.useFakeTimers();
        try {
            (api.search as ReturnType<typeof vi.fn>).mockClear();
            c.liveSeconds = 2;
            c.toggleLive(true);
            expect(c.streaming(), 'the stream connected').toBe(true);
            expect(api.search).toHaveBeenCalledTimes(1); // the no-replay initial fetch, once

            frames.next({ ...EVENT, eventId: 'evt-2', message: 'newer' });
            expect(c.events().map((r) => r.eventId)).toEqual(['evt-2', 'evt-1']);

            // ⚠ No poll may be armed while streaming, or the pane would fetch on top of the stream.
            vi.advanceTimersByTime(30000);
            expect(api.search).toHaveBeenCalledTimes(1);
        } finally {
            vi.useRealTimers();
        }
    });

    it('ignores a streamed row it already holds, so a re-run overlapping the tail cannot double a row', async () => {
        const frames = new Subject<EventRow>();
        const { fixture } = await create({ stream: vi.fn(() => frames.asObservable()) });
        const c = fixture.componentInstance;
        c.toggleLive(true); // arm the tail — without this the frame goes nowhere and the test proves nothing
        frames.next({ ...EVENT }); // same eventId as the row the initial fetch loaded
        expect(c.events()).toHaveLength(1);
    });

    it('drops a streamed row below the toolbar minimum level', async () => {
        const frames = new Subject<EventRow>();
        const { fixture } = await create({ stream: vi.fn(() => frames.asObservable()) });
        const c = fixture.componentInstance;
        c.fLevel = 'ERROR';
        c.toggleLive(true); // arm the tail first, or nothing is subscribed to the frames
        frames.next({ ...EVENT, eventId: 'evt-info', level: 'INFO' });
        expect(
            c.events().map((r) => r.eventId),
            'INFO is below the ERROR minimum',
        ).toEqual(['evt-1']);
        frames.next({ ...EVENT, eventId: 'evt-err', level: 'ERROR' });
        expect(c.events().map((r) => r.eventId)).toEqual(['evt-err', 'evt-1']);
    });

    it('calls NOTHING when the events module is absent — no views fetch, no stream, no poll', async () => {
        const stream = vi.fn(() => new Subject<EventRow>().asObservable());
        const views = vi.fn(() => of([VIEW]));
        const { fixture, api } = await create({ stream, views }, {}, false);
        const c = fixture.componentInstance;
        // The pane explains the absence where the grid would be; every call it could make is a guaranteed
        // 503, so the rule in this file is "never call, never toast".
        expect(views, 'saved views are part of the same absent module').not.toHaveBeenCalled();
        vi.useFakeTimers();
        try {
            (api.search as ReturnType<typeof vi.fn>).mockClear();
            c.liveSeconds = 2;
            c.toggleLive(true);
            expect(stream, 'never call when the module is absent').not.toHaveBeenCalled();
            expect(c.streaming()).toBe(false);
            vi.advanceTimersByTime(30000);
            expect(api.search).not.toHaveBeenCalled();
        } finally {
            vi.useRealTimers();
        }
    });

    it('falls back to polling when the stream errors mid-tail', async () => {
        const frames = new Subject<EventRow>();
        const { fixture, api } = await create({ stream: vi.fn(() => frames.asObservable()) });
        const c = fixture.componentInstance;
        vi.useFakeTimers();
        try {
            c.liveSeconds = 2;
            c.toggleLive(true);
            expect(c.streaming()).toBe(true);
            (api.search as ReturnType<typeof vi.fn>).mockClear();

            frames.error(new Error('signal stream closed'));
            expect(c.streaming(), 'a dropped stream must not look live').toBe(false);

            vi.advanceTimersByTime(2000);
            expect(api.search, 'the poll took over').toHaveBeenCalledTimes(1);
        } finally {
            vi.useRealTimers();
        }
    });

    it('renders the empty state with no a11y violations', async () => {
        const { fixture } = await create({ search: () => of([]) });
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('No events match the current filters.');
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
