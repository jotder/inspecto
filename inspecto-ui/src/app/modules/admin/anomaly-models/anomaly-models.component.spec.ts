import { ChangeDetectionStrategy, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { AnomalyModelsService, AnomalyScorePreview } from 'app/inspecto/api/anomaly-models.service';
import { ComponentDef } from 'app/inspecto/api/components.service';
import { LensService } from 'app/inspecto/api/lens.service';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { AnomalyModelsComponent } from './anomaly-models.component';

const MODEL: ComponentDef = {
    type: 'anomaly-model',
    name: 'usage',
    ref: 'anomaly-model/usage',
    content: {
        id: 'usage',
        entityType: 'subscriber',
        window: 28,
        seasonality: 'weekday',
        elevatedThreshold: 60,
        highThreshold: 80,
        peers: { by: ['plan'] },
        features: [
            {
                id: 'data_mb',
                label: 'Data (MB)',
                dataset: 'cdr',
                key: 'msisdn',
                time: 't',
                measure: 'sum(mb)',
                weight: 1,
            },
        ],
    },
};

const PREVIEW: AnomalyScorePreview = {
    model: 'usage',
    entityType: 'subscriber',
    entityKey: 'masked:0123456789abcdef',
    keyMasked: true,
    found: true,
    periodStart: '2026-09-30',
    score: 90.5,
    band: 'high',
    raw: 7,
    elevatedThreshold: 60,
    highThreshold: 80,
    insufficientCount: 0,
    saved: true,
    features: [
        {
            feature: 'data_mb',
            label: 'Data (MB)',
            observed: 1000,
            baseline: { median: 100, mad: 10, points: 28 },
            peerBaseline: {
                cohort: 'POST',
                basis: 'POST',
                median: 100,
                mad: 10,
                size: 121,
                fellBack: false,
                insufficient: false,
            },
            cohortShift: 1,
            zPeer: 10,
            zSelf: 1.2,
            deviation: 10,
            direction: 'up',
            weight: 1,
            contribution: 1,
            share: 1,
            insufficient: false,
            reason: 'Data (MB) 1000 vs peers 100',
        },
    ],
};

function create(caps: { author: boolean; work: boolean }) {
    const preview = vi.fn(() => of(PREVIEW));
    const remove = vi.fn(() => of({}));
    const open = vi.fn(() => ({ afterClosed: () => of(false) }));
    TestBed.configureTestingModule({
        imports: [AnomalyModelsComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: AnomalyModelsService, useValue: { list: () => of([MODEL]), preview, remove } },
            {
                provide: LensService,
                useValue: {
                    canAuthorWorkbench: signal(caps.author),
                    canWorkIncidents: signal(caps.work),
                    canAuthorAlertRules: signal(false),
                },
            },
            { provide: MatDialog, useValue: { open } },
            { provide: ToastrService, useValue: { success: vi.fn(), info: vi.fn(), error: vi.fn() } },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: vi.fn(async () => true) } },
        ],
    });
    TestBed.overrideComponent(AnomalyModelsComponent, { set: { changeDetection: ChangeDetectionStrategy.Eager } });
    const fixture = TestBed.createComponent(AnomalyModelsComponent);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement, preview, remove, open };
}

const buttons = (el: HTMLElement, text: string) =>
    Array.from(el.querySelectorAll('button')).filter((b) => b.textContent?.trim() === text);

describe('AnomalyModelsComponent', () => {
    it('lists models, shows one in detail and previews an entity with its per-feature explanation', async () => {
        const { fixture, el, preview } = create({ author: true, work: true });
        (el.querySelector('nav li button') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(el.querySelector('h2')?.textContent).toContain('usage');
        expect(el.textContent).toContain('anomaly_scores_usage_latest');
        expect(el.textContent).toContain('plan');
        expect(buttons(el, 'New Anomaly Model').length).toBe(1);
        expect(buttons(el, 'Edit').length).toBe(1);

        const input = el.querySelector('app-anomaly-model-preview input') as HTMLInputElement;
        input.value = '966501234567';
        input.dispatchEvent(new Event('input'));
        (el.querySelector('app-anomaly-model-preview form') as HTMLFormElement).dispatchEvent(new Event('submit'));
        fixture.detectChanges();
        expect(preview).toHaveBeenCalledWith('usage', '966501234567', undefined);
        const box = el.querySelector('[data-testid="anomaly-preview"]')!;
        expect(box.textContent).toContain('High');
        expect(box.textContent).toContain('masked:0123456789abcdef');
        const cells = Array.from(box.querySelectorAll('tbody td')).map((td) => td.textContent?.trim());
        expect(cells).toContain('10.00'); // peer z
        expect(cells).toContain('1.20'); // self z
        expect(box.querySelector('svg[role="img"]')?.getAttribute('aria-label')).toContain('observed 1,000.00');
        expect(box.querySelector('svg circle')).toBeTruthy();
        await expectNoA11yViolations(el);
    });

    it('hides authoring without canAuthorWorkbench and the preview without canWorkIncidents', async () => {
        const { fixture, el } = create({ author: false, work: false });
        (el.querySelector('nav li button') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(buttons(el, 'New Anomaly Model').length).toBe(0);
        expect(buttons(el, 'Edit').length).toBe(0);
        expect(buttons(el, 'Delete').length).toBe(0);
        expect(el.querySelector('app-anomaly-model-preview')).toBeNull();
        await expectNoA11yViolations(el);
    });

    it('asks for a key before previewing', () => {
        const { fixture, el, preview } = create({ author: false, work: true });
        (el.querySelector('nav li button') as HTMLButtonElement).click();
        fixture.detectChanges();
        (el.querySelector('app-anomaly-model-preview form') as HTMLFormElement).dispatchEvent(new Event('submit'));
        fixture.detectChanges();
        expect(preview).not.toHaveBeenCalled();
        expect(el.querySelector('app-anomaly-model-preview [role="alert"]')?.textContent).toContain('entity key');
    });

    it('deletes after confirmation', async () => {
        const { fixture, el, remove } = create({ author: true, work: false });
        (el.querySelector('nav li button') as HTMLButtonElement).click();
        fixture.detectChanges();
        buttons(el, 'Delete')[0].click();
        await new Promise((r) => setTimeout(r, 0));
        expect(remove).toHaveBeenCalledWith('usage');
    });
});
