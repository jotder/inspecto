import {
    ChangeDetectionStrategy,
    Component,
    EventEmitter,
    Input,
    OnChanges,
    Output,
    SimpleChanges,
    inject,
    signal,
} from '@angular/core';
import { FormBuilder, FormsModule, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { AuthoredNode } from 'app/inspecto/api';
import { InspectoOptionPickerComponent, PickerOption } from 'app/inspecto/components/option-picker.component';
import {
    categoryColor,
    categoryLabel,
    NodeStatus,
    nodeConfigEntries,
    statusIcon,
    statusLabel,
    statusTint,
} from './pipeline-graph';

/**
 * The pipeline editor's property strip — a selected node's summary (read-only lens / dialog-custody
 * nodes), the COMPACT identity strip inside the definition drawer, a selected edge's relationship
 * picker, or an idle hint (Wave-1 decomposition of `PipelineEditorComponent`,
 * see `docs/superpower/reviews/pipeline-editor.md`). Purely presentational: the host computes `status`
 * (it alone holds the registry-refs/test-outcome state `statusOf` needs) and `category`; every rendering
 * decision here uses the framework-free helpers in `pipeline-graph.ts` directly.
 */
@Component({
    selector: 'app-pipeline-inspector',
    standalone: true,
    imports: [
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        FormsModule,
        MatSlideToggleModule,
        ReactiveFormsModule,
        InspectoOptionPickerComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        @if (node) {
            @if (!compact) {
                <div class="mb-1 flex items-center justify-between gap-2">
                    <h3 class="truncate text-sm font-semibold">Node · {{ node.id }}</h3>
                    <span
                        class="shrink-0 rounded px-1.5 py-0.5 text-xs font-semibold"
                        [style.color]="categoryColor(category)"
                        style="background: var(--gamma-bg-default)"
                    >
                        {{ categoryLabel(category) }}
                    </span>
                </div>
                <p class="mb-1 text-xs opacity-70">{{ node.type }}</p>
            }
            @if (status) {
                <p class="mb-2 inline-flex items-center gap-1 text-xs font-semibold" [style.color]="statusTint(status)">
                    <mat-icon class="icon-size-4" [svgIcon]="statusIcon(status)"></mat-icon>
                    {{ statusLabel(status) }}
                </p>
            }
            @if (lastRun) {
                <p class="mb-2 text-xs opacity-70">
                    Last run: {{ lastRun.rowCount.toLocaleString() }} row(s) · {{ lastRun.runTs }}
                </p>
            }

            @if (compact && !readOnly) {
                <!-- Identity fields ON the config page (operator ask 2026-08-22): Name/Description are
                     always-visible fields above every definition pane, committed on blur — the pencil
                     round-trip survives only in the (rare) non-compact summary. -->
                <form [formGroup]="renameForm" class="mb-2 space-y-1">
                    <mat-form-field class="w-full" subscriptSizing="dynamic">
                        <mat-label>Name</mat-label>
                        <input matInput formControlName="name" [placeholder]="node.id" (change)="commitIdentity()" />
                    </mat-form-field>
                    <mat-form-field class="w-full" subscriptSizing="dynamic">
                        <mat-label>Description</mat-label>
                        <input matInput formControlName="description" (change)="commitIdentity()" />
                    </mat-form-field>
                </form>
                @if (parkable) {
                    <!--
                        Phase 4 S4 / D-13: the per-Step switch. Only a route-branch sink offers it — the
                        host decides that structurally (a sink fed by the route Step), never by mirroring
                        the engine's sink-id grammar. Off is a durable PAUSE, not a skip: at rest the
                        Consignments reaching this branch PARK, and a drain completes them once it is
                        switched back on.
                    -->
                    <div class="mb-2">
                        <mat-slide-toggle
                            [checked]="node.config?.['enabled'] !== false"
                            (change)="setEnabled($event.checked)"
                        >
                            Step enabled
                        </mat-slide-toggle>
                        <p class="mt-1 text-xs opacity-70">
                            @if (node.config?.['enabled'] === false) {
                                Consignments reaching this branch park until it is switched back on and drained.
                            } @else {
                                Switch off to park this branch's Consignments instead of writing them.
                            }
                        </p>
                    </div>
                }
            } @else if (renaming()) {
                <form [formGroup]="renameForm" (ngSubmit)="commitRename()" class="mb-2 space-y-1">
                    <mat-form-field class="w-full" subscriptSizing="dynamic">
                        <mat-label>Name</mat-label>
                        <input matInput formControlName="name" [placeholder]="node.id" />
                    </mat-form-field>
                    <mat-form-field class="w-full" subscriptSizing="dynamic">
                        <mat-label>Description</mat-label>
                        <input matInput formControlName="description" />
                    </mat-form-field>
                    <div class="flex gap-2">
                        <button mat-flat-button color="primary" type="submit">Save</button>
                        <button mat-stroked-button type="button" (click)="renaming.set(false)">Cancel</button>
                    </div>
                </form>
            } @else {
                <div class="flex items-start gap-1">
                    <div class="min-w-0 grow">
                        @if (!compact && node.name) {
                            <p class="text-sm"><span class="opacity-70">name:</span> {{ node.name }}</p>
                        }
                        @if (node.description) {
                            <p class="text-sm opacity-80">{{ node.description }}</p>
                        }
                    </div>
                    @if (!readOnly) {
                        <button
                            mat-icon-button
                            type="button"
                            class="shrink-0"
                            aria-label="Rename Step"
                            (click)="startRename()"
                        >
                            <mat-icon class="icon-size-4" svgIcon="heroicons_outline:pencil"></mat-icon>
                        </button>
                    }
                </div>
            }
            @if (node.use) {
                <p class="text-sm"><span class="opacity-70">use:</span> {{ node.use }}</p>
            }

            <!-- The config-row summary renders only READ-ONLY (2026-08-21 second pass): the author's
                 summary is SLIM — config detail is the pane's job, one Configure away. -->
            @if (!compact && readOnly && configEntries().length) {
                <div class="mt-2">
                    <span class="text-xs font-semibold uppercase opacity-70">Config</span>
                    @for (c of configEntries(); track c.k) {
                        <div class="truncate text-sm">
                            <span class="opacity-70">{{ c.k }}:</span> {{ c.v }}
                        </div>
                    }
                </div>
            }

            <!-- S1: Run to here / Preview data / Connect / Delete now live in the toolbar's selection
                 cluster — they act on the selection, and Delete rendered TWICE on screen at once. What
                 stays here is the one verb that opens this panel's own successor surface. -->
            @if (!readOnly && !compact) {
                <div class="mt-3 flex flex-wrap gap-2">
                    <button mat-flat-button color="primary" type="button" (click)="configure.emit(node)">
                        <mat-icon svgIcon="heroicons_outline:cog-6-tooth"></mat-icon> Configure
                    </button>
                </div>
            }
        } @else if (selectedEdgeId) {
            <h3 class="mb-2 text-sm font-semibold">Connection</h3>
            <inspecto-option-picker
                class="block w-full"
                label="Relationship"
                [options]="relOptions()"
                [disabled]="readOnly"
                [ngModel]="selectedEdgeRel"
                (ngModelChange)="edgeRelChange.emit($event)"
            />
            <p class="mt-1 text-xs opacity-60">The source's output this connection carries.</p>
        } @else {
            <p class="text-sm opacity-70">
                @if (readOnly) {
                    Click a Step or edge to inspect it. Authoring is read-only in the Business lens.
                } @else {
                    Drag a Step from the palette onto the canvas. Click a Step to edit its configuration right here.
                    <b>Delete selected</b> removes the selected item.
                }
            </p>
        }
    `,
})
export class PipelineInspectorComponent implements OnChanges {
    private fb = inject(FormBuilder);

    @Input() node: AuthoredNode | null = null;
    /** The node's authoring status — the host computes this (it alone holds the ref/test-outcome state). */
    @Input() status: NodeStatus | null = null;
    /** The node's palette category — drives the label chip's text + colour. */
    @Input() category = '';
    /** The node's real most-recent run (T17 live overlay), or `null` when that run recorded nothing for it. */
    @Input() lastRun: { rowCount: number; runTs: string } | null = null;
    @Input() selectedEdgeId: string | null = null;
    @Input() selectedEdgeRel: string | null = null;
    @Input() candidateRels: string[] = [];

    /** The relationship names in the shared picker's shape — a rel is its own label. */
    relOptions(): PickerOption[] {
        return this.candidateRels.map((r) => ({ value: r, label: r }));
    }
    /** Business lens: hide every authoring action (Configure/relabel). */
    @Input() readOnly = false;
    /**
     * Compact strip mode — rendered INSIDE the definition drawer, above the config pane, since a
     * selected Step opens its configuration directly (operator ask, re-flipped 2026-08-22) and the
     * full summary panel would duplicate what the drawer header and the pane already show. Keeps
     * what the pane does not carry: the status chip, the last-run overlay, and the Step's identity —
     * Name/Description as always-visible fields, committed on blur (the ONE rename path for
     * definition-pane nodes).
     */
    @Input() compact = false;
    /**
     * Whether this Step may be switched off (Phase 4 S4 / D-13). The HOST decides: only a sink fed by
     * an armed `route:` Step can park, which is a structural fact of the graph on screen — deriving it
     * here from the node alone would mean mirroring `StepDisableArming`'s sink-id grammar.
     */
    @Input() parkable = false;

    @Output() configure = new EventEmitter<AuthoredNode>();
    @Output() edgeRelChange = new EventEmitter<string>();
    /**
     * The node's display name/description, edited in place (the canvas rename affordance — the ONLY
     * rename path for drawer-parse nodes since the Parse pane dropped its Name/Description fields).
     * The host patches the model; identity (`id`/`type`) stays fixed.
     */
    @Output() rename = new EventEmitter<{ name: string; description: string }>();

    /** The per-Step switch (see {@link parkable}); the host patches `config.enabled` on the model. */
    @Output() enabledChange = new EventEmitter<boolean>();

    /** Whether the inline Name/Description editor is open. Reset whenever the selection changes. */
    readonly renaming = signal(false);
    readonly renameForm = this.fb.group({
        name: this.fb.control(''),
        description: this.fb.control(''),
    });

    ngOnChanges(changes: SimpleChanges): void {
        // A different selection makes any in-progress rename stale — never carry one node's draft
        // onto another node. In compact mode the fields are always live, so reseed them instead.
        if (changes['node']) {
            this.renaming.set(false);
            if (this.compact && this.node) {
                this.renameForm.setValue({
                    name: this.node.name ?? '',
                    description: this.node.description ?? '',
                });
            }
        }
    }

    /**
     * Compact-mode commit (on blur): emit only when the trimmed values actually differ from the
     * node's, so tabbing through the fields never patches the model or dirties the pipeline.
     */
    commitIdentity(): void {
        const n = this.node;
        if (!n) return;
        const v = this.renameForm.getRawValue();
        const name = (v.name ?? '').trim();
        const description = (v.description ?? '').trim();
        if (name === (n.name ?? '') && description === (n.description ?? '')) return;
        this.rename.emit({ name, description });
    }

    setEnabled(enabled: boolean): void {
        if (this.node) this.enabledChange.emit(enabled);
    }

    startRename(): void {
        const n = this.node;
        if (!n) return;
        this.renameForm.setValue({ name: n.name ?? '', description: n.description ?? '' });
        this.renaming.set(true);
    }

    commitRename(): void {
        const v = this.renameForm.getRawValue();
        this.renaming.set(false);
        this.rename.emit({ name: (v.name ?? '').trim(), description: (v.description ?? '').trim() });
    }

    readonly categoryColor = categoryColor;
    readonly categoryLabel = categoryLabel;
    readonly statusIcon = statusIcon;
    readonly statusTint = statusTint;
    readonly statusLabel = statusLabel;

    configEntries(): { k: string; v: string }[] {
        return this.node ? nodeConfigEntries(this.node) : [];
    }
}
