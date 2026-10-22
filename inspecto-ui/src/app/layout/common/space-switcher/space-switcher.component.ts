import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, input, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatDividerModule } from '@angular/material/divider';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { LensService, Space, SpacesService } from 'app/inspecto/api';
import { SessionService } from 'app/inspecto/auth/session.service';
import { SampleSpaceDialog } from 'app/inspecto/spaces/sample-space.dialog';
import { SpaceFormDialog } from 'app/inspecto/spaces/space-form.dialog';

/**
 * Header control that shows the active space and switches between them. Rendered on a multi-space (discover)
 * server with spaces to switch between ({@link SpacesService.showSwitcher}) — and, with ZERO Spaces, whenever the
 * user may create one (LA-ONBOARD-1 OB-11, F-2: before, a fresh install had no way to make its first Space).
 *
 * Switching persists the new space ({@link SpacesService.selectSpace}) and then hard-reloads at the
 * app root, which lands on the new Space's landing page (UIE-7 `landingGuard`; Home when it names none):
 * every feature component fetches on init and holds its own state, so a reload is the simplest correct way to re-scope the whole app to the new space
 * (and a deep link valid in the old space may not exist in the new one).
 *
 * "New Space…" at the bottom of the menu opens the same {@link SpaceFormDialog} as Settings → Spaces and,
 * on a successful create, switches straight to the new Space (builder pilot 2026-09-25: a Space created
 * from Settings was not obviously something you then had to activate). "New from sample…" opens
 * {@link SampleSpaceDialog}: one call creates a Space from a template that ships samples, loads and runs them
 * (OB-12). Both are offered to whoever may create a Space ({@link canCreate}): anyone with no sign-in (the server
 * gates `POST /spaces` on `canAdminister` only for a signed-in Subject), otherwise {@link LensService.canAdminister}.
 *
 * A URL carrying `?space=<id>` (a menu screen link into another Space, a shared link) switches to that
 * Space and reloads at the SAME URL, so the link opens its screen in the right Space. An unknown id is
 * ignored, so a bad link cannot reload-loop.
 */
@Component({
    selector: 'inspecto-space-switcher',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatDividerModule, MatIconModule, MatMenuModule, MatTooltipModule],
    template: `
        @if (visible()) {
            <button
                mat-stroked-button
                [matMenuTriggerFor]="menu"
                class="min-w-0 max-sm:!px-2"
                [matTooltip]="spaces.availableSpaces().length ? 'Switch space' : 'Create a Space'"
                [attr.aria-label]="label() + (spaces.availableSpaces().length ? ', switch space' : ', create a Space')"
            >
                <mat-icon class="icon-size-5" svgIcon="heroicons_outline:square-3-stack-3d"></mat-icon>
                <!-- label collapses to the icon on phones so the header row fits 375px (Wave 5 P2) -->
                <span class="ml-2 hidden max-w-40 truncate md:inline">{{ label() }}</span>
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
                @if (canCreate()) {
                    @if (spaces.availableSpaces().length) {
                        <mat-divider></mat-divider>
                    }
                    <button mat-menu-item (click)="newSpace()">
                        <mat-icon svgIcon="heroicons_outline:plus"></mat-icon>
                        <span>New Space…</span>
                    </button>
                    <button mat-menu-item (click)="newFromSample()">
                        <mat-icon svgIcon="heroicons_outline:beaker"></mat-icon>
                        <span>New from sample…</span>
                    </button>
                }
            </mat-menu>
        }
    `,
})
export class SpaceSwitcherComponent implements OnInit {
    readonly spaces = inject(SpacesService);
    protected lens = inject(LensService);
    private session = inject(SessionService);

    /** The Inspecto LA create: New Space asks the name and What kind of data? only ({@link SpaceFormDialog}). */
    readonly dataKind = input(false);
    /** The sample New from sample picks first (e.g. `la-sample` in Inspecto LA). */
    readonly preferredSample = input<string | undefined>(undefined);

    /** May create a Space: no sign-in, or {@link LensService.canAdminister} (OB-11). */
    readonly canCreate = computed(() => this.session.authMode() !== 'oidc' || this.lens.canAdminister());
    /** Spaces to switch between, or none yet and the user may create the first. */
    readonly visible = computed(() => this.spaces.showSwitcher() || (this.spaces.multiSpace() && this.canCreate()));
    private router = inject(Router);
    private dialog = inject(MatDialog);
    private destroyRef = inject(DestroyRef);

    ngOnInit(): void {
        this.spaces.refresh().subscribe(() => {
            this.followSpaceParam(this.router.url);
            this.router.events
                .pipe(
                    filter((e): e is NavigationEnd => e instanceof NavigationEnd),
                    takeUntilDestroyed(this.destroyRef),
                )
                .subscribe((e) => this.followSpaceParam(e.urlAfterRedirects));
        });
    }

    /** Switch to the Space a `?space=<id>` URL names, then reload in place (see the class doc). */
    protected followSpaceParam(url: string): void {
        const id = this.router.parseUrl(url).queryParams['space'];
        if (!id || id === this.spaces.currentSpaceId()) return;
        if (!this.spaces.availableSpaces().some((s) => s.id === id)) return;
        this.spaces.selectSpace(id);
        this.reload();
    }

    /** A hard reload in place (its own seam so a spec can stub it — jsdom cannot reload). */
    protected reload(): void {
        window.location.reload();
    }

    /** The active space's display label (its name, falling back to its id; "No Space yet" before the first). */
    label(): string {
        const c = this.spaces.currentSpace();
        if (c) return c.displayName || c.id;
        return this.spaces.availableSpaces().length ? (this.spaces.currentSpaceId() ?? '') : 'No Space yet';
    }

    switch(s: Space): void {
        if (s.id === this.spaces.currentSpaceId()) return;
        this.spaces.selectSpace(s.id);
        this.router.navigateByUrl('/').then(() => window.location.reload());
    }

    /** Create a Space through the shared form, then make it the active one. */
    newSpace(): void {
        this.dialog
            .open(SpaceFormDialog, { width: '520px', maxHeight: '90vh', data: { dataKind: this.dataKind() } })
            .afterClosed()
            .subscribe((created?: Space) => {
                if (created) this.switch(created);
            });
    }

    /** Create a Space from a sample (loaded and run), then make it the active one. */
    newFromSample(): void {
        this.dialog
            .open(SampleSpaceDialog, { width: '520px', maxHeight: '90vh', data: { preferred: this.preferredSample() } })
            .afterClosed()
            .subscribe((created?: Space) => {
                if (created) this.switch(created);
            });
    }
}
