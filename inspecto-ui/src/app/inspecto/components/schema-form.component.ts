import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, Input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AbstractControl, FormBuilder, FormGroup, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import {
    AttributeOption,
    AttributeSpec,
    AttributeTier,
    AttributeToken,
    byTier,
    defaultsFor,
    dependsOnMatches,
    isRequired,
    listPatternViolation,
} from '../component-model';
import { ChipComponent } from './chip.component';
import { InspectoOptionPickerComponent } from './option-picker.component';
import { InspectoTokenPickerComponent } from './token-picker.component';

/**
 * Supplies the suggestion list for a `type: 'autocomplete'` attribute. Receives the current raw form
 * value so a suggestion set can follow a sibling field (e.g. `target` follows `targetType`). Called on
 * focus — return the fresh list (sync or async); failures degrade to no suggestions.
 */
export type AttributeOptionLoader = (value: Record<string, unknown>) => AttributeOption[] | Promise<AttributeOption[]>;

/**
 * The shared spec-driven form renderer (Wave 0, W2): renders an {@link AttributeSpec} list as a
 * reactive form with the product's three-tier disclosure — **required** always visible, **optional**
 * in a collapsed group, **advanced** behind the gear toggle. `dependsOn` attributes show (and
 * validate) only while their controlling attribute matches. Hosts embed it, patch `initial`, and on
 * submit call `validate()` + read `value()` — bespoke sections (key/value arrays, canvases) stay in
 * the host below it.
 */
@Component({
    selector: 'inspecto-schema-form',
    standalone: true,
    imports: [
        NgTemplateOutlet,
        ReactiveFormsModule,
        ChipComponent,
        InspectoOptionPickerComponent,
        InspectoTokenPickerComponent,
        MatAutocompleteModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSlideToggleModule,
        MatTooltipModule,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <form [formGroup]="form" class="flex flex-col gap-1" (ngSubmit)="submitted.emit()">
            <!-- Invisible submit target so Enter in any field triggers ngSubmit (implicit submission
                 requires a rendered submit control — display:none is skipped by Chrome, so sr-only,
                 not "hidden"; hosts keep their visible Save button outside). -->
            <button type="submit" class="sr-only" aria-hidden="true" tabindex="-1"></button>
            @if (tiers().advanced.length) {
                <div class="flex justify-end">
                    <button
                        mat-icon-button
                        type="button"
                        matTooltip="Advanced settings"
                        [attr.aria-label]="showAdvanced() ? 'Hide advanced settings' : 'Show advanced settings'"
                        [attr.aria-expanded]="showAdvanced()"
                        (click)="showAdvanced.set(!showAdvanced())"
                    >
                        <mat-icon svgIcon="heroicons_outline:cog-6-tooth"></mat-icon>
                    </button>
                </div>
            }

            @for (g of groupsOf(tiers().required); track g.name; let gi = $index) {
                @if (g.name) {
                    <div class="text-secondary pb-1 pt-2 text-sm font-medium" role="heading" aria-level="3">
                        {{ g.name }}
                    </div>
                }
                @for (spec of g.specs; track spec.key; let i = $index) {
                    <ng-container
                        *ngTemplateOutlet="field; context: { spec, first: gi === 0 && i === 0 }"
                    ></ng-container>
                }
            }

            @if (tiers().optional.length) {
                <button
                    type="button"
                    class="text-secondary flex items-center gap-1 self-start py-1 text-sm font-medium"
                    [attr.aria-expanded]="showOptional()"
                    (click)="showOptional.set(!showOptional())"
                >
                    <mat-icon
                        class="icon-size-4"
                        [svgIcon]="
                            showOptional() ? 'heroicons_outline:chevron-down' : 'heroicons_outline:chevron-right'
                        "
                    ></mat-icon>
                    Optional settings ({{ tiers().optional.length }})
                </button>
                @if (showOptional()) {
                    @for (g of groupsOf(tiers().optional); track g.name) {
                        @if (g.name) {
                            <div class="text-secondary pb-1 pt-2 text-sm font-medium" role="heading" aria-level="3">
                                {{ g.name }}
                            </div>
                        }
                        @for (spec of g.specs; track spec.key) {
                            <ng-container *ngTemplateOutlet="field; context: { spec }"></ng-container>
                        }
                    }
                }
            }

            @if (showAdvanced()) {
                <div class="text-secondary py-1 text-sm font-medium" role="heading" aria-level="3">Advanced</div>
                @for (g of groupsOf(tiers().advanced); track g.name) {
                    @if (g.name) {
                        <div class="text-secondary pb-1 pt-2 text-sm font-medium" role="heading" aria-level="4">
                            {{ g.name }}
                        </div>
                    }
                    @for (spec of g.specs; track spec.key) {
                        <ng-container *ngTemplateOutlet="field; context: { spec }"></ng-container>
                    }
                }
            }

            <ng-template #field let-spec="spec" let-first="first">
                @if (isVisible(spec)) {
                    @switch (spec.type) {
                        @case ('boolean') {
                            <mat-slide-toggle
                                class="py-2"
                                [formControlName]="spec.key"
                                [attr.cdkFocusInitial]="first ? '' : null"
                                >{{ spec.label }}</mat-slide-toggle
                            >
                        }
                        <!--
                            A single choice is asked in a POPUP, not a dropdown (operator ask
                            2026-08-22): the shared picker gives every option a full-length label and
                            a filter box once the list is long, which a dropdown overlay cannot. It is
                            a ControlValueAccessor, so formControlName binds exactly as mat-select
                            did — and it renders its own label and error, which is why there is no
                            mat-form-field around it (see the component's own note).
                            (no backticks in this comment: it lives inside a template literal)
                        -->
                        @case ('select') {
                            <inspecto-option-picker
                                class="block w-full py-1"
                                [formControlName]="spec.key"
                                [label]="spec.label"
                                [options]="spec.options ?? []"
                                [help]="spec.help ?? ''"
                                [required]="!!spec.required"
                                [attr.cdkFocusInitial]="first ? '' : null"
                            />
                        }
                        @case ('autocomplete') {
                            <mat-form-field class="w-full" subscriptSizing="dynamic">
                                <mat-label>{{ spec.label }}</mat-label>
                                <input
                                    matInput
                                    [formControlName]="spec.key"
                                    [placeholder]="spec.placeholder ?? ''"
                                    [matAutocomplete]="ac"
                                    [attr.cdkFocusInitial]="first ? '' : null"
                                    (focus)="loadOptionsFor(spec)"
                                />
                                <mat-autocomplete #ac="matAutocomplete">
                                    @for (opt of filteredOptions(spec); track opt.value) {
                                        <mat-option [value]="opt.value">{{ opt.label }}</mat-option>
                                    }
                                </mat-autocomplete>
                                <inspecto-token-picker
                                    matSuffix
                                    [fieldLabel]="spec.label"
                                    [tokens]="tokensFor(spec)"
                                    (picked)="applyToken(spec, $event)"
                                />
                                @if (spec.help) {
                                    <mat-hint>{{ spec.help }}</mat-hint>
                                }
                                <mat-error>{{ errorFor(spec) }}</mat-error>
                            </mat-form-field>
                        }
                        @case ('multiline') {
                            <mat-form-field class="w-full" subscriptSizing="dynamic">
                                <mat-label>{{ spec.label }}</mat-label>
                                <textarea
                                    matInput
                                    rows="4"
                                    [formControlName]="spec.key"
                                    [placeholder]="spec.placeholder ?? ''"
                                    [attr.cdkFocusInitial]="first ? '' : null"
                                ></textarea>
                                <inspecto-token-picker
                                    matSuffix
                                    [fieldLabel]="spec.label"
                                    [tokens]="tokensFor(spec)"
                                    (picked)="applyToken(spec, $event)"
                                />
                                @if (spec.help) {
                                    <mat-hint>{{ spec.help }}</mat-hint>
                                }
                                <mat-error>{{ errorFor(spec) }}</mat-error>
                            </mat-form-field>
                        }
                        @case ('list') {
                            <!-- string[] as removable chips. The text box is a DRAFT, not the control:
                                 the control's value is the array, committed on Enter / + / blur. -->
                            <div class="flex flex-col gap-1">
                                <mat-form-field class="w-full" subscriptSizing="dynamic">
                                    <mat-label>{{ spec.label }}</mat-label>
                                    <input
                                        matInput
                                        type="text"
                                        [value]="listDraft(spec.key)"
                                        [placeholder]="spec.placeholder ?? ''"
                                        [attr.cdkFocusInitial]="first ? '' : null"
                                        (input)="setListDraft(spec.key, $any($event.target).value)"
                                        (keydown.enter)="addListItem(spec, $event)"
                                        (blur)="addListItem(spec)"
                                    />
                                    <button
                                        mat-icon-button
                                        matSuffix
                                        type="button"
                                        [attr.aria-label]="'Add entry to ' + spec.label"
                                        [disabled]="!listDraft(spec.key).trim()"
                                        (click)="addListItem(spec)"
                                    >
                                        <mat-icon svgIcon="heroicons_outline:plus" />
                                    </button>
                                    <inspecto-token-picker
                                        matSuffix
                                        [fieldLabel]="spec.label"
                                        [tokens]="tokensFor(spec)"
                                        (picked)="applyToken(spec, $event)"
                                    />
                                    @if (spec.help) {
                                        <mat-hint>{{ spec.help }}</mat-hint>
                                    }
                                    <!-- No <mat-error> here: this field's <input> is a DRAFT, never bound
                                         to the control, so the form-field has no NgControl and could
                                         never enter an error state. See listError(). -->
                                </mat-form-field>
                                @if (listError(spec)) {
                                    <p class="text-warn text-xs" role="alert">{{ listError(spec) }}</p>
                                }
                                @if (listValue(spec.key).length) {
                                    <div class="flex flex-wrap gap-1 pb-1">
                                        @for (item of listValue(spec.key); track $index) {
                                            <inspecto-chip
                                                variant="soft"
                                                [removable]="true"
                                                [removeLabel]="'Remove ' + item + ' from ' + spec.label"
                                                (removed)="removeListItem(spec, $index)"
                                            >
                                                <span class="font-mono">{{ item }}</span>
                                            </inspecto-chip>
                                        }
                                    </div>
                                }
                            </div>
                        }
                        @case ('number') {
                            <!-- Static type="number" so Angular's NumberValueAccessor attaches (a [type]
                                 binding would leave the default accessor → string values). -->
                            <mat-form-field class="w-full" subscriptSizing="dynamic">
                                <mat-label>{{ spec.label }}</mat-label>
                                <input
                                    matInput
                                    type="number"
                                    [formControlName]="spec.key"
                                    [placeholder]="spec.placeholder ?? ''"
                                    [attr.cdkFocusInitial]="first ? '' : null"
                                />
                                @if (spec.help) {
                                    <mat-hint>{{ spec.help }}</mat-hint>
                                }
                                <mat-error>{{ errorFor(spec) }}</mat-error>
                            </mat-form-field>
                        }
                        @default {
                            <!-- string / identifier. A secret spec masks the input; unlike the number
                                 case above, a bound [type] is safe here because text and password
                                 share Angular's DefaultValueAccessor, so no accessor is lost. -->
                            <mat-form-field class="w-full" subscriptSizing="dynamic">
                                <mat-label>{{ spec.label }}</mat-label>
                                <input
                                    matInput
                                    [type]="spec.secret ? 'password' : 'text'"
                                    [attr.autocomplete]="spec.secret ? 'new-password' : null"
                                    [formControlName]="spec.key"
                                    [placeholder]="spec.placeholder ?? ''"
                                    [attr.cdkFocusInitial]="first ? '' : null"
                                />
                                <inspecto-token-picker
                                    matSuffix
                                    [fieldLabel]="spec.label"
                                    [tokens]="tokensFor(spec)"
                                    (picked)="applyToken(spec, $event)"
                                />
                                @if (spec.help) {
                                    <mat-hint>{{ spec.help }}</mat-hint>
                                }
                                <mat-error>{{ errorFor(spec) }}</mat-error>
                            </mat-form-field>
                        }
                    }
                }
            </ng-template>
        </form>
    `,
})
export class InspectoSchemaFormComponent {
    private fb = inject(FormBuilder);
    private destroyRef = inject(DestroyRef);

    readonly form: FormGroup = this.fb.group({});
    /** Fires on Enter in any field (native form submission). Hosts bind their save action here so
     *  keyboard submit and the visible Save button share one path. */
    readonly submitted = output<void>();
    readonly showOptional = signal(false);
    readonly showAdvanced = signal(false);
    readonly tiers = signal<ReturnType<typeof byTier>>({ required: [], optional: [], advanced: [] });

    private allSpecs: AttributeSpec[] = [];
    private formValue = signal<Record<string, unknown>>({});

    /** The attribute declarations to render. Set once (rebuilds the form when reassigned). */
    @Input({ required: true }) set specs(specs: AttributeSpec[]) {
        this.allSpecs = specs ?? [];
        this.tiers.set(byTier(this.allSpecs));
        for (const key of Object.keys(this.form.controls)) this.form.removeControl(key, { emitEvent: false });
        const defaults = defaultsFor(this.allSpecs);
        for (const s of this.allSpecs) {
            this.form.addControl(s.key, this.fb.control(defaults[s.key] ?? null, this.validatorsFor(s)), {
                emitEvent: false,
            });
        }
        this.applyExtraValidators();
        // …and re-apply the host's seeded VALUES, for the same reason the validators above are
        // re-applied: this setter rebuilds every control from defaults, and Angular does not re-run
        // the `initial` setter unless that binding's reference also changed. A host whose spec set
        // resolves ASYNCHRONOUSLY (the Grammar editor's served-parser form, which swaps specs when
        // `GET /parsers` lands) therefore seeded before the swap and would silently show defaults —
        // then write them back on save. Keys absent from the new spec set are ignored by patchValue,
        // so a deliberate format switch still lands on the new format's defaults.
        if (this.lastInitial) this.form.patchValue(this.lastInitial, { emitEvent: false });
        this.syncVisibility(this.form.getRawValue());
    }

    /**
     * Host-supplied validators per attribute key, for a domain rule a declarative spec cannot express
     * (reference: the Pipelines `measuresValidator` — the engine's `count | agg(field)` grammar). A
     * validator returning `{ message: '…' }` has that message rendered verbatim by {@link errorFor},
     * because the generic error keys cannot phrase such a rule.
     *
     * <p>Re-applied whenever `specs` is reassigned — that setter rebuilds every control from scratch, so
     * validators attached earlier would otherwise be silently dropped by a spec swap (the same trap the
     * collector/grammar editors hit with live *values*).
     */
    @Input() set extraValidators(validators: Record<string, ValidatorFn[]> | undefined) {
        this.hostValidators = validators ?? {};
        this.applyExtraValidators();
    }

    private hostValidators: Record<string, ValidatorFn[]> = {};

    private applyExtraValidators(): void {
        for (const [key, fns] of Object.entries(this.hostValidators)) {
            const control = this.form.get(key);
            if (!control) continue; // the key isn't in this spec set — a host may cover several
            control.addValidators(fns);
            control.updateValueAndValidity({ emitEvent: false });
        }
    }

    /** Existing values to edit (patched over the declared defaults). */
    @Input() set initial(value: Record<string, unknown> | null | undefined) {
        this.lastInitial = value ?? null;
        if (value) this.form.patchValue(value, { emitEvent: false });
        this.syncVisibility(this.form.getRawValue());
    }

    /** The last seed, replayed after a {@link specs} rebuild — see the note in that setter. */
    private lastInitial: Record<string, unknown> | null = null;

    /** Suggestion sources for `type: 'autocomplete'` attributes, keyed by attribute key. */
    @Input() optionLoaders: Record<string, AttributeOptionLoader> | undefined;

    /**
     * Whole-value tokens offered per attribute key (same idiom as {@link optionLoaders}). A key with no
     * entry — or an empty list — renders no picker at all, which is how a host expresses "this field takes
     * a literal only".
     *
     * <p>The host does all the filtering: only it knows which tokens suit which field. Jobs filters on
     * three things (§8.5) — the parameter's type against the token's `yields`, the Job's trigger kind
     * against `availableIn`, and the declaration's `expressions` flag.
     */
    @Input() tokens: Record<string, AttributeToken[]> | undefined;

    /**
     * Recognises a value that is a whole-value token rather than a literal, so the field's **format**
     * contract (`pattern`) does not apply to it — the platform that owns the vocabulary validates it
     * instead, and re-validates the resolved value against the same contract at run time.
     *
     * <p><b>Without this the picker is decorative.</b> Every typed field it is most useful on carries a
     * preset `pattern` (date, instant, email), so the moment a token lands in one the form marks it
     * invalid and Save refuses the very value the picker just authored.
     *
     * <p>⚠ Must not carry the `g` flag — `RegExp.test` is stateful with it and would alternate pass/fail
     * across calls. Unset (the default) ⇒ no exemption, so every existing adopter is unaffected.
     */
    @Input() tokenSyntax: RegExp | undefined;

    /** The tokens offered for a field. */
    tokensFor(spec: AttributeSpec): AttributeToken[] {
        return this.tokens?.[spec.key] ?? [];
    }

    /**
     * Substitute a token for the field's **entire** value.
     *
     * <p>⚠ Never an insertion at the cursor. A platform that evaluates only a value which *is* a token
     * leaves `report for $today` untouched, so a cursor insert would author a value that silently never
     * resolves — the failure looks like a bad token rather than a bad position.
     *
     * <p>On a `list` field the same rule means the token replaces **every** entry: a list is one value
     * (its items joined), so a token sitting beside other entries is part of a longer value too.
     */
    applyToken(spec: AttributeSpec, token: AttributeToken): void {
        const control = this.form.get(spec.key);
        if (!control) return;
        if (spec.type === 'list') {
            this.setListDraft(spec.key, '');
            this.writeList(spec.key, [token.token]);
            return;
        }
        control.setValue(token.token);
        control.markAsDirty();
        control.markAsTouched();
    }

    /** Whether a value is a whole-value token substitution rather than a literal — see {@link tokenSyntax}. */
    private isTokenValue(value: unknown): boolean {
        return !!this.tokenSyntax && typeof value === 'string' && this.tokenSyntax.test(value);
    }

    /** Loaded suggestions per attribute key (refreshed on field focus). */
    private readonly loadedOptions = signal<Record<string, AttributeOption[]>>({});

    /** Refresh an autocomplete field's suggestions — called on focus so sets that depend on sibling
     *  fields (e.g. `target` on `targetType`) stay current. Best-effort: a failed load = no list. */
    loadOptionsFor(spec: AttributeSpec): void {
        const loader = this.optionLoaders?.[spec.key];
        if (!loader) return;
        Promise.resolve(loader(this.form.getRawValue())).then(
            (opts) => this.loadedOptions.update((m) => ({ ...m, [spec.key]: opts })),
            () => undefined,
        );
    }

    /** In-progress text per `type: 'list'` field — the control itself holds the committed array. */
    private readonly listDrafts = signal<Record<string, string>>({});

    listDraft(key: string): string {
        return this.listDrafts()[key] ?? '';
    }

    setListDraft(key: string, text: string): void {
        this.listDrafts.update((m) => ({ ...m, [key]: text }));
    }

    /** The committed entries of a `list` control (null/non-array ⇒ empty). */
    listValue(key: string): string[] {
        const v = this.form.get(key)?.value;
        return Array.isArray(v) ? (v as string[]) : [];
    }

    /**
     * Commit the draft as a new entry. Called from Enter, the + button and blur — blur included so a
     * typed-but-uncommitted value isn't silently lost when the user goes straight for Save.
     * Duplicates and blanks are ignored; `event.preventDefault()` stops Enter from submitting the form
     * (the sr-only submit button above would otherwise fire).
     */
    addListItem(spec: AttributeSpec, event?: Event): void {
        event?.preventDefault();
        const text = this.listDraft(spec.key).trim();
        if (!text) return;
        const current = this.listValue(spec.key);
        if (!current.includes(text)) this.writeList(spec.key, [...current, text]);
        this.setListDraft(spec.key, '');
    }

    removeListItem(spec: AttributeSpec, index: number): void {
        const next = this.listValue(spec.key).filter((_, i) => i !== index);
        this.writeList(spec.key, next);
    }

    /** Empty ⇒ null, so a cleared list reads as blank to `required` and to the host's delete-on-clear. */
    private writeList(key: string, items: string[]): void {
        const control = this.form.get(key);
        if (!control) return;
        control.setValue(items.length ? items : null);
        control.markAsDirty();
        control.markAsTouched();
    }

    /** Suggestions narrowed by the field's current text (matches value or label, case-insensitive). */
    filteredOptions(spec: AttributeSpec): AttributeOption[] {
        const all = this.loadedOptions()[spec.key] ?? spec.options ?? [];
        const q = String(this.formValue()[spec.key] ?? '')
            .trim()
            .toLowerCase();
        if (!q) return all;
        return all.filter((o) => o.value.toLowerCase().includes(q) || o.label.toLowerCase().includes(q));
    }

    constructor() {
        this.form.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
            this.syncVisibility(this.form.getRawValue());
        });
    }

    /**
     * Specs bucketed into `group` sections within one tier, in first-appearance order; ungrouped specs
     * fall into a single nameless section so an un-grouped spec set renders exactly as it did before.
     *
     * <p>Specs sharing a name coalesce even when declared apart — one heading per name is what an author
     * means by naming a section twice, and it also keeps the `@for` track key unique, which repeating a
     * heading would not.
     */
    groupsOf(specs: AttributeSpec[]): { name: string; specs: AttributeSpec[] }[] {
        const byName = new Map<string, AttributeSpec[]>();
        for (const s of specs) {
            const name = s.group ?? '';
            const bucket = byName.get(name);
            if (bucket) bucket.push(s);
            else byName.set(name, [s]);
        }
        return [...byName].map(([name, grouped]) => ({ name, specs: grouped }));
    }

    /** True while `spec` should render (its `dependsOn` matches the current values). */
    isVisible(spec: AttributeSpec): boolean {
        return !spec.dependsOn || dependsOnMatches(spec.dependsOn, this.formValue());
    }

    /** Whether the user changed anything — drives the shared discard-on-close guard. */
    isDirty(): boolean {
        return this.form.dirty;
    }

    /**
     * Mark everything touched (house rule on invalid submit) and report validity.
     *
     * <p>⚠ Also OPENS whichever collapsed section holds an invalid control. Marking a control touched
     * renders its error only where the control itself renders, so an invalid `optional`/`advanced`
     * field left behind its collapsed disclosure blocked the save with **no visible reason anywhere** —
     * the submit button simply did nothing. That is worse than a wrong value, because there is nothing
     * on screen to correct. Applies to every validator, not just the JSON one that surfaced it.
     */
    validate(): boolean {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            if (this.hasInvalidIn('optional')) this.showOptional.set(true);
            if (this.hasInvalidIn('advanced')) this.showAdvanced.set(true);
        }
        return this.form.valid;
    }

    /** Whether any control in `tier`'s (rendered) specs is currently invalid. */
    private hasInvalidIn(tier: AttributeTier): boolean {
        return this.tiers()[tier].some((s) => this.isVisible(s) && !!this.form.get(s.key)?.invalid);
    }

    /** The visible values only — hidden (`dependsOn`-suppressed) controls are disabled and excluded. */
    value(): Record<string, unknown> {
        return this.form.value as Record<string, unknown>;
    }

    /**
     * The error text for a `type: 'list'` field, rendered as an explicit line instead of a `<mat-error>`.
     *
     * <p><b>Why this is needed at all.</b> A list field's `<input>` holds the uncommitted DRAFT — the
     * control's value is the chip array — so it is deliberately not bound with `formControlName`. That
     * leaves `<mat-form-field>` with no `NgControl`, so it never learns the control is invalid and its
     * `<mat-error>` can never display. Every list error was invisible, `required` included; the validator
     * ran, the message existed, and nothing reached the screen. Caught in the preview — a unit test
     * asserting `errorFor()` returns the string passes either way.
     *
     * <p>Gated on `touched` to match Material's own display rule, so an untouched required list is not
     * red on open.
     */
    listError(spec: AttributeSpec): string {
        const c = this.form.get(spec.key);
        return c && c.invalid && c.touched ? this.errorFor(spec) : '';
    }

    /** The first matching error message for a control, from its spec. */
    errorFor(spec: AttributeSpec): string {
        const c = this.form.get(spec.key);
        if (!c || !c.errors) return '';
        if (c.errors['required']) return `${spec.label} is required`;
        // A host validator (see `extraValidators`) phrases its own message — a domain rule like the
        // measure grammar has to name what to write, which no generic key below can do.
        if (typeof c.errors['message'] === 'string') return c.errors['message'] as string;
        if (c.errors['duplicate']) return `${spec.label} already exists`;
        if (c.errors['pattern']) return `${spec.label} has an invalid format`;
        if (c.errors['min']) return `${spec.label} must be ≥ ${spec.min}`;
        if (c.errors['max']) return `${spec.label} must be ≤ ${spec.max}`;
        return `${spec.label} is invalid`;
    }

    private validatorsFor(s: AttributeSpec): ValidatorFn[] {
        const v: ValidatorFn[] = [];
        if (isRequired(s)) v.push(Validators.required);
        if (s.type === 'identifier') v.push(Validators.pattern(/^[A-Za-z][A-Za-z0-9_-]*$/));
        // A `list` holds the array, so `pattern` means "every item" — Validators.pattern would test the
        // array's toString() instead. Its message is phrased by listPatternViolation and named the
        // offending entry, so it rides the `message` key errorFor() renders verbatim.
        if (s.pattern && s.type === 'list') {
            v.push((control: AbstractControl) => {
                const items = control.value;
                if (!Array.isArray(items)) return null;
                const strings = items.filter((e): e is string => typeof e === 'string');
                // A lone token IS the field's whole value, so the format contract does not apply to it
                // (see `tokenSyntax`). Two or more entries are a literal even when one of them looks like
                // a token — the platform evaluates a value only when it is a token in its ENTIRETY — so
                // the format does apply, and flagging it here is the point rather than a false positive.
                if (strings.length === 1 && this.isTokenValue(strings[0])) return null;
                const message = listPatternViolation(s, strings);
                return message ? { message } : null;
            });
        } else if (s.pattern) {
            const literal = Validators.pattern(`^(?:${s.pattern})$`);
            v.push((control: AbstractControl) => (this.isTokenValue(control.value) ? null : literal(control)));
        }
        if (s.type === 'number') {
            if (s.min !== undefined) v.push(Validators.min(s.min));
            if (s.max !== undefined) v.push(Validators.max(s.max));
        }
        return v;
    }

    /** Enable/disable controls to match `dependsOn` visibility so hidden fields never block validity. */
    private syncVisibility(value: Record<string, unknown>): void {
        this.formValue.set(value);
        for (const s of this.allSpecs) {
            if (!s.dependsOn) continue;
            const visible = dependsOnMatches(s.dependsOn, value);
            const control: AbstractControl | null = this.form.get(s.key);
            if (!control) continue;
            if (visible && control.disabled) control.enable({ emitEvent: false });
            if (!visible && control.enabled) control.disable({ emitEvent: false });
        }
    }
}
