import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** One row of a server line diff (`PipelineHistory.diff`): kept, removed or added text. */
export interface LineDiffLine {
    op: 'context' | 'remove' | 'add';
    text: string;
}

/** The line diff the server computes — Pipeline config history and Pending Changes share it. */
export interface LineDiff {
    added: number;
    removed: number;
    /** The middle was too large to tabulate, so it is shown as one replacement — correct, not minimal. */
    coarse?: boolean;
    lines: LineDiffLine[];
}

/**
 * Renders a server-computed {@link LineDiff} (`PipelineHistory.diff`, reused by the Pending Change diff):
 * removed lines as `<del>`, added as `<ins>`, the rest as context, in a scrollable monospaced region.
 * Presentational only — the host fetches the diff and writes any summary line above it. Extracted from the
 * Pipeline config-history dialog (`ASSURE-MAKER-CHECKER-1` S4) so both surfaces draw a change the same way.
 */
@Component({
    selector: 'inspecto-line-diff',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="diff overflow-auto rounded border font-mono text-xs" role="region" [attr.aria-label]="label()">
            @for (l of diff().lines; track $index) {
                @switch (l.op) {
                    @case ('remove') {
                        <del class="line block text-red-700 no-underline dark:text-red-400"
                            ><span aria-hidden="true">- </span>{{ l.text }}</del
                        >
                    }
                    @case ('add') {
                        <ins class="line block text-green-700 no-underline dark:text-green-400"
                            ><span aria-hidden="true">+ </span>{{ l.text }}</ins
                        >
                    }
                    @default {
                        <span class="line text-secondary block"
                            ><span aria-hidden="true">&nbsp; </span>{{ l.text }}</span
                        >
                    }
                }
            }
        </div>
    `,
    styles: [
        `
            .diff {
                border-color: var(--gamma-border);
                max-height: 60vh;
            }
            .line {
                white-space: pre;
                padding: 0 0.5rem;
            }
        `,
    ],
})
export class InspectoLineDiffComponent {
    readonly diff = input.required<LineDiff>();
    readonly label = input('Diff');
}
