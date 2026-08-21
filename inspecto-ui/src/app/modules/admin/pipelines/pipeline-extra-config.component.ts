import { ChangeDetectionStrategy, Component, effect, inject, input, output, signal } from '@angular/core';
import { AbstractControl, FormBuilder, FormControl, ReactiveFormsModule, ValidationErrors } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';

/** What an extra entry's stored value IS — decides the control drawn and the validation applied. */
export type ExtraValueKind = 'text' | 'number' | 'boolean' | 'json';

/** One unmodelled config entry under edit. `original` backs the untouched-verbatim round-trip. */
interface ExtraEntry {
    key: string;
    kind: ExtraValueKind;
    control: FormControl<string>;
    original: unknown;
    /** True for a row the operator ADDED here — it has no original to restore. */
    added: boolean;
}

/**
 * The **additional-config editor** (2026-08-21 operator ask) — the typed replacement for the generic
 * Key/Value text grid. Keys outside the node type's schema render as their ACTUAL key with a control
 * matching the stored value's TYPE, each with proper validation:
 *
 * - `boolean` → a true/false select;
 * - `number` → a numeric input that refuses a non-number;
 * - object/array (`json`) → a textarea that refuses unparseable JSON — this is how
 *   `transform.route`'s unmodelled `branches` stays EDITABLE without ever silently becoming a string;
 * - `string` → a plain text input.
 *
 * <p>⚠ The untouched-verbatim rule lives HERE now: a pristine entry's {@link value} is the ORIGINAL
 * value reference, never a re-parse of its display string — so an unmodelled block survives an apply
 * byte-identical (the route-branches data-loss rule, re-homed from `node-config-build`).
 *
 * <p>Keys are NOT editable — the key is the label. New keys can only be added where the free-form
 * editor is the node's PRIMARY surface (`allowAdd`, i.e. a type with no schema at all); a
 * schema-backed type's vocabulary is the schema, and inventing keys beside it is what the generic
 * grid allowed and the operator banned.
 */
@Component({
    selector: 'app-pipeline-extra-config',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        MatTooltipModule,
    ],
    template: `
        <div class="space-y-2">
            @for (e of rows(); track e.key) {
                <div class="flex items-start gap-1">
                    @switch (e.kind) {
                        @case ('boolean') {
                            <mat-form-field subscriptSizing="dynamic" class="flex-1">
                                <mat-label>{{ e.key }}</mat-label>
                                <mat-select [formControl]="e.control">
                                    <mat-option value="true">true</mat-option>
                                    <mat-option value="false">false</mat-option>
                                </mat-select>
                            </mat-form-field>
                        }
                        @case ('number') {
                            <mat-form-field subscriptSizing="dynamic" class="flex-1">
                                <mat-label>{{ e.key }}</mat-label>
                                <input matInput type="number" [formControl]="e.control" />
                                @if (e.control.hasError('number')) {
                                    <mat-error>Must be a number</mat-error>
                                }
                            </mat-form-field>
                        }
                        @case ('json') {
                            <mat-form-field subscriptSizing="dynamic" class="flex-1">
                                <mat-label>{{ e.key }} (JSON)</mat-label>
                                <textarea
                                    matInput
                                    rows="3"
                                    class="font-mono !text-xs"
                                    [formControl]="e.control"
                                ></textarea>
                                @if (e.control.hasError('json')) {
                                    <mat-error
                                        >Not valid JSON — the value is kept as authored until it parses</mat-error
                                    >
                                }
                            </mat-form-field>
                        }
                        @default {
                            <mat-form-field subscriptSizing="dynamic" class="flex-1">
                                <mat-label>{{ e.key }}</mat-label>
                                <input matInput [formControl]="e.control" />
                            </mat-form-field>
                        }
                    }
                    <button
                        mat-icon-button
                        type="button"
                        class="mt-1 shrink-0"
                        (click)="remove(e.key)"
                        [attr.aria-label]="'Remove ' + e.key"
                        [matTooltip]="'Remove ' + e.key"
                    >
                        <mat-icon svgIcon="heroicons_outline:x-mark"></mat-icon>
                    </button>
                </div>
            } @empty {
                <p class="text-sm opacity-60">
                    {{ allowAdd() ? 'No config yet — add an entry below.' : 'No keys outside the schema.' }}
                </p>
            }

            @if (allowAdd()) {
                <!-- Adding is offered ONLY where this editor is the node's primary surface (no schema
                     at all) — a schema-backed type's vocabulary is the schema. -->
                <div class="flex items-start gap-1">
                    <mat-form-field subscriptSizing="dynamic" class="flex-1">
                        <mat-label>Key</mat-label>
                        <input matInput [formControl]="draftKey" />
                        @if (draftKey.hasError('duplicate')) {
                            <mat-error>That key already exists above</mat-error>
                        }
                    </mat-form-field>
                    <mat-form-field subscriptSizing="dynamic" class="w-28 shrink-0">
                        <mat-label>Type</mat-label>
                        <mat-select [formControl]="draftKind">
                            <mat-option value="text">Text</mat-option>
                            <mat-option value="number">Number</mat-option>
                            <mat-option value="boolean">True/false</mat-option>
                            <mat-option value="json">JSON</mat-option>
                        </mat-select>
                    </mat-form-field>
                    <button
                        mat-icon-button
                        type="button"
                        class="mt-1 shrink-0"
                        [disabled]="!draftKey.value.trim() || draftKey.invalid"
                        (click)="add()"
                        aria-label="Add config entry"
                        matTooltip="Add config entry"
                    >
                        <mat-icon svgIcon="heroicons_outline:plus"></mat-icon>
                    </button>
                </div>
            }
        </div>
    `,
})
export class PipelineExtraConfigComponent {
    private fb = inject(FormBuilder);

    /** The unmodelled entries, with their ORIGINAL typed values (`splitNodeConfig().extraRows`). */
    readonly entries = input.required<{ key: string; value: unknown }[]>();
    /** Offer the add affordance — only when this editor is the node's PRIMARY surface (no schema). */
    readonly allowAdd = input(false);
    /** Any user interaction that changes the eventual value — the host re-derives its dirty flag. */
    readonly changed = output<void>();

    readonly rows = signal<ExtraEntry[]>([]);
    readonly draftKey = this.fb.nonNullable.control('');
    readonly draftKind = this.fb.nonNullable.control<ExtraValueKind>('text');

    constructor() {
        // Seed from the input. The host recreates this component per node (and on Discard), and an
        // input swap without recreation re-seeds too — same contract as every definition pane.
        effect(() => {
            this.rows.set(this.entries().map((e) => this.entry(e.key, e.value)));
            this.draftKey.reset('');
        });
        this.draftKey.addValidators((c) =>
            this.rows().some((r) => r.key === String(c.value ?? '').trim()) ? { duplicate: true } : null,
        );
    }

    private entry(key: string, value: unknown, added = false): ExtraEntry {
        const kind = kindOf(value);
        const control = this.fb.nonNullable.control(display(kind, value));
        if (kind === 'number') control.addValidators(numberValidator);
        if (kind === 'json') control.addValidators(jsonValidator);
        return { key, kind, control, original: value, added };
    }

    add(): void {
        const key = this.draftKey.value.trim();
        if (!key || this.draftKey.invalid) return;
        const kind = this.draftKind.value;
        const seed: unknown = kind === 'number' ? 0 : kind === 'boolean' ? false : kind === 'json' ? {} : '';
        const row = this.entry(key, seed, true);
        row.control.markAsDirty(); // an added entry is authored work, never "untouched"
        this.rows.update((r) => [...r, row]);
        this.draftKey.reset('');
        this.changed.emit();
    }

    remove(key: string): void {
        this.rows.update((r) => r.filter((e) => e.key !== key));
        this.changed.emit();
    }

    /** Whether every edited entry parses under its own type. Touches controls so errors show. */
    validate(): boolean {
        for (const e of this.rows()) e.control.markAsTouched();
        return this.rows().every((e) => e.control.valid);
    }

    /**
     * The entries as TYPED values. ⚠ A pristine entry emits its ORIGINAL value reference — never a
     * re-parse of the display string — so unmodelled blocks round-trip an apply verbatim.
     */
    value(): Record<string, unknown> {
        const out: Record<string, unknown> = {};
        for (const e of this.rows()) {
            out[e.key] = e.control.dirty || e.added ? parse(e.kind, e.control.value, e.original) : e.original;
        }
        return out;
    }

    isDirty(): boolean {
        return this.rows().some((e) => e.control.dirty || e.added);
    }

    markPristine(): void {
        // Apply consumed the edits: what was written is the new baseline, additions included.
        this.rows.update((r) =>
            r.map((e) => {
                const v = e.control.dirty || e.added ? parse(e.kind, e.control.value, e.original) : e.original;
                const next = this.entry(e.key, v);
                return next;
            }),
        );
    }
}

/** The control family a stored value belongs to. */
export function kindOf(value: unknown): ExtraValueKind {
    if (typeof value === 'boolean') return 'boolean';
    if (typeof value === 'number') return 'number';
    if (value !== null && typeof value === 'object') return 'json';
    return 'text';
}

/** The display string a value seeds its control with. */
function display(kind: ExtraValueKind, value: unknown): string {
    if (kind === 'json') return JSON.stringify(value, null, 2);
    return String(value ?? '');
}

/**
 * An edited display string back to its TYPE. The validators guarantee parseability; the original is
 * the last-resort fallback so an invalid value can never silently downgrade to a string.
 */
function parse(kind: ExtraValueKind, text: string, original: unknown): unknown {
    switch (kind) {
        case 'boolean':
            return text === 'true';
        case 'number': {
            const n = Number(text);
            return Number.isFinite(n) && text.trim() !== '' ? n : original;
        }
        case 'json':
            try {
                return JSON.parse(text);
            } catch {
                return original;
            }
        default:
            return text;
    }
}

function numberValidator(c: AbstractControl): ValidationErrors | null {
    const v = String(c.value ?? '').trim();
    return v === '' || !Number.isFinite(Number(v)) ? { number: true } : null;
}

function jsonValidator(c: AbstractControl): ValidationErrors | null {
    try {
        JSON.parse(String(c.value ?? ''));
        return null;
    } catch {
        return { json: true };
    }
}
