import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AiExplainDialog } from './ai-explain.dialog';

/**
 * The read-only "explain this screen" affordance (AGT-6a A4) — one icon button a pane drops into its
 * header, next to its own actions.
 *
 * It exists so breadth is cheap: adopting it is one element and a list of canonical terms, with no
 * per-pane state, no gating decision, and no layout risk (everything it renders lives in a dialog, so
 * it cannot disturb the header row it sits in). The pane declares the terms; see
 * {@link AiExplainDialog} for why they are declared rather than typed, and why this is a sibling of
 * `<inspecto-ai-assist>` rather than a mode of it.
 *
 * @example
 * <inspecto-ai-explain screen="Pipelines" [terms]="['Pipeline', 'Step', 'Trigger']" />
 */
@Component({
    selector: 'inspecto-ai-explain',
    standalone: true,
    imports: [MatButtonModule, MatIconModule, MatTooltipModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <button
            mat-icon-button
            type="button"
            [attr.aria-label]="'About ' + screen()"
            [matTooltip]="'About ' + screen()"
            (click)="open()"
        >
            <mat-icon svgIcon="heroicons_outline:question-mark-circle" />
        </button>
    `,
})
export class AiExplainComponent {
    private dialog = inject(MatDialog);

    /** The screen's name, as the operator sees it in the `<h1>` ("Pipelines", "Expectations"). */
    readonly screen = input.required<string>();

    /** The canonical terms this screen is built on — `docs/GLOSSARY.md` spellings, never synonyms. */
    readonly terms = input.required<string[]>();

    open(): void {
        this.dialog.open(AiExplainDialog, { data: { screen: this.screen(), terms: this.terms() } });
    }
}
