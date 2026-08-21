import { ChangeDetectionStrategy, Component, EventEmitter, Output, computed, input, signal } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { categoryColor, categoryLabel, NodeTypeGroup, typeHeroIcon } from './pipeline-graph';

/**
 * The processor palette — the editor's **left dock** content (the "Shapes" panel of the Visio-style
 * shell). Presentational: the host supplies the node-type catalog, owns the dock's width/collapse
 * (`[inspectoSplit]`), and reacts to `pick` (click-to-add at canvas centre). Drag-to-position needs no
 * output — it writes the type onto the native `dataTransfer` (`text/flow-node-type`), which
 * `PipelineEditorGraphComponent`'s drop handler reads.
 *
 * <p>Owns only its own filter + which category sections are folded. Sections start expanded: the catalog
 * is small enough that hiding it costs more than it saves, and a search box is the scale answer.
 */
@Component({
    selector: 'app-pipeline-palette',
    standalone: true,
    imports: [MatIconModule, MatTooltipModule],
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
            @for (group of filtered(); track group.category) {
                <div class="mb-1">
                    <button
                        type="button"
                        class="flex w-full items-center gap-1.5 rounded px-1.5 py-1 text-xs font-semibold uppercase hover:bg-black/5 dark:hover:bg-white/10"
                        [attr.aria-expanded]="isOpen(group.category)"
                        (click)="toggleGroup(group.category)"
                    >
                        <mat-icon
                            class="icon-size-4 shrink-0 opacity-60"
                            [svgIcon]="
                                isOpen(group.category)
                                    ? 'heroicons_outline:chevron-down'
                                    : 'heroicons_outline:chevron-right'
                            "
                        ></mat-icon>
                        <span
                            class="h-2 w-2 shrink-0 rounded-full"
                            [style.background]="categoryColor(group.category)"
                        ></span>
                        <span class="truncate opacity-70">{{ categoryLabel(group.category) }}</span>
                        <span class="ml-auto text-xs font-normal opacity-40">{{ group.types.length }}</span>
                    </button>

                    @if (isOpen(group.category)) {
                        @for (t of group.types; track t.type) {
                            <!-- Hosts pass only lowerable types (the editor filters the catalog), so
                                 every entry is addable — no disabled state to draw. -->
                            <button
                                type="button"
                                class="flex w-full cursor-grab items-center gap-1.5 rounded py-1 pl-7 pr-2 text-left text-xs hover:bg-black/5 dark:hover:bg-white/10"
                                draggable="true"
                                [matTooltip]="t.description"
                                [attr.aria-label]="'Add ' + t.label"
                                (click)="pick.emit(t.type)"
                                (dragstart)="$event.dataTransfer?.setData('text/flow-node-type', t.type)"
                            >
                                <!-- Each item carries its OWN glyph; the category is the TINT
                                     (matching the header dot), never a shared glyph per group. -->
                                <mat-icon
                                    class="icon-size-4 shrink-0"
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
        </div>

        <div class="border-t px-2 py-1.5 text-xs opacity-50" style="border-color: var(--gamma-border)">
            click to add · drag to place
        </div>
    `,
})
export class PipelinePaletteComponent {
    /** A **signal** input: the catalog loads async, and `filtered()` must recompute when it lands. */
    readonly groups = input.required<NodeTypeGroup[]>();
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

    readonly categoryColor = categoryColor;
    readonly categoryLabel = categoryLabel;
    readonly typeHeroIcon = typeHeroIcon;

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
