import { ChangeDetectionStrategy, Component, HostListener, OnInit, computed, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ToastrService } from 'ngx-toastr';

import { EgressSettingsService, LensService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';

/** `EgressRoutes.MAX_ENTRIES` — the PUT refuses a longer list. */
const MAX_ENTRIES = 200;

/**
 * Settings ▸ **Egress Allowlist** (ASSURE-ACTION-REQUESTS-RESIDUALS-1 (4)): the Space's `egress.toon`, the host
 * names and CIDR ranges that Action Requests, the webhook Step and channel, and the object-store connectors may
 * reach although their address class is denied by default.
 *
 * <p>The read renders for everyone (`GET /settings/egress` is ungated); editing needs `canAdminister`, the
 * capability `PUT /settings/egress` checks. The list is edited as a draft and replaced in one PUT. The server is
 * the only judge of an entry — a refused one (a range overlapping loopback / link-local / …) comes back as a 422
 * whose message is shown inline, and nothing is saved. A dirty draft is guarded by
 * {@link hasUnsavedChanges} (the Settings route's leave guard) and `beforeunload`.
 */
@Component({
    selector: 'inspecto-egress-settings',
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
                title="Egress Allowlist"
                subtitle="Private hosts and ranges that outbound calls from this space may reach."
                [inset]="false"
            />

            <inspecto-alert variant="info" title="What an entry can and cannot open">
                Action Requests, webhooks and object-store connectors refuse every private (RFC 1918, ULA, site-local)
                and carrier-grade NAT (100.64.0.0/10) address by default. An entry lifts only those: a host name for
                that exact name, or a CIDR range (a bare IP counts as one address) for its range. Loopback, link-local
                (including the cloud metadata service 169.254.169.254), unspecified, multicast, broadcast and this
                server's own addresses can never be allowlisted. A range that overlaps them is refused on save, and a
                host name that resolves to one is still refused when the call is made.
            </inspecto-alert>

            @if (loading()) {
                <div class="flex items-center gap-2 py-6">
                    <mat-spinner diameter="20"></mat-spinner>
                    <span class="text-secondary text-sm">Reading the allowlist…</span>
                </div>
            } @else if (loadError()) {
                <inspecto-empty-state
                    icon="heroicons_outline:globe-alt"
                    title="Allowlist unavailable"
                    [message]="loadError()!"
                ></inspecto-empty-state>
            } @else {
                <section class="flex max-w-160 flex-col gap-3" aria-labelledby="egress-entries-heading">
                    <h2 id="egress-entries-heading" class="text-lg font-semibold">Entries ({{ entries().length }})</h2>
                    @if (entries().length) {
                        <ul class="flex flex-col divide-y rounded-lg border">
                            @for (entry of entries(); track entry) {
                                <li class="flex items-center justify-between gap-3 px-3 py-1.5">
                                    <span class="font-mono text-sm">{{ entry }}</span>
                                    @if (canEdit()) {
                                        <button
                                            mat-icon-button
                                            type="button"
                                            [attr.aria-label]="'Remove ' + entry"
                                            (click)="remove(entry)"
                                        >
                                            <mat-icon svgIcon="heroicons_outline:x-mark"></mat-icon>
                                        </button>
                                    }
                                </li>
                            }
                        </ul>
                    } @else {
                        <p class="text-secondary text-sm">
                            No entries — every private and carrier-grade NAT address is refused.
                        </p>
                    }

                    @if (canEdit()) {
                        <form [formGroup]="addForm" class="flex flex-wrap items-start gap-3" (ngSubmit)="add()">
                            <mat-form-field class="w-80" subscriptSizing="dynamic">
                                <mat-label>Host name or CIDR</mat-label>
                                <input
                                    matInput
                                    formControlName="entry"
                                    placeholder="tickets.internal or 10.20.0.0/16"
                                    autocomplete="off"
                                />
                                @if (addForm.controls.entry.hasError('required')) {
                                    <mat-error>Enter a host name or a CIDR range.</mat-error>
                                } @else if (addForm.controls.entry.hasError('whitespace')) {
                                    <mat-error>An entry cannot contain spaces.</mat-error>
                                } @else if (addForm.controls.entry.hasError('duplicate')) {
                                    <mat-error>That entry is already in the list.</mat-error>
                                } @else if (addForm.controls.entry.hasError('full')) {
                                    <mat-error>The list holds at most {{ maxEntries }} entries.</mat-error>
                                }
                            </mat-form-field>
                            <button mat-stroked-button type="submit" class="mt-1">Add</button>
                        </form>
                    }
                </section>

                @if (saveError()) {
                    <inspecto-alert variant="error" title="The allowlist was not saved">{{
                        saveError()
                    }}</inspecto-alert>
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
                            Save allowlist
                        </button>
                        <button mat-button type="button" [disabled]="!dirty() || saving()" (click)="revert()">
                            Discard changes
                        </button>
                        @if (dirty()) {
                            <span class="text-secondary text-sm">Unsaved changes</span>
                        }
                    </div>
                } @else {
                    <inspecto-alert variant="warning" title="Administer capability required">
                        Only an administrator of this space can change the Egress Allowlist.
                    </inspecto-alert>
                }
            }
        </div>
    `,
})
export class EgressSettingsComponent implements OnInit {
    private api = inject(EgressSettingsService);
    private fb = inject(FormBuilder);
    private toastr = inject(ToastrService);
    private lens = inject(LensService);

    readonly maxEntries = MAX_ENTRIES;
    readonly canEdit = computed(() => this.lens.canAdminister());
    readonly loading = signal(true);
    readonly saving = signal(false);
    readonly loadError = signal<string | null>(null);
    /** The server's 422 refusal, verbatim — it names the entry and the range it overlaps. */
    readonly saveError = signal<string | null>(null);
    /** The 503 message when this server has no writable config root. */
    readonly writesDisabled = signal<string | null>(null);

    /** The list as last read or saved, and the draft being edited. */
    private readonly saved = signal<string[]>([]);
    readonly entries = signal<string[]>([]);
    readonly dirty = computed(() => this.entries().join('\n') !== this.saved().join('\n'));

    readonly addForm = this.fb.nonNullable.group({
        entry: ['', [Validators.required, (c: AbstractControl) => this.entryErrors(c)]],
    });

    ngOnInit(): void {
        this.api.get().subscribe({
            next: (v) => {
                this.loading.set(false);
                this.apply(v.allow ?? []);
            },
            error: (err) => {
                this.loading.set(false);
                this.loadError.set(apiErrorMessage(err, 'The egress settings route did not answer.'));
            },
        });
    }

    /** Read by the Settings route's leave guard. */
    hasUnsavedChanges(): boolean {
        return this.dirty();
    }

    @HostListener('window:beforeunload', ['$event'])
    onBeforeUnload(event: BeforeUnloadEvent): void {
        if (this.dirty()) event.preventDefault();
    }

    add(): void {
        const control = this.addForm.controls.entry;
        control.setValue(control.value.trim());
        if (this.addForm.invalid) {
            this.addForm.markAllAsTouched();
            return;
        }
        // The server lowercases every entry; do it here so the draft shows what will be stored.
        this.entries.update((list) => [...list, control.value.toLowerCase()]);
        this.addForm.reset();
        this.saveError.set(null);
    }

    remove(entry: string): void {
        this.entries.update((list) => list.filter((e) => e !== entry));
        this.saveError.set(null);
    }

    revert(): void {
        this.entries.set(this.saved());
        this.saveError.set(null);
    }

    save(): void {
        this.saving.set(true);
        this.saveError.set(null);
        this.api.save(this.entries()).subscribe({
            next: (v) => {
                this.saving.set(false);
                this.writesDisabled.set(null);
                this.apply(v.allow ?? []);
                this.toastr.success('Egress Allowlist saved.');
            },
            error: (err) => {
                this.saving.set(false);
                const message = apiErrorMessage(err, 'Saving the Egress Allowlist failed.');
                if (err?.status === 422) this.saveError.set(message);
                else if (err?.status === 503) this.writesDisabled.set(message);
                else this.toastr.error(message);
            },
        });
    }

    private apply(allow: string[]): void {
        this.saved.set(allow);
        this.entries.set(allow);
    }

    /** Shape checks only — whether an entry may be allowlisted is the server's decision. */
    private entryErrors(c: AbstractControl): ValidationErrors | null {
        const v = String(c.value ?? '').trim();
        if (!v) return null;
        if (/\s/.test(v)) return { whitespace: true };
        if (this.entries().includes(v.toLowerCase())) return { duplicate: true };
        if (this.entries().length >= MAX_ENTRIES) return { full: true };
        return null;
    }
}
