import { ChangeDetectionStrategy, Component, computed, inject, signal, viewChild } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { G6GraphData, GraphSnapshot, snapshotGraph } from 'app/inspecto/graph';
import { ConditionGroup } from 'app/inspecto/query/query-types';
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
                    <inspecto-link-analysis-case-field
                        formControlName="caseId"
                        placeholder="None — save without attaching"
                    ></inspecto-link-analysis-case-field>
                    @if (form.controls.caseId.value) {
                        <p class="text-secondary mt-2 text-xs">
                            The frozen snapshot is attached, not the live view — reopening a saved view re-runs the
                            question and may show a different graph.
                        </p>
                    }
                </div>
                <inspecto-alert variant="warning">
                    UI-first: snapshots are kept for this browser session only until the backend snapshot store lands.
                </inspecto-alert>
            </mat-dialog-content>
            <mat-dialog-actions align="end">
                <button mat-button type="button" (click)="requestClose()">Cancel</button>
                <button mat-flat-button color="primary" type="submit">Save analysis</button>
            </mat-dialog-actions>
        </form>
    `,
})
export class LinkAnalysisSnapshotDialog {
    readonly data = inject<SnapshotDialogData>(MAT_DIALOG_DATA);
    private readonly ref = inject<MatDialogRef<LinkAnalysisSnapshotDialog, GraphSnapshot | undefined>>(MatDialogRef);
    private readonly confirm = inject(InspectoConfirmService);
    private readonly store = inject(LinkAnalysisSnapshotsService);
    private readonly fb = inject(FormBuilder);

    readonly form = this.fb.nonNullable.group({
        title: [this.data.suggestedTitle, Validators.required],
        description: [''],
        // Deliberately NOT required: the analysis stands on its own, and a Case that cannot be offered
        // must cost the attachment rather than the save.
        caseId: [this.data.caseId ?? ''],
    });
    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);

    readonly predicateText = describePredicate(this.data.predicate);
    readonly strandedExcluded = signal(this.data.graph.nodes.filter((n) => n.data.missing).length);
    readonly frozenNodes = signal(this.data.graph.nodes.length - this.strandedExcluded());
    readonly frozenEdges = computed(() => {
        const keep = new Set(this.data.graph.nodes.filter((n) => !n.data.missing).map((n) => n.id));
        return this.data.graph.edges.filter((e) => keep.has(e.source) && keep.has(e.target)).length;
    });

    save(): void {
        this.form.markAllAsTouched();
        if (this.form.invalid) return;
        const { title, description, caseId } = this.form.getRawValue();
        const snap = snapshotGraph({
            title: title.trim(),
            description: description.trim() || undefined,
            graph: this.data.graph,
            predicate: this.data.predicate,
            origin: this.data.origin,
            viewport: { layout: this.data.layout },
        });
        this.store.add(snap);
        // The attachment is a second, optional step on the SAME action — a Case that was never offered
        // (lookup failed) simply leaves the saved analysis unattached.
        this.ref.close(caseId ? (this.store.attach(snap.id, caseId) ?? snap) : snap);
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
                <inspecto-alert variant="warning">
                    UI-first: the attachment is recorded in this browser session only until the Case evidence route
                    lands.
                </inspecto-alert>
            </mat-dialog-content>
            <mat-dialog-actions align="end">
                <button mat-button type="button" (click)="requestClose()">Cancel</button>
                <button mat-flat-button color="primary" type="submit" [disabled]="caseField.loadError() !== ''">
                    Attach
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
    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);

    attach(): void {
        // A caseId reaching the form while the lookup is errored (a deep link, a stale patch) is still
        // not attachable — nothing offered it, so nothing vouches that the Case exists.
        if (this.caseFieldRef()?.loadError()) return;
        this.form.markAllAsTouched();
        if (this.form.invalid) return;
        const { caseId, what } = this.form.getRawValue();
        if (what === 'snapshot') this.store.attach(this.data.snapshot.id, caseId);
        this.ref.close({ caseId });
    }
}
