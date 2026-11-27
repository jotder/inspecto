import { ChangeDetectionStrategy, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { BriefNarration, BriefProfile, InsightsService } from 'app/inspecto/api/insights.service';
import { SessionService } from 'app/inspecto/auth/session.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { getViz } from 'app/inspecto/viz';
import { getVizComponentLoader } from 'app/inspecto/viz/viz-components';
import '../widgets/widget.kind';
import { InsightBriefWidgetComponent } from './insight-brief-widget.component';

const PROFILE: BriefProfile = {
    profile: 'sales',
    runId: 'r1',
    period: '2026-11-17',
    stale: false,
    lines: [
        { insightId: 'i1', band: 'high', score: 90, sentence: 'amount for region = North was 400', type: 'spike' },
        { insightId: 'i2', band: 'elevated', score: 50, sentence: 'amount for region = South was 400', type: 'spike' },
    ],
};

const NARRATED: BriefNarration = {
    brief: { profiles: [PROFILE] },
    narration: {
        mode: 'model',
        profiles: [
            {
                profile: 'sales',
                runId: 'r1',
                modelBacked: true,
                repaired: false,
                sentences: [
                    { n: 1, text: '[1] Amount rose in North.', cites: ['i1'], source: 'model' },
                    { n: 2, text: '[2] amount for region = South was 400.', cites: ['i2'], source: 'template' },
                ],
            },
        ],
    },
};

interface Api {
    brief?: ReturnType<typeof vi.fn>;
    narrate?: ReturnType<typeof vi.fn>;
}

function create(api: Api, features: Record<string, boolean> = { insights: true, agent: true }) {
    TestBed.configureTestingModule({
        imports: [InsightBriefWidgetComponent],
        providers: [
            provideRouter([]),
            { provide: InsightsService, useValue: { brief: api.brief ?? vi.fn(() => of({ profiles: [PROFILE] })), narrate: api.narrate } },
            { provide: SessionService, useValue: { features: signal(features) } },
        ],
    });
    TestBed.overrideComponent(InsightBriefWidgetComponent, {
        set: { changeDetection: ChangeDetectionStrategy.Eager },
    });
    const fixture = TestBed.createComponent(InsightBriefWidgetComponent);
    fixture.componentRef.setInput('viewId', 'sales');
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement };
}

const narrateButton = (el: HTMLElement) => el.querySelector<HTMLButtonElement>('[data-testid="narrate"]');

describe('InsightBriefWidgetComponent (Visualization Type insight-brief)', () => {
    it('is a registered view-bound Visualization Type over an Insight Profile, with a lazy component', () => {
        const p = getViz('insight-brief');
        expect(p?.meta.viewKind).toBe('insight-profile');
        expect(p?.render).toEqual({ kind: 'component', componentKey: 'insight-brief' });
        expect(getVizComponentLoader('insight-brief')).toBeTruthy();
    });

    it('renders the templated Brief: band as a word, the sentence, a link to the evidence', async () => {
        const brief = vi.fn(() => of({ profiles: [PROFILE] }));
        const { el } = create({ brief });
        expect(brief).toHaveBeenCalledWith(['sales']);
        const rows = Array.from(el.querySelectorAll('[data-testid="brief-line"]'));
        expect(rows.length).toBe(2);
        expect(rows[0].textContent).toContain('High');
        expect(rows[0].textContent).toContain('amount for region = North was 400');
        expect(rows[1].textContent).toContain('Elevated');
        expect(rows[0].querySelector('a')!.getAttribute('href')).toBe('/studio/insights?profile=sales&insight=i1');
        expect(el.querySelector('[data-testid="brief-narration"]')).toBeNull();
        await expectNoA11yViolations(el);
    });

    it('Narrate shows the paragraph above the lines, marks a template sentence per sentence and holds it in the tile', async () => {
        const narrate = vi.fn(() => of(NARRATED));
        const { fixture, el } = create({ narrate });
        narrateButton(el)!.click();
        fixture.detectChanges();
        expect(narrate).toHaveBeenCalledWith(['sales']);
        const para = el.querySelector('[data-testid="brief-narration"]')!;
        expect(para).not.toBeNull();
        expect(para.compareDocumentPosition(el.querySelector('[data-testid="brief-line"]')!)).toBe(
            Node.DOCUMENT_POSITION_FOLLOWING,
        );
        const sentences = Array.from(para.querySelectorAll('[data-testid="narrated-sentence"]'));
        expect(sentences[0].textContent).toContain('[1] Amount rose in North.');
        expect(sentences[0].textContent).not.toContain('Template');
        expect(sentences[1].textContent).toContain('Template');
        expect(para.textContent).toContain('checked against the Insights');
        await expectNoA11yViolations(el);
    });

    it('never interprets markdown or HTML in a narrated sentence', () => {
        const evil: BriefNarration = structuredClone(NARRATED);
        evil.narration.profiles[0].sentences[0].text = '[1] <b>bold</b> **x** <img src=x onerror=alert(1)>';
        const { fixture, el } = create({ narrate: vi.fn(() => of(evil)) });
        narrateButton(el)!.click();
        fixture.detectChanges();
        const s = el.querySelector('[data-testid="narrated-sentence"]')!;
        expect(s.querySelector('b, img')).toBeNull();
        expect(s.textContent).toContain('<b>bold</b> **x**');
    });

    it('with no model the Brief says so and keeps its own sentences', () => {
        const none: BriefNarration = structuredClone(NARRATED);
        none.narration.mode = 'templated';
        none.narration.profiles[0].modelBacked = false;
        none.narration.profiles[0].reason = 'no model';
        none.narration.profiles[0].sentences.forEach((s) => (s.source = 'template'));
        const { fixture, el } = create({ narrate: vi.fn(() => of(none)) });
        narrateButton(el)!.click();
        fixture.detectChanges();
        expect(el.textContent).toContain('No model is available');
        expect(el.querySelectorAll('[data-testid="brief-line"]').length).toBe(2);
    });

    it('the Narrate button is not rendered without AI Assist in /bootstrap, and the templated Brief still is', () => {
        const { el } = create({}, { insights: true });
        expect(narrateButton(el)).toBeNull();
        expect(el.querySelectorAll('[data-testid="brief-line"]').length).toBe(2);
    });

    it('a 503 from Narrate is an explained in-place line and removes the button, not a toast', () => {
        const { fixture, el } = create({ narrate: vi.fn(() => throwError(() => ({ status: 503 }))) });
        narrateButton(el)!.click();
        fixture.detectChanges();
        expect(el.querySelector('[role="alert"]')!.textContent).toContain('AI Assist is not available');
        expect(narrateButton(el)).toBeNull();
        expect(el.querySelectorAll('[data-testid="brief-line"]').length).toBe(2);
    });

    it('a stale note and an empty Brief are shown; a Profile the viewer may not see is the empty state', async () => {
        const stale = { ...PROFILE, stale: true, note: 'Last scan was 9 days ago' };
        const a = create({ brief: vi.fn(() => of({ profiles: [stale] })) });
        expect(a.el.textContent).toContain('Last scan was 9 days ago');
        TestBed.resetTestingModule();

        const quiet = { ...PROFILE, lines: [], summary: 'Nothing unusual in 2026-11-17' };
        const b = create({ brief: vi.fn(() => of({ profiles: [quiet] })) });
        expect(b.el.textContent).toContain('Nothing unusual in 2026-11-17');
        expect(narrateButton(b.el)).toBeNull();
        TestBed.resetTestingModule();

        const c = create({ brief: vi.fn(() => of({ profiles: [] })) });
        expect(c.el.textContent).toContain('No Insights yet');
        await expectNoA11yViolations(c.el);
    });

    it('a 404 names the insight.scan Job; another failure says so', () => {
        const a = create({ brief: vi.fn(() => throwError(() => ({ status: 404 }))) });
        expect(a.el.querySelector('[role="alert"]')).toBeNull();
        expect(a.el.textContent).toContain('insight.scan');
        TestBed.resetTestingModule();
        const b = create({ brief: vi.fn(() => throwError(() => ({ status: 500, error: { message: 'boom' } }))) });
        expect(b.el.querySelector('[role="alert"]')).not.toBeNull();
    });
});
