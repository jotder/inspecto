import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';
import { LensService, Space, SpacesService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { SampleSpaceDialog } from 'app/inspecto/spaces/sample-space.dialog';
import { SpacesComponent } from './spaces.component';

const SPACES: Space[] = [
    { id: 'alpha', displayName: 'Alpha', description: 'first project', createdAt: '2026-06-23 10:00:00' },
    { id: 'beta', displayName: '', description: '', createdAt: '' },
];

function create(multi: boolean, list: Space[], canAdminister = true, dialog: object = {}) {
    const stub = {
        multiSpace: signal(multi),
        availableSpaces: signal(list),
        currentSpaceId: signal<string | null>(multi ? 'alpha' : null),
        refresh: () => of(list),
        selectSpace: vi.fn(),
        dataSources: () => of([]),
    } as unknown as SpacesService;
    TestBed.configureTestingModule({
        imports: [SpacesComponent],
        providers: [
            provideNoopAnimations(),
            { provide: SpacesService, useValue: stub },
            { provide: MatDialog, useValue: dialog },
            { provide: ToastrService, useValue: {} },
            { provide: InspectoConfirmService, useValue: {} },
            { provide: LensService, useValue: { canAdminister: signal(canAdminister) } },
        ],
    });
    const fixture = TestBed.createComponent(SpacesComponent);
    fixture.detectChanges();
    return fixture;
}

describe('SpacesComponent', () => {
    it('lists spaces with no a11y violations', async () => {
        const fixture = create(true, SPACES);
        expect(fixture.componentInstance.spaces.availableSpaces().length).toBe(2);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('shows single-tenant guidance with no a11y violations', async () => {
        const fixture = create(false, []);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('hides "Create from bundle" without canAdminister (UI-CAPABILITY-AFFORDANCE-1)', () => {
        const fixture = create(true, SPACES, false);
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('[aria-label="Create from bundle"]')).toBeNull();
    });

    it('shows "Create from bundle" with canAdminister', () => {
        const fixture = create(true, SPACES, true);
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('[aria-label="Create from bundle"]')).not.toBeNull();
    });

    it('hides "New space" and "New from template" without canAdminister — the server would refuse them', () => {
        const text = (create(true, SPACES, false).nativeElement as HTMLElement).textContent ?? '';
        expect(text).not.toContain('New space');
        expect(text).not.toContain('New from template');
    });

    it('shows "New space" and "New from template" with canAdminister', () => {
        const text = (create(true, SPACES, true).nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('New space');
        expect(text).toContain('New from template');
    });

    it('offers "New from sample" only with canAdminister (ONBOARDING-FIRST-RUN-1 C4)', () => {
        const q = '[data-testid="new-from-sample"]';
        expect((create(true, SPACES, true).nativeElement as HTMLElement).querySelector(q)).not.toBeNull();
        TestBed.resetTestingModule();
        expect((create(true, SPACES, false).nativeElement as HTMLElement).querySelector(q)).toBeNull();
    });

    it('"New from sample" opens the sample dialog on orders-starter and enters the Space it made', () => {
        const made: Space = { id: 'sample', displayName: 'Sample', description: '', createdAt: '' };
        const open = vi.fn(() => ({ afterClosed: () => of(made) }));
        const fixture = create(true, SPACES, true, { open });
        const assign = vi.fn();
        vi.stubGlobal('location', { ...window.location, assign });
        try {
            (fixture.nativeElement.querySelector('[data-testid="new-from-sample"]') as HTMLButtonElement).click();
            expect(open).toHaveBeenCalledWith(
                SampleSpaceDialog,
                expect.objectContaining({ data: { preferred: 'orders-starter' } }),
            );
            expect(fixture.componentInstance.spaces.selectSpace).toHaveBeenCalledWith('sample');
            expect(assign).toHaveBeenCalledWith('/');
        } finally {
            vi.unstubAllGlobals();
        }
    });

    it('offers "Compare Spaces" only to canAdminister with at least two Spaces — the server refuses it otherwise', () => {
        const q = '[aria-label="Compare Spaces"]';
        expect((create(true, SPACES, true).nativeElement as HTMLElement).querySelector(q)).not.toBeNull();
    });

    it('hides "Compare Spaces" without canAdminister', () => {
        expect(
            (create(true, SPACES, false).nativeElement as HTMLElement).querySelector('[aria-label="Compare Spaces"]'),
        ).toBeNull();
    });

    it('hides "Compare Spaces" when fewer than two Spaces are hosted', () => {
        const el = create(true, SPACES.slice(0, 1), true).nativeElement as HTMLElement;
        expect(el.querySelector('[aria-label="Compare Spaces"]')).toBeNull();
    });

    it('shows the integrity count of each Space with its three numbers, and nothing for a Space without a ready report (LC-4)', async () => {
        const list: Space[] = [
            { ...SPACES[0], integrity: { status: 'ready', broken: 2, duplicates: 0, changedOutside: 1 } },
            { ...SPACES[1], integrity: { status: 'computing' } },
            { id: 'gamma', displayName: 'Gamma', description: '', createdAt: '' },
        ];
        const el = create(true, list).nativeElement as HTMLElement;
        const counts = Array.from(el.querySelectorAll('[data-testid="space-integrity"]'));
        expect(counts.length).toBe(1);
        expect(counts[0].textContent).toContain('3 integrity findings');
        expect(counts[0].textContent).toContain('Broken links: 2 · Duplicates: 0 · Changed outside the server: 1');
        expect(el.textContent).not.toContain('0 integrity findings');
        await expectNoA11yViolations(el);
    });
});
