import { ChangeDetectionStrategy, Component, HostListener, computed, input, output, signal, viewChild } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule, MatMenuTrigger } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import type { G6GraphData } from '@inspecto/core/graph/graph-types';
import type { GraphEmphasis } from '@inspecto/core/graph/graph-view.component';
import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
import {
    cleanupEffect,
    keepOnly,
    keepPathsBetweenSeeds,
    removeSelected,
    shortestPathsBetweenSeeds,
    trimDanglingLeaves,
} from '@inspecto/link-analysis/graph/graph-cleanup';

export type CleanupTool = 'paths' | 'shortest' | 'trim' | 'keep' | 'remove';

/** What an applied tool takes away: canvas node ids, the tool, and the reason an Investigation records with it. */
export interface CleanupApply {
    tool: CleanupTool;
    ids: string[];
    reason: string;
}

const TOOLS: { id: CleanupTool; label: string; icon: string }[] = [
    { id: 'paths', label: 'Keep paths between seeds', icon: 'heroicons_outline:arrows-right-left' },
    { id: 'shortest', label: 'Highlight shortest paths', icon: 'heroicons_outline:light-bulb' },
    { id: 'trim', label: 'Trim dangling leaves', icon: 'heroicons_outline:scissors' },
    { id: 'keep', label: 'Keep only selected', icon: 'heroicons_outline:check-circle' },
    { id: 'remove', label: 'Remove selected', icon: 'heroicons_outline:minus-circle' },
];

/**
 * **Clean up ▾** (LA-STUDIO-UI-REORG-1 §5.6): the four clean-up tools over the drawn canvas. A picked tool first states
 * its effect ("hides 612 of 921 entities") with Apply / Cancel; Highlight shortest paths only glows (Esc clears). It has
 * no write path: the host hides (exploration) or records (Investigation) what {@link applied} names.
 */
@Component({
    selector: 'inspecto-link-analysis-cleanup',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: { class: 'relative inline-block' },
    imports: [MatButtonModule, MatIconModule, MatMenuModule, MatTooltipModule, ReactiveFormsModule, PenFieldComponent],
    template: `
        <!-- below 1,300 px the Studio's second toolbar folds into one overflow menu, which offers these tools -->
        <button
            mat-icon-button
            type="button"
            class="hidden min-[1300px]:inline-flex"
            [matMenuTriggerFor]="menu"
            matTooltip="Clean up"
            aria-label="Clean up the canvas"
            data-testid="cleanup-toggle"
        >
            <!-- a broom (operator 2026-10-28); heroicons has none, so the Material symbol -->
            <mat-icon svgIcon="mat_outline:cleaning_services" aria-hidden="true"></mat-icon>
        </button>
        <mat-menu #menu="matMenu" aria-label="Clean-up tools">
            @for (t of tools; track t.id) {
                <button mat-menu-item type="button" (click)="pick(t.id)" [attr.data-testid]="'cleanup-' + t.id">
                    <mat-icon [svgIcon]="t.icon" aria-hidden="true"></mat-icon>
                    <span>{{ t.label }}</span>
                </button>
            }
        </mat-menu>
        @if (tool(); as t) {
            <div
                class="absolute left-0 top-full z-30 mt-1 flex w-80 max-w-[90vw] flex-col gap-2 rounded border p-3 text-sm shadow-lg"
                style="background: var(--gamma-bg-card); border-color: var(--gamma-border)"
                role="group"
                [attr.aria-label]="label(t)"
                data-testid="cleanup-panel"
            >
                <div class="font-semibold">{{ label(t) }}</div>
                @if (t === 'paths') {
                    <inspecto-pen-field label="Longest path (links)" type="number" [formControl]="maxLen" />
                }
                @if (blocked(); as why) {
                    <p class="m-0" role="alert" data-testid="cleanup-blocked">{{ why }}</p>
                    @if (t === 'paths' || t === 'shortest') {
                        <button mat-stroked-button type="button" (click)="findOnServer.emit(len()); close()">
                            Find paths (server)
                        </button>
                    }
                } @else if (t === 'shortest') {
                    <p class="m-0" data-testid="cleanup-effect">
                        Lights {{ shortest()?.nodeIds?.length ?? 0 }} entities on the shortest paths; nothing is removed.
                    </p>
                } @else {
                    <p class="m-0" data-testid="cleanup-effect">{{ effect() }}</p>
                }
                <div class="flex justify-end gap-2">
                    <button mat-button type="button" (click)="close()" data-testid="cleanup-cancel">Cancel</button>
                    <button
                        mat-flat-button
                        color="primary"
                        type="button"
                        [disabled]="!!blocked()"
                        (click)="apply()"
                        data-testid="cleanup-apply"
                    >
                        {{ t === 'shortest' ? 'Highlight' : 'Apply' }}
                    </button>
                </div>
            </div>
        }
        @if (glowing()) {
            <button mat-button type="button" (click)="clearGlow()" aria-label="Clear the shortest-path highlight" data-testid="cleanup-clear-glow">
                <mat-icon svgIcon="heroicons_outline:x-mark" aria-hidden="true"></mat-icon>
                Clear highlight
            </button>
        }
    `,
})
export class LinkAnalysisCleanupComponent {
    /** What the canvas draws now. */
    readonly graph = input<G6GraphData | null>(null);
    /** Canvas ids of the seeds (an Investigation's seeds, or the selection while exploring). */
    readonly seeds = input<string[]>([]);
    readonly selection = input<string[]>([]);
    /** Pinned and held canvas ids: Trim never takes them. */
    readonly spare = input<string[]>([]);
    /** The browser path code's node cap; over it the path tools are disabled and offer the server. */
    readonly nodeCap = input<number>(Infinity);
    readonly applied = output<CleanupApply>();
    readonly highlight = output<GraphEmphasis | null>();
    /** *Find paths (server)*, with the longest path asked for (links). */
    readonly findOnServer = output<number>();

    readonly tools = TOOLS;
    readonly tool = signal<CleanupTool | null>(null);
    readonly glowing = signal(false);
    readonly maxLen = new FormControl(4, { nonNullable: true, validators: [Validators.min(1), Validators.max(8)] });
    readonly len = signal(4);
    private readonly trigger = viewChild.required(MatMenuTrigger);

    constructor() {
        this.maxLen.valueChanges.subscribe((v) => this.len.set(Number(v)));
    }

    label(t: CleanupTool): string {
        return TOOLS.find((x) => x.id === t)!.label;
    }

    /** Why the picked tool cannot run here, or null. */
    readonly blocked = computed<string | null>(() => {
        const t = this.tool();
        const g = this.graph();
        if (!t || !g) return t ? 'Nothing is drawn.' : null;
        if (t === 'paths' || t === 'shortest') {
            if (g.nodes.length > this.nodeCap())
                return `Path search in the browser needs at most ${this.nodeCap().toLocaleString('en')} nodes — this graph has ${g.nodes.length.toLocaleString('en')}.`;
            if (new Set(this.seeds()).size < 2) return 'Needs at least two seeds on the canvas.';
            if (t === 'paths' && !(this.len() >= 1 && this.len() <= 8)) return 'The longest path is 1 to 8 links.';
            if (t === 'paths' && this.hidden() === null) return 'Too many paths to list in the browser.';
        }
        if ((t === 'keep' || t === 'remove') && !this.selection().length) return 'Select entities first.';
        return null;
    });

    /** The ids the picked tool takes away (null: not computable in the browser). */
    readonly hidden = computed<string[] | null>(() => {
        const t = this.tool();
        const g = this.graph();
        if (!t || !g) return [];
        switch (t) {
            case 'paths':
                return g.nodes.length > this.nodeCap() ? null : keepPathsBetweenSeeds(g, this.seeds(), this.len()).hide;
            case 'trim':
                return trimDanglingLeaves(g, [...this.seeds(), ...this.spare()]);
            case 'keep':
                return keepOnly(g, this.selection());
            case 'remove':
                return removeSelected(g, this.selection());
            default:
                return [];
        }
    });

    readonly shortest = computed(() => {
        const g = this.graph();
        return g && this.tool() === 'shortest' ? shortestPathsBetweenSeeds(g, this.seeds()) : null;
    });

    readonly effect = computed(() => cleanupEffect(this.hidden()?.length ?? 0, this.graph()?.nodes.length ?? 0));

    /** Open the tool menu ("reduce first", §5.7). */
    openMenu(): void {
        this.trigger().openMenu();
    }

    pick(t: CleanupTool): void {
        this.tool.set(t);
    }

    close(): void {
        this.tool.set(null);
    }

    apply(): void {
        const t = this.tool();
        if (!t || this.blocked()) return;
        if (t === 'shortest') {
            const s = this.shortest();
            this.highlight.emit(s && s.nodeIds.length ? s : null);
            this.glowing.set(!!s?.nodeIds.length);
        } else {
            const ids = this.hidden() ?? [];
            if (ids.length) this.applied.emit({ tool: t, ids, reason: this.reasonFor(t) });
        }
        this.close();
    }

    clearGlow(): void {
        this.glowing.set(false);
        this.highlight.emit(null);
    }

    @HostListener('document:keydown.escape')
    onEscape(): void {
        if (this.tool()) this.close();
        else if (this.glowing()) this.clearGlow();
    }

    /** The reason an Investigation records with the hide (prefilled; §5.6). */
    private reasonFor(t: CleanupTool): string {
        switch (t) {
            case 'paths':
                return `Clean up: not on a path between seeds, L = ${this.len()}`;
            case 'trim':
                return 'Clean up: dangling leaf';
            case 'keep':
                return 'Clean up: not in the kept selection';
            default:
                return 'Clean up: removed selection';
        }
    }
}
