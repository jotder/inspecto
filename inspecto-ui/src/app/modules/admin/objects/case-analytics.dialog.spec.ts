import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA } from '@angular/material/dialog';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ObjectAnalytics, ObjectsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { GAMMA_CONFIG } from '@gamma/services/config/config.constants';
import { CaseAnalyticsDialog } from './case-analytics.dialog';

const HOUR = 3_600_000;

const analytics = (over: Partial<ObjectAnalytics> = {}): ObjectAnalytics => ({
    type: 'INCIDENT',
    total: 10,
    backlog: 4,
    byStatus: { IDENTIFIED: 2, RESOLVED: 3, ARCHIVED: 5 },
    byCategory: { 'Data quality': 6, Delivery: 4 },
    byPriority: { HIGH: 3, NONE: 7 },
    cycleTime: { count: 5, avgMs: 720 * HOUR, definition: 'created → closed (the terminal state…)' },
    mttr: { count: 8, avgMs: 2 * HOUR, definition: 'created → most recent RESOLVED transition…' },
    impact: { impactAmount: 0, recordsAffected: 0 },
    ...over,
});

async function create(a: ObjectAnalytics | null = analytics()) {
    TestBed.configureTestingModule({
        imports: [CaseAnalyticsDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { type: 'INCIDENT', typeLabel: 'Incident' } },
            { provide: ObjectsService, useValue: { analytics: vi.fn(() => of(a)) } },
            // The by-category chart's theme walks up to GAMMA_APP_CONFIG — stub it, as the grid specs do.
            { provide: GAMMA_CONFIG, useValue: { scheme: 'light', theme: 'theme-default' } },
        ],
    });
    const fixture = TestBed.createComponent(CaseAnalyticsDialog);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance };
}

const tile = (c: CaseAnalyticsDialog, label: string) => c.tiles().find((t) => t.label === label);

describe('CaseAnalyticsDialog (KPI report)', () => {
    /**
     * `INCIDENT-KPI-MTTR-1`. These two tiles are the point of the row: they look interchangeable and are
     * not. Time-to-close runs to the TERMINAL state — `ARCHIVED` for an Incident — so an Incident resolved
     * in two hours and archived a month later reports a month here. MTTR runs to the resolution.
     */
    it('reports MTTR separately from time-to-close, and they differ', async () => {
        const { c } = await create();

        expect(tile(c, 'MTTR')?.value).toBe('2h 0m');
        expect(tile(c, 'Avg time to close')?.value).not.toBe(tile(c, 'MTTR')?.value);
        expect(tile(c, 'Resolved')?.value).toBe('8'); // the MTTR denominator
        expect(tile(c, 'Closed')?.value).toBe('5'); // reached the terminal state
    });

    /**
     * ⚠ The tile now labelled `Closed` used to read `Resolved` while showing `cycleTime.count`, which
     * counts objects in their TERMINAL state. It was naming the wrong thing, and this asserts the fix
     * rather than merely the presence of a new tile.
     */
    it('does not label the terminal-state count as "Resolved"', async () => {
        const { c } = await create();
        expect(tile(c, 'Resolved')?.value).not.toBe(String(c.analytics()!.cycleTime.count));
    });

    it('publishes each KPI with the definition the server sent, so the number is not implied', async () => {
        const { fixture, c } = await create();
        expect(tile(c, 'MTTR')?.hint).toContain('RESOLVED');
        expect(tile(c, 'Avg time to close')?.hint).toContain('terminal');
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('most recent RESOLVED transition');
    });

    /** A deployment upgraded today has no resolution stamps yet — that must read as "no data", not 0ms. */
    it('shows an em-dash rather than a zero when nothing has a recorded resolution', async () => {
        const { c } = await create(analytics({ mttr: { count: 0, avgMs: 0, definition: 'x' } }));
        expect(tile(c, 'MTTR')?.value).toBe('—');
        expect(tile(c, 'Resolved')?.value).toBe('0');
    });

    /** An older server that does not send `mttr` at all must not blank the whole dialog. */
    it('degrades when the server sends no mttr block', async () => {
        const legacy = analytics();
        delete (legacy as Partial<ObjectAnalytics>).mttr;
        const { c } = await create(legacy);
        expect(tile(c, 'MTTR')?.value).toBe('—');
        expect(tile(c, 'Total')?.value).toBe('10');
    });

    it('has no accessibility violations', async () => {
        const { fixture } = await create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
