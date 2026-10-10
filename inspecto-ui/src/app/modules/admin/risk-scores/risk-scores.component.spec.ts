import { ChangeDetectionStrategy } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ToastrService } from 'ngx-toastr';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ComponentDef, ComponentsService } from 'app/inspecto/api/components.service';
import { RiskScoresService } from 'app/inspecto/api/risk-scores.service';
import { AnomalyScoresService } from 'app/inspecto/api/anomaly-scores.service';
import { PendingChange, PendingChangesService } from 'app/inspecto/api/pending-changes.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { RiskScoresComponent } from './risk-scores.component';

const MODEL: ComponentDef = {
    type: 'risk-score',
    name: 'sim_box',
    ref: 'risk-score/sim_box',
    content: {
        id: 'sim_box',
        entityType: 'msisdn',
        highThreshold: 70,
        factors: [{ id: 'calls', label: 'Short calls', dataset: 'cdr', key: 'a_number', measure: 'count', weight: 3 }],
    },
};
const BROKEN: ComponentDef = {
    type: 'risk-score',
    name: 'broken',
    ref: 'risk-score/broken',
    content: { id: 'broken' },
};

function create(list: () => unknown, pending: unknown[] = []) {
    const latest = vi.fn(() =>
        of({
            model: 'sim_box',
            entityType: 'msisdn',
            entityKey: '966501234567',
            score: 80,
            high: true,
            highThreshold: 70,
            modelVersion: 'v',
            runId: 'r',
            scoredAt: 't',
            factors: [],
        }),
    );
    const preview = vi.fn(() =>
        of({
            model: 'sim_box',
            entityType: 'msisdn',
            entityKey: 'masked:0123456789abcdef',
            keyMasked: true,
            found: true,
            score: 42,
            high: false,
            highThreshold: 70,
            saved: true,
            factors: [
                {
                    indicator: 'calls',
                    label: 'Short calls',
                    value: 14,
                    missing: false,
                    weight: 3,
                    capped: false,
                    contribution: 42,
                    evidence: [],
                },
            ],
        }),
    );
    const open = vi.fn(() => ({ afterClosed: () => of(false) }));
    // No Anomaly Score for the entity: the anomaly panel beside the Risk Score renders nothing.
    const anomalyLatest = vi.fn(() => throwError(() => ({ status: 404 })));
    TestBed.configureTestingModule({
        imports: [RiskScoresComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: ComponentsService, useValue: { list: vi.fn(list) } },
            { provide: RiskScoresService, useValue: { latest, preview } },
            { provide: AnomalyScoresService, useValue: { latest: anomalyLatest } },
            { provide: MatDialog, useValue: { open } },
            { provide: ToastrService, useValue: {} },
            {
                provide: PendingChangesService,
                useValue: { list: () => of({ items: pending, total: pending.length, truncated: false }) },
            },
        ],
    });
    TestBed.overrideComponent(RiskScoresComponent, { set: { changeDetection: ChangeDetectionStrategy.Eager } });
    const fixture = TestBed.createComponent(RiskScoresComponent);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement, latest, preview, open, anomalyLatest };
}

describe('RiskScoresComponent', () => {
    it('lists models, shows one in detail and looks up a masked entity score', async () => {
        const { fixture, el, latest, open, anomalyLatest } = create(() => of([MODEL, BROKEN]));
        const items = Array.from(el.querySelectorAll('nav li button')) as HTMLButtonElement[];
        expect(items.map((b) => b.textContent)).toEqual([
            expect.stringContaining('sim_box'),
            expect.stringContaining('broken'),
        ]);
        // the factor-less model is flagged, the valid one is not
        expect(items[0].textContent).not.toContain('Invalid');
        expect(items[1].textContent).toContain('Invalid');

        items[0].click();
        fixture.detectChanges();
        expect(el.querySelector('h2')?.textContent).toContain('sim_box');
        expect(el.querySelector('tbody')?.textContent).toContain('Short calls');
        expect(el.textContent).toContain('risk_scores_sim_box_latest');
        // no lookup before the author submits a key
        expect(latest).not.toHaveBeenCalled();

        const input = el.querySelector('form input') as HTMLInputElement;
        input.value = '966501234567';
        input.dispatchEvent(new Event('input'));
        (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
        fixture.detectChanges();
        expect(latest).toHaveBeenCalledWith('sim_box', '966501234567');
        const panel = el.querySelector('inspecto-risk-score-panel section');
        expect(panel?.getAttribute('aria-label')).toBe('Risk Score of ••••••••4567');
        // the anomaly panel looks the same entity up in every Anomaly Model, and hides on a 404
        expect(anomalyLatest).toHaveBeenCalledWith(expect.any(String), '966501234567');
        expect(el.querySelector('inspecto-anomaly-panel section')).toBeNull();

        (
            Array.from(el.querySelectorAll('section button')).find((b) =>
                b.textContent?.includes('History'),
            ) as HTMLButtonElement
        ).click();
        expect(open).toHaveBeenCalledWith(expect.anything(), {
            data: { type: 'risk-score', id: 'sim_box', label: 'sim_box' },
        });
        await expectNoA11yViolations(el);
    });

    it('previews a saved model for one entity without loading the stored score', async () => {
        const { fixture, el, latest, preview } = create(() => of([MODEL]));
        (el.querySelector('nav li button') as HTMLButtonElement).click();
        fixture.detectChanges();
        const input = el.querySelector('form input') as HTMLInputElement;
        input.value = '966501234567';
        input.dispatchEvent(new Event('input'));
        (
            Array.from(el.querySelectorAll('form button')).find((b) =>
                b.textContent?.includes('Preview'),
            ) as HTMLButtonElement
        ).click();
        fixture.detectChanges();
        expect(preview).toHaveBeenCalledWith('sim_box', '966501234567');
        expect(latest).not.toHaveBeenCalled();
        const panel = el.querySelector('inspecto-risk-score-panel section');
        expect(panel?.textContent).toContain('42');
        expect(panel?.textContent).toContain('preview, not saved');
        expect(panel?.textContent).toContain('Short calls');
        expect(panel?.textContent).not.toContain('966501234567');
        await expectNoA11yViolations(el);
    });

    it('shows the empty state with no models', async () => {
        const { el } = create(() => of([]));
        expect(el.querySelector('inspecto-empty-state')?.textContent).toContain('No Risk Scores yet');
        await expectNoA11yViolations(el);
    });

    it('shows a retryable error when the list fails', () => {
        const { el } = create(() => throwError(() => ({ status: 500 })));
        expect(el.querySelector('[role="alert"]')?.textContent).toContain('Could not load Risk Scores');
        expect(el.querySelector('inspecto-empty-state')).toBeNull();
    });

    const held = (name: string, operation: PendingChange['operation'], kind = 'risk-score'): PendingChange =>
        ({
            id: 'pc-' + name,
            kind,
            name,
            operation,
            status: 'pending',
            author: 'maker1',
            reason: null,
            createdAt: '2026-10-06T09:00:00Z',
        }) as PendingChange;

    it('shows a held create as a read-only Awaiting approval row linked to Pending Changes', async () => {
        const { fixture, el, latest } = create(() => of([]), [held('new_model', 'create')]);
        expect(el.querySelector('inspecto-empty-state')).toBeNull();
        const row = el.querySelector('[data-held-create]') as HTMLElement;
        expect(row.textContent).toContain('new_model');
        expect(row.textContent).toContain('maker1');
        expect(row.textContent).toContain('Awaiting approval');
        expect(row.querySelector('a')?.getAttribute('href')).toBe('/pending-changes');
        // not selectable: no button, so no stored model is ever loaded
        expect(row.querySelector('button')).toBeNull();
        row.click();
        fixture.detectChanges();
        expect(el.querySelector('section h2')).toBeNull();
        expect(latest).not.toHaveBeenCalled();
        await expectNoA11yViolations(el);
    });

    it('shows no held row for an absent, non-risk-score or non-create Pending Change', () => {
        const { el } = create(() => of([]), [held('other', 'create', 'alert-rule'), held('gone', 'delete')]);
        expect(el.querySelector('[data-held-create]')).toBeNull();
        expect(el.querySelector('inspecto-empty-state')).not.toBeNull();
    });

    it('keeps a held edit of a stored model as a badge only, never a duplicate row', () => {
        const { el } = create(() => of([MODEL]), [held('sim_box', 'update'), held('sim_box_2', 'create')]);
        const items = Array.from(el.querySelectorAll('nav li'));
        expect(items.length).toBe(2);
        expect(items[0].textContent).toContain('Awaiting approval');
        expect(el.querySelectorAll('[data-held-create]').length).toBe(1);
        expect(el.querySelector('[data-held-create]')?.textContent).toContain('sim_box_2');
    });
});
