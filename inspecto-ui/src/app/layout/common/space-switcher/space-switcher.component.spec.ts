import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatTooltip } from '@angular/material/tooltip';
import { By } from '@angular/platform-browser';
import { provideRouter, Router } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { LensService, Space, SpacesService } from 'app/inspecto/api';
import { SessionService } from 'app/inspecto/auth/session.service';
import { SampleSpaceDialog } from 'app/inspecto/spaces/sample-space.dialog';
import { SpaceFormDialog } from 'app/inspecto/spaces/space-form.dialog';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { SpaceSwitcherComponent, spacesByName } from './space-switcher.component';

const SPACES: Space[] = [
    { id: 'alpha', displayName: 'Alpha', description: '', createdAt: '' },
    { id: 'beta', displayName: 'Beta', description: '', createdAt: '' },
];

/**
 * `show`: Spaces to switch between are hosted. `canAdminister`: the lens grant. `signedIn`: the server signs people
 * in (authMode oidc) — the existing cases sign in, so "cannot administer" really means it; `multiSpace` defaults to
 * `show`.
 */
function create(
    show: boolean,
    canAdminister = false,
    created?: Space,
    opts: { signedIn?: boolean; multiSpace?: boolean; spaces?: Space[] } = {},
) {
    const spaces = opts.spaces ?? SPACES;
    const selectSpace = vi.fn();
    const open = vi.fn(() => ({ afterClosed: () => of(created) }));
    const stub = {
        showSwitcher: signal(show),
        multiSpace: signal(opts.multiSpace ?? show),
        availableSpaces: signal(show ? spaces : []),
        currentSpaceId: signal<string | null>(show ? 'alpha' : null),
        currentSpace: signal<Space | null>(show ? (spaces.find((x) => x.id === 'alpha') ?? null) : null),
        refresh: () => of(spaces),
        selectSpace,
    } as unknown as SpacesService;
    const lens = { canAdminister: signal(canAdminister), currentLens: signal('builder') } as unknown as LensService;
    TestBed.configureTestingModule({
        imports: [SpaceSwitcherComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: SpacesService, useValue: stub },
            { provide: LensService, useValue: lens },
            {
                provide: SessionService,
                useValue: { authMode: signal((opts.signedIn ?? true) ? 'oidc' : 'none') },
            },
            { provide: MatDialog, useValue: { open } },
        ],
    });
    // The switch hard-reloads after navigating; keep the navigation pending so jsdom never reloads.
    vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockReturnValue(new Promise<boolean>(() => undefined));
    const fixture = TestBed.createComponent(SpaceSwitcherComponent);
    fixture.detectChanges();
    return { fixture, selectSpace, open };
}

describe('SpaceSwitcherComponent', () => {
    it('renders nothing on a single-tenant server (no violations)', async () => {
        const { fixture } = create(false);
        expect(fixture.nativeElement.querySelector('button')).toBeNull();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('renders an accessible switcher when multiple spaces exist', async () => {
        const { fixture } = create(true);
        const btn = fixture.nativeElement.querySelector('button[aria-label$=", switch space"]') as HTMLButtonElement;
        // WCAG 2.5.3 label in name: the accessible name starts with the visible Space label.
        const visible = btn.querySelector('[data-testid="space-switcher-name"]')!.textContent!.trim();
        expect(visible).toBe('Alpha');
        expect(btn.getAttribute('aria-label')!.startsWith(visible)).toBe(true);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    /** Opens the switcher menu and returns its item labels (the menu renders in the CDK overlay). */
    function openMenu(fixture: ReturnType<typeof create>['fixture']): HTMLButtonElement[] {
        fixture.nativeElement.querySelector('button[aria-label$=", switch space"]').click();
        fixture.detectChanges();
        return Array.from(document.querySelectorAll<HTMLButtonElement>('.mat-mdc-menu-panel button[mat-menu-item]'));
    }

    it('hides "New space…" when the user cannot administer Spaces', () => {
        const { fixture } = create(true, false);
        const items = openMenu(fixture);
        expect(items.map((b) => b.textContent?.trim())).toEqual(['Alpha', 'Beta']);
        expect(document.querySelector('.mat-mdc-menu-panel mat-divider')).toBeNull();
    });

    it('offers "New Space…" and "New from sample…" last, after a divider, to a Space administrator (no violations)', async () => {
        const { fixture } = create(true, true);
        const items = openMenu(fixture);
        expect(items.map((b) => b.textContent?.trim())).toEqual(['Alpha', 'Beta', 'New Space…', 'New from sample…']);
        const panel = document.querySelector('.mat-mdc-menu-panel') as HTMLElement;
        expect(panel.querySelector('mat-divider + button')?.textContent?.trim()).toBe('New Space…');
        await expectNoA11yViolations(panel);
    });

    it('opens the shared New-space dialog and switches to the Space it creates', () => {
        const created: Space = { id: 'gamma', displayName: 'Gamma', description: '', createdAt: '' };
        const { fixture, open, selectSpace } = create(true, true, created);
        openMenu(fixture).at(-2)!.click();
        expect(open).toHaveBeenCalledWith(SpaceFormDialog, expect.anything());
        expect(selectSpace).toHaveBeenCalledWith('gamma');
    });

    it('New from sample opens the sample dialog and switches to the sample Space it creates', () => {
        const created: Space = { id: 'sample', displayName: 'Sample', description: '', createdAt: '' };
        const { fixture, open, selectSpace } = create(true, true, created);
        openMenu(fixture).at(-1)!.click();
        expect(open).toHaveBeenCalledWith(SampleSpaceDialog, expect.anything());
        expect(selectSpace).toHaveBeenCalledWith('sample');
    });

    describe('with ZERO Spaces (OB-11)', () => {
        it('renders for anyone when nobody signs in, offering New Space and New from sample (no violations)', async () => {
            const { fixture } = create(false, false, undefined, { signedIn: false, multiSpace: true });
            const btn = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
            expect(btn).not.toBeNull();
            expect(btn.getAttribute('aria-label')).toBe('No Space yet, create a Space');
            const items = openMenu2(fixture);
            expect(items.map((b) => b.textContent?.trim())).toEqual(['New Space…', 'New from sample…']);
            expect(document.querySelector('.mat-mdc-menu-panel mat-divider')).toBeNull();
            await expectNoA11yViolations(fixture.nativeElement);
        });

        it('renders for a signed-in Space administrator', () => {
            const { fixture } = create(false, true, undefined, { signedIn: true, multiSpace: true });
            expect(fixture.nativeElement.querySelector('button')).not.toBeNull();
        });

        it('stays hidden for a signed-in user who may not create a Space, and on a single-Space server', () => {
            const signedIn = create(false, false, undefined, { signedIn: true, multiSpace: true });
            expect(signedIn.fixture.nativeElement.querySelector('button')).toBeNull();
            TestBed.resetTestingModule();
            const single = create(false, true, undefined, { signedIn: false, multiSpace: false });
            expect(single.fixture.nativeElement.querySelector('button')).toBeNull();
        });

        /** The zero-Space trigger has no ", switch space" name, so open it by its own button. */
        function openMenu2(fixture: ReturnType<typeof create>['fixture']): HTMLButtonElement[] {
            fixture.nativeElement.querySelector('button').click();
            fixture.detectChanges();
            return Array.from(
                document.querySelectorAll<HTMLButtonElement>('.mat-mdc-menu-panel button[mat-menu-item]'),
            );
        }
    });

    describe('LA-SPACE-SWITCHER-1', () => {
        const space = (id: string, displayName = ''): Space => ({ id, displayName, description: '', createdAt: '' });

        it('spacesByName: by the shown name, case aside, numbers in order, the id breaking a tie; the input is kept', () => {
            const input = [
                space('la-prov', 'Provider billing fraud'),
                space('la-health', 'healthcare'),
                space('la-ent', 'Enterprise'),
                space('s10', 'Space 10'),
                space('s9', 'Space 9'),
                space('zz'),
                space('b-twin', 'Twin'),
                space('a-twin', 'Twin'),
            ];
            expect(spacesByName(input).map((s) => s.id)).toEqual([
                'la-ent',
                'la-health',
                'la-prov',
                's9',
                's10',
                'a-twin',
                'b-twin',
                'zz',
            ]);
            expect(input[0].id).toBe('la-prov');
        });

        it('lists the Spaces in that order whatever order the server answered in', () => {
            const { fixture } = create(true, false, undefined, {
                spaces: [space('zeta', 'Zeta'), space('alpha', 'Alpha'), space('mid', 'mid-range')],
            });
            expect(openMenu(fixture).map((b) => b.textContent?.trim())).toEqual(['Alpha', 'mid-range', 'Zeta']);
        });

        it('a long name cannot widen the button past its container: it truncates, the tooltip and name keep it whole', () => {
            const long = 'Link Analysis sample (invented data)';
            const { fixture } = create(true, false, undefined, {
                spaces: [space('alpha', long), space('beta', 'Beta')],
            });
            const btn = fixture.nativeElement.querySelector(
                'button[aria-label$=", switch space"]',
            ) as HTMLButtonElement;
            expect(btn.classList).toContain('space-switcher'); // the label truncates (component style)
            expect(btn.classList).toContain('max-w-full');
            expect(btn.getAttribute('aria-label')).toBe(`${long}, switch space`);
            const tip = fixture.debugElement.query(By.directive(MatTooltip)).injector.get(MatTooltip);
            expect(tip.message).toBe(`${long} (switch Space)`);
            // the chevron follows the name rather than sitting between the icon and the name
            const label = btn.querySelector('.mdc-button__label')!;
            expect(label.textContent!.trim()).toBe(long);
            expect(label.nextElementSibling?.getAttribute('svgIcon')).toBe('heroicons_outline:chevron-down');
        });
    });

    it('stays on the current Space when the dialog is dismissed', () => {
        const { fixture, open, selectSpace } = create(true, true, undefined);
        openMenu(fixture).at(-2)!.click();
        expect(open).toHaveBeenCalled();
        expect(selectSpace).not.toHaveBeenCalled();
    });
    describe('a ?space=<id> URL', () => {
        type Seams = { followSpaceParam(url: string): void; reload(): void };
        function seams(fixture: ReturnType<typeof create>['fixture']) {
            const c = fixture.componentInstance as unknown as Seams;
            const reload = vi.spyOn(c, 'reload').mockImplementation(() => undefined);
            return { c, reload };
        }

        it('switches to the named Space and reloads in place', () => {
            const { fixture, selectSpace } = create(true);
            const { c, reload } = seams(fixture);
            c.followSpaceParam('/pipelines?space=beta');
            expect(selectSpace).toHaveBeenCalledWith('beta');
            expect(reload).toHaveBeenCalled();
        });

        it('does nothing for the current Space, an unknown Space, or no parameter', () => {
            const { fixture, selectSpace } = create(true);
            const { c, reload } = seams(fixture);
            c.followSpaceParam('/pipelines?space=alpha');
            c.followSpaceParam('/pipelines?space=nope');
            c.followSpaceParam('/pipelines');
            expect(selectSpace).not.toHaveBeenCalled();
            expect(reload).not.toHaveBeenCalled();
        });
    });
});
