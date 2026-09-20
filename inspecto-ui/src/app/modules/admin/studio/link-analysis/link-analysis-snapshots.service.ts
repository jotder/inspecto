import { Injectable, computed, signal } from '@angular/core';
import { GraphSnapshot } from 'app/inspecto/graph';

/** A Case an evidence snapshot can be attached to (id + title, as the objects API reports them). */
export interface CaseRef {
    id: string;
    title: string;
}

/**
 * **Evidence snapshots — UI-first, session-scoped store** (spec §3.6 / plan S1.3).
 *
 * ⚠ MOCK PERSISTENCE. Snapshots live in this root service's signal for the browser session and are gone on
 * reload; attaching to a Case records the link here only. The plan's `POST /inv/snapshots` and
 * `POST /cases/{id}/evidence/graph` replace `add`/`attach` when the backend lands — the dialogs and the
 * host are written against this interface so that swap is confined to this file.
 */
@Injectable({ providedIn: 'root' })
export class LinkAnalysisSnapshotsService {
    readonly snapshots = signal<GraphSnapshot[]>([]);
    readonly count = computed(() => this.snapshots().length);

    /**
     * Cases offered when the ops module is absent (`bootstrap.features.ops` false) — placeholder rows so the
     * attach flow can be exercised UI-first. With ops present the dialog lists real Cases instead.
     */
    readonly mockCases: readonly CaseRef[] = [
        { id: 'CASE-2026-0318', title: 'Suspected layering network (placeholder Case)' },
        { id: 'CASE-2026-0322', title: 'Burner rotation cluster (placeholder Case)' },
    ];

    add(s: GraphSnapshot): void {
        this.snapshots.update((all) => [s, ...all.filter((x) => x.id !== s.id)]);
    }

    attach(snapshotId: string, caseId: string): GraphSnapshot | undefined {
        let out: GraphSnapshot | undefined;
        this.snapshots.update((all) =>
            all.map((s) => {
                if (s.id !== snapshotId) return s;
                out = s.attachedTo.includes(caseId) ? s : { ...s, attachedTo: [...s.attachedTo, caseId] };
                return out;
            }),
        );
        return out;
    }
}
