import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { SessionService, apiErrorMessage } from 'app/inspecto/api';
import { ObjectsService } from 'app/inspecto/api/objects.service';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoOptionPickerComponent } from 'app/inspecto/components/option-picker.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { G6GraphData, GraphSnapshot, snapshotGraph } from 'app/inspecto/graph';
import { ConditionGroup } from 'app/inspecto/query/query-types';
import { CaseRef, LinkAnalysisSnapshotsService } from './link-analysis-snapshots.service';

export interface SnapshotDialogData {
    graph: G6GraphData;
    predicate: ConditionGroup | null;
    origin: GraphSnapshot['origin'];
    layout: string;
    suggestedTitle: string;
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
 * **Snapshot this graph as evidence** (spec §3.6 / plan S1.3, UI first). States the one distinction the
 * product must never blur: a saved view re-runs the question; a snapshot freezes the answer. Stranded nodes
 * are excluded, the predicate travels with it, and the manifest fingerprint is computed on save.
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
    ],
    template: `
        <h2 mat-dialog-title>Snapshot this graph as evidence</h2>
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
                <inspecto-alert variant="warning">
                    UI-first: snapshots are kept for this browser session only until the backend snapshot store lands.
                </inspecto-alert>
            </mat-dialog-content>
            <mat-dialog-actions align="end">
                <button mat-button type="button" (click)="requestClose()">Cancel</button>
                <button mat-flat-button color="primary" type="submit">Save snapshot</button>
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
        const { title, description } = this.form.getRawValue();
        const snap = snapshotGraph({
            title: title.trim(),
            description: description.trim() || undefined,
            graph: this.data.graph,
            predicate: this.data.predicate,
            origin: this.data.origin,
            viewport: { layout: this.data.layout },
        });
        this.store.add(snap);
        this.ref.close(snap);
    }
}

export interface AttachCaseDialogData {
    /** The snapshot to attach (the host creates one first when none exists). */
    snapshot: GraphSnapshot;
}

/**
 * **Attach to Case** (plan S1.3). Picks a Case and records the snapshot against it. With the ops module
 * present the Cases come from `GET /objects?type=CASE`; without it, placeholder Cases let the flow be
 * exercised UI-first. The "saved view only" option is deliberately labelled *not evidence*.
 *
 * ⚠ Those two states must never be confused. "The ops module is not installed" is a deployment fact and
 * may offer placeholders; a Case lookup that FAILED is an error, and the dialog then offers nothing —
 * attaching evidence to a placeholder id the analyst believes is a real Case is a silent wrong answer.
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
        InspectoOptionPickerComponent,
    ],
    template: `
        <h2 mat-dialog-title>Attach to Case</h2>
        <form [formGroup]="form" (ngSubmit)="attach()">
            <mat-dialog-content class="flex flex-col gap-3">
                @if (loadError()) {
                    <inspecto-alert variant="error" title="Cases could not be loaded">
                        {{ loadError() }} — no Case can be offered until the lookup succeeds.
                    </inspecto-alert>
                } @else {
                    <inspecto-option-picker
                        label="Case"
                        formControlName="caseId"
                        [options]="caseOptions()"
                        [help]="casesHelp()"
                    ></inspecto-option-picker>
                    @if (form.controls.caseId.touched && form.controls.caseId.invalid) {
                        <p class="text-warn text-xs" role="alert">Pick a Case.</p>
                    }
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
                <button mat-flat-button color="primary" type="submit" [disabled]="loadError() !== ''">Attach</button>
            </mat-dialog-actions>
        </form>
    `,
})
export class LinkAnalysisAttachCaseDialog implements OnInit {
    readonly data = inject<AttachCaseDialogData>(MAT_DIALOG_DATA);
    private readonly ref =
        inject<MatDialogRef<LinkAnalysisAttachCaseDialog, { caseId: string } | undefined>>(MatDialogRef);
    private readonly confirm = inject(InspectoConfirmService);
    private readonly store = inject(LinkAnalysisSnapshotsService);
    private readonly objects = inject(ObjectsService);
    private readonly opsEnabled = inject(SessionService).opsEnabled;
    private readonly fb = inject(FormBuilder);

    readonly cases = signal<CaseRef[]>([]);
    /** Non-empty once the Case lookup FAILED — distinct from the ops-absent path, which has placeholders. */
    readonly loadError = signal('');
    readonly caseOptions = computed(() => this.cases().map((c) => ({ value: c.id, label: `${c.id} · ${c.title}` })));
    readonly casesHelp = computed(() =>
        this.opsEnabled()
            ? 'Open Cases from the objects store.'
            : 'Placeholder Cases — the ops module is not installed.',
    );
    readonly form = this.fb.nonNullable.group({
        caseId: ['', Validators.required],
        what: ['snapshot' as 'snapshot' | 'view'],
        note: [''],
    });
    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);

    ngOnInit(): void {
        if (!this.opsEnabled()) {
            this.cases.set([...this.store.mockCases]);
            return;
        }
        this.objects.list({ type: 'CASE' }).subscribe({
            next: (rows) => this.cases.set(rows.map((o) => ({ id: o.id, title: o.title }))),
            error: (err) => {
                this.cases.set([]);
                this.loadError.set(apiErrorMessage(err, 'The Cases lookup failed.'));
            },
        });
    }

    attach(): void {
        if (this.loadError()) return;
        this.form.markAllAsTouched();
        if (this.form.invalid) return;
        const { caseId, what } = this.form.getRawValue();
        if (what === 'snapshot') this.store.attach(this.data.snapshot.id, caseId);
        this.ref.close({ caseId });
    }
}
