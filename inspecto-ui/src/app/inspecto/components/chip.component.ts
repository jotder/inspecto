import { booleanAttribute, ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { statusBadgeClasses, statusToneSchemeClasses } from './status-badge.component';

/** Chip surface: a hollow bordered pill (`outline`) or a filled tint (`soft`). */
export type ChipVariant = 'outline' | 'soft';
/**
 * Chip emphasis: `neutral` grey, `primary` for a selected/active token, or `warning` for a caution the reader
 * must not miss (the Dashboard header's *Illustrative data*) — the status `warning` tone's amber pair.
 */
export type ChipTone = 'neutral' | 'primary' | 'warning';
/** Every chip tone, in gallery order. */
export const CHIP_TONES: readonly ChipTone[] = ['neutral', 'primary', 'warning'];

/**
 * 20 px tall like `<inspecto-status-badge>`: `leading-4` because the app's `text-xs` (10 px) has no
 * line-height of its own and would otherwise inherit its container's. Case is left alone — a chip holds
 * user text (tags, glob patterns, ids), unlike a badge's status vocabulary.
 */
export const CHIP_BASE = 'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium leading-4';

const TONE_CLASSES: Record<ChipVariant, Record<ChipTone, string>> = {
    outline: {
        neutral: 'border border-gray-300 dark:border-gray-600',
        // primary-600 on the dark card is 2.3:1 — dark mode steps up to primary-400 (4.9:1 on bg-card).
        primary: 'border border-primary text-primary dark:border-primary-400 dark:text-primary-400',
        // The status warning ink (amber-800 / dark amber-200) on the card, not the 600 shade, which fails AA.
        warning: 'border border-amber-600 text-amber-800 dark:border-amber-400 dark:text-amber-200',
    },
    soft: {
        neutral: 'bg-gray-100 text-gray-700 dark:bg-gray-700 dark:text-gray-200',
        primary: 'bg-primary-100 text-primary-800 dark:bg-primary-900 dark:text-primary-200',
        // Borrowed from `<inspecto-status-badge>`, the owner of status tints — measured AA in both schemes on `/design`.
        warning: statusBadgeClasses('warning'),
    },
};

/**
 * The `soft` pairs split per scheme WITHOUT the `dark:` prefix, so the `/design` Foundations section can measure a
 * light and a dark specimen side by side (a `dark:` class cannot be switched off inside a `.light` island).
 * `chip.component.spec.ts` pins it to {@link TONE_CLASSES}.
 */
const SOFT_SCHEME_CLASSES: Record<ChipTone, Record<'light' | 'dark', string>> = {
    neutral: { light: 'bg-gray-100 text-gray-700', dark: 'bg-gray-700 text-gray-200' },
    primary: { light: 'bg-primary-100 text-primary-800', dark: 'bg-primary-900 text-primary-200' },
    warning: { light: statusToneSchemeClasses('warning', 'light'), dark: statusToneSchemeClasses('warning', 'dark') },
};

/** The scheme-specific half of a `soft` tone (no `dark:` prefix, no geometry) — for side-by-side specimens. */
export function chipSoftSchemeClasses(tone: ChipTone, scheme: 'light' | 'dark'): string {
    return SOFT_SCHEME_CLASSES[tone][scheme];
}

/** A chip's full class string (geometry + tone, both schemes). */
export function chipClasses(variant: ChipVariant, tone: ChipTone): string {
    return `${CHIP_BASE} ${TONE_CLASSES[variant][tone]}`;
}

/**
 * Small labelled pill — the shared primitive for tag / token / filter chips (widget tags &
 * type/tag toggles, the events correlation filter, query tokens, reconciliation match keys,
 * link-analysis summaries). Replaces the per-component hand-rolled `rounded-full … text-xs`
 * spans. Content is projected, so a leading `<mat-icon>` or `<span class="font-mono">` just
 * goes inside. Set `[removable]` to append an ✕ that emits `(removed)`.
 *
 * @example <inspecto-chip variant="soft">{{ tag }}</inspecto-chip>
 * @example <inspecto-chip [tone]="active() ? 'primary' : 'neutral'">{{ type }}</inspecto-chip>
 * @example <inspecto-chip variant="soft" tone="warning">Illustrative data</inspecto-chip>
 */
@Component({
    selector: 'inspecto-chip',
    standalone: true,
    imports: [MatIconModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <span [class]="classes">
            <ng-content />
            @if (removable) {
                <button
                    type="button"
                    class="-mr-0.5 ml-0.5 flex"
                    [attr.aria-label]="removeLabel"
                    (click)="removed.emit()"
                >
                    <mat-icon class="icon-size-3.5" svgIcon="heroicons_outline:x-mark" />
                </button>
            }
        </span>
    `,
})
export class ChipComponent {
    @Input() variant: ChipVariant = 'outline';
    @Input() tone: ChipTone = 'neutral';
    /** When true, renders a trailing ✕ button that emits {@link removed}. */
    @Input({ transform: booleanAttribute }) removable = false;
    /** Accessible label for the remove button. */
    @Input() removeLabel = 'Remove';

    @Output() readonly removed = new EventEmitter<void>();

    get classes(): string {
        return chipClasses(this.variant, this.tone);
    }
}
