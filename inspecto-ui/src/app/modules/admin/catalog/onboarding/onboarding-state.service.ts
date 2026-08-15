import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, forkJoin, map, of, switchMap, tap } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import {
    apiErrorMessage,
    ConfigDeleteResult,
    ConfigImpact,
    ConfigService,
    ConfigWriteResult,
    Finding,
} from 'app/inspecto/api';
import { mergeBlock, nullifyDeletes } from 'app/inspecto/component-model';
import { DefinitionStateService } from 'app/inspecto/definition/definition-state.service';

/** Stage ids across both kinds — a Stream uses schema/enrichment, a Reference keys. */
export type OnboardingStageId = 'collection' | 'parsing' | 'schema' | 'enrichment' | 'keys' | 'publish';

/** Computed, never stored: readiness is derived from the config blocks (+ the last save's findings) on
 *  every read. `blocked` = an ERROR-severity Finding attributed to this stage (by fieldPath prefix)
 *  is still to resolve. */
export type StageStatus = 'empty' | 'configured' | 'validated' | 'blocked';

/** The pipeline-config block a stage owns, or `null` for a stage authoring a companion artifact. */
export type OnboardingStageBlock = 'collector' | 'parsing' | 'processing' | 'output' | null;

export interface OnboardingStage {
    id: OnboardingStageId;
    /**
     * The engine node type this stage authors (`BuiltinNodeType`, what `GET /pipelines/node-types`
     * serves) — W4a/U-B: the guided rail is a view over the head of the SAME graph the Pipelines
     * editor edits, so every stage names its node. Both `schema` and `keys` are `parser`: schema
     * artifacts live on the parser node (that is where `PipelineLift` carries them).
     */
    nodeType: string;
    /** The config block the stage owns — the panes' read/merge root AND the findings-attribution
     *  prefix ({@link OnboardingStateService.stageForPath} derives from it). `null` = the stage
     *  authors a companion config (`enrichment` → `<name>_enrich`), not a block of the pipeline TOON. */
    block: OnboardingStageBlock;
    label: string;
    icon: string;
    hint: string;
    optional?: boolean;
}

export const STREAM_STAGES: OnboardingStage[] = [
    {
        id: 'collection',
        nodeType: 'acquisition',
        block: 'collector',
        label: 'Collection',
        icon: 'heroicons_outline:inbox-arrow-down',
        hint: 'Where the files come from',
    },
    {
        id: 'parsing',
        nodeType: 'parser',
        block: 'parsing',
        label: 'Parsing',
        icon: 'heroicons_outline:code-bracket',
        hint: 'How raw bytes become rows',
    },
    {
        id: 'schema',
        nodeType: 'parser',
        block: 'processing',
        label: 'Schema & Mapping',
        icon: 'heroicons_outline:table-cells',
        hint: 'Names, types and casts',
    },
    {
        id: 'enrichment',
        nodeType: 'enrichment',
        block: null,
        label: 'Enrichment',
        icon: 'heroicons_outline:sparkles',
        hint: 'Joins and aggregations',
        optional: true,
    },
    {
        id: 'publish',
        nodeType: 'sink.persistent',
        block: 'output',
        label: 'Dataset & Go-live',
        icon: 'heroicons_outline:rocket-launch',
        hint: 'Output format and activation',
    },
];

export const REFERENCE_STAGES: OnboardingStage[] = [
    {
        id: 'collection',
        nodeType: 'acquisition',
        block: 'collector',
        label: 'Collection',
        icon: 'heroicons_outline:inbox-arrow-down',
        hint: 'Where the dumps come from',
    },
    {
        id: 'parsing',
        nodeType: 'parser',
        block: 'parsing',
        label: 'Parsing',
        icon: 'heroicons_outline:code-bracket',
        hint: 'How raw bytes become rows',
    },
    // Required, not optional: a pipeline cannot arm without a schema — the keys stage IS where a
    // Reference gets its columns/types (plus the honest full-replace load-policy note).
    {
        id: 'keys',
        nodeType: 'parser',
        block: 'processing',
        label: 'Keys & Load',
        icon: 'heroicons_outline:key',
        hint: 'Columns, types and the full-replace load',
    },
    {
        id: 'publish',
        nodeType: 'sink.persistent',
        block: 'output',
        label: 'Publish',
        icon: 'heroicons_outline:rocket-launch',
        hint: 'Make it bindable by name',
    },
];

/**
 * Per-onboarding-session state (provided by the shell, one instance per opened draft): the
 * server-held config is THE draft — every stage save is a `POST /config/write overwrite:true`
 * and readiness is recomputed from the blocks, never stored. Session-only extras (the captured
 * sample, the last parse preview, the active pane's dirty check) live here so the stage panes
 * and the sample panel share them without inputs.
 */
@Injectable()
export class OnboardingStateService {
    private configApi = inject(ConfigService);
    private toastr = inject(ToastrService);
    private definition = inject(DefinitionStateService);

    readonly name = signal('');
    readonly loading = signal(false);
    /** 404 on load — no draft/pipeline of that name on the server. */
    readonly missing = signal(false);
    readonly writesDisabled = signal(false);
    readonly config = signal<Record<string, unknown> | null>(null);
    readonly activeStageId = signal<OnboardingStageId>('collection');

    // ── sample-as-thread ──
    // Owned by the shared DefinitionStateService (D5) and re-exported here, so the wizard's stage
    // readiness keeps reading it while the panes migrate onto the shared service pane-by-pane.
    // These are the SAME signal instances, not copies — there is one thread, not two.
    readonly sample = this.definition.sample;
    readonly parsePreview = this.definition.parsePreview;
    readonly parseError = this.definition.parseError;
    readonly schemaPreview = this.definition.schemaPreview;
    readonly schemaError = this.definition.schemaError;
    /** The companion `EnrichmentConfig` (Streams only, `<name>_enrich`) — server-held like the
     *  draft itself; null = none authored yet (the stage is optional). */
    readonly enrichmentConfig = signal<Record<string, unknown> | null>(null);

    /** Findings retained from the last save (the server `POST /config/write` result), attributed to
     *  stages by `fieldPath` prefix (`collector.*` → collection, `parsing.*` → parsing, `processing.*` →
     *  schema/keys, `output.*`/`active` → publish); a finding without a matching prefix (blank path,
     *  cross-field rules) falls back to the stage that triggered the save. Every save validates the
     *  whole config, so all pipeline-stage buckets are replaced per save — a clean save clears stale
     *  findings on every stage, not just the active one. A stage with an ERROR-severity finding reads
     *  as `blocked` in {@link stageStatus}. */
    readonly stageFindings = signal<Partial<Record<OnboardingStageId, Finding[]>>>({});

    /** The active pane's unsaved-changes probe (registered on init, cleared on destroy). */
    private dirtyCheck: (() => boolean) | null = null;

    readonly kind = computed<'stream' | 'reference'>(() =>
        String((this.config() ?? {})['produces'] ?? '') === 'reference' ? 'reference' : 'stream',
    );
    readonly active = computed(() => (this.config() ?? {})['active'] === true);
    readonly stages = computed<OnboardingStage[]>(() =>
        this.kind() === 'reference' ? REFERENCE_STAGES : STREAM_STAGES,
    );
    /** The engine's normalized pipeline id (`Identity.pipelineName`) — what `BatchEvent.pipeline()`
     *  carries and what an enrichment's `triggers.on_pipeline` must therefore use. */
    readonly normalizedName = computed(() =>
        String((this.config() ?? {})['name'] ?? this.name())
            .toLowerCase()
            .replace(/ /g, '_'),
    );
    /** Companion enrichment identity, mirroring the schema convention (`<pipeline>_schema`). */
    enrichName(): string {
        return `${this.name()}_enrich`;
    }

    /** A stage-owned config block off the server-held draft, or `null` when not yet authored —
     *  the one read path for the panes (W4a; each pane used to re-derive this ad hoc). */
    block(name: NonNullable<OnboardingStageBlock> | 'dirs'): Record<string, unknown> | null {
        const v = (this.config() ?? {})[name];
        return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null;
    }

    readonly stageStatus = computed<Record<OnboardingStageId, StageStatus>>(() => {
        const cfg = this.config() ?? {};
        const proc = (cfg['processing'] ?? {}) as Record<string, unknown>;
        const parsing = (cfg['parsing'] ?? {}) as Record<string, unknown>;
        const hasSchema =
            !!proc['schema_file'] ||
            (Array.isArray(proc['schemas']) && proc['schemas'].length > 0) ||
            !!proc['ingester'] ||
            !!parsing['plugin'];
        const parsingConfigured = 'parsing' in cfg;
        const schemaStatus: StageStatus = hasSchema
            ? this.schemaPreview() && !this.schemaError()
                ? 'validated'
                : 'configured'
            : 'empty';
        // A stage whose last save returned an ERROR finding is `blocked`, overriding its base readiness.
        const sf = this.stageFindings();
        const at = (id: OnboardingStageId, base: StageStatus): StageStatus =>
            (sf[id] ?? []).some((f) => f.severity === 'ERROR') ? 'blocked' : base;
        return {
            collection: at('collection', 'collector' in cfg ? 'configured' : 'empty'),
            parsing: at(
                'parsing',
                parsingConfigured ? (this.parsePreview() && !this.parseError() ? 'validated' : 'configured') : 'empty',
            ),
            schema: at('schema', schemaStatus),
            // The Reference "Keys & Load" stage authors the same schema artifact.
            keys: at('keys', schemaStatus),
            enrichment: at('enrichment', this.enrichmentConfig() ? 'configured' : 'empty'),
            publish: at('publish', 'output' in cfg || this.active() ? 'configured' : 'empty'),
        };
    });

    /** Draft (incomplete) → Ready (complete, inactive) → Live (active). A `blocked` stage (unresolved
     *  ERROR finding) is not Ready any more than an empty one. */
    readonly lifecycle = computed<'Draft' | 'Ready' | 'Live'>(() => {
        if (this.active()) return 'Live';
        const status = this.stageStatus();
        const required = this.stages().filter((s) => !s.optional && s.id !== 'publish');
        const ready = (st: StageStatus): boolean => st !== 'empty' && st !== 'blocked';
        const complete = required.every((s) => ready(status[s.id])) && ready(status['publish']);
        return complete ? 'Ready' : 'Draft';
    });

    /** The OTHER required stages still empty (mirrors {@link lifecycle}'s own `id !== 'publish'`
     *  exclusion) — the Publish stage names them so a blocked go-live is never a silent dead end.
     *  Lives here, not in that pane: it is a fact about the stage model, which is host-side (D5). */
    readonly blockedStages = computed(() =>
        this.stages()
            .filter((s) => !s.optional && s.id !== 'publish' && this.stageStatus()[s.id] === 'empty')
            .map((s) => s.label),
    );

    /** The first ERROR finding message for a stage (the rail's `blocked` chip tooltip), or null. */
    blockingMessage(id: OnboardingStageId): string | null {
        return (this.stageFindings()[id] ?? []).find((f) => f.severity === 'ERROR')?.message ?? null;
    }

    /** The first not-yet-configured stage in data-path order — where a resumed session lands
     *  (an unimplemented stage's placeholder honestly names the next step). */
    readonly firstOpenStage = computed<OnboardingStageId>(() => {
        const status = this.stageStatus();
        const next = this.stages().find((s) => status[s.id] === 'empty');
        return (next ?? this.stages()[0]).id;
    });

    load(name: string): void {
        this.name.set(name);
        this.loading.set(true);
        this.missing.set(false);
        this.enrichmentConfig.set(null);
        this.stageFindings.set({});
        this.configApi.read('pipeline', name).subscribe({
            next: (r) => {
                this.config.set(r.config);
                this.loading.set(false);
                // Streams may carry a companion enrichment; 404 just means none authored yet.
                if (this.kind() === 'stream') {
                    this.configApi.read('enrichment', this.enrichName()).subscribe({
                        next: (er) => this.enrichmentConfig.set(er.config),
                        error: () => this.enrichmentConfig.set(null),
                    });
                }
            },
            error: (e) => {
                this.loading.set(false);
                if (e?.status === 404) this.missing.set(true);
                else if (e?.status === 503) this.writesDisabled.set(true);
                else this.toastr.error(apiErrorMessage(e, `Could not load "${name}".`));
            },
        });
    }

    /**
     * Stage save: POST the patch to `/config/patch`, which deep-merges it over the file's CURRENT
     * on-disk content server-side (collector-config unification, 2026-08-04 — a stale client-held
     * copy can no longer clobber blocks this stage didn't edit, e.g. a graph save landing between
     * this pane's load and its save). `undefined` patch values delete their key ({@link
     * nullifyDeletes} converts them to explicit `null`s for the wire). The local `config` signal
     * still updates optimistically with the same merge.
     */
    saveBlock(patch: Record<string, unknown>): Observable<ConfigWriteResult> {
        const next = mergeBlock(this.config() ?? {}, patch);
        return this.configApi.patch('pipeline', this.name(), nullifyDeletes(patch)).pipe(
            tap({
                next: (r) => {
                    this.config.set(next);
                    // Route each finding to its stage by fieldPath prefix (fallback: the saving stage);
                    // replace every pipeline-stage bucket — the save validated the whole config.
                    const findings = r.findings ?? [];
                    const buckets: Partial<Record<OnboardingStageId, Finding[]>> = {
                        collection: [],
                        parsing: [],
                        schema: [],
                        keys: [],
                        publish: [],
                    };
                    for (const f of findings) {
                        const stage = this.stageForPath(f.fieldPath ?? '') ?? this.activeStageId();
                        (buckets[stage] ??= []).push(f);
                    }
                    this.stageFindings.update((m) => ({ ...m, ...buckets }));
                    if (findings.length) {
                        this.toastr.warning(
                            `${findings[0].message}${findings.length > 1 ? ` (+${findings.length - 1} more)` : ''}`,
                            'Saved with warnings',
                        );
                    }
                },
                error: (e) => {
                    if (e?.status === 503) this.writesDisabled.set(true);
                    else this.toastr.error(apiErrorMessage(e, 'Could not save the draft.'));
                },
            }),
        );
    }

    /** The stage a finding's dotted `fieldPath` belongs to, derived from the stage model's own
     *  `block` ownership (W4a — no second block→stage map to drift): `stages()` already carries the
     *  right stage per kind (`processing.*` lands on `schema` for a Stream, `keys` for a Reference).
     *  `active` belongs to publish (go-live flips it). Null for blank/cross-field paths (caller
     *  falls back to the saving stage). */
    private stageForPath(path: string): OnboardingStageId | null {
        if (path === 'active') return 'publish';
        return this.stages().find((s) => s.block && path.startsWith(s.block))?.id ?? null;
    }

    /**
     * What still references this stream — the shell reads it before confirming a discard, so the
     * operator is told what would dangle instead of finding out from a later job failure. A failure
     * to read it must NOT block the discard: the server re-checks and refuses on its own.
     */
    draftImpact(): Observable<ConfigImpact | null> {
        return this.configApi.impact(this.name()).pipe(catchError(() => of(null)));
    }

    /**
     * Draft discard — the server refuses an active pipeline (409); the shell confirms first. Deletes
     * the pipeline first (the authoritative, gated op), then best-effort cascades the guided companions
     * so no orphan `<name>_schema` / `<name>_enrich` configs linger. Companion failures (404 = never
     * authored, or anything else) don't fail the discard — the pipeline is already gone. (2026-07-20:
     * the backend's `DELETE /config/pipeline/{name}` now unregisters the in-memory pipeline entry
     * synchronously — `CollectorService.unregisterPipeline` — so the registry no longer ghosts the
     * deleted pipeline for up to a poll cycle.)
     */
    discardDraft(force = false): Observable<ConfigDeleteResult> {
        const name = this.name();
        return this.configApi
            .remove('pipeline', name, undefined, force)
            .pipe(
                switchMap((res) =>
                    forkJoin([
                        this.configApi.remove('schema', `${name}_schema`).pipe(catchError(() => of(null))),
                        this.configApi.remove('enrichment', `${name}_enrich`).pipe(catchError(() => of(null))),
                    ]).pipe(map(() => res)),
                ),
            );
    }

    captureSample(name: string, text: string): void {
        this.definition.captureSample(name, text);
    }

    clearSample(): void {
        this.definition.clearSample();
    }

    registerDirtyCheck(fn: () => boolean): void {
        this.dirtyCheck = fn;
    }

    unregisterDirtyCheck(fn: () => boolean): void {
        if (this.dirtyCheck === fn) this.dirtyCheck = null;
    }

    isDirty(): boolean {
        return this.dirtyCheck?.() ?? false;
    }
}
