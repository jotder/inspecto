import { ChangeDetectionStrategy, Component, OnInit, effect, input, signal } from '@angular/core';

/**
 * One collapsible section of the Investigation tab: a bordered card whose title is the toggle. Closed by default so the
 * panel does not need a long scroll; `forceOpen` opens it when something in it needs attention (a selected entity).
 * Native `<details>`: keyboard and screen-reader behaviour come with it, and the content stays in the DOM when closed.
 *
 * LA-INVESTIGATE-UX-1: `changed` marks a filter group set away from its default (a dot, and the words for a screen
 * reader); `dense` is the compact header of a filter group; `rememberKey` keeps the open state in this browser.
 */
@Component({
    selector: 'la-fold',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <details
            class="la-fold rounded-md border"
            style="border-color: var(--gamma-border)"
            [open]="opened()"
            [class.border-0]="dense()"
            (toggle)="toggled($any($event.target).open)"
        >
            <summary
                class="flex cursor-pointer select-none items-center gap-2"
                [class]="dense() ? 'px-1 py-1 text-sm font-semibold' : 'px-3 py-2 text-base font-semibold'"
            >
                <span class="la-fold-chevron" aria-hidden="true">▸</span>
                <span class="shrink-0">{{ title() }}</span>
                @if (changed()) {
                    <span class="la-fold-dot" aria-hidden="true" data-testid="la-fold-changed"></span>
                    <span class="sr-only">(changed)</span>
                }
                @if (hint()) {
                    <span class="text-secondary min-w-0 flex-1 truncate text-right font-normal" [class.text-sm]="!dense()" [class.text-xs]="dense()">{{ hint() }}</span>
                }
            </summary>
            <div class="flex flex-col gap-2" [class]="dense() ? 'pb-2 pl-5 pr-1' : 'border-t p-3'" style="border-color: var(--gamma-border)">
                <ng-content />
            </div>
        </details>
    `,
    styles: `
        :host {
            display: block;
        }
        .la-fold > summary {
            list-style: none;
        }
        .la-fold > summary::-webkit-details-marker {
            display: none;
        }
        .la-fold-chevron {
            display: inline-block;
            transition: transform 0.15s;
        }
        .la-fold[open] > summary .la-fold-chevron {
            transform: rotate(90deg);
        }
        .la-fold-dot {
            width: 0.5rem;
            height: 0.5rem;
            flex-shrink: 0;
            border-radius: 9999px;
            background: var(--gamma-primary);
        }
    `,
})
export class LaFoldComponent implements OnInit {
    readonly title = input.required<string>();
    /** A short right-aligned note on the closed header (a count, a state). */
    readonly hint = input('');
    /** Opens the section when it turns true; the person can still close it. */
    readonly forceOpen = input(false);
    /** The section is open when it first shows (unless this browser remembers otherwise). */
    readonly startOpen = input(false);
    /** A filter group set away from its default. */
    readonly changed = input(false);
    /** The compact borderless header of a filter group. */
    readonly dense = input(false);
    /** Remember open / closed in this browser under this key. */
    readonly rememberKey = input('');
    readonly opened = signal(false);

    ngOnInit(): void {
        const kept = this.rememberKey() ? readFold(this.rememberKey()) : null;
        if (!this.opened()) this.opened.set(kept ?? this.startOpen());
    }

    toggled(open: boolean): void {
        this.opened.set(open);
        if (this.rememberKey()) writeFold(this.rememberKey(), open);
    }

    constructor() {
        effect(() => {
            if (this.forceOpen()) this.opened.set(true);
        });
    }
}

const FOLD_KEY = 'la.fold.';

function readFold(key: string): boolean | null {
    try {
        const v = localStorage.getItem(FOLD_KEY + key);
        return v === null ? null : v === '1';
    } catch {
        return null;
    }
}

function writeFold(key: string, open: boolean): void {
    try {
        localStorage.setItem(FOLD_KEY + key, open ? '1' : '0');
    } catch {
        // storage blocked: the fold simply forgets
    }
}
