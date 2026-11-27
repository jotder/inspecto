import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { EventsService, SignalBoard } from 'app/inspecto/api';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { SignalBoardComponent } from './signal-board.component';

const BOARD: SignalBoard = {
    hours: 24,
    since: '2026-11-27T00:00:00Z',
    until: '2026-11-28T00:00:00Z',
    capped: false,
    guardsCapped: true,
    missedTracked: false,
    types: [
        {
            type: 'order.placed',
            title: 'Order placed',
            poster: 'engine',
            source: 'core',
            catalogued: true,
            family: false,
            posted: 3,
            lastPosted: '2026-11-27T10:00:00Z',
            postedBy: ['job:shop'],
            postedByTotal: 1,
            postedByTruncated: false,
            listeners: [
                { job: 'fulfil', key: 'on_signal', value: 'order.placed', enabled: true },
                { job: 'audit', key: 'on_signal', value: 'order.*', enabled: false },
            ],
            listenersTotal: 2,
            listenersTruncated: false,
            runsStarted: 2,
            guardsSaidNo: 1,
            refused: 0,
            chainCuts: 0,
            missed: null,
        },
    ],
    typesTotal: 1,
    typesTruncated: false,
    unheard: [{ type: 'loop.ping', posted: 2, lastPosted: '2026-11-27T09:00:00Z', postedBy: ['job:x'], postedByTotal: 1, postedByTruncated: false }],
    unheardTotal: 1,
    unheardTruncated: false,
    unposted: [{ job: 'on_typo', onSignal: 'order.plcaed', enabled: true }],
    unpostedTotal: 1,
    unpostedTruncated: false,
};

async function create(signalBoard: EventsService['signalBoard']) {
    TestBed.configureTestingModule({
        imports: [SignalBoardComponent],
        providers: [
            provideNoopAnimations(),
            { provide: EventsService, useValue: { signalBoard } },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    await TestBed.compileComponents(); // data-table @defer block
    const fixture = TestBed.createComponent(SignalBoardComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
}

describe('SignalBoardComponent', () => {
    it('reads the board for the chosen window and shows both findings first', async () => {
        const signalBoard = vi.fn(() => of(BOARD));
        const fixture = await create(signalBoard);
        const el: HTMLElement = fixture.nativeElement;
        expect(signalBoard).toHaveBeenCalledWith(24);
        const headings = Array.from(el.querySelectorAll('h2')).map((h) => h.textContent?.trim());
        expect(headings).toEqual(['Posted, but no Job hears it', 'Listening, but nothing posts it', 'Signal Types']);
        expect(el.textContent).toContain('Some counts are lower bounds');
        expect(el.textContent).toContain("Guards that said no are read from each Job's recent Runs");

        fixture.componentInstance.setHours(168);
        expect(signalBoard).toHaveBeenLastCalledWith(168);
    });

    it('marks a disabled listener and shows Missed only once it is tracked', async () => {
        const fixture = await create(vi.fn(() => of(BOARD)));
        const c = fixture.componentInstance;
        const listeners = c.typeColumns().find((col) => col.headerName === 'Listening Jobs')!;
        const getter = listeners.valueGetter as (p: { data: unknown }) => string;
        expect(getter({ data: BOARD.types[0] })).toBe('fulfil, audit (off)');
        expect(c.typeColumns().some((col) => col.headerName === 'Missed')).toBe(false);

        c.board.set({ ...BOARD, missedTracked: true });
        expect(c.typeColumns().some((col) => col.headerName === 'Missed')).toBe(true);
    });

    it('says why when the board cannot be read', async () => {
        const fixture = await create(vi.fn(() => throwError(() => ({ status: 403 }))));
        expect(fixture.nativeElement.textContent).toContain('needs the right to author Jobs');
    });

    it('renders with no a11y violations', async () => {
        const fixture = await create(vi.fn(() => of(BOARD)));
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
