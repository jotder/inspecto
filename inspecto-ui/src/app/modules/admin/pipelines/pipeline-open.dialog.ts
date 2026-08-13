import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { PipelineSummary } from 'app/inspecto/api';
import { InspectoDialogResizeDirective } from 'app/inspecto/components/dialog-resize.directive';

export interface PipelineOpenData {
    /** Every pipeline the server lists — names only; no graph is fetched to build this. */
    pipelines: PipelineSummary[];
    /** Already-open ids, pre-ticked so the dialog reads as "what is open" rather than "what to add". */
    open: string[];
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
    imports: [MatDialogModule, MatButtonModule, MatCheckboxModule, MatIconModule, InspectoDialogResizeDirective],
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

            <div class="max-h-80 min-w-80 overflow-y-auto">
                @for (p of filtered(); track p.name) {
                    <label
                        class="flex cursor-pointer items-center gap-2 rounded px-1 py-1 text-sm hover:bg-black/5 dark:hover:bg-white/10"
                    >
                        <mat-checkbox [checked]="picked().has(p.name)" (change)="toggle(p.name)"> </mat-checkbox>
                        <span class="min-w-0 flex-auto truncate">{{ p.displayName || p.name }}</span>
                        @if (p.template) {
                            <span class="shrink-0 text-xs opacity-60">template</span>
                        } @else if (p.active) {
                            <span class="shrink-0 text-xs" style="color: var(--gamma-primary)">active</span>
                        }
                    </label>
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
            <span class="mr-auto pl-2 text-xs opacity-60">{{ picked().size }} selected</span>
            <button type="button" mat-button mat-dialog-close>Cancel</button>
            <button type="button" mat-flat-button color="primary" (click)="confirm()">Open</button>
        </mat-dialog-actions>
    `,
})
export class PipelineOpenDialog {
    private ref = inject(MatDialogRef<PipelineOpenDialog, string[]>);
    readonly data = inject<PipelineOpenData>(MAT_DIALOG_DATA);

    readonly query = signal('');
    readonly picked = signal<ReadonlySet<string>>(new Set(this.data.open));

    readonly filtered = computed<PipelineSummary[]>(() => {
        const q = this.query().trim().toLowerCase();
        if (!q) return this.data.pipelines;
        return this.data.pipelines.filter(
            (p) => p.name.toLowerCase().includes(q) || (p.displayName ?? '').toLowerCase().includes(q),
        );
    });

    onSearch(e: Event): void {
        this.query.set((e.target as HTMLInputElement).value);
    }

    toggle(name: string): void {
        this.picked.update((s) => {
            const next = new Set(s);
            if (!next.delete(name)) next.add(name);
            return next;
        });
    }

    /** Returns the full desired open set, in the listed order so tabs are stable across re-opens. */
    confirm(): void {
        const picked = this.picked();
        this.ref.close(this.data.pipelines.filter((p) => picked.has(p.name)).map((p) => p.name));
    }
}
