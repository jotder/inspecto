import { Component, inject, signal, ChangeDetectionStrategy, DestroyRef } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { RouterLink } from '@angular/router';
import { JobRunRow, JobRunSource, JobsService } from 'app/inspecto/api';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { fmtDuration } from './jobs.component';

/** Single-run detail — every field of one durable {@link JobRunRow} from the reporting projection (T27). */
@Component({
    selector: 'app-job-run-detail-dialog',
    standalone: true,
    imports: [MatDialogModule, MatButtonModule, StatusBadgeComponent, RouterLink],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title class="flex items-center gap-3">
            <inspecto-status-badge [value]="data.status" />
            <span class="font-mono text-base">{{ data.job }}</span>
        </h2>
        <mat-dialog-content class="space-y-4">
            <div class="grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
                <div>
                    <span class="text-secondary font-medium">run id:</span>
                    <span class="font-mono text-xs">{{ data.runId }}</span>
                </div>
                <div><span class="text-secondary font-medium">type:</span> {{ data.type }}</div>
                <div>
                    <span class="text-secondary font-medium">trigger:</span>
                    {{ data.trigger }}
                </div>
                <div>
                    <span class="text-secondary font-medium">duration:</span>
                    {{ fmt(data.durationMs) }}
                </div>
                <div>
                    <span class="text-secondary font-medium">started:</span>
                    <span class="font-mono text-xs">{{ data.startTime || '—' }}</span>
                </div>
                <div>
                    <span class="text-secondary font-medium">ended:</span>
                    <span class="font-mono text-xs">{{ data.endTime || '—' }}</span>
                </div>
            </div>
            <!-- X2 cross-lane provenance: the Consignments this at-rest run READ. Present only on the
                 single-run read and only for runs that read a store; a source whose producer pipeline the
                 registry did not know is listed but NOT linked — the UI opens a Consignment by its
                 (pipeline, id) pair and never guesses the pipeline from the id. -->
            @if (derivedFrom().length) {
                <div data-testid="derived-from">
                    <div class="text-secondary mb-1 text-sm font-medium">
                        derived from ({{ derivedFrom().length }} Consignment{{ derivedFrom().length === 1 ? '' : 's' }})
                    </div>
                    <ul class="space-y-1 text-xs">
                        @for (src of derivedFrom(); track src.consignmentId) {
                            <li class="flex flex-wrap items-baseline gap-x-2">
                                <span class="font-mono">{{ src.consignmentId }}</span>
                                <span class="text-secondary">from {{ src.tableName }}</span>
                                @if (src.pipeline) {
                                    <a
                                        class="text-primary hover:underline"
                                        [routerLink]="['/runs', src.pipeline]"
                                        mat-dialog-close
                                        >open {{ src.pipeline }}</a
                                    >
                                }
                            </li>
                        }
                    </ul>
                </div>
            }
            <div>
                <div class="text-secondary mb-1 text-sm font-medium">message</div>
                <div class="font-mono text-xs break-all">
                    {{ data.message || '— (none)' }}
                </div>
            </div>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button mat-dialog-close>Close</button>
        </mat-dialog-actions>
    `,
})
export class JobRunDetailDialog {
    readonly data = inject<JobRunRow>(MAT_DIALOG_DATA);
    private readonly api = inject(JobsService);
    /** Starts as the list row's (absent) value; filled once the by-id read returns — the list never carries it. */
    readonly derivedFrom = signal<JobRunSource[]>(this.data.derivedFrom ?? []);
    fmt = fmtDuration;

    constructor() {
        // Enrich only: the dialog is usable from the row at once, and a failed read leaves it as is.
        this.api
            .run(this.data.runId)
            .pipe(takeUntilDestroyed(inject(DestroyRef)))
            .subscribe({
                next: (run) => {
                    if (run.derivedFrom?.length) this.derivedFrom.set(run.derivedFrom);
                },
                error: () => undefined,
            });
    }
}
