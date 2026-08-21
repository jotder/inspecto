import { ChangeDetectionStrategy, Component, EventEmitter, Input, OnChanges, Output, SimpleChanges, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { AuthoredNode } from 'app/inspecto/api';
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
 * The pipeline editor's property drawer — one of three states: a selected node's summary + actions, a
 * selected edge's relationship picker, or an idle hint (Wave-1 decomposition of `PipelineEditorComponent`,
 * see `docs/superpower/reviews/pipeline-editor.md`). Purely presentational: the host computes `status`
 * (it alone holds the registry-refs/test-outcome state `statusOf` needs) and `category`; every rendering
 * decision here uses the framework-free helpers in `pipeline-graph.ts` directly.
 */
@Component({
    selector: 'app-pipeline-inspector',
    standalone: true,
    imports: [MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule, ReactiveFormsModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        @if (node) {
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

            @if (renaming()) {
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
                        @if (node.name) {
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

            @if (configEntries().length) {
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
            @if (!readOnly) {
                <div class="mt-3 flex flex-wrap gap-2">
                    <button mat-flat-button color="primary" type="button" (click)="configure.emit(node)">
                        <mat-icon svgIcon="heroicons_outline:cog-6-tooth"></mat-icon> Configure
                    </button>
                </div>
            }
        } @else if (selectedEdgeId) {
            <h3 class="mb-2 text-sm font-semibold">Connection</h3>
            <mat-form-field class="w-full" subscriptSizing="dynamic">
                <mat-label>Relationship</mat-label>
                <mat-select
                    [value]="selectedEdgeRel"
                    [disabled]="readOnly"
                    (selectionChange)="edgeRelChange.emit($event.value)"
                >
                    @for (r of candidateRels; track r) {
                        <mat-option [value]="r">{{ r }}</mat-option>
                    }
                </mat-select>
            </mat-form-field>
            <p class="mt-1 text-xs opacity-60">The source's output this connection carries.</p>
        } @else {
            <p class="text-sm opacity-70">
                @if (readOnly) {
                    Click a Step or edge to inspect it. Authoring is read-only in the Business lens.
                } @else {
                    Drag a Step from the toolbar onto the canvas. Click a Step or edge to select it;
                    <b>double-click</b> a Step (or use <b>Configure</b>) to edit its attributes.
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
    /** Business lens: hide every authoring action (Configure/relabel). */
    @Input() readOnly = false;

    @Output() configure = new EventEmitter<AuthoredNode>();
    @Output() edgeRelChange = new EventEmitter<string>();
    /**
     * The node's display name/description, edited in place (the canvas rename affordance — the ONLY
     * rename path for drawer-parse nodes since the Parse pane dropped its Name/Description fields).
     * The host patches the model; identity (`id`/`type`) stays fixed.
     */
    @Output() rename = new EventEmitter<{ name: string; description: string }>();

    /** Whether the inline Name/Description editor is open. Reset whenever the selection changes. */
    readonly renaming = signal(false);
    readonly renameForm = this.fb.group({
        name: this.fb.control(''),
        description: this.fb.control(''),
    });

    ngOnChanges(changes: SimpleChanges): void {
        // A different selection makes any in-progress rename stale — never carry one node's draft
        // onto another node.
        if (changes['node']) this.renaming.set(false);
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
