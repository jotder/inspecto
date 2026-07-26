import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { forkJoin, of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ObjectsService, Tag, TagsService } from 'app/inspecto/api';

/** What the caller must say to open the dialog: which thing is being labelled. */
export interface TagAssignmentData {
    /** An annotation-target kind — `object` or a `ComponentStore.WRITABLE_TYPES` value. */
    targetKind: string;
    targetId: string;
    /** Optional display label (defaults to the id). */
    label?: string;
}

/**
 * Apply tags to one non-object target (BACKLOG D7 residual) — the Gmail label menu, next to the thing
 * being labelled rather than in the `/tags` vocabulary pane (which deliberately owns only removal and
 * vocabulary-wide rename/delete).
 *
 * **Why this is not {@link TagDialog}.** The mail pane's dialog persists through the `attributes.tags`
 * CSV on `OperationalObject` and is bulk/tri-state over a selection. This one addresses a single
 * `(targetKind, targetId)` through {@link TagsService}'s assignment edges — the source of truth for
 * every kind. The two look alike on purpose; they are not the same persistence path, so folding them
 * together would mean one dialog straddling both, which is how the split-brain D7 phase 2 closed
 * comes back.
 *
 * Kind-agnostic by construction: it takes the kind as data, so a second adopter pane is a menu item,
 * not another dialog. Mirrors {@code LinkAnalysisCommentsDialog} (D10) so the "actions on a saved view"
 * dialogs stay one family.
 *
 * Closes with the target's resulting tag list, or `null` if nothing was applied.
 */
@Component({
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatCheckboxModule,
        MatDialogModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        MatTooltipModule,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Tags</h2>
        <mat-dialog-content class="w-[26rem] max-w-full">
            <div class="text-secondary mb-3 text-sm">
                Tags on <strong>{{ data.label || data.targetId }}</strong>.
            </div>
            @if (loading()) {
                <div class="flex items-center gap-3 py-2 text-sm">
                    <mat-spinner diameter="20"></mat-spinner><span>Loading tags…</span>
                </div>
            } @else {
                <div class="flex flex-col gap-1">
                    @for (t of tags(); track t.name) {
                        <mat-checkbox class="block" [checked]="isOn(t.name)" (change)="toggle(t.name, $event.checked)">
                            {{ t.name }}
                        </mat-checkbox>
                    } @empty {
                        <p class="text-secondary text-sm">No tags yet — create the first one below.</p>
                    }
                </div>
                <div class="mt-2 flex items-center gap-2">
                    <mat-form-field class="flex-auto" subscriptSizing="dynamic">
                        <mat-label>New tag</mat-label>
                        <input
                            matInput
                            [formControl]="newTag"
                            (keyup.enter)="createTag()"
                            placeholder="e.g. billing"
                            aria-label="New tag name"
                            cdkFocusInitial
                        />
                    </mat-form-field>
                    <button
                        mat-icon-button
                        type="button"
                        [disabled]="!newTag.value?.trim() || creating()"
                        (click)="createTag()"
                        matTooltip="Create tag"
                        aria-label="Create tag"
                    >
                        <mat-icon class="icon-size-5" svgIcon="heroicons_outline:plus"></mat-icon>
                    </button>
                </div>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button [mat-dialog-close]="null">Cancel</button>
            <button mat-flat-button color="primary" [disabled]="!dirty() || saving()" (click)="apply()">Apply</button>
        </mat-dialog-actions>
    `,
})
export class TagAssignmentDialog {
    readonly data = inject<TagAssignmentData>(MAT_DIALOG_DATA);
    private objects = inject(ObjectsService);
    private tagsApi = inject(TagsService);
    private toastr = inject(ToastrService);
    private ref = inject(MatDialogRef<TagAssignmentDialog>);

    readonly loading = signal(true);
    readonly creating = signal(false);
    readonly saving = signal(false);
    readonly tags = signal<Tag[]>([]);
    readonly newTag = new FormControl('');

    /** Tags the target had when the dialog opened; `touched` records the user's decisions since. */
    private readonly initial = new Set<string>();
    private readonly touched = new Map<string, boolean>();
    readonly dirty = signal(false);

    constructor() {
        this.load();
    }

    private load(): void {
        forkJoin({
            registry: this.objects.tags(),
            assigned: this.tagsApi.assignments(this.data.targetKind, this.data.targetId),
        }).subscribe({
            next: ({ registry, assigned }) => {
                for (const name of assigned.tags) this.initial.add(name);
                // A tag already on the target but missing from the registry still shows — it exists on the data.
                const names = new Set([...registry.map((t) => t.name), ...assigned.tags]);
                this.tags.set(
                    [...names].sort().map((name) => registry.find((t) => t.name === name) ?? { name, createdAt: 0 }),
                );
                this.loading.set(false);
            },
            error: (e) => {
                this.loading.set(false);
                this.toastr.error(apiErrorMessage(e, 'Could not load tags.'));
            },
        });
    }

    isOn(name: string): boolean {
        return this.touched.get(name) ?? this.initial.has(name);
    }

    toggle(name: string, checked: boolean): void {
        this.touched.set(name, checked);
        this.dirty.set(true);
    }

    /**
     * Register a new tag, then pre-check it. Assigning an unregistered tag is a 404 (never an implicit
     * create), so the registry write has to land before Apply can reference the name.
     */
    createTag(): void {
        const name = (this.newTag.value ?? '').trim();
        if (!name || this.creating()) return;
        this.creating.set(true);
        this.objects.createTag(name).subscribe({
            next: (t) => {
                this.creating.set(false);
                this.newTag.setValue('');
                this.tags.update((all) =>
                    [...all.filter((x) => x.name !== t.name), t].sort((a, b) => a.name.localeCompare(b.name)),
                );
                this.toggle(t.name, true); // creating a tag here means "apply it"
            },
            error: (e) => {
                this.creating.set(false);
                this.toastr.error(apiErrorMessage(e, 'Could not create tag.'));
            },
        });
    }

    apply(): void {
        const add: string[] = [];
        const remove: string[] = [];
        for (const [name, checked] of this.touched) {
            if (checked && !this.initial.has(name)) add.push(name);
            if (!checked && this.initial.has(name)) remove.push(name);
        }
        if (!add.length && !remove.length) {
            this.ref.close(null);
            return;
        }
        const { targetKind, targetId } = this.data;
        this.saving.set(true);
        forkJoin([
            ...add.map((t) => this.tagsApi.assign(targetKind, targetId, t)),
            ...remove.map((t) => this.tagsApi.unassign(targetKind, targetId, t)),
            of(null), // keeps forkJoin honest if one side is empty
        ]).subscribe({
            next: () => {
                const result = [...this.tags().map((t) => t.name).filter((n) => this.isOn(n))].sort();
                this.saving.set(false);
                this.ref.close(result);
            },
            error: (e) => {
                this.saving.set(false);
                this.toastr.error(apiErrorMessage(e, 'Could not save tags.'));
            },
        });
    }
}
