import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter, Router } from '@angular/router';
import { Observable, of, Subject, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { LensService, SampleSpace, Space, SpacesService, SpaceTemplateInfo } from '@inspecto/core/api';
import { SessionService } from '@inspecto/core/auth/session.service';
import { NO_SPACE, SpaceGuideState } from '@inspecto/core/onboarding/space-guide';
import { SPACE_GUIDE_COLLAPSED_KEY } from '@inspecto/core/onboarding/space-guide.component';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { FirstRun } from './first-run';
import { GettingStartedComponent } from './getting-started.component';

const SAMPLE: SpaceTemplateInfo = {
    id: 'la-sample',
    name: 'Link Analysis sample',
    tagline: '',
    description: '',
    icon: '',
    contents: [],
    creatable: true,
    samples: true,
};
const ALPHA: Space = { id: 'alpha', displayName: 'Alpha', description: '', createdAt: '' };

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [GettingStartedComponent],
    template: `<la-app-getting-started [state]="state()"></la-app-getting-started>`,
})
class HostComponent {
    readonly state = signal<SpaceGuideState>(NO_SPACE);
}

interface Opts {
    state?: SpaceGuideState;
    spaces?: Space[];
    multiSpace?: boolean;
    canCreate?: boolean;
    gallery?: SpaceTemplateInfo[];
    sample?: () => Observable<SampleSpace>;
}

function create(o: Opts = {}) {
    const spaces = o.spaces ?? [];
    const firstRun = {
        canCreate: signal(o.canCreate ?? true),
        trySample: vi.fn(o.sample ?? (() => new Subject<SampleSpace>())),
        createSpace: vi.fn(),
        openStep: vi.fn(),
        enter: vi.fn(),
    };
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: FirstRun, useValue: firstRun },
            {
                provide: SpacesService,
                useValue: {
                    multiSpace: signal(o.multiSpace ?? true),
                    availableSpaces: signal(spaces),
                    showSwitcher: signal(spaces.length > 0),
                    currentSpaceId: signal(spaces[0]?.id ?? null),
                    currentSpace: signal(spaces[0] ?? null),
                    refresh: () => of(spaces),
                    templates: () => of(o.gallery ?? [SAMPLE]),
                    selectSpace: vi.fn(),
                },
            },
            { provide: LensService, useValue: { canAdminister: signal(o.canCreate ?? true) } },
            { provide: SessionService, useValue: { authMode: signal(o.canCreate === false ? 'oidc' : 'none') } },
            { provide: MatDialog, useValue: { open: vi.fn() } },
        ],
    });
    const fixture = TestBed.createComponent(HostComponent);
    if (o.state) fixture.componentInstance.state.set(o.state);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const card = (choice: string) => el.querySelector<HTMLElement>(`[data-choice="${choice}"]`);
    const button = (choice: string) => card(choice)?.querySelector<HTMLButtonElement>('button') ?? null;
    return { fixture, el, firstRun, card, button };
}

describe('GettingStartedComponent', () => {
    beforeEach(() => localStorage.removeItem(SPACE_GUIDE_COLLAPSED_KEY));

    it('with no Space: the sample and Create a Space, no team choice, the guide at 0 of 4 (no violations)', async () => {
        const { el, card, button } = create();
        expect(el.querySelector('h1')!.textContent).toContain('Getting started');
        expect(button('sample')!.textContent).toContain('Try the sample');
        expect(button('sample')!.disabled).toBe(false);
        expect(button('own')!.textContent).toContain('Create a Space');
        expect(card('team')).toBeNull();
        expect(el.textContent).toContain('0 of 4 done');
        expect(card('sample')!.textContent).toContain('Invented');
        await expectNoA11yViolations(el);
    });

    it('a Space with no Dataset: Use my own data says Add data and opens /add-data (no violations)', async () => {
        const { fixture, el, button } = create({ state: { ...NO_SPACE, hasSpace: true }, spaces: [ALPHA] });
        const nav = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
        expect(button('own')!.textContent).toContain('Add data');
        button('own')!.click();
        expect(nav).toHaveBeenCalledWith('/add-data');
        fixture.detectChanges();
        await expectNoA11yViolations(el);
    });

    it("with Spaces hosted: Open my team's data carries the Space switcher (no violations)", async () => {
        const { el, card } = create({ state: { ...NO_SPACE, hasSpace: true }, spaces: [ALPHA] });
        expect(card('team')!.querySelector('inspecto-space-switcher button')).not.toBeNull();
        await expectNoA11yViolations(el);
    });

    it('Create a Space opens the create dialog, then Add data', () => {
        const { firstRun, button } = create();
        button('own')!.click();
        expect(firstRun.createSpace).toHaveBeenCalledWith('/add-data');
    });

    it('a user who may not create a Space sees both choices locked, each with its reason (no violations)', async () => {
        const { el, card, button } = create({ canCreate: false });
        expect(button('sample')!.disabled).toBe(true);
        expect(card('sample')!.textContent).toContain('Only an administrator can create a Space');
        expect(button('sample')!.getAttribute('aria-describedby')).toBe('la-sample-why');
        expect(button('own')!.disabled).toBe(true);
        expect(card('own')!.textContent).toContain('Only an administrator can create a Space');
        await expectNoA11yViolations(el);
    });

    it('says why the sample cannot run: a single-Space server, a sample not installed, an edition that lacks it', () => {
        const single = create({ multiSpace: false, state: { ...NO_SPACE, hasSpace: true } });
        expect(single.button('sample')!.disabled).toBe(true);
        expect(single.card('sample')!.textContent).toContain('runs a single Space');
        TestBed.resetTestingModule();
        const absent = create({ gallery: [] });
        expect(absent.button('sample')!.disabled).toBe(true);
        expect(absent.card('sample')!.textContent).toContain('not installed');
        TestBed.resetTestingModule();
        const locked = create({
            gallery: [{ ...SAMPLE, creatable: false, missingFeatures: [{ feature: 'f', message: 'Needs Standard.' }] }],
        });
        expect(locked.card('sample')!.textContent).toContain('cannot be created in this edition: Needs Standard.');
    });

    it('Try the sample shows progress while the Space is made and loaded (no violations)', async () => {
        const { fixture, el, firstRun, button } = create();
        button('sample')!.click();
        fixture.detectChanges();
        expect(firstRun.trySample).toHaveBeenCalled();
        expect(button('sample')!.disabled).toBe(true);
        expect(el.querySelector('[role="status"]')!.textContent).toContain('loading its data');
        await expectNoA11yViolations(el);
    });

    it('a sample whose data did not load names the Pipeline and offers the Space anyway (no violations)', async () => {
        const made: SampleSpace = {
            ...ALPHA,
            id: 'sample',
            samples: {
                pipelines: [
                    {
                        pipeline: 'mule_transfers',
                        sampleFiles: 3,
                        status: 'failed',
                        filesLoaded: 0,
                        filesQuarantined: 0,
                        error: 'boom',
                    },
                ],
            },
        };
        const { fixture, el, firstRun, card, button } = create({ sample: () => of(made) });
        button('sample')!.click();
        fixture.detectChanges();
        expect(card('sample')!.textContent).toContain('mule_transfers: boom');
        const open = Array.from(card('sample')!.querySelectorAll('button')).find((b) =>
            b.textContent!.includes('Open the sample Space'),
        )!;
        open.click();
        expect(firstRun.enter).toHaveBeenCalledWith('sample', '/');
        await expectNoA11yViolations(el);
    });

    it('a refused sample create says why in place', () => {
        const { fixture, card, button } = create({ sample: () => throwError(() => new Error('nope')) });
        button('sample')!.click();
        fixture.detectChanges();
        expect(card('sample')!.querySelector('inspecto-alert')).not.toBeNull();
        expect(button('sample')!.disabled).toBe(false);
    });

    it('each Space guide line opens its step', () => {
        const { el, firstRun } = create();
        el.querySelector<HTMLButtonElement>('button[data-step="graph"]')!.click();
        expect(firstRun.openStep).toHaveBeenCalledWith('graph');
    });
});
