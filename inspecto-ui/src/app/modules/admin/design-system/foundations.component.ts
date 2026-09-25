import { afterNextRender, ChangeDetectionStrategy, Component, ElementRef, inject, signal } from '@angular/core';
import {
    STATUS_BADGE_BASE,
    STATUS_TONES,
    statusToneSchemeClasses,
} from 'app/inspecto/components/status-badge.component';
import { CHIP_BASE, CHIP_TONES, ChipTone, chipSoftSchemeClasses } from 'app/inspecto/components/chip.component';
import { CHART_TONE } from 'app/inspecto/theme/chart-tokens';

type Scheme = 'light' | 'dark';

/** A `--gamma-*` custom property as the theming plugin emits it (`src/@gamma/tailwind/plugins/theming.js`). */
interface TokenRow {
    token: string;
    role: string;
}

/** A foreground token measured against a surface token. */
interface TextPair {
    fg: string;
    bg: string;
}

interface TypeRow {
    role: string;
    classes: string;
    usedBy: string;
}

interface StepRow {
    classes: string;
    usedBy: string;
}

/** Parse a computed CSS colour (`rgb(…)` / `rgba(…)`) into 0–255 channels + alpha; `null` when unresolved. */
export function parseCssColor(value: string | null | undefined): [number, number, number, number] | null {
    const m = /^rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)(?:\s*[,/]\s*([\d.]+%?))?\s*\)$/.exec(
        (value ?? '').trim(),
    );
    if (!m) return null;
    const a = m[4] === undefined ? 1 : m[4].endsWith('%') ? parseFloat(m[4]) / 100 : parseFloat(m[4]);
    return [+m[1], +m[2], +m[3], a];
}

/** WCAG 2.x relative-luminance contrast ratio of two opaque sRGB colours (1–21). */
export function contrastRatio(a: readonly number[], b: readonly number[]): number {
    const lum = (c: readonly number[]) => {
        const [r, g, bl] = c.slice(0, 3).map((v) => {
            const s = v / 255;
            return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
        });
        return 0.2126 * r + 0.7152 * g + 0.0722 * bl;
    };
    const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x);
    return (hi + 0.05) / (lo + 0.05);
}

/** A computed colour as `#rrggbb` (plus alpha when translucent) for display; `—` when unresolved. */
export function formatCssColor(value: string | null | undefined): string {
    const c = parseCssColor(value);
    if (!c) return '—';
    const hex =
        '#' +
        c
            .slice(0, 3)
            .map((v) => Math.round(v).toString(16).padStart(2, '0'))
            .join('');
    return c[3] < 1 ? `${hex} @ ${Math.round(c[3] * 100)}%` : hex;
}

/**
 * Design-system Foundations — the tokens the app actually uses, resolved LIVE from the running
 * stylesheet in both schemes. Nothing here is a second copy of a value: every swatch paints the real
 * `--gamma-*` variable or Tailwind class, and the hex / px / contrast figures are read back with
 * `getComputedStyle`, so the page cannot drift from the theme. The light and dark panels are forced
 * `.light` / `.dark` islands (the theming plugin scopes its variables to those classes), so both
 * render whichever scheme the app is in.
 */
@Component({
    selector: 'inspecto-ds-foundations',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './foundations.component.html',
})
export class DesignSystemFoundationsComponent {
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

    readonly schemes: Scheme[] = ['light', 'dark'];
    readonly tones = STATUS_TONES;
    readonly badgeBase = STATUS_BADGE_BASE;
    readonly chartTone = CHART_TONE;
    readonly chipTones = CHIP_TONES;

    /** Scheme-dependent tokens: `customProps` in `theming.js` (values differ per `.light` / `.dark`). */
    readonly surfaces: TokenRow[] = [
        { token: 'bg-card', role: 'Cards, panels, popovers (bg-card)' },
        { token: 'bg-default', role: 'Page background, code wells (bg-default)' },
        { token: 'bg-dialog', role: 'Dialogs (bg-dialog)' },
        { token: 'bg-app-bar', role: 'Top bar' },
        { token: 'bg-status-bar', role: 'Status bar' },
        { token: 'bg-hover', role: 'Row / option hover (bg-hover)' },
    ];
    readonly texts: TokenRow[] = [
        { token: 'text-default', role: 'Body and headings (text-default)' },
        { token: 'text-secondary', role: 'Subtitles, captions, labels (text-secondary)' },
        { token: 'text-hint', role: 'Placeholders, hints (text-hint)' },
        {
            token: 'text-primary',
            role: 'Links, emphasis (text-primary) — primary-600 light, primary-400 dark; fills keep primary',
        },
        { token: 'text-disabled', role: 'Disabled controls (text-disabled) — exempt from AA' },
    ];
    readonly lines: TokenRow[] = [
        { token: 'border', role: 'Every default border (border)' },
        { token: 'divider', role: 'Dividers (divider)' },
        { token: 'icon', role: 'Icons' },
        { token: 'mat-icon', role: '<mat-icon> ink' },
    ];
    readonly tokenGroups: { title: string; rows: TokenRow[]; kind: 'bg' | 'fg' | 'line' }[] = [
        { title: 'Surfaces', rows: this.surfaces, kind: 'bg' },
        { title: 'Text', rows: this.texts, kind: 'fg' },
        { title: 'Lines & icons', rows: this.lines, kind: 'line' },
    ];

    /** Scheme-independent palettes (`themes.default` in `tailwind.config.js`: indigo / slate / red). */
    readonly palettes = [
        { name: 'primary', source: 'indigo — buttons, links, focus ring, selection (bg-primary, text-primary)' },
        { name: 'accent', source: 'slate — Material accent' },
        { name: 'warn', source: 'red — destructive actions, form errors (text-warn)' },
    ];
    readonly hues = [50, 100, 200, 300, 400, 500, 600, 700, 800, 900];

    /**
     * The text-on-surface pairs a screen actually draws; measured per scheme. Every AA text token on both
     * page surfaces (`text-disabled` is exempt) — `text-primary` is the scheme-aware TEXT token, never the
     * raw palette DEFAULT, which is a fill colour and stays 2.33:1 on the dark card.
     */
    readonly textPairs: TextPair[] = [
        { fg: 'text-default', bg: 'bg-card' },
        { fg: 'text-default', bg: 'bg-default' },
        { fg: 'text-secondary', bg: 'bg-card' },
        { fg: 'text-secondary', bg: 'bg-default' },
        { fg: 'text-hint', bg: 'bg-card' },
        { fg: 'text-hint', bg: 'bg-default' },
        { fg: 'text-primary', bg: 'bg-card' },
        { fg: 'text-primary', bg: 'bg-default' },
    ];

    /** Type roles, grounded in the components that own them. The app's scale is gamma's, not stock Tailwind. */
    readonly typeRoles: TypeRow[] = [
        {
            role: 'Page title',
            classes: 'text-title font-semibold leading-8 tracking-tight',
            usedBy: 'inspecto-page-header',
        },
        { role: 'Section title', classes: 'text-lg font-semibold', usedBy: 'h2 in panes and cards' },
        { role: 'Card title', classes: 'text-sm font-semibold', usedBy: 'h3 inside a card' },
        { role: 'Body', classes: 'text-base', usedBy: 'body default (0.875rem)' },
        { role: 'Dense body / table', classes: 'text-sm', usedBy: 'forms, lists, table cells' },
        { role: 'Caption', classes: 'text-secondary text-xs', usedBy: 'hints, stat-tile label' },
        {
            role: 'Eyebrow',
            classes: 'text-secondary text-xs font-semibold uppercase tracking-wider',
            usedBy: 'page-header eyebrow, column group labels',
        },
        {
            role: 'Tabular number',
            classes: 'text-xl font-semibold leading-7 tabular-nums',
            usedBy: 'inspecto-stat-tile',
        },
        { role: 'Code / id', classes: 'font-mono text-xs', usedBy: 'ids, SQL, snippets' },
    ];

    readonly spacing: StepRow[] = [
        { classes: 'w-1', usedBy: 'gap-1 — icon + label, chip content' },
        { classes: 'w-2', usedBy: 'gap-2 — the default inline gap (most used)' },
        { classes: 'w-3', usedBy: 'gap-3 / p-3 — toolbars, dense cards' },
        { classes: 'w-4', usedBy: 'gap-4 / p-4 — form rows, card grids' },
        { classes: 'w-6', usedBy: 'p-6 — card padding (most used)' },
        { classes: 'w-10', usedBy: 'gap-10 / p-10 — page sections (sm:)' },
    ];
    readonly radii: StepRow[] = [
        { classes: 'rounded', usedBy: 'status badge, inline code' },
        { classes: 'rounded-md', usedBy: 'inputs, focus ring' },
        { classes: 'rounded-lg', usedBy: 'snippet wells, small panels' },
        { classes: 'rounded-xl', usedBy: 'stat tile' },
        { classes: 'rounded-2xl', usedBy: 'cards (most used)' },
        { classes: 'rounded-full', usedBy: 'chips, avatars, dots' },
    ];
    readonly elevations: StepRow[] = [
        { classes: 'shadow-sm', usedBy: 'stat tile' },
        { classes: 'shadow', usedBy: 'cards (most used)' },
        { classes: 'shadow-lg', usedBy: 'popovers, floating panels' },
    ];

    /** Resolved values keyed by probe id, filled after render (empty in jsdom, where no stylesheet exists). */
    readonly resolved = signal<Record<string, string>>({});

    constructor() {
        afterNextRender(() => this.measure());
    }

    toneClasses(tone: (typeof STATUS_TONES)[number], scheme: Scheme): string {
        return `${this.badgeBase} ${statusToneSchemeClasses(tone, scheme)}`;
    }

    /** A soft `<inspecto-chip>` specimen for one scheme — the chip's own per-scheme pair, measured like a badge. */
    chipToneClasses(tone: ChipTone, scheme: Scheme): string {
        return `${CHIP_BASE} ${chipSoftSchemeClasses(tone, scheme)}`;
    }

    chartColor(tone: string): string | null {
        return (this.chartTone as Record<string, string>)[tone] ?? null;
    }

    /** `var(--gamma-…)` for a token (`text-primary` is defined in `src/styles/styles.scss`, not the plugin). */
    cssVar(token: string): string {
        return `var(--gamma-${token})`;
    }

    value(id: string): string {
        return this.resolved()[id] ?? '—';
    }

    /** Contrast of a measured pair, `null` when either side is unresolved or translucent. */
    ratio(id: string): number | null {
        const [fg, bg] = [parseCssColor(this.resolved()[`${id}:fg`]), parseCssColor(this.resolved()[`${id}:bg`])];
        if (!fg || !bg || fg[3] < 1 || bg[3] < 1) return null;
        return Math.round(contrastRatio(fg, bg) * 100) / 100;
    }

    /** Read every probe back from the live stylesheet. */
    measure(): void {
        const out: Record<string, string> = {};
        this.host.nativeElement.querySelectorAll<HTMLElement>('[data-probe]').forEach((el) => {
            const id = el.dataset['probe']!;
            const style = getComputedStyle(el);
            switch (el.dataset['measure']) {
                case 'bg':
                    out[id] = formatCssColor(style.backgroundColor);
                    break;
                case 'fg':
                    out[id] = formatCssColor(style.color);
                    break;
                case 'pair':
                    out[`${id}:fg`] = style.color;
                    out[`${id}:bg`] = style.backgroundColor;
                    break;
                case 'type':
                    out[id] = style.fontSize ? `${style.fontSize} / ${style.lineHeight} · ${style.fontWeight}` : '—';
                    break;
            }
        });
        this.resolved.set(out);
    }
}
