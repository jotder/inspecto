import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { LockedActionComponent } from '@inspecto/core/components/locked-action.component';
import { LA_FEATURES, investigationsLockedReason } from '@inspecto/link-analysis/la-host';

/**
 * What to do with a ranking (Centrality, Suspicion score, similar nodes) - the hint and the one action under
 * the table. Results were a dead end for a new analyst (operator 2026-10-10): a table, then nothing.
 * The action only QUEUES the top results; the Investigation tab asks for the title/purpose and creates nothing
 * until the analyst presses Start there. Without the Investigation surface (the Explorer level: Basic, or a sign-in
 * install without the Investigations add-on - `LA_FEATURES.investigations` off) the action is shown LOCKED with the
 * server's one-line reason (`LA-ONBOARD-1` OB-10), and the hint offers only what exists (LA-ONBOARD-FIXES-1, F-10).
 */
@Component({
    selector: 'inspecto-link-analysis-result-next',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatIconModule, LockedActionComponent],
    host: { class: 'mt-2 block max-w-md' },
    template: `
        @if (investigations()) {
            <p class="text-secondary m-0 text-xs" data-testid="result-next-hint">
                What to do next: click a row to find that node on the canvas. If these look like leads, start an
                Investigation to record what you do from here - the top {{ count() }} are queued as seed entities, and
                you confirm the title and purpose before anything is created.
            </p>
            <button mat-stroked-button type="button" class="mt-1" (click)="start.emit()">
                <mat-icon svgIcon="heroicons_outline:magnifying-glass-circle"></mat-icon>
                Start an Investigation from the top results
            </button>
        } @else {
            <p class="text-secondary m-0 text-xs" data-testid="result-next-hint">
                What to do next: click a row to find that node on the canvas.
            </p>
            <inspecto-locked-action
                class="mt-1"
                label="Start an Investigation from the top results"
                [reason]="lockedReason()"
            />
        }
    `,
})
export class LinkAnalysisResultNextComponent {
    /** Whether this edition includes the Investigation surface (the action below the ranking needs it). */
    private readonly features = inject(LA_FEATURES);
    protected readonly investigations = this.features.investigations;
    /** Why the action is locked on the Explorer level (`/bootstrap` `featureReasons.investigations`). */
    protected readonly lockedReason = computed(() => investigationsLockedReason(this.features));
    /** How many top results the action carries over. */
    readonly count = input.required<number>();
    readonly start = output<void>();
}
