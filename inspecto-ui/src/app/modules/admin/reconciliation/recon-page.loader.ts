import { HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, catchError, map, of, switchMap, tap } from 'rxjs';
import { apiErrorMessage } from 'app/inspecto/api';
import { Reconciliation, ReconRunQuery, ReconRunResult } from 'app/inspecto/reconciliation';
import { ReconExecService } from './recon-exec.service';

/**
 * The Board's and the widget's ONE request pipeline for a day's page (RECON-PERF-1, operator 2026-10-09): every
 * date / page / filter / sample change is a {@link load}, and `switchMap` CANCELS the request still in flight, so a
 * slow day can never land over the one the user moved to. `loading` is true from the request until its answer,
 * which is what keeps a host from showing its empty state mid-flight; a failure lands in `error` with `retry()`.
 */
export class ReconPageLoader {
    readonly result = signal<ReconRunResult | null>(null);
    readonly loading = signal(false);
    readonly error = signal<string | null>(null);
    /**
     * LIVEFIX2 #4: whether asking again can help — a network failure (status 0), a 5xx or a 429. A 4xx refusal
     * (422 "no date column") is about the Reconciliation or its Datasets, so the host offers a fix, not Retry.
     */
    readonly retryable = signal(true);
    /** The query of the request in flight, or of the last one answered. */
    readonly query = signal<ReconRunQuery | null>(null);

    private readonly requests = new Subject<{ recon: Reconciliation; query: ReconRunQuery }>();
    private last: { recon: Reconciliation; query: ReconRunQuery } | null = null;

    constructor(exec: ReconExecService, destroyRef: DestroyRef) {
        this.requests
            .pipe(
                tap(({ query }) => {
                    this.query.set(query);
                    this.loading.set(true);
                    this.error.set(null);
                    this.retryable.set(true);
                }),
                switchMap(({ recon, query }) =>
                    exec.page(recon, query).pipe(
                        map((r) => ({ ok: true as const, r })),
                        catchError((e: unknown) => of({ ok: false as const, e })),
                    ),
                ),
                takeUntilDestroyed(destroyRef),
            )
            .subscribe((out) => {
                this.loading.set(false);
                if (out.ok === true) this.result.set(out.r);
                else {
                    this.retryable.set(isRetryable(out.e));
                    this.error.set(apiErrorMessage(out.e, 'The reconciliation run failed.'));
                }
            });
    }

    load(recon: Reconciliation, query: ReconRunQuery): void {
        this.last = { recon, query };
        this.requests.next(this.last);
    }

    retry(): void {
        if (this.last) this.requests.next(this.last);
    }
}

/** A failure asking again can fix: no HTTP answer at all, a 5xx, or a 429. Anything else is a refusal. */
export function isRetryable(e: unknown): boolean {
    if (!(e instanceof HttpErrorResponse)) return true;
    return e.status === 0 || e.status === 429 || e.status >= 500;
}
