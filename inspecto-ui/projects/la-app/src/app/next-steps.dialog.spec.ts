import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialogRef } from '@angular/material/dialog';
import { provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { SpaceGuideState } from '@inspecto/core/onboarding/space-guide';
import { SpaceGuideService } from '@inspecto/core/onboarding/space-guide.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { AddDataAccess } from '@inspecto/link-analysis/add-data/add-data-access';
import { FirstRun } from './first-run';
import { NextStepsDialog } from './next-steps.dialog';

function create(state: SpaceGuideState, lock: string | null = null, canCreate = true) {
    const close = vi.fn();
    const firstRun = { canCreate: signal(canCreate), createSpace: vi.fn(), openStep: vi.fn() };
    TestBed.configureTestingModule({
        imports: [NextStepsDialog],
        providers: [
            provideRouter([]),
            { provide: SpaceGuideService, useValue: { load: () => of(state) } },
            { provide: AddDataAccess, useValue: { load: vi.fn(), lockReason: signal(lock) } },
            { provide: FirstRun, useValue: firstRun },
            { provide: MatDialogRef, useValue: { close } },
        ],
    });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const f = TestBed.createComponent(NextStepsDialog);
    f.detectChanges();
    return { el: f.nativeElement as HTMLElement, close, navigate, firstRun };
}

describe('NextStepsDialog - What can I do next? (LA-ONBOARD-1 phase 8)', () => {
    it('answers from the server state: the recommended step first, with why, and the Space guide (no violations)', async () => {
        const { el, close, navigate } = create({
            hasSpace: true,
            datasets: 1,
            columnsDescribed: false,
            views: 0,
            lists: 0,
        });
        const steps = Array.from(el.querySelectorAll('[data-testid="la-next-steps"] li'));
        expect(steps.map((s) => s.getAttribute('data-step'))).toEqual(['columns', 'graph', 'list']);
        expect(steps[0].textContent).toContain('No column is tied to an Entity Type yet');
        expect(el.querySelector('inspecto-space-guide')).not.toBeNull();
        await expectNoA11yViolations(el);

        (steps[0].querySelector('button') as HTMLButtonElement).click();
        expect(close).toHaveBeenCalled();
        expect(navigate).toHaveBeenCalledWith('/entities?tab=columns');
    });

    it('a step this person may not take is shown locked with the reason, never a button that fails', async () => {
        const lock = 'Data in this Space comes from Pipelines - ask an administrator.';
        const { el } = create(
            { hasSpace: true, datasets: 0, columnsDescribed: false, views: 0, lists: 0 },
            lock,
            false,
        );
        const first = el.querySelector('[data-step="add-data"]') as HTMLElement;
        expect(first.querySelector('inspecto-locked-action')).not.toBeNull();
        expect(first.textContent).toContain(lock);
        await expectNoA11yViolations(el);
    });

    it('with no Space, the first step opens the Create Space dialog', () => {
        const { el, firstRun, close } = create({
            hasSpace: false,
            datasets: 0,
            columnsDescribed: false,
            views: 0,
            lists: 0,
        });
        (el.querySelector('[data-step="create-space"] button') as HTMLButtonElement).click();
        expect(close).toHaveBeenCalled();
        expect(firstRun.createSpace).toHaveBeenCalled();
    });
});
