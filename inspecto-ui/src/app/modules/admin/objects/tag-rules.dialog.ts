import { Component, inject, signal, ChangeDetectionStrategy } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ObjectsService, Tag, TagRule } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { CASE_STATUSES, INCIDENT_PRIORITIES, INCIDENT_STATUSES } from './mail-model';
import {
    InspectoOptionPickerComponent,
    PickerOption,
    pickerOptions,
} from 'app/inspecto/components/option-picker.component';
import { PenFieldComponent } from 'app/inspecto/components/pen-field.component';

/**
 * Tag Rules manager (GLOSSARY §9) — Gmail-filter semantics: a saved search that applies a tag.
 * Rules auto-apply to newly created objects; **Apply now** tags every existing match in bulk.
 * Scoped to the pane's object type (the rule's `filter.type` is set from context).
 * Closes with `true` when anything changed so the shell can reload.
 */
@Component({
    selector: 'app-tag-rules-dialog',
    standalone: true,
    imports: [
        InspectoOptionPickerComponent,
        PenFieldComponent,
        ReactiveFormsModule,
        MatButtonModule,
        MatDialogModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatTooltipModule,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Tag rules — {{ data.typeLabel }}</h2>
        <mat-dialog-content class="flex flex-col gap-4 pt-2">
            <!-- Existing rules -->
            <section aria-label="Saved tag rules">
                @for (r of rules(); track r.name) {
                    <div
                        class="mb-1 flex items-center gap-2 rounded border px-3 py-2"
                        style="border-color: var(--gamma-border)"
                    >
                        <div class="min-w-0 flex-auto">
                            <div class="truncate text-sm font-semibold">{{ r.name }}</div>
                            <div class="text-secondary truncate text-xs">
                                tags "{{ r.tag }}" where {{ describe(r) }}
                            </div>
                        </div>
                        <button
                            mat-stroked-button
                            [disabled]="busy()"
                            (click)="applyNow(r)"
                            matTooltip="Tag every existing match"
                        >
                            Apply now
                        </button>
                        <button
                            mat-icon-button
                            [disabled]="busy()"
                            (click)="remove(r)"
                            matTooltip="Delete rule"
                            [attr.aria-label]="'Delete rule ' + r.name"
                        >
                            <mat-icon class="icon-size-5" svgIcon="heroicons_outline:trash"></mat-icon>
                        </button>
                    </div>
                } @empty {
                    <p class="text-secondary text-sm">No tag rules yet — save the first one below.</p>
                }
            </section>

            <!-- New rule -->
            <section aria-label="New tag rule">
                <h3 class="mb-2 text-xs font-bold uppercase tracking-wider opacity-60">New rule</h3>
                <form [formGroup]="form" class="grid gap-x-8 gap-y-1 sm:grid-cols-2">
                    <inspecto-pen-field
                        cdkFocusInitial
                        label="Rule name"
                        [required]="true"
                        placeholder="e.g. critical-is-urgent"
                        formControlName="name"
                        [error]="requiredError(form.controls.name)"
                    ></inspecto-pen-field>
                    <div>
                        <mat-form-field class="w-full" subscriptSizing="dynamic"><!-- form-allow: a typeahead over the tag registry -->
                            <mat-label>Apply tag</mat-label>
                            <input
                                matInput
                                formControlName="tag"
                                placeholder="existing or new tag"
                                required
                                [attr.list]="'tag-options'"
                            />
                            @if (form.controls.tag.hasError('required') && form.controls.tag.touched) {
                                <mat-error>Required.</mat-error>
                            }
                        </mat-form-field>
                        <datalist id="tag-options">
                            @for (t of data.registry; track t.name) {
                                <option [value]="t.name"></option>
                            }
                        </datalist>
                    </div>
                    <inspecto-pen-field
                        label="Search text"
                        placeholder="title / description contains…"
                        formControlName="q"
                    ></inspecto-pen-field>
                    <inspecto-option-picker label="Status" [options]="statuses" formControlName="status" />
                    <inspecto-option-picker label="Priority" [options]="priorities" formControlName="priority" />
                    <inspecto-option-picker label="Severity" [options]="severities" formControlName="severity" />
                    <inspecto-pen-field
                        label="Category starts with"
                        placeholder="e.g. Pipeline / Ingest"
                        formControlName="category"
                    ></inspecto-pen-field>
                    @if (criteriaMissing()) {
                        <p class="text-secondary text-sm sm:col-span-2" role="alert">
                            Set at least one criterion — an unconstrained rule would tag everything.
                        </p>
                    }
                </form>
            </section>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="requestClose()">Close</button>
            <button mat-stroked-button [disabled]="busy()" (click)="save(false)">Save</button>
            <button mat-flat-button color="primary" [disabled]="busy()" (click)="save(true)">
                Save &amp; apply now
            </button>
        </mat-dialog-actions>
    `,
})
export class TagRulesDialog {
    private api = inject(ObjectsService);
    private ref = inject(MatDialogRef<TagRulesDialog>);
    private toastr = inject(ToastrService);
    private fb = inject(FormBuilder);
    readonly data = inject<{
        type: string;
        typeLabel: string;
        registry: Tag[];
        rules: TagRule[];
    }>(MAT_DIALOG_DATA);

    private confirm = inject(InspectoConfirmService);

    /**
     * ⚠ Carries `changed()`: the shell reloads the list on it (and only re-reads tags otherwise), so
     * closing bare would leave every rule edit invisible behind the dialog. Guarded because the
     * "add a rule" form is a draft Esc used to discard silently.
     */
    readonly requestClose = guardDirtyClose(
        this.ref,
        () => this.form.dirty,
        this.confirm,
        () => this.changed(),
    );

    readonly rules = signal<TagRule[]>([...this.data.rules]);
    readonly busy = signal(false);
    readonly changed = signal(false);
    readonly criteriaMissing = signal(false);

    readonly statuses: PickerOption[] = [
        { value: '', label: 'any' },
        ...pickerOptions(this.data.type === 'INCIDENT' ? INCIDENT_STATUSES : CASE_STATUSES),
    ];
    readonly priorities: PickerOption[] = [{ value: '', label: 'any' }, ...pickerOptions(INCIDENT_PRIORITIES)];
    readonly severities: PickerOption[] = [
        { value: '', label: 'any' },
        ...pickerOptions(['INFO', 'WARNING', 'CRITICAL']),
    ];

    readonly form = this.fb.group({
        name: ['', Validators.required],
        tag: ['', Validators.required],
        q: [''],
        status: [''],
        priority: [''],
        severity: [''],
        category: [''],
    });

    /** A required row's error line: shown once the row was left or Save was pressed. */
    requiredError(c: AbstractControl): string {
        return c.touched && c.hasError('required') ? 'Required.' : '';
    }

    describe(r: TagRule): string {
        const f = r.filter ?? {};
        const parts = [
            f.q ? `text ~ "${f.q}"` : '',
            f.status ? `status = ${f.status}` : '',
            f.priority ? `priority = ${f.priority}` : '',
            f.severity ? `severity = ${f.severity}` : '',
            f.category ? `category ^ "${f.category}"` : '',
        ].filter(Boolean);
        return parts.join(' and ') || 'anything';
    }

    save(applyNow: boolean): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const v = this.form.getRawValue();
        if (!v.q && !v.status && !v.priority && !v.severity && !v.category) {
            this.criteriaMissing.set(true);
            return;
        }
        this.criteriaMissing.set(false);
        const rule: TagRule = {
            name: v.name!.trim(),
            tag: v.tag!.trim(),
            filter: {
                type: this.data.type,
                ...(v.q ? { q: v.q } : {}),
                ...(v.status ? { status: v.status } : {}),
                ...(v.priority ? { priority: v.priority } : {}),
                ...(v.severity ? { severity: v.severity } : {}),
                ...(v.category ? { category: v.category } : {}),
            },
        };
        this.busy.set(true);
        this.api.saveTagRule(rule).subscribe({
            next: (saved) => {
                this.rules.update((all) =>
                    [...all.filter((r) => r.name !== saved.name), saved].sort((a, b) => a.name.localeCompare(b.name)),
                );
                this.form.reset({
                    name: '',
                    tag: '',
                    q: '',
                    status: '',
                    priority: '',
                    severity: '',
                    category: '',
                });
                this.changed.set(true);
                if (applyNow) {
                    this.applyNow(saved);
                } else {
                    this.busy.set(false);
                    this.toastr.success(`Rule "${saved.name}" saved`);
                }
            },
            error: (e) => {
                this.busy.set(false);
                this.toastr.error(apiErrorMessage(e, 'Could not save rule'));
            },
        });
    }

    applyNow(rule: TagRule): void {
        this.busy.set(true);
        this.api.applyTagRule(rule.name).subscribe({
            next: (res) => {
                this.busy.set(false);
                this.changed.set(true);
                this.toastr.success(
                    `"${rule.name}": ${res.matched} matched, ${res.updated} newly tagged "${rule.tag}"`,
                );
            },
            error: (e) => {
                this.busy.set(false);
                this.toastr.error(apiErrorMessage(e, 'Apply failed'));
            },
        });
    }

    remove(rule: TagRule): void {
        this.busy.set(true);
        this.api.deleteTagRule(rule.name).subscribe({
            next: () => {
                this.busy.set(false);
                this.changed.set(true);
                this.rules.update((all) => all.filter((r) => r.name !== rule.name));
            },
            error: (e) => {
                this.busy.set(false);
                this.toastr.error(apiErrorMessage(e, 'Delete failed'));
            },
        });
    }
}
