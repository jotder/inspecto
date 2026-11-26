import {
    ChangeDetectionStrategy,
    Component,
    DestroyRef,
    computed,
    effect,
    inject,
    input,
    output,
    signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { ToastrService } from 'ngx-toastr';
import { catchError, forkJoin, map, of, switchMap, takeWhile, timer } from 'rxjs';
import { apiErrorMessage, needsPermission } from 'app/inspecto/api/api-base';
import { JobsService } from 'app/inspecto/api/jobs.service';
import { LensService } from 'app/inspecto/api/lens.service';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { scanJobName } from 'app/inspecto/insights/insight-profile-form';

/** The Job Type that writes a Profile's Insights. */
export const INSIGHT_SCAN_TYPE = 'insight.scan';
/** How long the button follows a run before it stops waiting (the run itself goes on). */
const FOLLOW_MS = 120_000;
const POLL_MS = 2_000;

/**
 * **Run scan now** on the Insight Profile screen (BI-INSIGHTS-1): finds the `insight.scan` Job whose `profile`
 * parameter names this Profile and fires it through `POST /jobs/{name}/trigger` (canOperateRuns). With no such Job it
 * offers to create one through `POST /jobs` (canAuthorWorkbench), after a confirmation. Disabled with the reason
 * otherwise. Follows the run and emits `(finished)` so the feed reloads.
 */
@Component({
    selector: 'app-insight-scan-button',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule],
    template: `
        @if (state() === 'loading') {
            <span class="text-secondary text-xs" role="status">Looking for the scan Job…</span>
        } @else if (job(); as name) {
            <button
                mat-stroked-button
                type="button"
                data-testid="run-scan"
                [disabled]="!lens.canOperateRuns() || running()"
                [attr.title]="lens.canOperateRuns() ? 'Runs the Job ' + name : runReason"
                (click)="run(name)"
            >
                {{ running() ? 'Scanning…' : 'Run scan now' }}
            </button>
        } @else {
            <button
                mat-stroked-button
                type="button"
                data-testid="create-scan"
                [disabled]="!lens.canAuthorWorkbench() || state() === 'error'"
                [attr.title]="createTitle()"
                (click)="create()"
            >
                Create a scan Job…
            </button>
        }
        @if (!lens.canOperateRuns() && job()) {
            <span class="text-secondary ml-2 text-xs">{{ runReason }}</span>
        }
        @if (!job() && state() === 'ready' && !lens.canAuthorWorkbench()) {
            <span class="text-secondary ml-2 text-xs">No insight.scan Job names this Profile. {{ createReason }}</span>
        }
        @if (message(); as m) {
            <span class="text-secondary ml-2 text-xs" role="status" data-testid="scan-status">{{ m }}</span>
        }
    `,
})
export class InsightScanButtonComponent {
    readonly lens = inject(LensService);
    private jobs = inject(JobsService);
    private toastr = inject(ToastrService);
    private confirm = inject(InspectoConfirmService);
    private destroyRef = inject(DestroyRef);

    readonly profile = input.required<string>();
    /** A scan run this button started has ended (succeeded or failed). */
    readonly finished = output<void>();

    readonly runReason = needsPermission('canOperateRuns');
    readonly createReason = needsPermission('canAuthorWorkbench');
    readonly state = signal<'loading' | 'ready' | 'error'>('loading');
    readonly job = signal<string | null>(null);
    readonly running = signal(false);
    readonly message = signal<string | null>(null);
    readonly createTitle = computed(() =>
        this.state() === 'error'
            ? 'The Jobs could not be read.'
            : this.lens.canAuthorWorkbench()
              ? 'No insight.scan Job names this Profile yet.'
              : this.createReason,
    );

    constructor() {
        effect(() => this.find(this.profile()));
    }

    /** `GET /jobs` lists no parameters, so each `insight.scan` Job is read for its `profile`. 404 = no Jobs at all. */
    private find(profile: string): void {
        this.state.set('loading');
        this.job.set(null);
        this.message.set(null);
        this.jobs
            .list()
            .pipe(
                catchError((e) => (e?.status === 404 ? of([]) : Promise.reject(e))),
                switchMap((all) => {
                    const scans = all.filter((j) => j.type === INSIGHT_SCAN_TYPE);
                    if (!scans.length) return of(null);
                    return forkJoin(scans.map((j) => this.jobs.get(j.name).pipe(catchError(() => of(null))))).pipe(
                        map((details) => details.find((d) => d?.params?.['profile'] === profile)?.name ?? null),
                    );
                }),
                takeUntilDestroyed(this.destroyRef),
            )
            .subscribe({
                next: (name) => {
                    this.job.set(name);
                    this.state.set('ready');
                },
                error: () => this.state.set('error'),
            });
    }

    run(name: string): void {
        this.running.set(true);
        this.message.set(null);
        this.jobs.trigger(name).subscribe({
            next: ({ runId }) => this.follow(runId),
            error: (e) => {
                this.running.set(false);
                this.toastr.error(apiErrorMessage(e, 'The scan did not start.'));
            },
        });
    }

    /** Poll the run until it ends; the button stops waiting after {@link FOLLOW_MS}, the run itself goes on. */
    private follow(runId: string): void {
        let last = '';
        timer(POLL_MS, POLL_MS)
            .pipe(
                switchMap(() => this.jobs.run(runId).pipe(catchError(() => of(null)))),
                map((r) => (last = String(r?.status ?? last))),
                takeWhile((s) => s !== 'SUCCESS' && s !== 'FAILED' && s !== 'SKIPPED', true),
                takeWhile((_, i) => (i + 1) * POLL_MS < FOLLOW_MS, true),
                takeUntilDestroyed(this.destroyRef),
            )
            .subscribe({
                complete: () => {
                    this.running.set(false);
                    if (last === 'SUCCESS') this.message.set('The scan finished; the Insights below are the new run.');
                    else if (last === 'FAILED') this.message.set('The scan failed; the Jobs screen has its log.');
                    else if (last === 'SKIPPED') this.message.set('The scan was skipped; the Jobs screen says why.');
                    else this.message.set('The scan is still running; refresh the Insights later.');
                    this.finished.emit();
                },
            });
    }

    async create(): Promise<void> {
        const profile = this.profile();
        const name = scanJobName(profile);
        const ok = await this.confirm.confirm(
            `Create the Job "${name}" (type insight.scan, profile = ${profile})? It runs when you press Run scan now; ` +
                'add a schedule on the Jobs screen.',
            'Create a scan Job?',
        );
        if (!ok) return;
        this.jobs.create({ name, type: INSIGHT_SCAN_TYPE, enabled: true, params: { profile } }).subscribe({
            next: (j) => {
                this.job.set(j.name || name);
                this.toastr.success(`Job "${j.name || name}" created`);
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, 'The scan Job was not created.')),
        });
    }
}
