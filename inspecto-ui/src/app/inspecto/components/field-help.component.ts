import { ChangeDetectionStrategy, Component, input, viewChild } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltip, MatTooltipModule } from '@angular/material/tooltip';

/**
 * A field's description behind a small "?" after its label (operator ask 2026-11-22: forms read compact; experts,
 * regular users and agents rarely need the description, so it no longer takes a line under every field). The text
 * shows as a tooltip on hover, on keyboard focus and on a tap or click (a touch screen has no hover). The same look
 * as `<inspecto-term>`'s "?", so the two read as one idiom.
 *
 * It renders nothing for an empty text. The host keeps a visually hidden copy of the text for screen readers and
 * points its control's `aria-describedby` at it, so moving the line behind the icon takes nothing from them.
 *
 * @example <inspecto-field-help [text]="spec.help" [label]="spec.label" />
 */
@Component({
    selector: 'inspecto-field-help',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatIconModule, MatTooltipModule],
    host: { class: 'inline-flex shrink-0' },
    template: `
        @if (text()) {
            <button
                type="button"
                class="field-help text-secondary hover:text-primary inline-flex h-4 w-4 items-center justify-center rounded-full focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1"
                data-testid="field-help"
                [attr.data-help]="text()"
                [matTooltip]="text()"
                matTooltipPosition="above"
                [attr.aria-label]="'About ' + (label() || 'this field')"
                (click)="toggle($event)"
            >
                <mat-icon
                    class="icon-size-4"
                    svgIcon="heroicons_outline:question-mark-circle"
                    aria-hidden="true"
                ></mat-icon>
            </button>
        }
    `,
})
export class InspectoFieldHelpComponent {
    /** The description; nothing renders when it is empty. */
    readonly text = input<string | null | undefined>('');
    /** The field's label, for the button's accessible name ("About Join keys"). */
    readonly label = input<string>('');

    private readonly tooltip = viewChild(MatTooltip);

    /** A click or tap shows the text (a touch screen has no hover); it must not reach the row (that opens the editor). */
    protected toggle(e: Event): void {
        e.stopPropagation();
        this.tooltip()?.toggle();
    }
}
