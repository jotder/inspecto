import { ChangeDetectionStrategy, Component, Input } from '@angular/core';

/**
 * The four semantic status tones (plus neutral) shared across Inspecto. This is the SINGLE
 * source of truth for status/severity/level colors — every event level, severity, reachability
 * flag and batch outcome maps to one of these. Do NOT hand-roll status colors in components or
 * cell renderers; classify with {@link statusTone} / {@link statusBadgeClasses} or render an
 * `<inspecto-status-badge>` so the palette stays consistent and WCAG-AA in both schemes.
 */
export type StatusTone = 'error' | 'warning' | 'info' | 'success' | 'neutral';

/** Every tone, in the order the gallery lists them. */
export const STATUS_TONES: readonly StatusTone[] = ['success', 'warning', 'error', 'info', 'neutral'];

/**
 * Tailwind class set per tone. The light `100/800` and dark `900/200` pairings are deliberately
 * the high-contrast ones (6.4–9.5:1 in both schemes — measured live in the `/design` Foundations
 * section) rather than the single-shade `text-*-600` that fails AA on cards. Class strings are
 * literal here so Tailwind's content scanner emits them even when only referenced via the
 * {@link statusBadgeClasses} string builder (e.g. ag-Grid renderers).
 */
const TONE_CLASSES: Record<StatusTone, string> = {
    error: 'bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200',
    warning: 'bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-200',
    info: 'bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200',
    success: 'bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200',
    neutral: 'bg-gray-100 text-gray-700 dark:bg-gray-700 dark:text-gray-200',
};

/**
 * The same pairs split per scheme, WITHOUT the `dark:` prefix — so the `/design` Foundations section
 * can render a light and a dark specimen side by side whichever scheme the app is in (a `dark:` class
 * cannot be switched off inside a `.light` island on a dark page). Literal for the same content-scanner
 * reason; `status-badge.component.spec.ts` pins it to {@link TONE_CLASSES}.
 */
const TONE_SCHEME_CLASSES: Record<StatusTone, Record<'light' | 'dark', string>> = {
    error: { light: 'bg-red-100 text-red-800', dark: 'bg-red-900 text-red-200' },
    warning: { light: 'bg-amber-100 text-amber-800', dark: 'bg-amber-900 text-amber-200' },
    info: { light: 'bg-blue-100 text-blue-800', dark: 'bg-blue-900 text-blue-200' },
    success: { light: 'bg-green-100 text-green-800', dark: 'bg-green-900 text-green-200' },
    neutral: { light: 'bg-gray-100 text-gray-700', dark: 'bg-gray-700 text-gray-200' },
};

/** The `dot` variant's marker: the tone's 500 shade (the hue `CHART_TONE` draws a series in). */
const DOT_CLASSES: Record<StatusTone, string> = {
    error: 'bg-red-500',
    warning: 'bg-amber-500',
    info: 'bg-blue-500',
    success: 'bg-green-500',
    neutral: 'bg-gray-400',
};

/**
 * `pill` (default) — the tinted pill. `dot` — a coloured dot before plain text, for dense tables where
 * a column of pills gets loud; the text keeps the row's own ink, so its contrast is the table's.
 */
export type StatusBadgeVariant = 'pill' | 'dot';

/**
 * Shared type + height (kept here so the component and string-renderer paths render identically).
 * `leading-4` pins the badge at 20 px: the app's `text-xs` (10 px) carries no line-height of its own,
 * so a badge used to inherit its container's — inside an ag-Grid cell, whose line-height is the row
 * height, the pill grew to the full row. A raw status VALUE is upper-cased (it arrives as `FAIL`, `Pass`,
 * `healthy`, so the case is the badge's, not the data's); a caller's `label` is a phrase ("Down 2.6 pts vs prior
 * period", "Target 95 % — below target") and keeps its own case.
 */
const BADGE_TYPE = 'py-0.5 text-xs font-semibold leading-4';
/** A status value is one word: upper-case, never wraps or shrinks. A label phrase keeps its case and WRAPS inside
 *  its container — at a quarter-width KPI tile a no-wrap delta line spilled into the neighbouring tile. */
const VALUE_CASE = 'shrink-0 whitespace-nowrap uppercase tracking-wide';
const LABEL_FLOW = 'max-w-full';
/** Pill geometry + type. */
export const STATUS_BADGE_BASE = `inline-flex items-center rounded px-2 ${BADGE_TYPE}`;
const STATUS_DOT_BASE = `inline-flex items-center gap-1.5 ${BADGE_TYPE}`;
const DOT = 'h-2 w-2 shrink-0 rounded-full';

/** Map a free-form status / level / severity / outcome token to a semantic tone. */
export function statusTone(value: string | null | undefined): StatusTone {
    switch ((value ?? '').toUpperCase()) {
        case 'ERROR':
        case 'CRITICAL':
        case 'FATAL':
        case 'FAIL':
        case 'FAILED':
        case 'ERRORED':
        case 'REJECTED':
        case 'UNREACHABLE':
        case 'DENIED':
        case 'REVOKED':
        case 'RED': // RAG — KPI below its red threshold (TM Forum scorecard convention)
        case 'CONFIRMED': // leakage / fraud case — loss confirmed, recovery pending
            return 'error';
        case 'WARN':
        case 'WARNING':
        case 'HIGH':
        case 'MAJOR':
        case 'DIAGNOSING':
        case 'PAUSED':
        case 'QUARANTINE':
        case 'QUARANTINED':
        case 'EXPIRED':
        case 'AMBER': // RAG — between target and the red threshold
        case 'INVESTIGATING':
            return 'warning';
        case 'INFO':
        case 'OPEN':
        case 'IDENTIFIED':
        case 'MINOR':
        case 'PENDING':
        case 'PROCESSING':
        case 'REQUESTED':
            return 'info';
        case 'SUCCESS':
        case 'SUCCEEDED':
        case 'OK':
        case 'READY':
        case 'HEALTHY':
        case 'REACHABLE':
        case 'RESOLVED':
        case 'CLOSED':
        case 'ACTIVE':
        case 'LIVE':
        case 'PASS':
        case 'GREEN': // RAG — on or better than target
        case 'RECOVERED': // leakage case — value recovered
        case 'CLOSED - NO LOSS':
            return 'success';
        default:
            return 'neutral';
    }
}

/** Tailwind color classes (no geometry) for a status token — for ag-Grid/innerHTML renderers. */
export function statusBadgeClasses(value: string | null | undefined): string {
    return TONE_CLASSES[statusTone(value)];
}

/** The scheme-specific half of a tone's classes (no `dark:` prefix) — for side-by-side specimens. */
export function statusToneSchemeClasses(tone: StatusTone, scheme: 'light' | 'dark'): string {
    return TONE_SCHEME_CLASSES[tone][scheme];
}

/** HTML-escape text for an HTML-string cell renderer. */
export function escapeHtml(s: string): string {
    return s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);
}

/**
 * Full markup (geometry + color) for a status token — for raw HTML renderers. Pass `label` to show
 * custom text under a chosen tone (e.g. `statusBadgeHtml('warning', 'Behind')`), mirroring the
 * component's `value`+`label` split, and `'dot'` for the dense-table variant.
 */
export function statusBadgeHtml(
    value: string | null | undefined,
    label?: string,
    variant: StatusBadgeVariant = 'pill',
): string {
    // The text is ESCAPED here: callers hand over raw cell values (alert names, statuses read off data), and this
    // string goes into the grid as HTML.
    const text = escapeHtml(label ?? value ?? '');
    const kase = ` ${label == null ? VALUE_CASE : LABEL_FLOW}`;
    if (variant === 'dot') {
        const dot = `<span aria-hidden="true" class="${DOT} ${DOT_CLASSES[statusTone(value)]}"></span>`;
        return `<span class="${STATUS_DOT_BASE}${kase}">${dot}${text}</span>`;
    }
    return `<span class="${STATUS_BADGE_BASE}${kase} ${statusBadgeClasses(value)}">${text}</span>`;
}

/**
 * Status pill — renders `value` (or `label`) with the shared semantic palette. Replaces the
 * per-component `levelClass()` helpers and hardcoded badge styles.
 *
 * @example <inspecto-status-badge [value]="event.level" />
 * @example <inspecto-status-badge [value]="row.result" variant="dot" />
 */
@Component({
    selector: 'inspecto-status-badge',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        @if (variant === 'dot') {
            <span [class]="dotBase + kase"
                ><span aria-hidden="true" [class]="dotClasses"></span>{{ label || value }}</span
            >
        } @else {
            <span [class]="base + kase + ' ' + classes">{{ label || value }}</span>
        }
    `,
})
export class StatusBadgeComponent {
    /** Raw status/level/severity token (case-insensitive); also the default visible text. */
    @Input({ required: true }) value: string | null | undefined = '';
    /** Optional display text override (defaults to `value`). */
    @Input() label = '';
    /** `pill` (default) or `dot` — a coloured dot before plain text, for dense tables. */
    @Input() variant: StatusBadgeVariant = 'pill';

    readonly base = STATUS_BADGE_BASE;
    readonly dotBase = STATUS_DOT_BASE;

    get classes(): string {
        return statusBadgeClasses(this.value);
    }

    /** Upper-case only the raw status value — a `label` is a phrase and keeps its case. */
    get kase(): string {
        return ` ${this.label ? LABEL_FLOW : VALUE_CASE}`;
    }

    get dotClasses(): string {
        return `${DOT} ${DOT_CLASSES[statusTone(this.value)]}`;
    }
}
