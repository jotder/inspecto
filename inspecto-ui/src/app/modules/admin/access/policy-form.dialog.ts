import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { Observable } from 'rxjs';

import {
    AccessService,
    apiErrorMessage,
    isStaleVersionError,
    PolicyDef,
    PolicyPreview,
    PolicyPreviewCell,
    PolicyWarning,
    STALE_WRITE_MESSAGE,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';

export interface PolicyFormData {
    /** Absent = create; present = edit (the name is immutable), or override a built-in (`override`). */
    policy?: PolicyDef;
    /** Authoring a policy with a built-in's name — it replaces that built-in wholesale (F6, D8). */
    override?: boolean;
    /** Every policy name already in force (create-mode duplicate guard). */
    existingNames: string[];
    /** The served resource-kind vocabulary (`GET /access/policies` → `resourceKinds`). */
    resourceKinds: string[];
    /** The rest of the authored list — the draft is `[...others, thisPolicy]`, for preview and save. */
    others: PolicyDef[];
    /** The server's current warnings for this policy (edit mode). */
    warnings: PolicyWarning[];
    /** Persist the whole authored list; the dialog stays open on a refusal and shows the server's message. */
    save: (authored: PolicyDef[]) => Observable<unknown>;
}

const ACTIONS = ['read', 'write', 'operate'] as const;

/** One column of the impact matrix: an action at route level, or an action on one resource kind. */
interface MatrixColumn {
    action: string;
    kind: string | null;
    label: string;
}

/**
 * Create/edit one Access Policy (Settings ▸ Access ▸ Policies, policy-authoring S3/S4): name
 * (immutable once created), effect, target actions and resource kinds, and the `when` condition.
 * **Preview impact** evaluates the unsaved draft server-side (`POST /access/policies/preview` — the
 * server is the only evaluator; no client-side mirror) as a role × action matrix with the flipped
 * cells marked, plus the draft's lint warnings. Save goes through the host (a full replace of the
 * authored list); a refusal — including `would-lock-out` — keeps the dialog open with the server's
 * message. Cancel/Esc/backdrop go through the dirty guard.
 */
@Component({
    selector: 'app-policy-form-dialog',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatCheckboxModule,
        MatDialogModule,
        MatFormFieldModule,
        MatInputModule,
        MatRadioModule,
        InspectoAlertComponent,
    ],
    template: `
        <h2 mat-dialog-title>{{ title }}</h2>
        <form (ngSubmit)="save()" [formGroup]="form">
            <mat-dialog-content class="flex max-w-full flex-col gap-4">
                @if (data.override) {
                    <inspecto-alert variant="warning" title="Replacing a built-in policy">
                        Saving replaces the built-in <strong>{{ data.policy?.name }}</strong> wholesale — including any
                        exemption its condition carries. Keep that clause if the replacement should still spare the same
                        subjects.
                    </inspecto-alert>
                }
                @if (!data.policy) {
                    <mat-form-field subscriptSizing="dynamic">
                        <mat-label>Policy name</mat-label>
                        <input matInput formControlName="name" placeholder="e.g. contractor-write-freeze" />
                        @if (form.controls.name.hasError('required')) {
                            <mat-error>A policy name is required.</mat-error>
                        } @else if (form.controls.name.hasError('pattern')) {
                            <mat-error>Letters, digits, '.', '_' and '-' only.</mat-error>
                        } @else if (form.controls.name.hasError('duplicate')) {
                            <mat-error>A policy with this name already exists.</mat-error>
                        }
                    </mat-form-field>
                }

                <div class="flex flex-col gap-1">
                    <span id="policy-effect-label" class="text-secondary text-sm font-medium">Effect</span>
                    <mat-radio-group formControlName="effect" aria-labelledby="policy-effect-label" class="flex gap-4">
                        <mat-radio-button value="deny">Deny</mat-radio-button>
                        <mat-radio-button value="allow">Allow</mat-radio-button>
                    </mat-radio-group>
                </div>

                <fieldset class="flex flex-wrap gap-x-4">
                    <legend class="text-secondary mb-1 text-sm font-medium">Actions (none = every action)</legend>
                    @for (a of actions; track a) {
                        <mat-checkbox [checked]="selectedActions.has(a)" (change)="toggleAction(a, $event.checked)">
                            {{ a }}
                        </mat-checkbox>
                    }
                </fieldset>

                <fieldset class="flex flex-wrap gap-x-4">
                    <legend class="text-secondary mb-1 text-sm font-medium">
                        Resource kinds (none = route level and every row)
                    </legend>
                    @for (k of kinds; track k) {
                        <mat-checkbox [checked]="selectedKinds.has(k)" (change)="toggleKind(k, $event.checked)">
                            {{ k }}
                        </mat-checkbox>
                    }
                </fieldset>

                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Condition (when)</mat-label>
                    <textarea
                        matInput
                        formControlName="when"
                        rows="3"
                        class="font-mono"
                        placeholder="subject.roles contains 'contractor'"
                    ></textarea>
                    <mat-hint
                        >Over subject.&#123;id, capabilities, dataScopes, roles, allowlisted claims&#125;,
                        env.&#123;action, route, space&#125; and resource.*. Blank = whenever the target
                        matches.</mat-hint
                    >
                </mat-form-field>

                @if (warnings().length) {
                    <inspecto-alert variant="warning" title="Check this policy">
                        <ul class="list-disc pl-5">
                            @for (w of warnings(); track w.code + w.message) {
                                <li>{{ w.message }}</li>
                            }
                        </ul>
                    </inspecto-alert>
                }
                @if (error(); as e) {
                    <inspecto-alert variant="error" title="Not saved">{{ e }}</inspecto-alert>
                }

                <section class="flex flex-col gap-2" aria-labelledby="policy-impact-heading">
                    <div class="flex items-center gap-3">
                        <h3 id="policy-impact-heading" class="text-base font-medium">Impact</h3>
                        <button mat-stroked-button type="button" (click)="runPreview()" [disabled]="previewing()">
                            {{ previewing() ? 'Evaluating…' : 'Preview impact' }}
                        </button>
                    </div>
                    @if (preview(); as p) {
                        @if (!p.enabled) {
                            <inspecto-alert variant="info" title="Policy engine not active">
                                {{ p.reason || 'Access policies are enforced only on the Enterprise edition.' }}
                            </inspecto-alert>
                        } @else {
                            <p class="text-secondary text-sm" role="status">
                                {{ changedCount() }} of {{ p.cells?.length ?? 0 }} decisions change. Each role is
                                evaluated by its capabilities and name only — a condition on subject.id or a claim shows
                                no change here.
                            </p>
                            <div class="max-h-80 overflow-auto">
                                <table class="w-full text-left text-xs">
                                    <thead class="text-secondary border-b">
                                        <tr>
                                            <th scope="col" class="py-1 pr-3 font-medium">Role</th>
                                            @for (col of columns(); track col.label) {
                                                <th scope="col" class="py-1 pr-3 font-medium">{{ col.label }}</th>
                                            }
                                        </tr>
                                    </thead>
                                    <tbody>
                                        @for (role of p.roles ?? []; track role) {
                                            <tr class="border-b">
                                                <th scope="row" class="py-1 pr-3 font-medium">{{ role }}</th>
                                                @for (col of columns(); track col.label) {
                                                    @if (cell(role, col); as c) {
                                                        <td class="py-1 pr-3" [class.font-semibold]="c.changed">
                                                            @if (c.changed) {
                                                                {{ c.before }} → {{ c.after }}
                                                                <span class="sr-only">(changed)</span>
                                                            } @else {
                                                                <span class="text-secondary">{{ c.after }}</span>
                                                            }
                                                        </td>
                                                    } @else {
                                                        <td class="py-1 pr-3"></td>
                                                    }
                                                }
                                            </tr>
                                        }
                                    </tbody>
                                </table>
                            </div>
                        }
                    }
                </section>
            </mat-dialog-content>
            <mat-dialog-actions align="end">
                <button mat-button type="button" (click)="requestClose()">Cancel</button>
                <button mat-flat-button color="primary" type="submit" [disabled]="saving()">
                    {{ saving() ? 'Saving…' : 'Save' }}
                </button>
            </mat-dialog-actions>
        </form>
    `,
})
export class PolicyFormDialog {
    private readonly fb = inject(FormBuilder);
    private readonly api = inject(AccessService);
    private readonly ref = inject(MatDialogRef<PolicyFormDialog, PolicyDef | undefined>);
    private readonly confirm = inject(InspectoConfirmService);
    readonly data = inject<PolicyFormData>(MAT_DIALOG_DATA);

    readonly actions = ACTIONS;
    /** The served vocabulary, plus any stored value outside it (shown verbatim, never dropped). */
    readonly kinds = [...new Set([...this.data.resourceKinds, ...(this.data.policy?.target?.resourceKinds ?? [])])];
    readonly selectedActions = new Set<string>(this.data.policy?.target?.actions ?? []);
    readonly selectedKinds = new Set<string>(this.data.policy?.target?.resourceKinds ?? []);
    private targetDirty = false;

    readonly title = this.data.override
        ? `Override built-in "${this.data.policy?.name}"`
        : this.data.policy
          ? `Edit policy "${this.data.policy.name}"`
          : 'New policy';

    readonly form = this.fb.nonNullable.group({
        name: [
            this.data.policy?.name ?? '',
            this.data.policy
                ? []
                : [
                      Validators.required,
                      Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/),
                      (c: { value: string }) =>
                          this.data.existingNames.includes(String(c.value).trim().toLowerCase())
                              ? { duplicate: true }
                              : null,
                  ],
        ],
        effect: [(this.data.policy?.effect ?? 'deny') as 'allow' | 'deny'],
        when: [this.data.policy?.when ?? ''],
    });

    readonly saving = signal(false);
    readonly error = signal<string | null>(null);
    readonly previewing = signal(false);
    readonly preview = signal<PolicyPreview | null>(null);
    /** The server's findings for THIS policy — the edit-time ones until a preview refreshes them. */
    readonly warnings = signal<PolicyWarning[]>(this.data.warnings);

    readonly changedCount = computed(() => (this.preview()?.cells ?? []).filter((c) => c.changed).length);
    readonly columns = computed<MatrixColumn[]>(() => {
        const p = this.preview();
        if (!p?.enabled) return [];
        const cols: MatrixColumn[] = [];
        for (const kind of [null, ...(p.kinds ?? [])])
            for (const action of p.actions ?? [])
                cols.push({ action, kind, label: kind ? `${action} · ${kind}` : action });
        return cols;
    });

    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty || this.targetDirty, this.confirm);

    toggleAction(a: string, checked: boolean): void {
        if (checked) this.selectedActions.add(a);
        else this.selectedActions.delete(a);
        this.targetDirty = true;
    }

    toggleKind(k: string, checked: boolean): void {
        if (checked) this.selectedKinds.add(k);
        else this.selectedKinds.delete(k);
        this.targetDirty = true;
    }

    cell(role: string, col: MatrixColumn): PolicyPreviewCell | undefined {
        return this.preview()?.cells?.find(
            (c) => c.role === role && c.action === col.action && (c.resourceKind ?? null) === col.kind,
        );
    }

    /** The policy as authored in the form (name lower-cased, like the server). */
    draft(): PolicyDef {
        const v = this.form.getRawValue();
        const actions = ACTIONS.filter((a) => this.selectedActions.has(a));
        const resourceKinds = this.kinds.filter((k) => this.selectedKinds.has(k));
        return {
            name: (this.data.policy?.name ?? v.name).trim().toLowerCase(),
            effect: v.effect,
            ...(actions.length || resourceKinds.length
                ? {
                      target: {
                          ...(actions.length ? { actions: [...actions] } : {}),
                          ...(resourceKinds.length ? { resourceKinds } : {}),
                      },
                  }
                : {}),
            ...(v.when.trim() ? { when: v.when.trim() } : {}),
        };
    }

    runPreview(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const draft = this.draft();
        this.previewing.set(true);
        this.error.set(null);
        this.api.previewPolicies([...this.data.others, draft]).subscribe({
            next: (p) => {
                this.preview.set(p);
                this.warnings.set((p.warnings ?? []).filter((w) => w.policy === draft.name));
                this.previewing.set(false);
            },
            error: (err) => {
                this.previewing.set(false);
                this.preview.set(null);
                this.error.set(apiErrorMessage(err, 'Could not evaluate the draft'));
            },
        });
    }

    save(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const draft = this.draft();
        this.saving.set(true);
        this.error.set(null);
        this.data.save([...this.data.others, draft]).subscribe({
            next: () => {
                this.saving.set(false);
                this.ref.close(draft);
            },
            error: (err) => {
                this.saving.set(false);
                this.error.set(
                    isStaleVersionError(err) ? STALE_WRITE_MESSAGE : apiErrorMessage(err, 'Could not save the policy'),
                );
            },
        });
    }
}
