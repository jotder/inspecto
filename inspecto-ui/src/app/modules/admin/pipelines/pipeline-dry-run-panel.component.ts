import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { PipelineDryRunResult, PipelinesService, apiErrorMessage } from 'app/inspecto/api';

/**
 * Dry-run panel for the pipeline editor: runs a bounded JSON sample through the transform→sink
 * subgraph (no write) and renders the per-node/per-sink row counts. Extracted out of
 * {@link PipelineEditorComponent} (BACKLOG §4) — self-contained state (sample text, result, error),
 * the host only owns the open/closed toggle and which pipeline id is selected.
 */
@Component({
    selector: 'app-pipeline-dry-run-panel',
    standalone: true,
    imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule],
    templateUrl: './pipeline-dry-run-panel.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PipelineDryRunPanelComponent {
    private api = inject(PipelinesService);
    private fb = inject(FormBuilder);

    readonly pipelineId = input<string | null>(null);

    /**
     * The rows the tab's sample thread parsed, if any. The sample strip promises the captured sample
     * "follows you through the definition, so every test shows your data" — without this the dry-run
     * was the one test that broke that promise, asking a builder who had just parsed their own file to
     * hand-type JSON rows. Passed in (never injected): the thread belongs to the TAB.
     */
    readonly capturedRows = input<Record<string, unknown>[] | null>(null);

    readonly sampleText = this.fb.control('[\n  {}\n]', { nonNullable: true });

    /** Fill the sample box with the rows the parse step produced. Explicit — it overwrites what is typed. */
    useCapturedSample(): void {
        const rows = this.capturedRows();
        if (!rows?.length) return;
        this.sampleText.setValue(JSON.stringify(rows, null, 2));
    }

    /**
     * The outcome, STAMPED with the pipeline it came from. The panel is mounted once and outlives the
     * tab switch, so a plain signal left "sink → fixed_width_ledger, 2 rows" standing under another
     * pipeline's name. ⚠ Scoping it by stamp rather than clearing it in an effect on purpose: an effect
     * flushes on Angular's schedule, so a clear could land AFTER a run and wipe a fresh result. The
     * typed sample is the operator's own text and deliberately survives the switch.
     */
    private readonly outcome = signal<{ id: string | null; result?: PipelineDryRunResult; error?: string } | null>(
        null,
    );
    private readonly mine = computed(() => (this.outcome()?.id === this.pipelineId() ? this.outcome() : null));
    readonly result = computed(() => this.mine()?.result ?? null);
    readonly error = computed(() => this.mine()?.error ?? null);

    run(): void {
        const id = this.pipelineId();
        if (!id) return;
        let rows: Record<string, unknown>[];
        try {
            const parsed = JSON.parse(this.sampleText.value);
            rows = Array.isArray(parsed) ? parsed : [parsed];
        } catch {
            this.outcome.set({ id, error: 'Sample must be valid JSON (an array of row objects)' });
            return;
        }
        this.outcome.set({ id });
        this.api.dryRunAuthored(id, rows).subscribe({
            next: (result) => this.outcome.set({ id, result }),
            error: (err) => this.outcome.set({ id, error: apiErrorMessage(err, 'Dry-run failed') }),
        });
    }
}
