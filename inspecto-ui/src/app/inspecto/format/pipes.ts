import { Pipe, PipeTransform } from '@angular/core';
import { fmtPercent, fmtWhen } from './format';

/**
 * `{{ ratio | fmtPercent }}` → "1.2%" for a ratio in 0–1 (the error-rate displays). Pure standalone pipe; the
 * app had no formatting pipes before P4. Null/undefined render as an em-dash. Add more pipes here only when a
 * formatter gains a template consumer (adoption-plan STOP — don't pipe-ify everything).
 */
@Pipe({ name: 'fmtPercent', standalone: true })
export class FmtPercentPipe implements PipeTransform {
    transform(ratio: number | null | undefined): string {
        return ratio == null ? '—' : fmtPercent(ratio);
    }
}

/**
 * `{{ v.savedAt | fmtWhen }}` → "3m ago" / locale date-time (relative within ±24h, absolute beyond — the
 * comments/history/feed stamp convention). Null/undefined render as an em-dash; unparseable input passes
 * through raw (same contract as {@link fmtWhen}).
 */
@Pipe({ name: 'fmtWhen', standalone: true })
export class FmtWhenPipe implements PipeTransform {
    transform(value: string | number | null | undefined): string {
        return value == null || value === '' ? '—' : fmtWhen(value);
    }
}
