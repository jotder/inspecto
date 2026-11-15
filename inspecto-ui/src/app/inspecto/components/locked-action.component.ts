import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

let nextId = 0;

/**
 * An action this installation does not include, shown LOCKED instead of hidden or left to fail (`LA-ONBOARD-1` OB-10,
 * operator answer A-11: "locked with a reason inside screens; the navigation stays lean"). It renders the action's own
 * button, disabled but still focusable (`aria-disabled`, so a keyboard or screen-reader user finds it), and under it ONE
 * visible line saying why and which edition or add-on has it. The line is the button's description (`aria-describedby`).
 *
 * The reason comes from the server: `/bootstrap` `featureReasons[featureId]` (`SessionService.featureReasons`, or
 * `LA_FEATURES.featureReasons` inside Link Analysis). Whole pages an edition lacks stay out of the navigation instead.
 * Presentational only: no HTTP, no click output — there is nothing to do.
 *
 * @example <inspecto-locked-action label="Start an Investigation" [reason]="why()" />
 */
@Component({
    selector: 'inspecto-locked-action',
    standalone: true,
    imports: [MatButtonModule, MatIconModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: { class: 'inline-flex max-w-full flex-col items-start gap-1' },
    // A long action name wraps inside the width it is given instead of running off a phone (LA-PHONE-OVERFLOW-1): the
    // shared button style keeps a label on one line at a fixed height.
    styles: `
        button {
            max-width: 100%;
            height: auto;
            min-height: var(--mat-button-outlined-container-height, 40px);
            padding-block: 0.375rem;
            text-align: start;
        }
        button ::ng-deep .mdc-button__label {
            white-space: normal;
        }
    `,
    template: `
        <button
            mat-stroked-button
            type="button"
            data-testid="locked-action"
            [disabled]="true"
            [disabledInteractive]="true"
            [attr.aria-describedby]="reasonId"
        >
            <mat-icon svgIcon="heroicons_outline:lock-closed"></mat-icon>
            {{ label() }}
        </button>
        <p class="text-secondary m-0 text-xs" data-testid="locked-reason" [id]="reasonId">{{ reason() }}</p>
    `,
})
export class LockedActionComponent {
    /** The action's own words, as the unlocked button would say them ("Start an Investigation"). */
    readonly label = input.required<string>();
    /** One line: why it is locked and which edition or add-on has it (`featureReasons[featureId]`). */
    readonly reason = input.required<string>();
    protected readonly reasonId = `inspecto-locked-reason-${nextId++}`;
}
