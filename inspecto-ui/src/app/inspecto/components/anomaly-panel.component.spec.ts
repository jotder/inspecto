import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { AnomalyScore, AnomalyScoresService } from '../api/anomaly-scores.service';
import { ComponentsService } from '../api/components.service';
import { expectNoA11yViolations } from '../testing/a11y';
import { AnomalyPanelComponent } from './anomaly-panel.component';

const SCORE: AnomalyScore = {
    model: 'usage',
    entityType: 'subscriber',
    entityKey: '96650001234',
    keyMasked: false,
    score: 91,
    band: 'high',
    elevatedThreshold: 60,
    highThreshold: 80,
    periodStart: '2026-10-09',
    modelVersion: 'abc123def456',
    runId: 'r3',
    scoredAt: '2026-10-10 01:00:00',
    features: [
        { feature: 'calls', label: 'Calls', observed: 12, baseline: { median: 10 }, contribution: 0.05 },
        {
            feature: 'data',
            label: 'Data MB',
            observed: 4812,
            baseline: { median: 310 },
            contribution: 0.8,
            reason: 'data MB 4 812 vs a usual 310 (Tuesdays, 4 weeks) — 14.5 MADs above',
        },
        { feature: 'sms', label: 'SMS', observed: 3, baseline: { median: 2 }, contribution: 0.1 },
        { feature: 'dest', label: 'Distinct numbers', observed: 40, baseline: { median: 20 }, contribution: 0.05 },
    ],
    history: [
        { periodStart: 'd3', score: 91, band: 'high', runId: 'r3' },
        { periodStart: 'd2', score: 40, band: 'normal', runId: 'r2' },
        { periodStart: 'd1', score: 20, band: 'normal', runId: 'r1' },
    ],
};

function mount(api: Partial<AnomalyScoresService>, components: Partial<ComponentsService>, model: string | null) {
    TestBed.configureTestingModule({
        imports: [AnomalyPanelComponent],
        providers: [
            { provide: AnomalyScoresService, useValue: api },
            { provide: ComponentsService, useValue: components },
        ],
    });
    const fixture = TestBed.createComponent(AnomalyPanelComponent);
    fixture.componentRef.setInput('entityKey', '96650001234');
    fixture.componentRef.setInput('model', model);
    fixture.detectChanges();
    return fixture;
}

async function settle(fixture: ReturnType<typeof mount>) {
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
}

describe('AnomalyPanelComponent', () => {
    it('renders the band, the top 3 features by contribution with the reason, and a sparkline', async () => {
        const el = await settle(mount({ latest: () => of(SCORE) }, {}, 'usage'));
        expect(el.textContent).toContain('Anomaly Score');
        expect(el.textContent).toContain('High');
        const items = Array.from(el.querySelectorAll('li')).map((l) => l.textContent ?? '');
        expect(items.length).toBe(3);
        expect(items[0]).toContain('Data MB');
        expect(items[0]).toContain('14.5 MADs above');
        expect(items[0]).toContain('observed 4812');
        expect(el.querySelector('svg[role="img"]')?.getAttribute('aria-label')).toContain('last 3 runs');
        await expectNoA11yViolations(el);
    });

    it('shows every feature on demand and reveals a raw key only when it was not masked', async () => {
        const fixture = mount({ latest: () => of(SCORE) }, {}, 'usage');
        const el = await settle(fixture);
        expect(el.textContent).not.toContain('96650001234');
        const buttons = Array.from(el.querySelectorAll('button'));
        buttons.find((b) => b.textContent?.includes('Reveal key'))!.click();
        buttons.find((b) => b.textContent?.includes('Show all 4'))!.click();
        fixture.detectChanges();
        expect(el.textContent).toContain('96650001234');
        expect(el.querySelectorAll('li').length).toBe(4);
    });

    it('shows a masked token with no reveal affordance', async () => {
        const masked = { ...SCORE, entityKey: 'masked:0123456789abcdef', keyMasked: true };
        const el = await settle(mount({ latest: () => of(masked) }, {}, 'usage'));
        expect(el.textContent).toContain('masked:0123456789abcdef');
        expect(el.textContent).not.toContain('Reveal key');
    });

    it('without a model, shows one block per Anomaly Model that has a score', async () => {
        const el = await settle(
            mount(
                {
                    latest: (m: string) => (m === 'usage' ? of(SCORE) : throwError(() => ({ status: 404 }))),
                },
                {
                    list: () =>
                        of([
                            { type: 'anomaly-model', name: 'usage', ref: 'anomaly-model/usage', content: {} },
                            { type: 'anomaly-model', name: 'topups', ref: 'anomaly-model/topups', content: {} },
                        ]),
                },
                null,
            ),
        );
        expect(el.querySelectorAll('section').length).toBe(1);
        expect(el.querySelector('section')?.getAttribute('aria-label')).toBe('Anomaly Score, usage');
    });

    it('renders nothing when the module is absent (503) or there is no score (404)', async () => {
        const absent = await settle(
            mount(
                {},
                { list: () => throwError(() => ({ status: 503, error: { code: 'CAPABILITY_UNAVAILABLE' } })) },
                null,
            ),
        );
        expect(absent.querySelector('section')).toBeNull();
        expect(absent.textContent?.trim()).toBe('');
    });

    it('renders nothing for a 404 on a named model', async () => {
        const el = await settle(mount({ latest: () => throwError(() => ({ status: 404 })) }, {}, 'usage'));
        expect(el.querySelector('section')).toBeNull();
    });
});
