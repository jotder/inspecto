import {
    ChangeDetectionStrategy,
    Component,
    ElementRef,
    Injector,
    afterNextRender,
    computed,
    forwardRef,
    inject,
    input,
    signal,
    viewChild,
} from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';

/**
 * **Pen-style field** — one compact property row: `label · value · ✎`. The value reads as text; the pencil (or a
 * click on the value) swaps it for an input, committed on Enter / blur and dropped on Escape. A `ControlValueAccessor`,
 * so `formControlName` / `[formControl]` / `[ngModel]` bind exactly as a `matInput` did.
 *
 * **Defaults:** when the value is empty and a `default` is declared, the row shows it as a ghost ("↳ 2"), and while
 * editing, pressing **→** in an empty box (or at the end of a typed prefix of it) fills the default in without typing.
 * A default is only ever a suggestion — it is never written until the author accepts it.
 *
 * Like `<inspecto-option-picker>` it renders its own label and error line; do not wrap it in a `mat-form-field`.
 */
@Component({
    selector: 'inspecto-pen-field',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatIconModule],
    providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => PenFieldComponent), multi: true }],
    host: { class: 'block min-w-0' },
    template: `
        <div class="flex min-h-8 min-w-0 items-center gap-2">
            @if (label()) {
                <span class="text-secondary shrink-0 text-xs"
                    >{{ label() }}
                    @if (required()) {
                        <span class="text-warn"> *</span>
                    }
                </span>
            }
            @if (editing()) {
                @if (multiline()) {
                    <textarea
                        #box
                        rows="2"
                        class="min-w-0 flex-1 rounded border px-1.5 py-0.5 text-sm focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1"
                        style="background: transparent; color: inherit"
                        [attr.maxlength]="maxlength()"
                        [attr.aria-label]="ariaLabel() || label()"
                        [placeholder]="placeholder()"
                        [value]="draft()"
                        (input)="draft.set($any($event.target).value)"
                        (keydown)="onKey($event)"
                        (blur)="commit()"
                    ></textarea>
                } @else {
                    <input
                        #box
                        class="min-w-0 flex-1 rounded border px-1.5 py-0.5 text-sm focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1"
                        style="background: transparent; color: inherit"
                        [type]="type()"
                        [attr.min]="min()"
                        [attr.max]="max()"
                        [attr.maxlength]="maxlength()"
                        [attr.aria-label]="ariaLabel() || label()"
                        [placeholder]="placeholder()"
                        [value]="draft()"
                        (input)="draft.set($any($event.target).value)"
                        (keydown)="onKey($event)"
                        (blur)="commit()"
                    />
                }
                @if (hasDefault() && draft() === '') {
                    <span class="text-secondary shrink-0 text-xs" aria-hidden="true">→ {{ defaultText() }}</span>
                }
            } @else {
                <button
                    type="button"
                    class="hover:bg-hover min-w-0 flex-1 rounded px-1 py-0.5 text-left text-sm focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1"
                    [class.font-mono]="mono()"
                    [class.truncate]="!multiline()"
                    [class.whitespace-pre-line]="multiline()"
                    [class.line-clamp-3]="multiline()"
                    [disabled]="disabled()"
                    [attr.aria-label]="'Edit ' + (ariaLabel() || label())"
                    (click)="startEditing()"
                    (keydown)="typeToEdit($event)"
                >
                    @if (text() !== '') {
                        {{ text() }}
                    } @else if (hasDefault()) {
                        <span class="text-secondary italic">{{ defaultText() }}</span>
                    } @else {
                        <span class="text-secondary">{{ placeholder() || '—' }}</span>
                    }
                </button>
                @if (!readOnly() && !disabled()) {
                    <button
                        type="button"
                        class="pen-pencil text-secondary hover:bg-hover flex h-7 w-7 shrink-0 items-center justify-center rounded focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1"
                        [attr.aria-label]="'Edit ' + (ariaLabel() || label())"
                        (click)="startEditing()"
                        (keydown)="typeToEdit($event)"
                    >
                        <mat-icon class="icon-size-4" svgIcon="heroicons_outline:pencil"></mat-icon>
                    </button>
                }
            }
        </div>
        @if (error()) {
            <p class="text-warn m-0 text-xs" role="alert">{{ error() }}</p>
        }
    `,
})
export class PenFieldComponent implements ControlValueAccessor {
    readonly label = input('');
    readonly type = input<'text' | 'number' | 'date'>('text');
    /** A free-text note: edits in a two-row box (Shift+Enter breaks a line, Enter commits) and reads as wrapped text. */
    readonly multiline = input(false);
    readonly maxlength = input<number | null>(null);
    /** Suggested value shown as a ghost while empty; **→** accepts it. */
    readonly default = input<string | number | null>(null);
    readonly placeholder = input('');
    readonly min = input<number | null>(null);
    readonly max = input<number | null>(null);
    readonly required = input(false);
    readonly readOnly = input(false);
    readonly mono = input(false);
    readonly ariaLabel = input('');
    /** Explicit error line (a host validating on submit renders its own message here). */
    readonly error = input('');

    protected readonly value = signal<string | number | null>(null);
    protected readonly disabled = signal(false);
    protected readonly editing = signal(false);
    protected readonly draft = signal('');
    private readonly box = viewChild<ElementRef<HTMLInputElement | HTMLTextAreaElement>>('box');
    private readonly injector = inject(Injector);

    protected readonly text = computed(() => (this.value() ?? '') + '');
    protected readonly hasDefault = computed(() => this.default() !== null && this.default() !== '');
    protected readonly defaultText = computed(() => (this.default() ?? '') + '');

    private onChange: (v: string | number | null) => void = () => undefined;
    private onTouched: () => void = () => undefined;

    writeValue(v: string | number | null): void {
        this.value.set(v ?? null);
    }
    registerOnChange(fn: (v: string | number | null) => void): void {
        this.onChange = fn;
    }
    registerOnTouched(fn: () => void): void {
        this.onTouched = fn;
    }
    setDisabledState(d: boolean): void {
        this.disabled.set(d);
    }

    /** Open the field for editing and put the caret in it (a host pointing at a missing value). */
    focus(): void {
        this.startEditing();
    }

    protected startEditing(initial?: string): void {
        if (this.readOnly() || this.disabled()) return;
        this.draft.set(initial ?? this.text());
        this.editing.set(true);
        // The input exists only after the next render: a microtask runs BEFORE it, so the caret was never placed.
        afterNextRender(
            () => {
                const el = this.box()?.nativeElement;
                el?.focus();
                // A typed first key starts the value: the caret goes after it (a number input has no caret to place).
                if (el && initial !== undefined && el.type !== 'number')
                    el.setSelectionRange(el.value.length, el.value.length);
            },
            { injector: this.injector },
        );
    }

    /**
     * Typing on the closed row edits it (LA-ONBOARD-1 phase 0 follow-up): a keyboard user who tabs to the row and types
     * gets an open editor holding that key, as a spreadsheet cell does (the key REPLACES the value; Enter, Space or the
     * pencil open it keeping the value). Only a printable key with no Ctrl / Alt / Meta: shortcuts, Tab, arrows and the
     * activation keys keep their meaning. A number field takes only a key a number can start with; a date field opens
     * through its picker, never from one key.
     */
    protected typeToEdit(e: KeyboardEvent): void {
        if (e.key.length !== 1 || e.key === ' ' || e.ctrlKey || e.altKey || e.metaKey || e.isComposing) return;
        if (this.type() === 'date') return;
        if (this.type() === 'number' && !/[0-9.+-]/.test(e.key)) return;
        if (this.readOnly() || this.disabled()) return;
        e.preventDefault();
        this.startEditing(e.key);
    }

    protected onKey(e: KeyboardEvent): void {
        const el = e.target as HTMLInputElement;
        if (e.key === 'Enter') {
            if (this.multiline() && e.shiftKey) return; // Shift+Enter breaks a line in a note
            e.preventDefault();
            this.commit();
        } else if (e.key === 'Escape') {
            e.stopPropagation();
            this.editing.set(false);
        } else if (e.key === 'ArrowRight' && this.hasDefault()) {
            const d = this.defaultText();
            // Number inputs expose no caret, so → there only fires on an empty box.
            const atEnd = el.type === 'number' ? el.value === '' : el.selectionStart === el.value.length;
            if (atEnd && d.startsWith(el.value) && el.value !== d) {
                e.preventDefault();
                this.draft.set(d);
                el.value = d;
            }
        }
    }

    protected commit(): void {
        if (!this.editing()) return;
        this.editing.set(false);
        const raw = this.draft().trim();
        let next: string | number | null = raw;
        if (this.type() === 'number') {
            const n = Number(raw);
            next = raw === '' || Number.isNaN(n) ? (this.default() as number | null) : n;
        }
        if (next !== this.value()) {
            this.value.set(next);
            this.onChange(next);
        }
        this.onTouched();
    }
}
