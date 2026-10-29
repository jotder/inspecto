import { ChangeDetectionStrategy, Component, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';

/**
 * **Caps in force** (the footer's left-most item, the counterpart of *Installed modules* at its right edge): the node caps
 * the server and this browser enforce, stated rather than hit silently. A small read-only popover that opens upward over
 * the canvas; it reads values the page already holds, so it makes no request.
 */
@Component({
    selector: 'inspecto-link-analysis-caps',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    // The footer hosts it at the left edge; the list is a popover anchored above its button, never in the footer's flow.
    host: { class: 'relative inline-block' },
    imports: [MatButtonModule],
    template: `
        <button mat-button data-testid="caps-toggle" (click)="open.set(!open())" [attr.aria-expanded]="open()">
            Limits
        </button>
        @if (open()) {
            <!-- Opens upward over the canvas: an opaque card above the footer, or the graph labels bleed through. -->
            <div
                class="absolute bottom-full left-0 z-30 mb-1 w-max max-w-[90vw] rounded border p-2 text-sm"
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
}
