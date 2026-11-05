import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter, Router } from '@angular/router';
import { LensService, SampleSpace, SessionService, SpacesService, SpaceTemplateInfo } from 'app/inspecto/api';
import { OnboardingEventsService } from 'app/inspecto/onboarding/onboarding-events.service';
import { CONSOLE_SAMPLE_LANDING, NO_SPACE, SpaceGuideState } from 'app/inspecto/onboarding/space-guide';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GettingStartedComponent } from './getting-started.component';

const ORDERS: SpaceTemplateInfo = {
    id: 'orders-starter',
    name: 'Orders starter',
    tagline: 'Daily orders',
    samples: true,
    creatable: true,
} as SpaceTemplateInfo;

const SPACE: SpaceGuideState = { ...NO_SPACE, hasSpace: true };

@Component({
    standalone: true,
    imports: [GettingStartedComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `<inspecto-getting-started [state]="state" />`,
})
class Host {
    state: SpaceGuideState = SPACE;
}

interface Opts {
    multiSpace?: boolean;
    templates?: SpaceTemplateInfo[];
    created?: SampleSpace | 'error';
    authMode?: 'none' | 'oidc';
    administer?: boolean;
    state?: SpaceGuideState;
    spaces?: { id: string }[];
}

function create(o: Opts = {}) {
    localStorage.clear();
    const spaces = {
        multiSpace: signal(o.multiSpace ?? true),
        availableSpaces: signal(o.spaces ?? []),
        showSwitcher: signal((o.spaces ?? []).length > 0),
        currentSpaceId: signal(null),
        currentSpace: signal(null),
        templates: vi.fn(() => of(o.templates ?? [ORDERS])),
        createSample: vi.fn(() =>
            o.created === 'error' ? throwError(() => ({ status: 422 })) : of(o.created ?? { id: 'sample' }),
        ),
        selectSpace: vi.fn(),
        refresh: () => of([]),
    };
    const dialog = { open: vi.fn(() => ({ afterClosed: () => of(undefined) })) };
    const events = { stepAbandoned: vi.fn(), guideCompleted: vi.fn() };
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: SpacesService, useValue: spaces },
            { provide: SessionService, useValue: { authMode: signal(o.authMode ?? 'none') } },
            {
                provide: LensService,
                useValue: { canAdminister: signal(o.administer ?? false), canOnboardConnections: signal(true) },
            },
            { provide: OnboardingEventsService, useValue: events },
        ],
    });
    TestBed.overrideProvider(MatDialog, { useValue: dialog });
    const fixture = TestBed.createComponent(Host);
    if (o.state) fixture.componentInstance.state = o.state;
    fixture.detectChanges();
    const gs = fixture.debugElement.children[0].componentInstance as GettingStartedComponent;
    const reload = vi.spyOn(gs, 'reload').mockImplementation(() => {});
    const router = TestBed.inject(Router);
    const go = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    const el = fixture.nativeElement as HTMLElement;
    return { fixture, el, spaces, dialog, events, reload, go };
}

const sampleButton = (el: HTMLElement) => el.querySelector('[data-testid="gs-sample"]') as HTMLButtonElement;

describe('GettingStartedComponent (ONBOARDING-FIRST-RUN-1 C4)', () => {
    it('offers the three ways in beside the console Space guide, with no a11y violations', async () => {
        const { el } = create({ spaces: [{ id: 'team' }] });
        expect(Array.from(el.querySelectorAll('[data-choice]')).map((c) => c.getAttribute('data-choice'))).toEqual([
            'sample',
            'own',
            'team',
        ]);
        expect(Array.from(el.querySelectorAll('[data-step]')).map((b) => b.getAttribute('data-step'))).toEqual([
            'space',
            'data',
            'query',
            'dashboard',
        ]);
        await expectNoA11yViolations(el);
    });

    it('Try the sample creates orders-starter with its samples and lands on its dashboard', async () => {
        const { el, spaces, go, reload } = create();
        sampleButton(el).click();
        expect(spaces.createSample).toHaveBeenCalledWith('orders-starter', 'Sample: Orders starter');
        expect(spaces.selectSpace).toHaveBeenCalledWith('sample');
        expect(go).toHaveBeenCalledWith(CONSOLE_SAMPLE_LANDING);
        await Promise.resolve();
        expect(reload).toHaveBeenCalled();
    });

    it('a sample whose Pipeline did not load says which, and does not jump to an empty dashboard', () => {
        const created = {
            id: 'sample',
            samples: { pipelines: [{ pipeline: 'orders', status: 'failed', error: 'no files' }] },
        } as unknown as SampleSpace;
        const { fixture, el, go } = create({ created });
        sampleButton(el).click();
        fixture.detectChanges();
        expect(el.textContent).toContain('its data did not load');
        expect(el.textContent).toContain('orders: no files');
        expect(go).not.toHaveBeenCalled();
    });

    it('a refused create is shown in place', () => {
        const { fixture, el, go } = create({ created: 'error' });
        sampleButton(el).click();
        fixture.detectChanges();
        expect(el.querySelector('inspecto-alert')).not.toBeNull();
        expect(go).not.toHaveBeenCalled();
    });

    it('says why the sample cannot run on a single-Space server', () => {
        const { el, spaces } = create({ multiSpace: false });
        expect(sampleButton(el).disabled).toBe(true);
        expect(el.textContent).toContain('This server runs a single Space');
        expect(spaces.templates).not.toHaveBeenCalled();
    });

    it('says why when the sample is not installed', () => {
        const { el } = create({ templates: [] });
        expect(sampleButton(el).disabled).toBe(true);
        expect(el.textContent).toContain('The sample is not installed on this server.');
    });

    it('with no Space, Add data creates one first; a signed-in non-administrator is told to ask', () => {
        const { el } = create({ state: NO_SPACE, authMode: 'oidc' });
        const add = el.querySelector('[data-testid="home-add-data"]') as HTMLButtonElement;
        expect(add.textContent).toContain('Create a Space');
        expect(add.disabled).toBe(true);
        expect(el.textContent).toContain('Only an administrator can create a Space');
    });

    it('a guide line opens its step; a dismissed Create Space counts as the step left unfinished', () => {
        const { el, go, dialog, events } = create({ state: NO_SPACE });
        (el.querySelector('[data-step="query"]') as HTMLButtonElement).click();
        expect(go).toHaveBeenCalledWith('/studio/queries');
        (el.querySelector('[data-step="space"]') as HTMLButtonElement).click();
        expect(dialog.open).toHaveBeenCalled();
        expect(events.stepAbandoned).toHaveBeenCalledWith('space');
    });
});
