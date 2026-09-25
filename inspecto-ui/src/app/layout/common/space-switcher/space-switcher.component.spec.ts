import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter, Router } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { LensService, Space, SpacesService } from 'app/inspecto/api';
import { SpaceFormDialog } from 'app/inspecto/spaces/space-form.dialog';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { SpaceSwitcherComponent } from './space-switcher.component';

const SPACES: Space[] = [
    { id: 'alpha', displayName: 'Alpha', description: '', createdAt: '' },
    { id: 'beta', displayName: 'Beta', description: '', createdAt: '' },
];

function create(show: boolean, canAdminister = false, created?: Space) {
    const selectSpace = vi.fn();
    const open = vi.fn(() => ({ afterClosed: () => of(created) }));
    const stub = {
        showSwitcher: signal(show),
        availableSpaces: signal(show ? SPACES : []),
        currentSpaceId: signal<string | null>(show ? 'alpha' : null),
        currentSpace: signal<Space | null>(show ? SPACES[0] : null),
        refresh: () => of(SPACES),
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
        expect(fixture.nativeElement.querySelector('button[aria-label="Switch space"]')).not.toBeNull();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    /** Opens the switcher menu and returns its item labels (the menu renders in the CDK overlay). */
    function openMenu(fixture: ReturnType<typeof create>['fixture']): HTMLButtonElement[] {
        fixture.nativeElement.querySelector('button[aria-label="Switch space"]').click();
        fixture.detectChanges();
        return Array.from(document.querySelectorAll<HTMLButtonElement>('.mat-mdc-menu-panel button[mat-menu-item]'));
    }

    it('hides "New space…" when the user cannot administer Spaces', () => {
        const { fixture } = create(true, false);
        const items = openMenu(fixture);
        expect(items.map((b) => b.textContent?.trim())).toEqual(['Alpha', 'Beta']);
        expect(document.querySelector('.mat-mdc-menu-panel mat-divider')).toBeNull();
    });

    it('offers "New space…" last, after a divider, to a Space administrator (no violations)', async () => {
        const { fixture } = create(true, true);
        const items = openMenu(fixture);
        expect(items.map((b) => b.textContent?.trim())).toEqual(['Alpha', 'Beta', 'New space…']);
        const panel = document.querySelector('.mat-mdc-menu-panel') as HTMLElement;
        expect(panel.querySelector('mat-divider + button')?.textContent?.trim()).toBe('New space…');
        await expectNoA11yViolations(panel);
    });

    it('opens the shared New-space dialog and switches to the Space it creates', () => {
        const created: Space = { id: 'gamma', displayName: 'Gamma', description: '', createdAt: '' };
        const { fixture, open, selectSpace } = create(true, true, created);
        openMenu(fixture).at(-1)!.click();
        expect(open).toHaveBeenCalledWith(SpaceFormDialog, expect.anything());
        expect(selectSpace).toHaveBeenCalledWith('gamma');
    });

    it('stays on the current Space when the dialog is dismissed', () => {
        const { fixture, open, selectSpace } = create(true, true, undefined);
        openMenu(fixture).at(-1)!.click();
        expect(open).toHaveBeenCalled();
        expect(selectSpace).not.toHaveBeenCalled();
    });
});
