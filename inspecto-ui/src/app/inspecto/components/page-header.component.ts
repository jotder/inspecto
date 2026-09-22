import { ChangeDetectionStrategy, Component, Input, booleanAttribute, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AiExplainComponent } from 'app/inspecto/ai-assist/ai-explain.component';

/**
 * The ONE page header (UI consolidation plan UI-02, 2026-09-22). Every routed pane renders its title,
 * one-line subtitle, the "About this screen" affordance and its action row through this component, so
 * the eye finds the same three things in the same place on every screen.
 *
 * Geometry it owns: a 22 px / 600 `<h1>` (`text-title`), a subtitle clamped to ONE line that expands on
 * click (the prose is still there, it just stops pushing content below the fold), a right-aligned
 * `[actions]` slot, and an optional `[tabs]` slot that renders full-width under the title row.
 *
 * Rules the sweep enforces through it:
 * - exactly one `<h1>` per page — the header renders it, panes must not render another;
 * - ONE filled primary action at the right end; everything else is an icon button or an overflow menu;
 * - refresh is always an icon button, never a pill;
 * - `terms` are canonical glossary spellings (they feed `<inspecto-ai-explain>`), never synonyms.
 *
 * @example
 * <inspecto-page-header title="Alerts" subtitle="Fired alert-rule breaches." [terms]="['Alert', 'Alert Rule']">
 *     <ng-container actions>
 *         <button mat-icon-button aria-label="Refresh" (click)="load()"><mat-icon svgIcon="heroicons_outline:arrow-path" /></button>
 *         <button mat-flat-button color="primary" (click)="create()">New rule</button>
 *     </ng-container>
 * </inspecto-page-header>
 */
@Component({
    selector: 'inspecto-page-header',
    standalone: true,
    imports: [RouterLink, MatIconModule, MatTooltipModule, AiExplainComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: { class: 'block' },
    template: `
        <header
            class="flex flex-col border-b pt-4"
            [class.px-6]="inset"
            [class.sm:px-10]="inset"
            [class.pb-4]="!hasTabs"
            [class.pb-0]="hasTabs"
        >
            <div class="flex min-w-0 items-start gap-4">
                <div class="min-w-0 flex-auto">
                    @if (backLink) {
                        <a
                            [routerLink]="backLink"
                            class="text-secondary hover:text-primary -ml-1 mb-1 inline-flex items-center gap-1 text-sm"
                        >
                            <mat-icon class="icon-size-4" svgIcon="heroicons_outline:chevron-left"></mat-icon>
                            <span>{{ backLabel || 'Back' }}</span>
                        </a>
                    }
                    @if (eyebrow) {
                        <div class="text-secondary text-xs font-semibold uppercase tracking-wider">{{ eyebrow }}</div>
                    }
                    <div class="flex min-w-0 items-center gap-2">
                        @if (headingLevel === 1) {
                            <h1
                                class="text-title min-w-0 truncate font-semibold leading-8 tracking-tight"
                                [class.font-mono]="mono"
                            >
                                {{ title }}
                            </h1>
                        } @else {
                            <h2
                                class="text-title min-w-0 truncate font-semibold leading-8 tracking-tight"
                                [class.font-mono]="mono"
                            >
                                {{ title }}
                            </h2>
                        }
                        @if (terms?.length) {
                            <inspecto-ai-explain [screen]="title" [terms]="terms!" />
                        }
                        <ng-content select="[badge]"></ng-content>
                    </div>
                    @if (subtitle) {
                        <button
                            type="button"
                            class="text-secondary mt-0.5 block max-w-full text-left text-sm leading-5 focus-visible:ring-primary rounded focus-visible:outline-none focus-visible:ring-2"
                            [class.truncate]="!expanded()"
                            [attr.aria-expanded]="expanded()"
                            [matTooltip]="expanded() ? '' : 'Show the full description'"
                            (click)="expanded.set(!expanded())"
                        >
                            {{ subtitle }}
                        </button>
                    }
                </div>
                <div class="flex shrink-0 items-center gap-2 pt-0.5">
                    <ng-content select="[actions]"></ng-content>
                </div>
            </div>
            <ng-content select="[tabs]"></ng-content>
        </header>
    `,
})
export class InspectoPageHeaderComponent {
    /** The screen's name — also the `screen` handed to the explain dialog. */
    @Input({ required: true }) title = '';
    /** One sentence. Rendered on ONE line; click expands it. Put the essay in the explain dialog, not here. */
    @Input() subtitle = '';
    /** Canonical glossary terms; when non-empty the `?` explain button renders beside the title. */
    @Input() terms?: string[];
    /** Small uppercase label above the title (e.g. the space name, or the parent entity type). */
    @Input() eyebrow = '';
    /** Detail pages: link back to the list. */
    @Input() backLink?: string | unknown[];
    @Input() backLabel = '';
    /** Render the title in the mono face (identifiers such as a pipeline name). */
    @Input({ transform: booleanAttribute }) mono = false;
    /** Set when a `[tabs]` slot is projected so the border sits under the tabs, not above them. */
    @Input({ transform: booleanAttribute }) hasTabs = false;

    /**
     * Whether the header supplies its own horizontal padding. The SPA has two pane layouts and this is
     * the one knob between them: a **full-bleed** pane puts the header above its padded content area
     * and keeps `inset` true, so the header pads itself and its bottom border spans the pane; a pane
     * whose outer wrapper is already padded (`p-6 md:p-8`) passes `[inset]="false"` so the text does not
     * end up double-indented.
     */
    @Input({ transform: booleanAttribute }) inset = true;

    /**
     * `1` (the default) renders the page's `<h1>`. Pass `2` when the header is NOT the page — the
     * `/design` gallery's live example, or a section mounted inside another pane that already owns the
     * heading — so the document keeps exactly one `<h1>` (WCAG; the sweep's own rule).
     */
    @Input() headingLevel: 1 | 2 = 1;

    readonly expanded = signal(false);
}
