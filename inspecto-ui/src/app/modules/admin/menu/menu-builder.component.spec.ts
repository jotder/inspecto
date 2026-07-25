import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { NavigationService } from 'app/core/navigation/navigation.service';
import { LensService, SpacesService } from 'app/inspecto/api';
import { NavMenusService, emptyTree } from 'app/inspecto/menu';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { MenuBuilderComponent } from './menu-builder.component';

describe('MenuBuilderComponent', () => {
    beforeEach(() => localStorage.clear());

    it('renders the empty state with no menus, titled Menus, and is a11y-clean', async () => {
        TestBed.configureTestingModule({
            imports: [MenuBuilderComponent],
            providers: [
                provideNoopAnimations(),
                { provide: SpacesService, useValue: { currentSpaceId: signal<string | null>(null) } },
                { provide: NavigationService, useValue: { get: () => of(null) } },
                { provide: NavMenusService, useValue: { get: () => of(emptyTree('default')), put: () => of(emptyTree('default')) } },
                { provide: ToastrService, useValue: { error: () => {} } },
                { provide: MatDialog, useValue: { open: () => ({ afterClosed: () => of(undefined) }) } },
                { provide: LensService, useValue: { canAuthorWorkbench: () => true } },
            ],
        });
        const f = TestBed.createComponent(MenuBuilderComponent);
        f.detectChanges();
        expect(f.nativeElement.querySelector('h1')?.textContent).toContain('Menus');
        expect(f.nativeElement.textContent).toContain('No menus yet');
        await expectNoA11yViolations(f.nativeElement);
    });

    it('shows a favorite toggle on leaves only, toggles state, and stays a11y-clean', async () => {
        const leafTree = {
            space: 'default',
            version: 1 as const,
            nodes: [
                {
                    id: 'rev',
                    title: 'Revenue',
                    children: [{ id: 'd1', title: 'Dash one', binding: { kind: 'dashboard' as const, componentId: 'c1' } }],
                },
            ],
        };
        TestBed.configureTestingModule({
            imports: [MenuBuilderComponent],
            providers: [
                provideNoopAnimations(),
                { provide: SpacesService, useValue: { currentSpaceId: signal<string | null>(null) } },
                { provide: NavigationService, useValue: { get: () => of(null) } },
                { provide: NavMenusService, useValue: { get: () => of(leafTree), put: () => of(leafTree) } },
                { provide: ToastrService, useValue: { error: () => {} } },
                { provide: MatDialog, useValue: { open: () => ({ afterClosed: () => of(undefined) }) } },
                { provide: LensService, useValue: { canAuthorWorkbench: () => true } },
            ],
        });
        const f = TestBed.createComponent(MenuBuilderComponent);
        f.detectChanges();

        // exactly one favorite toggle — on the leaf, not the group
        const stars = Array.from(f.nativeElement.querySelectorAll('button[aria-pressed]')) as HTMLButtonElement[];
        expect(stars.length).toBe(1);
        expect(stars[0].getAttribute('aria-pressed')).toBe('false');
        expect(stars[0].getAttribute('aria-label')).toContain('favorites');
        await expectNoA11yViolations(f.nativeElement);

        // clicking stars the leaf → re-renders pressed
        stars[0].click();
        f.detectChanges();
        expect(f.nativeElement.querySelector('button[aria-pressed]')!.getAttribute('aria-pressed')).toBe('true');
    });

    // Open point O1 — who may curate. The server has always gated PUT /nav/menus on canAuthorWorkbench;
    // the pane offered edits to everyone, so a non-curator could build a tree and only then be 403'd.
    // Curation affordances now mirror the server gate, while favoriting (personal, client-local, never PUT)
    // stays available to every viewer.
    it('hides curation affordances without canAuthorWorkbench but keeps favorites usable', async () => {
        const leafTree = {
            space: 'default',
            version: 1 as const,
            nodes: [
                {
                    id: 'rev',
                    title: 'Revenue',
                    children: [{ id: 'd1', title: 'Dash one', binding: { kind: 'dashboard' as const, componentId: 'c1' } }],
                },
            ],
        };
        TestBed.configureTestingModule({
            imports: [MenuBuilderComponent],
            providers: [
                provideNoopAnimations(),
                { provide: SpacesService, useValue: { currentSpaceId: signal<string | null>(null) } },
                { provide: NavigationService, useValue: { get: () => of(null) } },
                { provide: NavMenusService, useValue: { get: () => of(leafTree), put: () => of(leafTree) } },
                { provide: ToastrService, useValue: { error: () => {} } },
                { provide: MatDialog, useValue: { open: () => ({ afterClosed: () => of(undefined) }) } },
                { provide: LensService, useValue: { canAuthorWorkbench: () => false } },
            ],
        });
        const f = TestBed.createComponent(MenuBuilderComponent);
        f.detectChanges();

        // No "Add menu" / "Load example", and no per-node actions menu.
        expect(f.nativeElement.textContent).not.toContain('Add menu');
        expect(f.nativeElement.textContent).not.toContain('Load example');
        expect(f.nativeElement.querySelector('button[aria-label^="Actions for"]')).toBeNull();

        // The tree still renders, and the personal favorite toggle still works.
        expect(f.nativeElement.textContent).toContain('Revenue');
        const star = f.nativeElement.querySelector('button[aria-pressed]') as HTMLButtonElement;
        expect(star).not.toBeNull();
        star.click();
        f.detectChanges();
        expect(f.nativeElement.querySelector('button[aria-pressed]')!.getAttribute('aria-pressed')).toBe('true');
        await expectNoA11yViolations(f.nativeElement);
    });

    it('guards the mutating methods, not just the buttons, for a non-curator', () => {
        TestBed.configureTestingModule({
            imports: [MenuBuilderComponent],
            providers: [
                provideNoopAnimations(),
                { provide: SpacesService, useValue: { currentSpaceId: signal<string | null>(null) } },
                { provide: NavigationService, useValue: { get: () => of(null) } },
                { provide: NavMenusService, useValue: { get: () => of(emptyTree('default')), put: () => of(emptyTree('default')) } },
                { provide: ToastrService, useValue: { error: () => {} } },
                { provide: MatDialog, useValue: { open: () => ({ afterClosed: () => of(undefined) }) } },
                { provide: LensService, useValue: { canAuthorWorkbench: () => false } },
            ],
        });
        const f = TestBed.createComponent(MenuBuilderComponent);
        f.detectChanges();

        f.componentInstance.addMenu();
        f.componentInstance.loadExample();
        f.detectChanges();
        expect(f.componentInstance.nodes().length).toBe(0);
    });
});
