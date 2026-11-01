import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { ComponentsService } from '../api/components.service';
import { Prediction, PredictionKind, PredictionsService } from '../api/predictions.service';
import { expectNoA11yViolations } from '../testing/a11y';
import { PredictionPanelComponent } from './prediction-panel.component';

const BASE = {
    entityType: 'customer',
    entityKey: 'masked:0123456789abcdef',
    keyMasked: true,
    asOf: '2026-06-30 00:00:00',
    modelVersion: 'abc123def456',
    runId: 'r2',
    scoredAt: '2026-07-01 01:00:00',
};
const SEGMENT: Prediction = {
    ...BASE,
    kind: 'segmentation-model',
    model: 'rfm',
    segment: 'At risk',
    recencyScore: 1,
    frequencyScore: 2,
    monetaryScore: 3,
};
const CHURN: Prediction = { ...BASE, kind: 'churn-model', model: 'churn', score: 72.5, band: 'elevated' };
const FORECAST: Prediction = {
    ...BASE,
    kind: 'forecast-model',
    model: 'demand',
    points: [
        { status: 'ok', step: 1, bucket: '2026-07-01', forecast: 10, lower: 8, upper: 12, backtestMae: 1.5 },
        { status: 'ok', step: 2, bucket: '2026-07-02', forecast: 11, lower: 9, upper: 13, backtestMae: 1.5 },
    ],
};

const MODELS: Record<string, string[]> = {
    'segmentation-model': ['rfm'],
    'churn-model': ['churn', 'other'],
    'forecast-model': ['demand'],
};
const BY_MODEL: Record<string, Prediction> = { rfm: SEGMENT, churn: CHURN, demand: FORECAST };

function mount(api: Partial<PredictionsService>, components: Partial<ComponentsService>) {
    TestBed.configureTestingModule({
        imports: [PredictionPanelComponent],
        providers: [
            { provide: PredictionsService, useValue: api },
            { provide: ComponentsService, useValue: components },
        ],
    });
    const fixture = TestBed.createComponent(PredictionPanelComponent);
    fixture.componentRef.setInput('entityKey', 'cust-1');
    fixture.detectChanges();
    return fixture;
}

async function settle(fixture: ReturnType<typeof mount>) {
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
}

const lister = { list: (kind: string) => of((MODELS[kind] ?? []).map((name) => ({ name }))) } as never;

describe('PredictionPanelComponent', () => {
    it('shows one block per model with a prediction: the segment, the churn band and the forecast chart', async () => {
        const read = (_k: PredictionKind, model: string) =>
            BY_MODEL[model] ? of(BY_MODEL[model]) : throwError(() => ({ status: 404 }));
        const el = await settle(mount({ latest: read }, lister));
        const sections = el.querySelectorAll('section');
        expect(sections.length).toBe(3);
        expect(el.textContent).toContain('Customer Segment');
        expect(el.textContent).toContain('At risk');
        expect(el.textContent).toContain('Churn Score');
        expect(el.textContent).toContain('Elevated');
        expect(el.querySelector('inspecto-forecast-chart')).not.toBeNull();
        expect(el.textContent).toContain('Backtest');
        expect(el.textContent).toContain('masked:0123456789abcdef');
        await expectNoA11yViolations(el);
    });

    it('says not enough history for an insufficient churn score instead of a 0', async () => {
        const read = (k: PredictionKind) =>
            k === 'churn-model' ? of({ ...CHURN, score: null, band: 'insufficient' }) : throwError(() => ({}));
        const el = await settle(mount({ latest: read }, lister));
        expect(el.textContent).toContain('Not enough history');
        expect(el.textContent).not.toContain('Elevated');
    });

    it('renders nothing when the module is absent or nothing is predicted', async () => {
        const absent = { list: () => throwError(() => ({ status: 503 })) } as never;
        const el = await settle(mount({ latest: () => of(CHURN) }, absent));
        expect(el.querySelector('section')).toBeNull();
    });
});
