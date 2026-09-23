import { ChangeDetectionStrategy, Component, computed, inject, signal, viewChild } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { SessionService, apiErrorMessage } from 'app/inspecto/api';
import { ObjectsService } from 'app/inspecto/api/objects.service';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { G6GraphData, GraphSnapshot, snapshotGraph } from 'app/inspecto/graph';
import { ConditionGroup } from 'app/inspecto/query/query-types';
import { of } from 'rxjs';
import { MAX_CASE_MEMBERS, caseMemberCandidates } from './case-members';
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
        <h2 mat-dialog-title>Save this analysis</h2>
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
                    <dd class="m-0 text-xs">
                        computed on save (FNV-1a 64 — a stable fingerprint; the SHA-256 chain of custody arrives with
                        the backend snapshot store)
                    </dd>
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
    private readonly objects = inject(ObjectsService);
    private readonly opsEnabled = inject(SessionService).opsEnabled;
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
            ? 'Creating a Case needs operational objects, which this bundle does not include (Professional edition and above).'
            : this.candidates.length === 0
              ? 'No node on this graph can become a Case member: each must be an Entity with a known source Dataset.'
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
     */
    save(): void {
        this.form.markAllAsTouched();
        this.caseForm.markAllAsTouched();
        this.submitted.set(true);
        if (this.form.invalid || this.saving()) return;
        if (this.creatingCase() && (this.caseForm.invalid || this.memberError())) return;
        const { title, description, caseId } = this.form.getRawValue();
        const already = this.sealed();
        const snap =
            already ??
            snapshotGraph({
                title: title.trim(),
                description: description.trim() || undefined,
                graph: this.data.graph,
                predicate: this.data.predicate,
                origin: this.data.origin,
                viewport: { layout: this.data.layout },
            });
        this.saveError.set('');
        this.caseError.set('');
        this.saving.set(true);
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
                this.saveError.set(e instanceof Error ? e.message : 'The analysis could not be saved.');
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
        this.objects
            .openCaseFromEntities(this.caseForm.controls.title.value.trim(), snap.description, members)
            .subscribe({
                next: (made) => {
                    this.store.attachTo(snap.id, made.case.id).subscribe({
                        next: (attachedTo) => {
                            this.saving.set(false);
                            this.ref.close({ ...snap, attachedTo });
                        },
                        error: (e: unknown) => {
                            this.saving.set(false);
                            this.form.controls.caseId.setValue(made.case.id);
                            this.caseForm.controls.mode.setValue('existing');
                            this.caseError.set(
                                `Case ${made.case.id} was created with ${made.members.length} member(s), but the ` +
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

export interface AttachCaseDialogData {
    /** The snapshot to attach (the host creates one first when none exists). */
    snapshot: GraphSnapshot;
}

/**
 * **Attach to Case** (plan S1.3). Attaches an analysis that was saved WITHOUT a Case — the save dialog
 * asks for one optionally, so this is the second path rather than the only one. Here the Case genuinely
 * is required: attaching to nothing is not an outcome this action has.
 *
 * The three Case states (ops present / ops absent / lookup failed) live in
 * {@link LinkAnalysisCaseFieldComponent}, shared with the save dialog so they cannot drift.
 * The "saved view only" option is deliberately labelled *not evidence*.
 */
@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
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
                <inspecto-link-analysis-case-field
                    #caseField
                    formControlName="caseId"
                    [required]="true"
                ></inspecto-link-analysis-case-field>
                @if (form.controls.caseId.touched && form.controls.caseId.invalid && !caseField.loadError()) {
                    <p class="text-warn text-xs" role="alert">Pick a Case.</p>
                }
                <div class="rounded-lg border p-3 text-sm">
                    <div class="text-secondary mb-1 text-xs font-semibold uppercase tracking-wide">
                        What gets attached
                    </div>
                    <mat-radio-group formControlName="what" class="flex flex-col gap-1" aria-label="What gets attached">
                        <mat-radio-button value="snapshot">
                            <b>Snapshot</b> “{{ data.snapshot.title }}” — frozen graph, predicate, fingerprint
                            <span class="font-mono text-xs">{{ data.snapshot.manifestHash.slice(0, 12) }}…</span>
                        </mat-radio-button>
                        <mat-radio-button value="view">
                            Saved view only <span class="text-warn text-xs font-semibold">not evidence</span>
                            <span class="text-secondary">— re-projects live, may change</span>
                        </mat-radio-button>
                    </mat-radio-group>
                </div>
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Note for the Case ledger</mat-label>
                    <textarea matInput formControlName="note" rows="2"></textarea>
                </mat-form-field>
                @if (attachError(); as message) {
                    <inspecto-alert variant="error" title="The attachment was not recorded">
                        {{ message }} — the snapshot itself is untouched; nothing links it to this Case yet.
                    </inspecto-alert>
                }
            </mat-dialog-content>
            <mat-dialog-actions align="end">
                <button mat-button type="button" (click)="requestClose()">Cancel</button>
                <button
                    mat-flat-button
                    color="primary"
                    type="submit"
                    [disabled]="caseField.loadError() !== '' || attaching()"
                >
                    {{ attaching() ? 'Attaching…' : 'Attach' }}
                </button>
            </mat-dialog-actions>
        </form>
    `,
})
export class LinkAnalysisAttachCaseDialog {
    readonly data = inject<AttachCaseDialogData>(MAT_DIALOG_DATA);
    private readonly ref =
        inject<MatDialogRef<LinkAnalysisAttachCaseDialog, { caseId: string } | undefined>>(MatDialogRef);
    private readonly confirm = inject(InspectoConfirmService);
    private readonly store = inject(LinkAnalysisSnapshotsService);
    private readonly fb = inject(FormBuilder);

    /** Read in TS only, for the submit guard — the template uses its own `#caseField` reference. */
    private readonly caseFieldRef = viewChild(LinkAnalysisCaseFieldComponent);

    readonly form = this.fb.nonNullable.group({
        caseId: ['', Validators.required],
        what: ['snapshot' as 'snapshot' | 'view'],
        note: [''],
    });
    /** Non-empty when the attachment was refused — shown in place; the dialog stays open. */
    readonly attachError = signal('');
    readonly attaching = signal(false);
    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);

    /**
     * ⛔ Like the save dialog, this does NOT close on failure. An attachment that silently failed would
     * leave an analyst believing a piece of evidence is linked to a Case when the record says otherwise —
     * and the whole point of the attachment log is that it can be trusted later.
     *
     * ⚠ Attaching the VIEW is still local: a saved view is not evidence (D-S1), so there is nothing sealed
     * to record server-side, and the close simply reports the chosen Case.
     */
    attach(): void {
        // A caseId reaching the form while the lookup is errored (a deep link, a stale patch) is still
        // not attachable — nothing offered it, so nothing vouches that the Case exists.
        if (this.caseFieldRef()?.loadError()) return;
        this.form.markAllAsTouched();
        if (this.form.invalid || this.attaching()) return;
        const { caseId, what } = this.form.getRawValue();
        if (what !== 'snapshot') {
            this.ref.close({ caseId });
            return;
        }
        this.attachError.set('');
        this.attaching.set(true);
        this.store.attachTo(this.data.snapshot.id, caseId).subscribe({
            next: () => {
                this.attaching.set(false);
                this.ref.close({ caseId });
            },
            error: (e: unknown) => {
                this.attaching.set(false);
                this.attachError.set(e instanceof Error ? e.message : 'The attachment was not recorded.');
            },
        });
    }
}
