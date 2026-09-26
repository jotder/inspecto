import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { EntityProjection, G6Node } from 'app/inspecto/graph';
import {
    InvService,
    InvestigationLog,
    InvestigationOpRequest,
    InvestigationReplayResult,
    InvestigationStepResult,
    WorkingSet,
} from 'app/inspecto/api';
import {
    InvestigationRef,
    effectiveOpSteps,
    entityListErrorMessage,
    investigationErrorMessage,
    truncatedSteps,
    workingSetToGraph,
} from './investigation-state';
import { ProjectedGraph } from './entity-projection';

const EMPTY_SET: WorkingSet = { entities: [], links: [], excluded: [], hash: '' };

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

    /** Every Investigation this screen knows about (created here or restored from a saved view). */
    readonly refs = signal<InvestigationRef[]>([]);
    readonly activeId = signal<string | null>(null);
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

    readonly active = computed(() => this.activeId() !== null);
    readonly activeRef = computed(() => this.refs().find((r) => r.id === this.activeId()) ?? null);
    readonly header = computed(() => this.log()?.header ?? null);
    readonly effectiveSteps = computed(() => effectiveOpSteps(this.log()?.entries ?? []));
    readonly truncatedSteps = computed(() => truncatedSteps(this.log()?.entries ?? []));
    readonly canUndo = computed(() => this.effectiveSteps().length > 0);
    /** The projection the Investigation is bound to, rebuilt from its header (+ the remembered entity type). */
    readonly binding = computed<EntityProjection | null>(() => {
        const h = this.header();
        if (!h) return null;
        return {
            datasetId: h.dataset,
            sourceCol: h.sourceCol,
            targetCol: h.targetCol,
            linkKindCol: h.linkKindCol ?? undefined,
            entityType: this.activeRef()?.entityType,
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

    /** A saved view brought its Investigations back — remember them; open none (opening replays). */
    restore(refs: InvestigationRef[]): void {
        this.close();
        this.refs.set([...refs]);
    }

    /** Leave the open Investigation (it stays remembered). */
    close(): void {
        this.activeId.set(null);
        this.log.set(null);
        this.workingSet.set(null);
        this.lastStep.set(null);
        this.replayResult.set(null);
        this.selected.set(null);
        this.error.set('');
    }

    /** Stop remembering an id (e.g. one the server no longer answers for). Nothing is deleted server-side. */
    forget(id: string): void {
        if (this.activeId() === id) this.close();
        this.refs.update((all) => all.filter((r) => r.id !== id));
    }

    /** Create an Investigation over the projection's Dataset + columns and open it. */
    async start(p: EntityProjection, purpose: string, title?: string): Promise<boolean> {
        return this.run('Could not start the Investigation.', async () => {
            const h = await firstValueFrom(
                this.inv.createInvestigation({
                    title: title || undefined,
                    purpose,
                    dataset: p.datasetId,
                    sourceCol: p.sourceCol,
                    targetCol: p.targetCol,
                    linkKindCol: p.linkKindCol || undefined,
                }),
            );
            this.refs.update((all) => [...all, { id: h.id, title: h.title ?? undefined, entityType: p.entityType }]);
            this.close();
            this.activeId.set(h.id);
            // A fresh Investigation has an empty log and an empty Working Set — no replay needed.
            this.workingSet.set(EMPTY_SET);
            this.log.set(await firstValueFrom(this.inv.investigationLog(h.id)));
        });
    }

    /** Open a remembered Investigation: its log and (via replay) its Working Set. */
    async open(id: string): Promise<boolean> {
        this.close();
        this.activeId.set(id);
        return this.run('Could not open the Investigation.', () => this.refresh(id));
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
                this.lastStep.set(await firstValueFrom(this.inv.appendInvestigationOp(id, op)));
                await this.refresh(id);
            },
            message,
        );
    }

    async undo(): Promise<boolean> {
        const id = this.activeId();
        if (!id) return false;
        return this.run('Undo failed.', async () => {
            this.lastStep.set(await firstValueFrom(this.inv.undoInvestigation(id)));
            await this.refresh(id);
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
                { id: res.id, title: parent.title, entityType: parent.entityType, parentId: parent.id },
            ]);
            this.close();
            this.activeId.set(res.id);
            await this.refresh(res.id);
        });
    }

    /** LA-23: a template was instantiated into a NEW Investigation — remember it and open it. */
    async adopt(id: string, title?: string, entityType?: string): Promise<boolean> {
        if (!this.refs().some((r) => r.id === id)) this.refs.update((all) => [...all, { id, title, entityType }]);
        return this.open(id);
    }

    /** Full evaluation from the sealed log; with `reread`, per-expand drift against current data. */
    async replay(reread: boolean): Promise<boolean> {
        const id = this.activeId();
        if (!id) return false;
        return this.run('Replay failed.', async () => {
            const r = await firstValueFrom(this.inv.replayInvestigation(id, { reread }));
            this.replayResult.set(r);
            this.workingSet.set(r.workingSet);
        });
    }

    private async refresh(id: string): Promise<void> {
        const [log, replay] = await Promise.all([
            firstValueFrom(this.inv.investigationLog(id)),
            firstValueFrom(this.inv.replayInvestigation(id, {})),
        ]);
        if (this.activeId() !== id) return; // switched away meanwhile
        this.log.set(log);
        this.workingSet.set(replay.workingSet);
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
