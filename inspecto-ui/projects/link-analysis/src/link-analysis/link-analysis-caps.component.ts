import { ChangeDetectionStrategy, Component, ElementRef, input, signal, viewChild } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';

/**
 * **Limits** (LA-INV-EXPAND-1, operator 2026-10-29: the footer strip is gone): the node caps the server and this browser
 * enforce, stated rather than hit silently. An icon button at the bottom right of the *Graph counts* overlay opens a small
 * read-only popover under it; it reads values the page already holds, so it makes no request. Escape closes it and puts
 * focus back on the button.
 */
@Component({
    selector: 'inspecto-link-analysis-caps',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    // The popover is anchored under its button, never in the overlay's flow.
    host: { class: 'relative inline-block', '(keydown.escape)': 'close($event)' },
    imports: [MatIconModule, MatTooltipModule],
    template: `
        <button
            #toggle
            type="button"
            class="hover:bg-hover flex h-6 w-6 items-center justify-center rounded focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1"
            data-testid="caps-toggle"
            aria-label="Limits"
            matTooltip="Limits"
            aria-controls="la-caps-list"
            [attr.aria-expanded]="open()"
            (click)="open.set(!open())"
        >
            <mat-icon class="icon-size-4" svgIcon="heroicons_outline:scale" aria-hidden="true"></mat-icon>
        </button>
        @if (open()) {
            <!-- an opaque card, or the graph labels bleed through -->
            <div
                id="la-caps-list"
                class="absolute right-0 top-full z-30 mt-1 w-max max-w-[90vw] rounded border p-2 text-sm shadow-sm"
                style="background: var(--gamma-bg-card); border-color: var(--gamma-border)"
                data-testid="caps-list"
                role="group"
                aria-label="Node limits in force"
            >
                <ul class="m-0 flex list-none flex-col gap-1 p-0">
                    <li>Graph drawn: up to {{ projection().toLocaleString('en') }} nodes</li>
                    <li>On screen tools: up to {{ analysis().toLocaleString('en') }} nodes</li>
                    @if (graphRun(); as ceiling) {
                        <li>Whole Dataset tools: up to {{ ceiling.toLocaleString('en') }} nodes</li>
                    }
                </ul>
            </div>
        }
    `,
})
export class LinkAnalysisCapsComponent {
    readonly projection = input.required<number>();
    readonly analysis = input.required<number>();
    /** The server's hard ceiling on a graph run; null until the algorithm catalogue answers. */
    readonly graphRun = input<number | null>(null);
    readonly open = signal(false);
    private readonly toggle = viewChild.required<ElementRef<HTMLButtonElement>>('toggle');

    /** Escape closes the popover and returns focus to its button (it goes no further than this component). */
    close(e: Event): void {
        if (!this.open()) return;
        e.stopPropagation();
        this.open.set(false);
        this.toggle().nativeElement.focus();
    }
}
