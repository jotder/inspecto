import { Injectable, inject } from '@angular/core';
import { JobRunRow } from './jobs.service';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiErrorMessage, apiUrl, toParams } from './api-base';
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
