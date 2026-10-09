import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

/**
 * What to do with a ranking (Centrality, Suspicion score, similar nodes) - the hint and the one action under
 * the table. Results were a dead end for a new analyst (operator 2026-10-10): a table, then nothing.
 * The action only QUEUES the top results; the Investigation tab asks for the title/purpose and creates nothing
 * until the analyst presses Start there.
 */
@Component({
    selector: 'inspecto-link-analysis-result-next',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatIconModule],
    host: { class: 'mt-2 block max-w-md' },
    template: `
        <p class="text-secondary m-0 text-xs" data-testid="result-next-hint">
            What to do next: click a row to find that node on the canvas. If these look like leads, start an
            Investigation to record what you do from here - the top {{ count() }} are queued as seed entities, and you
            confirm the title and purpose before anything is created.
        </p>
        <button mat-stroked-button type="button" class="mt-1" (click)="start.emit()">
            <mat-icon svgIcon="heroicons_outline:magnifying-glass-circle"></mat-icon>
            Start an Investigation from the top results
        </button>
    `,
})
export class LinkAnalysisResultNextComponent {
    /** How many top results the action carries over. */
    readonly count = input.required<number>();
    readonly start = output<void>();
}
