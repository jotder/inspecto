import { ChangeDetectionStrategy, Component, computed, inject, signal, viewChild } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { apiErrorMessage } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { guardDirtyClose } from '@inspecto/core/dialog-dirty-guard';
import { G6GraphData } from '@inspecto/core/graph';
import { GraphSnapshot, snapshotGraph } from '@inspecto/link-analysis/graph/graph-snapshot';
import { LA_CASES } from '@inspecto/link-analysis/la-host';
import { ConditionGroup } from '@inspecto/core/query/query-types';
import { firstValueFrom, of } from 'rxjs';
import { InvService } from '@inspecto/link-analysis/api/inv.service';
import { MAX_CASE_MEMBERS, caseMemberCandidates, noCaseMemberReason } from './case-members';
import { LinkAnalysisCaseFieldComponent } from './link-analysis-case-field.component';
import { LinkAnalysisSnapshotsService } from './link-analysis-snapshots.service';

export interface SnapshotDialogData {
    graph: G6GraphData;
    predicate: ConditionGroup | null;
    origin: GraphSnapshot['origin'];
    layout: string;
    suggestedTitle: string;
    /** Pre-selected Case, when Link Analysis was opened from one (`?case=<id>`). Empty otherwise. */
    caseId?: string;
    /** The nodes emphasised on the canvas — the default pick when a new Case is minted from the graph. */
    selectedNodeIds?: string[];
    /** The Investigation open while sealing — anchors the snapshot so its Dossier may include it. */
    investigationId?: string;
}

/** A predicate tree as one line, for the frozen-content summary. */
export function describePredicate(g: ConditionGroup | null | undefined): string {
    if (!g) return 'none';
    const parts = g.items.map((it) =>
        it.kind === 'group'
            ? `(${describePredicate(it)})`
            : `${it.field} ${it.operator} ${it.value ?? ''}${it.value2 != null ? ` … ${it.value2}` : ''}`.trim(),
    );
    return parts.length ? parts.join(` ${g.op} `) : 'none';
}

/**
 * **Save this analysis** (spec §3.6 / plan S1.3, UI first). States the one distinction the product must
 * never blur: a saved view re-runs the question; a snapshot freezes the answer. Stranded nodes are
 * excluded, the predicate travels with it, and the manifest fingerprint is computed on save.
 *
 * ⚠ **Attaching to a Case is OPTIONAL here** (decision 2026-09-22): the analysis is the thing being
 * saved, and it stands on its own. So a Case that cannot be offered — the lookup failed — must never
 * block the save; it costs the attachment, not the analyst's work. Attach-to-Case remains a separate
 * action for attaching an analysis that was saved without one.
 *
 * **A new Case can be created in place, minted from graph nodes** (LA-CASE-CREATE-IN-PLACE-1, operator
 * decision 2026-09-23). The 2026-07-22 rule stands — a Case CONTAINS its members, so it is never born
 * empty — which is why the analyst picks nodes (default: the canvas emphasis) and each becomes an Incident
 * the Case contains, reused when the same Entity was minted before (`caseMemberCandidates`). The order is
 * seal → `POST /cases/from-entities` → attach, and each step fails closed: a refused Case creates nothing
 * (one server call, compensated there), and a step that fails AFTER the seal keeps the dialog open with the
 * sealed snapshot remembered, so a retry never seals twice or opens a second Case.
 */
@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatCheckboxModule,
        MatDialogModule,
        MatFormFieldModule,
        MatInputModule,
        MatRadioModule,
        InspectoAlertComponent,
        LinkAnalysisCaseFieldComponent,
    ],
    template: `
        <h2 mat-dialog-title>Save snapshot</h2>
        <form [formGroup]="form" (ngSubmit)="save()">
            <mat-dialog-content class="flex flex-col gap-3">
                <inspecto-alert variant="info">
                    A <b>saved view</b> re-runs the question and may show a different graph tomorrow. A
                    <b>snapshot</b> freezes the answer as it is now, with a fingerprint of its content.
                </inspecto-alert>
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Title</mat-label>
                    <input matInput formControlName="title" />
                    @if (form.controls.title.hasError('required')) {
                        <mat-error>A title is required.</mat-error>
                    }
                </mat-form-field>
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Description</mat-label>
                    <textarea matInput formControlName="description" rows="2"></textarea>
                </mat-form-field>
                <dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm" aria-label="Frozen content">
                    <dt class="text-secondary">Subgraph</dt>
                    <dd class="m-0">
                        {{ frozenNodes() }} nodes · {{ frozenEdges() }} links
                        @if (strandedExcluded()) {
                            <span class="text-secondary">({{ strandedExcluded() }} stranded nodes excluded)</span>
                        }
                    </dd>
                    <dt class="text-secondary">Predicate</dt>
                    <dd class="m-0 font-mono text-xs">{{ predicateText }}</dd>
                    <dt class="text-secondary">Origin</dt>
                    <dd class="m-0">
                        {{ data.origin.sourceId }}{{ data.origin.dataset ? ' · ' + data.origin.dataset : '' }}
                    </dd>
                    <dt class="text-secondary">Layout</dt>
                    <dd class="m-0">{{ data.layout }}</dd>
                    <dt class="text-secondary">Fingerprint</dt>
                    <dd class="m-0 text-xs">computed on save (SHA-256 over the frozen content)</dd>
                </dl>
                <div class="rounded-lg border p-3">
                    <div class="text-secondary mb-2 text-xs font-semibold uppercase tracking-wide">
                        Attach to a Case (optional)
                    </div>
                    <mat-radio-group
                        [formControl]="caseForm.controls.mode"
                        class="mb-2 flex flex-col gap-1"
                        aria-label="Which Case"
                    >
                        <mat-radio-button value="existing">An existing Case, or none</mat-radio-button>
                        <mat-radio-button value="new" [disabled]="createUnavailable() !== ''">
                            A new Case, from graph nodes
                        </mat-radio-button>
                    </mat-radio-group>
                    @if (createUnavailable(); as why) {
                        <p class="text-secondary mb-2 text-xs">{{ why }}</p>
                    }
                    <div [hidden]="creatingCase()">
                        <inspecto-link-analysis-case-field
                            formControlName="caseId"
                            placeholder="None — save without attaching"
                        ></inspecto-link-analysis-case-field>
                    </div>
                    @if (creatingCase()) {
                        <mat-form-field subscriptSizing="dynamic" class="w-full">
                            <mat-label>Case title</mat-label>
                            <input matInput [formControl]="caseForm.controls.title" />
                            @if (caseForm.controls.title.hasError('required')) {
                                <mat-error>A Case needs a title.</mat-error>
                            }
                        </mat-form-field>
                        <p class="text-secondary my-2 text-xs">
                            Each picked node becomes an Incident the new Case contains. A node minted before — the same
                            Entity from the same Dataset — is reused, not duplicated.
                        </p>
                        @if (candidates.length >= 8) {
                            <mat-form-field subscriptSizing="dynamic" class="w-full">
                                <mat-label>Filter nodes</mat-label>
                                <input matInput [value]="pickFilter()" (input)="onFilter($event)" />
                            </mat-form-field>
                        }
                        <ul
                            class="m-0 max-h-48 list-none overflow-auto p-0"
                            aria-label="Graph nodes to add to the Case"
                        >
                            @for (c of visibleCandidates(); track c.nodeId) {
                                <li>
                                    <mat-checkbox
                                        [checked]="picked().has(c.nodeId)"
                                        (change)="toggle(c.nodeId, $event.checked)"
                                    >
                                        {{ c.label }} <span class="text-secondary text-xs">{{ c.detail }}</span>
                                    </mat-checkbox>
                                </li>
                            }
                        </ul>
                        <p class="text-secondary mt-1 text-xs">{{ picked().size }} of {{ candidates.length }} picked</p>
                        @if (memberError(); as message) {
                            <p class="text-warn text-xs" role="alert">{{ message }}</p>
                        }
                    }
                    @if (form.controls.caseId.value || creatingCase()) {
                        <p class="text-secondary mt-2 text-xs">
                            The frozen snapshot is attached, not the live view — reopening a saved view re-runs the
                            question and may show a different graph.
                        </p>
                    }
                </div>
                @if (caseError(); as message) {
                    <inspecto-alert variant="warning" title="The analysis is saved — the Case step did not complete">
                        {{ message }}
                    </inspecto-alert>
                }
                @if (saveError(); as message) {
                    <inspecto-alert variant="error" title="The analysis was not saved">
                        {{ message }} — nothing has been sealed, so this dialog stays open with your work intact.
                    </inspecto-alert>
                }
            </mat-dialog-content>
            <mat-dialog-actions align="end">
                <button mat-button type="button" (click)="requestClose()">{{ sealed() ? 'Close' : 'Cancel' }}</button>
                <button mat-flat-button color="primary" type="submit" [disabled]="saving()">
                    {{ saving() ? 'Saving…' : creatingCase() ? 'Save and create Case' : 'Save analysis' }}
                </button>
            </mat-dialog-actions>
        </form>
    `,
})
export class LinkAnalysisSnapshotDialog {
    readonly data = inject<SnapshotDialogData>(MAT_DIALOG_DATA);
    private readonly ref = inject<MatDialogRef<LinkAnalysisSnapshotDialog, GraphSnapshot | undefined>>(MatDialogRef);
    private readonly confirm = inject(InspectoConfirmService);
    private readonly store = inject(LinkAnalysisSnapshotsService);
    private readonly caseMgmt = inject(LA_CASES);
    private readonly opsEnabled = this.caseMgmt.available;
    private readonly fb = inject(FormBuilder);

    /** Non-empty when the seal failed — rendered in place, and the dialog stays open. */
    readonly saveError = signal('');
    readonly saving = signal(false);
    /** Non-empty when a step AFTER the seal failed (create the Case, attach to it) — the seal stands. */
    readonly caseError = signal('');
    /** The snapshot once sealed — a retry after a failed Case step reuses it instead of sealing again. */
    readonly sealed = signal<GraphSnapshot | null>(null);

    readonly form = this.fb.nonNullable.group({
        title: [this.data.suggestedTitle, Validators.required],
        description: [''],
        // Deliberately NOT required: the analysis stands on its own, and a Case that cannot be offered
        // must cost the attachment rather than the save.
        caseId: [this.data.caseId ?? ''],
    });

    /** The Case half: which kind of Case, and — for a new one — its title. The node pick is {@link picked}. */
    readonly caseForm = this.fb.nonNullable.group({
        mode: ['existing' as 'existing' | 'new'],
        title: [this.data.suggestedTitle, Validators.required],
    });
    private readonly caseMode = signal<'existing' | 'new'>('existing');
    readonly creatingCase = computed(() => this.caseMode() === 'new');
    readonly candidates = caseMemberCandidates(this.data.graph, this.data.origin.dataset);
    readonly picked = signal(
        new Set(this.candidates.filter((c) => this.data.selectedNodeIds?.includes(c.nodeId)).map((c) => c.nodeId)),
    );
    readonly pickFilter = signal('');
    readonly visibleCandidates = computed(() => {
        const q = this.pickFilter().trim().toLowerCase();
        return q ? this.candidates.filter((c) => `${c.label} ${c.detail}`.toLowerCase().includes(q)) : this.candidates;
    });
    /** Why a new Case cannot be offered here — empty when it can. Stated, never a silently missing option. */
    readonly createUnavailable = computed(() =>
        !this.opsEnabled()
            ? 'Creating a Case needs operational objects, which this bundle does not include (Standard edition and above).'
            : this.candidates.length === 0
              ? noCaseMemberReason(this.data.graph)
              : '',
    );
    private readonly submitted = signal(false);
    readonly memberError = computed(() => {
        if (!this.submitted() || !this.creatingCase()) return '';
        const n = this.picked().size;
        if (n === 0) return 'Pick at least one node — a Case contains its members.';
        if (n > MAX_CASE_MEMBERS) return `Pick at most ${MAX_CASE_MEMBERS} nodes (${n} picked).`;
        return '';
    });

    // Once sealed, closing loses nothing — the snapshot is on disk — so it closes without asking, with it.
    readonly requestClose = guardDirtyClose(
        this.ref,
        () => !this.sealed() && (this.form.dirty || this.caseForm.dirty),
        this.confirm,
        () => this.sealed() ?? undefined,
    );

    constructor() {
        this.caseForm.controls.mode.valueChanges.subscribe((m) => this.caseMode.set(m));
    }

    toggle(nodeId: string, on: boolean): void {
        this.picked.update((s) => {
            const next = new Set(s);
            if (on) next.add(nodeId);
            else next.delete(nodeId);
            return next;
        });
    }

    onFilter(e: Event): void {
        this.pickFilter.set((e.target as HTMLInputElement).value);
    }

    readonly predicateText = describePredicate(this.data.predicate);
    readonly strandedExcluded = signal(this.data.graph.nodes.filter((n) => n.data.missing).length);
    readonly frozenNodes = signal(this.data.graph.nodes.length - this.strandedExcluded());
    readonly frozenEdges = computed(() => {
        const keep = new Set(this.data.graph.nodes.filter((n) => !n.data.missing).map((n) => n.id));
        return this.data.graph.edges.filter((e) => keep.has(e.source) && keep.has(e.target)).length;
    });

    /**
     * Seal the analysis, then optionally attach it to a Case.
     *
     * ⛔ <b>The dialog does NOT close on failure.</b> Closing would discard the analyst's title, description
     * and Case choice while nothing was written — the work would look saved and be gone. On an error the
     * message is shown in place and everything typed stays on screen.
     *
     * ⚠ Attachment is a SECOND, optional step on the same action, and it is deliberately not allowed to
     * fail the save: the snapshot is already sealed by then, and reporting "not saved" because a Case link
     * failed would be a lie. A failed attach says so, and the seal stands.
     *
     * Hashing (SHA-256, Web Crypto) is async, so `saving` is raised BEFORE it: a second submit while the
     * digest runs is refused rather than sealing twice. A hash failure (no secure context) seals nothing.
     */
    async save(): Promise<void> {
        this.form.markAllAsTouched();
        this.caseForm.markAllAsTouched();
        this.submitted.set(true);
        if (this.form.invalid || this.saving()) return;
        if (this.creatingCase() && (this.caseForm.invalid || this.memberError())) return;
        const { title, description, caseId } = this.form.getRawValue();
        const already = this.sealed();
        this.saveError.set('');
        this.caseError.set('');
        this.saving.set(true);
        let snap: GraphSnapshot;
        try {
            snap =
                already ??
                (await snapshotGraph({
                    title: title.trim(),
                    description: description.trim() || undefined,
                    graph: this.data.graph,
                    predicate: this.data.predicate,
                    origin: this.data.origin,
                    viewport: { layout: this.data.layout },
                    investigationId: this.data.investigationId,
                }));
        } catch (e: unknown) {
            this.saving.set(false);
            this.saveError.set(e instanceof Error ? e.message : 'The analysis could not be saved.');
            return;
        }
        (already ? of(already) : this.store.save(snap)).subscribe({
            next: () => {
                this.markSealed(snap);
                if (this.creatingCase()) {
                    this.createCaseAndAttach(snap);
                    return;
                }
                if (!caseId) {
                    this.saving.set(false);
                    this.ref.close(snap);
                    return;
                }
                this.store.attachTo(snap.id, caseId).subscribe({
                    next: (attachedTo) => {
                        this.saving.set(false);
                        this.ref.close({ ...snap, attachedTo });
                    },
                    error: (e: unknown) => {
                        // Sealed, but not linked. Closing is right — the evidence exists — and the caller is
                        // told the attachment did not take, rather than being shown a false success.
                        this.saving.set(false);
                        this.ref.close({ ...snap, attachedTo: [] });
                        console.warn('snapshot sealed but attach failed', e);
                    },
                });
            },
            error: (e: unknown) => {
                this.saving.set(false);
                // A refusal (403 missing capability, 413, 422) carries the server's own reason; say it.
                this.saveError.set(
                    e instanceof HttpErrorResponse
                        ? apiErrorMessage(e, 'The analysis could not be saved.')
                        : e instanceof Error
                          ? e.message
                          : 'The analysis could not be saved.',
                );
            },
        });
    }

    /** The seal is final: its title and description are now what is on disk, so they stop being editable. */
    private markSealed(snap: GraphSnapshot): void {
        if (this.sealed()) return;
        this.sealed.set(snap);
        this.form.controls.title.disable();
        this.form.controls.description.disable();
    }

    /**
     * Mint-or-reuse the picked nodes and open the Case with them (ONE server call — a refused member
     * creates nothing), then attach the sealed snapshot to it.
     *
     * ⛔ A failed attach does NOT create the Case again on retry: the form switches to the existing-Case
     * path with the new Case picked, so "Save" retries only the attachment.
     */
    private createCaseAndAttach(snap: GraphSnapshot): void {
        const members = this.candidates.filter((c) => this.picked().has(c.nodeId)).map((c) => c.member);
        this.caseMgmt.openFromEntities(this.caseForm.controls.title.value.trim(), snap.description, members).subscribe({
            next: (made) => {
                this.store.attachTo(snap.id, made.caseId).subscribe({
                    next: (attachedTo) => {
                        this.saving.set(false);
                        this.ref.close({ ...snap, attachedTo });
                    },
                    error: (e: unknown) => {
                        this.saving.set(false);
                        this.form.controls.caseId.setValue(made.caseId);
                        this.caseForm.controls.mode.setValue('existing');
                        this.caseError.set(
                            `Case ${made.caseId} was created with ${made.memberCount} member(s), but the ` +
                                `analysis could not be attached to it: ${apiErrorMessage(e, 'the attachment failed')}. ` +
                                'Save again to attach it — the Case will not be created twice.',
                        );
                    },
                });
            },
            error: (e: unknown) => {
                this.saving.set(false);
                this.caseError.set(
                    `The Case was not created: ${apiErrorMessage(e, 'the request failed')}. Nothing was created ` +
                        'for it — save again to retry, or attach the analysis to an existing Case.',
                );
            },
        });
    }
}

/** What the one-step Attach to Case dialog seals: the same on-screen graph the Save dialog would. */
export type AttachCaseDialogData = SnapshotDialogData;

/** What the dialog closes with: the sealed snapshot, the Case it is attached to, and whether the Investigation was linked. */
export interface AttachCaseResult {
    snapshot: GraphSnapshot;
    caseId: string;
    /** The open Investigation's id when it was linked to the Case (`PUT …/case`); absent when not asked or none open. */
    linkedInvestigation?: string;
}

/**
 * **Attach to Case — one step** (telecom demo S3). ONE action seals the on-screen analysis and attaches it to a
 * Case: there is no separate "save first". The Case is an existing one (picker) or a NEW one minted from graph
 * nodes (as in the Save dialog), and while an Investigation is open the analyst may also link the Investigation to
 * that Case (`PUT /inv/investigations/{id}/case`).
 *
 * The order is seal → (create the Case) → attach → (link the Investigation), and every step after the seal is
 * remembered, so a retry never seals twice, never opens a second Case and never re-attaches. ⛔ A failure AFTER the
 * seal keeps the dialog open and names the sealed snapshot id — the evidence exists and must not look lost.
 */
@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatCheckboxModule,
        MatDialogModule,
        MatFormFieldModule,
        MatInputModule,
        MatRadioModule,
        InspectoAlertComponent,
        LinkAnalysisCaseFieldComponent,
    ],
    template: `
        <h2 mat-dialog-title>Attach to Case</h2>
        <form [formGroup]="form" (ngSubmit)="attach()">
            <mat-dialog-content class="flex flex-col gap-3">
                <p class="text-secondary m-0 text-sm">
                    Seals the analysis on screen ({{ frozenNodes }} nodes) as a snapshot with a fingerprint and attaches it
                    to the Case — one step, no separate save.
                </p>
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Snapshot title</mat-label>
                    <input matInput formControlName="title" />
                    @if (form.controls.title.hasError('required')) {
                        <mat-error>A title is required.</mat-error>
                    }
                </mat-form-field>
                <mat-radio-group formControlName="mode" class="flex flex-col gap-1" aria-label="Which Case">
                    <mat-radio-button value="existing">An existing Case</mat-radio-button>
                    <mat-radio-button value="new" [disabled]="createUnavailable() !== '' || createUncertain()">
                        A new Case, from the selected nodes
                    </mat-radio-button>
                </mat-radio-group>
                @if (createUnavailable(); as why) {
                    <p class="text-secondary m-0 text-xs">{{ why }}</p>
                }
                <div [hidden]="creatingCase()">
                    <inspecto-link-analysis-case-field
                        #caseField
                        formControlName="caseId"
                        [required]="true"
                    ></inspecto-link-analysis-case-field>
                    @if (submitted() && !creatingCase() && !form.controls.caseId.value && !caseField.loadError()) {
                        <p class="text-warn m-0 text-xs" role="alert">Pick a Case.</p>
                    }
                </div>
                @if (creatingCase()) {
                    <mat-form-field subscriptSizing="dynamic" class="w-full">
                        <mat-label>Case title</mat-label>
                        <input matInput formControlName="caseTitle" />
                        @if (form.controls.caseTitle.hasError('required')) {
                            <mat-error>A Case needs a title.</mat-error>
                        }
                    </mat-form-field>
                    <ul class="m-0 max-h-48 list-none overflow-auto p-0" aria-label="Graph nodes to add to the Case">
                        @for (c of candidates; track c.nodeId) {
                            <li>
                                <mat-checkbox
                                    [checked]="picked().has(c.nodeId)"
                                    (change)="toggle(c.nodeId, $event.checked)"
                                >
                                    {{ c.label }} <span class="text-secondary text-xs">{{ c.detail }}</span>
                                </mat-checkbox>
                            </li>
                        }
                    </ul>
                    <p class="text-secondary m-0 text-xs">
                        {{ picked().size }} of {{ candidates.length }} picked — each becomes an Incident the new Case
                        contains (one minted before is reused).
                    </p>
                    @if (memberError(); as message) {
                        <p class="text-warn m-0 text-xs" role="alert">{{ message }}</p>
                    }
                }
                @if (data.investigationId) {
                    <mat-checkbox formControlName="linkInvestigation" data-testid="link-investigation">
                        Also link Investigation <code>{{ data.investigationId }}</code> to this Case
                    </mat-checkbox>
                }
                @if (saveError(); as message) {
                    <inspecto-alert variant="error" title="Nothing was attached">
                        {{ message }} — nothing has been sealed; your choices are still here.
                    </inspecto-alert>
                }
                @if (stepError(); as message) {
                    <inspecto-alert variant="warning" title="The analysis is sealed — a later step did not complete">
                        <span data-testid="attach-step-error"
                            >Sealed snapshot <code>{{ sealed()?.id }}</code> is saved. {{ message }}</span
                        >
                    </inspecto-alert>
                }
            </mat-dialog-content>
            <mat-dialog-actions align="end">
                <button mat-button type="button" (click)="requestClose()">{{ sealed() ? 'Close' : 'Cancel' }}</button>
                <button
                    mat-flat-button
                    color="primary"
                    type="submit"
                    [disabled]="busy() || (!creatingCase() && caseField.loadError() !== '')"
                >
                    {{ busy() ? 'Attaching…' : stepError() ? 'Retry' : 'Seal and attach' }}
                </button>
            </mat-dialog-actions>
        </form>
    `,
})
export class LinkAnalysisAttachCaseDialog {
    readonly data = inject<AttachCaseDialogData>(MAT_DIALOG_DATA);
    private readonly ref = inject<MatDialogRef<LinkAnalysisAttachCaseDialog, AttachCaseResult | undefined>>(MatDialogRef);
    private readonly confirm = inject(InspectoConfirmService);
    private readonly store = inject(LinkAnalysisSnapshotsService);
    private readonly caseMgmt = inject(LA_CASES);
    private readonly inv = inject(InvService);
    private readonly fb = inject(FormBuilder);
    private readonly caseFieldRef = viewChild(LinkAnalysisCaseFieldComponent);

    readonly form = this.fb.nonNullable.group({
        title: [this.data.suggestedTitle, Validators.required],
        mode: ['existing' as 'existing' | 'new'],
        caseId: [this.data.caseId ?? ''],
        caseTitle: [this.data.suggestedTitle, Validators.required],
        linkInvestigation: [true],
    });
    private readonly mode = signal<'existing' | 'new'>('existing');
    readonly creatingCase = computed(() => this.mode() === 'new');
    readonly frozenNodes = this.data.graph.nodes.filter((n) => !n.data.missing).length;
    readonly candidates = caseMemberCandidates(this.data.graph, this.data.origin.dataset);
    readonly picked = signal(
        new Set(this.candidates.filter((c) => this.data.selectedNodeIds?.includes(c.nodeId)).map((c) => c.nodeId)),
    );
    readonly createUnavailable = computed(() =>
        !this.caseMgmt.available()
            ? 'Creating a Case needs operational objects, which this bundle does not include (Standard edition and above).'
            : this.candidates.length === 0
              ? noCaseMemberReason(this.data.graph)
              : '',
    );
    readonly submitted = signal(false);
    readonly memberError = computed(() => {
        if (!this.submitted() || !this.creatingCase()) return '';
        const n = this.picked().size;
        if (n === 0) return 'Pick at least one node — a Case contains its members.';
        if (n > MAX_CASE_MEMBERS) return `Pick at most ${MAX_CASE_MEMBERS} nodes (${n} picked).`;
        return '';
    });

    readonly busy = signal(false);
    /** The seal failed — nothing exists yet. */
    readonly saveError = signal('');
    /** A step AFTER the seal failed — the snapshot exists and its id is shown. */
    readonly stepError = signal('');
    /** Remembered progress: a retry resumes after the last step that succeeded. */
    readonly sealed = signal<GraphSnapshot | null>(null);
    private createdCase: string | null = null;
    /** A Case create got no definite answer — it may exist, so "new" is not offered again in this dialog. */
    readonly createUncertain = signal(false);
    private attachedCase: string | null = null;

    readonly requestClose = guardDirtyClose(
        this.ref,
        () => !this.sealed() && this.form.dirty,
        this.confirm,
        // Closing after a partial run hands back what is on record; the store already holds the sealed snapshot.
        () => {
            const s = this.sealed();
            return s && this.attachedCase ? { snapshot: s, caseId: this.attachedCase } : undefined;
        },
    );

    constructor() {
        this.form.controls.mode.valueChanges.subscribe((m) => this.mode.set(m));
    }

    toggle(nodeId: string, on: boolean): void {
        this.picked.update((s) => {
            const next = new Set(s);
            if (on) next.add(nodeId);
            else next.delete(nodeId);
            return next;
        });
    }

    async attach(): Promise<void> {
        this.form.markAllAsTouched();
        this.submitted.set(true);
        if (this.busy()) return;
        const v = this.form.getRawValue();
        if (this.form.controls.title.invalid) return;
        if (this.creatingCase() ? this.form.controls.caseTitle.invalid || this.memberError() : !v.caseId) return;
        if (!this.creatingCase() && this.caseFieldRef()?.loadError()) return;
        this.busy.set(true);
        this.saveError.set('');
        this.stepError.set('');
        try {
            // 1. Seal (once).
            let snap = this.sealed();
            if (!snap) {
                try {
                    snap = await snapshotGraph({
                        title: v.title.trim(),
                        graph: this.data.graph,
                        predicate: this.data.predicate,
                        origin: this.data.origin,
                        viewport: { layout: this.data.layout },
                        investigationId: this.data.investigationId,
                    });
                    await firstValueFrom(this.store.save(snap));
                } catch (e: unknown) {
                    this.saveError.set(reason(e, 'The analysis could not be sealed'));
                    return;
                }
                this.sealed.set(snap);
                this.form.controls.title.disable();
            }
            // 2. The Case (created once).
            let caseId = v.caseId;
            if (this.creatingCase()) {
                if (!this.createdCase) {
                    const members = this.candidates.filter((c) => this.picked().has(c.nodeId)).map((c) => c.member);
                    try {
                        const made = await firstValueFrom(
                            this.caseMgmt.openFromEntities(v.caseTitle.trim(), undefined, members),
                        );
                        this.createdCase = made.caseId;
                        // Pinned: a retry attaches to THIS Case; switching to "existing" would leave it empty.
                        this.form.controls.mode.disable();
                    } catch (e: unknown) {
                        const refused = e instanceof HttpErrorResponse && e.status >= 400 && e.status < 500;
                        if (refused) {
                            this.stepError.set(
                                `The Case was not created: ${reason(e, 'the request was refused')}. Retry, or pick an existing Case.`,
                            );
                        } else {
                            // No definite answer (timeout, 5xx): the Case may exist. There is no idempotency key on
                            // this route, so never create again blindly — the analyst checks and picks it.
                            this.createUncertain.set(true);
                            this.form.controls.mode.setValue('existing');
                            this.stepError.set(
                                `Creating the Case got no definite answer (${reason(e, 'the request failed')}) — it may ` +
                                    'have been created. Check Cases, then pick it (or another Case) above and Retry; ' +
                                    'this dialog will not create a second Case.',
                            );
                        }
                        return;
                    }
                }
                caseId = this.createdCase;
            }
            // 3. Attach (once per Case).
            if (this.attachedCase !== caseId) {
                try {
                    await firstValueFrom(this.store.attachTo(snap.id, caseId));
                    this.attachedCase = caseId;
                } catch (e: unknown) {
                    this.stepError.set(
                        `It was not attached to ${caseId}: ${reason(e, 'the attachment failed')}. Retry attaches it — nothing is sealed or created again.`,
                    );
                    return;
                }
            }
            // 4. Link the open Investigation to the Case, when asked.
            const invId = this.data.investigationId;
            const link = !!invId && v.linkInvestigation;
            if (link) {
                try {
                    await firstValueFrom(this.inv.linkInvestigationCase(invId, caseId));
                } catch (e: unknown) {
                    this.stepError.set(
                        `It is attached to ${caseId}, but Investigation ${invId} was not linked to the Case: ` +
                            `${reason(e, 'the link failed')}. Retry links it, or untick the option and close.`,
                    );
                    return;
                }
            }
            this.ref.close({ snapshot: snap, caseId, ...(link ? { linkedInvestigation: invId } : {}) });
        } finally {
            this.busy.set(false);
        }
    }
}

/** A server's own message, else a thrown Error's (e.g. no secure context for the hash), else the fallback. */
function reason(e: unknown, fallback: string): string {
    return apiErrorMessage(e, e instanceof Error ? e.message : fallback);
}
