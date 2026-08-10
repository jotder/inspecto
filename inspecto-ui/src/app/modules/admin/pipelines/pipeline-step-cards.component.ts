import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AuthoredNode } from 'app/inspecto/api';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import {
    NodeStatus,
    RECIPE_VERBS,
    StepRow,
    categoryLabel,
    nodeConfigEntries,
    paletteHeroIcon,
    statusIcon,
    statusLabel,
    statusTint,
} from './pipeline-graph';

/**
 * The recipe view (ELT amendment UI plan §1, S1 read-only + S2 editing): one card per Step in chain
 * order, a `route` Step's branches nested and indented beneath it. Purely presentational — the host
 * computes {@link rows} ({@link flattenStepChain} over {@link detectStepChain}'s tree), the category
 * label per node type, and each node's status (it alone holds the registry-refs/test-outcome state
 * that needs). With {@link editable} the cards gain: click/Configure → {@link open} (the host routes
 * it to the EXISTING dialogs — no new dialog kinds), an Add-Step affordance between trunk cards
 * offering the {@link RECIPE_VERBS} (→ {@link insertStep}), remove (→ {@link remove}) and
 * move up/down (→ {@link move}). Nested Step cards (depth > 0) configure but don't splice — that
 * stays canvas work. S3 adds the branch editor on top: a route Step card gains a `case|clone` mode
 * toggle and an add-branch draft input; each branch row gains an inline `when` predicate input, a
 * default toggle, and remove — all emitted to the host, which applies the pure reducers.
 */
@Component({
    selector: 'app-pipeline-step-cards',
    standalone: true,
    imports: [MatButtonModule, MatIconModule, MatMenuModule, MatTooltipModule, ChipComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <ol class="flex flex-col gap-1 p-3" aria-label="Pipeline Steps">
            @if (editable) {
                <li class="flex justify-center">
                    <button
                        mat-icon-button
                        [matMenuTriggerFor]="verbMenu"
                        (click)="insertAfterId = null"
                        matTooltip="Add a Step at the start"
                        aria-label="Add a Step at the start"
                    >
                        <mat-icon class="icon-size-4" svgIcon="heroicons_outline:plus"></mat-icon>
                    </button>
                </li>
            }
            @for (row of rows; track row.rowId) {
                @if (row.kind === 'node') {
                    <li
                        class="flex flex-col gap-1 rounded border p-2.5"
                        [style.margin-left.px]="row.depth * 24"
                        style="background: var(--gamma-bg-card); border-color: var(--gamma-border)"
                    >
                        <div class="flex items-center gap-2">
                            <mat-icon
                                class="icon-size-4 shrink-0 opacity-70"
                                [svgIcon]="paletteHeroIcon(typeCat.get(row.node.type) ?? '')"
                            ></mat-icon>
                            <span class="truncate text-sm font-semibold">
                                {{ row.node.name && row.node.name.trim() ? row.node.name : row.node.id }}
                            </span>
                            <span class="shrink-0 text-xs opacity-60">{{ stepTypeLabel(row.node.type) }}</span>
                            @if (statusOf) {
                                <span
                                    class="ml-auto inline-flex shrink-0 items-center gap-1 text-xs font-semibold"
                                    [style.color]="statusTint(statusOf(row.node))"
                                >
                                    <mat-icon class="icon-size-4" [svgIcon]="statusIcon(statusOf(row.node))"></mat-icon>
                                    {{ statusLabel(statusOf(row.node)) }}
                                </span>
                            }
                            @if (editable) {
                                <span class="flex shrink-0 items-center" [class.ml-auto]="!statusOf">
                                    <button
                                        mat-icon-button
                                        (click)="open.emit(row.node)"
                                        [matTooltip]="'Configure ' + row.node.id"
                                        [attr.aria-label]="'Configure ' + row.node.id"
                                    >
                                        <mat-icon class="icon-size-4" svgIcon="heroicons_outline:pencil-square"></mat-icon>
                                    </button>
                                    @if (row.depth === 0) {
                                        <button
                                            mat-icon-button
                                            (click)="move.emit({ id: row.node.id, dir: 'up' })"
                                            [matTooltip]="'Move ' + row.node.id + ' up'"
                                            [attr.aria-label]="'Move ' + row.node.id + ' up'"
                                        >
                                            <mat-icon class="icon-size-4" svgIcon="heroicons_outline:chevron-up"></mat-icon>
                                        </button>
                                        <button
                                            mat-icon-button
                                            (click)="move.emit({ id: row.node.id, dir: 'down' })"
                                            [matTooltip]="'Move ' + row.node.id + ' down'"
                                            [attr.aria-label]="'Move ' + row.node.id + ' down'"
                                        >
                                            <mat-icon class="icon-size-4" svgIcon="heroicons_outline:chevron-down"></mat-icon>
                                        </button>
                                        <button
                                            mat-icon-button
                                            (click)="remove.emit(row.node.id)"
                                            [matTooltip]="'Remove ' + row.node.id"
                                            [attr.aria-label]="'Remove ' + row.node.id"
                                        >
                                            <mat-icon class="icon-size-4" svgIcon="heroicons_outline:trash"></mat-icon>
                                        </button>
                                    }
                                </span>
                            }
                        </div>
                        @if (row.node.description) {
                            <p class="truncate text-xs opacity-70">{{ row.node.description }}</p>
                        }
                        @if (configSummary(row.node); as entries) {
                            @if (entries.length) {
                                <div class="flex flex-wrap gap-1">
                                    @for (e of entries; track e.k) {
                                        <inspecto-chip variant="outline" tone="neutral">{{ e.k }}: {{ e.v }}</inspecto-chip>
                                    }
                                </div>
                            }
                        }
                        <!-- S3: the route Step's own branch controls (mode toggle + add-branch draft) -->
                        @if (editable && row.node.type === 'transform.route') {
                            <div class="flex flex-wrap items-center gap-2 pt-1">
                                <button
                                    mat-stroked-button
                                    class="min-h-8"
                                    (click)="modeChange.emit({ routeId: row.node.id, mode: routeMode(row.node) === 'clone' ? 'case' : 'clone' })"
                                    [matTooltip]="routeMode(row.node) === 'clone'
                                        ? 'clone: every matching branch fires — switch to case (first match wins)'
                                        : 'case: first matching branch wins — switch to clone (every match fires)'"
                                    [attr.aria-label]="'Route mode: ' + routeMode(row.node) + ' — click to switch'"
                                >
                                    mode: {{ routeMode(row.node) }}
                                </button>
                                <input
                                    class="rounded border bg-transparent px-2 py-1 text-xs"
                                    style="border-color: var(--gamma-border)"
                                    placeholder="new branch key"
                                    [attr.aria-label]="'New branch key for ' + row.node.id"
                                    #branchDraft
                                    (keydown.enter)="commitBranch(row.node.id, branchDraft)"
                                />
                                <button
                                    mat-icon-button
                                    (click)="commitBranch(row.node.id, branchDraft)"
                                    [matTooltip]="'Add a branch to ' + row.node.id"
                                    [attr.aria-label]="'Add a branch to ' + row.node.id"
                                >
                                    <mat-icon class="icon-size-4" svgIcon="heroicons_outline:plus"></mat-icon>
                                </button>
                            </div>
                        }
                    </li>
                    @if (editable && row.depth === 0) {
                        <li class="flex justify-center">
                            <button
                                mat-icon-button
                                [matMenuTriggerFor]="verbMenu"
                                (click)="insertAfterId = row.node.id"
                                [matTooltip]="'Add a Step after ' + row.node.id"
                                [attr.aria-label]="'Add a Step after ' + row.node.id"
                            >
                                <mat-icon class="icon-size-4" svgIcon="heroicons_outline:plus"></mat-icon>
                            </button>
                        </li>
                    }
                } @else {
                    <li
                        class="flex items-center gap-2 pt-1 text-xs font-semibold uppercase tracking-wide opacity-60"
                        [style.margin-left.px]="row.depth * 24"
                    >
                        <mat-icon class="icon-size-4" svgIcon="heroicons_outline:arrow-turn-down-right"></mat-icon>
                        <span>branch: {{ row.key }}</span>
                        @if (editable) {
                            <input
                                class="rounded border bg-transparent px-2 py-1 font-normal normal-case"
                                style="border-color: var(--gamma-border)"
                                placeholder="when …"
                                [value]="row.where ?? ''"
                                [attr.aria-label]="'Branch ' + row.key + ' predicate'"
                                (change)="branchWhere.emit({ routeId: row.routeId, key: row.key, where: $any($event.target).value })"
                            />
                            <button
                                mat-icon-button
                                (click)="setDefault.emit({ routeId: row.routeId, key: row.isDefault ? null : row.key })"
                                [matTooltip]="row.isDefault ? 'Clear default' : 'Make this the default branch'"
                                [attr.aria-label]="(row.isDefault ? 'Clear default branch ' : 'Make default branch ') + row.key"
                            >
                                <mat-icon
                                    class="icon-size-4"
                                    [svgIcon]="row.isDefault ? 'heroicons_solid:star' : 'heroicons_outline:star'"
                                ></mat-icon>
                            </button>
                            <button
                                mat-icon-button
                                (click)="removeBranch.emit({ routeId: row.routeId, key: row.key })"
                                [matTooltip]="'Remove branch ' + row.key + ' and its Steps'"
                                [attr.aria-label]="'Remove branch ' + row.key"
                            >
                                <mat-icon class="icon-size-4" svgIcon="heroicons_outline:trash"></mat-icon>
                            </button>
                        } @else {
                            @if (row.where) {
                                <span class="font-normal normal-case opacity-80">when {{ row.where }}</span>
                            }
                        }
                        @if (row.isDefault) {
                            <inspecto-chip variant="soft" tone="neutral">default</inspecto-chip>
                        }
                    </li>
                }
            }
        </ol>

        <mat-menu #verbMenu="matMenu">
            @for (v of verbs; track v.type) {
                <button mat-menu-item (click)="insertStep.emit({ type: v.type, afterId: insertAfterId })">
                    <mat-icon [svgIcon]="paletteHeroIcon(typeCat.get(v.type) ?? '')"></mat-icon>
                    <span>{{ v.label }}</span>
                </button>
            }
        </mat-menu>
    `,
})
export class PipelineStepCardsComponent {
    @Input({ required: true }) rows: StepRow[] = [];
    @Input({ required: true }) typeCat!: Map<string, string>;
    /**
     * node-type → per-type label ('Join'), used for the card's kind caption. Optional: a host with no
     * served catalog leaves it empty and every card falls back to its CATEGORY label — which is what
     * every card showed before, so a join and a filter read identically ('Transformer'). That fallback
     * is the degraded path, not the intent.
     */
    @Input() typeLabel: Map<string, string> = new Map();
    /** Optional — omitted when the host has no registry-refs/test-outcome state loaded yet. */
    @Input() statusOf?: (node: AuthoredNode) => NodeStatus;
    /** S2: reveal the editing affordances. The host still gates every mutation on canAuthor(). */
    @Input() editable = false;
    /** S4: the Add-Step verb palette — served step-types when the host has them, else the client map. */
    @Input() verbs: readonly { type: string; label: string }[] = RECIPE_VERBS;

    /** Open the Step's config dialog (the host routes parse → GrammarEditorDialog, rest → NodeConfigDialog). */
    @Output() readonly open = new EventEmitter<AuthoredNode>();
    /** Insert a new Step of `type` after the trunk node `afterId` (`null` = as the new entry). */
    @Output() readonly insertStep = new EventEmitter<{ type: string; afterId: string | null }>();
    /** Remove a trunk Step by node id. */
    @Output() readonly remove = new EventEmitter<string>();
    /** Move a trunk Step one place up/down the chain. */
    @Output() readonly move = new EventEmitter<{ id: string; dir: 'up' | 'down' }>();
    /** S3: add a named branch (+ its unconfigured sink) to a route Step. */
    @Output() readonly addBranch = new EventEmitter<{ routeId: string; key: string }>();
    /** S3: remove a branch and its downstream Steps. */
    @Output() readonly removeBranch = new EventEmitter<{ routeId: string; key: string }>();
    /** S3: set/clear a branch's `when` predicate. */
    @Output() readonly branchWhere = new EventEmitter<{ routeId: string; key: string; where: string }>();
    /** S3: mark a branch the route's default (`key: null` clears it — zero-or-one, the engine's contract). */
    @Output() readonly setDefault = new EventEmitter<{ routeId: string; key: string | null }>();
    /** S3: flip the route's `case|clone` mode. */
    @Output() readonly modeChange = new EventEmitter<{ routeId: string; mode: 'case' | 'clone' }>();

    /** Where the open verb menu will insert (set by the clicked "+" before the menu opens). */
    insertAfterId: string | null = null;

    /** What KIND of Step this card is: the type's own label, else its category group. */
    stepTypeLabel(type: string): string {
        return this.typeLabel.get(type) || categoryLabel(this.typeCat.get(type) ?? '');
    }
    readonly paletteHeroIcon = paletteHeroIcon;
    readonly statusIcon = statusIcon;
    readonly statusLabel = statusLabel;
    readonly statusTint = statusTint;

    /** The card's compact config summary — up to 4 entries, so a wide config doesn't dwarf the card. */
    configSummary(node: AuthoredNode): { k: string; v: string }[] {
        return nodeConfigEntries(node).slice(0, 4);
    }

    /** The route node's mode — `case` (exclusive first-match) unless the config says `clone` (RowShaper's rule). */
    routeMode(node: AuthoredNode): 'case' | 'clone' {
        return String(node.config?.['mode'] ?? '').toLowerCase() === 'clone' ? 'clone' : 'case';
    }

    /** Commit the add-branch draft input (Enter or the + button), clearing it on emit. */
    commitBranch(routeId: string, draft: HTMLInputElement): void {
        const key = draft.value.trim();
        if (!key) return;
        this.addBranch.emit({ routeId, key });
        draft.value = '';
    }
}
