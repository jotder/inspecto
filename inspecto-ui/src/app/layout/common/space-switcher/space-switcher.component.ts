import { ChangeDetectionStrategy, Component, inject, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatDividerModule } from '@angular/material/divider';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { LensService, Space, SpacesService } from 'app/inspecto/api';
import { SpaceFormDialog } from 'app/inspecto/spaces/space-form.dialog';

/**
 * Header control that shows the active space and switches between them. Only rendered on a
 * multi-space (discover) server with spaces to switch between ({@link SpacesService.showSwitcher}).
 *
 * Switching persists the new space ({@link SpacesService.selectSpace}) and then hard-reloads at the
 * app root, which lands on the new Space's landing page (UIE-7 `landingGuard`; Home when it names none):
 * every feature component fetches on init and holds its own state, so a reload is the simplest correct way to re-scope the whole app to the new space
 * (and a deep link valid in the old space may not exist in the new one).
 *
 * "New space…" at the bottom of the menu opens the same {@link SpaceFormDialog} as Settings → Spaces and,
 * on a successful create, switches straight to the new Space (builder pilot 2026-09-25: a Space created
 * from Settings was not obviously something you then had to activate). Offered only with
 * {@link LensService.canAdminister} — `POST /spaces` refuses anyone else once a Space is hosted, and the
 * switcher only renders when Spaces are hosted.
 */
@Component({
    selector: 'inspecto-space-switcher',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatDividerModule, MatIconModule, MatMenuModule, MatTooltipModule],
    template: `
        @if (spaces.showSwitcher()) {
            <button
                mat-stroked-button
                [matMenuTriggerFor]="menu"
                class="min-w-0"
                matTooltip="Switch space"
                aria-label="Switch space"
            >
                <mat-icon class="icon-size-5" svgIcon="heroicons_outline:square-3-stack-3d"></mat-icon>
                <!-- label collapses to the icon on phones so the header row fits 375px (Wave 5 P2) -->
                <span class="ml-2 hidden max-w-40 truncate sm:inline">{{ label() }}</span>
                <mat-icon class="icon-size-5" svgIcon="heroicons_outline:chevron-down"></mat-icon>
            </button>
            <mat-menu #menu="matMenu">
                @for (s of spaces.availableSpaces(); track s.id) {
                    <button
                        mat-menu-item
                        (click)="switch(s)"
                        [attr.aria-current]="s.id === spaces.currentSpaceId() ? 'true' : null"
                    >
                        <mat-icon
                            [svgIcon]="
                                s.id === spaces.currentSpaceId()
                                    ? 'heroicons_outline:check-circle'
                                    : 'heroicons_outline:square-3-stack-3d'
                            "
                        ></mat-icon>
                        <span>{{ s.displayName || s.id }}</span>
                    </button>
                }
                @if (lens.canAdminister()) {
                    <mat-divider></mat-divider>
                    <button mat-menu-item (click)="newSpace()">
                        <mat-icon svgIcon="heroicons_outline:plus"></mat-icon>
                        <span>New space…</span>
                    </button>
                }
            </mat-menu>
        }
    `,
})
export class SpaceSwitcherComponent implements OnInit {
    readonly spaces = inject(SpacesService);
    protected lens = inject(LensService);
    private router = inject(Router);
    private dialog = inject(MatDialog);

    ngOnInit(): void {
        this.spaces.refresh().subscribe();
    }

    /** The active space's display label (its name, falling back to its id). */
    label(): string {
        const c = this.spaces.currentSpace();
        return c ? c.displayName || c.id : (this.spaces.currentSpaceId() ?? '');
    }

    switch(s: Space): void {
        if (s.id === this.spaces.currentSpaceId()) return;
        this.spaces.selectSpace(s.id);
        this.router.navigateByUrl('/').then(() => window.location.reload());
    }

    /** Create a Space through the shared form, then make it the active one. */
    newSpace(): void {
        this.dialog
            .open(SpaceFormDialog, { width: '520px', maxHeight: '90vh' })
            .afterClosed()
            .subscribe((created?: Space) => {
                if (created) this.switch(created);
            });
    }
}
