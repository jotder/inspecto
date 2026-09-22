import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map, tap } from 'rxjs';
import { GraphSnapshot } from 'app/inspecto/graph';
import { apiUrl } from 'app/inspecto/api/api-base';

/** A Case an evidence snapshot can be attached to (id + title, as the objects API reports them). */
export interface CaseRef {
    id: string;
    title: string;
}

/**
 * **Evidence snapshots — durable, server-sealed** (LA-03; decisions `D-S1`/`D-E2`/`D-E3`).
 *
 * <p>Snapshots used to live only in this signal for the browser session and were gone on reload. They are
 * now sealed by `POST /inv/snapshots`, which writes one immutable file per id and answers **409 on a
 * re-POST** — a sealed snapshot is never replaced. Attaching to a Case is `POST /inv/snapshots/attach`,
 * which appends to a separate log and **never reopens the sealed record**, because rewriting it would
 * change its bytes and invalidate the `manifestHash` that makes it evidence.
 *
 * ⛔ **The signal updates only AFTER the server confirms.** An optimistic update would show an analyst a
 * snapshot that looks saved and is not — in an evidence store that is the worst class of bug, because the
 * whole point of the object is that you can rely on it later. Callers therefore get an `Observable` and
 * must decide what to do when it errors; there is deliberately no fire-and-forget path left.
 */
@Injectable({ providedIn: 'root' })
export class LinkAnalysisSnapshotsService {
    private http = inject(HttpClient);

    /** Snapshots this session has sealed or loaded — a read model, never the source of truth. */
    readonly snapshots = signal<GraphSnapshot[]>([]);
    readonly count = computed(() => this.snapshots().length);

    /**
     * Cases offered when the ops module is absent (`bootstrap.features.ops` false) — placeholder rows so the
     * attach flow can be exercised UI-first. With ops present the dialog lists real Cases instead.
     * ⚠ Never offered as a fallback when a real lookup FAILS: a failed lookup is an error state, not an
     * excuse to hand an analyst Case ids that do not exist.
     */
    readonly mockCases: readonly CaseRef[] = [
        { id: 'CASE-2026-0318', title: 'Suspected layering network (placeholder Case)' },
        { id: 'CASE-2026-0322', title: 'Burner rotation cluster (placeholder Case)' },
    ];

    /**
     * Seal one snapshot. Errors propagate — a 409 means this id is already sealed, which the caller should
     * surface rather than swallow, since it means the analysis on screen is NOT the one on disk.
     */
    save(s: GraphSnapshot): Observable<GraphSnapshot> {
        return this.http.post<unknown>(apiUrl('/inv/snapshots'), s).pipe(
            map(() => s),
            tap((sealed) => this.snapshots.update((all) => [sealed, ...all.filter((x) => x.id !== sealed.id)])),
        );
    }

    /** Record that a sealed snapshot was attached to a Case. Returns every Case it is now attached to. */
    attachTo(snapshotId: string, caseId: string): Observable<string[]> {
        return this.http
            .post<{ attachedTo?: string[] }>(apiUrl('/inv/snapshots/attach'), { snapshotId, caseId })
            .pipe(
                map((r) => r?.attachedTo ?? []),
                tap((attached) =>
                    this.snapshots.update((all) =>
                        all.map((s) => (s.id === snapshotId ? { ...s, attachedTo: attached } : s)),
                    ),
                ),
            );
    }
}
