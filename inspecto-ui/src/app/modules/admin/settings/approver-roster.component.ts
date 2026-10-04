import { ChangeDetectionStrategy, Component, HostListener, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ToastrService } from 'ngx-toastr';

import { ApproverRosterService, LensService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';

type ListKey = 'users' | 'groups';

/**
 * Settings ▸ **Approvers** (operator 2026-10-04): the Space's `approvers.toon` — the user ids and IdP groups who
 * may approve or decline an Action Request or a Pending Change when the sign-in provider has no user directory.
 * Empty means NOBODY can approve. Edited as a draft, replaced in one PUT (`canAdminister`); the server's 422 is
 * shown inline. The Settings route's leave guard reads {@link hasUnsavedChanges}.
 */
@Component({
    selector: 'inspecto-approver-roster-settings',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        InspectoPageHeaderComponent,
    ],
    template: `
        <div class="flex flex-col gap-6 p-6">
            <inspecto-page-header
                title="Approvers"
                subtitle="Who may approve Action Requests and Pending Changes in this space."
                [inset]="false"
            />

            @if (loading()) {
                <div class="flex items-center gap-2 py-6">
                    <mat-spinner diameter="20"></mat-spinner>
                    <span class="text-secondary text-sm">Reading the approver roster…</span>
                </div>
            } @else if (loadError()) {
                <inspecto-empty-state
                    icon="heroicons_outline:user-group"
                    title="Approver roster unavailable"
                    [message]="loadError()!"
                ></inspecto-empty-state>
            } @else {
                <inspecto-alert [variant]="applies() ? 'info' : 'warning'" title="When the roster decides">
                    @if (applies()) {
                        This space signs in through a provider with no user directory, so only the people listed here —
                        by user id, or by a group from their sign-in — can approve. An empty roster means nobody can.
                        The author of an item can never approve it, even when listed.
                    } @else {
                        The current sign-in lists its users, so approvals follow roles alone. The roster takes effect
                        when the space signs in through a provider with no user directory.
                    }
                </inspecto-alert>

                @for (key of keys; track key) {
                    <section class="flex max-w-160 flex-col gap-3" [attr.aria-labelledby]="key + '-heading'">
                        <h2 [id]="key + '-heading'" class="text-lg font-semibold">
                            {{ labels[key].title }} ({{ draft()[key].length }})
                        </h2>
                        @if (draft()[key].length) {
                            <ul class="flex flex-col divide-y rounded-lg border">
                                @for (entry of draft()[key]; track entry) {
                                    <li class="flex items-center justify-between gap-3 px-3 py-1.5">
                                        <span class="font-mono text-sm">{{ entry }}</span>
                                        @if (canEdit()) {
                                            <button
                                                mat-icon-button
                                                type="button"
                                                [attr.aria-label]="'Remove ' + entry"
                                                (click)="remove(key, entry)"
                                            >
                                                <mat-icon svgIcon="heroicons_outline:x-mark"></mat-icon>
                                            </button>
                                        }
                                    </li>
                                }
                            </ul>
                        } @else {
                            <p class="text-secondary text-sm">{{ labels[key].empty }}</p>
                        }
                        @if (canEdit()) {
                            <form
                                [formGroup]="forms[key]"
                                class="flex flex-wrap items-start gap-3"
                                (ngSubmit)="add(key)"
                            >
                                <mat-form-field class="w-80" subscriptSizing="dynamic">
                                    <mat-label>{{ labels[key].field }}</mat-label>
                                    <input matInput formControlName="entry" autocomplete="off" />
                                    @if (forms[key].controls.entry.hasError('required')) {
                                        <mat-error>Enter a value.</mat-error>
                                    } @else if (forms[key].controls.entry.hasError('duplicate')) {
                                        <mat-error>That entry is already in the list.</mat-error>
                                    }
                                </mat-form-field>
                                <button mat-stroked-button type="submit" class="mt-1">
                                    {{ labels[key].add }}
                                </button>
                            </form>
                        }
                    </section>
                }

                @if (saveError()) {
                    <inspecto-alert variant="error" title="The roster was not saved">{{ saveError() }}</inspecto-alert>
                }
                @if (writesDisabled()) {
                    <inspecto-alert variant="warning" title="Changes cannot be saved here">
                        {{ writesDisabled() }}
                    </inspecto-alert>
                }

                @if (canEdit()) {
                    <div class="flex items-center gap-3">
                        <button
                            mat-flat-button
                            color="primary"
                            type="button"
                            [disabled]="!dirty() || saving()"
                            (click)="save()"
                        >
                            Save roster
                        </button>
                        <button mat-button type="button" [disabled]="!dirty() || saving()" (click)="revert()">
                            Discard changes
                        </button>
                    </div>
                } @else {
                    <inspecto-alert variant="warning" title="Administer capability required">
                        Only an administrator of this space can change the approver roster.
                    </inspecto-alert>
                }
            }
        </div>
    `,
})
export class ApproverRosterSettingsComponent implements OnInit {
    private api = inject(ApproverRosterService);
    private fb = inject(FormBuilder);
    private toastr = inject(ToastrService);
    private lens = inject(LensService);

    readonly keys: ListKey[] = ['users', 'groups'];
    readonly labels = {
        users: { title: 'Users', field: 'User id', add: 'Add user', empty: 'No users listed.' },
        groups: { title: 'Groups', field: 'Group name', add: 'Add group', empty: 'No groups listed.' },
    };
    readonly canEdit = computed(() => this.lens.canAdminister());
    readonly loading = signal(true);
    readonly saving = signal(false);
    readonly applies = signal(false);
    readonly loadError = signal<string | null>(null);
    readonly saveError = signal<string | null>(null);
    readonly writesDisabled = signal<string | null>(null);

    private readonly saved = signal<Record<ListKey, string[]>>({ users: [], groups: [] });
    readonly draft = signal<Record<ListKey, string[]>>({ users: [], groups: [] });
    readonly dirty = computed(() => JSON.stringify(this.draft()) !== JSON.stringify(this.saved()));

    readonly forms = {
        users: this.fb.nonNullable.group({ entry: ['', Validators.required] }),
        groups: this.fb.nonNullable.group({ entry: ['', Validators.required] }),
    };

    ngOnInit(): void {
        this.api.get().subscribe({
            next: (v) => {
                this.loading.set(false);
                this.applies.set(!!v.applies);
                this.apply(v.users ?? [], v.groups ?? []);
            },
            error: (err) => {
                this.loading.set(false);
                this.loadError.set(apiErrorMessage(err, 'The approver roster route did not answer.'));
            },
        });
    }

    hasUnsavedChanges(): boolean {
        return this.dirty();
    }

    @HostListener('window:beforeunload', ['$event'])
    onBeforeUnload(event: BeforeUnloadEvent): void {
        if (this.dirty()) event.preventDefault();
    }

    add(key: ListKey): void {
        const control = this.forms[key].controls.entry;
        const value = control.value.trim();
        control.setValue(value);
        if (value && this.draft()[key].includes(value)) control.setErrors({ duplicate: true });
        if (this.forms[key].invalid) {
            this.forms[key].markAllAsTouched();
            return;
        }
        this.draft.update((d) => ({ ...d, [key]: [...d[key], value] }));
        this.forms[key].reset();
        this.saveError.set(null);
    }

    remove(key: ListKey, entry: string): void {
        this.draft.update((d) => ({ ...d, [key]: d[key].filter((e) => e !== entry) }));
        this.saveError.set(null);
    }

    revert(): void {
        this.draft.set(this.saved());
        this.saveError.set(null);
    }

    save(): void {
        this.saving.set(true);
        this.saveError.set(null);
        this.api.save(this.draft().users, this.draft().groups).subscribe({
            next: (v) => {
                this.saving.set(false);
                this.writesDisabled.set(null);
                this.apply(v.users ?? [], v.groups ?? []);
                this.toastr.success('Approver roster saved.');
            },
            error: (err) => {
                this.saving.set(false);
                const message = apiErrorMessage(err, 'Saving the approver roster failed.');
                if (err?.status === 422) this.saveError.set(message);
                else if (err?.status === 503) this.writesDisabled.set(message);
                else this.toastr.error(message);
            },
        });
    }

    private apply(users: string[], groups: string[]): void {
        this.saved.set({ users, groups });
        this.draft.set({ users, groups });
    }
}
