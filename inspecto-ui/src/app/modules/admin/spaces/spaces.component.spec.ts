import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it } from 'vitest';
import { LensService, Space, SpacesService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { SpacesComponent } from './spaces.component';

const SPACES: Space[] = [
    { id: 'alpha', displayName: 'Alpha', description: 'first project', createdAt: '2026-06-23 10:00:00' },
    { id: 'beta', displayName: '', description: '', createdAt: '' },
];

function create(multi: boolean, list: Space[], canAdminister = true) {
    const stub = {
        multiSpace: signal(multi),
        availableSpaces: signal(list),
        currentSpaceId: signal<string | null>(multi ? 'alpha' : null),
        refresh: () => of(list),
        selectSpace: () => {},
        dataSources: () => of([]),
    } as unknown as SpacesService;
    TestBed.configureTestingModule({
        imports: [SpacesComponent],
        providers: [
            provideNoopAnimations(),
            { provide: SpacesService, useValue: stub },
            { provide: MatDialog, useValue: {} },
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
});
