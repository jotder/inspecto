import {
    ChangeDetectionStrategy,
    Component,
    DestroyRef,
    ElementRef,
    Injector,
    afterNextRender,
    computed,
    inject,
    signal,
    viewChild,
    viewChildren,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { FormArray, FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { catchError, forkJoin, of, throwError } from 'rxjs';
import { apiErrorMessage, ComponentDef, ComponentsService, LensService, ObjectsService } from 'app/inspecto/api';
import { AttributeTier, AttributeType } from 'app/inspecto/component-model';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { ComponentHistoryDialog } from 'app/inspecto/components/component-history.dialog';
import { InspectoDialogResizeDirective } from 'app/inspecto/components/dialog-resize.directive';
import { InspectoOptionPickerComponent, PickerOption } from 'app/inspecto/components/option-picker.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import {
    ANALYTICS_KEYS,
    CASE_TYPE_PATTERN,
    caseTypesOf,
    CONTROL_CHOICES,
    choiceValuesOf,
    controlLabel,
    findingsSpecId,
    FieldDraft,
    fieldsAbove,
    fromWire,
    isBuiltInImpact,
    keyOf,
    newChoice,
    newField,
    toSections,
    toWire,
    usesChoices,
    usesPattern,
    validateDraft,
} from './findings-spec-editor.model';
import { findingsAttributes } from './mail-model';

/** Opened from the Cases pane toolbar (D4). Parameterised on the object type (D2 — Case only today). */
export interface FindingsSpecEditorData {
    /** The object type whose Findings fields are edited, e.g. `CASE`. */
    objectType: string;
    /** Display name, e.g. `Case`. */
    typeLabel: string;
}

const KIND = 'findings-spec' as const;
/** Picker value for a Case type that has no form yet (never a legal Case type). */
const NEW_TYPE = '#new';

/**
 * **Findings fields** editor (findings-spec authoring UI S2–S5, design
 * `docs/archived-documents/plans-archive/findings-spec-authoring-ui-design.md`, Option C). Lets a Case-desk lead see, add, edit,
 * reorder and remove the questions the Case Findings panel asks — with a live preview that is the real
 * `<inspecto-schema-form>` — without ever seeing a key, a tier, a regex or TOON.
 *
 * Saves through the generic `/components/findings-spec/{type}` CRUD (no new endpoint), gated server-side on
 * `canManageIncidents` (D1); `POST` while the built-in is in force, `PUT` + `If-Match` once authored, so a
 * colleague's concurrent save is refused (409) rather than overwritten. Closes with `true` after any
 * persisted change so the Cases pane refetches the effective spec.
 */
@Component({
    selector: 'app-findings-spec-editor-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatDialogModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatRadioModule,
        MatSlideToggleModule,
        MatTooltipModule,
        InspectoAlertComponent,
        InspectoDialogResizeDirective,
        InspectoOptionPickerComponent,
        InspectoSchemaFormComponent,
        InspectoSkeletonComponent,
        StatusBadgeComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './findings-spec-editor.dialog.html',
})
export class FindingsSpecEditorDialog {
    readonly data = inject<FindingsSpecEditorData>(MAT_DIALOG_DATA);
    private ref = inject<MatDialogRef<FindingsSpecEditorDialog, boolean>>(MatDialogRef);
    private objects = inject(ObjectsService);
    private components = inject(ComponentsService);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    private dialog = inject(MatDialog);
    private fb = inject(FormBuilder);
    private destroyRef = inject(DestroyRef);
    private injector = inject(Injector);

    /** D1: writing needs `canManageIncidents`. Everyone else gets the same screen, read-only (T1). */
    readonly canEdit = inject(LensService).canManageIncidents;

    /** Case only: whose form is edited — `null` = the shared form every Case without its own uses. */
    readonly caseType = signal<string | null>(null);
    /** Case types that already have their own form (FINDINGS-EDITOR-PER-CASE-TYPE-1). */
    readonly caseTypes = signal<string[]>([]);
    readonly perCaseType = this.data.objectType.toLowerCase() === 'case';
    private get id(): string {
        return findingsSpecId(this.data.objectType, this.caseType());
    }
    /** The form picker: `''` = shared, a Case type, or {@link NEW_TYPE}. */
    readonly targetCtrl = new FormControl('', { nonNullable: true });
    readonly newTypeCtrl = new FormControl('', {
        nonNullable: true,
        validators: [Validators.required, Validators.pattern(CASE_TYPE_PATTERN)],
    });
    readonly targetOptionsList = computed<PickerOption[]>(() => [
        { value: '', label: `Every ${this.data.typeLabel} without its own form`, hint: 'The shared form' },
        ...this.caseTypes().map((t) => ({ value: t, label: t, hint: 'This Case type’s own form' })),
        ...(this.canEdit()
            ? [{ value: NEW_TYPE, label: 'A new Case type…', hint: 'Start its own form from the shared one' }]
            : []),
    ]);
    readonly askingNewType = signal(false);

    readonly loading = signal(true);
    readonly loadError = signal<string | null>(null);
    /** The authored component, or `null` while the built-in is in force. */
    readonly authored = signal<ComponentDef | null>(null);
    readonly fields = signal<FieldDraft[]>([]);
    readonly selectedUid = signal<string | null>(null);
    readonly dirty = signal(false);
    readonly saving = signal(false);
    /** A server 422 that got past the client mirror — shown verbatim so drift is visible, not swallowed. */
    readonly serverError = signal<string | null>(null);
    /** A 409: someone else saved since this dialog loaded. */
    readonly conflict = signal(false);
    /** Per-field "Technical details" disclosure (D6: collapsed by default). */
    readonly showTechnical = signal(false);
    /** Set after any persisted change (save, restore, history restore) — the close result. */
    private changed = false;

    readonly problems = computed(() => validateDraft(this.fields()));
    readonly selected = computed(() => this.fields().find((f) => f.uid === this.selectedUid()) ?? null);
    readonly previewSpecs = computed(() =>
        findingsAttributes({ objectType: this.id, sections: toSections(this.fields()) }),
    );
    /** What the preview holds, carried across every draft edit (a spec swap rebuilds its controls). */
    readonly previewSeed = signal<Record<string, unknown>>({});
    private readonly preview = viewChild<InspectoSchemaFormComponent>('preview');
    private readonly choiceInputs = viewChildren<ElementRef<HTMLInputElement>>('choiceInput');

    readonly controlLabel = controlLabel;
    readonly usesChoices = usesChoices;
    readonly usesPattern = usesPattern;

    /** The selected field's plain properties. Choices are a parallel array of labels (uids stay in the draft). */
    readonly fieldForm = this.fb.nonNullable.group({
        label: '',
        help: '',
        placeholder: '',
        type: 'string' as AttributeType,
        tier: 'required' as AttributeTier,
        required: false,
        pattern: '',
        min: '',
        max: '',
        showWhenOn: false,
        showWhenTarget: '',
        showWhenSense: 'is' as 'is' | 'isNot',
        showWhenValue: '',
        choices: this.fb.array<FormControl<string>>([]),
    });
    private syncing = false;

    readonly requestClose = guardDirtyClose(
        this.ref,
        () => this.dirty(),
        this.confirm,
        () => this.changed,
    );

    constructor() {
        this.fieldForm.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.applyForm());
        this.targetCtrl.valueChanges
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((v) => void this.onTargetPicked(v));
        if (this.perCaseType) this.loadCaseTypes();
        this.load();
    }

    // ── which form ──

    private loadCaseTypes(): void {
        this.components.list(KIND).subscribe({
            next: (defs) =>
                this.caseTypes.set(
                    caseTypesOf(
                        defs.map((d) => d.name),
                        this.data.objectType,
                    ),
                ),
            // The picker degrades to the shared form alone; the dialog itself still works.
            error: () => this.caseTypes.set([]),
        });
    }

    private async onTargetPicked(v: string): Promise<void> {
        if (v === NEW_TYPE) {
            this.askingNewType.set(true);
            this.newTypeCtrl.reset('');
            return;
        }
        this.askingNewType.set(false);
        await this.switchTo(v || null);
    }

    /** Open the form for a Case type typed in by the lead (its own form, or a new one seeded from the shared). */
    async openNewType(): Promise<void> {
        if (this.newTypeCtrl.invalid) {
            this.newTypeCtrl.markAsTouched();
            return;
        }
        const t = this.newTypeCtrl.value.trim();
        if (!(await this.switchTo(t))) return;
        this.askingNewType.set(false);
        if (!this.caseTypes().includes(t)) this.caseTypes.set([...this.caseTypes(), t].sort());
        this.targetCtrl.setValue(t, { emitEvent: false });
    }

    /** Discard-guarded switch; returns false (and restores the picker) when the lead keeps the draft. */
    private async switchTo(caseType: string | null): Promise<boolean> {
        if (caseType === this.caseType()) return true;
        if (this.dirty()) {
            const ok = await this.confirm.confirmDestructive('Your unsaved changes to this form will be lost.', {
                title: 'Switch form?',
                confirmText: 'Discard changes',
                cancelText: 'Keep editing',
            });
            if (!ok) {
                this.targetCtrl.setValue(this.caseType() ?? '', { emitEvent: false });
                this.askingNewType.set(false);
                return false;
            }
        }
        this.caseType.set(caseType);
        this.load();
        return true;
    }

    /** "every Case without its own form" / "every Case of type “sim-box”". */
    scopeText(): string {
        const t = this.caseType();
        if (!this.perCaseType) return `every ${this.data.typeLabel}`;
        return t ? `every ${this.data.typeLabel} of type “${t}”` : `every ${this.data.typeLabel} without its own form`;
    }

    get choiceControls(): FormArray<FormControl<string>> {
        return this.fieldForm.controls.choices;
    }

    // ── load ─────────────────────────────────────────────────────────────────────

    /** The effective spec (what the team sees) + the authored component (whether one exists, its hash). */
    load(): void {
        this.loading.set(true);
        this.loadError.set(null);
        forkJoin({
            effective: this.objects.findingsSpec(this.id),
            authored: this.components
                .get(KIND, this.id)
                // 404 = nothing authored yet (the built-in is in force); 503 = no write root on this deployment.
                .pipe(
                    catchError((e: HttpErrorResponse) =>
                        e.status === 404 || e.status === 503 ? of(null) : throwError(() => e),
                    ),
                ),
        }).subscribe({
            next: ({ effective, authored }) => {
                const fields = fromWire(effective);
                this.authored.set(authored);
                this.fields.set(fields);
                this.dirty.set(false);
                this.conflict.set(false);
                this.serverError.set(null);
                this.previewSeed.set({});
                this.loading.set(false);
                this.select(fields[0]?.uid ?? null);
            },
            error: (e) => {
                this.loading.set(false);
                this.loadError.set(apiErrorMessage(e, 'Could not load the Findings fields.'));
            },
        });
    }

    // ── selection + the field form ───────────────────────────────────────────────

    select(uid: string | null): void {
        // D6: the disclosure is per field — moving to another field collapses it again.
        if (uid !== this.selectedUid()) this.showTechnical.set(false);
        this.selectedUid.set(uid);
        const f = this.fields().find((x) => x.uid === uid);
        if (!f) return;
        this.syncing = true;
        this.choiceControls.clear({ emitEvent: false });
        for (const c of f.choices) this.choiceControls.push(this.fb.nonNullable.control(c.label), { emitEvent: false });
        this.fieldForm.reset(
            {
                label: f.label,
                help: f.help,
                placeholder: f.placeholder,
                type: f.type,
                tier: f.tier,
                required: f.required,
                pattern: f.pattern,
                min: f.min === null ? '' : String(f.min),
                max: f.max === null ? '' : String(f.max),
                showWhenOn: !!f.showWhen,
                showWhenTarget: f.showWhen?.targetUid ?? '',
                showWhenSense: f.showWhen?.negated ? 'isNot' : 'is',
                showWhenValue: f.showWhen?.value ?? '',
                choices: f.choices.map((c) => c.label),
            },
            { emitEvent: false },
        );
        if (this.canEdit()) this.fieldForm.enable({ emitEvent: false });
        else this.fieldForm.disable({ emitEvent: false });
        // The built-in Impact field: only its label may change (the server 422s anything else).
        if (this.isLocked(f)) {
            for (const [name, control] of Object.entries(this.fieldForm.controls))
                if (name !== 'label') control.disable({ emitEvent: false });
        }
        this.syncing = false;
    }

    /** Fold the field form back into the selected draft. */
    private applyForm(): void {
        const uid = this.selectedUid();
        if (!uid || this.syncing || !this.canEdit()) return;
        const v = this.fieldForm.getRawValue();
        const current = this.selected();
        const targetChanged = current?.showWhen?.targetUid !== v.showWhenTarget;
        if (v.showWhenOn && targetChanged && v.showWhenValue) {
            // A new target's values are not the old one's — never carry a value across.
            this.syncing = true;
            this.fieldForm.controls.showWhenValue.setValue('', { emitEvent: false });
            this.syncing = false;
            v.showWhenValue = '';
        }
        this.mutate((fields) =>
            fields.map((f) =>
                f.uid !== uid
                    ? f
                    : {
                          ...f,
                          label: v.label,
                          help: v.help,
                          placeholder: v.placeholder,
                          type: v.type,
                          tier: v.tier,
                          required: v.required,
                          pattern: v.pattern,
                          min: toNumber(v.min),
                          max: toNumber(v.max),
                          choices: f.choices.map((c, i) => ({ ...c, label: v.choices[i] ?? c.label })),
                          showWhen:
                              v.showWhenOn && v.showWhenTarget
                                  ? {
                                        targetUid: v.showWhenTarget,
                                        negated: v.showWhenSense === 'isNot',
                                        value: v.showWhenValue,
                                    }
                                  : null,
                      },
            ),
        );
    }

    /** Every draft edit goes through here: keep what the preview holds, mark dirty, clear a stale 422. */
    private mutate(update: (fields: FieldDraft[]) => FieldDraft[]): void {
        const live = this.preview()?.value();
        if (live) this.previewSeed.set(live);
        this.fields.set(update(this.fields()));
        this.dirty.set(true);
        this.serverError.set(null);
    }

    // ── the field list ───────────────────────────────────────────────────────────

    addField(): void {
        const f = newField('New field');
        this.mutate((fields) => [...fields, f]);
        this.select(f.uid);
    }

    move(uid: string, delta: -1 | 1): void {
        this.mutate((fields) => {
            const i = fields.findIndex((f) => f.uid === uid);
            const j = i + delta;
            if (i < 0 || j < 0 || j >= fields.length) return fields;
            const next = [...fields];
            [next[i], next[j]] = [next[j], next[i]];
            return next;
        });
    }

    /** Whether `f` is the Case's built-in Impact field — renameable, never removed or changed otherwise. */
    isLocked(f: FieldDraft): boolean {
        return isBuiltInImpact(f, this.data.objectType);
    }

    /** D7: a removed field's values stay stored on the Cases that recorded them, but stop showing. */
    async removeField(f: FieldDraft): Promise<void> {
        if (this.isLocked(f)) return;
        if (f.key) {
            const analytics = ANALYTICS_KEYS.includes(f.key)
                ? ' Case analytics will also stop receiving this figure from Cases saved from now on.'
                : '';
            const ok = await this.confirm.confirmDestructive(
                `Cases that already recorded “${f.label.trim() || 'this field'}” keep the value, but it will no ` +
                    `longer be shown.${analytics}`,
                { title: 'Remove this Findings field?', confirmText: 'Remove field', cancelText: 'Keep it' },
            );
            if (!ok) return;
        }
        const remaining = this.fields().filter((x) => x.uid !== f.uid);
        this.mutate(() => remaining);
        if (this.selectedUid() === f.uid) this.select(remaining[0]?.uid ?? null);
    }

    // ── choices ──────────────────────────────────────────────────────────────────

    // The choice rows are tracked by their control, so every edit below changes `choiceControls` IN PLACE.
    // Rebuilding it (as `select()` does) under a still-rendered row left that input bound to a removed control,
    // and what the lead typed there was silently lost (FINDINGS-EDITOR-CHOICE-BINDING-1).

    addChoice(): void {
        const uid = this.selectedUid();
        if (!uid) return;
        this.mutate((fields) =>
            fields.map((f) => (f.uid === uid ? { ...f, choices: [...f.choices, newChoice('')] } : f)),
        );
        this.choiceControls.push(this.fb.nonNullable.control(''), { emitEvent: false });
        // Put the cursor in the new box, not the field's Name (FINDINGS-EDITOR-ADD-CHOICE-FOCUS-1).
        afterNextRender(() => this.choiceInputs().at(-1)?.nativeElement.focus(), { injector: this.injector });
    }

    moveChoice(index: number, delta: -1 | 1): void {
        const uid = this.selectedUid();
        const j = index + delta;
        if (!uid || j < 0 || j >= this.choiceControls.length) return;
        this.mutate((fields) =>
            fields.map((f) => {
                if (f.uid !== uid) return f;
                const choices = [...f.choices];
                [choices[index], choices[j]] = [choices[j], choices[index]];
                return { ...f, choices };
            }),
        );
        const control = this.choiceControls.at(index);
        this.choiceControls.removeAt(index, { emitEvent: false });
        this.choiceControls.insert(j, control, { emitEvent: false });
    }

    async removeChoice(index: number): Promise<void> {
        const f = this.selected();
        if (!f) return;
        const c = f.choices[index];
        if (c?.value) {
            const ok = await this.confirm.confirmDestructive(
                `Cases that already recorded “${c.label.trim() || c.value}” keep the value, but it will no longer ` +
                    'be offered or shown as a choice.',
                { title: 'Remove this choice?', confirmText: 'Remove choice', cancelText: 'Keep it' },
            );
            if (!ok) return;
        }
        this.mutate((fields) =>
            fields.map((x) => (x.uid === f.uid ? { ...x, choices: x.choices.filter((_, i) => i !== index) } : x)),
        );
        this.choiceControls.removeAt(index, { emitEvent: false });
    }

    // ── pickers ──────────────────────────────────────────────────────────────────

    /** Plain types always; the technical ones only once "Technical details" is open, or when already chosen. */
    controlOptions(current: AttributeType): PickerOption[] {
        return CONTROL_CHOICES.filter((c) => !c.technical || this.showTechnical() || c.type === current).map((c) => ({
            value: c.type,
            label: c.label,
            hint: c.hint,
        }));
    }

    /** "Show only when" targets — fields ABOVE this one only (a legal forward reference is still refused). */
    targetOptions(uid: string): PickerOption[] {
        return fieldsAbove(this.fields(), uid).map((f) => ({ value: f.uid, label: f.label.trim() || 'Untitled' }));
    }

    readonly senseOptions: PickerOption[] = [
        { value: 'is', label: 'is' },
        { value: 'isNot', label: 'is not' },
    ];

    /** The target's own answers: its choices, or Yes/No. `null` = free text. */
    valueOptions(targetUid: string): PickerOption[] | null {
        const t = this.fields().find((f) => f.uid === targetUid);
        if (!t) return null;
        if (t.type === 'boolean') {
            return [
                { value: 'true', label: 'Yes' },
                { value: 'false', label: 'No' },
            ];
        }
        if (t.type === 'select') return t.choices.map((c) => ({ value: c.uid, label: c.label.trim() || 'Untitled' }));
        return null;
    }

    /** "Saved as …" / "Will be saved as …" — the key, read-only (Technical details). */
    keyNote(f: FieldDraft): string {
        return `${f.key ? 'Saved as' : 'Will be saved as'} ${keyOf(this.fields(), f.uid)}`;
    }

    choiceValues(f: FieldDraft): string[] {
        return choiceValuesOf(f);
    }

    resetPreview(): void {
        this.previewSeed.set({});
        this.preview()?.form.reset();
    }

    // ── save / restore / history ─────────────────────────────────────────────────

    save(): void {
        if (!this.canEdit() || this.problems().length || this.saving()) return;
        const body = toWire(this.data.objectType, this.fields(), this.caseType());
        const authored = this.authored();
        const write$ = authored
            ? this.components.update(KIND, this.id, body, { ifMatch: authored.contentHash })
            : this.components.create(KIND, body);
        this.saving.set(true);
        this.serverError.set(null);
        write$.subscribe({
            next: () => {
                this.saving.set(false);
                this.dirty.set(false);
                this.changed = true;
                this.toastr.success('Findings fields saved');
                this.ref.close(true);
            },
            error: (e: HttpErrorResponse) => {
                this.saving.set(false);
                if (e.status === 409) this.conflict.set(true);
                else if (e.status === 422) this.serverError.set(apiErrorMessage(e, 'The server refused the save.'));
                else this.toastr.error(apiErrorMessage(e, 'Save failed'));
            },
        });
    }

    /** Back to the four built-in fields: `DELETE` the authored component, the server then serves the default. */
    async restoreBuiltIn(): Promise<void> {
        if (!this.canEdit() || !this.authored()) return;
        if (this.caseType()) return this.removeTypeForm();
        const ok = await this.confirm.confirmDestructive(
            'Your team will see the built-in Findings fields again (Disposition, Records affected, ' +
                'Summary). Values already recorded in your own fields stay stored on each Case but stop showing.',
            { title: 'Restore the built-in Findings fields?', confirmText: 'Restore built-in', cancelText: 'Cancel' },
        );
        if (!ok) return;
        this.components.remove(KIND, this.id).subscribe({
            next: () => {
                this.changed = true;
                this.dirty.set(false);
                this.toastr.success('Built-in Findings fields restored');
                this.ref.close(true);
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Restore failed')),
        });
    }

    /** A Case type's own form is deleted: its Cases go back to the shared form. */
    private async removeTypeForm(): Promise<void> {
        const t = this.caseType()!;
        const ok = await this.confirm.confirmDestructive(
            `Cases of type “${t}” will use the shared Findings form again. Values already recorded in ` +
                'this form’s own fields stay stored on each Case but stop showing.',
            { title: `Remove the “${t}” form?`, confirmText: 'Use the shared form', cancelText: 'Cancel' },
        );
        if (!ok) return;
        this.components.remove(KIND, this.id).subscribe({
            next: () => {
                this.changed = true;
                this.dirty.set(false);
                this.toastr.success(`Cases of type “${t}” now use the shared form`);
                this.ref.close(true);
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Remove failed')),
        });
    }

    openHistory(): void {
        this.dialog
            .open(ComponentHistoryDialog, {
                width: '560px',
                data: {
                    type: KIND,
                    id: this.id,
                    label: `Findings fields — ${this.data.typeLabel}${this.caseType() ? ' · ' + this.caseType() : ''}`,
                },
            })
            .afterClosed()
            .subscribe((restored?: boolean) => {
                if (!restored) return;
                this.changed = true;
                this.load();
            });
    }
}

function toNumber(v: string | number | null): number | null {
    if (v === null || String(v).trim() === '') return null;
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
}
