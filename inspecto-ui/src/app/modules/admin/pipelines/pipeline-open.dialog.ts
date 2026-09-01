import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { PipelineSummary, apiErrorMessage } from 'app/inspecto/api';
import { InspectoDialogResizeDirective } from 'app/inspecto/components/dialog-resize.directive';
import { StreamTransferService } from 'app/inspecto/transfer/stream-transfer.service';

export interface PipelineOpenData {
    /** Every pipeline the server lists — names only; no graph is fetched to build this. */
    pipelines: PipelineSummary[];
    /** Already-open ids, pre-ticked so the dialog reads as "what is open" rather than "what to add". */
    open: string[];
    /** Open ids with unsaved edits — a per-row export refuses these (an export carries SAVED state). */
    dirty?: string[];
}

/**
 * Open dialog — search the pipeline list and tick one or many to open as canvas tabs.
 *
 * <p>Exists because the editor no longer loads anything on arrival: listing pipelines is cheap
 * (`GET /pipelines`, names + flags), lifting each one's graph is not, so the open set is an explicit
 * user choice. Pre-ticking what is already open makes this the one place both opening and closing
 * happen — untick-and-Open closes a tab, which is why the result is the full desired set rather than
 * a delta.
 */
@Component({
    selector: 'app-pipeline-open-dialog',
    standalone: true,
    imports: [
        MatDialogModule,
        MatButtonModule,
        MatCheckboxModule,
        MatIconModule,
        MatTooltipModule,
        NgTemplateOutlet,
        InspectoDialogResizeDirective,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title inspectoDialogResize>Open pipelines</h2>
        <mat-dialog-content>
            <div class="relative mb-2">
                <mat-icon
                    class="icon-size-4 text-secondary pointer-events-none absolute left-2 top-1/2 -translate-y-1/2"
                    svgIcon="heroicons_outline:magnifying-glass"
                ></mat-icon>
                <input
                    type="text"
                    class="bg-card w-full rounded border border-gray-300 py-1.5 pl-8 pr-2 text-sm dark:border-gray-600"
                    placeholder="Search pipelines…"
                    aria-label="Search pipelines"
                    cdkFocusInitial
                    [value]="query()"
                    (input)="onSearch($event)"
                />
            </div>

            <!-- ONE row template for every section — the sections may never drift from the full list. -->
            <ng-template #row let-p>
                <div class="flex items-center gap-1 rounded px-1 hover:bg-black/5 dark:hover:bg-white/10">
                    <label class="flex min-w-0 flex-auto cursor-pointer items-center gap-2 py-1 text-sm">
                        <!-- MDC's own inner (empty) label wins the association, so the row text
                             never names the input — give it an explicit accessible name. -->
                        <mat-checkbox
                            [checked]="picked().has(p.name)"
                            (change)="toggle(p.name)"
                            [aria-label]="'Open ' + (p.displayName || p.name)"
                        >
                        </mat-checkbox>
                        <span class="flex min-w-0 flex-auto flex-col">
                            <span class="truncate">{{ p.displayName || p.name }}</span>
                            @if (p.description) {
                                <span class="text-secondary truncate text-xs">{{ p.description }}</span>
                            }
                        </span>
                        @if (p.template) {
                            <span class="shrink-0 text-xs opacity-60">template</span>
                        } @else if (p.active) {
                            <span class="shrink-0 text-xs" style="color: var(--gamma-primary)">active</span>
                        }
                    </label>
                    <!-- Buttons OUTSIDE the label so a click can never toggle the checkbox. -->
                    <button
                        mat-icon-button
                        class="shrink-0"
                        (click)="togglePin(p.name, $event)"
                        [matTooltip]="pinned().has(p.name) ? 'Unpin' : 'Pin'"
                        [attr.aria-pressed]="pinned().has(p.name)"
                        [attr.aria-label]="(pinned().has(p.name) ? 'Unpin ' : 'Pin ') + (p.displayName || p.name)"
                    >
                        <mat-icon
                            class="icon-size-4"
                            [svgIcon]="pinned().has(p.name) ? 'heroicons_solid:star' : 'heroicons_outline:star'"
                        ></mat-icon>
                    </button>
                    <!-- Export is a READ (the saved server-held config), so no author gate — a
                         Business-lens operator handing a config to support is the point. -->
                    <button
                        mat-icon-button
                        class="shrink-0"
                        [disabled]="exporting().has(p.name)"
                        (click)="exportRow(p, $event)"
                        [matTooltip]="'Export configuration'"
                        [attr.aria-label]="'Export the ' + (p.displayName || p.name) + ' configuration'"
                    >
                        <mat-icon class="icon-size-4" svgIcon="heroicons_outline:arrow-down-tray"></mat-icon>
                    </button>
                </div>
            </ng-template>

            <div class="max-h-80 min-w-80 overflow-y-auto">
                @if (pinnedRows().length) {
                    <p class="px-1 pb-0.5 pt-1 text-xs font-semibold uppercase tracking-wide opacity-60">Pinned</p>
                    @for (p of pinnedRows(); track p.name) {
                        <ng-container *ngTemplateOutlet="row; context: { $implicit: p }" />
                    }
                }
                @if (recentRows().length) {
                    <p class="px-1 pb-0.5 pt-1 text-xs font-semibold uppercase tracking-wide opacity-60">Recent</p>
                    @for (p of recentRows(); track p.name) {
                        <ng-container *ngTemplateOutlet="row; context: { $implicit: p }" />
                    }
                }
                @if (pinnedRows().length || recentRows().length) {
                    <p class="px-1 pb-0.5 pt-1 text-xs font-semibold uppercase tracking-wide opacity-60">All pipelines</p>
                }
                @for (p of filtered(); track p.name) {
                    <ng-container *ngTemplateOutlet="row; context: { $implicit: p }" />
                } @empty {
                    <p class="px-1 py-3 text-sm opacity-60">
                        @if (data.pipelines.length) {
                            No pipeline matches '{{ query() }}'.
                        } @else {
                            No authored pipelines yet.
                        }
                    </p>
                }
            </div>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <!-- The guided create — navigates to Catalog onboarding rather than importing its dialog
                 (a feature may not import a feature); onboarding redirects back into the editor. -->
            <button type="button" mat-button (click)="newPipeline()">
                <mat-icon svgIcon="heroicons_outline:plus"></mat-icon>
                <span>New pipeline&hellip;</span>
            </button>
            <span class="mr-auto pl-2 text-xs opacity-60">{{ picked().size }} selected</span>
            <button type="button" mat-button mat-dialog-close>Cancel</button>
            <button type="button" mat-flat-button color="primary" (click)="confirm()">Open</button>
        </mat-dialog-actions>
    `,
})
export class PipelineOpenDialog {
    /** Last ~8 ids the dialog's confirm newly opened, most-recent-first (R5). */
    private static readonly MRU_KEY = 'inspecto.pipelines.mru';
    private static readonly MRU_CAP = 8;
    /** Ids the user pinned via the per-row star (R5) — an unordered set. */
    private static readonly PINNED_KEY = 'inspecto.pipelines.pinned';

    /** Storage is a convenience: unreadable/corrupt reads as empty, a failed write is silent. */
    private static readStrings(key: string): string[] {
        try {
            const parsed: unknown = JSON.parse(localStorage.getItem(key) ?? '[]');
            return Array.isArray(parsed) ? parsed.filter((v): v is string => typeof v === 'string') : [];
        } catch {
            return [];
        }
    }

    private static writeStrings(key: string, values: readonly string[]): void {
        try {
            localStorage.setItem(key, JSON.stringify(values));
        } catch {
            // Storage unavailable (private mode, quota) — never worth erroring.
        }
    }

    private ref = inject(MatDialogRef<PipelineOpenDialog, string[]>);
    private router = inject(Router);
    private transfer = inject(StreamTransferService);
    private toast = inject(ToastrService);
    readonly data = inject<PipelineOpenData>(MAT_DIALOG_DATA);

    readonly query = signal('');
    readonly picked = signal<ReadonlySet<string>>(new Set(this.data.open));
    /** Rows with an export in flight — disables that row's download button, nothing else. */
    readonly exporting = signal<ReadonlySet<string>>(new Set());
    private readonly dirty = new Set(this.data.dirty ?? []);
    /** The open set AT dialog open — confirm's MRU update counts only ids ticked beyond this. */
    private readonly openAtStart = new Set(this.data.open);

    /**
     * Stored ids kept RAW here (they may name pipelines this space/server no longer lists — e.g.
     * another space's ids sharing the device); staleness is dropped on RENDER against the served
     * list, never scrubbed from storage — the same rule as the open-tab persistence.
     */
    private readonly mru: readonly string[] = PipelineOpenDialog.readStrings(PipelineOpenDialog.MRU_KEY);
    readonly pinned = signal<ReadonlySet<string>>(
        new Set(PipelineOpenDialog.readStrings(PipelineOpenDialog.PINNED_KEY)),
    );

    readonly filtered = computed<PipelineSummary[]>(() => {
        const q = this.query().trim().toLowerCase();
        if (!q) return this.data.pipelines;
        return this.data.pipelines.filter(
            (p) => p.name.toLowerCase().includes(q) || (p.displayName ?? '').toLowerCase().includes(q),
        );
    });

    /** Pinned section — served, search-filtered rows only (a stale pinned id simply never renders). */
    readonly pinnedRows = computed<PipelineSummary[]>(() =>
        this.filtered().filter((p) => this.pinned().has(p.name)),
    );

    /** Recent section — MRU order, deduped against Pinned so a pinned row never repeats here. */
    readonly recentRows = computed<PipelineSummary[]>(() => {
        const pinned = this.pinned();
        const byName = new Map(this.filtered().map((p) => [p.name, p]));
        return this.mru
            .map((id) => byName.get(id))
            .filter((p): p is PipelineSummary => p !== undefined && !pinned.has(p.name));
    });

    onSearch(e: Event): void {
        this.query.set((e.target as HTMLInputElement).value);
    }

    /** The per-row star — membership in the pinned set, persisted immediately. */
    togglePin(name: string, event: Event): void {
        event.preventDefault();
        event.stopPropagation();
        this.pinned.update((s) => {
            const next = new Set(s);
            if (!next.delete(name)) next.add(name);
            return next;
        });
        PipelineOpenDialog.writeStrings(PipelineOpenDialog.PINNED_KEY, [...this.pinned()]);
    }

    toggle(name: string): void {
        this.picked.update((s) => {
            const next = new Set(s);
            if (!next.delete(name)) next.add(name);
            return next;
        });
    }

    /** Close first, then hand off to the Catalog onboarding entry — its flow redirects back here. */
    newPipeline(): void {
        this.ref.close();
        this.router.navigate(['/catalog'], { queryParams: { onboard: 'stream' } });
    }

    /**
     * Export THIS row's stream-config bundle without opening it — the same
     * {@link StreamTransferService.exportPipeline} seam the editor's menu item uses, reading the
     * SERVER-held config. A row that is open with unsaved edits refuses (same rule as the editor):
     * the file would quietly disagree with that tab's screen.
     */
    exportRow(p: PipelineSummary, event: Event): void {
        // Inside the row's <label>: without this the click also toggles the open checkbox.
        event.preventDefault();
        event.stopPropagation();
        if (this.exporting().has(p.name)) return;
        if (this.dirty.has(p.name)) {
            this.toast.warning('Apply this pipeline before exporting — an export carries the saved configuration.');
            return;
        }
        this.exporting.update((s) => new Set(s).add(p.name));
        const done = (): void =>
            this.exporting.update((s) => {
                const next = new Set(s);
                next.delete(p.name);
                return next;
            });
        this.transfer.exportPipeline(p.name).subscribe({
            next: ({ bundle, missing }) => {
                done();
                this.transfer.download(bundle);
                if (missing.length) this.toast.warning(`Exported without ${missing.join(', ')} — could not be read.`);
                else this.toast.success(`Exported "${p.name}" configuration`);
            },
            error: (err) => {
                done();
                this.toast.error(apiErrorMessage(err, 'Could not export the configuration.'));
            },
        });
    }

    /** Returns the full desired open set, in the listed order so tabs are stable across re-opens. */
    confirm(): void {
        const picked = this.picked();
        const result = this.data.pipelines.filter((p) => picked.has(p.name)).map((p) => p.name);
        // MRU records what this confirm NEWLY opened (recorded here, from the dialog's own result —
        // the editor is not involved). Newly-ticked ids go to the front, then the prior entries
        // minus duplicates, capped — most-recent-first.
        const newlyTicked = result.filter((id) => !this.openAtStart.has(id));
        if (newlyTicked.length) {
            const fresh = new Set(newlyTicked);
            PipelineOpenDialog.writeStrings(
                PipelineOpenDialog.MRU_KEY,
                [...newlyTicked, ...this.mru.filter((id) => !fresh.has(id))].slice(0, PipelineOpenDialog.MRU_CAP),
            );
        }
        this.ref.close(result);
    }
}
