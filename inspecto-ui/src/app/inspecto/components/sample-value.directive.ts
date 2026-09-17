import { Directive, ElementRef, inject, input } from '@angular/core';
import { NgControl } from '@angular/forms';

/**
 * Parses an edit box's placeholder and extracts a usable sample value.
 *
 * Handles:
 * - Prefixed examples: `'e.g. cdr_ingest'` -> `'cdr_ingest'`
 * - Explanatory parentheticals: `'e.g. cdr_ingest (comma-separate for several)'` -> `'cdr_ingest'`
 * - Multiple alternatives: `'e.g. BATCH_FAILED or job.custom'` -> `'BATCH_FAILED'`
 * - Clean sample literals: `'ops@example.com'`, `'0 0 6 * * *'`, `'2026-08-06'`, `'ROUND(amount * 100)'`
 *
 * Excludes prompts and instructions:
 * - Instructional starters: `'Search…'`, `'Filter…'`, `'Choose a format'`, `'leave blank to delete'`, `"Defaults to widget's name"`, `'when …'`
 * - Ellipsis indicators (`'…'`, `'...'`)
 */
export function extractSampleValue(placeholder: string | null | undefined): string | null {
    if (!placeholder) return null;
    const raw = placeholder.trim();
    if (!raw) return null;

    // 1. If explicitly prefixed as an example (e.g., eg:, example:, ex:)
    const prefixMatch = raw.match(/^(?:e\.g\.?|eg:?|example:?|ex:?)\s*(.+)$/i);
    if (prefixMatch) {
        let sample = prefixMatch[1].trim();
        // Remove trailing parenthetical remarks like "(comma-separate for several)" or "(optional)"
        sample = sample.replace(/\s*\([^)]*\)$/, '').trim();
        // If alternatives are listed with "or", pick the first valid option
        if (/\s+or\s+/i.test(sample)) {
            sample = sample.split(/\s+or\s+/i)[0].trim();
        }
        return sample.length > 0 ? sample : null;
    }

    // 2. Non-prefixed placeholders: exclude instructional / prompt text
    if (raw.endsWith('…') || raw.endsWith('...')) {
        return null;
    }

    const lower = raw.toLowerCase();
    const instructionalVerbs = [
        'search',
        'filter',
        'choose',
        'select',
        'pick',
        'enter',
        'type',
        'describe',
        'paste',
        'leave',
        'defaults to',
        'default:',
        'optional',
        'inherit',
        'none',
        'new key',
    ];
    for (const verb of instructionalVerbs) {
        if (lower === verb || lower.startsWith(verb + ' ') || lower.startsWith(verb + ':')) {
            return null;
        }
    }

    // If wrapped in parentheses only, e.g. "(optional)", not a sample
    if (/^\([^)]+\)$/.test(raw)) {
        return null;
    }

    return raw;
}

/**
 * Attaches to edit boxes (inputs and textareas) so pressing the Right Arrow (`->` / `ArrowRight`)
 * key when empty populates the field with the sample / placeholder value, positioning the cursor
 * at the end so the operator can freely edit or adapt it.
 *
 * Scoped strictly to the edit box element: does not register global document listeners.
 */
@Directive({
    selector: 'input[inspectoSampleValue], textarea[inspectoSampleValue], input[matInput], textarea[matInput]',
    standalone: true,
    exportAs: 'inspectoSampleValue',
    host: {
        '(keydown)': 'onKeydown($event)',
    },
})
export class InspectoSampleValueDirective {
    private readonly el = inject<ElementRef<HTMLInputElement | HTMLTextAreaElement>>(ElementRef);
    private readonly ngControl = inject(NgControl, { optional: true, self: true });

    /** Explicit sample value override if not extracting from placeholder. */
    readonly inspectoSampleValue = input<string | null | undefined>(undefined);

    /**
     * Resolves the candidate sample value: either explicitly bound or extracted from the
     * edit box's placeholder.
     */
    resolveSample(): string | null {
        const override = this.inspectoSampleValue();
        if (override !== undefined && override !== null && override.trim().length > 0) {
            return override.trim();
        }
        const nativeEl = this.el.nativeElement;
        const placeholder = nativeEl.placeholder || nativeEl.getAttribute('placeholder');
        return extractSampleValue(placeholder);
    }

    onKeydown(event: Event): void {
        const e = event as KeyboardEvent;
        if (e.key !== 'ArrowRight' && e.key !== 'Right') return;
        if (e.defaultPrevented) return;
        // Do not intercept if modifier keys are held
        if (e.altKey || e.ctrlKey || e.metaKey || e.shiftKey) return;

        const el = this.el.nativeElement;
        if (el.readOnly || el.disabled) return;

        const sample = this.resolveSample();
        if (!sample) return;

        const val = el.value ?? '';
        let selStart: number | null = null;
        let selEnd: number | null = null;
        try {
            selStart = el.selectionStart;
            selEnd = el.selectionEnd;
        } catch {
            // Some HTML5 input types (e.g. type="number") throw on selectionStart access
        }

        const isEmpty = val.trim().length === 0;
        const isPrefixCompletion =
            !isEmpty &&
            selStart !== null &&
            selEnd !== null &&
            selStart === val.length &&
            selEnd === val.length &&
            sample.toLowerCase().startsWith(val.toLowerCase()) &&
            val.length < sample.length;

        if (isEmpty || isPrefixCompletion) {
            e.preventDefault();
            this.applySample(sample);
        }
    }

    applySample(sample: string): void {
        const el = this.el.nativeElement;
        el.value = sample;

        try {
            el.setSelectionRange(sample.length, sample.length);
        } catch {
            // Ignored for input types that do not support setSelectionRange (e.g. type="number")
        }

        if (this.ngControl?.control) {
            const num = Number(sample);
            const valToSet = el.type === 'number' && !isNaN(num) && sample.trim() !== '' ? num : sample;
            this.ngControl.control.setValue(valToSet);
            this.ngControl.control.markAsDirty();
        }

        // Dispatch DOM events so native bindings, [(ngModel)], and (input) listeners receive the change
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
    }
}
