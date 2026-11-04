import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { uniqueNameValidator } from 'app/inspecto/investigation/unique-name';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage } from 'app/inspecto/api';
import { PenFieldComponent } from 'app/inspecto/components/pen-field.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
// Direct file imports (NOT the `app/inspecto/query` barrel) — see the note in `rule-types.ts`.
import { QueryModel } from '../query/query-types';
import { SqlParam } from '../query/query-sql';
import { buildRuleTemplate, RuleTemplate } from './rule-types';
import { RulesService } from './rules.service';

/** Dialog data: the query to save + source name + display SQL + the parameterized SQL and its binds. */
export interface RuleSaveData {
    model: QueryModel;
    sql: string;
    sourceName: string;
    params: SqlParam[];
    paramSql: string;
}

/**
 * Save-as-rule dialog (Pro Max). Names the template, lets the operator set a default value for each
 * condition-derived **parameter** (`:fieldValue`), and persists it via {@link RulesService} (the
 * `rule-template` component type). Closes with the saved {@link RuleTemplate}.
 */
@Component({
    selector: 'app-rule-save-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        PenFieldComponent,
        MatProgressSpinnerModule,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Save as rule</h2>
        <mat-dialog-content class="space-y-2">
            <p class="text-secondary text-xs">
                Saves this query as a reusable rule template over <span class="font-mono">{{ data.sourceName }}</span
                >.
            </p>
            <form [formGroup]="form" (ngSubmit)="save()">
                <inspecto-pen-field
                    cdkFocusInitial
                    label="Rule id"
                    [required]="true"
                    [mono]="true"
                    placeholder="e.g. high_error_rate"
                    formControlName="name"
                    [error]="nameError()"
                ></inspecto-pen-field>

                @if (data.params.length) {
                    <div class="mt-3">
                        <div class="mb-1 text-xs font-semibold uppercase tracking-wider opacity-60">Parameters</div>
                        <div formGroupName="params" class="space-y-1">
                            @for (p of data.params; track p.name) {
                                <inspecto-pen-field
                                    label=":{{ p.name }} — {{ p.field }} {{ p.operator }}"
                                    [formControlName]="p.name"
                                ></inspecto-pen-field>
                            }
                        </div>
                    </div>
                }
            </form>

            <div class="mb-1 text-xs font-semibold uppercase tracking-wider opacity-60">SQL</div>
            <pre
                class="max-h-40 overflow-auto rounded border p-2 font-mono text-xs"
                style="background: var(--gamma-bg-default); border-color: var(--gamma-border)"
                >{{ data.paramSql || data.sql }}</pre
            >
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button (click)="requestClose()">Cancel</button>
            <button type="button" mat-flat-button color="primary" (click)="save()" [disabled]="saving()">
                @if (saving()) {
                    <mat-spinner diameter="16" class="mr-2"></mat-spinner>
                }
                <span>Save rule</span>
            </button>
        </mat-dialog-actions>
    `,
})
export class RuleSaveDialog {
    private fb = inject(FormBuilder);
    private rules = inject(RulesService);
    private toastr = inject(ToastrService);
    private ref = inject(MatDialogRef<RuleSaveDialog, RuleTemplate>);
    private confirm = inject(InspectoConfirmService);
    readonly data = inject<RuleSaveData>(MAT_DIALOG_DATA);

    /** Guarded close: Esc / backdrop / Cancel confirm before discarding a dirty form. */
    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);

    readonly saving = signal(false);
    readonly form = this.fb.group({
        name: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/)]],
        params: this.fb.group(
            Object.fromEntries(this.data.params.map((p) => [p.name, this.fb.nonNullable.control(p.value)])),
        ),
    });

    constructor() {
        // House form rule: block a duplicate id inline on create rather than relying on the server 409.
        const destroyRef = inject(DestroyRef);
        this.rules
            .list()
            .pipe(takeUntilDestroyed(destroyRef))
            .subscribe((all) => {
                const taken = all.map((r) => r.id);
                this.form.controls.name.addValidators(uniqueNameValidator(() => taken));
                this.form.controls.name.updateValueAndValidity({ emitEvent: false });
            });
    }

    /** The Rule id row's error line: shown once the row was left or Save rule was pressed. */
    nameError(): string {
        const c = this.form.controls.name;
        if (!c.touched) return '';
        // The boxed field went red on an empty id; a row has no outline, so it says so.
        if (c.hasError('required')) return 'A rule id is required.';
        if (c.hasError('pattern')) return 'Letters, digits, dot, dash, underscore; start alphanumeric.';
        if (c.hasError('duplicate')) return 'A rule with this id already exists.';
        return '';
    }

    private get paramsGroup(): FormGroup {
        return this.form.controls.params;
    }

    save(): void {
        const ctrl = this.form.controls.name;
        const name = String(ctrl.value ?? '').trim();
        if (!name || ctrl.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const params: SqlParam[] = this.data.params.map((p) => ({
            ...p,
            value: String(this.paramsGroup.get(p.name)?.value ?? p.value),
        }));
        const rule = buildRuleTemplate(name, this.data.sourceName, this.data.model, {
            params,
            paramSql: this.data.paramSql,
        });
        this.saving.set(true);
        this.rules.save(rule).subscribe({
            next: (saved) => {
                this.saving.set(false);
                this.toastr.success(`Rule "${name}" saved`);
                this.ref.close(saved);
            },
            error: (e) => {
                this.saving.set(false);
                this.toastr.error(
                    e?.status === 503
                        ? 'Writes are disabled (no write root configured).'
                        : apiErrorMessage(e, `Could not save "${name}"`),
                );
            },
        });
    }
}
