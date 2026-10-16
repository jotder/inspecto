import { NgComponentOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, Type, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatIconModule } from '@angular/material/icon';
import { ActivatedRoute, Router } from '@angular/router';
import { LensService } from 'app/inspecto/api';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import { LaLimitsService } from '@inspecto/link-analysis/link-analysis/la-limits.service';
import { AccessPageComponent } from './access.page';
import {
    AnalysisLimitsSettingsComponent,
    HostResourcesSettingsComponent,
    MyBrowserSettingsComponent,
    SpaceSettingsComponent,
} from './settings-sections';

/** Who a section is for: the signed-in user's own values, this Space, or the whole server. */
type Audience = 'me' | 'space' | 'server';

interface SettingsDrawer {
    readonly id: string;
    readonly title: string;
    readonly description: string;
    readonly icon: string;
    readonly audience: Audience;
    readonly component: Type<unknown>;
    /** Whether THIS user may change the section; without it the section is shown read-only. */
    readonly canChange: () => boolean;
}

const AUDIENCES: readonly { id: Audience; label: string }[] = [
    { id: 'me', label: 'My settings' },
    { id: 'space', label: 'This Space' },
    { id: 'server', label: 'This server' },
];

/**
 * Settings, the way the console lays it out (a menu of drawers on the left, the chosen one in a common panel on the
 * right, deep-linked by `:section`) but compact, and split by who the setting is for: a user's own values, this Space's
 * settings, limits and role definitions, and the server's host resources. Every section is listed; one the user may not
 * change opens read-only and says so in the menu. The server stays the judge: its 403 is shown, never worked around.
 */
@Component({
    selector: 'la-app-settings-page',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [NgComponentOutlet, MatIconModule, ChipComponent],
    template: `
        <div class="flex min-w-0 flex-auto flex-col gap-4 p-4 md:p-6">
            <div class="text-secondary text-xs font-semibold uppercase tracking-wider">Settings</div>
            <div class="flex max-w-320 flex-col gap-4 md:flex-row md:items-start">
                <nav class="flex shrink-0 flex-col gap-3 md:w-60" aria-label="Settings sections">
                    @for (group of groups(); track group.id) {
                        <div
                            class="flex flex-col gap-0.5"
                            role="group"
                            [attr.aria-labelledby]="'la-settings-' + group.id"
                        >
                            <div
                                [id]="'la-settings-' + group.id"
                                class="text-secondary px-3 pb-1 text-xs font-semibold uppercase tracking-wider"
                            >
                                {{ group.label }}
                            </div>
                            @for (drawer of group.drawers; track drawer.id) {
                                <button
                                    type="button"
                                    class="hover:bg-hover flex items-center gap-2 rounded-lg px-3 py-1.5 text-left transition-colors"
                                    [class.bg-hover]="selected().id === drawer.id"
                                    [attr.aria-current]="selected().id === drawer.id ? 'true' : null"
                                    [attr.data-section]="drawer.id"
                                    (click)="select(drawer)"
                                >
                                    <mat-icon class="icon-size-4 text-hint" [svgIcon]="drawer.icon"></mat-icon>
                                    <span class="flex min-w-0 flex-auto flex-col">
                                        <span class="truncate text-sm font-medium">{{ drawer.title }}</span>
                                        <span class="text-secondary truncate text-xs">{{ drawer.description }}</span>
                                    </span>
                                    @if (!drawer.canChange()) {
                                        <inspecto-chip variant="soft" tone="neutral">Read-only</inspecto-chip>
                                    }
                                </button>
                            }
                        </div>
                    }
                </nav>
                <section
                    class="min-w-0 flex-auto rounded-lg border border-gray-200 dark:border-gray-700"
                    aria-live="polite"
                >
                    <ng-container [ngComponentOutlet]="selected().component"></ng-container>
                </section>
            </div>
        </div>
    `,
})
export class SettingsPageComponent {
    private readonly lens = inject(LensService);
    private readonly limits = inject(LaLimitsService);
    private readonly route = inject(ActivatedRoute);
    private readonly router = inject(Router);

    /** The server marks each limit this caller may change, so a section's privilege is read off its own items. */
    private readonly mayChangeLimits = (layer: 'space' | 'install') => () =>
        this.limits.items().some((i) => i.layer === layer && i.editable);

    readonly drawers: readonly SettingsDrawer[] = [
        {
            id: 'my-browser',
            title: 'My browser',
            icon: 'heroicons_outline:user',
            description: 'Your own limits for this browser.',
            audience: 'me',
            component: MyBrowserSettingsComponent,
            canChange: () => true,
        },
        {
            id: 'link-analysis',
            title: 'Link Analysis',
            icon: 'heroicons_outline:share',
            description: 'Masking, Entity Types, index, embed links.',
            audience: 'space',
            component: SpaceSettingsComponent,
            canChange: () => this.lens.canAuthorWorkbench(),
        },
        {
            id: 'analysis-limits',
            title: 'Analysis limits',
            icon: 'heroicons_outline:adjustments-horizontal',
            description: 'Caps and budgets for this Space.',
            audience: 'space',
            component: AnalysisLimitsSettingsComponent,
            canChange: this.mayChangeLimits('space'),
        },
        {
            id: 'access',
            title: 'Access',
            icon: 'heroicons_outline:key',
            description: 'What each role may do.',
            audience: 'space',
            component: AccessPageComponent,
            canChange: () => this.lens.canConfigureAccess(),
        },
        {
            id: 'host-resources',
            title: 'Host resources',
            icon: 'heroicons_outline:server-stack',
            description: 'Threads and queues for every Space.',
            audience: 'server',
            component: HostResourcesSettingsComponent,
            canChange: this.mayChangeLimits('install'),
        },
    ];

    readonly groups = computed(() =>
        AUDIENCES.map((a) => ({ ...a, drawers: this.drawers.filter((d) => d.audience === a.id) })).filter(
            (g) => g.drawers.length > 0,
        ),
    );

    /** From the `:section` route param (deep-linkable, Back works); the first section when none or an unknown one is named. */
    readonly selected = signal<SettingsDrawer>(this.drawers[0]);

    constructor() {
        this.route.paramMap.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe((p) => {
            this.selected.set(this.drawers.find((d) => d.id === p.get('section')) ?? this.drawers[0]);
        });
    }

    /** Selecting a section navigates; the paramMap subscription above applies it. */
    select(drawer: SettingsDrawer): void {
        this.router.navigate(['/settings', drawer.id]);
    }
}
