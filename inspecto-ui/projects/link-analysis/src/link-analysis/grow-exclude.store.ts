import { Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import {
    ExclusionListSummary,
    GrowPreview,
    InvService,
    isHeldListChange,
} from '@inspecto/link-analysis/api/inv.service';
import { LensService } from '@inspecto/core/api';
import { InvestigationSessionStore } from './link-analysis-investigation.store';
import { entityListErrorMessage } from './investigation-state';
import {
    GrowContext,
    GrowRequest,
    PackTerms,
    growExpandBody,
    growIds,
    heldReason,
    listsInForce,
    previewFailure,
} from './grow-exclude';
import type { DegreePresets } from '@inspecto/link-analysis/graph/domain-profile';

/** The one-line result of the last Grow / Exclude, with its Undo (§3.1 "After the step"). */
export interface LastChange {
    text: string;
    /** Absent when the change cannot be undone from here (e.g. held for approval). */
    undo?: () => Promise<void> | void;
    /**
     * Whether the Undo still takes back exactly this change: false once something else changed after it (another
     * step in the log, another expand on the explorer graph). Absent = always.
     */
    valid?: () => boolean;
}

/** The preview the Grow panel shows. */
export interface PreviewState {
    state: 'idle' | 'loading' | 'ready' | 'unavailable' | 'error';
    preview: GrowPreview | null;
    message: string;
}

/** The explorer's (Basic) side of Grow, registered by the Link Analysis screen — it owns the explorer graph. */
export interface BasicGrowAdapter {
    preview(frontier: string[], req: GrowRequest): Promise<GrowPreview | null>;
}

/**
 * LA-GROW-EXCLUDE-1 — the session state of Grow, the Held tray and Exclude: the exclusion lists, the last change and its
 * Undo, and the Investigation actions (each an existing op — `expand`, `exclude`, `excludeBy`, `undo`; GE-12). Provided
 * by `LinkAnalysisComponent` beside `InvestigationSessionStore`.
 */
@Injectable()
export class GrowExcludeStore {
    private inv = inject(InvService);
    private lens = inject(LensService);
    private investigation = inject(InvestigationSessionStore);

    /** The Space's exclusion lists; null = not read (yet), or unreadable — then list chips are simply not shown. */
    readonly lists = signal<ExclusionListSummary[] | null>(null);
    readonly lastChange = signal<LastChange | null>(null);
    readonly busy = signal(false);
    readonly growOpen = signal(false);
    readonly exclusionsOpen = signal(false);
    /** Basic: busy nodes the last Grow previews named (id → contacts) — drawn held, not grown further. */
    readonly basicHeld = signal<ReadonlyMap<string, number>>(new Map());
    basicAdapter: BasicGrowAdapter | null = null;

    constructor() {
        // An Undo belongs to the Investigation (or graph) it was made on: another one opening drops it.
        let scope: string | null | undefined;
        effect(() => {
            const now = `${this.investigation.activeId()}|${this.investigation.activeDraftId?.() ?? ''}`;
            if (scope !== undefined && scope !== now) untracked(() => this.lastChange.set(null));
            scope = now;
        });
    }

    /** Back to nothing open (Close, a fresh query): no panel, no pending Undo, no held explorer items. */
    reset(): void {
        this.lastChange.set(null);
        this.growOpen.set(false);
        this.exclusionsOpen.set(false);
        this.basicHeld.set(new Map());
        this.resetPreview();
    }

    /** Who may see a restricted list's members (GE-8): administrators of the categories and holders of reveal. */
    readonly canSeeRestricted = computed(
        () => this.lens.canManageExclusionCategories() || this.lens.canRevealLinkEntities(),
    );

    readonly inForce = computed(() =>
        listsInForce(
            this.investigation.log()?.entries ?? [],
            this.investigation.workingSet(),
            this.lists(),
            this.canSeeRestricted(),
        ),
    );

    private listsRead = false;

    /** Read the lists once per screen: `/inv/exclusion-lists`, else the plain Entity Lists of purpose exclusion. */
    async loadLists(force = false): Promise<void> {
        if (this.listsRead && !force) return;
        this.listsRead = true;
        try {
            this.lists.set((await firstValueFrom(this.inv.exclusionLists())).lists ?? []);
            return;
        } catch {
            // an older server: fall back to the Entity Lists it has
        }
        try {
            const idx = await firstValueFrom(this.inv.listEntityLists());
            this.lists.set(
                (idx.lists ?? [])
                    .filter((l) => l.purpose === 'exclusion' && !l.retired)
                    .map((l) => ({
                        id: l.id,
                        title: l.title,
                        entityType: l.entityType,
                        ownerKind: l.ownerKind ?? 'analyst',
                        autoApply: l.autoApply ?? false,
                        visibility: l.visibility ?? 'open',
                        size: l.size,
                    })),
            );
        } catch {
            this.lists.set(null);
        }
    }

    // ── preview ──

    readonly preview = signal<PreviewState>({ state: 'idle', preview: null, message: '' });
    private previewSeq = 0;
    /** The server answered that the preview route does not exist (404/405/501): no more previews this session. */
    private previewAbsent = { investigation: false, basic: false };

    /** A dry run of what `req` would do from `ctx` — numbers, or quietly none when the server has no preview. */
    async runPreview(ctx: GrowContext, req: GrowRequest, presets: DegreePresets): Promise<void> {
        const seq = ++this.previewSeq;
        const ids = growIds(ctx, req);
        if (ctx.blocked || !ids.length) {
            this.preview.set({ state: 'idle', preview: null, message: '' });
            return;
        }
        if (this.previewAbsent[ctx.mode]) {
            this.preview.set({ state: 'unavailable', preview: null, message: '' });
            return;
        }
        this.preview.set({ state: 'loading', preview: null, message: '' });
        try {
            let p: GrowPreview | null;
            if (ctx.mode === 'investigation') {
                const id = this.investigation.activeId();
                if (!id) {
                    this.preview.set({ state: 'idle', preview: null, message: '' });
                    return;
                }
                const body = growExpandBody(ids, req, this.investigation.withPresets(presets, {}));
                p = await firstValueFrom(this.inv.previewExpand(id, body));
            } else {
                p = this.basicAdapter ? await this.basicAdapter.preview(ids, req) : null;
            }
            if (seq !== this.previewSeq) return;
            this.preview.set(p ? { state: 'ready', preview: p, message: '' } : { state: 'unavailable', preview: null, message: '' });
        } catch (err) {
            if (seq !== this.previewSeq) return;
            const f = previewFailure(err);
            if (f.absent) this.previewAbsent[ctx.mode] = true;
            this.preview.set({ state: f.unavailable ? 'unavailable' : 'error', preview: null, message: f.message });
        }
    }

    resetPreview(): void {
        this.previewSeq++;
        this.preview.set({ state: 'idle', preview: null, message: '' });
    }

    // ── Investigation actions (each one recorded op) ──

    /** Grow 1 or 2 steps: one `expand` per step; the second grows from what the first admitted. */
    async growInvestigation(ctx: GrowContext, req: GrowRequest, presets: DegreePresets): Promise<boolean> {
        const inv = this.investigation;
        let frontier = growIds(ctx, req);
        let applied = 0;
        const made: number[] = [];
        let admitted = 0;
        let links = 0;
        let held = 0;
        for (let s = 0; s < req.steps && frontier.length; s++) {
            const body = growExpandBody(frontier, req, inv.withPresets(presets, {}));
            if (!(await inv.apply(body))) break;
            const step = inv.lastStep();
            if (step?.status === 'pending') {
                this.lastChange.set({
                    text: 'This Grow is waiting for a second person’s approval — nothing changes until it is approved.',
                });
                return true;
            }
            applied++;
            if (step) made.push(step.step);
            const added = step?.delta?.admitted ?? [];
            admitted += added.length;
            links += step?.delta?.linksAdded ?? 0;
            const flagged = new Set(step?.read?.hubs?.map((h) => h.id) ?? []);
            held += flagged.size;
            frontier = added.filter((id) => !flagged.has(id));
        }
        if (!applied) return false;
        const t = ctx.terms;
        const many = (n: number, one: string, more: string) => `${n.toLocaleString('en')} ${n === 1 ? one : more}`;
        this.lastChange.set({
            text:
                `Grew ${applied === 1 ? 'one step' : `${applied} steps`}: ${many(admitted, t.node, t.nodes)} and ` +
                `${many(links, t.link, t.links)} added` +
                (held ? `; ${many(held, `busy ${t.node}`, `busy ${t.nodes}`)} held.` : '.'),
            ...this.undoOf(made),
        });
        return true;
    }

    /** "Remove held super nodes": ONE `exclude` of every held entity with the reason (§3.2, GE-4). */
    async removeHeld(ids: string[], threshold: number, t: PackTerms): Promise<boolean> {
        if (!ids.length) return false;
        return this.excludeHere(ids, heldReason(threshold, t), t, `Removed ${ids.length.toLocaleString('en')} busy ${ids.length === 1 ? t.node : t.nodes}.`);
    }

    /** "Exclude… → From this Investigation only": one `exclude` op. */
    async excludeHere(ids: string[], reason: string, t: PackTerms, text?: string): Promise<boolean> {
        if (!(await this.investigation.apply({ op: 'exclude', ids, reason }))) return false;
        const done = this.investigation.lastStep()?.delta?.excluded?.length ?? ids.length;
        this.lastChange.set({
            text: text ?? `Excluded ${done.toLocaleString('en')} ${done === 1 ? t.node : t.nodes} from this Investigation.`,
            ...this.undoOf(this.madeStep()),
        });
        return true;
    }

    /** "Expand this one anyway" on a held entity — the recorded override (`expandHubs`). */
    async expandAnyway(id: string, presets: DegreePresets): Promise<boolean> {
        if (!(await this.investigation.expandHub(id, this.investigation.withPresets(presets, {})))) return false;
        const step = this.investigation.lastStep();
        this.lastChange.set(
            step?.status === 'pending'
                ? { text: 'Expanding through this busy item is waiting for a second person’s approval.' }
                : { text: `Expanded through ${id} — the override is recorded in the log.`, ...this.undoOf(this.madeStep()) },
        );
        return true;
    }

    /** Switch a list off: the `undo` of its step (only the latest step can be — GE-12 adds no op). */
    async switchOff(listId: string): Promise<boolean> {
        const l = this.inForce().find((x) => x.listId === listId);
        if (!l || l.switchOffWhyNot) return false;
        if (!(await this.investigation.undo())) return false;
        this.lastChange.set({ text: `Switched “${l.title}” off — its step was undone (recorded).` });
        return true;
    }

    /** Switch a list on: one `excludeBy` step (an analyst list applies when the analyst switches it on — §3.4). */
    async switchOn(listId: string, t: PackTerms): Promise<boolean> {
        const title = this.lists()?.find((l) => l.id === listId)?.title ?? listId;
        if (!(await this.investigation.apply({ op: 'excludeBy', listId, reason: 'Switched on from Hidden by lists' })))
            return false;
        const step = this.investigation.lastStep();
        const n = step?.list?.removed ?? step?.delta?.excluded?.length ?? 0;
        this.lastChange.set({
            text: `Switched “${title}” on: ${n.toLocaleString('en')} ${n === 1 ? t.node : t.nodes} removed.`,
            ...this.undoOf(this.madeStep()),
        });
        return true;
    }

    /** "Create your first list": a new analyst exclusion list (no members yet). The message says what happened. */
    async createList(title: string, entityType: string | null): Promise<string> {
        if (!entityType) return 'This graph has no Entity Type, so a list cannot be made for it.';
        this.busy.set(true);
        try {
            const made = await firstValueFrom(
                this.inv.createEntityList({ title, purpose: 'exclusion', entityType, reason: 'Created from Hidden by lists' }),
            );
            await this.loadLists(true);
            return `Created “${made.title}”. Add to it with right-click → Exclude…`;
        } catch (err) {
            return entityListErrorMessage(err, 'The list could not be created.');
        } finally {
            this.busy.set(false);
        }
    }

    /**
     * "Exclude… → Add to a list": add the raw values to the list (a list write, with the reason), then — in an
     * Investigation — apply the list (`excludeBy`) so they are excluded now and in every later step.
     */
    async addToList(
        list: { id?: string; newTitle?: string; entityType: string | null },
        values: string[],
        reason: string,
        t: PackTerms,
        inInvestigation: boolean,
    ): Promise<{ ok: boolean; message: string; listId?: string }> {
        this.busy.set(true);
        try {
            let id = list.id;
            let title = this.lists()?.find((l) => l.id === id)?.title ?? id ?? '';
            if (!id) {
                if (!list.entityType)
                    return { ok: false, message: 'These items have no Entity Type, so they cannot go on a list.' };
                const made = await firstValueFrom(
                    this.inv.createEntityList({
                        title: list.newTitle!.trim(),
                        purpose: 'exclusion',
                        entityType: list.entityType,
                        reason,
                    }),
                );
                id = made.id;
                title = made.title;
                await this.loadLists(true);
            }
            const r = await firstValueFrom(this.inv.changeEntityListMembers(id, { add: values, reason }));
            if (isHeldListChange(r))
                return {
                    ok: true,
                    listId: id,
                    message: `Waiting for approval: adding to “${title}” needs a second person to approve it.`,
                };
            this.investigation.entityListsChanged.update((n) => n + 1);
            if (inInvestigation) {
                if (!(await this.investigation.apply({ op: 'excludeBy', listId: id, reason })))
                    return { ok: false, listId: id, message: this.investigation.error() || 'The list could not be applied.' };
                this.lastChange.set({
                    text: `Added to “${title}” and applied: ${values.length.toLocaleString('en')} ${values.length === 1 ? t.node : t.nodes} excluded now and in every later step.`,
                    ...this.undoOf(this.madeStep()),
                });
            }
            return { ok: true, listId: id, message: `Added to “${title}”.` };
        } catch (err) {
            return { ok: false, message: entityListErrorMessage(err, 'The list could not be changed.') };
        } finally {
            this.busy.set(false);
        }
    }

    /** The step the last `apply` recorded (none when it was held for approval). */
    private madeStep(): number[] {
        const s = this.investigation.lastStep();
        return s && s.status !== 'pending' ? [s.step] : [];
    }

    /** The newest `n` effective op steps of the log, oldest first. */
    private newestSteps(n: number): number[] {
        const ops = (this.investigation.log()?.entries ?? []).filter((e) => e.kind === 'op' && e.undoneBy == null);
        return ops.slice(-n).map((e) => e.step);
    }

    /**
     * The Undo of the recorded steps `made`: the `undo` op once per step (it always takes back the NEWEST effective step,
     * GE-12), so it is offered only while `made` are still the newest steps — a step recorded after them would be the one
     * undone instead.
     */
    private undoOf(made: number[]): Pick<LastChange, 'undo' | 'valid'> {
        if (!made.length) return {};
        const still = () => this.newestSteps(made.length).join(',') === made.join(',');
        return {
            valid: still,
            undo: async () => {
                for (let i = 0; i < made.length; i++) if (!(await this.investigation.undo())) break;
                const n = made.length;
                this.lastChange.set(
                    this.investigation.error()
                        ? { text: this.investigation.error() }
                        : { text: n === 1 ? 'Undone.' : `Undone (${n} steps).` },
                );
            },
        };
    }

    /** True while the last change's Undo takes back exactly that change. */
    undoable(): boolean {
        const c = this.lastChange();
        return !!c?.undo && (c.valid?.() ?? true);
    }

    /** Ctrl+Z: undo the last Grow / Exclude while it is still the latest change; false = not ours (the screen's own Undo runs). */
    async undoLast(): Promise<boolean> {
        const c = this.lastChange();
        if (!c?.undo || !this.undoable()) return false;
        this.lastChange.set(null);
        await c.undo();
        return true;
    }
}
