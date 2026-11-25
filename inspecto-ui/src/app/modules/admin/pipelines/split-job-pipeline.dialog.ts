import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { SplitJobPipelineResponse } from 'app/inspecto/api';

/**
 * JG3 — Two-file diff preview before splitting a `steps:` + `output_store:` Pipeline into an
 * onboarding Pipeline + a standalone Job Pipeline + its `on_pipeline` Job.
 */
@Component({
    selector: 'app-split-job-pipeline-dialog',
    standalone: true,
    imports: [MatDialogModule, MatButtonModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Split into Job Pipeline</h2>
        <mat-dialog-content class="max-h-[70vh] space-y-4 text-sm">
            <p class="text-secondary m-0">
                Moves the at-rest chain out of <strong>{{ data.onboardingPipeline.name }}</strong> into its own Job
                Pipeline <strong>{{ data.jobPipeline.name }}</strong> and creates Job
                <strong>{{ data.job.name }}</strong>.
            </p>

            <div>
                <div class="mb-1 font-mono text-xs font-semibold">{{ data.onboardingPipeline.path }} (updated)</div>
                <div class="grid grid-cols-1 gap-2 md:grid-cols-2">
                    <div>
                        <div class="text-secondary mb-1 text-xs uppercase">Before</div>
                        <pre class="max-h-56 overflow-auto rounded border p-2 font-mono text-xs" style="border-color: var(--gamma-border)">{{ data.onboardingPipeline.before }}</pre>
                    </div>
                    <div>
                        <div class="text-secondary mb-1 text-xs uppercase">After</div>
                        <pre class="max-h-56 overflow-auto rounded border p-2 font-mono text-xs" style="border-color: var(--gamma-border)">{{ data.onboardingPipeline.after }}</pre>
                    </div>
                </div>
            </div>

            <div>
                <div class="mb-1 font-mono text-xs font-semibold">{{ data.jobPipeline.path }} (new Job Pipeline)</div>
                <pre class="max-h-56 overflow-auto rounded border p-2 font-mono text-xs" style="border-color: var(--gamma-border)">{{ data.jobPipeline.after }}</pre>
            </div>

            <div>
                <div class="mb-1 font-mono text-xs font-semibold">{{ data.job.path }} (new Job)</div>
                <pre class="max-h-40 overflow-auto rounded border p-2 font-mono text-xs" style="border-color: var(--gamma-border)">{{ data.job.after }}</pre>
            </div>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button type="button" (click)="ref.close(false)">Cancel</button>
            <button mat-flat-button color="primary" type="button" (click)="ref.close(true)">Split &amp; Save</button>
        </mat-dialog-actions>
    `,
})
export class SplitJobPipelineDialogComponent {
    readonly ref = inject(MatDialogRef<SplitJobPipelineDialogComponent, boolean>);
    readonly data = inject<SplitJobPipelineResponse>(MAT_DIALOG_DATA);
}
