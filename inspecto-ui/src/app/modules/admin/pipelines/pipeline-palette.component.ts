import { ChangeDetectionStrategy, Component, EventEmitter, Output, computed, input, signal } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import {
    categoryColor,
    categoryLabel,
    familyCategory,
    familyColor,
    NodeTypeGroup,
    ProcessorGroup,
    typeHeroIcon,
} from './pipeline-graph';

/**
 * The processor palette — the editor's **left dock** content (the "Shapes" panel of the Visio-style
 * shell). Presentational: the host supplies the node-type catalog, owns the dock's width/collapse
 * (`[inspectoSplit]`), and reacts to `pick` (click-to-add at canvas centre). Drag-to-position needs no
 * output — it writes the type onto the native `dataTransfer` (`text/flow-node-type`), which
 * `PipelineEditorGraphComponent`'s drop handler reads.
 *
 * <p>Owns only its own filter + which sections are folded. Sections start expanded: the catalog
 * is small enough that hiding it costs more than it saves, and a search box is the scale answer.
 *
 * <p><b>Two catalogs, one palette (2026-09-02).</b> When the host supplies the served Step Processor
 * taxonomy ({@link processors} — eight families, every processor the product names, delivered or not),
 * the palette renders THAT: an {@code addable} processor is a normal add/drag entry for its node type;
 * everything else (planned, or a capability that is not a Step) is shown <em>inactive</em> — visible,
 * labelled, tooltipped with why, never addable. Operator decision: "all visible on screen, inactivated
 * whichever not delivered". Without the taxonomy (an old server) the node-type {@link groups} render
 * exactly as before.
 */
@Component({
    selector: 'app-pipeline-palette',
    standalone: true,
    imports: [MatIconModule, MatTooltipModule, ChipComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: { class: 'flex min-h-0 flex-1 flex-col' },
    template: `
        <div class="border-b p-2" style="border-color: var(--gamma-border)">
            <div class="relative">
                <mat-icon
                    class="icon-size-4 text-secondary pointer-events-none absolute left-2 top-1/2 -translate-y-1/2"
                    svgIcon="heroicons_outline:magnifying-glass"
                ></mat-icon>
                <input
                    type="text"
                    class="bg-card w-full rounded border border-gray-300 py-1 pl-8 pr-2 text-xs dark:border-gray-600"
                    placeholder="Search steps…"
                    aria-label="Search step types"
                    [value]="query()"
                    (input)="onSearch($event)"
                />
            </div>
        </div>

        <div class="min-h-0 flex-1 overflow-y-auto p-1.5">
            @if (filteredProcessors(); as pgroups) {
                @for (g of pgroups; track g.family.code) {
                    <div class="mb-1">
                        <button
                            type="button"
                            class="flex w-full items-center gap-2.5 rounded px-1.5 py-2 text-base font-semibold uppercase hover:bg-black/5 dark:hover:bg-white/10"
                            [attr.aria-expanded]="isOpen(g.family.code)"
                            (click)="toggleGroup(g.family.code)"
                        >
                            <mat-icon
                                class="icon-size-5 shrink-0 opacity-60"
                                [svgIcon]="
                                    isOpen(g.family.code)
                                        ? 'heroicons_outline:chevron-down'
                                        : 'heroicons_outline:chevron-right'
                                "
                            ></mat-icon>
                            <span class="truncate opacity-70">{{ g.family.label }}</span>
                            <span class="ml-auto text-xs font-normal opacity-40">
                                {{ addableCount(g) }}/{{ g.processors.length }}
                            </span>
                        </button>
                        @if (isOpen(g.family.code)) {
                            @for (p of g.processors; track p.id) {
                                @if (p.addable && p.nodeType) {
                                    <button
                                        type="button"
                                        class="flex w-full cursor-grab items-center gap-2.5 rounded py-2 pl-8 pr-2 text-left text-base hover:bg-black/5 dark:hover:bg-white/10"
                                        draggable="true"
                                        [matTooltip]="p.note || p.label"
                                        [attr.aria-label]="'Add ' + p.label"
                                        (click)="pick.emit(p.nodeType)"
                                        (dragstart)="$event.dataTransfer?.setData('text/flow-node-type', p.nodeType)"
                                    >
                                        <mat-icon
                                            class="icon-size-6 shrink-0"
                                            [svgIcon]="
                                                p.icon || typeHeroIcon(p.nodeType, familyCategory(g.family.code))
                                            "
                                            [style.color]="familyColor(g.family.code)"
                                        ></mat-icon>
                                        <span class="truncate">{{ p.label }}</span>
                                    </button>
                                } @else {
                                    <!-- Inactive: visible and named, never addable. A disabled <button> swallows
                                         pointer events (so no tooltip) — a span with the disabled semantics
                                         carries the "why" instead. -->
                                    <span
                                        class="flex w-full cursor-not-allowed items-center gap-2.5 rounded py-2 pl-8 pr-2 text-left text-base opacity-50"
                                        role="button"
                                        aria-disabled="true"
                                        [matTooltip]="inactiveReason(p)"
                                        [attr.aria-label]="p.label + ' — ' + inactiveReason(p)"
                                        [attr.data-processor]="p.id"
                                    >
                                        <mat-icon
                                            class="icon-size-6 shrink-0"
                                            [svgIcon]="p.icon || g.family.icon"
                                            [style.color]="familyColor(g.family.code)"
                                        ></mat-icon>
                                        <span class="truncate">{{ p.label }}</span>
                                        @if (p.status !== 'planned') {
                                            <inspecto-chip class="ml-auto shrink-0" variant="soft" tone="neutral">
                                                via {{ p.capability || p.nodeType }}
                                            </inspecto-chip>
                                        }
                                    </span>
                                }
                            }
                        }
                    </div>
                } @empty {
                    <p class="px-2 py-3 text-xs opacity-60">No step processor matches '{{ query() }}'.</p>
                }
            } @else {
                @for (group of filtered(); track group.category) {
                    <div class="mb-1">
                        <button
                            type="button"
                            class="flex w-full items-center gap-2.5 rounded px-1.5 py-2 text-base font-semibold uppercase hover:bg-black/5 dark:hover:bg-white/10"
                            [attr.aria-expanded]="isOpen(group.category)"
                            (click)="toggleGroup(group.category)"
                        >
                            <mat-icon
                                class="icon-size-6 shrink-0 opacity-60"
                                [svgIcon]="
                                    isOpen(group.category)
                                        ? 'heroicons_outline:chevron-down'
                                        : 'heroicons_outline:chevron-right'
                                "
                            ></mat-icon>
                            <span class="truncate opacity-70">{{ categoryLabel(group.category) }}</span>
                            <span class="ml-auto text-xs font-normal opacity-40">{{ group.types.length }}</span>
                        </button>

                        @if (isOpen(group.category)) {
                            @for (t of group.types; track t.type) {
                                <!-- Hosts pass only lowerable types (the editor filters the catalog), so
                                 every entry is addable — no disabled state to draw. -->
                                <button
                                    type="button"
                                    class="flex w-full cursor-grab items-center gap-2.5 rounded py-2 pl-8 pr-2 text-left text-base hover:bg-black/5 dark:hover:bg-white/10"
                                    draggable="true"
                                    [matTooltip]="t.description"
                                    [attr.aria-label]="'Add ' + t.label"
                                    (click)="pick.emit(t.type)"
                                    (dragstart)="$event.dataTransfer?.setData('text/flow-node-type', t.type)"
                                >
                                    <!-- Each item carries its OWN glyph; the category is the TINT
                                     (matching the header dot), never a shared glyph per group. -->
                                    <mat-icon
                                        class="icon-size-6 shrink-0"
                                        [svgIcon]="typeHeroIcon(t.type, group.category)"
                                        [style.color]="categoryColor(group.category)"
                                    ></mat-icon>
                                    <span class="truncate">{{ t.label }}</span>
                                </button>
                            }
                        }
                    </div>
                } @empty {
                    <p class="px-2 py-3 text-xs opacity-60">No step type matches '{{ query() }}'.</p>
                }
            }
        </div>

        <div class="border-t px-2 py-1.5 text-xs opacity-50" style="border-color: var(--gamma-border)">
            click to add · drag to place
        </div>
    `,
})
export class PipelinePaletteComponent {
    /** A **signal** input: the catalog loads async, and `filtered()` must recompute when it lands. */
    readonly groups = input.required<NodeTypeGroup[]>();
    /** The served Step Processor taxonomy, grouped by family; `null` = not served (old server) → render `groups`. */
    readonly processors = input<ProcessorGroup[] | null>(null);
    /** A palette entry was clicked — add it at the canvas centre (the no-mouse path). */
    @Output() pick = new EventEmitter<string>();

    readonly query = signal('');
    /** Categories the user folded away. Absent = expanded, so a new catalog group shows by default. */
    private readonly folded = signal<ReadonlySet<string>>(new Set());

    /** The catalog narrowed to the search text (matched on the type's label and its id). */
    readonly filtered = computed<NodeTypeGroup[]>(() => {
        const q = this.query().trim().toLowerCase();
        if (!q) return this.groups();
        return this.groups()
            .map((g) => ({
                category: g.category,
                types: g.types.filter((t) => t.label.toLowerCase().includes(q) || t.type.toLowerCase().includes(q)),
            }))
            .filter((g) => g.types.length > 0);
    });

    /** The taxonomy narrowed to the search text (label, id, or the node type it maps to); `null` when not served. */
    readonly filteredProcessors = computed<ProcessorGroup[] | null>(() => {
        const groups = this.processors();
        if (!groups || groups.length === 0) return null;
        const q = this.query().trim().toLowerCase();
        if (!q) return groups;
        return groups
            .map((g) => ({
                family: g.family,
                processors: g.processors.filter(
                    (p) =>
                        p.label.toLowerCase().includes(q) ||
                        p.id.toLowerCase().includes(q) ||
                        (p.nodeType ?? '').toLowerCase().includes(q),
                ),
            }))
            .filter((g) => g.processors.length > 0);
    });
    readonly categoryColor = categoryColor;
    readonly categoryLabel = categoryLabel;
    readonly familyCategory = familyCategory;
    readonly familyColor = familyColor;
    readonly typeHeroIcon = typeHeroIcon;

    addableCount(g: ProcessorGroup): number {
        return g.processors.filter((p) => p.addable).length;
    }

    /** Why an inactive entry cannot be added — the tooltip and the accessible name carry it. */
    inactiveReason(p: { status: string; capability?: string; nodeType?: string; note?: string }): string {
        if (p.status === 'planned') return 'Not yet available' + (p.note ? ' — ' + p.note : '');
        const via = p.capability ?? p.nodeType;
        return 'Not a Step you add here — delivered as ' + via + (p.note ? ' — ' + p.note : '');
    }

    /** A searching user wants to see the hits — the fold state only applies to the unfiltered catalog. */
    isOpen(category: string): boolean {
        return !!this.query().trim() || !this.folded().has(category);
    }

    toggleGroup(category: string): void {
        this.folded.update((s) => {
            const next = new Set(s);
            if (!next.delete(category)) next.add(category);
            return next;
        });
    }

    onSearch(e: Event): void {
        this.query.set((e.target as HTMLInputElement).value);
    }
}
