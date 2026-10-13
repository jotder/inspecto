import { Injectable, Injector, computed, effect, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { EntityProjection, G6Node } from '@inspecto/core/graph';
import {
    DraftReplayResult,
    ExpandRung,
    InvService,
    InvestigationLog,
    InvestigationOpRequest,
    InvestigationReplayResult,
    InvestigationStepResult,
    WorkingSet,
} from '@inspecto/link-analysis/api/inv.service';
import {
    InvestigationRef,
    effectiveOpSteps,
    entityListErrorMessage,
    investigationErrorMessage,
    truncatedSteps,
    workingSetToGraph,
} from './investigation-state';
import { ProjectedGraph } from './entity-projection';
import { NeighbourRow } from './neighbour-ranking';
import { LA_DATASETS } from '@inspecto/link-analysis/la-host';
import { DatasetRowsService } from '@inspecto/core/viz/dataset-rows.service';
import { QueuedSeed } from './la-starter';
import { DegreePresets, DomainProfileId, domainProfile } from '@inspecto/link-analysis/graph/domain-profile';
import {
    DegreeOutcome,
    degreeOutcome,
    degreeOutcomesFromLog,
    hubBadgeText,
    oneHopFrom,
    degreeState,
    hubContactsFromRows,
    investigationTitle,
    isAwaiting,
    mergeRung,
    overrideSteps,
    overriddenHubs,
    sealedHubThreshold,
    presetRung,
    latestTimeOf,
    latestTimeSql,
    sealedWindowEnd,
    profileForDataset,
    reconcileApprovals,
    requestedDegree,
    windowLabel,
} from './investigate-number';

/** The query parameter that keeps the open Investigation across a reload (`?investigation=<id>`). */
export const INVESTIGATION_PARAM = 'investigation';
/** The most Working Set rows read for the flagged entities' contact counts (the route's own cap). */
const HUB_ROWS_LIMIT = 10_000;

const EMPTY_SET: WorkingSet = { entities: [], links: [], excluded: [], hash: '' };

interface Loaded {
    log: InvestigationLog;
    workingSet: WorkingSet;
    baseStep: number;
}

/** A Draft's replay in the main replay's shape: its set mismatches count as mismatches too; never a re-read. */
function fromDraftReplay(id: string, r: DraftReplayResult): InvestigationReplayResult {
    const mismatches = [...new Set([...r.mismatches, ...r.setMismatches])].sort((a, b) => a - b);
    return {
        id,
        at: r.at,
        workingSet: r.workingSet,
        equivalent: r.equivalent,
        mismatches,
        reread: false,
        drift: [],
        diverged: false,
    };
}

/**
 * LA-10 — the Link Analysis screen's Investigation session: which Investigation is open, its op log, and the
 * Working Set it evaluates to. Provided by `LinkAnalysisComponent`, so it outlives the toolbox dock (the panel
 * that renders it is destroyed whenever the dock collapses) and the canvas can read the Working Set graph.
 *
 * ⚠ `/ops` and `/undo` answer the Working Set as COUNTS plus a delta whose link changes are counts too, so the
 * canvas cannot be drawn from them. After every mutation the store re-reads `GET /log` and `POST /replay` —
 * `/replay` is the only route answering the full entities + links + exclusions.
 */
@Injectable()
export class InvestigationSessionStore {
    private inv = inject(InvService);
    private injector = inject(Injector);
    private router = inject(Router, { optional: true });
    private route = inject(ActivatedRoute, { optional: true });

    constructor() {
        // Keep `?investigation=<id>` in the URL while one is open (replaceUrl), so a reload or a persona switch reopens
        // it (the host adopts that param on init). The param is dropped only when THIS store closed what it had set.
        let synced: string | null = null;
        effect(() => {
            const id = this.activeId();
            if (!this.router || id === synced) return;
            const had = synced;
            synced = id;
            if (!id && !had) return;
            if (id && this.route?.snapshot.queryParamMap.get(INVESTIGATION_PARAM) === id) return;
            void this.router.navigate([], {
                ...(this.route ? { relativeTo: this.route } : {}),
                queryParams: { [INVESTIGATION_PARAM]: id },
                queryParamsHandling: 'merge',
                replaceUrl: true,
            });
        });
    }

    /** Every Investigation this screen knows about (created here or restored from a saved view). */
    readonly refs = signal<InvestigationRef[]>([]);
    readonly activeId = signal<string | null>(null);
    /** LA-UI-DRAFT-OPS-1: the Draft that is the working scope — ops, undo, the log and the Working Set go to it; null = the main log. */
    readonly activeDraftId = signal<string | null>(null);
    /** The main-log step the scope Draft forked at — log entries up to it are the main prefix, not the Draft's own. */
    readonly draftBaseStep = signal(0);
    readonly log = signal<InvestigationLog | null>(null);
    readonly workingSet = signal<WorkingSet | null>(null);
    readonly lastStep = signal<InvestigationStepResult | null>(null);
    readonly replayResult = signal<InvestigationReplayResult | null>(null);
    readonly busy = signal(false);
    readonly error = signal('');
    /** The canvas entity an op acts on; set by a node click while an Investigation is open. */
    readonly selected = signal<G6Node | null>(null);
    /** Draw the Working Set (true) or the query graph — seeds are picked from the latter. */
    readonly showWorkingSet = signal(true);

    /**
     * Ranked nodes carried over from an analysis result ("Start an Investigation from the top results"), waiting for
     * the analyst to confirm: nothing is created or seeded until they press Start (or Seed) in the Investigation tab.
     */
    readonly queuedSeeds = signal<QueuedSeed[]>([]);

    /** "Expand next degree": what each degree expanded in THIS session did (the open Investigation only). */
    readonly degreeOutcomes = signal<DegreeOutcome[]>([]);
    /** The held degree still waiting for its four-eyes decision, or null — the panel polls while it is set. */
    readonly awaitingApproval = computed(() => {
        const last = this.degreeOutcomes().at(-1);
        return isAwaiting(last) ? last!.awaitingApproval! : null;
    });
    /**
     * Hub suppression: each flagged entity's sealed contact count, as this session's steps answered it
     * (`read.hubs`). The Working Set rows' own `contacts` wins; this fills in where a replay row has none.
     */
    readonly hubContacts = signal<ReadonlyMap<string, number>>(new Map());

    readonly active = computed(() => this.activeId() !== null);
    readonly activeRef = computed(() => this.refs().find((r) => r.id === this.activeId()) ?? null);
    readonly header = computed(() => this.log()?.header ?? null);
    /** The open Investigation's name: the analyst's own title when the server masked it, never a raw pseudonym. */
    readonly displayTitle = computed(() => {
        const id = this.activeId();
        return id ? investigationTitle(this.header()?.title, this.activeRef()?.title, id) : '';
    });
    /**
     * The domain profile the open Investigation belongs to — remembered on its ref, else the profile whose default
     * mapping binds its Dataset. The host switches its profile to this on open/reload; null = leave it as it is.
     */
    readonly boundProfileId = computed<DomainProfileId | null>(
        () => this.activeRef()?.profileId ?? profileForDataset(this.header()?.dataset),
    );
    /** The "Expand anyway" steps in effect — their hub's contacts are kept out of the next degree's frontier. */
    readonly overrideSteps = computed(() => overrideSteps(this.log()?.entries ?? []));
    /** The flagged entities an override in effect expanded through (applied, or approved and run) — read from the log. */
    readonly overriddenHubs = computed(() => {
        const flagged = new Set((this.workingSet()?.entities ?? []).filter((e) => e.highConnectivity).map((e) => e.id));
        return overriddenHubs(this.log()?.entries ?? [], flagged);
    });
    /** The hub threshold the latest expand in effect sealed — survives a reload, unlike `lastStep`. */
    readonly sealedHubThreshold = computed(() => sealedHubThreshold(this.log()?.entries ?? []));
    /**
     * One flagged entity's badge, from the same sources as the Investigation panel: the last step's sealed threshold,
     * else the log's, else `fallbackThreshold` (the Space's value in force); its contacts from the row, the session's
     * reads or the Working Set; and whether an override expanded through it. Node dialog and Working Set rows use it.
     */
    hubBadge(id: string, contacts?: number | null, fallbackThreshold?: number | null): string {
        const sealed = this.lastStep()?.read?.rung?.['hubThreshold'];
        const threshold = typeof sealed === 'number' ? sealed : (this.sealedHubThreshold() ?? fallbackThreshold ?? null);
        const count =
            contacts ??
            this.hubContacts().get(id) ??
            this.workingSet()?.entities.find((e) => e.id === id)?.contacts ??
            null;
        return hubBadgeText(threshold, count, this.overriddenHubs().has(id));
    }
    readonly effectiveSteps = computed(() => effectiveOpSteps(this.log()?.entries ?? []));
    readonly truncatedSteps = computed(() => truncatedSteps(this.log()?.entries ?? []));
    /** A Draft's Undo reverts only its OWN steps, so the main-log prefix it forked from never counts. */
    readonly canUndo = computed(() => {
        const base = this.activeDraftId() ? this.draftBaseStep() : 0;
        return this.effectiveSteps().some((e) => e.step > base);
    });
    /** The projection the Investigation is bound to, rebuilt from its header (+ the remembered entity type). */
    readonly binding = computed<EntityProjection | null>(() => {
        const h = this.header();
        if (!h) return null;
        return {
            datasetId: h.dataset,
            sourceCol: h.sourceCol,
            targetCol: h.targetCol,
            linkKindCol: h.linkKindCol ?? undefined,
            // A reopened Investigation (reload) has no remembered type: its profile's investigated type stands in.
            entityType:
                this.activeRef()?.entityType ??
                (this.boundProfileId() ? domainProfile(this.boundProfileId()!).investigate?.entityType : undefined),
            sourceType: this.activeRef()?.sourceType,
            targetType: this.activeRef()?.targetType,
        };
    });
    readonly workingSetGraph = computed<ProjectedGraph | null>(() => {
        const ws = this.workingSet();
        const p = this.binding();
        return ws && p ? workingSetToGraph(ws, p) : null;
    });
    /** What the canvas draws instead of the query graph; null = the query graph. */
    readonly canvas = computed<ProjectedGraph | null>(() =>
        this.active() && this.showWorkingSet() ? this.workingSetGraph() : null,
    );

    /** Replace the queue with these ranked nodes. */
    queueSeeds(seeds: QueuedSeed[]): void {
        this.queuedSeeds.set([...seeds]);
    }

    unqueueSeed(id: string): void {
        this.queuedSeeds.update((all) => all.filter((s) => s.id !== id));
    }

    clearQueuedSeeds(): void {
        this.queuedSeeds.set([]);
    }

    /** A saved view brought its Investigations back — remember them; open none (opening replays). */
    restore(refs: InvestigationRef[]): void {
        this.close();
        this.refs.set([...refs]);
    }

    /** Leave the open Investigation (it stays remembered). */
    close(): void {
        this.activeId.set(null);
        this.activeDraftId.set(null);
        this.draftBaseStep.set(0);
        this.log.set(null);
        this.workingSet.set(null);
        this.lastStep.set(null);
        this.replayResult.set(null);
        this.selected.set(null);
        this.error.set('');
        this.degreeOutcomes.set([]);
        this.hubContacts.set(new Map());
        this.windowEnd.set(null);
        this.lastOverride.set(null);
    }

    /** Stop remembering an id (e.g. one the server no longer answers for). Nothing is deleted server-side. */
    forget(id: string): void {
        if (this.activeId() === id) this.close();
        this.refs.update((all) => all.filter((r) => r.id !== id));
    }

    /** Create an Investigation over the projection's Dataset + columns and open it. `timeCol` binds the event time
     *  windows and the coverage read use — without it `GET …/coverage` answers 422. `timeColZone` is the IANA zone a naive
     *  TIMESTAMP column is read in (UTC when absent). `eventsCol` weighs each link by that column's SUM (a pre-aggregated
     *  Dataset) instead of its row count. */
    async start(
        p: EntityProjection,
        purpose: string,
        title?: string,
        timeCol?: string,
        timeColZone?: string,
        eventsCol?: string,
        entityType?: string,
        profileId?: DomainProfileId,
    ): Promise<boolean> {
        return this.run('Could not start the Investigation.', async () => {
            const h = await firstValueFrom(
                this.inv.createInvestigation({
                    title: title || undefined,
                    purpose,
                    dataset: p.datasetId,
                    sourceCol: p.sourceCol,
                    targetCol: p.targetCol,
                    linkKindCol: p.linkKindCol || undefined,
                    timeCol: timeCol || undefined,
                    // A zone means nothing without a time column (the server refuses it), so it never travels alone.
                    timeColZone: timeCol && timeColZone ? timeColZone : undefined,
                    eventsCol: eventsCol || undefined,
                    // Types the admitted endpoints (e.g. msisdn) so Entity Lists and masking know what they are.
                    entityType: entityType || undefined,
                }),
            );
            this.refs.update((all) => [
                ...all,
                {
                    id: h.id,
                    // The analyst's own title as typed: the server may answer it masked (a title is a value).
                    title: title || h.title || undefined,
                    ...(profileId ? { profileId } : {}),
                    entityType: p.entityType,
                    sourceType: p.sourceType,
                    targetType: p.targetType,
                },
            ]);
            this.close();
            this.activeId.set(h.id);
            // A fresh Investigation has an empty log and an empty Working Set — no replay needed.
            this.workingSet.set(EMPTY_SET);
            this.log.set(await firstValueFrom(this.inv.investigationLog(h.id)));
        });
    }

    /**
     * "Expand next degree": one `expand` from the visible entities at the Working Set's outermost hop, with the
     * profile's preset rung. Records the degree's outcome; false (with `error` set) when blocked or refused.
     *
     * High-connectivity entities at that hop are left out of the frontier — the server refuses a named flagged id —
     * unless the analyst opted in (`includeHubs`), and only then does the op carry `includeHubs: true`, which the
     * server seals in the step's params as the audited override.
     */
    async expandNextDegree(
        presets: DegreePresets,
        maxDegree: number,
        includeHubs = false,
        advanced: ExpandRung | null = null,
    ): Promise<boolean> {
        if (this.expanding) return false; // one degree at a time: a second click while one runs is ignored
        const state = degreeState(
            this.workingSet(),
            maxDegree,
            this.degreeOutcomes(),
            includeHubs,
            this.overrideSteps(),
        );
        if (state.blocked) {
            this.error.set(state.blocked);
            return false;
        }
        const id = this.activeId();
        const end = this.windowEnd();
        const overriding = includeHubs && state.heldHubs.length > 0;
        // The analyst's Advanced fields (e.g. minimum events per link) win over the profile's presets.
        const rung = {
            ...mergeRung(presetRung(presets, !!this.header()?.timeCol, end), advanced),
            ...(overriding ? { includeHubs: true } : {}),
        };
        this.expanding = true;
        try {
            if (!(await this.apply({ op: 'expand', ids: state.frontier, ...rung }))) return false;
        } finally {
            this.expanding = false;
        }
        if (this.activeId() !== id) return false; // switched away meanwhile - the answer belongs to another one
        const step = this.lastStep();
        const own = advanced?.window;
        const range =
            own === 'full'
                ? 'all available data'
                : own
                  ? 'the window set for this degree'
                  : rung.window
                    ? windowLabel(presets, end)
                    : presets.windowDays != null
                      ? 'all available data'
                      : null;
        if (step) {
            const outcome = degreeOutcome(state.current + 1, step, rung, range, this.workingSet());
            if (overriding && step.status !== 'pending') {
                outcome.hubsOverridden = state.heldHubs.length;
                this.lastOverride.set({ step: step.step, ids: state.heldHubs });
            }
            // The refresh may already have listed a held request from the log — this outcome replaces that line.
            const held = outcome.awaitingApproval;
            this.degreeOutcomes.update((all) => [...all.filter((o) => !held || o.awaitingApproval !== held), outcome]);
        }
        return true;
    }

    /**
     * The last hub override this session appended — the step that sealed it and the entities it expanded through —
     * so the panel can say the override is on record. Null until one runs (and after the Investigation closes).
     */
    readonly lastOverride = signal<{ step: number; ids: string[] } | null>(null);

    /**
     * "Expand anyway": one hop from a flagged entity, overriding the hub suppression for it alone (`expandHubs`).
     * The caller has already asked the analyst to confirm; the override is sealed in the step's params.
     */
    async expandHub(id: string, rung: ExpandRung = {}): Promise<boolean> {
        const rest: ExpandRung = { ...rung };
        delete rest.includeHubs; // the override names this entity only
        delete rest.expandHubs;
        // The hop this override admits at: one past the hub's own — the held and the approved line both say it.
        const degree = requestedDegree(this.workingSet(), [id]) ?? 1;
        if (!(await this.apply({ op: 'expand', ids: [id], ...rest, expandHubs: [id] }))) return false;
        const step = this.lastStep();
        if (step && step.status !== 'pending') this.lastOverride.set({ step: step.step, ids: [id] });
        if (step?.status === 'pending') {
            const outcome: DegreeOutcome = { ...degreeOutcome(degree, step, rest), override: id };
            this.degreeOutcomes.update((all) => [
                ...all.filter((o) => o.awaitingApproval !== outcome.awaitingApproval),
                outcome,
            ]);
        }
        return true;
    }

    /**
     * LA-EXPAND-PICK-NEIGHBOURS-1: the selected entity's neighbours, a READ-ONLY hint for choosing what to expand to (nothing
     * is written until the analyst adds some). The server folds them per neighbour, strongest first, and says the REAL
     * `total`; the list is read a window at a time (`moreNeighbours`) and is never held by the four-eyes fan-out threshold.
     */
    readonly neighbours = signal<{
        of: string;
        rows: NeighbourRow[];
        /** The entity's real number of distinct neighbours. */
        total: number;
        hasMore: boolean;
        loading: boolean;
        error: string;
    } | null>(null);

    async loadNeighbours(value: string): Promise<void> {
        this.neighbours.set({ of: value, rows: [], total: 0, hasMore: false, loading: true, error: '' });
        await this.readNeighbourWindow(value, 0);
    }

    /** The next window of the list already shown. */
    async moreNeighbours(): Promise<void> {
        const cur = this.neighbours();
        if (!cur || cur.loading || !cur.hasMore) return;
        this.neighbours.set({ ...cur, loading: true });
        await this.readNeighbourWindow(cur.of, cur.rows.length);
    }

    private async readNeighbourWindow(value: string, offset: number): Promise<void> {
        const h = this.header();
        if (!h) return;
        // DR-D2: on a masked Space this read speaks the Space's alias, the Working Set this Investigation's own - the
        // Working Set's `exploreAliases` pairs them. Send the Space's alias, show this Investigation's wherever it has one.
        const toExplore = this.workingSet()?.exploreAliases ?? {};
        const back = new Map(Object.entries(toExplore).map(([inv, explore]) => [explore, inv]));
        const sent = toExplore[value] ?? value;
        const before = this.neighbours()?.rows ?? [];
        try {
            const res = await firstValueFrom(
                this.inv.neighbourList({
                    dataset: h.dataset,
                    sourceCol: h.sourceCol,
                    targetCol: h.targetCol,
                    linkKindCol: h.linkKindCol ?? undefined,
                    value: sent,
                    offset,
                }),
            );
            if (this.neighbours()?.of !== value) return; // another entity was picked meanwhile
            this.neighbours.set({
                of: value,
                rows: [...before, ...res.neighbours.map((n) => ({ ...n, id: back.get(n.id) ?? n.id }))],
                total: res.total || this.neighbours()?.total || 0,
                hasMore: res.hasMore,
                loading: false,
                error: '',
            });
        } catch (err) {
            if (this.neighbours()?.of !== value) return;
            this.neighbours.set({
                of: value,
                rows: before,
                total: this.neighbours()?.total ?? 0,
                hasMore: false,
                loading: false,
                error: investigationErrorMessage(err, 'The neighbours could not be read.'),
            });
        }
    }

    clearNeighbours(): void {
        this.neighbours.set(null);
    }

    /**
     * One hop from `id` to ONLY the chosen neighbours (`only`), recorded as one `expand` step. `throughHub` is the analyst's
     * confirmed override for a flagged `id` (the caller has asked); it is sealed in the step like "Expand anyway".
     */
    async expandPicked(id: string, only: string[], rung: ExpandRung = {}, throughHub = false): Promise<boolean> {
        const rest: ExpandRung = { ...rung };
        delete rest.includeHubs; // the override names this entity only
        delete rest.expandHubs;
        if (!(await this.apply({ op: 'expand', ids: [id], ...rest, only, ...(throughHub ? { expandHubs: [id] } : {}) })))
            return false;
        const step = this.lastStep();
        if (throughHub && step && step.status !== 'pending') this.lastOverride.set({ step: step.step, ids: [id] });
        return true;
    }

    /**
     * The profile's preset rung (window + budget + caps) laid under the analyst's own rung fields, for an expand that
     * is not "Expand next degree" (one hop from the selection, every entity, "Expand anyway"). Without it those
     * expands read with NO window and the server's default budget. `includeHubs` / `expandHubs` are kept — the caller
     * decides them; an Advanced `window` (incl. `'full'`, All time) wins over the preset window.
     */
    withPresets(presets: DegreePresets, rung: ExpandRung): ExpandRung {
        return { ...presetRung(presets, !!this.header()?.timeCol, this.windowEnd()), ...rung };
    }

    /** Bumped when an Entity List changed outside the Entity Lists panel (the node dialog's add): it re-reads. */
    readonly entityListsChanged = signal(0);

    private expanding = false;
    /** True while "Investigate a number" runs its create → seed → expand chain; the panel disables its buttons. */
    readonly chainRunning = signal(false);
    /**
     * The end of the preset window: the bound Dataset's latest event time, looked up when the Investigation was
     * created here. Null = unknown, and then no window is sent ("all available data") — never "today".
     */
    readonly windowEnd = signal<string | null>(null);

    /**
     * "Investigate a number": create the Investigation, seed the id, then expand `degrees` degrees with the presets.
     * Stops at the first refusal (its message is in `error`) and when a degree finds nobody new.
     */
    async investigateNumber(req: {
        projection: EntityProjection;
        purpose: string;
        title: string;
        timeCol?: string;
        eventsCol?: string;
        seed: string;
        presets: DegreePresets;
        maxDegree: number;
        degrees: number;
        /** The Dataset's latest event time — the window's end. */
        windowEnd?: string | null;
        /** The analyst's Advanced rung fields, laid over the presets on every degree. */
        advanced?: ExpandRung | null;
        /** The profile the deep link bound — remembered on the ref so a reopen restores it. */
        profileId?: DomainProfileId;
    }): Promise<boolean> {
        if (this.chainRunning()) return false;
        this.chainRunning.set(true);
        try {
            return await this.runInvestigateChain(req);
        } finally {
            this.chainRunning.set(false);
        }
    }

    private async runInvestigateChain(
        req: Parameters<InvestigationSessionStore['investigateNumber']>[0],
    ): Promise<boolean> {
        if (
            !(await this.start(
                req.projection,
                req.purpose,
                req.title,
                req.timeCol,
                undefined,
                req.eventsCol,
                req.projection.entityType,
                req.profileId,
            ))
        )
            return false;
        const id = this.activeId();
        this.windowEnd.set(req.windowEnd ?? null);
        const entityType = req.projection.entityType;
        if (!(await this.apply({ op: 'seed', ids: [req.seed], ...(entityType ? { entityType } : {}) }))) return false;
        this.showWorkingSet.set(true);
        // A held (pending-approval) degree blocks the next one, so the loop stops there - no error, no retry.
        for (let i = 0; i < req.degrees; i++) {
            if (this.activeId() !== id) return false; // closed or switched mid-chain: stop, never act on another one
            // The chain never opts into hubs: an override is the analyst's own, confirmed decision.
            const s = degreeState(this.workingSet(), req.maxDegree, this.degreeOutcomes(), false, this.overrideSteps());
            if (s.blocked) break;
            if (!(await this.expandNextDegree(req.presets, req.maxDegree, false, req.advanced ?? null))) return false;
        }
        return true;
    }

    /** Open a remembered Investigation: its log and (via replay) its Working Set. */
    async open(id: string): Promise<boolean> {
        // Reopening the open one refreshes it in place: its degree results stay and pick up a decision made elsewhere.
        if (id === this.activeId() && !this.activeDraftId())
            return this.run('Could not open the Investigation.', () => this.refresh(id));
        this.close();
        this.activeId.set(id);
        this.rebuildOutcomes = true; // the session's degree lines are gone: rebuild them from the sealed log
        const ok = await this.run('Could not open the Investigation.', () => this.refresh(id));
        if (ok) await this.recoverWindowEnd(id);
        return ok;
    }

    /**
     * A reopened Investigation (incl. the `?investigation=` restore after a reload) has no window end — recover it so
     * its expands keep the preset window: first the window sealed in the latest live expand step that carried one (the
     * analyst continues on the same window), else the bound Dataset's latest event time read with the same zone-free
     * anchor query "Investigate a number" uses. Neither → stays null ("all available data", truthful).
     */
    private async recoverWindowEnd(id: string): Promise<void> {
        const presets = domainProfile(this.boundProfileId()).investigate?.expand;
        const timeCol = this.header()?.timeCol;
        if (presets?.windowDays == null || !timeCol) return;
        const end = sealedWindowEnd(this.log()?.entries ?? [], presets.windowDays) ?? (await this.latestTime(timeCol));
        if (end && this.activeId() === id && !this.windowEnd()) this.windowEnd.set(end);
    }

    /** The bound Dataset's latest event time over `/db/query`; null when it cannot be read. */
    private async latestTime(timeCol: string): Promise<string | null> {
        const dataset = this.header()?.dataset;
        if (!dataset) return null;
        try {
            const ds = await firstValueFrom(this.injector.get(LA_DATASETS).get(dataset));
            if (!ds.sourceName) return null;
            const rows = this.injector.get(DatasetRowsService);
            const res = await rows.sql(ds.sourceName, latestTimeSql(ds.sourceName, timeCol), 1);
            return res.error ? null : latestTimeOf(res.rows[0]?.['latest']);
        } catch {
            return null;
        }
    }

    /**
     * Re-read the open Investigation's log and Working Set without closing it — after a decision made elsewhere
     * (an approval in Oversight), on reopen of the same id, on focus, and while a held degree is polled. Quiet: it
     * neither sets `busy` nor replaces a shown error; false when nothing is open, a mutation is running, or it failed.
     */
    async reload(): Promise<boolean> {
        const id = this.activeId();
        if (!id || this.busy() || this.expanding) return false;
        try {
            await this.refresh(id);
            return true;
        } catch {
            return false;
        }
    }

    /** Append one op, then re-read the log and the Working Set. */
    async apply(op: InvestigationOpRequest): Promise<boolean> {
        const id = this.activeId();
        if (!id) return false;
        // LA-17: a list-bound op's 404/409 is usually about the Entity List, not the Investigation.
        const message =
            op.op === 'excludeBy' || op.op === 'seedBy'
                ? (err: unknown, fallback: string) => entityListErrorMessage(err, fallback, true)
                : investigationErrorMessage;
        return this.run(
            `The ${op.op} step failed.`,
            async () => {
                const draft = this.activeDraftId();
                const step = await firstValueFrom(
                    draft ? this.inv.appendDraftOp(id, draft, op) : this.inv.appendInvestigationOp(id, op),
                );
                this.lastStep.set(step);
                this.noteHubs(step);
                await this.refresh(id);
            },
            message,
        );
    }

    async undo(): Promise<boolean> {
        const id = this.activeId();
        if (!id) return false;
        return this.run('Undo failed.', async () => {
            const draft = this.activeDraftId();
            this.lastStep.set(
                await firstValueFrom(draft ? this.inv.undoDraft(id, draft) : this.inv.undoInvestigation(id)),
            );
            await this.refresh(id);
        });
    }

    /** Make a Draft (or, with null, the main log) the working scope. The scope changes only once its log and Working
     *  Set have loaded — a Draft discarded elsewhere (404) leaves the current scope as it was, with the error shown. */
    async useDraft(draftId: string | null): Promise<boolean> {
        const id = this.activeId();
        if (!id) return false;
        return this.run(draftId ? 'Could not open the Draft.' : 'Could not open the Investigation.', async () => {
            const loaded = await this.load(id, draftId);
            if (this.activeId() !== id) return;
            this.activeDraftId.set(draftId);
            this.lastStep.set(null);
            this.replayResult.set(null);
            this.commit(loaded);
        });
    }

    /** D-E4: re-ordering creates a FORK — remember it with its parent and switch to it. */
    async fork(order: number[]): Promise<boolean> {
        const parent = this.activeRef();
        if (!parent) return false;
        return this.run('Creating the fork failed.', async () => {
            const res = await firstValueFrom(this.inv.reorderInvestigation(parent.id, { order }));
            this.refs.update((all) => [
                ...all,
                {
                    id: res.id,
                    title: parent.title,
                    entityType: parent.entityType,
                    sourceType: parent.sourceType,
                    targetType: parent.targetType,
                    parentId: parent.id,
                },
            ]);
            this.close();
            this.activeId.set(res.id);
            await this.refresh(res.id);
        });
    }

    /** LA-23: a template was instantiated into a NEW Investigation — remember it and open it. */
    async adopt(
        id: string,
        title?: string,
        like?: Pick<InvestigationRef, 'entityType' | 'sourceType' | 'targetType'>,
    ): Promise<boolean> {
        const ids = { entityType: like?.entityType, sourceType: like?.sourceType, targetType: like?.targetType };
        if (!this.refs().some((r) => r.id === id)) this.refs.update((all) => [...all, { id, title, ...ids }]);
        return this.open(id);
    }

    /** Full evaluation from the sealed log; with `reread`, per-expand drift against current data. */
    async replay(reread: boolean): Promise<boolean> {
        const id = this.activeId();
        if (!id) return false;
        const draft = this.activeDraftId();
        return this.run('Replay failed.', async () => {
            // A Draft's replay is the equivalence check only — it has no re-read (drift), so `reread` is not offered.
            const r = draft
                ? fromDraftReplay(id, await firstValueFrom(this.inv.draftReplay(id, draft)))
                : await firstValueFrom(this.inv.replayInvestigation(id, { reread }));
            this.replayResult.set(r);
            this.workingSet.set(r.workingSet);
        });
    }

    private async refresh(id: string): Promise<void> {
        const draft = this.activeDraftId();
        const loaded = await this.load(id, draft);
        if (this.activeId() !== id || this.activeDraftId() !== draft) return; // switched away meanwhile
        this.commit(loaded);
        if (!draft) await this.loadHubContacts(id);
    }

    /**
     * `/replay` rows carry no `contacts`, and a hub flagged by a step this session did not run (approved by someone
     * else, or before a reopen) has none in `hubContacts` either — read them from `GET working-set?of=entities`,
     * which serves each flagged entity's sealed count. Quiet: a refusal leaves the threshold wording in place.
     */
    private hubRowsRead = '';
    private async loadHubContacts(id: string): Promise<void> {
        const known = this.hubContacts();
        const missing = (this.workingSet()?.entities ?? []).some(
            (e) => e.highConnectivity && e.contacts == null && !known.has(e.id),
        );
        // Once per Working Set head: a poll of an unchanged set does not read the rows again.
        const key = `${id}@${this.workingSet()?.hash ?? ''}`;
        if (!missing || key === this.hubRowsRead) return;
        this.hubRowsRead = key;
        try {
            const page = await firstValueFrom(
                this.inv.workingSetRelation(id, { of: 'entities', limit: HUB_ROWS_LIMIT }),
            );
            if (this.activeId() !== id) return;
            const read = hubContactsFromRows(page.rows ?? []);
            if (!read.size) return;
            this.hubContacts.set(new Map([...this.hubContacts(), ...read]));
        } catch {
            // the step-read counts and the threshold wording stand
        }
    }

    /** Read the log and the Working Set of the main log or of a Draft — without touching the store. */
    private async load(id: string, draft: string | null): Promise<Loaded> {
        if (draft) {
            const [log, replay] = await Promise.all([
                firstValueFrom(this.inv.draftLog(id, draft)),
                firstValueFrom(this.inv.draftReplay(id, draft)),
            ]);
            return { log, workingSet: replay.workingSet, baseStep: replay.baseStep };
        }
        const [log, replay] = await Promise.all([
            firstValueFrom(this.inv.investigationLog(id)),
            firstValueFrom(this.inv.replayInvestigation(id, {})),
        ]);
        return { log, workingSet: replay.workingSet, baseStep: 0 };
    }

    private commit(l: Loaded): void {
        this.log.set(l.log);
        this.workingSet.set(l.workingSet);
        this.draftBaseStep.set(l.baseStep);
        const base = this.rebuildOutcomes ? degreeOutcomesFromLog(l.log.entries, l.workingSet) : this.degreeOutcomes();
        this.rebuildOutcomes = false;
        this.degreeOutcomes.set(reconcileApprovals(base, l.log.pending, l.workingSet));
    }

    private rebuildOutcomes = false;

    /** A held expand from the panel is one hop from what it named — never the ladder's next degree. */
    markOneHop(requestId: string, ids: readonly string[] | undefined): void {
        const oneHop = oneHopFrom(ids);
        this.degreeOutcomes.update((all) =>
            all.map((o) => (o.awaitingApproval === requestId && !o.override ? { ...o, oneHop } : o)),
        );
    }

    /** Remember the sealed contact counts a step's read answered for its flagged entities. */
    private noteHubs(step: InvestigationStepResult | null | undefined): void {
        const hubs = step?.read?.hubs;
        if (!hubs?.length) return;
        const next = new Map(this.hubContacts());
        for (const h of hubs) if (typeof h.degree === 'number') next.set(h.id, h.degree);
        this.hubContacts.set(next);
    }

    private async run(
        fallback: string,
        body: () => Promise<void>,
        message: (err: unknown, fallback: string) => string = investigationErrorMessage,
    ): Promise<boolean> {
        this.busy.set(true);
        this.error.set('');
        try {
            await body();
            return true;
        } catch (err) {
            this.error.set(message(err, fallback));
            return false;
        } finally {
            this.busy.set(false);
        }
    }
}
