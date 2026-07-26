import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Observable, catchError, forkJoin, map, of } from 'rxjs';
import { AgentService } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';

/** What the pane must say to open the dialog: what is being explained, and how to address it. */
export interface AiStatusData {
    /** The thing whose state is being explained, as the operator sees it ("orders", "INC-42"). */
    label: string;
    /**
     * The pipeline to read live state for. Optional: a pane with no pipeline (an Incident, a Signal)
     * passes only a `correlationId` and gets the timeline half alone.
     */
    pipelineId?: string;
    /**
     * A correlation id to reconstruct the causal chain for. When present this is preferred over the
     * windowed timeline — it is the exact chain, not everything that happened nearby.
     */
    correlationId?: string;
    /** How far back the windowed timeline reaches when there is no `correlationId`. Default 24h. */
    windowMinutes?: number;
}

/** One row of the "what happened" list, normalised across the two timeline tools. */
export interface StatusTimelineEntry {
    at: string;
    /** `signal` / `job-run` / `config-change` from `timeline_build`, or the signal type from the chain. */
    kind: string;
    summary: string;
    severity?: string;
    /** Present only on the causal chain — the signal this one was caused by. */
    causedBy?: string | null;
}

/** Live pipeline state, as `status_get` reports it. */
interface PipelineStatus {
    name: string;
    paused: boolean;
    committedBatches: number;
}

interface SignalTimelineResult {
    correlationId: string;
    count: number;
    timeline: { signalId: string; at: string; type: string; severity?: string; message?: string; causedBy?: string | null }[];
}

interface BuiltTimelineResult {
    count: number;
    truncated: boolean;
    timeline: { at: string; kind: string; summary: string; ref: string; severity?: string }[];
}

const DEFAULT_WINDOW_MINUTES = 1440;
const ENTRY_LIMIT = 40;

/**
 * "Why is this red" — the status half of the read-only inline AI surface (AGT-6a A4-status).
 *
 * The sibling of {@link AiExplainDialog}, and deliberately a separate one. That dialog answers a
 * question about *documentation* (what does this word mean); this answers a question about
 * **deployment state** (what actually happened to this thing). The distinction is not cosmetic:
 *
 * - It needs **real entity ids**, so it is meaningful only on the operational panes — it is not the
 *   breadth win the vocabulary half was, and must not be swept onto every pane.
 * - Its answer is **not mockable honestly**: offline it reflects the mock store's own state, which is
 *   the only "deployment" that exists there. It never narrates a story the ledger does not contain.
 *
 * Like its sibling: no model in the loop (three deterministic non-mutating tools over
 * `POST /agent/tools/{name}`, so **no new backend capability**), no write path, and **not gated on
 * `canAuthorWorkbench()`** — reading why something is red is not an authoring act.
 *
 * The two halves degrade independently: a pipeline whose live status cannot be read still shows its
 * timeline, and vice versa. A 503 (module absent) explains itself once rather than per call.
 */
@Component({
    standalone: true,
    imports: [
        MatButtonModule,
        MatDialogModule,
        MatIconModule,
        MatProgressSpinnerModule,
        InspectoAlertComponent,
        StatusBadgeComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>What happened to {{ data.label }}</h2>
        <mat-dialog-content class="w-[38rem] max-w-full">
            @if (loading()) {
                <div class="flex items-center gap-3 py-4">
                    <mat-progress-spinner diameter="20" mode="indeterminate" />
                    <span class="text-secondary text-sm">Reading current state and recent activity…</span>
                </div>
            } @else if (unavailable()) {
                <inspecto-alert variant="info" title="AI assistance unavailable">
                    The intelligence module is not installed on this backend, so live state and the activity
                    timeline cannot be read. Everything else on this screen works as usual.
                </inspecto-alert>
            } @else {
                @if (status(); as s) {
                    <section class="mb-4">
                        <h3 class="mb-1 text-sm font-semibold">Current state</h3>
                        <div class="flex items-center gap-3 text-sm">
                            <inspecto-status-badge [value]="s.paused ? 'paused' : 'running'" />
                            <span class="text-secondary">
                                {{ s.committedBatches }} committed
                                {{ s.committedBatches === 1 ? 'batch' : 'batches' }}
                            </span>
                        </div>
                    </section>
                }

                <section>
                    <h3 class="mb-1 text-sm font-semibold">{{ timelineHeading() }}</h3>
                    @if (entries().length) {
                        <ol class="flex flex-col gap-2">
                            @for (entry of entries(); track entry.at + entry.summary) {
                                <li class="flex items-start gap-3 text-sm">
                                    <span class="text-secondary shrink-0 text-xs tabular-nums">
                                        {{ entry.at }}
                                    </span>
                                    @if (entry.severity) {
                                        <inspecto-status-badge [value]="entry.severity" />
                                    }
                                    <span>
                                        {{ entry.summary }}
                                        <span class="text-secondary text-xs">({{ entry.kind }})</span>
                                    </span>
                                </li>
                            }
                        </ol>
                        @if (truncated()) {
                            <p class="text-secondary mt-2 text-xs">
                                More activity than can be shown — narrow the question on the Signals pane.
                            </p>
                        }
                    } @else {
                        <!-- Honest silence: nothing recorded is a real answer, not an empty state to fill. -->
                        <p class="text-secondary text-sm">
                            Nothing was recorded for {{ data.label }} in this window.
                        </p>
                    }
                </section>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-flat-button color="primary" type="button" mat-dialog-close>Close</button>
        </mat-dialog-actions>
    `,
})
export class AiStatusDialog {
    private agent = inject(AgentService);
    readonly data = inject<AiStatusData>(MAT_DIALOG_DATA);

    readonly loading = signal(true);
    readonly status = signal<PipelineStatus | null>(null);
    readonly entries = signal<StatusTimelineEntry[]>([]);
    readonly truncated = signal(false);
    /** Latched when the intelligence module is absent (503) — explained once, not per call. */
    readonly unavailable = signal(false);

    constructor() {
        forkJoin({ status: this.readStatus(), entries: this.readTimeline() }).subscribe(({ status, entries }) => {
            this.loading.set(false);
            this.status.set(status);
            this.entries.set(entries.slice(0, ENTRY_LIMIT));
        });
    }

    /** The heading names which question was answered — the exact chain, or a time window. */
    timelineHeading(): string {
        return this.data.correlationId ? 'What led to this' : 'Recent activity';
    }

    private readStatus(): Observable<PipelineStatus | null> {
        if (!this.data.pipelineId) return of(null);
        return this.agent.runTool<PipelineStatus>('status_get', { pipelineId: this.data.pipelineId }).pipe(
            catchError((err: unknown) => {
                // 422 = this deployment has no such pipeline. Not an error worth shouting about here:
                // the timeline half may still answer the operator's actual question.
                this.isUnavailable(err);
                return of(null);
            }),
        );
    }

    private readTimeline(): Observable<StatusTimelineEntry[]> {
        return this.data.correlationId ? this.readCausalChain(this.data.correlationId) : this.readWindow();
    }

    /** The exact causal chain for one correlation id — preferred whenever the pane has one. */
    private readCausalChain(correlationId: string): Observable<StatusTimelineEntry[]> {
        return this.agent.runTool<SignalTimelineResult>('signal_timeline', { correlationId }).pipe(
            map((result) =>
                (result.timeline ?? []).map((s) => ({
                    at: s.at,
                    kind: s.type,
                    summary: s.message ?? s.type,
                    severity: s.severity,
                    causedBy: s.causedBy,
                })),
            ),
            catchError((err: unknown) => {
                this.isUnavailable(err);
                return of([]);
            }),
        );
    }

    /** Everything that happened in the window, filtered to this entity by the tool's own `focus`. */
    private readWindow(): Observable<StatusTimelineEntry[]> {
        const args: Record<string, unknown> = {
            sinceMinutes: this.data.windowMinutes ?? DEFAULT_WINDOW_MINUTES,
        };
        if (this.data.pipelineId) args['focus'] = this.data.pipelineId;
        return this.agent.runTool<BuiltTimelineResult>('timeline_build', args).pipe(
            map((result) => {
                this.truncated.set(result.truncated === true);
                // Newest first: the operator asking "why is this red" is asking about the latest state.
                return (result.timeline ?? [])
                    .map((e) => ({ at: e.at, kind: e.kind, summary: e.summary, severity: e.severity }))
                    .reverse();
            }),
            catchError((err: unknown) => {
                this.isUnavailable(err);
                return of([]);
            }),
        );
    }

    private isUnavailable(err: unknown): boolean {
        if ((err as { status?: number })?.status === 503) {
            this.unavailable.set(true);
            return true;
        }
        return false;
    }
}
