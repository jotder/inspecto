import { ChangeDetectionStrategy, Component, effect, input, signal } from '@angular/core';

/**
 * One collapsible section of the Investigation tab: a bordered card whose title is the toggle. Closed by default so the
 * panel does not need a long scroll; `forceOpen` opens it when something in it needs attention (a selected entity).
 * Native `<details>`: keyboard and screen-reader behaviour come with it, and the content stays in the DOM when closed.
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
            (toggle)="opened.set($any($event.target).open)"
        >
            <summary class="flex cursor-pointer select-none items-center gap-2 px-3 py-2 text-base font-semibold">
                <span class="la-fold-chevron" aria-hidden="true">▸</span>
                <span class="min-w-0 flex-1 truncate">{{ title() }}</span>
                @if (hint()) {
                    <span class="text-secondary text-sm font-normal">{{ hint() }}</span>
                }
            </summary>
            <div class="flex flex-col gap-2 border-t p-3" style="border-color: var(--gamma-border)">
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
    `,
})
export class LaFoldComponent {
    readonly title = input.required<string>();
    /** A short right-aligned note on the closed header (a count, a state). */
    readonly hint = input('');
    /** Opens the section when it turns true; the person can still close it. */
    readonly forceOpen = input(false);
    readonly opened = signal(false);

    constructor() {
        effect(() => {
            if (this.forceOpen()) this.opened.set(true);
        });
    }
}
