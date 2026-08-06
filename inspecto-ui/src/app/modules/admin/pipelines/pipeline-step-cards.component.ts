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
 * move up/down (→ {@link move}). Branch rows (depth > 0) stay read-only cards except Configure —
 * trunk-only editing is S2's deliberate scope; the branch editor is S3.
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
                            <span class="shrink-0 text-xs opacity-60">{{ categoryLabel(typeCat.get(row.node.type) ?? '') }}</span>
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
                        @if (row.where) {
                            <span class="font-normal normal-case opacity-80">when {{ row.where }}</span>
                        }
                        @if (row.isDefault) {
                            <inspecto-chip variant="soft" tone="neutral">default</inspecto-chip>
                        }
                    </li>
                }
            }
        </ol>

        <mat-menu #verbMenu="matMenu">
            @for (v of verbs; track v.verb) {
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
    /** Optional — omitted when the host has no registry-refs/test-outcome state loaded yet. */
    @Input() statusOf?: (node: AuthoredNode) => NodeStatus;
    /** S2: reveal the editing affordances. The host still gates every mutation on canAuthor(). */
    @Input() editable = false;

    /** Open the Step's config dialog (the host routes parse → GrammarEditorDialog, rest → NodeConfigDialog). */
    @Output() readonly open = new EventEmitter<AuthoredNode>();
    /** Insert a new Step of `type` after the trunk node `afterId` (`null` = as the new entry). */
    @Output() readonly insertStep = new EventEmitter<{ type: string; afterId: string | null }>();
    /** Remove a trunk Step by node id. */
    @Output() readonly remove = new EventEmitter<string>();
    /** Move a trunk Step one place up/down the chain. */
    @Output() readonly move = new EventEmitter<{ id: string; dir: 'up' | 'down' }>();

    /** Where the open verb menu will insert (set by the clicked "+" before the menu opens). */
    insertAfterId: string | null = null;

    readonly verbs = RECIPE_VERBS;
    readonly categoryLabel = categoryLabel;
    readonly paletteHeroIcon = paletteHeroIcon;
    readonly statusIcon = statusIcon;
    readonly statusLabel = statusLabel;
    readonly statusTint = statusTint;

    /** The card's compact config summary — up to 4 entries, so a wide config doesn't dwarf the card. */
    configSummary(node: AuthoredNode): { k: string; v: string }[] {
        return nodeConfigEntries(node).slice(0, 4);
    }
}
