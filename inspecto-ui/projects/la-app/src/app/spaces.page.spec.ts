import { ChangeDetectionStrategy, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';
import { LensService, Space, SPACE_LOCATIONS_DISABLED_MESSAGE, SpacesService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { AttachSpaceDialog } from 'app/modules/admin/spaces/attach-space.dialog';
import { DataFolderDialog } from 'app/modules/admin/spaces/data-folder.dialog';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { SpacesSettingsComponent } from './spaces.page';

const SPACES: Space[] = [
    {
        id: 'empty',
        displayName: 'Empty',
        description: '',
        createdAt: '',
        location: { spaceFolder: '/srv/spaces/empty', dataFolder: '/srv/spaces/empty', holdsData: false },
    },
    {
        id: 'kept',
        displayName: 'Kept',
        description: '',
        createdAt: '',
        location: { spaceFolder: '/mnt/kept', dataFolder: '/mnt/kept-data', referenced: true, holdsData: true },
    },
];

function render(opts: { admin?: boolean; disabled?: boolean; confirm?: boolean } = {}) {
    const open = vi.fn(() => ({ afterClosed: () => of(undefined) }));
    const spaces = {
        availableSpaces: signal(SPACES),
        multiSpace: signal(true),
        locationsDisabled: signal(opts.disabled ?? false),
        currentSpaceId: signal<string | null>('kept'),
        refresh: vi.fn(() => of(SPACES)),
        detach: vi.fn(() => of({ id: 'kept', detached: true })),
        selectSpace: vi.fn(),
    };
    TestBed.configureTestingModule({
        providers: [
            provideNoopAnimations(),
            { provide: ToastrService, useValue: { success: vi.fn(), error: vi.fn(), warning: vi.fn() } },
            { provide: InspectoConfirmService, useValue: { confirm: () => Promise.resolve(opts.confirm ?? true) } },
            { provide: LensService, useValue: { canAdminister: signal(opts.admin ?? true) } },
            { provide: SpacesService, useValue: spaces },
        ],
    });
    TestBed.overrideProvider(MatDialog, { useValue: { open } });
    TestBed.overrideComponent(SpacesSettingsComponent, {
        set: { changeDetection: ChangeDetectionStrategy.Eager },
    });
    const fixture = TestBed.createComponent(SpacesSettingsComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const row = (id: string) => el.querySelector(`[data-space="${id}"]`) as HTMLElement;
    return { fixture, el, row, open, spaces };
}

describe('SpacesSettingsComponent (Settings > Spaces in Inspecto LA)', () => {
    it('lists each Space with its Space folder and Data folder (no violations)', async () => {
        const { el, row, spaces } = render();
        expect(spaces.refresh).toHaveBeenCalled();
        expect(row('empty').textContent).toContain('/srv/spaces/empty');
        expect(row('kept').querySelector('[data-testid="space-location"]')!.textContent).toContain('/mnt/kept-data');
        await expectNoA11yViolations(el);
    });

    it('offers each action only where it applies: Change data folder while no data, Detach for a referenced Space', () => {
        const { el, row } = render();
        expect(el.querySelector('[data-testid="attach-space"]')).not.toBeNull();
        expect(row('empty').querySelector('[data-testid="change-data-folder"]')).not.toBeNull();
        expect(row('empty').querySelector('[data-testid="detach-space"]')).toBeNull();
        expect(row('kept').querySelector('[data-testid="change-data-folder"]')).toBeNull();
        expect(row('kept').querySelector('[data-testid="detach-space"]')).not.toBeNull();
    });

    it('opens the console dialogs: Attach existing Space and Change data folder for the chosen Space', () => {
        const { el, row, open } = render();
        (el.querySelector('[data-testid="attach-space"]') as HTMLButtonElement).click();
        expect(open).toHaveBeenCalledWith(AttachSpaceDialog, expect.anything());
        (row('empty').querySelector('[data-testid="change-data-folder"]') as HTMLButtonElement).click();
        expect(open).toHaveBeenCalledWith(DataFolderDialog, expect.objectContaining({ data: { space: SPACES[0] } }));
    });

    it('detaches only after the confirm, leaving the current Space', async () => {
        const { row, spaces, fixture } = render();
        (row('kept').querySelector('[data-testid="detach-space"]') as HTMLButtonElement).click();
        await fixture.whenStable();
        expect(spaces.detach).toHaveBeenCalledWith('kept');
        expect(spaces.selectSpace).toHaveBeenCalledWith(null);
    });

    it('does not detach when the confirm is declined', async () => {
        const { row, spaces, fixture } = render({ confirm: false });
        (row('kept').querySelector('[data-testid="detach-space"]') as HTMLButtonElement).click();
        await fixture.whenStable();
        expect(spaces.detach).not.toHaveBeenCalled();
    });

    it('with folder choices switched off, names spaces.allowed.roots and hides every folder action (no violations)', async () => {
        const { el } = render({ disabled: true });
        expect(el.querySelector('[data-testid="locations-disabled"]')!.textContent).toContain(
            SPACE_LOCATIONS_DISABLED_MESSAGE,
        );
        expect(el.textContent).toContain('SPACES_ALLOWED_ROOTS');
        for (const a of ['attach-space', 'change-data-folder', 'detach-space'])
            expect(el.querySelector(`[data-testid="${a}"]`)).toBeNull();
        expect(el.textContent).toContain('/mnt/kept');
        await expectNoA11yViolations(el);
    });

    it('offers no folder action to a non-administrator', () => {
        const { el } = render({ admin: false });
        for (const a of ['attach-space', 'change-data-folder', 'detach-space'])
            expect(el.querySelector(`[data-testid="${a}"]`)).toBeNull();
    });
});
