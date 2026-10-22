import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { termHelp } from './term-help';

let nextId = 0;

/**
 * A product term with its plain explanation one click away (LA-ONBOARD-1 UC-15 rule 3). Renders the word, a small
 * "?" button (its tooltip and accessible name carry the explanation) and, once pressed, the explanation inline under the
 * word's sentence. Deterministic (`TERM_HELP`), no server call, so it works on every edition. Use it at a term's FIRST
 * appearance on a screen, not at every mention.
 *
 * @example <inspecto-term term="dataset" /> · <inspecto-term term="entity-type" label="Entity Types" />
 */
@Component({
    selector: 'inspecto-term',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatIconModule, MatTooltipModule],
    host: { class: 'inline' },
    template: `
        @if (help(); as h) {
            <span class="whitespace-nowrap"
                >{{ word()
                }}<button
                    type="button"
                    class="text-secondary hover:text-primary focus-visible:outline-primary ml-0.5 inline-flex h-4 w-4 items-center justify-center rounded-full align-text-top focus-visible:outline-2"
                    data-testid="term-help"
                    [matTooltip]="h.text"
                    [attr.aria-label]="'What is ' + article() + h.term + '?'"
                    [attr.aria-expanded]="open()"
                    [attr.aria-controls]="textId"
                    (click)="open.set(!open())"
                >
                    <mat-icon
                        class="icon-size-4"
                        svgIcon="heroicons_outline:question-mark-circle"
                        aria-hidden="true"
                    ></mat-icon></button
            ></span>
            <span
                class="text-secondary text-sm"
                role="note"
                data-testid="term-help-text"
                [id]="textId"
                [hidden]="!open()"
            >
                ({{ h.text }})</span
            >
        } @else {
            {{ word() }}
        }
    `,
})
export class TermHelpComponent {
    /** The term (`TERM_HELP` key). */
    readonly term = input.required<string>();
    /** The word as the sentence needs it ("Datasets", "Entity Types"); the canonical term by default. */
    readonly label = input<string>('');

    protected readonly help = computed(() => termHelp(this.term()));
    protected readonly word = computed(() => this.label() || this.help()?.term || this.term());
    protected readonly article = computed(() => {
        const t = this.help()?.term ?? '';
        return /^[AEIOU]/i.test(t) ? 'an ' : 'a ';
    });
    protected readonly open = signal(false);
    protected readonly textId = `inspecto-term-help-${nextId++}`;
}
