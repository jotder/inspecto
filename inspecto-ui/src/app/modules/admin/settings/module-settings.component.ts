import {
    ChangeDetectionStrategy,
    Component,
    ElementRef,
    HostListener,
    OnInit,
    computed,
    inject,
    signal,
    viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';

import { NavigationService } from 'app/core/navigation/navigation.service';
import { InstalledModule, LensService, ModuleSettingsService, apiErrorMessage } from 'app/inspecto/api';
import { SessionService } from 'app/inspecto/auth/session.service';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';

interface Group {
    role: string;
    title: string;
    note: string;
    modules: InstalledModule[];
}

const GROUPS: { role: string; title: string; note: string }[] = [
    { role: 'optional', title: 'Optional Modules', note: 'Part of an Offering; a Space can turn their Features off.' },
    {
        role: 'provider',
        title: 'Provider Modules',
        note: 'Chosen by the deployment profile; a Space can turn their Features off.',
    },
    { role: 'base', title: 'Base Modules', note: 'Always on: the platform needs them, so they have no switch.' },
    { role: 'internal', title: 'Internal Modules', note: 'Always on: other Modules use them, so they have no switch.' },
];

/**
 * Settings > **Modules** (MODULE-REORG-1 P2b): the installed Modules by offering role and, for those that declare
 * Features and are not Base/Internal, a per-Space switch. The draft is the set of disabled Feature ids, replaced in
 * one explicit PUT (`canAdminister`). Unknown ids already in the Space's settings are listed and sent back untouched.
 */
@Component({
    selector: 'inspecto-module-settings',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        MatButtonModule,
        MatSlideToggleModule,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        InspectoPageHeaderComponent,
        InspectoSkeletonComponent,
        StatusBadgeComponent,
    ],
    template: `
        <div class="flex flex-col gap-6 p-6">
            <inspecto-page-header
                title="Modules"
                subtitle="Which installed Modules and their Features are on in this Space."
                [inset]="false"
            />

            @if (loading()) {
                <inspecto-skeleton [lines]="4" />
            } @else if (loadError()) {
                <inspecto-empty-state
                    icon="heroicons_outline:puzzle-piece"
                    title="Modules unavailable"
                    [message]="loadError()!"
                ></inspecto-empty-state>
            } @else {
                @if (unreadable()) {
                    <inspecto-alert variant="warning" title="The Module settings could not be read">
                        Every Feature is treated as on until the file is fixed. Saving replaces it.
                    </inspecto-alert>
                }

                @for (group of groups(); track group.role) {
                    <section class="flex max-w-240 flex-col gap-2" [attr.aria-labelledby]="'grp-' + group.role">
                        <h2 [id]="'grp-' + group.role" class="text-lg font-semibold">
                            {{ group.title }} ({{ group.modules.length }})
                        </h2>
                        <p class="text-secondary text-sm">{{ group.note }}</p>
                        <ul class="flex flex-col divide-y rounded-lg border">
                            @for (m of group.modules; track m.id) {
                                <li class="flex flex-wrap items-start justify-between gap-3 px-3 py-2">
                                    <div class="flex min-w-0 flex-col gap-1">
                                        <div class="flex flex-wrap items-center gap-2">
                                            <span class="font-semibold">{{ m.title }}</span>
                                            <span class="text-secondary font-mono text-sm">{{ m.id }}</span>
                                            <inspecto-status-badge
                                                [value]="m.state === 'ACTIVE' ? 'ACTIVE' : 'INACTIVE'"
                                                [label]="m.state === 'ACTIVE' ? 'Active' : 'Inert'"
                                            />
                                        </div>
                                        @if (m.state === 'INERT' && m.reasons.length) {
                                            <span class="text-secondary text-sm">
                                                Inert because: {{ m.reasons.join('; ') }}
                                            </span>
                                        }
                                        @if (m.provides.features.length) {
                                            <span class="text-secondary text-sm">
                                                Features: {{ m.provides.features.join(', ') }}
                                            </span>
                                        } @else {
                                            <span class="text-secondary text-sm">No Features to switch.</span>
                                        }
                                    </div>
                                    @if (switchable(m)) {
                                        <mat-slide-toggle
                                            [checked]="isOn(m)"
                                            [disabled]="!canEdit() || saving()"
                                            [attr.data-module]="m.id"
                                            [aria-label]="'Enabled in this Space: ' + m.title"
                                            (change)="set(m, $event.checked)"
                                        >
                                            Enabled in this Space
                                        </mat-slide-toggle>
                                    }
                                </li>
                            }
                        </ul>
                    </section>
                }

                @if (inert().length) {
                    <inspecto-alert variant="info" title="Unknown modules in this Space's settings">
                        The settings name {{ inert().length }} Feature id(s) that no installed Module declares:
                        <span class="font-mono">{{ inert().join(', ') }}</span
                        >. They are kept untouched, and take effect if a Module declaring them is installed later.
                    </inspecto-alert>
                }

                @if (hiding().length) {
                    <inspecto-alert variant="warning" title="Saving will hide Features">
                        These Modules will be switched off in this Space: {{ hiding().join(', ') }}. Their menu entries
                        and routes disappear; no stored data is removed.
                    </inspecto-alert>
                }

                <div
                    #status
                    tabindex="-1"
                    role="status"
                    aria-live="polite"
                    class="max-w-240 outline-offset-2 focus-visible:outline focus-visible:outline-2"
                >
                    @if (saveError()) {
                        <inspecto-alert variant="error" title="The Module settings were not saved">
                            {{ saveError() }}
                        </inspecto-alert>
                    } @else if (savedNote()) {
                        <inspecto-alert variant="success" title="Saved">{{ savedNote() }}</inspecto-alert>
                    }
                </div>

                @if (canEdit()) {
                    <div class="flex items-center gap-3">
                        <button
                            mat-flat-button
                            color="primary"
                            type="button"
                            [disabled]="!dirty() || saving()"
                            (click)="save()"
                        >
                            Save modules
                        </button>
                        <button mat-button type="button" [disabled]="!dirty() || saving()" (click)="revert()">
                            Discard changes
                        </button>
                    </div>
                } @else {
                    <inspecto-alert variant="warning" title="Administer capability required">
                        Only an administrator of this space can change which Modules are on.
                    </inspecto-alert>
                }
            }
        </div>
    `,
})
export class ModuleSettingsComponent implements OnInit {
    private api = inject(ModuleSettingsService);
    private lens = inject(LensService);
    private session = inject(SessionService);
    private navigation = inject(NavigationService);
    private statusEl = viewChild<ElementRef<HTMLElement>>('status');

    readonly canEdit = computed(() => this.lens.canAdminister());
    readonly loading = signal(true);
    readonly saving = signal(false);
    readonly loadError = signal<string | null>(null);
    readonly saveError = signal<string | null>(null);
    readonly savedNote = signal<string | null>(null);
    readonly unreadable = signal(false);
    readonly modules = signal<InstalledModule[]>([]);
    readonly inert = signal<string[]>([]);

    private readonly saved = signal<string[]>([]);
    /** Feature ids switched off in the draft (sorted, so equality is order-free). */
    readonly draft = signal<string[]>([]);
    readonly dirty = computed(() => this.draft().join('\n') !== this.saved().join('\n'));

    readonly groups = computed<Group[]>(() =>
        GROUPS.map((g) => ({ ...g, modules: this.modules().filter((m) => m.offeringRole === g.role) })).filter(
            (g) => g.modules.length,
        ),
    );
    /** Modules the draft switches off that were not already off when last saved. */
    readonly hiding = computed(() =>
        this.modules()
            .filter(
                (m) =>
                    this.switchable(m) && !this.isOn(m) && m.provides.features.some((f) => !this.saved().includes(f)),
            )
            .map((m) => m.title),
    );

    ngOnInit(): void {
        let modules: InstalledModule[] | null = null;
        let settings: { disabled: string[]; inert: string[]; unreadable: boolean } | null = null;
        const done = () => {
            if (!modules || !settings) return;
            this.modules.set(modules);
            this.inert.set(settings.inert ?? []);
            this.unreadable.set(!!settings.unreadable);
            this.adopt(settings.disabled ?? []);
            this.loading.set(false);
        };
        const fail = (err: unknown) => {
            this.loading.set(false);
            this.loadError.set(apiErrorMessage(err, 'The Modules routes did not answer.'));
        };
        this.api.modules().subscribe({
            next: (r) => {
                modules = r.modules ?? [];
                done();
            },
            error: fail,
        });
        this.api.get().subscribe({
            next: (r) => {
                settings = r;
                done();
            },
            error: fail,
        });
    }

    /** A Module gets a switch only when it declares Features and a Space may turn it off (not Base/Internal). */
    switchable(m: InstalledModule): boolean {
        return m.provides.features.length > 0 && m.offeringRole !== 'base' && m.offeringRole !== 'internal';
    }

    isOn(m: InstalledModule): boolean {
        return m.provides.features.every((f) => !this.draft().includes(f));
    }

    set(m: InstalledModule, on: boolean): void {
        const rest = this.draft().filter((f) => !m.provides.features.includes(f));
        this.draft.set((on ? rest : [...rest, ...m.provides.features]).sort());
        this.saveError.set(null);
        this.savedNote.set(null);
    }

    revert(): void {
        this.draft.set(this.saved());
        this.saveError.set(null);
    }

    hasUnsavedChanges(): boolean {
        return this.dirty();
    }

    @HostListener('window:beforeunload', ['$event'])
    onBeforeUnload(event: BeforeUnloadEvent): void {
        if (this.dirty()) event.preventDefault();
    }

    save(): void {
        this.saving.set(true);
        this.saveError.set(null);
        this.savedNote.set(null);
        // Inert ids get no switch; the server keeps them, so they travel back with the draft.
        this.api.save([...new Set([...this.draft(), ...this.inert()])].sort()).subscribe({
            next: (v) => {
                this.saving.set(false);
                this.inert.set(v.inert ?? []);
                this.adopt(v.disabled ?? []);
                this.savedNote.set('Module settings saved. The menu is being refreshed.');
                this.focusStatus();
                void this.session.reloadFeatures().then(() => this.navigation.get().subscribe());
            },
            error: (err) => {
                this.saving.set(false);
                this.saveError.set(
                    err?.status === 403
                        ? 'Only an administrator of this space can change which Modules are on.'
                        : apiErrorMessage(err, 'Saving the Module settings failed.'),
                );
                this.focusStatus();
            },
        });
    }

    private adopt(disabled: string[]): void {
        const known = disabled.filter((id) => !this.inert().includes(id)).sort();
        this.saved.set(known);
        this.draft.set(known);
    }

    private focusStatus(): void {
        setTimeout(() => this.statusEl()?.nativeElement.focus());
    }
}
