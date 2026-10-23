import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ToastrService } from 'ngx-toastr';

import { LensService, apiErrorMessage } from 'app/inspecto/api';
import {
    ExclusionCategoriesService,
    ExclusionListChip,
    ExclusionVisibility,
} from 'app/inspecto/api/exclusion-categories.service';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';

/** What every change made here records as its reason in the list's fact log. */
const REASON = 'changed in Settings > Link Analysis';

/**
 * Settings ▸ Link Analysis ▸ **Category exclusion lists** (LA-GROW-EXCLUDE-1 phase 4, GE-6 / GE-8 / GE-9): the Space's
 * administrator-kept exclusion lists (marketing VIPs, politicians, defence, intelligence). Create one, and switch per
 * list whether it applies to every new Investigation (`autoApply`) and whether analysts see its members (`restricted`).
 * Members are added on the Entity Lists page. Reads need Space access; every change needs
 * `canManageExclusionCategories` (the server answers 403 otherwise). The graph-side chips are another lane's.
 */
@Component({
    selector: 'inspecto-exclusion-categories',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatCheckboxModule,
        MatFormFieldModule,
        MatInputModule,
        MatSelectModule,
        InspectoAlertComponent,
    ],
    template: `
        <section class="flex max-w-4xl flex-col gap-3" aria-labelledby="exclusion-categories-heading">
            <h2 id="exclusion-categories-heading" class="text-lg font-semibold">Category exclusion lists</h2>
            <p class="text-secondary m-0 text-sm">
                Lists of numbers no Investigation should follow, kept by an administrator. An auto-applied list is the
                first step of every new Investigation in this space. A restricted list shows analysts its name and how
                many nodes it removed, never its members. Add members on the Entity Lists page.
            </p>
            @if (loading()) {
                <p class="text-secondary text-sm">Reading the exclusion lists…</p>
            } @else if (loadError()) {
                <inspecto-alert variant="error" title="Exclusion lists unavailable">{{ loadError() }}</inspecto-alert>
            } @else {
                @for (l of broken(); track l.id) {
                    <inspecto-alert variant="warning" title="New Investigations are refused">
                        "{{ l.title }}" is applied to every new Investigation, but {{ l.problem }}. Until it is fixed or
                        switched off, no Investigation can be created in this space.
                    </inspecto-alert>
                }
                @if (categories().length) {
                    <ul class="flex flex-col divide-y rounded-lg border" aria-label="Category exclusion lists">
                        @for (l of categories(); track l.id) {
                            <li class="flex flex-wrap items-center justify-between gap-3 px-3 py-2">
                                <span class="text-sm">
                                    <strong>{{ l.title }}</strong>
                                    <span class="text-secondary"> - {{ l.entityType }}, {{ l.size }} members</span>
                                </span>
                                <span class="flex flex-wrap items-center gap-4">
                                    <mat-checkbox
                                        [checked]="l.autoApply"
                                        [disabled]="!canEdit() || busy() === l.id"
                                        (change)="setAutoApply(l, $event.checked)"
                                        >Apply to every new Investigation</mat-checkbox
                                    >
                                    <mat-checkbox
                                        [checked]="l.visibility === 'restricted'"
                                        [disabled]="!canEdit() || busy() === l.id"
                                        (change)="setRestricted(l, $event.checked)"
                                        >Restricted</mat-checkbox
                                    >
                                </span>
                            </li>
                        }
                    </ul>
                } @else {
                    <p class="text-secondary text-sm">No category list yet.</p>
                }
                @if (saveError()) {
                    <inspecto-alert variant="error" title="Not saved">{{ saveError() }}</inspecto-alert>
                }
                @if (canEdit()) {
                    <form [formGroup]="form" class="flex flex-wrap items-start gap-3" (ngSubmit)="create()">
                        <mat-form-field class="w-72" subscriptSizing="dynamic">
                            <mat-label>New category list</mat-label>
                            <input matInput formControlName="title" autocomplete="off" maxlength="200" />
                            @if (form.controls.title.hasError('required')) {
                                <mat-error>Enter a name, for example Politicians.</mat-error>
                            }
                        </mat-form-field>
                        <mat-form-field class="w-48" subscriptSizing="dynamic">
                            <mat-label>Entity Type</mat-label>
                            <mat-select formControlName="entityType">
                                @for (t of entityTypes(); track t.id) {
                                    <mat-option [value]="t.id">{{ t.label }}</mat-option>
                                }
                            </mat-select>
                            @if (form.controls.entityType.hasError('required')) {
                                <mat-error>Choose an Entity Type.</mat-error>
                            }
                        </mat-form-field>
                        <mat-checkbox formControlName="autoApply" class="mt-3"
                            >Apply to every new Investigation</mat-checkbox
                        >
                        <mat-checkbox formControlName="restricted" class="mt-3">Restricted</mat-checkbox>
                        <button mat-stroked-button type="submit" class="mt-1" [disabled]="busy() !== null">
                            Create category list
                        </button>
                    </form>
                } @else {
                    <inspecto-alert variant="info" title="Read only">
                        Creating and changing category lists needs the canManageExclusionCategories capability.
                    </inspecto-alert>
                }
            }
        </section>
    `,
})
export class ExclusionCategoriesComponent implements OnInit {
    /** The Entity Types in force (`entityTypesInForce` of the Link Analysis settings), offered for a new list. */
    readonly entityTypes = input<{ id: string; label: string }[]>([]);

    private api = inject(ExclusionCategoriesService);
    private lens = inject(LensService);
    private toastr = inject(ToastrService);

    readonly canEdit = computed(() => this.lens.canManageExclusionCategories());
    readonly loading = signal(true);
    readonly loadError = signal<string | null>(null);
    readonly saveError = signal<string | null>(null);
    /** The list id a change is in flight for, `''` for a create, null when idle. */
    readonly busy = signal<string | null>(null);
    private readonly lists = signal<ExclusionListChip[]>([]);
    readonly categories = computed(() => this.lists().filter((l) => l.ownerKind === 'admin'));
    /** Auto-apply lists the server cannot seal: each one refuses every Investigation create (fail closed). */
    readonly broken = computed(() => this.categories().filter((l) => l.autoApply && l.problem));

    readonly form = new FormGroup({
        title: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(200)] }),
        entityType: new FormControl('', { nonNullable: true, validators: Validators.required }),
        autoApply: new FormControl(false, { nonNullable: true }),
        restricted: new FormControl(false, { nonNullable: true }),
    });

    ngOnInit(): void {
        this.reload();
    }

    create(): void {
        if (this.busy() !== null) return;
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const v = this.form.getRawValue();
        this.run(
            '',
            this.api.create({
                title: v.title.trim(),
                entityType: v.entityType,
                autoApply: v.autoApply,
                visibility: v.restricted ? 'restricted' : 'open',
                reason: REASON,
            }),
            `Category list "${v.title.trim()}" created.`,
            () => this.form.reset(),
        );
    }

    setAutoApply(l: ExclusionListChip, on: boolean): void {
        this.run(
            l.id,
            this.api.setAttributes(l.id, { autoApply: on, reason: REASON }),
            on ? `"${l.title}" now applies to every new Investigation.` : `"${l.title}" is now optional.`,
        );
    }

    setRestricted(l: ExclusionListChip, on: boolean): void {
        const visibility: ExclusionVisibility = on ? 'restricted' : 'open';
        this.run(
            l.id,
            this.api.setAttributes(l.id, { visibility, reason: REASON }),
            on ? `"${l.title}" members are now restricted.` : `"${l.title}" members are now visible to analysts.`,
        );
    }

    private run(
        key: string,
        call: ReturnType<ExclusionCategoriesService['setAttributes']>,
        done: string,
        after?: () => void,
    ): void {
        this.busy.set(key);
        this.saveError.set(null);
        call.subscribe({
            next: () => {
                this.busy.set(null);
                after?.();
                this.toastr.success(done);
                this.reload();
            },
            error: (err) => {
                this.busy.set(null);
                const message = apiErrorMessage(err, 'The change was not saved.');
                this.saveError.set(
                    err?.status === 403 ? 'You are not allowed to change category lists. Server: ' + message : message,
                );
                this.reload(); // a refused toggle snaps back to what the server holds
            },
        });
    }

    private reload(): void {
        this.api.list().subscribe({
            next: (r) => {
                this.loading.set(false);
                this.lists.set(r.lists ?? []);
            },
            error: (err) => {
                this.loading.set(false);
                this.loadError.set(apiErrorMessage(err, 'The exclusion lists route did not answer.'));
            },
        });
    }
}
