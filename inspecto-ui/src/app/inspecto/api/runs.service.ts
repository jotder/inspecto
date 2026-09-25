import { Injectable, inject } from '@angular/core';
import { JobRunRow } from './jobs.service';
import { HttpClient } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { apiErrorMessage, apiUrl, toParams } from './api-base';
import { visibleDelay } from './auto-refresh';
import {
    AuditRow,
    DrainResult,
    RunResult,
    RunView,
    ConsignmentAuditReport,
    ReportWindow,
    InboxStatus,
    RejectedRows,
    ReplayRejectsResult,
} from './models';

/**
 * The operator-facing message for a refused `POST /runs/{name}/replay-rejects`. Each refusal names its
 * NEXT step, since the server's own message says only what failed: 409 = already replayed (or one in
 * flight), 422 = this file's rejects cannot be replayed, 403 = refused (a missing capability, or a
 * non-bare file name). The server's reason is appended whenever it sent one.
 */
export function replayRejectsErrorMessage(err: unknown, file: string): string {
    const status = (err as { status?: number } | null)?.status;
    const reason = apiErrorMessage(err, '');
    const why = reason ? ` (${reason})` : '';
    switch (status) {
        case 409:
            return `Not replayed: the rejected records of "${file}" were already replayed from this reject file, or a replay is still running — replaying again would land them twice${why}.`;
        case 422:
            return `The rejected records of "${file}" cannot be replayed${why}.`;
        case 403:
            return `Replay refused: it needs the canOperateRuns capability and a bare file name${why}.`;
        default:
            return apiErrorMessage(err, `Replay failed for "${file}"`);
    }
}

/**
 * The operator-facing message for a refused `POST /runs/{name}/retries/retry-now|cancel` (X1). As with
 * {@link replayRejectsErrorMessage}, each refusal names its NEXT step and the server's reason is appended.
 * A 409 has three distinct causes the server tells apart only in its reason text — the pipeline is
 * mid-cycle (try again), it keeps no retry state (nothing to act on), or the file is already quarantined
 * (its fate is decided) — so the reason picks the prefix. 404 = no retry record / the file left the inbox.
 */
export function commitRetryErrorMessage(err: unknown, file: string, action: 'retry-now' | 'cancel'): string {
    const status = (err as { status?: number } | null)?.status;
    const reason = apiErrorMessage(err, '');
    const why = reason ? ` (${reason})` : '';
    const verb = action === 'cancel' ? 'Not cancelled' : 'Not retried';
    switch (status) {
        case 409:
            if (/running a cycle/i.test(reason))
                return `${verb}: the pipeline is processing a cycle right now and nothing was changed — try again when it finishes${why}.`;
            if (/no retry state/i.test(reason))
                return `${verb}: this pipeline keeps no retry state (no dirs.status_dir), so there is nothing to act on${why}.`;
            if (/already quarantined/i.test(reason))
                return `${verb}: "${file}" is already quarantined — its fate is decided and it is not retried${why}.`;
            return `${verb}: the retry record of "${file}" could not be acted on${why}.`;
        case 404:
            return `${verb}: "${file}" has no retry record any more, or is no longer in the inbox — reload the list${why}.`;
        case 403:
            return `${verb}: it needs the canOperateRuns capability and a path inside the poll directory${why}.`;
        default:
            return apiErrorMessage(err, `${action === 'cancel' ? 'Cancel' : 'Retry now'} failed for "${file}"`);
    }
}

/** One file waiting on a bounded COMMIT retry (`GET /runs/{name}/retries`). */
export interface CommitRetryRow {
    /** Poll-relative path — the key retry-now / cancel take (never a batch id). */
    file: string;
    attempts: number;
    firstFailedAt: string | null;
    lastFailedAt: string | null;
    /** ISO instant; blank/absent ⇒ due now. */
    nextRetryAt: string | null;
    due: boolean;
    lastError: string | null;
    inInbox: boolean;
    /** false ⇒ the sidecar could not be parsed; it is listed rather than failing the read. */
    readable: boolean;
}

/**
 * A pipeline's COMMIT retry queue. ⚠ `keepsRetryState: false` is NOT an empty queue: with no
 * `dirs.status_dir` nothing is recorded and a failed Consignment is retried every cycle without bound.
 */
export interface CommitRetriesPage {
    pipeline: string;
    keepsRetryState: boolean;
    note?: string;
    policy: { maxAttempts: number; initialBackoffMs: number; maxBackoffMs: number; bounded: boolean };
    total: number;
    truncated: boolean;
    retries: CommitRetryRow[];
    error?: string;
}

/** A successful retry-now / cancel. retry-now keeps the attempt count, and its `note` says so. */
export interface CommitRetryActResult {
    pipeline: string;
    file: string;
    outcome: 'rescheduled' | 'cancelled';
    attempts: number;
    maxAttempts: number;
    attemptsKept?: boolean;
    quarantineReason?: string;
    note?: string;
}

/** One registered output file of a Consignment. */
export interface ConsignmentOutputRow {
    tableName: string;
    partitionKey: string;
    recordDay: string | null;
    rows: number;
    bytes: number;
    /** LIVE | SUPERSEDED | COMPACTED_AWAY — what the Selector prunes on. */
    state: string | null;
    /** Which step wrote it — the sync tier, or the processor that derived it. */
    producer: string | null;
    writtenAt: string;
    path: string;
}

/** `enabled: false` means the registry is switched off — NOT that the Consignment wrote nothing. */
export interface ConsignmentOutputsPage {
    enabled: boolean;
    consignmentId: string;
    outputs: ConsignmentOutputRow[];
    /**
     * X2 cross-lane provenance, reverse half: the at-rest job runs that READ this Consignment. ABSENT (not
     * `[]`) when the backend has no run store — a deployment that cannot know must not read as "nothing
     * derived from this".
     */
    derivedRuns?: JobRunRow[];
}

/** One manual run's status (`GET /runs/runs/{runId}`, W5b). `total`/`failed` are -1 while `RUNNING`. */
export interface PipelineRunStatus {
    runId: string;
    pipeline?: string;
    status: 'RUNNING' | 'SUCCESS' | 'FAILED' | string;
    startedAt?: string;
    finishedAt?: string | null;
    total?: number;
    failed?: number;
    message?: string | null;
}

/** Delays (ms) between polls of a triggered run; the last one repeats until it settles. */
export const RUN_POLL_BACKOFF_MS: readonly number[] = [500, 1000, 2000, 4000, 5000];
/** Give up after this many polls (~5 min at the capped delay) — the Auto refresh takes over from there. */
export const RUN_POLL_MAX = 60;

/** Ingest run lifecycle + audit queries (CONTROL scope). */
@Injectable({ providedIn: 'root' })
export class RunsService {
    private http = inject(HttpClient);

    list(): Observable<RunView[]> {
        return this.http.get<RunView[]>(apiUrl('/runs'));
    }
    /** v1 async contract (W5b): 202 + the submitted run's id; poll `/runs/runs/{runId}` for status (or just
     *  refresh the list, which shows the outcome). Mirrors the job trigger. */
    trigger(name: string): Observable<{ runId: string }> {
        return this.http.post<{ runId: string }>(apiUrl(`/runs/${encodeURIComponent(name)}/trigger`), {});
    }
    /** One manual run's status by the id {@link trigger} returned; 404 once evicted or unknown. */
    run(runId: string): Observable<PipelineRunStatus> {
        return this.http.get<PipelineRunStatus>(apiUrl(`/runs/runs/${encodeURIComponent(runId)}`));
    }
    /**
     * Poll {@link run} on {@link RUN_POLL_BACKOFF_MS} until it leaves `RUNNING`, then emit its last status once
     * and complete. Delays only count while the page is visible. Emits `null` when a poll fails (404 = evicted
     * or unknown) and the last `RUNNING` status after {@link RUN_POLL_MAX} polls — the caller refreshes either way.
     */
    awaitRun(runId: string): Observable<PipelineRunStatus | null> {
        const poll = (i: number): Observable<PipelineRunStatus | null> =>
            visibleDelay(RUN_POLL_BACKOFF_MS[Math.min(i, RUN_POLL_BACKOFF_MS.length - 1)]).pipe(
                switchMap(() => this.run(runId)),
                switchMap((r) => (r.status === 'RUNNING' && i + 1 < RUN_POLL_MAX ? poll(i + 1) : of(r))),
                catchError(() => of(null)),
            );
        return poll(0);
    }
    runAll(): Observable<Record<string, RunResult>> {
        return this.http.post<Record<string, RunResult>>(apiUrl('/trigger'), {});
    }
    pause(name: string): Observable<{ pipeline: string; paused: boolean }> {
        return this.http.post<{ pipeline: string; paused: boolean }>(
            apiUrl(`/runs/${encodeURIComponent(name)}/pause`),
            {},
        );
    }
    resume(name: string): Observable<{ pipeline: string; paused: boolean }> {
        return this.http.post<{ pipeline: string; paused: boolean }>(
            apiUrl(`/runs/${encodeURIComponent(name)}/resume`),
            {},
        );
    }
    reprocess(name: string, batchId: string): Observable<Record<string, string>> {
        return this.http.post<Record<string, string>>(apiUrl(`/runs/${encodeURIComponent(name)}/reprocess`), {
            batchId,
        });
    }
    /**
     * Complete a Consignment that PARKED at a disabled route branch (Phase 4 S4 / D-13). Deliberately
     * separate from a re-enabling save: switching the Step back on is a config change, draining is the
     * operator saying "now finish the Consignments that waited". Refusals come back 409 with the reason.
     */
    drain(name: string, batchId: string): Observable<DrainResult> {
        return this.http.post<DrainResult>(apiUrl(`/runs/${encodeURIComponent(name)}/drain`), { batchId });
    }
    /**
     * Replay ONE file's rejected records from its reject file as a new Consignment (X4). Once per reject
     * file: a second call is 409. Map refusals with {@link replayRejectsErrorMessage}.
     */
    replayRejects(name: string, file: string): Observable<ReplayRejectsResult> {
        return this.http.post<ReplayRejectsResult>(apiUrl(`/runs/${encodeURIComponent(name)}/replay-rejects`), {
            file,
        });
    }
    /** The COMMIT retry queue of ONE pipeline (X1). Bounded server-side — see `truncated` / `total`. */
    retries(name: string, limit?: number): Observable<CommitRetriesPage> {
        return this.http.get<CommitRetriesPage>(apiUrl(`/runs/${encodeURIComponent(name)}/retries`), {
            params: toParams({ limit }),
        });
    }
    /** Clear ONE file's backoff so the next cycle admits it — the attempt count is KEPT. Map refusals with
     *  {@link commitRetryErrorMessage}. */
    retryNow(name: string, file: string): Observable<CommitRetryActResult> {
        return this.http.post<CommitRetryActResult>(apiUrl(`/runs/${encodeURIComponent(name)}/retries/retry-now`), {
            file,
        });
    }
    /** Stop retrying ONE file: it is quarantined NOW under `retry_cancelled` and never retried again. */
    cancelRetry(name: string, file: string): Observable<CommitRetryActResult> {
        return this.http.post<CommitRetryActResult>(apiUrl(`/runs/${encodeURIComponent(name)}/retries/cancel`), {
            file,
        });
    }
    commits(name: string): Observable<string[]> {
        return this.http.get<string[]>(apiUrl(`/runs/${encodeURIComponent(name)}/commits`));
    }
    batches(name: string): Observable<AuditRow[]> {
        return this.http.get<AuditRow[]>(apiUrl(`/runs/${encodeURIComponent(name)}/batches`));
    }
    files(name: string): Observable<AuditRow[]> {
        return this.http.get<AuditRow[]>(apiUrl(`/runs/${encodeURIComponent(name)}/files`));
    }
    lineage(name: string, batchId?: string): Observable<AuditRow[]> {
        return this.http.get<AuditRow[]>(apiUrl(`/runs/${encodeURIComponent(name)}/lineage`), {
            params: toParams({ batchId }),
        });
    }
    /**
     * One Consignment's registered outputs — every file it wrote, INCLUDING the derived tables and
     * summaries a post-sync step registered onto it.
     *
     * ⚠ `enabled` is not decoration: the output registry is switchable, and an empty list with it OFF
     * would read as "this Consignment wrote nothing", which is false. Render the two differently.
     */
    consignmentOutputs(name: string, consignmentId: string): Observable<ConsignmentOutputsPage> {
        return this.http.get<ConsignmentOutputsPage>(apiUrl(`/runs/${encodeURIComponent(name)}/outputs`), {
            params: toParams({ consignmentId }),
        });
    }
    quarantine(name: string): Observable<AuditRow[]> {
        return this.http.get<AuditRow[]>(apiUrl(`/runs/${encodeURIComponent(name)}/quarantine`));
    }
    /**
     * The rejected ROWS behind a file's `error_rows` count — the audit ledgers carry only counts, the
     * content lives in the companion `<base>_errors.csv`. `file` is the input file's bare NAME (the
     * key both the Files and Quarantine tabs already hold); 404 means no detail was recorded.
     */
    rejectedRows(name: string, file: string): Observable<RejectedRows> {
        return this.http.get<RejectedRows>(apiUrl(`/runs/${encodeURIComponent(name)}/errors`), {
            params: toParams({ file }),
        });
    }
    /** Inbox/processing status: files pending (matched, not yet processed) + whether mid-ingest. */
    pending(name: string): Observable<InboxStatus> {
        return this.http.get<InboxStatus>(apiUrl(`/runs/${encodeURIComponent(name)}/pending`));
    }
    report(name: string, window?: ReportWindow): Observable<ConsignmentAuditReport> {
        return this.http.get<ConsignmentAuditReport>(apiUrl(`/runs/${encodeURIComponent(name)}/report`), {
            params: toParams({ ...window }),
        });
    }
}
