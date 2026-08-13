import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, forkJoin, of } from 'rxjs';
import { catchError, concatMap, map } from 'rxjs/operators';
import {
    ComponentType,
    ComponentsService,
    ConnectionProfile,
    ConnectionsService,
    DecisionRule,
    DecisionRulesService,
    JobsService,
    PipelinesService,
    SpacesService,
    apiUrl,
} from 'app/inspecto/api';
import type { AuthoredPipeline } from 'app/inspecto/api/pipelines.service';
import type { JobDetail } from 'app/inspecto/api/jobs.service';
import { BUNDLE_KINDS, BundleItem, BundleKind, MetadataBundle, buildBundle, withDependencies } from './bundle';

/** What the caller asks for per item; anything absent takes the server's default (existing ⇒ skip). */
export type ImportAction = 'overwrite' | 'skip';

/** Per-item outcome from `POST /bundle/import`. `unchanged` = identical hash, so nothing was written. */
export type ImportStatus = 'imported' | 'overwritten' | 'skipped' | 'unchanged' | 'failed';

export interface ImportResultRow {
    kind: BundleKind;
    id: string;
    status: ImportStatus;
    /** Why it was skipped or how it failed — server-authored, shown verbatim in the result column. */
    message?: string;
}

export interface ImportOutcome {
    imported: number;
    overwritten: number;
    skipped: number;
    unchanged: number;
    failed: number;
    results: ImportResultRow[];
}

const COMPONENT_KINDS = BUNDLE_KINDS.map((k) => k.kind).filter(
    (k): k is Extract<BundleKind, ComponentType> =>
        k !== 'connection' && k !== 'authored-pipeline' && k !== 'job' && k !== 'decision-rule',
);

/** A job's transportable metadata — the upsert shape; runtime state (last status/run/next fire) never travels. */
function jobContent(job: JobDetail): Record<string, unknown> {
    const { name, type, cron, onPipeline, enabled, catchUp, params } = job;
    return { name, type, cron: cron ?? null, onPipeline: onPipeline ?? null, enabled, catchUp, params };
}

/** A decision rule's transportable metadata (the upsert shape) — runtime `lastSimulation`/timestamps never travel. */
function decisionRuleContent(rule: DecisionRule): Record<string, unknown> {
    const { name, description, targetType, target, when, consequences, priority, enabled } = rule;
    return { name, description: description ?? '', targetType, target, when, consequences, priority, enabled };
}

/**
 * The single source of Metadata-Bundle transport (R6): loading every exportable artifact off the
 * instance, applying an import, and building/downloading a bundle. Extracted from the Settings Transfer
 * pane so the shared import dialog and the per-surface `<inspecto-transfer-menu>` reuse the exact same
 * path — one format, three surfaces.
 *
 * <p><b>Import is the backend's</b> since U-F (2026-08-01): {@link #applyImport} posts the envelope to
 * `/bundle/import` instead of fanning out one per-kind write per row. {@link #loadAll} stays client-side
 * on purpose — it feeds the *selection* UI (what this instance has to offer, and the target index the fit
 * check runs against), which is the UI's half of the split `BundleRoutes` was designed around: the UI
 * derives the closure and lineage, the backend owns content, hashes, gates and persistence.
 */
@Injectable({ providedIn: 'root' })
export class BundleTransferService {
    private http = inject(HttpClient);
    private components = inject(ComponentsService);
    private connections = inject(ConnectionsService);
    private pipelines = inject(PipelinesService);
    private jobs = inject(JobsService);
    private decisionRules = inject(DecisionRulesService);
    private spaces = inject(SpacesService);

    /** Load every exportable artifact (component kinds + connections + lossless pipelines + jobs + rules). */
    loadAll(): Observable<BundleItem[]> {
        const componentLists = Object.fromEntries(
            COMPONENT_KINDS.map((kind) => [kind, this.components.list(kind).pipe(catchError(() => of([])))]),
        );
        return forkJoin({
            ...componentLists,
            connection: this.connections.list().pipe(catchError(() => of([]))),
            pipelineNames: this.pipelines.list().pipe(
                map((list) => list.map((p) => p.name)),
                catchError(() => of([] as string[])),
            ),
            jobNames: this.jobs.list().pipe(
                map((list) => list.map((j) => j.name)),
                catchError(() => of([] as string[])),
            ),
            decisionRules: this.decisionRules.list().pipe(catchError(() => of([] as DecisionRule[]))),
        }).pipe(
            concatMap((res) => {
                const raws = (res['pipelineNames'] as string[]).map((name) =>
                    this.pipelines.pipelineGraphRaw(name).pipe(catchError(() => of(null))),
                );
                const jobDetails = (res['jobNames'] as string[]).map((name) =>
                    this.jobs.get(name).pipe(catchError(() => of(null))),
                );
                return forkJoin({
                    pipelines: raws.length ? forkJoin(raws) : of([] as (AuthoredPipeline | null)[]),
                    jobs: jobDetails.length ? forkJoin(jobDetails) : of([] as (JobDetail | null)[]),
                }).pipe(map(({ pipelines, jobs }) => ({ res, pipelines, jobs })));
            }),
            map(({ res, pipelines, jobs }) => {
                const items: BundleItem[] = [];
                for (const kind of COMPONENT_KINDS) {
                    for (const def of res[kind] as { name: string; content: Record<string, unknown> }[]) {
                        items.push({ kind, id: def.name, content: def.content });
                    }
                }
                for (const c of res['connection'] as ConnectionProfile[]) {
                    items.push({ kind: 'connection', id: c.id, content: c as unknown as Record<string, unknown> });
                }
                for (const p of pipelines)
                    if (p)
                        items.push({
                            kind: 'authored-pipeline',
                            id: p.name,
                            content: p as unknown as Record<string, unknown>,
                        });
                for (const j of jobs) if (j) items.push({ kind: 'job', id: j.name, content: jobContent(j) });
                for (const r of res['decisionRules'] as DecisionRule[])
                    items.push({ kind: 'decision-rule', id: r.name, content: decisionRuleContent(r) });
                return items;
            }),
        );
    }

    /**
     * Apply an import through **the backend's own bundle pipe** (`POST /bundle/import`) — U-F, 2026-08-01.
     *
     * <p>This used to be a client-side fan-out: one `write()` per row, each going to the per-kind store
     * (`POST /connections`, `PUT /components/{kind}/{id}`, …) in whatever order the caller looped. That was
     * the third parallel implementation of a format the backend already owns, and it silently skipped every
     * gate `/bundle/import` applies:
     * - **referential integrity**, fail-closed before *any* write — an import may not INTRODUCE broken refs;
     * - **connection secret defence-in-depth** — a secret-looking field that is present and not a `${…}`
     *   reference fails that item, where `POST /connections` accepts a literal (its `keepSecret` only
     *   intercepts the `***` sentinel), so the fan-out could land a raw credential on the target;
     * - **dependency-ordered apply** (`APPLY_ORDER`), so a referenced kind is written before its referencer;
     * - **idempotent re-promotion**: identical content hash ⇒ `unchanged`, no write at all. The fan-out had
     *   no such status and reported a no-op re-import as a successful write.
     *
     * The envelope is passed through **as parsed**, not rebuilt, so each item keeps the origin
     * `provenance.contentHash` the drift/idempotency check reads.
     *
     * @param actions per-item `"<kind>/<id>" → "overwrite" | "skip"`; an existing item defaults to skip
     */
    applyImport(bundle: MetadataBundle, actions: Record<string, ImportAction>): Observable<ImportOutcome> {
        return this.http.post<ImportOutcome>(apiUrl('/bundle/import'), { bundle, actions });
    }

    /**
     * Build a bundle for a selection through **the backend's own export pipe** (`POST /bundle/export`) —
     * U-F, 2026-08-01. The UI still derives the *selection*: the dependency closure and each item's
     * lineage `refs` come from the client-side metadata network, and are sent along. What it stopped doing
     * is authoring the *content*, because the list shapes `loadAll()` holds are the API's display views,
     * not the bundle's transport views. Two of those differences were live promotion defects:
     *
     * - a **connection** came off `GET /connections` with its literal secret masked to `***` and its base
     *   path spelled `basePath`. The bundle view ({@code ConnectionProfile.toBundleMap}) *omits* a literal
     *   secret and spells it `base_path` — and `/bundle/import` rejects `***` as a raw credential (it is a
     *   persisted lie that would round-trip into the target as a literal). So a UI-exported connection
     *   could not be imported at all. That only became reachable when import became a backend caller;
     *   the old per-kind fan-out went to `POST /connections`, whose `keepSecret` swallows the sentinel.
     * - external `requires` now come back stamped with the source's `originHash`, which is what lets the
     *   target's preview say *present but at a different version* instead of a bare "satisfied".
     *
     * `missing` stays the closure's unresolvable references (nothing on this instance to pull in);
     * `absent` is the server's own list — requested items its stores do not hold. A partial bundle is
     * still a valid bundle, so neither is fatal; the caller reports them.
     */
    buildExport(
        selected: BundleItem[],
        available: BundleItem[],
        includeDeps: boolean,
    ): Observable<{ bundle: MetadataBundle; missing: string[]; absent: string[] }> {
        let items = selected;
        let missing: string[] = [];
        if (includeDeps) ({ items, missing } = withDependencies(selected, available));
        const local = buildBundle(items, this.spaces.currentSpaceId());
        const body = {
            items: local.items.map((i) => ({ kind: i.kind, id: i.id, refs: i.refs })),
            sourceSpace: local.sourceSpace,
            requires: local.requires,
        };
        return this.http
            .post<{ bundle: MetadataBundle; missing?: { kind: string; id: string }[] }>(apiUrl('/bundle/export'), body)
            .pipe(
                map((res) => ({
                    bundle: res.bundle,
                    missing,
                    absent: (res.missing ?? []).map((m) => `${m.kind}/${m.id}`),
                })),
            );
    }

    /** Trigger a browser download of a bundle as pretty-printed JSON. */
    download(bundle: MetadataBundle): void {
        const space = bundle.sourceSpace ?? 'default';
        const stamp = bundle.exportedAt.slice(0, 16).replace(/[:T]/g, '-');
        const url = URL.createObjectURL(new Blob([JSON.stringify(bundle, null, 2)], { type: 'application/json' }));
        const link = document.createElement('a');
        link.href = url;
        link.download = `inspecto-bundle-${space}-${stamp}.json`;
        link.click();
        URL.revokeObjectURL(url);
    }
}
