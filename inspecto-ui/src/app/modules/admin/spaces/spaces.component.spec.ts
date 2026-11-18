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
import { AttachSpaceDialog } from './attach-space.dialog';
import { DataFolderDialog } from './data-folder.dialog';

const SPACES: Space[] = [
    { id: 'alpha', displayName: 'Alpha', description: 'first project', createdAt: '2026-06-23 10:00:00' },
    { id: 'beta', displayName: '', description: '', createdAt: '' },
];

interface Extra {
    /** The server has no allowed folders (SPACE-LOCATIONS-1). */
    disabled?: boolean;
    svc?: object;
    confirm?: object;
    toastr?: object;
}

function create(multi: boolean, list: Space[], canAdminister = true, dialog: object = {}, extra: Extra = {}) {
    const stub = {
        multiSpace: signal(multi),
        availableSpaces: signal(list),
        currentSpaceId: signal<string | null>(multi ? 'alpha' : null),
        refresh: () => of(list),
        selectSpace: vi.fn(),
        dataSources: () => of([]),
        locationsDisabled: signal(extra.disabled ?? false),
        ...extra.svc,
    } as unknown as SpacesService;
    TestBed.configureTestingModule({
        imports: [SpacesComponent],
        providers: [
            provideNoopAnimations(),
            { provide: SpacesService, useValue: stub },
            { provide: MatDialog, useValue: dialog },
            { provide: ToastrService, useValue: extra.toastr ?? {} },
            { provide: InspectoConfirmService, useValue: extra.confirm ?? {} },
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

    // ── SPACE-LOCATIONS-1: Space folder and Data folder ──────────────────────────

    const LOCATED: Space[] = [
        {
            ...SPACES[0],
            location: {
                spaceFolder: '/srv/elsewhere/alpha',
                dataFolder: '/srv/data/alpha',
                referenced: true,
                holdsData: true,
            },
        },
        {
            ...SPACES[1],
            location: {
                spaceFolder: '/srv/spaces/beta',
                dataFolder: '/srv/spaces/beta/data',
                referenced: false,
                holdsData: false,
            },
        },
    ];

    it('shows each Space folder and Data folder to an administrator, with the folder actions', async () => {
        const fixture = create(true, LOCATED, true);
        const el = fixture.nativeElement as HTMLElement;
        const rows = Array.from(el.querySelectorAll('[data-testid="space-location"]'));
        expect(rows.length).toBe(2);
        expect(rows[0].textContent).toContain('/srv/elsewhere/alpha');
        expect(rows[0].textContent).toContain('/srv/data/alpha');
        expect(el.querySelector('[data-testid="attach-space"]')?.textContent).toContain('Attach existing Space');
        // Change data folder only on the Space that holds no data (beta)
        expect(el.querySelectorAll('[data-testid="change-data-folder"]').length).toBe(1);
        const c = fixture.componentInstance;
        expect(c.canDetach(LOCATED[0])).toBe(true);
        expect(c.canDetach(LOCATED[1])).toBe(false);
        expect(c.canChangeDataFolder(LOCATED[0])).toBe(false);
        expect(c.canChangeDataFolder(LOCATED[1])).toBe(true);
        await expectNoA11yViolations(el);
    });

    it('offers no folder action and no paths without canAdminister', () => {
        const fixture = create(true, LOCATED, false);
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('[data-testid="space-location"]')).toBeNull();
        expect(el.querySelector('[data-testid="attach-space"]')).toBeNull();
        expect(el.querySelector('[data-testid="change-data-folder"]')).toBeNull();
        expect(fixture.componentInstance.canDetach(LOCATED[0])).toBe(false);
    });

    it('on a server with no allowed folders it says what the administrator must set and hides the folder actions', async () => {
        const fixture = create(true, LOCATED, true, {}, { disabled: true });
        const el = fixture.nativeElement as HTMLElement;
        const alert = el.querySelector('[data-testid="locations-disabled"]');
        expect(alert?.textContent).toContain('spaces.allowed.roots');
        expect(alert?.textContent).toContain('SPACES_ALLOWED_ROOTS');
        expect(alert?.textContent).toContain('restart');
        expect(el.querySelector('[data-testid="attach-space"]')).toBeNull();
        expect(el.querySelector('[data-testid="change-data-folder"]')).toBeNull();
        expect(fixture.componentInstance.canDetach(LOCATED[0])).toBe(false);
        await expectNoA11yViolations(el);
    });

    it('"Attach existing Space" opens the attach dialog and reloads after an attach', () => {
        const open = vi.fn(() => ({ afterClosed: () => of(SPACES[0]) }));
        const fixture = create(true, LOCATED, true, { open });
        const reload = vi.spyOn(fixture.componentInstance, 'reload');
        (fixture.nativeElement.querySelector('[data-testid="attach-space"]') as HTMLButtonElement).click();
        expect(open).toHaveBeenCalledWith(AttachSpaceDialog, expect.anything());
        expect(reload).toHaveBeenCalled();
    });

    it('"Change data folder" opens its dialog for that Space', () => {
        const open = vi.fn(() => ({ afterClosed: () => of(undefined) }));
        const fixture = create(true, LOCATED, true, { open });
        (fixture.nativeElement.querySelector('[data-testid="change-data-folder"]') as HTMLButtonElement).click();
        expect(open).toHaveBeenCalledWith(DataFolderDialog, expect.objectContaining({ data: { space: LOCATED[1] } }));
    });

    it('Detach asks first, saying the folders stay on disk, then detaches and reloads', async () => {
        const confirm = vi.fn(async () => true);
        const detach = vi.fn(() => of(null));
        const success = vi.fn();
        const fixture = create(true, LOCATED, true, {}, { confirm: { confirm }, svc: { detach }, toastr: { success } });
        await fixture.componentInstance.detach(LOCATED[0]);
        const [message] = confirm.mock.calls[0] as unknown as [string];
        expect(message).toContain('stay on disk');
        expect(detach).toHaveBeenCalledWith('alpha');
        expect(success).toHaveBeenCalled();
        // the detached Space was the active one: the selection is dropped
        expect(fixture.componentInstance.spaces.selectSpace).toHaveBeenCalledWith(null);
    });

    it('Detach does nothing when the confirm is declined', async () => {
        const detach = vi.fn(() => of(null));
        const fixture = create(true, LOCATED, true, {}, { confirm: { confirm: async () => false }, svc: { detach } });
        await fixture.componentInstance.detach(LOCATED[0]);
        expect(detach).not.toHaveBeenCalled();
    });
});
