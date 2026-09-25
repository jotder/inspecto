import { ChangeDetectionStrategy, Component, OnInit, ViewEncapsulation, computed, inject, signal } from '@angular/core';
import { FormArray, FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { ToastrService } from 'ngx-toastr';
import {
    LensService,
    NotificationPrefRow,
    NotificationsService,
    PrefChange,
    PrefSource,
    SessionService,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { DeploymentDefaultPreferencesComponent } from './deployment-default-preferences.component';

const CHANNELS = ['inApp', 'email'] as const;
type Channel = (typeof CHANNELS)[number];

/**
 * Notification preferences — the category × channel grid AS THE CALLER RECEIVES IT (ses-sns §7). Signed in,
 * each cell is either inherited from the deployment default or overridden by the user, and an overridden
 * cell can be reset to the default; Save sends only the cells that changed, because every cell sent becomes
 * the user's own. Critical categories (e.g. Security) are locked on; email is locked off for a user with no
 * verified email address. Administrators also get the separate "Deployment default" editor. On Personal
 * (no sign-in) there is one user and one grid, so there are no markers and no default editor.
 */
@Component({
    selector: 'app-notification-preferences',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    encapsulation: ViewEncapsulation.None,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatSlideToggleModule,
        InspectoAlertComponent,
        InspectoSkeletonComponent,
        DeploymentDefaultPreferencesComponent,
    ],
    template: `
        <div class="flex max-w-3xl flex-col gap-4 p-6">
            <div>
                <!-- h2: this pane is embedded as a Notification-center tab (the page h1 lives there). -->
                <h2 class="text-2xl font-semibold">Notification preferences</h2>
                <p class="text-secondary mt-1">
                    @if (signedIn()) {
                        Choose how you're notified for each category. Your choices apply to you only; anything you
                        haven't changed follows the deployment default.
                    } @else {
                        Choose how you're notified for each category.
                    }
                </p>
            </div>

            <inspecto-alert variant="info" title="Security alerts are always on">
                Critical security notifications can't be turned off and are delivered on every channel.
            </inspecto-alert>

            @if (emailLocked()) {
                <inspecto-alert variant="info" title="Email needs a verified address">
                    Your sign-in has no verified email address, so only in-app delivery can be turned on. Email is only
                    ever sent to the verified address your identity provider supplies.
                </inspecto-alert>
            }

            @if (loading()) {
                <inspecto-skeleton [lines]="6" />
            } @else {
                <form [formGroup]="form" (ngSubmit)="save()">
                    <table class="w-full text-left">
                        <caption class="sr-only">
                            Notification channel preferences by category
                        </caption>
                        <thead>
                            <tr class="border-b">
                                <th scope="col" class="py-2">Category</th>
                                <th scope="col" class="w-36 py-2 text-center">In-app</th>
                                <th scope="col" class="w-36 py-2 text-center">Email</th>
                            </tr>
                        </thead>
                        <tbody formArrayName="rows">
                            @for (row of rows.controls; track row.value.category; let i = $index) {
                                <tr [formGroupName]="i" class="border-b">
                                    <th scope="row" class="py-3 font-normal">
                                        <div class="flex items-center gap-2">
                                            <span>{{ row.value.label }}</span>
                                            @if (row.value.critical) {
                                                <span class="text-secondary text-xs">Always on</span>
                                            } @else if (!row.value.available) {
                                                <span class="text-secondary text-xs">Coming soon</span>
                                            }
                                        </div>
                                    </th>
                                    @for (ch of channels; track ch) {
                                        <td class="py-3 text-center">
                                            <mat-slide-toggle [formControlName]="ch">
                                                <span class="sr-only"
                                                    >{{ channelLabel(ch) }} notifications for
                                                    {{ row.value.label }}</span
                                                >
                                            </mat-slide-toggle>
                                            @if (signedIn() && !row.value.critical) {
                                                <div class="mt-1 flex items-center justify-center gap-1 text-xs">
                                                    @if (row.value.source[ch] === 'overridden') {
                                                        <span data-testid="marker">Overridden</span>
                                                        <button
                                                            mat-button
                                                            type="button"
                                                            class="min-w-0 px-1 text-xs"
                                                            [disabled]="saving()"
                                                            [attr.aria-label]="
                                                                'Reset ' +
                                                                channelLabel(ch) +
                                                                ' for ' +
                                                                row.value.label +
                                                                ' to the deployment default'
                                                            "
                                                            (click)="reset(row.value.category, ch)"
                                                        >
                                                            Reset
                                                        </button>
                                                    } @else {
                                                        <span class="text-secondary" data-testid="marker"
                                                            >Inherited</span
                                                        >
                                                    }
                                                </div>
                                            }
                                        </td>
                                    }
                                </tr>
                            }
                        </tbody>
                    </table>

                    <div class="mt-4 flex gap-2">
                        <button mat-flat-button color="primary" type="submit" [disabled]="form.pristine || saving()">
                            Save
                        </button>
                        <button mat-stroked-button type="button" (click)="load()" [disabled]="saving()">
                            Discard changes
                        </button>
                    </div>
                </form>
            }

            @if (showDefaultEditor()) {
                <app-deployment-default-preferences class="mt-6 border-t pt-6" (saved)="load()" />
            }
        </div>
    `,
})
export class NotificationPreferencesComponent implements OnInit {
    private fb = inject(FormBuilder);
    private svc = inject(NotificationsService);
    private toastr = inject(ToastrService);
    private session = inject(SessionService);
    private lens = inject(LensService);

    readonly channels = CHANNELS;
    readonly loading = signal(true);
    readonly saving = signal(false);
    /** Signed in: the grid is personal (inherited / overridden) and there is a separate deployment default. */
    readonly signedIn = computed(() => this.session.authMode() === 'oidc');
    readonly showDefaultEditor = computed(() => this.signedIn() && this.lens.canAdminister());
    /** The server says email cannot be enabled on some non-critical row: the caller has no verified address. */
    readonly emailLocked = signal(false);

    readonly form = this.fb.group({ rows: this.fb.array([]) });

    get rows(): FormArray {
        return this.form.get('rows') as FormArray;
    }

    ngOnInit(): void {
        this.load();
    }

    channelLabel(ch: Channel): string {
        return ch === 'inApp' ? 'In-app' : 'Email';
    }

    load(): void {
        this.loading.set(true);
        this.svc.preferences().subscribe({
            next: (grid) => {
                this.buildForm(grid);
                this.loading.set(false);
            },
            error: () => {
                this.loading.set(false);
                this.toastr.error('Failed to load preferences');
            },
        });
    }

    /** Send only the changed cells — every cell sent becomes the caller's own override. */
    save(): void {
        const changes: PrefChange[] = [];
        for (const row of this.rows.controls) {
            const channels: Record<string, boolean> = {};
            for (const ch of CHANNELS) {
                const c = row.get(ch);
                if (c?.dirty) channels[ch] = c.value;
            }
            if (Object.keys(channels).length) changes.push({ category: row.value.category, channels });
        }
        this.persist(changes, 'Preferences saved');
    }

    /** Drop the caller's override for one cell, so it follows the deployment default again. */
    reset(category: string, ch: Channel): void {
        this.persist([{ category, channels: { [ch]: null } }], 'Reset to the deployment default');
    }

    private persist(changes: PrefChange[], done: string): void {
        this.saving.set(true);
        this.svc.savePreferences(changes).subscribe({
            next: (grid) => {
                this.buildForm(grid);
                this.saving.set(false);
                this.toastr.success(done);
            },
            error: () => {
                this.saving.set(false);
                this.toastr.error('Save failed');
            },
        });
    }

    /** Rebuild the form array from a grid; a cell the server marks not editable is a disabled control. */
    private buildForm(grid: NotificationPrefRow[]): void {
        this.rows.clear();
        let emailLocked = false;
        for (const r of grid) {
            const editable = (ch: Channel) => (r.editable ? r.editable[ch] !== false : !r.critical);
            if (!r.critical && !editable('email')) emailLocked = true;
            const source: Record<string, PrefSource> = r.source ?? {};
            this.rows.push(
                this.fb.group({
                    category: [r.category],
                    label: [r.label],
                    critical: [r.critical],
                    available: [r.available],
                    source: [source],
                    inApp: [{ value: r.channels.inApp, disabled: !editable('inApp') }],
                    email: [{ value: r.channels.email, disabled: !editable('email') }],
                }),
            );
        }
        this.emailLocked.set(emailLocked && this.signedIn());
        this.form.markAsPristine();
    }
}
