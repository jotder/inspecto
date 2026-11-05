import { HttpClient, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, filter, switchMap, take, tap, throwError, timer } from 'rxjs';
import { apiUrl, toParams } from '@inspecto/core/api';
import { ShortReads } from '@inspecto/core/api/short-reads';
import type {
    Arrivals,
    CommitBody,
    DataSettings,
    FormatOverrides,
    ImportKind,
    ImportRun,
    ImportSummary,
    OnboardDraft,
} from './add-data.model';
import type { DatasetFeedState, IndexJob, KeepUpToDate } from './dataset-feed.model';
import type { DatasetStorageState, Retention } from './dataset-storage.model';

/** A followed run that ended in `failed`: the server's reason, said as it said it. */
export class ImportRunFailed extends Error {
    constructor(readonly run: ImportRun) {
        super(run.message ?? 'The import did not finish.');
    }
}

/** How often a 202 run is re-read. */
export const FOLLOW_INTERVAL_MS = 1500;

/**
 * The **Add data** route family (LA-ONBOARD-1 OB-2, phase 2): file in, Dataset out. Every edition, not under `/inv`, all
 * `canUploadData`. Gate order on the server: 401/403 capability, 503 no write root, 403 `data.self_import` off.
 */
@Injectable({ providedIn: 'root' })
export class OnboardService {
    private readonly http = inject(HttpClient);
    private readonly reads = inject(ShortReads);

    /** Upload the file and read it back as a draft. `format` re-reads it with stated choices (a re-draft). */
    draft(file: File, kind: ImportKind, format: FormatOverrides = {}): Observable<OnboardDraft> {
        let params = toParams({
            name: file.name,
            kind,
            delimiter: format.delimiter,
            header: format.header === undefined ? undefined : String(format.header),
        });
        // "No quoting" is the empty quote, a stated choice: sent as `quote=` (toParams drops an empty value).
        if (format.quote !== undefined) params = params.set('quote', format.quote);
        return this.http.post<OnboardDraft>(apiUrl('/onboard/files'), file, {
            params,
            headers: { 'Content-Type': 'application/octet-stream' },
        });
    }

    /** The one confirm: writes, registers, lands, runs once. A large file answers 202 and is followed to its end. */
    commit(draftId: string, body: CommitBody, onFollow?: (runId: string) => void): Observable<ImportSummary> {
        // The commit registers a Dataset: the Dataset list the landing reads must be asked again.
        return this.settle(
            this.http.post<ImportSummary | ImportRun>(
                apiUrl(`/onboard/files/${encodeURIComponent(draftId)}/commit`),
                body,
                {
                    observe: 'response',
                },
            ),
            onFollow,
        ).pipe(tap(() => this.reads.clear()));
    }

    /** **Add a file** to an imported Dataset and run it (`replace` is the default; `append` for a Stream only). */
    addFile(
        datasetId: string,
        file: File,
        mode: 'replace' | 'append',
        onFollow?: (runId: string) => void,
    ): Observable<ImportSummary> {
        return this.settle(
            this.http.post<ImportSummary | ImportRun>(
                apiUrl(`/datasets/${encodeURIComponent(datasetId)}/files`),
                file,
                {
                    params: toParams({ name: file.name, mode }),
                    headers: { 'Content-Type': 'application/octet-stream' },
                    observe: 'response',
                },
            ),
            onFollow,
        );
    }

    /** **Run now**: the import's Pipeline over whatever waits in its inbox. */
    runNow(datasetId: string, onFollow?: (runId: string) => void): Observable<ImportSummary> {
        return this.settle(
            this.http.post<ImportSummary | ImportRun>(
                apiUrl(`/datasets/${encodeURIComponent(datasetId)}/run`),
                {},
                { observe: 'response' },
            ),
            onFollow,
        );
    }

    /** One followed run's state. */
    run(runId: string): Observable<ImportRun> {
        return this.http.get<ImportRun>(apiUrl(`/onboard/runs/${encodeURIComponent(runId)}`));
    }

    /** Re-read a 202 run until it ends: its summary, or {@link ImportRunFailed} with the server's reason. */
    follow(runId: string, intervalMs = FOLLOW_INTERVAL_MS): Observable<ImportSummary> {
        return timer(intervalMs, intervalMs).pipe(
            switchMap(() => this.run(runId)),
            filter((r) => r.state !== 'running'),
            take(1),
            switchMap((r) =>
                r.state === 'succeeded' && r.summary ? [r.summary] : throwError(() => new ImportRunFailed(r)),
            ),
        );
    }

    /** Every file the import Dataset took, newest first, with the period each covers (a read: no capability). */
    arrivals(datasetId: string): Observable<Arrivals> {
        return this.http.get<Arrivals>(apiUrl(`/datasets/${encodeURIComponent(datasetId)}/arrivals`));
    }

    /** The Space's `data.self_import` (OB-7): whether people may Add data here. */
    dataSettings(): Observable<DataSettings> {
        return this.http.get<DataSettings>(apiUrl('/settings/data'));
    }

    /** Turn Add data on or off in this Space (`canAdminister`); null = back to the default. Answers the settings in force. */
    setDataSettings(selfImport: boolean | null): Observable<DataSettings> {
        return this.http.put<DataSettings>(apiUrl('/settings/data'), { selfImport });
    }

    /** LA-ARRIVAL-FEEDS-1: the Dataset's inbox, the files waiting there and its feed (`canUploadData`). */
    feed(datasetId: string): Observable<DatasetFeedState> {
        return this.http.get<DatasetFeedState>(apiUrl(`/datasets/${encodeURIComponent(datasetId)}/feed`));
    }

    /** *Keep this up to date* (Standard and above): the import's Pipeline watches its inbox, or reads an SFTP Connection. */
    keepUpToDate(datasetId: string, body: KeepUpToDate): Observable<DatasetFeedState> {
        return this.http.put<DatasetFeedState>(apiUrl(`/datasets/${encodeURIComponent(datasetId)}/feed`), body);
    }

    /** Back to uploads: the import runs only when someone asks. */
    stopFeed(datasetId: string): Observable<DatasetFeedState> {
        return this.http.delete<DatasetFeedState>(apiUrl(`/datasets/${encodeURIComponent(datasetId)}/feed`));
    }

    /** Save the index Job *Keep this up to date* proposed, through the Jobs route and its own gates. */
    saveIndexJob(job: IndexJob): Observable<unknown> {
        return this.http.post(apiUrl('/jobs'), job);
    }

    /** LA-DATASET-COMPACT-1: the Dataset's Parquet files, the last compaction and its retention policy. */
    storage(datasetId: string): Observable<DatasetStorageState> {
        return this.http.get<DatasetStorageState>(apiUrl(`/datasets/${encodeURIComponent(datasetId)}/storage`));
    }

    /** *Compact now*: merge the Dataset's files into one, whatever their count. */
    compactNow(datasetId: string): Observable<DatasetStorageState> {
        return this.http.post<DatasetStorageState>(apiUrl(`/datasets/${encodeURIComponent(datasetId)}/compact`), null);
    }

    /** The retention policy (Standard and up, `canAuthorWorkbench`); null keeps every arrival. */
    setRetention(datasetId: string, retention: Retention | null): Observable<DatasetStorageState> {
        return this.http.put<DatasetStorageState>(apiUrl(`/datasets/${encodeURIComponent(datasetId)}/storage`), {
            retention,
        });
    }

    /** 200 = the summary; 202 = a run to follow (the caller is told, so it can say the run continues on the server). */
    private settle(
        res: Observable<HttpResponse<ImportSummary | ImportRun>>,
        onFollow?: (runId: string) => void,
    ): Observable<ImportSummary> {
        return res.pipe(
            switchMap((r) => {
                if (r.status !== 202) return [r.body as ImportSummary];
                const runId = (r.body as ImportRun).runId;
                onFollow?.(runId);
                return this.follow(runId);
            }),
        );
    }
}
