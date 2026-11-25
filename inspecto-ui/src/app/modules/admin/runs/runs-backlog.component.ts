import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, JobBacklog, JobsService, LensService } from 'app/inspecto/api';

/**
 * Runs ▸ Backlog (DATED-REFERENCE-RUNS-1 P4): per-Job work ledger showing days waiting
 * and why, ready, running, stale, oldest waiting age, drain estimate, and operator controls
 * (Run now, Pause/Resume, Drain, Give up waiting).
 */
@Component({
    selector: 'app-runs-backlog',
    standalone: true,
    imports: [MatButtonModule, MatIconModule, MatTooltipModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="flex flex-col gap-4">
            <div class="flex items-center justify-between">
                <span class="text-sm text-gray-500">
                    Per-Job daily backlog over the Consignment output registry.
                </span>
                <button mat-stroked-button (click)="load()" [disabled]="loading()">
                    <mat-icon svgIcon="heroicons_outline:arrow-path"></mat-icon>
                    <span class="ml-1">Refresh backlog</span>
                </button>
            </div>

            @if (backlogs().length === 0 && !loading()) {
                <div class="rounded-lg border p-6 text-center text-sm text-gray-500" style="border-color: var(--gamma-border)">
                    No Jobs with <code>schedule.mode: by_day</code> configured.
                </div>
            }

            @for (job of backlogs(); track job.jobName) {
                <div class="rounded-lg border p-4" style="border-color: var(--gamma-border)">
                    <div class="flex flex-wrap items-center justify-between gap-2 pb-3 border-b" style="border-color: var(--gamma-border)">
                        <div>
                            <div class="font-semibold text-base">{{ job.jobName }}</div>
                            <div class="text-xs text-gray-500">
                                Stream: {{ job.inputStream ?? '—' }}
                                · Ready: <strong>{{ job.readyCount }}</strong>
                                · Waiting: <strong>{{ job.waitingCount }}</strong>
                                · Running: <strong>{{ job.runningCount }}</strong>
                                · Stale: <strong>{{ job.staleCount }}</strong>
                                @if (job.oldestWaitingAgeSeconds > 0) {
                                    · Oldest waiting: <strong>{{ formatDuration(job.oldestWaitingAgeSeconds) }}</strong>
                                }
                                · Est. drain: <strong>{{ formatDuration(job.estimatedDrainSeconds) }}</strong>
                            </div>
                        </div>
                        @if (lens.canOperateRuns()) {
                            <div class="flex items-center gap-2">
                                <button mat-stroked-button (click)="togglePause(job)">
                                    {{ job.paused ? 'Resume' : 'Pause' }}
                                </button>
                                <button mat-flat-button color="primary" (click)="drain(job)">
                                    Drain
                                </button>
                            </div>
                        }
                    </div>

                    @if (job.days.length === 0) {
                        <div class="pt-3 text-sm text-gray-500">All caught up — no pending or stale days.</div>
                    } @else {
                        <table class="w-full text-sm mt-2">
                            <thead>
                                <tr class="text-left text-xs text-gray-500 border-b" style="border-color: var(--gamma-border)">
                                    <th class="py-1.5 pr-3">Day</th>
                                    <th class="py-1.5 pr-3">Status</th>
                                    <th class="py-1.5 pr-3">Files</th>
                                    <th class="py-1.5 pr-3">Reason / Version</th>
                                    @if (lens.canOperateRuns()) {
                                        <th class="py-1.5 text-right">Actions</th>
                                    }
                                </tr>
                            </thead>
                            <tbody>
                                @for (d of job.days; track d.day) {
                                    <tr class="border-b last:border-b-0" style="border-color: var(--gamma-border)">
                                        <td class="py-2 pr-3 font-mono">{{ d.day }}</td>
                                        <td class="py-2 pr-3">
                                            <span class="px-2 py-0.5 rounded text-xs font-medium"
                                                  [class.bg-amber-100]="d.status === 'WAITING'"
                                                  [class.text-amber-800]="d.status === 'WAITING'"
                                                  [class.bg-green-100]="d.status === 'READY'"
                                                  [class.text-green-800]="d.status === 'READY'"
                                                  [class.bg-red-100]="d.status === 'FAILED'"
                                                  [class.text-red-800]="d.status === 'FAILED'">
                                                {{ d.status }}
                                            </span>
                                            @if (d.stale) {
                                                <span class="ml-1 px-1.5 py-0.5 rounded text-xs bg-orange-100 text-orange-800">STALE</span>
                                            }
                                        </td>
                                        <td class="py-2 pr-3">{{ d.pendingFiles }}</td>
                                        <td class="py-2 pr-3 text-xs text-gray-600">
                                            {{ d.waitingReason ?? d.staleReason ?? d.referenceVersions ?? '—' }}
                                        </td>
                                        @if (lens.canOperateRuns()) {
                                            <td class="py-2 text-right whitespace-nowrap">
                                                <button mat-button (click)="runDay(job.jobName, d.day)">Run now</button>
                                                @if (d.status === 'WAITING') {
                                                    <button mat-button color="warn" (click)="giveUp(job.jobName, d.day)">
                                                        Give up waiting
                                                    </button>
                                                }
                                            </td>
                                        }
                                    </tr>
                                }
                            </tbody>
                        </table>
                    }
                </div>
            }
        </div>
    `,
})
export class RunsBacklogComponent implements OnInit {
    private jobsApi = inject(JobsService);
    private toastr = inject(ToastrService);
    protected lens = inject(LensService);

    readonly backlogs = signal<JobBacklog[]>([]);
    readonly loading = signal(false);

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.jobsApi.backlog().subscribe({
            next: (rows) => {
                this.backlogs.set(rows ?? []);
                this.loading.set(false);
            },
            error: (err) => {
                this.loading.set(false);
                this.toastr.error(apiErrorMessage(err, 'Could not load backlog'));
            },
        });
    }

    togglePause(job: JobBacklog): void {
        const obs = job.paused
            ? this.jobsApi.resumeBacklog(job.jobName)
            : this.jobsApi.pauseBacklog(job.jobName);
        obs.subscribe({
            next: () => {
                this.toastr.success(`${job.jobName} ${job.paused ? 'resumed' : 'paused'}`);
                this.load();
            },
            error: (err) => this.toastr.error(apiErrorMessage(err, 'Failed to update backlog state')),
        });
    }

    drain(job: JobBacklog): void {
        this.jobsApi.drainBacklog(job.jobName, true).subscribe({
            next: (res) => {
                this.toastr.success(`Drained ${res.submitted} day(s) for ${job.jobName}`);
                this.load();
            },
            error: (err) => this.toastr.error(apiErrorMessage(err, 'Drain failed')),
        });
    }

    runDay(jobName: string, day: string): void {
        this.jobsApi.runBacklogDay(jobName, day, true).subscribe({
            next: () => {
                this.toastr.success(`Triggered ${jobName} for ${day}`);
                this.load();
            },
            error: (err) => this.toastr.error(apiErrorMessage(err, `Failed to run ${day}`)),
        });
    }

    giveUp(jobName: string, day: string): void {
        this.jobsApi.giveUpWaiting(jobName, day).subscribe({
            next: () => {
                this.toastr.info(`Skipped waiting for ${jobName} (${day})`);
                this.load();
            },
            error: (err) => this.toastr.error(apiErrorMessage(err, `Failed to skip ${day}`)),
        });
    }

    formatDuration(seconds: number): string {
        if (!seconds || seconds <= 0) return '0s';
        if (seconds < 60) return `${seconds}s`;
        const mins = Math.floor(seconds / 60);
        if (mins < 60) return `${mins}m`;
        const hrs = Math.floor(mins / 60);
        return `${hrs}h ${mins % 60}m`;
    }
}
