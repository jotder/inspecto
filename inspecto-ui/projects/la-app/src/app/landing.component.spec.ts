import { Component, input, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Observable, of, Subject } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SessionService } from '@inspecto/core/auth/session.service';
import { NO_SPACE, SpaceGuideState } from '@inspecto/core/onboarding/space-guide';
import { SPACE_GUIDE_COLLAPSED_KEY } from '@inspecto/core/onboarding/space-guide.component';
import { SpaceGuideService } from '@inspecto/core/onboarding/space-guide.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { FirstRun } from './first-run';
import { GettingStartedComponent } from './getting-started.component';
import { LandingComponent } from './landing.component';
import { MyWorkComponent } from './my-work.component';

function create(load: Observable<SpaceGuideState>) {
    TestBed.configureTestingModule({
        imports: [LandingComponent],
        providers: [
            provideRouter([]),
            { provide: SpaceGuideService, useValue: { load: () => load } },
            { provide: SessionService, useValue: { features: signal({}) } },
            { provide: FirstRun, useValue: { openStep: vi.fn() } },
        ],
    });
    // The Getting started page is covered by its own spec; here only WHICH page renders matters.
    TestBed.overrideComponent(LandingComponent, {
        remove: { imports: [GettingStartedComponent, MyWorkComponent] },
        add: { imports: [GettingStartedStub, MyWorkStub] },
    });
    const fixture = TestBed.createComponent(LandingComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
}

@Component({ selector: 'la-app-getting-started', standalone: true, template: '<h1>Getting started</h1>' })
class GettingStartedStub {
    readonly state = input<SpaceGuideState>();
}

/** My work has its own spec; here only WHERE it renders matters. */
@Component({ selector: 'la-app-my-work', standalone: true, template: '<p>My work</p>' })
class MyWorkStub {}

describe('LandingComponent', () => {
    beforeEach(() => localStorage.removeItem(SPACE_GUIDE_COLLAPSED_KEY));

    it('shows a skeleton while the setup state loads', () => {
        const el = create(new Subject<SpaceGuideState>());
        expect(el.querySelector('[aria-busy="true"] inspecto-skeleton')).not.toBeNull();
        expect(el.querySelector('h1')).toBeNull();
    });

    it('shows Getting started with no Space, and with a Space but no Dataset (keyed on data, not Runs)', () => {
        expect(create(of(NO_SPACE)).querySelector('la-app-getting-started')).not.toBeNull();
        TestBed.resetTestingModule();
        expect(create(of({ ...NO_SPACE, hasSpace: true })).querySelector('la-app-getting-started')).not.toBeNull();
    });

    it('once there is data: the area cards, with the Space guide above them until it is done (no violations)', async () => {
        const el = create(of({ hasSpace: true, datasets: 1, columnsDescribed: false, views: 0, lists: 0 }));
        expect(el.querySelector('la-app-getting-started')).toBeNull();
        expect(el.querySelector('h1')!.textContent).toContain('Inspecto LA');
        expect(el.querySelector('inspecto-space-guide section')).not.toBeNull();
        // My work comes after the Space guide (phase 7)
        const guide = el.querySelector('inspecto-space-guide')!;
        const work = el.querySelector('la-app-my-work')!;
        expect(guide.compareDocumentPosition(work) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
        expect(el.querySelectorAll('ul a').length).toBeGreaterThan(0);
        await expectNoA11yViolations(el);
    });

    it('a finished guide is gone, and My work leads the home page', () => {
        const el = create(of({ hasSpace: true, datasets: 1, columnsDescribed: true, views: 1, lists: 0 }));
        expect(el.querySelector('inspecto-space-guide section')).toBeNull();
        expect(el.querySelector('la-app-my-work')).not.toBeNull();
        expect(el.querySelectorAll('ul a').length).toBeGreaterThan(0);
    });
});
