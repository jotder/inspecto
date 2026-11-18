import { ChangeDetectionStrategy, Component, computed, inject, OnInit } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, LensService, Space, SPACE_LOCATIONS_DISABLED_MESSAGE, SpacesService } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { AttachSpaceDialog } from 'app/modules/admin/spaces/attach-space.dialog';
import { DataFolderData, DataFolderDialog } from 'app/modules/admin/spaces/data-folder.dialog';

/**
 * Settings > **Spaces** (SPACE-LOCATIONS-UI-1, the Inspecto LA side): the Spaces this administrator sees, each with its
 * Space folder and Data folder, and the console's folder actions reused whole (the same dialogs, the same
 * `SpacesService` calls): Attach existing Space, Change data folder (only while the Space holds no data) and Detach
 * (only a Space attached by reference; its folders stay on disk). Listed only for an administrator on a multi-Space
 * server; the server sends `location` to administrators only and refuses every folder call anyway.
 */
@Component({
    selector: 'la-app-spaces-settings',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [InspectoAlertComponent, InspectoPageHeaderComponent, MatButtonModule],
    template: `
        <div class="flex flex-col gap-6 p-6">
            <inspecto-page-header
                title="Spaces"
                subtitle="The Spaces on this server and the folders each one keeps its files in."
                [inset]="false"
            >
                @if (canManageFolders()) {
                    <button actions mat-flat-button color="primary" data-testid="attach-space" (click)="attach()">
                        Attach existing Space…
                    </button>
                }
            </inspecto-page-header>
            @if (spaces.locationsDisabled()) {
                <inspecto-alert variant="info" data-testid="locations-disabled">{{ disabledMessage }}</inspecto-alert>
            }
            <ul class="m-0 flex list-none flex-col gap-3 p-0" data-testid="la-spaces">
                @for (s of spaces.availableSpaces(); track s.id) {
                    <li
                        class="flex flex-col gap-2 rounded-lg border border-gray-200 p-4 dark:border-gray-700"
                        [attr.data-space]="s.id"
                    >
                        <div class="flex flex-wrap items-center gap-x-3 gap-y-2">
                            <span class="flex min-w-0 flex-auto flex-col">
                                <span class="font-medium">{{ s.displayName || s.id }}</span>
                                <span class="text-secondary font-mono text-xs">{{ s.id }}</span>
                            </span>
                            @if (canChangeDataFolder(s)) {
                                <button
                                    mat-stroked-button
                                    type="button"
                                    data-testid="change-data-folder"
                                    (click)="changeDataFolder(s)"
                                >
                                    Change data folder…
                                </button>
                            }
                            @if (canDetach(s)) {
                                <button mat-stroked-button type="button" data-testid="detach-space" (click)="detach(s)">
                                    Detach…
                                </button>
                            }
                        </div>
                        @if (s.location; as loc) {
                            <dl
                                class="m-0 grid grid-cols-1 gap-x-6 gap-y-1 text-sm sm:grid-cols-2"
                                data-testid="space-location"
                            >
                                <div class="flex min-w-0 flex-col">
                                    <dt class="text-secondary text-xs">Space folder</dt>
                                    <dd class="m-0 font-mono [overflow-wrap:anywhere]">{{ loc.spaceFolder || '—' }}</dd>
                                </div>
                                <div class="flex min-w-0 flex-col">
                                    <dt class="text-secondary text-xs">Data folder</dt>
                                    <dd class="m-0 font-mono [overflow-wrap:anywhere]">{{ loc.dataFolder || '—' }}</dd>
                                </div>
                            </dl>
                        }
                    </li>
                } @empty {
                    <li class="text-secondary text-sm">No Spaces on this server yet.</li>
                }
            </ul>
        </div>
    `,
})
export class SpacesSettingsComponent implements OnInit {
    protected readonly spaces = inject(SpacesService);
    private readonly lens = inject(LensService);
    private readonly dialog = inject(MatDialog);
    private readonly toastr = inject(ToastrService);
    private readonly confirm = inject(InspectoConfirmService);
    protected readonly disabledMessage = SPACE_LOCATIONS_DISABLED_MESSAGE;

    /** The folder actions: an administrator's, on a multi-Space server with allowed folders set (as the console). */
    readonly canManageFolders = computed(
        () => this.spaces.multiSpace() && this.lens.canAdminister() && !this.spaces.locationsDisabled(),
    );

    ngOnInit(): void {
        this.reload();
    }

    private reload(): void {
        this.spaces.refresh().subscribe({
            error: (e) => this.toastr.warning(apiErrorMessage(e, 'Could not load the Spaces.')),
        });
    }

    /** Only a Space attached by reference can be detached. */
    canDetach(s: Space): boolean {
        return this.canManageFolders() && s.location?.referenced === true;
    }

    /** Only while the Space holds no data. */
    canChangeDataFolder(s: Space): boolean {
        return this.canManageFolders() && s.location?.holdsData === false;
    }

    attach(): void {
        this.dialog
            .open(AttachSpaceDialog, { width: '560px', maxHeight: '90vh' })
            .afterClosed()
            .subscribe((attached?: Space) => {
                if (attached) this.reload();
            });
    }

    changeDataFolder(s: Space): void {
        const data: DataFolderData = { space: s };
        this.dialog
            .open(DataFolderDialog, { data, width: '560px', maxHeight: '90vh' })
            .afterClosed()
            .subscribe((updated?: Space) => {
                if (updated) this.reload();
            });
    }

    async detach(s: Space): Promise<void> {
        const go = await this.confirm.confirm(
            `Detach “${s.displayName || s.id}”? It stops and leaves this server. Its Space folder and Data folder ` +
                'stay on disk, and you can attach it again later.',
            'Detach Space',
        );
        if (!go) return;
        this.spaces.detach(s.id).subscribe({
            next: () => {
                this.toastr.success(`Space “${s.id}” detached; its folders are kept`);
                if (s.id === this.spaces.currentSpaceId()) this.spaces.selectSpace(null);
                this.reload();
            },
            error: (e) => {
                if (!this.spaces.locationsDisabled())
                    this.toastr.error(apiErrorMessage(e, `Could not detach “${s.id}”.`));
            },
        });
    }
}
